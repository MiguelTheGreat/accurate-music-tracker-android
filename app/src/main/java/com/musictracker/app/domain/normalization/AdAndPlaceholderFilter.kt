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

    private val EXACT_AD_ARTISTS: Set<String> = setOf(
        "advertisement",
        "audio ad",
        "video ad",
        "sponsored",
        "sponsored content",
        "sponsored audio",
        "sponsored message",
        "ad break",
        "commercial break",
        "paid promotion",
        "paid partnership"
    )

    private val EXACT_AD_OR_PLACEHOLDER_TITLES: Set<String> = setOf(
        "advertisement",
        "audio ad",
        "video ad",
        "sponsored",
        "sponsored content",
        "sponsored audio",
        "sponsored message",
        "ad break",
        "commercial break",
        "paid promotion",
        "paid partnership",
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
        "pandora",
        "deezer",
        "tidal",
        "apple music",
        "amazon music",
        "skip ad",
        "skip ads",
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
        Regex(
            "\\b(?:advertisement|audio\\s+ad|video\\s+ad|sponsored|ad\\s+break|commercial\\s+break|paid\\s+promotion|ad[\\s-]free\\s+(?:music|listening))\\b",
            RegexOption.IGNORE_CASE
        )

    private val AD_COUNT_REGEX =
        Regex(
            "\\b(?:ad|ads|advertisement|audio\\s+ad|sponsored)\\s*(?:[·•∙●‧|:\\-–—(]\\s*)?\\d+\\s*(?:of|/)\\s*\\d+\\b",
            RegexOption.IGNORE_CASE
        )

    private val AD_TIMER_REGEX =
        Regex(
            "\\b(?:ad|advertisement|audio\\s+ad|sponsored)\\s*(?:[·•∙●‧|:\\-–—(]\\s*)?\\d{1,2}:\\d{2}\\b",
            RegexOption.IGNORE_CASE
        )

    private val PLAY_AFTER_AD_REGEX =
        Regex(
            "\\b(?:(?:video|music|song|audio|track)\\s+will\\s+play\\s+after\\s+(?:(?:this|the|a)\\s+)?ads?|(?:your\\s+)?(?:music|video|song|audio|track)\\s+will\\s+resume\\s+after\\s+(?:(?:this|the|a)\\s+)?(?:short\\s+)?(?:break|ad|message))\\b",
            RegexOption.IGNORE_CASE
        )

    private val AD_LABEL_PREFIX_REGEX =
        Regex(
            "^(?:(?:advertisement|audio\\s+ad|video\\s+ad|sponsored)(?:\\s*[·•∙●‧|:\\-–—]|\\s*$)|sponsored\\s+by\\b)",
            RegexOption.IGNORE_CASE
        )

    fun isAdOrPlaceholder(metadata: MediaMetadataSnapshot): Boolean =
        metadata.isAdvertisement ||
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
        val normalizedTitle = normalizeField(rawTitle)
        if (normalizedTitle.isEmpty()) return true

        val normalizedArtist = normalizeField(rawArtist)
        val normalizedAlbum = normalizeField(album)

        if (normalizedTitle in EXACT_AD_OR_PLACEHOLDER_TITLES) {
            return true
        }

        if (normalizedAlbum in EXACT_AD_ALBUMS) {
            return true
        }

        if (isAdLabelNormalized(normalizedArtist) || isAdLabelNormalized(normalizedAlbum)) {
            return true
        }

        if (
            AD_COUNT_REGEX.containsMatchIn(normalizedTitle) ||
            AD_TIMER_REGEX.containsMatchIn(normalizedTitle) ||
            PLAY_AFTER_AD_REGEX.containsMatchIn(normalizedTitle)
        ) {
            return true
        }

        val genericOrPlatformArtist = isBlankOrGenericOrPlatformArtist(normalizedArtist)

        if (normalizedTitle in CONTEXTUAL_AD_TITLES && genericOrPlatformArtist) {
            return true
        }

        if (genericOrPlatformArtist && WORD_BOUNDARY_AD_PHRASE_REGEX.containsMatchIn(normalizedTitle)) {
            return true
        }

        return false
    }

    fun isAdLabel(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return isAdLabelNormalized(normalizeField(value))
    }

    fun isPlaceholderSubtitle(subtitle: String?): Boolean {
        if (subtitle.isNullOrBlank()) return true
        return normalizeField(subtitle) in PLACEHOLDER_SUBTITLES
    }

    private fun normalizeField(value: String): String =
        MusicMetadataNormalizer.collapseWhitespace(value.replace("\u00AD", ""))
            .lowercase(Locale.ROOT)

    private fun isAdLabelNormalized(normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        return normalized in EXACT_AD_ARTISTS ||
            AD_COUNT_REGEX.containsMatchIn(normalized) ||
            AD_TIMER_REGEX.containsMatchIn(normalized) ||
            AD_LABEL_PREFIX_REGEX.containsMatchIn(normalized) ||
            PLAY_AFTER_AD_REGEX.containsMatchIn(normalized)
    }

    private fun isBlankOrGenericOrPlatformArtist(normalizedArtist: String): Boolean =
        normalizedArtist in GENERIC_OR_PLATFORM_ARTISTS
}
