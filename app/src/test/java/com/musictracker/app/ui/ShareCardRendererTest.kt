package com.musictracker.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.share.ShareAspectRatio
import com.musictracker.app.ui.share.ShareCardData
import com.musictracker.app.ui.share.ShareCardMode
import com.musictracker.app.ui.share.ShareCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareCardRendererTest {

    private val sampleArtist = TopArtistStat(
        artist = "Daft Punk",
        totalListenedMs = 7_500_000L,
        playCount = 42,
        uniqueTrackCount = 11
    )

    private val sampleTracks = listOf(
        TopTrackStat(1L, "Instant Crush", "Daft Punk", "Random Access Memories", null, 2_400_000L, 12),
        TopTrackStat(2L, "Get Lucky", "Daft Punk", "Random Access Memories", null, 1_800_000L, 9),
        TopTrackStat(3L, "Digital Love", "Daft Punk", "Discovery", null, 1_500_000L, 8),
        TopTrackStat(4L, "Veridis Quo", "Daft Punk", "Discovery", null, 1_100_000L, 6),
        TopTrackStat(5L, "One More Time", "Daft Punk", "Discovery", null, 700_000L, 4)
    )

    private val sampleSummary = ListeningSummary(
        totalListenedMs = 14_400_000L,
        playCount = 68,
        skippedCount = 7,
        skipRate = 7f / 68f
    )

    @Test
    fun render_allModesAndAspectRatios_withNullArtworkFallback_produceExactDimensions() {
        val cases = listOf(
            ShareCardMode.TOP_ARTIST to ShareAspectRatio.STORY_9_16,
            ShareCardMode.TOP_ARTIST to ShareAspectRatio.POST_4_5,
            ShareCardMode.TOP_MUSIC to ShareAspectRatio.STORY_9_16,
            ShareCardMode.TOP_MUSIC to ShareAspectRatio.POST_4_5,
            ShareCardMode.LISTENING_SUMMARY to ShareAspectRatio.STORY_9_16,
            ShareCardMode.LISTENING_SUMMARY to ShareAspectRatio.POST_4_5
        )

        for ((mode, ratio) in cases) {
            val bitmap = ShareCardRenderer.render(
                ShareCardData(
                    mode = mode,
                    aspectRatio = ratio,
                    timeframe = StatsTimeframe.SEVEN_DAYS,
                    summary = sampleSummary,
                    featuredArtist = sampleArtist,
                    featuredTrack = null,
                    topTracks = sampleTracks,
                    topArtists = listOf(sampleArtist),
                    streakDays = 7,
                    peakHour = 21,
                    primaryArtwork = null,
                    accentArtworks = emptyList()
                )
            )
            assertFalse(bitmap.isRecycled)
            assertEquals("Width mismatch for $mode/$ratio", ratio.widthPx, bitmap.width)
            assertEquals("Height mismatch for $mode/$ratio", ratio.heightPx, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            bitmap.recycle()
        }
    }

    @Test
    fun render_topMusicHeroTrack_withSampleArtworkPaletteExtraction_rendersExactDimensions() {
        val artwork = createSampleArtworkBitmap()
        try {
            for (ratio in ShareAspectRatio.entries) {
                val bitmap = ShareCardRenderer.render(
                    ShareCardData(
                        mode = ShareCardMode.TOP_MUSIC,
                        aspectRatio = ratio,
                        timeframe = StatsTimeframe.THIRTY_DAYS,
                        summary = sampleSummary,
                        featuredTrack = sampleTracks.first(),
                        topTracks = sampleTracks,
                        topArtists = listOf(sampleArtist),
                        primaryArtwork = artwork,
                        accentArtworks = listOf(artwork, artwork, artwork)
                    )
                )
                assertEquals(ratio.widthPx, bitmap.width)
                assertEquals(ratio.heightPx, bitmap.height)
                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                bitmap.recycle()
            }
        } finally {
            artwork.recycle()
        }
    }

    @Test
    fun render_topArtistAndSummary_withSampleArtworkBitmap_succeedsWithoutError() {
        val artwork = createSampleArtworkBitmap()
        try {
            val artistCard = ShareCardRenderer.render(
                ShareCardData(
                    mode = ShareCardMode.TOP_ARTIST,
                    aspectRatio = ShareAspectRatio.STORY_9_16,
                    timeframe = StatsTimeframe.ALL_TIME,
                    featuredArtist = sampleArtist,
                    topTracks = sampleTracks.take(3),
                    primaryArtwork = artwork,
                    accentArtworks = listOf(artwork, null, artwork)
                )
            )
            assertEquals(1080, artistCard.width)
            assertEquals(1920, artistCard.height)
            artistCard.recycle()

            val summaryCard = ShareCardRenderer.render(
                ShareCardData(
                    mode = ShareCardMode.LISTENING_SUMMARY,
                    aspectRatio = ShareAspectRatio.POST_4_5,
                    timeframe = StatsTimeframe.TODAY,
                    summary = sampleSummary,
                    topArtists = listOf(sampleArtist),
                    topTracks = sampleTracks,
                    streakDays = 1,
                    peakHour = null,
                    primaryArtwork = artwork,
                    accentArtworks = listOf(artwork)
                )
            )
            assertEquals(1080, summaryCard.width)
            assertEquals(1350, summaryCard.height)
            summaryCard.recycle()
        } finally {
            artwork.recycle()
        }
    }

    private fun createSampleArtworkBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val color = if (x < 32) Color.rgb(220, 45, 130) else Color.rgb(35, 110, 235)
                bmp.setPixel(x, y, color)
            }
        }
        return bmp
    }
}
