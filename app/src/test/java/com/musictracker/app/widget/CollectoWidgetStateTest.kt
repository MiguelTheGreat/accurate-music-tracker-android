package com.musictracker.app.widget

import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.TopArtistStat
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectoWidgetStateTest {

    private val weeklyArtist = TopArtistStat(
        artist = "Daft Punk",
        totalListenedMs = 5_400_000L,
        playCount = 18,
        uniqueTrackCount = 6
    )

    private val weeklySummary = ListeningSummary(
        totalListenedMs = 8_040_000L, // 2h 14m
        playCount = 31,
        skippedCount = 3,
        skipRate = 3f / 31f
    )

    @Test
    fun map_whenActivelyPlayingAndNotDebouncing_returnsNowTracking() {
        val state = CollectoWidgetStateMapper.map(
            nowTracking = sampleNowTracking(isPlaying = true, isDebouncing = false),
            topArtist = weeklyArtist,
            weeklySummary = weeklySummary,
            artworkPath = "/data/artwork/tracks/abc.webp"
        )

        assertEquals(
            CollectoWidgetState.NowTracking(
                title = "Instant Crush",
                artist = "Daft Punk",
                sourceApp = "YouTube Music",
                artworkPath = "/data/artwork/tracks/abc.webp"
            ),
            state
        )
    }

    @Test
    fun map_whenDebouncingWithWeeklyData_fallsBackToWeeklyTopArtist() {
        val state = CollectoWidgetStateMapper.map(
            nowTracking = sampleNowTracking(isPlaying = true, isDebouncing = true),
            topArtist = weeklyArtist,
            weeklySummary = weeklySummary,
            artworkPath = "/data/artwork/artists/daft.webp"
        )

        assertEquals(
            CollectoWidgetState.WeeklyTopArtist(
                artistName = "Daft Punk",
                weeklyDurationFormatted = "2h 14m",
                weeklyPlayCount = 31,
                artworkPath = "/data/artwork/artists/daft.webp"
            ),
            state
        )
    }

    @Test
    fun map_whenPausedOrNullWithWeeklyData_returnsWeeklyTopArtist() {
        val pausedState = CollectoWidgetStateMapper.map(
            nowTracking = sampleNowTracking(isPlaying = false, isDebouncing = false),
            topArtist = weeklyArtist,
            weeklySummary = weeklySummary,
            artworkPath = null
        )
        val nullState = CollectoWidgetStateMapper.map(
            nowTracking = null,
            topArtist = weeklyArtist,
            weeklySummary = weeklySummary,
            artworkPath = null
        )

        val expected = CollectoWidgetState.WeeklyTopArtist(
            artistName = "Daft Punk",
            weeklyDurationFormatted = "2h 14m",
            weeklyPlayCount = 31,
            artworkPath = null
        )
        assertEquals(expected, pausedState)
        assertEquals(expected, nullState)
    }

    @Test
    fun map_whenNoPlaybackAndNoWeeklyData_returnsEmpty() {
        val noArtistState = CollectoWidgetStateMapper.map(
            nowTracking = null,
            topArtist = null,
            weeklySummary = ListeningSummary(),
            artworkPath = null
        )
        val zeroDurationState = CollectoWidgetStateMapper.map(
            nowTracking = null,
            topArtist = weeklyArtist,
            weeklySummary = ListeningSummary(totalListenedMs = 0L, playCount = 0),
            artworkPath = null
        )

        assertEquals(CollectoWidgetState.Empty, noArtistState)
        assertEquals(CollectoWidgetState.Empty, zeroDurationState)
    }

    @Test
    fun map_whenNormalizedFieldsBlank_fallsBackToRawMetadata() {
        val state = CollectoWidgetStateMapper.map(
            nowTracking = sampleNowTracking(
                isPlaying = true,
                isDebouncing = false,
                normalizedTitle = "",
                normalizedArtist = "   "
            ),
            topArtist = null,
            weeklySummary = ListeningSummary(),
            artworkPath = null
        )

        assertEquals(
            CollectoWidgetState.NowTracking(
                title = "Instant Crush (Official Video)",
                artist = "Daft Punk - Topic",
                sourceApp = "YouTube Music",
                artworkPath = null
            ),
            state
        )
    }

    private fun sampleNowTracking(
        isPlaying: Boolean,
        isDebouncing: Boolean,
        normalizedTitle: String = "Instant Crush",
        normalizedArtist: String = "Daft Punk"
    ): NowTrackingState = NowTrackingState(
        sourcePackage = "com.google.android.apps.youtube.music",
        rawTitle = "Instant Crush (Official Video)",
        rawArtist = "Daft Punk - Topic",
        normalizedTitle = normalizedTitle,
        normalizedArtist = normalizedArtist,
        album = "Random Access Memories",
        durationMs = 337_000L,
        artworkUri = null,
        isPlaying = isPlaying,
        accumulatedMs = 15_000L,
        segmentStartElapsedMs = 1_000L,
        isDebouncing = isDebouncing
    )
}
