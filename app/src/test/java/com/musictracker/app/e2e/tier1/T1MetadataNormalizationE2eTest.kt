package com.musictracker.app.e2e.tier1

import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 — Area A: Metadata Normalization (Pure JVM, F16, F17, F18).
 */
class T1MetadataNormalizationE2eTest {

    private lateinit var normalizer: MetadataNormalizer

    @Before
    fun setUp() {
        normalizer = MusicMetadataNormalizer()
    }

    @Test
    fun t1_a1_stripsArtistTopicSuffix() {
        val result = normalizer.normalize(
            rawTitle = "Paranoid Android",
            rawArtist = "Radiohead - Topic"
        )
        assertEquals("Radiohead", result.normalizedArtist)
        assertEquals("Paranoid Android", result.normalizedTitle)
        assertTrue(result.dedupKey.isNotBlank())
    }

    @Test
    fun t1_a2_stripsTitleNoiseTagsAndPreservesFeaturedArtist() {
        val stripped = normalizer.normalize(
            rawTitle = "Creep (Official Music Video) [Lyrics] (Explicit)",
            rawArtist = "Radiohead"
        )
        assertEquals("Creep", stripped.normalizedTitle)
        assertEquals("Radiohead", stripped.normalizedArtist)

        val featured = normalizer.normalize(
            rawTitle = "Song (feat. Daft Punk) (Official Audio)",
            rawArtist = "Pharrell Williams"
        )
        assertEquals("Song (feat. Daft Punk)", featured.normalizedTitle)
        assertEquals("Pharrell Williams", featured.normalizedArtist)
    }

    @Test
    fun t1_a3_unpacksArtistDashTitleWhenArtistIsEmpty() {
        val result = normalizer.normalize(
            rawTitle = "Daft Punk - Get Lucky (Official Audio)",
            rawArtist = ""
        )
        assertEquals("Daft Punk", result.normalizedArtist)
        assertEquals("Get Lucky", result.normalizedTitle)
    }

    @Test
    fun t1_a4_unpacksGenericArtistAndUnicodeDashes() {
        val result = normalizer.normalize(
            rawTitle = "Tame Impala – Borderline",
            rawArtist = "Unknown Artist"
        )
        assertEquals("Tame Impala", result.normalizedArtist)
        assertEquals("Borderline", result.normalizedTitle)
    }

    @Test
    fun t1_a5_producesIdenticalCrossAppDedupKeyForYouTubeMusicAndSpotify() {
        val ytMusic = normalizer.normalize(
            rawTitle = "Blinding Lights (Official Audio)",
            rawArtist = "The Weeknd - Topic"
        )
        val spotify = normalizer.normalize(
            rawTitle = "Blinding Lights",
            rawArtist = "The Weeknd"
        )
        assertEquals("The Weeknd", ytMusic.normalizedArtist)
        assertEquals("Blinding Lights", ytMusic.normalizedTitle)
        assertEquals(spotify.normalizedArtist, ytMusic.normalizedArtist)
        assertEquals(spotify.normalizedTitle, ytMusic.normalizedTitle)
        assertEquals(spotify.dedupKey, ytMusic.dedupKey)
    }
}
