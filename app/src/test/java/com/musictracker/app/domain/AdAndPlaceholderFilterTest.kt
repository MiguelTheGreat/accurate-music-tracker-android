package com.musictracker.app.domain

import com.musictracker.app.domain.normalization.AdAndPlaceholderFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdAndPlaceholderFilterTest {

    @Test
    fun streamingAdsAndPlaceholders_areFiltered() {
        val adCases = listOf(
            Triple("Advertisement", "", ""),
            Triple("Advertisement", "Spotify", ""),
            Triple("Sponsored", "BrandX", ""),
            Triple("Audio Ad", "SoundCloud", ""),
            Triple("Commercial", "", ""),
            Triple("Spotify", "", ""),
            Triple("YouTube Music", "YouTube", ""),
            Triple("Recommended for you", "", ""),
            Triple("Recommended for you", "YouTube Music", ""),
            Triple("Advertisement · Spotify", "Spotify", ""),
            Triple("Sponsored Message", "", ""),
            Triple("Brand Promo", "Some Brand", "Advertisement"),
            Triple("   ", "Some Artist", "")
        )

        adCases.forEach { (title, artist, album) ->
            assertTrue(
                "Expected ad/placeholder to be filtered: title='$title', artist='$artist', album='$album'",
                AdAndPlaceholderFilter.isAdOrPlaceholder(title, artist, album)
            )
        }
    }

    @Test
    fun realSongsWithAdSubstrings_areNotFalsePositived() {
        val realSongs = listOf(
            Triple("Ad Astra", "Max Richter", "Ad Astra (Original Motion Picture Soundtrack)"),
            Triple("Advertisement for Myself", "Some Band", "First Album"),
            Triple("Commercial Breakdown", "The Band", "Live Sessions"),
            Triple("Sponsored by Destiny", "Real Artist", "Destiny"),
            Triple("Spotify Sessions", "Hozier", "Spotify Sessions"),
            Triple("Blinding Lights", "The Weeknd", "After Hours"),
            Triple("Commercial", "Real Artist", "Real Album")
        )

        realSongs.forEach { (title, artist, album) ->
            assertFalse(
                "Real song must NOT be filtered: title='$title', artist='$artist', album='$album'",
                AdAndPlaceholderFilter.isAdOrPlaceholder(title, artist, album)
            )
        }
    }

    @Test
    fun placeholderSubtitles_areDetected() {
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle("Recommended for you"))
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle("  RECOMMENDED FOR YOU  "))
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle("Up next"))
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle("Advertisement"))
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle(""))
        assertTrue(AdAndPlaceholderFilter.isPlaceholderSubtitle(null))

        assertFalse(AdAndPlaceholderFilter.isPlaceholderSubtitle("The Weeknd"))
        assertFalse(AdAndPlaceholderFilter.isPlaceholderSubtitle("Max Richter"))
    }
}
