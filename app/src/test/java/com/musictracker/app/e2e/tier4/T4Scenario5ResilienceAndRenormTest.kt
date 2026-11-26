package com.musictracker.app.e2e.tier4

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.tracking.MusicNotificationListenerService
import com.musictracker.app.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 4 — Scenario 5: Background Service Resilience (Disconnect/Destroy Flush),
 * 35-Minute Idle Pause Timeout & Transactional Re-Normalization.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T4Scenario5ResilienceAndRenormTest {

    private lateinit var app: Application
    private lateinit var env: E2eTestEnvironment
    private lateinit var coordinatorScope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = app)
        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
        env.close()
    }

    @Test
    fun scenario5_serviceDestroyFlushIdleTimeoutAndTransactionalRenormalization() {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val service = Robolectric.buildService(MusicNotificationListenerService::class.java).create().get()
        service.coordinatorOverride = coordinator
        service.onListenerConnected()

        // 1. User plays a track for 45s; OS abruptly destroys the service mid-track -> 0 seconds lost
        val driver = FakeMediaControllerDriver(
            packageName = "com.google.android.apps.youtube.music",
            initialMetadata = MediaMetadataSnapshot(
                title = "Resilient Song (Official Audio)",
                artist = "Resilient Artist - Topic",
                durationMs = 200_000L
            ),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(driver))
        env.clock.advanceBy(45_000L)

        service.onDestroy()
        runBlocking { env.waitForEventCount(1) }

        val afterDestroyEvents = runBlocking { env.database.listenEventDao().getAll() }
        assertEquals(1, afterDestroyEvents.size)
        assertEquals("0 seconds may be lost on service destroy", 45_000L, afterDestroyEvents.single().actualListenedMs)

        // 2. Later, user pauses a track for 35 minutes (> 30 min idle timeout) -> finalizes cleanly;
        //    resuming starts a fresh session.
        val track = MediaMetadataSnapshot(
            title = "Resilient Song",
            artist = "Resilient Artist",
            durationMs = 200_000L
        )
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = "com.spotify.music",
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        env.clock.advanceBy(30_000L)
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.StateChanged("com.spotify.music", PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 30_000L))
        )
        // Pause for 35 minutes
        env.clock.advanceBy(35L * 60L * 1_000L)
        val timedOut = env.playSessionStateMachine.onTick()
        assertEquals(1, timedOut.size)
        assertEquals(30_000L, timedOut.single().actualListenedMs)
        assertNull(env.playSessionStateMachine.nowTracking.value)
        runBlocking { timedOut.forEach { env.listeningRepository.recordCompletedSession(it) } }

        // Resume starts a fresh session (60s)
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = "com.spotify.music",
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 30_000L)
            )
        )
        env.clock.advanceBy(60_000L)
        val resumed = env.playSessionStateMachine.flushActiveSession()
        assertEquals(1, resumed.size)
        assertEquals(60_000L, resumed.single().actualListenedMs)
        runBlocking { resumed.forEach { env.listeningRepository.recordCompletedSession(it) } }

        // 3. Insert a legacy raw-variant track row + listen event, then run renormalizeAllTracks()
        runBlocking {
            val legacyTrackId = env.database.trackDao().insertIgnore(
                TrackEntity(
                    dedupKey = "legacy_resilient_key",
                    normalizedTitle = "Resilient Song [Lyrics] (Explicit)",
                    normalizedArtist = "Resilient Artist - Topic",
                    album = "",
                    durationMs = 200_000L,
                    rawTitle = "Resilient Song [Lyrics] (Explicit)",
                    rawArtist = "Resilient Artist - Topic"
                )
            )
            env.database.listenEventDao().insert(
                ListenEventEntity(
                    trackId = legacyTrackId,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    startedAtEpoch = env.clock.currentTimeMillis() - 10_000L,
                    endedAtEpoch = env.clock.currentTimeMillis(),
                    actualListenedMs = 50_000L,
                    completedRatio = 0.25f,
                    wasSkipped = true
                )
            )

            env.listeningRepository.renormalizeAllTracks()

            val finalTracks = env.database.trackDao().getAll()
            val finalEvents = env.database.listenEventDao().getAll()
            assertEquals("All raw variants must merge into 1 canonical track", 1, finalTracks.size)
            assertEquals("All 4 listen_events must remain intact", 4, finalEvents.size)
            assertTrue(finalEvents.all { it.trackId == finalTracks.single().id })
        }

        val expectedTotalMs = 45_000L + 30_000L + 60_000L + 50_000L
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        statsVm.selectTimeframe(StatsTimeframe.ALL_TIME)
        val statsState = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.topTracks.isNotEmpty() }
        assertEquals(expectedTotalMs, statsState.summary.totalListenedMs)
        assertEquals(4, statsState.summary.playCount)
        assertEquals("Resilient Song", statsState.topTracks.single().title)
        assertEquals(expectedTotalMs, statsState.topTracks.single().totalListenedMs)
    }
}
