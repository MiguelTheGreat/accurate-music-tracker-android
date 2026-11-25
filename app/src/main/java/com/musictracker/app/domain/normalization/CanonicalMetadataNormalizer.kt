package com.musictracker.app.domain.normalization

import java.util.Locale

class CanonicalMetadataNormalizer : MetadataNormalizer {

    override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack {
        val cleanedTitle = collapseWhitespace(rawTitle)
        val cleanedArtist = collapseWhitespace(rawArtist).ifEmpty { UNKNOWN_ARTIST }
        return NormalizedTrack(
            normalizedTitle = cleanedTitle,
            normalizedArtist = cleanedArtist,
            dedupKey = buildDedupKey(cleanedArtist, cleanedTitle)
        )
    }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

        fun collapseWhitespace(value: String): String =
            value.trim().replace(WHITESPACE_REGEX, " ")

        fun buildDedupKey(normalizedArtist: String, normalizedTitle: String): String =
            "${canonicalSegment(normalizedArtist)}::${canonicalSegment(normalizedTitle)}"

        private fun canonicalSegment(value: String): String =
            value.lowercase(Locale.ROOT).replace(NON_ALNUM_REGEX, " ").trim()
    }
}
