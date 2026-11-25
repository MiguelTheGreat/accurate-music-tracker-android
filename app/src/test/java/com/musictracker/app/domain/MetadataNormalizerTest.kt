package com.musictracker.app.domain

import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataNormalizerTest {

    private val normalizer = MusicMetadataNormalizer()

    @Test
    fun whitespaceAndZeroWidthCharacters_areCollapsedAndTrimmed() {
        val result = normalizer.normalize(
            rawTitle = "  \uFEFFBlinding\t\n  Lights\u200B\u00A0Now\u3000  ",
            rawArtist = "\u200C  The\u00A0\u00A0Weeknd \r\n "
        )
        assertEquals("Blinding Lights Now", result.normalizedTitle)
        assertEquals("The Weeknd", result.normalizedArtist)
        assertEquals("the weeknd::blinding lights now", result.dedupKey)
    }

    @Test
    fun artistNoiseStripping_stripsTopicOfficialAndStrayBracketsIteratively() {
        val cases = listOf(
            "The Weeknd - Topic" to "The Weeknd",
            "Radiohead – Topic" to "Radiohead",
            "Coldplay — TOPIC" to "Coldplay",
            "Drake - Official" to "Drake",
            "Drake – Official" to "Drake",
            "Drake — Official" to "Drake",
            "Drake Official" to "Drake",
            "Drake (Official)" to "Drake",
            "Drake [Official]" to "Drake",
            "Drake Official - Topic" to "Drake",
            "The Weeknd - Topic ()" to "The Weeknd",
            "Artist () []" to "Artist",
            "Topic - Topic" to "Topic",
            "Topic" to "Topic",
            "Official" to "Official",
            "- Topic" to "Unknown Artist",
            "   " to "Unknown Artist"
        )

        cases.forEach { (rawArtist, expectedArtist) ->
            val result = normalizer.normalize("Song", rawArtist)
            assertEquals("Failed for rawArtist='$rawArtist'", expectedArtist, result.normalizedArtist)
        }
    }

    @Test
    fun titleNoiseStripping_stripsAllRequiredBracketedAndParentheticalTags() {
        val tags = listOf(
            "(Official Audio)",
            "[Official Audio]",
            "(Official Video)",
            "[Official Video]",
            "(Official Music Video)",
            "[Official Music Video]",
            "(Official Lyric Video)",
            "[Official Lyric Video]",
            "(Official Lyrics Video)",
            "(Official Visualizer)",
            "[Official Visualizer]",
            "[Lyrics]",
            "(Lyrics)",
            "(Lyric Video)",
            "[Lyric Video]",
            "(Explicit)",
            "[Explicit]",
            "[Clean]",
            "(Clean)",
            "(Audio)",
            "[Audio]",
            "(Music Video)",
            "[Music Video]",
            "(Visualizer)",
            "[Visualizer]",
            "(Full Audio)",
            "[Full Audio]",
            "(HD)",
            "[HD]",
            "(HQ)",
            "[HQ]",
            "(4K)",
            "[4K]",
            "[MV]",
            "(MV)",
            "[M/V]",
            "(official audio)",
            "[LYRICS]"
        )

        tags.forEach { tag ->
            val result = normalizer.normalize("Blinding Lights $tag", "The Weeknd")
            assertEquals("Failed to strip tag '$tag'", "Blinding Lights", result.normalizedTitle)
        }
    }

    @Test
    fun titleNoiseStripping_stripsStackedTagsPipeNoiseAndStraySeparators() {
        val cases = listOf(
            "Creep (Official Music Video) [Lyrics] (Explicit) (HD)" to "Creep",
            "Blinding Lights | Official Video" to "Blinding Lights",
            "Blinding Lights | Lyrics" to "Blinding Lights",
            "Blinding Lights | Official Audio" to "Blinding Lights",
            "Blinding Lights | Official Music Video | 4K" to "Blinding Lights",
            "Song - (Official Video)" to "Song",
            "Song | [Lyrics]" to "Song",
            "Song () []" to "Song",
            "Part I | Part II" to "Part I | Part II"
        )

        cases.forEach { (rawTitle, expectedTitle) ->
            val result = normalizer.normalize(rawTitle, "Artist")
            assertEquals("Failed for rawTitle='$rawTitle'", expectedTitle, result.normalizedTitle)
        }
    }

    @Test
    fun titleNoiseStripping_preservesMeaningfulMusicalMetadata() {
        val cases = listOf(
            "Get Lucky (feat. Pharrell Williams) (Official Audio)" to "Get Lucky (feat. Pharrell Williams)",
            "Song (Official Video) (ft. Guest Artist)" to "Song (ft. Guest Artist)",
            "Save Your Tears (Remix) [Official Video]" to "Save Your Tears (Remix)",
            "Save Your Tears (Tiësto Remix) (Official Audio)" to "Save Your Tears (Tiësto Remix)",
            "Hotel California (Live) [Lyrics]" to "Hotel California (Live)",
            "One More Time (Radio Edit) (Explicit)" to "One More Time (Radio Edit)",
            "Everlong (Acoustic) [Official Video]" to "Everlong (Acoustic)",
            "Audio" to "Audio",
            "Clean" to "Clean"
        )

        cases.forEach { (rawTitle, expectedTitle) ->
            val result = normalizer.normalize(rawTitle, "Artist")
            assertEquals("Failed for rawTitle='$rawTitle'", expectedTitle, result.normalizedTitle)
        }
    }

    @Test
    fun titleNoiseStripping_fallsBackToTrimmedRawTitleWhenStrippingYieldsBlank() {
        assertEquals("(Official Video)", normalizer.normalize("  (Official Video)  ", "Artist").normalizedTitle)
        assertEquals("[Lyrics] (Explicit)", normalizer.normalize("[Lyrics]  (Explicit)", "Artist").normalizedTitle)
        assertEquals("", normalizer.normalize("   ", "Artist").normalizedTitle)
    }

    @Test
    fun artistTitleUnpacking_unpacksForAllGenericArtistsAndAllSpacedDashVariants() {
        val genericArtists = listOf(
            "",
            "   ",
            "unknown",
            "Unknown Artist",
            "various",
            "Various Artists",
            "<unknown>",
            "artist",
            "YouTube",
            "YouTube Music",
            "Unknown Artist - Topic"
        )

        genericArtists.forEach { generic ->
            val hyphen = normalizer.normalize("The Weeknd - Blinding Lights (Official Audio)", generic)
            assertEquals("Failed artist for generic='$generic'", "The Weeknd", hyphen.normalizedArtist)
            assertEquals("Failed title for generic='$generic'", "Blinding Lights", hyphen.normalizedTitle)
        }

        val enDash = normalizer.normalize("Radiohead – Creep [Lyrics]", "")
        assertEquals("Radiohead", enDash.normalizedArtist)
        assertEquals("Creep", enDash.normalizedTitle)

        val emDash = normalizer.normalize("Coldplay — Yellow (Official Video)", "YouTube")
        assertEquals("Coldplay", emDash.normalizedArtist)
        assertEquals("Yellow", emDash.normalizedTitle)
    }

    @Test
    fun artistTitleUnpacking_splitsOnFirstSpacedDashPreservesInternalHyphensAndReStripsBothSides() {
        val multiDash = normalizer.normalize("Daft Punk - One More Time - Radio Edit (Official Audio)", "")
        assertEquals("Daft Punk", multiDash.normalizedArtist)
        assertEquals("One More Time - Radio Edit", multiDash.normalizedTitle)

        val noisyUnpacked = normalizer.normalize(
            "The Weeknd Official - Blinding Lights (Official Video) [Lyrics]",
            "YouTube Music"
        )
        assertEquals("The Weeknd", noisyUnpacked.normalizedArtist)
        assertEquals("Blinding Lights", noisyUnpacked.normalizedTitle)

        val hyphenatedArtist = normalizer.normalize("Jay-Z - 99 Problems (Explicit)", "Unknown Artist")
        assertEquals("Jay-Z", hyphenatedArtist.normalizedArtist)
        assertEquals("99 Problems", hyphenatedArtist.normalizedTitle)

        val hyphenatedTitleNoSpacedDash = normalizer.normalize("Spider-Man Theme (Official Audio)", "")
        assertEquals("Unknown Artist", hyphenatedTitleNoSpacedDash.normalizedArtist)
        assertEquals("Spider-Man Theme", hyphenatedTitleNoSpacedDash.normalizedTitle)

        val nonBlankGenericNoDash = normalizer.normalize("Ambient Interlude", "Various Artists")
        assertEquals("Various Artists", nonBlankGenericNoDash.normalizedArtist)
        assertEquals("Ambient Interlude", nonBlankGenericNoDash.normalizedTitle)
    }

    @Test
    fun artistTitleUnpacking_doesNotUnpackWhenArtistIsValid() {
        val result = normalizer.normalize("One More Time - Radio Edit (Official Video)", "Daft Punk")
        assertEquals("Daft Punk", result.normalizedArtist)
        assertEquals("One More Time - Radio Edit", result.normalizedTitle)

        val remix = normalizer.normalize("Save Your Tears - Remix", "The Weeknd")
        assertEquals("The Weeknd", remix.normalizedArtist)
        assertEquals("Save Your Tears - Remix", remix.normalizedTitle)
    }

    @Test
    fun dedupKey_unifiesCrossPlatformVariantsDiacriticsAndPunctuationAndPreservesNonLatin() {
        val ytMusic = normalizer.normalize("Blinding Lights (Official Audio) [Lyrics]", "The Weeknd - Topic")
        val ytUnpacked = normalizer.normalize("The Weeknd - Blinding Lights | Official Video", "YouTube Music")
        val spotify = normalizer.normalize("Blinding Lights!", "The Weeknd")

        assertEquals("the weeknd::blinding lights", ytMusic.dedupKey)
        assertEquals(ytMusic.dedupKey, ytUnpacked.dedupKey)
        assertEquals(ytMusic.dedupKey, spotify.dedupKey)

        val curlyApostrophe = normalizer.normalize("Don’t Stop Me Now", "Queen")
        val straightApostrophe = normalizer.normalize("Don't Stop Me Now", "Queen")
        assertEquals(curlyApostrophe.dedupKey, straightApostrophe.dedupKey)

        val nfc = normalizer.normalize("Halo", "Beyoncé")
        val nfd = normalizer.normalize("Halo", "Beyonce\u0301")
        val ascii = normalizer.normalize("Halo", "Beyonce")
        assertEquals("beyonce::halo", nfc.dedupKey)
        assertEquals(nfc.dedupKey, nfd.dedupKey)
        assertEquals(nfc.dedupKey, ascii.dedupKey)

        val japanese = normalizer.normalize("First Love (Official Video)", "宇多田ヒカル - Topic")
        assertEquals("宇多田ヒカル", japanese.normalizedArtist)
        assertEquals("First Love", japanese.normalizedTitle)
        assertEquals("宇多田ヒカル::first love", japanese.dedupKey)

        val korean = normalizer.normalize("봄날 [Lyrics]", "방탄소년단 - Topic")
        assertEquals("방탄소년단", korean.normalizedArtist)
        assertEquals("봄날", korean.normalizedTitle)
        assertEquals("방탄소년단::봄날", korean.dedupKey)
    }
}
