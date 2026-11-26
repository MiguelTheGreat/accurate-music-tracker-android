package com.musictracker.app.domain.normalization

import java.text.Normalizer
import java.util.Locale

class MusicMetadataNormalizer : MetadataNormalizer {

    override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack {
        val collapsedRawTitle = collapseWhitespace(rawTitle)
        val collapsedRawArtist = collapseWhitespace(rawArtist)

        var artist = stripArtistNoise(collapsedRawArtist)
        var title = stripTitleNoise(collapsedRawTitle, fallback = collapsedRawTitle)

        if (artist.lowercase(Locale.ROOT) in GENERIC_ARTISTS) {
            val parts = title.split(SPACED_DASH_REGEX, limit = 2)
            if (parts.size == 2) {
                val left = parts[0].trim()
                val right = parts[1].trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    artist = stripArtistNoise(left)
                    title = stripTitleNoise(right, fallback = right)
                }
            }
        }

        if (artist.isBlank()) {
            artist = CanonicalMetadataNormalizer.UNKNOWN_ARTIST
        }

        return NormalizedTrack(
            normalizedTitle = title,
            normalizedArtist = artist,
            dedupKey = buildDedupKey(artist, title)
        )
    }

    companion object {
        private const val MAX_STRIP_ITERATIONS = 10

        private val ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200D\\uFEFF]")
        private val CURLY_APOSTROPHE_REGEX = Regex("[\\u2018\\u2019\\u2032]")
        private val WHITESPACE_REGEX = Regex("[\\s\\p{Z}]+")
        private val SPACED_DASH_REGEX = Regex(" [-–—] ")
        private val EMPTY_BRACKETS_REGEX = Regex("\\s*(?:\\(\\s*\\)|\\[\\s*\\])")
        private val TRAILING_SEPARATORS_REGEX = Regex("\\s+[-–—|]+\\s*$")

        private val ARTIST_TOPIC_SUFFIX_REGEX = Regex("(?i)\\s*[-–—]\\s*Topic$")
        private val ARTIST_DASH_OFFICIAL_CHANNEL_REGEX =
            Regex("(?i)\\s*[-–—]\\s*Official\\s+(?:Music|Channel|Artist|Audio)$")
        private val ARTIST_SPACE_OFFICIAL_CHANNEL_REGEX =
            Regex("(?i)\\s+Official\\s+(?:Music|Channel|Artist|Audio)$")
        private val ARTIST_BRACKET_OFFICIAL_CHANNEL_REGEX =
            Regex("(?i)\\s*(?:\\(\\s*Official\\s+(?:Music|Channel|Artist|Audio)\\s*\\)|\\[\\s*Official\\s+(?:Music|Channel|Artist|Audio)\\s*\\])$")
        private val ARTIST_DASH_OFFICIAL_SUFFIX_REGEX = Regex("(?i)\\s*[-–—]\\s*Official$")
        private val ARTIST_SPACE_OFFICIAL_SUFFIX_REGEX = Regex("(?i)\\s+Official$")
        private val ARTIST_BRACKET_OFFICIAL_SUFFIX_REGEX =
            Regex("(?i)\\s*(?:\\(\\s*Official\\s*\\)|\\[\\s*Official\\s*\\])$")
        private val ARTIST_VEVO_SUFFIX_REGEX =
            Regex("(?i)(?:\\s*[-–—]\\s*VEVO|\\s+VEVO|\\s*\\(\\s*VEVO\\s*\\)|\\s*\\[\\s*VEVO\\s*\\])$")

        private const val TITLE_NOISE_INNER =
            "(?:" +
                "Official\\s+(?:Music\\s+|Lyric(?:s)?\\s+)?(?:Audio|Video|Visualizer|MV|M/V|HD|HQ|4K)(?:\\s+(?:Video|Audio|HD|HQ|4K))?" +
                "|Official\\s+HD\\s+(?:Music\\s+)?Video" +
                "|Official\\s+4K\\s+(?:Music\\s+)?Video" +
                "|Full\\s+Audio" +
                "|Music\\s+Video" +
                "|Lyric(?:s)?\\s+Video" +
                "|Lyrics?" +
                "|Audio" +
                "|Visualizer" +
                "|Explicit" +
                "|Clean" +
                "|HD(?:\\s+Audio|\\s+Video)?" +
                "|HQ(?:\\s+Audio)?" +
                "|4K(?:\\s+Video)?" +
                "|MV|M/V" +
            ")"

        private val TITLE_BRACKETED_NOISE_REGEX = Regex(
            "(?i)\\s*(?:\\(\\s*$TITLE_NOISE_INNER\\s*\\)|\\[\\s*$TITLE_NOISE_INNER\\s*\\])"
        )

        private val TITLE_TRAILING_PIPE_NOISE_REGEX = Regex(
            "(?i)\\s*\\|\\s*$TITLE_NOISE_INNER(?:\\s*[-–—|]\\s*.*)?$"
        )

        private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
        private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

        private val GENERIC_ARTISTS: Set<String> = setOf(
            "",
            "unknown",
            "unknown artist",
            "various",
            "various artists",
            "<unknown>",
            "artist",
            "youtube",
            "youtube music",
            "recommended for you",
            "spotify",
            "soundcloud",
            "apple music",
            "tidal",
            "deezer",
            "amazon music",
            "null",
            "n/a",
            "none"
        )

        fun collapseWhitespace(value: String): String =
            ZERO_WIDTH_REGEX.replace(value, "")
                .replace(CURLY_APOSTROPHE_REGEX, "'")
                .replace(WHITESPACE_REGEX, " ")
                .trim()

        fun buildDedupKey(normalizedArtist: String, normalizedTitle: String): String =
            "${canonicalSegment(normalizedArtist)}::${canonicalSegment(normalizedTitle)}"

        private fun stripArtistNoise(input: String): String {
            var current = collapseWhitespace(input)
            repeat(MAX_STRIP_ITERATIONS) {
                var next = current
                next = ARTIST_TOPIC_SUFFIX_REGEX.replace(next, "")
                next = ARTIST_DASH_OFFICIAL_CHANNEL_REGEX.replace(next, "")
                next = ARTIST_SPACE_OFFICIAL_CHANNEL_REGEX.replace(next, "")
                next = ARTIST_BRACKET_OFFICIAL_CHANNEL_REGEX.replace(next, "")
                next = ARTIST_DASH_OFFICIAL_SUFFIX_REGEX.replace(next, "")
                next = ARTIST_SPACE_OFFICIAL_SUFFIX_REGEX.replace(next, "")
                next = ARTIST_BRACKET_OFFICIAL_SUFFIX_REGEX.replace(next, "")
                next = ARTIST_VEVO_SUFFIX_REGEX.replace(next, "")
                if (next.length > 4 && next.endsWith("vevo", ignoreCase = true)) {
                    val prefix = next.substring(0, next.length - 4).trim()
                    if (prefix.isNotEmpty()) {
                        next = prefix
                    }
                }
                next = EMPTY_BRACKETS_REGEX.replace(next, "")
                next = TRAILING_SEPARATORS_REGEX.replace(next, "")
                next = collapseWhitespace(next)
                if (next == current) {
                    return current
                }
                current = next
            }
            return current
        }

        private fun stripTitleNoise(input: String, fallback: String): String {
            var current = collapseWhitespace(input)
            repeat(MAX_STRIP_ITERATIONS) {
                var next = current
                next = TITLE_BRACKETED_NOISE_REGEX.replace(next, "")
                next = TITLE_TRAILING_PIPE_NOISE_REGEX.replace(next, "")
                next = EMPTY_BRACKETS_REGEX.replace(next, "")
                next = TRAILING_SEPARATORS_REGEX.replace(next, "")
                next = collapseWhitespace(next)
                if (next == current) {
                    return current.ifEmpty { fallback }
                }
                current = next
            }
            return current.ifEmpty { fallback }
        }

        private fun canonicalSegment(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            val withoutDiacritics = COMBINING_MARKS_REGEX.replace(decomposed, "")
            val recomposed = Normalizer.normalize(withoutDiacritics, Normalizer.Form.NFC)
            val lower = recomposed.lowercase(Locale.ROOT)
            val stripped = lower.replace(NON_ALNUM_REGEX, " ").trim()
            return stripped.ifEmpty { lower.trim() }
        }
    }
}
