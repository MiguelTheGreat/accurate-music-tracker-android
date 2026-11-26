package com.musictracker.app.e2e.tier3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 3 — T3-3: Multi-app YT Music/Spotify dedup + legacy track re-normalization + Stats aggregation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T3MultiAppDedupAndRenormE2eTest {

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun t3_3_multiAppDedupAndLegacyRenormalizationAggregateSeamlesslyInStats() {
        val now = env.clock.currentTimeMillis()

        runBlocking {
            // 1. Pre-existing legacy unnormalized row in Room
            val legacyTrackId = env.database.trackDao().insertIgnore(
                TrackEntity(
                    dedupKey = "legacy_unnormalized_dedup",
                    normalizedTitle = "Starboy (Official Video) [Explicit]",
                    normalizedArtist = "The Weeknd - Topic",
                    album = "",
                    durationMs = 230_000L,
                    rawTitle = "Starboy (Official Video) [Explicit]",
                    rawArtist = "The Weeknd - Topic"
                )
            )
            env.database.listenEventDao().insert(
                ListenEventEntity(
                    trackId = legacyTrackId,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    startedAtEpoch = now - 300_000L,
                    endedAtEpoch = now - 200_000L,
                    actualListenedMs = 100_000L,
                    completedRatio = 0.43f,
                    wasSkipped = true
                )
            )

            // 2. Live YT Music listen + Spotify listen for the same song
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.google.android.apps.youtube.music",
                    rawTitle = "Starboy (Official Audio)",
                    rawArtist = "The Weeknd - Topic",
                    album = "Starboy",
                    durationMs = 230_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 180_000L,
                    endedAtEpoch = now - 60_000L,
                    actualListenedMs = 120_000L,
                    completedRatio = 0.52f,
                    wasSkipped = false
                )
            )
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Starboy",
                    rawArtist = "The Weeknd",
                    album = "Starboy",
                    durationMs = 230_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 50_000L,
                    endedAtEpoch = now,
                    actualListenedMs = 150_000L,
                    completedRatio = 0.65f,
                    wasSkipped = false
                )
            )

            // Before renormalization: 2 tracks (legacy + normalized cross-app track)
            assertEquals(2, env.database.trackDao().getAll().size)

            // 3. Run renormalizeAllTracks() -> legacy row merges cleanly into 1 track
            env.listeningRepository.renormalizeAllTracks()

            val tracks = env.database.trackDao().getAll()
            val events = env.database.listenEventDao().getAll()
            assertEquals(1, tracks.size)
            assertEquals(3, events.size)
            assertEquals("Starboy", tracks.single().normalizedTitle)
            assertEquals("The Weeknd", tracks.single().normalizedArtist)
        }

        // 4. Stats aggregates all 3 listen_events seamlessly (100s + 120s + 150s = 370s)
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        statsVm.selectTimeframe(StatsTimeframe.TODAY)
        val uiState = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.topTracks.isNotEmpty() }

        assertEquals(1, uiState.topTracks.size)
        assertEquals("Starboy", uiState.topTracks.single().title)
        assertEquals(370_000L, uiState.topTracks.single().totalListenedMs)
        assertEquals(3, uiState.topTracks.single().playCount)

        assertEquals(1, uiState.topArtists.size)
        assertEquals("The Weeknd", uiState.topArtists.single().artist)
        assertEquals(370_000L, uiState.topArtists.single().totalListenedMs)
    }
}
