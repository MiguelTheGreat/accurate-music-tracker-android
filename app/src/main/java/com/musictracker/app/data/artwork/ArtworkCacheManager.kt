package com.musictracker.app.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

class ArtworkCacheManager(
    cacheRootDir: File,
    private val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    private val webpQuality: Int = DEFAULT_WEBP_QUALITY,
    private val negativeCacheTtlMs: Long = DEFAULT_NEGATIVE_CACHE_TTL_MS,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    memoryCacheKb: Int = DEFAULT_MEMORY_CACHE_KB
) {
    constructor(
        context: Context,
        maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
        webpQuality: Int = DEFAULT_WEBP_QUALITY,
        negativeCacheTtlMs: Long = DEFAULT_NEGATIVE_CACHE_TTL_MS,
        nowMsProvider: () -> Long = { System.currentTimeMillis() },
        memoryCacheKb: Int = DEFAULT_MEMORY_CACHE_KB
    ) : this(
        cacheRootDir = File(context.filesDir, ARTWORK_DIR_NAME),
        maxDimensionPx = maxDimensionPx,
        webpQuality = webpQuality,
        negativeCacheTtlMs = negativeCacheTtlMs,
        nowMsProvider = nowMsProvider,
        memoryCacheKb = memoryCacheKb
    )

    private val tracksDir = File(cacheRootDir, TRACKS_SUBDIR)
    private val artistsDir = File(cacheRootDir, ARTISTS_SUBDIR)

    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheKb.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            val kb = value.allocationByteCount / 1024
            return kb.coerceAtLeast(1)
        }
    }

    private val inFlightFetches = ConcurrentHashMap<String, Deferred<File?>>()
    private val negativeMissTimestamps = ConcurrentHashMap<String, Long>()

    fun getTrackFile(dedupKey: String): File? {
        if (dedupKey.isBlank()) return null
        val file = trackTargetFile(dedupKey)
        return if (file.isFile && file.length() > 0L) file else null
    }

    fun getArtistFile(artistKey: String): File? {
        if (artistKey.isBlank()) return null
        val file = artistTargetFile(artistKey)
        return if (file.isFile && file.length() > 0L) file else null
    }

    fun getCachedTrackBitmap(dedupKey: String): Bitmap? {
        if (dedupKey.isBlank()) return null
        return getOrLoadBitmap(trackCacheKey(dedupKey), getTrackFile(dedupKey))
    }

    fun getCachedArtistBitmap(artistKey: String): Bitmap? {
        if (artistKey.isBlank()) return null
        return getOrLoadBitmap(artistCacheKey(artistKey), getArtistFile(artistKey))
    }

    fun saveTrackBitmap(dedupKey: String, bitmap: Bitmap): File? {
        if (dedupKey.isBlank() || bitmap.isRecycled) return null
        val target = trackTargetFile(dedupKey)
        val cacheKey = trackCacheKey(dedupKey)
        return saveBitmapInternal(cacheKey, target, bitmap)
    }

    fun saveArtistBitmap(artistKey: String, bitmap: Bitmap): File? {
        if (artistKey.isBlank() || bitmap.isRecycled) return null
        val target = artistTargetFile(artistKey)
        val cacheKey = artistCacheKey(artistKey)
        return saveBitmapInternal(cacheKey, target, bitmap)
    }

    fun saveTrackBytes(dedupKey: String, bytes: ByteArray): File? {
        if (dedupKey.isBlank() || bytes.isEmpty()) return null
        val decoded = decodeSampledBitmap(bytes) ?: return null
        return saveTrackBitmap(dedupKey, decoded)
    }

    fun saveArtistBytes(artistKey: String, bytes: ByteArray): File? {
        if (artistKey.isBlank() || bytes.isEmpty()) return null
        val decoded = decodeSampledBitmap(bytes) ?: return null
        return saveArtistBitmap(artistKey, decoded)
    }

    fun isNegativelyCached(cacheKey: String): Boolean {
        val recordedAt = negativeMissTimestamps[cacheKey] ?: return false
        val age = nowMsProvider() - recordedAt
        if (age < 0L || age >= negativeCacheTtlMs) {
            negativeMissTimestamps.remove(cacheKey, recordedAt)
            return false
        }
        return true
    }

    fun recordNegativeMiss(cacheKey: String) {
        negativeMissTimestamps[cacheKey] = nowMsProvider()
    }

    fun clearNegativeMiss(cacheKey: String) {
        negativeMissTimestamps.remove(cacheKey)
    }

    suspend fun getOrFetch(
        cacheKey: String,
        fetcher: suspend () -> File?
    ): File? {
        val newDeferred = CompletableDeferred<File?>()
        val existing = inFlightFetches.putIfAbsent(cacheKey, newDeferred)
        if (existing != null) {
            return existing.await()
        }
        return try {
            val result = fetcher()
            newDeferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            newDeferred.complete(null)
            throw cancelled
        } catch (_: Throwable) {
            newDeferred.complete(null)
            null
        } finally {
            inFlightFetches.remove(cacheKey, newDeferred)
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    private fun getOrLoadBitmap(cacheKey: String, file: File?): Bitmap? {
        val inMemory = memoryCache.get(cacheKey)
        if (inMemory != null && !inMemory.isRecycled) {
            return inMemory
        }
        if (file == null || !file.isFile) return null
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        memoryCache.put(cacheKey, decoded)
        return decoded
    }

    private fun saveBitmapInternal(cacheKey: String, target: File, source: Bitmap): File? {
        val scaled = downscaleIfNeeded(source, maxDimensionPx)
        val parentDir = target.parentFile ?: return null
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            return null
        }

        val tmpFile = File(parentDir, "${target.name}.${Thread.currentThread().id}.tmp")
        return try {
            val format = resolveWebpFormat()
            val compressed = FileOutputStream(tmpFile).use { output ->
                scaled.compress(format, webpQuality, output)
            }
            if (!compressed || !tmpFile.exists() || tmpFile.length() == 0L) {
                tmpFile.delete()
                return null
            }

            if (!tmpFile.renameTo(target)) {
                if (target.exists()) {
                    target.delete()
                }
                if (!tmpFile.renameTo(target)) {
                    tmpFile.delete()
                    return null
                }
            }

            memoryCache.put(cacheKey, scaled)
            negativeMissTimestamps.remove(cacheKey)
            target
        } catch (_: Throwable) {
            runCatching { tmpFile.delete() }
            null
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDim = maxDimensionPx
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while ((width / (sampleSize * 2)) >= maxDim && (height / (sampleSize * 2)) >= maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun downscaleIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxDim && h <= maxDim) {
            return source
        }
        val scale = min(maxDim.toFloat() / w.toFloat(), maxDim.toFloat() / h.toFloat())
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun resolveWebpFormat(): Bitmap.CompressFormat {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }

    private fun trackTargetFile(dedupKey: String): File =
        File(tracksDir, "${sha256Hex(dedupKey)}.webp")

    private fun artistTargetFile(artistKey: String): File =
        File(artistsDir, "${sha256Hex(artistKey)}.webp")

    companion object {
        const val ARTWORK_DIR_NAME = "artwork"
        const val TRACKS_SUBDIR = "tracks"
        const val ARTISTS_SUBDIR = "artists"
        const val DEFAULT_MAX_DIMENSION_PX = 600
        const val DEFAULT_WEBP_QUALITY = 82
        const val DEFAULT_NEGATIVE_CACHE_TTL_MS = 10L * 60L * 1000L
        const val DEFAULT_MEMORY_CACHE_KB = 16 * 1024

        fun trackCacheKey(dedupKey: String): String = "track:$dedupKey"
        fun artistCacheKey(artistKey: String): String = "artist:$artistKey"

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(hashBytes.size * 2)
            for (b in hashBytes) {
                val value = b.toInt() and 0xFF
                sb.append(HEX_CHARS[value ushr 4])
                sb.append(HEX_CHARS[value and 0x0F])
            }
            return sb.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
