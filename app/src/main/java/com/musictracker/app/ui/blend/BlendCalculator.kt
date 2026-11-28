package com.musictracker.app.ui.blend

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Encodes/decodes compressed `COLLECTO1:` blend codes, computes weighted taste compatibility,
 * and generates ZXing QR bitmaps without any external backend.
 */
object BlendCalculator {

    const val PREFIX = "COLLECTO1:"
    const val MAX_DECOMPRESSED_BYTES = 64 * 1024
    const val MAX_ARTISTS = 15
    const val MAX_TRACKS = 10

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun fromStats(
        topArtists: List<TopArtistStat>,
        topTracks: List<TopTrackStat>,
        listenerName: String = "Collecto Listener"
    ): BlendPayload {
        val artists = topArtists
            .filter { it.artist.isNotBlank() }
            .take(MAX_ARTISTS)
            .map { BlendArtistEntry(name = it.artist.trim(), plays = max(1, it.playCount)) }
        val tracks = topTracks
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .take(MAX_TRACKS)
            .map {
                BlendTrackEntry(
                    title = it.title.trim(),
                    artist = it.artist.trim(),
                    plays = max(1, it.playCount)
                )
            }
        return BlendPayload(
            v = 1,
            name = listenerName.trim().ifBlank { "Collecto Listener" },
            artists = artists,
            tracks = tracks
        )
    }

