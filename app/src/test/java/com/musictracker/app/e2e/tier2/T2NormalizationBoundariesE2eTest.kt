package com.musictracker.app.e2e.tier2

import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 2 — Area A: Metadata Normalization Boundaries (Pure JVM).
 */
class T2NormalizationBoundariesE2eTest {

    private lateinit var normalizer: MetadataNormalizer

    @Before
    fun setUp() {
        normalizer = MusicMetadataNormalizer()
    }

    @Test
    fun t2_a1_allNoiseTitleFallsBackToTrimmedRawTitle() {
        val result = normalizer.normalize(
            rawTitle = "  (Official Video)  ",
            rawArtist = "Artist"
        )
        assertTrue("Normalized title must never be blank", result.normalizedTitle.isNotBlank())
        assertEquals("(Official Video)", result.normalizedTitle)
        assertEquals("Artist", result.normalizedArtist)
    }

    @Test
    fun t2_a2_hyphenatedArtistAndTitlePreserved() {
        val unpacked = normalizer.normalize(
            rawTitle = "Jay-Z - 99 Problems",
            rawArtist = ""
        )
        assertEquals("Jay-Z", unpacked.normalizedArtist)
        assertEquals("99 Problems", unpacked.normalizedTitle)

        val notSplit = normalizer.normalize(
            rawTitle = "Spider-Man",
            rawArtist = ""
        )
        assertEquals("Unknown Artist", notSplit.normalizedArtist)
        assertEquals("Spider-Man", notSplit.normalizedTitle)
    }

    @Test
    fun t2_a3_validArtistPreventsUnpackingTitleWithDash() {
        val result = normalizer.normalize(
            rawTitle = "Save Your Tears - Remix",
            rawArtist = "The Weeknd"
        )
        assertEquals("The Weeknd", result.normalizedArtist)
        assertEquals("Save Your Tears - Remix", result.normalizedTitle)
    }

    @Test
    fun t2_a4_unicodeDiacriticsEmojiAndRepeatedWhitespaceNormalizeDeterministically() {
        val first = normalizer.normalize(
            rawTitle = "  First\t\tLove   [Lyrics] (HD) | Official Audio ",
            rawArtist = "宇多田ヒカル - Topic"
        )
        val second = normalizer.normalize(
            rawTitle = "First Love",
            rawArtist = "宇多田ヒカル"
        )
        assertEquals("宇多田ヒカル", first.normalizedArtist)
        assertEquals("First Love", first.normalizedTitle)
        assertEquals(second.dedupKey, first.dedupKey)

        val diacriticsAndEmoji = normalizer.normalize(
            rawTitle = "Café   del   Mar 🌊 (Official Audio)",
            rawArtist = "Björk - Topic"
        )
        assertEquals("Björk", diacriticsAndEmoji.normalizedArtist)
        assertEquals("Café del Mar 🌊", diacriticsAndEmoji.normalizedTitle)
    }

    @Test
    fun t2_a5_blankArtistAfterFailedUnpackYieldsUnknownArtistAndValidDedupKey() {
        val result = normalizer.normalize(
            rawTitle = "Instrumental",
            rawArtist = "   "
        )
        assertEquals("Unknown Artist", result.normalizedArtist)
        assertEquals("Instrumental", result.normalizedTitle)
        assertTrue(result.dedupKey.isNotBlank())
    }
}
