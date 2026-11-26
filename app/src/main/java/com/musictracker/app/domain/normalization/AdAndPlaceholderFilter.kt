package com.musictracker.app.domain.normalization

import com.musictracker.app.domain.model.MediaMetadataSnapshot
import java.util.Locale

/**
 * Pure-Kotlin, context-aware detector for streaming audio advertisements and non-song
 * media placeholders (e.g., Spotify/YouTube Music/SoundCloud ads, "Recommended for you").
 *
 * Uses exact matching and word-boundary + artist-context rules so genuine songs like
 * "Ad Astra" by "Max Richter" or "Advertisement for Myself" by "Some Band" are never
 * false-positived.
 */
object AdAndPlaceholderFilter {

    private val GENERIC_OR_PLATFORM_ARTISTS: Set<String> = setOf(
        "",
        "unknown",
        "unknown artist",
        "various",
        "various artists",
        "<unknown>",
        "artist",
        "recommended for you",
        "null",
        "n/a",
        "none",
        "spotify",
        "youtube",
        "youtube music",
        "soundcloud",
        "pandora",
        "deezer",
        "tidal",
        "apple music",
        "amazon music",
        "google"
    )

    private val EXACT_AD_OR_PLACEHOLDER_TITLES: Set<String> = setOf(
        "advertisement",
        "audio ad",
        "sponsored",
        "sponsored content",
        "sponsored audio",
        "recommended for you"
    )

    private val CONTEXTUAL_AD_TITLES: Set<String> = setOf(
        "ad",
        "commercial",
        "promo",
        "promotion",
        "spotify",
        "youtube",
        "youtube music",
        "soundcloud",
        "untitled",
        "up next",
        "loading",
        "loading...",
        "buffering",
        "buffering..."
    )

    private val EXACT_AD_ALBUMS: Set<String> = setOf(
        "advertisement",
        "audio ad",
        "sponsored",
        "commercial"
    )

    private val PLACEHOLDER_SUBTITLES: Set<String> = setOf(
        "recommended for you",
        "up next",
        "advertisement",
        "audio ad",
        "sponsored"
    )

    private val WORD_BOUNDARY_AD_PHRASE_REGEX =
        Regex("\\b(?:advertisement|audio\\s+ad|sponsored)\\b", RegexOption.IGNORE_CASE)

    private val CONTEXTUAL_AD_COUNT_REGEX =
        Regex("\\bad\\s*(?:[·•|:-]\\s*)?\\d+\\s*(?:of|/)\\s*\\d+\\b", RegexOption.IGNORE_CASE)

    fun isAdOrPlaceholder(metadata: MediaMetadataSnapshot): Boolean =
        isAdOrPlaceholder(
            rawTitle = metadata.title,
            rawArtist = metadata.artist,
            album = metadata.album
        )

    fun isAdOrPlaceholder(
        rawTitle: String,
        rawArtist: String,
        album: String = ""
    ): Boolean {
        val normalizedTitle = MusicMetadataNormalizer.collapseWhitespace(rawTitle)
            .lowercase(Locale.ROOT)
        if (normalizedTitle.isEmpty()) return true

        val normalizedArtist = MusicMetadataNormalizer.collapseWhitespace(rawArtist)
            .lowercase(Locale.ROOT)
        val normalizedAlbum = MusicMetadataNormalizer.collapseWhitespace(album)
            .lowercase(Locale.ROOT)

        if (normalizedTitle in EXACT_AD_OR_PLACEHOLDER_TITLES) {
            return true
        }

        if (normalizedAlbum in EXACT_AD_ALBUMS) {
            return true
        }

        val genericOrPlatformArtist = isBlankOrGenericOrPlatformArtist(normalizedArtist)

        if (normalizedTitle in CONTEXTUAL_AD_TITLES && genericOrPlatformArtist) {
            return true
        }

        if (
            genericOrPlatformArtist &&
            (WORD_BOUNDARY_AD_PHRASE_REGEX.containsMatchIn(normalizedTitle) ||
                CONTEXTUAL_AD_COUNT_REGEX.containsMatchIn(normalizedTitle))
        ) {
            return true
        }

        return false
    }

    fun isPlaceholderSubtitle(subtitle: String?): Boolean {
        if (subtitle.isNullOrBlank()) return true
        val normalized = MusicMetadataNormalizer.collapseWhitespace(subtitle)
            .lowercase(Locale.ROOT)
        return normalized in PLACEHOLDER_SUBTITLES
    }

    private fun isBlankOrGenericOrPlatformArtist(normalizedArtist: String): Boolean =
        normalizedArtist in GENERIC_OR_PLATFORM_ARTISTS
}
