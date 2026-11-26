package com.musictracker.app.data.artwork

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class OnlineArtworkFetcher(
    private val httpClient: ArtworkHttpClient
) {
    suspend fun fetchArtistPhotoBytes(artistName: String): ByteArray? {
        val url = findArtistPhotoUrl(artistName) ?: return null
        return httpClient.getBytes(url)
    }

    suspend fun findArtistPhotoUrl(artistName: String): String? {
        val cleanArtist = artistName.trim()
        if (cleanArtist.isEmpty() || cleanArtist.lowercase(Locale.ROOT) in GENERIC_ARTISTS) {
            return null
        }

        val encoded = encodeQuery(cleanArtist)
        val jsonText = httpClient.getText("$DEEZER_ARTIST_SEARCH_URL?q=$encoded&limit=1")
            ?: return null

        return parseDeezerArtistImageUrl(jsonText)
    }

    suspend fun fetchTrackCoverBytes(artist: String, title: String): ByteArray? {
        val url = findTrackCoverUrl(artist = artist, title = title) ?: return null
        return httpClient.getBytes(url)
    }

    suspend fun findTrackCoverUrl(artist: String, title: String): String? {
        val query = buildTrackQuery(artist = artist, title = title) ?: return null
        val encoded = encodeQuery(query)

        val deezerJson = httpClient.getText("$DEEZER_TRACK_SEARCH_URL?q=$encoded&limit=1")
        val deezerUrl = deezerJson?.let(::parseDeezerTrackCoverUrl)
        if (deezerUrl != null) {
            return deezerUrl
        }

        val itunesJson = httpClient.getText(
            "$ITUNES_TRACK_SEARCH_URL?term=$encoded&entity=song&limit=1"
        )
        return itunesJson?.let(::parseItunesTrackCoverUrl)
    }

    suspend fun downloadImageBytes(url: String): ByteArray? {
        val trimmed = url.trim()
        if (!isHttpUrl(trimmed)) return null
        return httpClient.getBytes(trimmed)
    }

    internal fun parseDeezerArtistImageUrl(jsonText: String): String? {
        return runCatching {
            val root = JSONObject(jsonText)
            val data = root.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val first = data.optJSONObject(0) ?: return null
            firstValidHttpUrl(
                first.optString("picture_xl"),
                first.optString("picture_big"),
                first.optString("picture_medium")
            )
        }.getOrNull()
    }

    internal fun parseDeezerTrackCoverUrl(jsonText: String): String? {
        return runCatching {
            val root = JSONObject(jsonText)
            val data = root.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val first = data.optJSONObject(0) ?: return null
            val album = first.optJSONObject("album") ?: return null
            firstValidHttpUrl(
                album.optString("cover_xl"),
                album.optString("cover_big"),
                album.optString("cover_medium")
            )
        }.getOrNull()
    }

    internal fun parseItunesTrackCoverUrl(jsonText: String): String? {
        return runCatching {
            val root = JSONObject(jsonText)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.optJSONObject(0) ?: return null
            val rawUrl = first.optString("artworkUrl100").trim()
            if (!isHttpUrl(rawUrl)) return null
            rawUrl.replace("100x100bb", "600x600bb")
        }.getOrNull()
    }

    private fun buildTrackQuery(artist: String, title: String): String? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val useArtist = cleanArtist.isNotEmpty() &&
            cleanArtist.lowercase(Locale.ROOT) !in GENERIC_ARTISTS

        val query = when {
            useArtist && cleanTitle.isNotEmpty() -> "$cleanArtist $cleanTitle"
            cleanTitle.isNotEmpty() -> cleanTitle
            useArtist -> cleanArtist
            else -> ""
        }
        return query.ifBlank { null }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun firstValidHttpUrl(vararg candidates: String?): String? {
        for (candidate in candidates) {
            val trimmed = candidate?.trim().orEmpty()
            if (isHttpUrl(trimmed)) {
                return trimmed
            }
        }
        return null
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    companion object {
        private const val DEEZER_ARTIST_SEARCH_URL = "https://api.deezer.com/search/artist"
        private const val DEEZER_TRACK_SEARCH_URL = "https://api.deezer.com/search"
        private const val ITUNES_TRACK_SEARCH_URL = "https://itunes.apple.com/search"

        private val GENERIC_ARTISTS = setOf(
            "",
            "unknown",
            "unknown artist",
            "various",
            "various artists",
            "<unknown>",
            "artist"
        )
    }
}