    fun encode(payload: BlendPayload): String {
        val rawBytes = json.encodeToString(BlendPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val compressed = deflate(rawBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$PREFIX$encoded"
    }

    fun decode(code: String): Result<BlendPayload> = runCatching {
        val trimmed = code.trim()
        require(trimmed.startsWith(PREFIX)) {
            "Code must start with $PREFIX"
        }
        val encoded = trimmed.removePrefix(PREFIX).trim()
        require(encoded.isNotEmpty()) {
            "Blend code is empty"
        }
        val compressed = Base64.getUrlDecoder().decode(encoded)
        val rawBytes = inflateWithGuard(compressed, MAX_DECOMPRESSED_BYTES)
        val jsonText = rawBytes.toString(Charsets.UTF_8)
        json.decodeFromString(BlendPayload.serializer(), jsonText)
    }

    fun compare(mine: BlendPayload, friend: BlendPayload): BlendComparisonResult {
        val myArtistWeights = mutableMapOf<String, Double>()
        val artistDisplayNames = mutableMapOf<String, String>()
        mine.artists.forEach { entry ->
            val key = normalizeKey(entry.name)
            if (key.isNotEmpty()) {
                myArtistWeights[key] = (myArtistWeights[key] ?: 0.0) + max(1, entry.plays).toDouble()
                artistDisplayNames.putIfAbsent(key, entry.name.trim())
            }
        }

        val friendArtistWeights = mutableMapOf<String, Double>()
        friend.artists.forEach { entry ->
            val key = normalizeKey(entry.name)
            if (key.isNotEmpty()) {
                friendArtistWeights[key] =
                    (friendArtistWeights[key] ?: 0.0) + max(1, entry.plays).toDouble()
                artistDisplayNames.putIfAbsent(key, entry.name.trim())
            }
        }

        val myTrackWeights = mutableMapOf<String, Double>()
        val trackDisplayNames = mutableMapOf<String, String>()
        mine.tracks.forEach { entry ->
            val key = trackKey(entry.artist, entry.title)
            if (key.isNotEmpty()) {
                myTrackWeights[key] = (myTrackWeights[key] ?: 0.0) + max(1, entry.plays).toDouble()
                trackDisplayNames.putIfAbsent(key, "${entry.title.trim()} — ${entry.artist.trim()}")
            }
        }

        val friendTrackWeights = mutableMapOf<String, Double>()
        friend.tracks.forEach { entry ->
            val key = trackKey(entry.artist, entry.title)
            if (key.isNotEmpty()) {
                friendTrackWeights[key] =
                    (friendTrackWeights[key] ?: 0.0) + max(1, entry.plays).toDouble()
                trackDisplayNames.putIfAbsent(key, "${entry.title.trim()} — ${entry.artist.trim()}")
            }
        }

        val hasArtists = myArtistWeights.isNotEmpty() || friendArtistWeights.isNotEmpty()
        val hasTracks = myTrackWeights.isNotEmpty() || friendTrackWeights.isNotEmpty()

        val artistSimilarity = weightedCosineSimilarity(myArtistWeights, friendArtistWeights)
        val trackSimilarity = weightedCosineSimilarity(myTrackWeights, friendTrackWeights)

        val combinedSimilarity = when {
            hasArtists && hasTracks -> artistSimilarity * 0.65 + trackSimilarity * 0.35
            hasArtists -> artistSimilarity
            hasTracks -> trackSimilarity
            else -> 0.0
        }

        val matchPercent = (combinedSimilarity * 100.0).roundToInt().coerceIn(0, 100)

        val sharedArtists = myArtistWeights.keys
            .intersect(friendArtistWeights.keys)
            .sortedByDescending { key ->
                (myArtistWeights[key] ?: 0.0) + (friendArtistWeights[key] ?: 0.0)
            }
            .mapNotNull { artistDisplayNames[it] }

        val sharedTracks = myTrackWeights.keys
            .intersect(friendTrackWeights.keys)
            .sortedByDescending { key ->
                (myTrackWeights[key] ?: 0.0) + (friendTrackWeights[key] ?: 0.0)
            }
            .mapNotNull { trackDisplayNames[it] }

        val myTopArtist = mine.artists.maxByOrNull { it.plays }?.name?.trim()?.ifBlank { null }
        val friendTopArtist = friend.artists.maxByOrNull { it.plays }?.name?.trim()?.ifBlank { null }

        return BlendComparisonResult(
            matchPercent = matchPercent,
            sharedArtists = sharedArtists,
            sharedTracks = sharedTracks,
            myTopArtist = myTopArtist,
            friendTopArtist = friendTopArtist,
            vibeLabel = vibeLabelFor(matchPercent)
        )
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val safeSize = sizePx.coerceAtLeast(128)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            safeSize,
            safeSize,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun weightedCosineSimilarity(
        left: Map<String, Double>,
        right: Map<String, Double>
    ): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var dot = 0.0
        var leftNormSq = 0.0
        var rightNormSq = 0.0
        left.forEach { (key, weight) ->
            leftNormSq += weight * weight
            val other = right[key]
            if (other != null) {
                dot += weight * other
            }
        }
        right.values.forEach { weight ->
            rightNormSq += weight * weight
        }
        val denom = sqrt(leftNormSq) * sqrt(rightNormSq)
        return if (denom > 0.0) (dot / denom).coerceIn(0.0, 1.0) else 0.0
    }

    private fun normalizeKey(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun trackKey(artist: String, title: String): String {
        val a = normalizeKey(artist)
        val t = normalizeKey(title)
        return if (a.isEmpty() && t.isEmpty()) "" else "$a::$t"
    }

    private fun vibeLabelFor(matchPercent: Int): String = when {
        matchPercent >= 85 -> "Sonic Soulmates"
        matchPercent >= 65 -> "Same Wavelength"
        matchPercent >= 45 -> "Balanced Blend"
        matchPercent >= 25 -> "Eclectic Contrast"
        else -> "Worlds Apart"
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(2048)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateWithGuard(compressed: ByteArray, maxBytes: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var totalRead = 0
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break
                    }
                } else {
                    totalRead += count
                    if (totalRead > maxBytes) {
                        throw IllegalArgumentException(
                            "Blend code payload exceeds maximum allowed size ($maxBytes bytes)"
                        )
                    }
                    out.write(buffer, 0, count)
                }
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
