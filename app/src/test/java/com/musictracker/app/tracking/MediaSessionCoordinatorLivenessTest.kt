package com.musictracker.app.tracking

import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionCoordinatorLivenessTest {

    private class FakeClock(
        var elapsedMs: Long = 10_000L,
        var epochMs: Long = 1_700_000_000_000L
    ) : TrackerClock {
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun currentTimeMillis(): Long = epochMs

        fun advanceBy(deltaMs: Long) {
            elapsedMs += deltaMs
            epochMs += deltaMs
        }
    }

    private class FakeAllowlistRepository(initial: Set<String>) : AllowlistRepository {
        private val flow = MutableStateFlow(initial)
        override val allowedPackages: Flow<Set<String>> = flow
        override suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {}
        override suspend fun resetToDefaults() {}
    }

    private class RecordingListeningRepository : ListeningRepository {
        val recorded = mutableListOf<CompletedListenSession>()
        override suspend fun recordCompletedSession(session: CompletedListenSession) {
            recorded += session
        }
        override fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary> =
            flowOf(ListeningSummary(0L, 0, 0, 0f))
        override fun observeRecentHistory(limit: Int): Flow<List<RecentListenItem>> = flowOf(emptyList())
        override fun observeTopTracks(sinceEpochMs: Long, limit: Int): Flow<List<TopTrackStat>> = flowOf(emptyList())
        override fun observeTopArtists(sinceEpochMs: Long, limit: Int): Flow<List<TopArtistStat>> = flowOf(emptyList())
        override suspend fun renormalizeAllTracks(): Int = 0
    }

    private class FakeController(
        override val tokenId: Any,
        override val packageName: String,
        override var currentMetadata: MediaMetadataSnapshot? = null,
        override var currentPlaybackState: PlaybackStateSnapshot? = null
    ) : TrackedMediaController {
        var listener: TrackedMediaController.Listener? = null
        override fun registerCallback(listener: TrackedMediaController.Listener) {
            this.listener = listener
        }
        override fun unregisterCallback() {
            this.listener = null
        }
    }

    private val pkg = "com.google.android.apps.youtube.music"
    private val song = MediaMetadataSnapshot(
        title = "Starboy",
        artist = "The Weeknd",
        album = "Starboy",
        durationMs = 230_000L
    )

    private fun createCoordinator(
        testScope: TestScope,
        clock: FakeClock,
        repo: RecordingListeningRepository,
        livenessIntervalMs: Long = 10_000L
    ): MediaSessionCoordinator {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())
        return MediaSessionCoordinator(
            playSessionEngine = engine,
            allowlistRepository = FakeAllowlistRepository(setOf(pkg)),
            listeningRepository = repo,
            clock = clock,
            scope = testScope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher,
            livenessCheckIntervalMs = livenessIntervalMs
        )
    }

    @Test
    fun livenessCheck_detectsSilentPauseWhilePlaying() = runTest {
        val clock = FakeClock(elapsedMs = 10_000L)
        val repo = RecordingListeningRepository()
        val coordinator = createCoordinator(this, clock, repo, livenessIntervalMs = 10_000L)
        coordinator.start()
        runCurrent()

        val controller = FakeController(
            tokenId = "tok-1",
            packageName = pkg,
            currentMetadata = song,
            currentPlaybackState = PlaybackStateSnapshot(
                status = PlaybackStatus.PLAYING,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                lastPositionUpdateTimeMs = clock.elapsedMs
            )
        )
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()

        // Pass 1.5s debounce -> becomes ACTIVE and schedules livenessJob
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        assertNotNull(coordinator.nowTracking.value)
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)
        assertTrue(coordinator.isLivenessCheckScheduled)

        // Controller silently transitions to PAUSED at t=+5s WITHOUT firing callback
        val pauseTimestamp = clock.elapsedMs + 3_000L
        controller.currentPlaybackState = PlaybackStateSnapshot(
            status = PlaybackStatus.PAUSED,
            positionMs = 5_000L,
            playbackSpeed = 0f,
            lastPositionUpdateTimeMs = pauseTimestamp
        )

        // Liveness check fires at +10s
        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        runCurrent()

        val tracking = coordinator.nowTracking.value
        assertNotNull(tracking)
        assertFalse("Silent pause must be detected by liveness check", tracking!!.isPlaying)
        assertEquals(5_000L, tracking.accumulatedMs)
        assertFalse("Liveness check must stop when paused", coordinator.isLivenessCheckScheduled)

        coordinator.flushAndStop().join()
        assertEquals(1, repo.recorded.size)
        assertEquals(5_000L, repo.recorded[0].actualListenedMs)
    }

    @Test
    fun livenessCheck_detectsNullLiveStateAndEndsSession() = runTest {
        val clock = FakeClock(elapsedMs = 10_000L)
        val repo = RecordingListeningRepository()
        val coordinator = createCoordinator(this, clock, repo, livenessIntervalMs = 10_000L)
        coordinator.start()
        runCurrent()

        val controller = FakeController(
            tokenId = "tok-1",
            packageName = pkg,
            currentMetadata = song,
            currentPlaybackState = PlaybackStateSnapshot(
                status = PlaybackStatus.PLAYING,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                lastPositionUpdateTimeMs = clock.elapsedMs
            )
        )
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()

        // Pass debounce
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(coordinator.isLivenessCheckScheduled)

        // Controller dies silently (currentPlaybackState == null)
        controller.currentPlaybackState = null

        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        runCurrent()

        assertNull("Dead session must be ended cleanly", coordinator.nowTracking.value)
        assertFalse(coordinator.isLivenessCheckScheduled)
        assertEquals(1, repo.recorded.size)
        assertEquals(12_000L, repo.recorded[0].actualListenedMs)

        coordinator.flushAndStop().join()
    }

    @Test
    fun livenessCheck_notScheduledWhenPausedOrIdle() = runTest {
        val clock = FakeClock(elapsedMs = 10_000L)
        val repo = RecordingListeningRepository()
        val coordinator = createCoordinator(this, clock, repo, livenessIntervalMs = 10_000L)
        coordinator.start()
        runCurrent()

        // Idle -> zero liveness job
        assertFalse(coordinator.isLivenessCheckScheduled)

        val controller = FakeController(
            tokenId = "tok-1",
            packageName = pkg,
            currentMetadata = song,
            currentPlaybackState = PlaybackStateSnapshot(
                status = PlaybackStatus.PLAYING,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                lastPositionUpdateTimeMs = clock.elapsedMs
            )
        )
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()

        // Debouncing -> liveness check not scheduled yet
        assertFalse(coordinator.isLivenessCheckScheduled)

        // Active -> liveness check scheduled
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(coordinator.isLivenessCheckScheduled)

        // Paused -> liveness check immediately cancelled
        val pausedState = PlaybackStateSnapshot(
            status = PlaybackStatus.PAUSED,
            positionMs = 2_000L,
            playbackSpeed = 0f,
            lastPositionUpdateTimeMs = clock.elapsedMs
        )
        controller.currentPlaybackState = pausedState
        controller.listener?.onPlaybackStateChanged(pausedState)
        runCurrent()
        assertFalse("Liveness check must NOT be scheduled while paused", coordinator.isLivenessCheckScheduled)

        coordinator.flushAndStop().join()
    }

    @Test
    fun uninterruptedPlayback_survivesMultipleLivenessPollsAndRecordsFullDuration() = runTest {
        val clock = FakeClock(elapsedMs = 10_000L)
        val repo = RecordingListeningRepository()
        val coordinator = createCoordinator(this, clock, repo, livenessIntervalMs = 60_000L)
        coordinator.start()
        runCurrent()

        // Real Android MediaSession behavior: PLAYING state published once at t=0
        // and never mutates positionMs or lastPositionUpdateTimeMs during uninterrupted playback.
        val initialPlayingState = PlaybackStateSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = 0L,
            playbackSpeed = 1.0f,
            lastPositionUpdateTimeMs = clock.elapsedMs
        )
        val controller = FakeController(
            tokenId = "tok-1",
            packageName = pkg,
            currentMetadata = song, // durationMs = 230_000L
            currentPlaybackState = initialPlayingState
        )
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()

        // Pass 1.5s debounce -> becomes ACTIVE and schedules livenessJob.
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        assertNotNull(coordinator.nowTracking.value)
        assertTrue(coordinator.nowTracking.value!!.isPlaying)
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)
        assertTrue(coordinator.isLivenessCheckScheduled)

        // Survive 3 consecutive 60s livenessJob polls (at +62s, +122s, +182s) with unchanged state.
        repeat(3) { pollIndex ->
            clock.advanceBy(60_000L)
            advanceTimeBy(60_000L)
            runCurrent()
            val live = coordinator.nowTracking.value
            assertNotNull("Session must remain active after liveness poll #${pollIndex + 1}", live)
            assertTrue("Session must remain playing after liveness poll #${pollIndex + 1}", live!!.isPlaying)
            assertTrue("Liveness check must remain scheduled", coordinator.isLivenessCheckScheduled)
        }

        // Advance remaining 48s to reach full 230_000ms song duration and stop cleanly.
        clock.advanceBy(48_000L)
        advanceTimeBy(48_000L)
        runCurrent()

        val stoppedState = PlaybackStateSnapshot(
            status = PlaybackStatus.STOPPED,
            positionMs = 230_000L,
            playbackSpeed = 0f,
            lastPositionUpdateTimeMs = clock.elapsedMs
        )
        controller.currentPlaybackState = stoppedState
        controller.listener?.onPlaybackStateChanged(stoppedState)
        runCurrent()

        assertEquals(1, repo.recorded.size)
        assertEquals(230_000L, repo.recorded[0].actualListenedMs)
        assertFalse(repo.recorded[0].wasSkipped)

        coordinator.flushAndStop().join()
    }
}
