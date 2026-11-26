package com.musictracker.app.data.artwork

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

class ArtworkRepository(
    private val cacheManager: ArtworkCacheManager,
    private val onlineFetcher: OnlineArtworkFetcher,
    private val onlineArtworkEnabledFlow: Flow<Boolean>,
    private val contentResolver: ContentResolver? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _artworkRevision = MutableStateFlow(0)
    val artworkRevision: StateFlow<Int> = _artworkRevision.asStateFlow()

    fun getCachedTrackArtworkFile(dedupKey: String): File? =
        cacheManager.getTrackFile(dedupKey)

    fun getCachedArtistArtworkFile(artistKey: String): File? =
        cacheManager.getArtistFile(artistKey)

    suspend fun loadCachedTrackBitmap(dedupKey: String): Bitmap? = withContext(ioDispatcher) {
        cacheManager.getCachedTrackBitmap(dedupKey)
    }

    suspend fun loadCachedArtistBitmap(artistKey: String): Bitmap? = withContext(ioDispatcher) {
        cacheManager.getCachedArtistBitmap(artistKey)
    }

    suspend fun saveTrackArtworkBitmap(dedupKey: String, bitmap: Bitmap): File? =
        withContext(ioDispatcher) {
            val saved = cacheManager.saveTrackBitmap(dedupKey, bitmap)
            if (saved != null) {
                notifyArtworkUpdated()
            }
            saved
        }

    suspend fun resolveTrackArtwork(
        dedupKey: String,
        artist: String,
        title: String,
        rawArtworkUri: String?
    ): File? {
        if (dedupKey.isBlank()) return null

        // 1. Offline-first: return local WebP cache immediately if present.
        cacheManager.getTrackFile(dedupKey)?.let { return it }

        val cacheKey = ArtworkCacheManager.trackCacheKey(dedupKey)
        return cacheManager.getOrFetch(cacheKey) {
            withContext(ioDispatcher) {
                cacheManager.getTrackFile(dedupKey)?.let { return@withContext it }

                // 2. Try raw artwork URI from MediaMetadata (local file, content://, or http/https).
                val rawTrimmed = rawArtworkUri?.trim().orEmpty()
                if (rawTrimmed.isNotEmpty()) {
                    val fromRaw = fetchFromRawUri(dedupKey = dedupKey, rawUri = rawTrimmed)
                    if (fromRaw != null) {
                        return@withContext fromRaw
                    }
                }

                // 3. Online Deezer / iTunes fallback when enabled and not negatively cached.
                if (!isOnlineArtworkEnabled() || cacheManager.isNegativelyCached(cacheKey)) {
                    return@withContext null
                }

                val fetchedBytes = onlineFetcher.fetchTrackCoverBytes(
                    artist = artist,
                    title = title
                )
                if (fetchedBytes != null) {
                    val saved = cacheManager.saveTrackBytes(dedupKey, fetchedBytes)
                    if (saved != null) {
                        notifyArtworkUpdated()
                        return@withContext saved
                    }
                }

                cacheManager.recordNegativeMiss(cacheKey)
                null
            }
        }
    }

    suspend fun resolveArtistArtwork(
        artistKey: String,
        artistName: String,
        fallbackTrackDedupKey: String? = null
    ): File? {
        if (artistKey.isBlank()) {
            return fallbackTrackDedupKey?.let { cacheManager.getTrackFile(it) }
        }

        // 1. Offline-first: return local WebP artist portrait immediately if present.
        cacheManager.getArtistFile(artistKey)?.let { return it }

        val cacheKey = ArtworkCacheManager.artistCacheKey(artistKey)
        val fetched = cacheManager.getOrFetch(cacheKey) {
            withContext(ioDispatcher) {
                cacheManager.getArtistFile(artistKey)?.let { return@withContext it }

                // 2. Online Deezer artist portrait when enabled and not negatively cached.
                if (!isOnlineArtworkEnabled() || cacheManager.isNegativelyCached(cacheKey)) {
                    return@withContext null
                }

                val fetchedBytes = onlineFetcher.fetchArtistPhotoBytes(artistName)
                if (fetchedBytes != null) {
                    val saved = cacheManager.saveArtistBytes(artistKey, fetchedBytes)
                    if (saved != null) {
                        notifyArtworkUpdated()
                        return@withContext saved
                    }
                }

                cacheManager.recordNegativeMiss(cacheKey)
                null
            }
        }

        if (fetched != null) {
            return fetched
        }

        // 3. Fallback to cached track album art for this artist if available.
        return fallbackTrackDedupKey
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheManager.getTrackFile(it) }
    }

    private suspend fun fetchFromRawUri(dedupKey: String, rawUri: String): File? {
        return when {
            rawUri.startsWith("/") || rawUri.startsWith("file://", ignoreCase = true) -> {
                val localFile = resolveLocalFile(rawUri)
                if (localFile != null && localFile.isFile && localFile.length() > 0L) {
                    val bytes = runCatching { localFile.readBytes() }.getOrNull()
                    val saved = bytes?.let { cacheManager.saveTrackBytes(dedupKey, it) }
                    if (saved != null) {
                        notifyArtworkUpdated()
                        saved
                    } else {
                        localFile
                    }
                } else {
                    null
                }
            }

            rawUri.startsWith("content://", ignoreCase = true) -> {
                val resolver = contentResolver ?: return null
                val bytes = runCatching {
                    resolver.openInputStream(Uri.parse(rawUri))?.use { it.readBytes() }
                }.getOrNull() ?: return null
                val saved = cacheManager.saveTrackBytes(dedupKey, bytes)
                if (saved != null) {
                    notifyArtworkUpdated()
                }
                saved
            }

            rawUri.startsWith("http://", ignoreCase = true) ||
                rawUri.startsWith("https://", ignoreCase = true) -> {
                if (!isOnlineArtworkEnabled()) return null
                val bytes = onlineFetcher.downloadImageBytes(rawUri) ?: return null
                val saved = cacheManager.saveTrackBytes(dedupKey, bytes)
                if (saved != null) {
                    notifyArtworkUpdated()
                }
                saved
            }

            else -> null
        }
    }

    private fun resolveLocalFile(rawUri: String): File? {
        return if (rawUri.startsWith("file://", ignoreCase = true)) {
            val parsedPath = runCatching { Uri.parse(rawUri).path }.getOrNull()
            val path = parsedPath ?: rawUri.removePrefix("file://")
            if (path.isNotBlank()) File(path) else null
        } else {
            File(rawUri)
        }
    }

    private suspend fun isOnlineArtworkEnabled(): Boolean {
        return try {
            onlineArtworkEnabledFlow.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            true
        }
    }

    private fun notifyArtworkUpdated() {
        _artworkRevision.update { it + 1 }
    }
}
