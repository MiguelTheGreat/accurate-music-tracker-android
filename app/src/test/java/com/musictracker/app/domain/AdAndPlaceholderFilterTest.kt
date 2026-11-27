package com.musictracker.app.domain

import com.musictracker.app.domain.model.MediaMetadataSnapshot
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
            Triple("   ", "Some Artist", ""),
            Triple("Ad · 1 of 2", "YouTube Music", ""),
            Triple("Ad 1 of 2", "Spotify", ""),
            Triple("Ad 1/2", "", ""),
            Triple("Loading", "", ""),
            Triple("Up Next", "YouTube Music", ""),
            // Spotify & cross-platform artist-field and pattern ads
            Triple("Spotify", "Advertisement • 1 of 2", ""),
            Triple("Spotify", "Advertisement • 2 of 2", ""),
            Triple("Get Premium", "Advertisement • 1 of 2", ""),
            Triple("Acme Brand", "Advertisement", ""),
            Triple("Acme Brand", "Audio Ad", ""),
            Triple("Acme Brand", "Sponsored", ""),
            Triple("Acme Brand", "Sponsored by Acme", ""),
            Triple("Acme Brand", "Ad · 0:15", ""),
            Triple("Acme Brand", "Ad 1 of 2", ""),
            Triple("Acme Brand", "Ad 1/2", ""),
            Triple("Video will play after ad", "YouTube Music", ""),
            Triple("Video will play after ad", "Acme Brand", ""),
            Triple("Music will resume after this short break", "Pandora", ""),
            Triple("Ad · 0:15", "Geico", ""),
            Triple("Ad 1 of 2", "DoorDash", ""),
            Triple("Sponsored by Acme", "Spotify", ""),
            Triple("Paid Promotion", "Geico", ""),
            Triple("Paid Partnership", "Geico", ""),
            Triple("Spotify", "Advertisement (1 of 2)", ""),
            Triple("Ad (1 of 2)", "YouTube Music", ""),
            Triple("Ad (1/2)", "Geico", ""),
            Triple("Video will play after the ad", "YouTube Music", ""),
            Triple("Music will resume after a short break", "Spotify", ""),
            Triple("Your song will resume after this short break", "Spotify", ""),
            Triple("Enjoy the next 30 minutes of ad-free music", "Spotify", ""),
            Triple("Spotify", "Advertisement \u2219 1 of 2", ""),
            Triple("Spotify", "Ad\u00ADvertisement • 1 of 2", "")
        )

        adCases.forEach { (title, artist, album) ->
            assertTrue(
                "Expected ad/placeholder to be filtered: title='$title', artist='$artist', album='$album'",
                AdAndPlaceholderFilter.isAdOrPlaceholder(title, artist, album)
            )
        }

        assertTrue(
            AdAndPlaceholderFilter.isAdOrPlaceholder(
                MediaMetadataSnapshot(
                    title = "Any Jingle",
                    artist = "Any Brand",
                    isAdvertisement = true
                )
            )
        )
    }

    @Test
    fun realSongsWithAdSubstrings_areNotFalsePositived() {
        val realSongs = listOf(
            Triple("Ad Astra", "Max Richter", "Ad Astra (Original Motion Picture Soundtrack)"),
            Triple("Advertisement for Myself", "Some Band", "First Album"),
            Triple("Commercial Breakdown", "The Band", "Live Sessions"),
            Triple("Sponsored by Destiny", "Real Artist", "Destiny"),
            Triple("Spotify Sessions", "Hozier", "Spotify Sessions"),
            Triple("Spotify", "Real Artist", ""),
            Triple("Blinding Lights", "The Weeknd", "After Hours"),
            Triple("Commercial", "Real Artist", "Real Album"),
            Triple("Loading", "Central Cee", "Wild West"),
            Triple("Up Next", "Drake", "Album"),
            Triple("Juice", "AD", ""),
            Triple("Viva La Revolution", "The Adicts", "Songs of Praise"),
            Triple("Unstoppable", "Ad Infinitum", "Chapter I"),
            Triple("Paranoid Android", "Radiohead", "OK Computer")
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

    @Test
    fun adLabels_areDetectedWithoutMatchingNonAdPlaceholders() {
        assertTrue(AdAndPlaceholderFilter.isAdLabel("Advertisement"))
        assertTrue(AdAndPlaceholderFilter.isAdLabel("Advertisement • 1 of 2"))
        assertTrue(AdAndPlaceholderFilter.isAdLabel("Sponsored by Acme"))
        assertTrue(AdAndPlaceholderFilter.isAdLabel("Ad · 0:15"))
        assertTrue(AdAndPlaceholderFilter.isAdLabel("Audio Ad"))

        assertFalse(AdAndPlaceholderFilter.isAdLabel("Recommended for you"))
        assertFalse(AdAndPlaceholderFilter.isAdLabel("Up next"))
        assertFalse(AdAndPlaceholderFilter.isAdLabel("AD"))
        assertFalse(AdAndPlaceholderFilter.isAdLabel("The Weeknd"))
        assertFalse(AdAndPlaceholderFilter.isAdLabel(""))
        assertFalse(AdAndPlaceholderFilter.isAdLabel(null))
    }
}
