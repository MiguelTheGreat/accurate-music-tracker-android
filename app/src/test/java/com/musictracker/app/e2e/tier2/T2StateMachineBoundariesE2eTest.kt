package com.musictracker.app.e2e.tier2

import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import com.musictracker.app.e2e.harness.FakeTrackerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 2 — Area B: Play-Session State Machine Boundaries (Pure JVM).
 */
class T2StateMachineBoundariesE2eTest {

    private lateinit var clock: FakeTrackerClock
    private lateinit var engine: PlaySessionEngine
    private val pkg = "com.spotify.music"

    @Before
    fun setUp() {
        clock = FakeTrackerClock()
        engine = PlaySessionStateMachine(
            clock = clock,
            normalizer = MusicMetadataNormalizer()
        )
    }

    @Test
    fun t2_b1_debounceDiscardAt1499msAndPersistAt1500ms() {
        val trackA = MediaMetadataSnapshot(title = "Transient Track", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = trackA,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(1_499L)
        val discarded = engine.onSessionEnded(pkg)
        assertTrue("1499ms listen must be discarded by 1500ms debounce", discarded.isEmpty())

        val trackB = MediaMetadataSnapshot(title = "Exact Threshold Track", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = trackB,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(1_500L)
        val persisted = engine.onSessionEnded(pkg)
        assertEquals("1500ms listen must be promoted and persisted", 1, persisted.size)
        assertEquals(1_500L, persisted.single().actualListenedMs)
    }

    @Test
    fun t2_b2_blankTitleIsRejectedWithoutCorruptingActiveSession() {
        val valid = MediaMetadataSnapshot(title = "Valid Track", artist = "Valid Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = valid,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(10_000L)

        // Blank and whitespace-only titles must be ignored
        val emptyResult = engine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(pkg, MediaMetadataSnapshot(title = "", artist = "X"))
        )
        val blankResult = engine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(pkg, MediaMetadataSnapshot(title = "   ", artist = "Y"))
        )
        assertTrue(emptyResult.isEmpty())
        assertTrue(blankResult.isEmpty())

        val current = engine.nowTracking.value
        assertNotNull(current)
        assertEquals("Valid Track", current!!.normalizedTitle)

        clock.advanceBy(10_000L)
        val completed = engine.onSessionEnded(pkg)
        assertEquals(1, completed.size)
        assertEquals("Valid Track", completed.single().rawTitle)
        assertEquals(20_000L, completed.single().actualListenedMs)
    }

    @Test
    fun t2_b3_midTrackSeekBackIsNotALoop() {
        val track = MediaMetadataSnapshot(title = "Mid Seek", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(90_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 90_000L))
        )

        // Seek back from 90s (45%) to 0s — NOT near end of track
        val seekResult = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L))
        )
        assertTrue("Mid-track seek-back must not split session", seekResult.isEmpty())

        clock.advanceBy(30_000L)
        val completed = engine.onSessionEnded(pkg)
        assertEquals(1, completed.size)
        assertEquals(120_000L, completed.single().actualListenedMs)
    }

    @Test
    fun t2_b4_shortInterludeLoopSplitsAndUnknownDurationDoesNotLoop() {
        // Short 12s interlude looping 11s -> 0s after >= 6s listened
        val interlude = MediaMetadataSnapshot(title = "Interlude", artist = "Artist", durationMs = 12_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = interlude,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(11_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 11_000L))
        )
        clock.advanceBy(500L)
        val loopCompleted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L))
        )
        assertEquals("Short interlude loop must finalize previous session", 1, loopCompleted.size)
        assertFalse(loopCompleted.single().wasSkipped)
        engine.onSessionEnded(pkg)

        // Unknown duration (0ms) jumping to 0s must NOT trigger loop split
        val unknownDur = MediaMetadataSnapshot(title = "Stream", artist = "Radio", durationMs = 0L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = unknownDur,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(60_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 60_000L))
        )
        val noLoop = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L))
        )
        assertTrue("Unknown duration track must not trigger loop split", noLoop.isEmpty())
    }

    @Test
    fun t2_b5_negativeClockDeltaClampsToZeroAnd30MinIdlePauseFinalizes() {
        val track = MediaMetadataSnapshot(title = "Idle Test", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        // Play 10s (10_000L -> 20_000L) and pause so accumulatedMs == 10_000L
        clock.advanceBy(10_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 10_000L))
        )
        assertEquals(10_000L, engine.nowTracking.value!!.accumulatedMs)

        // Resume at elapsedMs = 20_000L (segmentStartElapsedMs = 20_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 10_000L))
        )
        assertEquals(20_000L, engine.nowTracking.value!!.segmentStartElapsedMs)

        // Jump monotonic clock backward by -5_000L so now (15_000L) < segmentStartElapsedMs (20_000L)
        clock.advanceElapsedOnly(-5_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 10_000L))
        )
        // Strictly negative delta (-5_000L) must clamp to 0L and never reduce accumulatedMs
        assertEquals(10_000L, engine.nowTracking.value!!.accumulatedMs)

        // Advance >= 30 minutes while paused
        clock.advanceBy(PlaySessionStateMachine.DEFAULT_IDLE_PAUSE_TIMEOUT_MS)
        val timedOut = engine.onTick()
        assertEquals("Idle pause >= 30m must finalize session", 1, timedOut.size)
        assertEquals(10_000L, timedOut.single().actualListenedMs)
        assertEquals(null, engine.nowTracking.value)
    }

    @Test
    fun t2_b6_unknownDurationRatioAndReplayOver100PercentRatio() {
        // Unknown duration: completedRatio == 0f, wasSkipped = actual < 30s
        val streamShort = MediaMetadataSnapshot(title = "Short Stream", artist = "Artist", durationMs = 0L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = streamShort,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(20_000L)
        val shortSession = engine.onSessionEnded(pkg).single()
        assertEquals(0f, shortSession.completedRatio, 0.0001f)
        assertFalse(shortSession.completedRatio.isNaN())
        assertFalse(shortSession.completedRatio.isInfinite())
        assertTrue(shortSession.wasSkipped)

        val streamLong = MediaMetadataSnapshot(title = "Long Stream", artist = "Artist", durationMs = 0L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = streamLong,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(40_000L)
        val longSession = engine.onSessionEnded(pkg).single()
        assertEquals(0f, longSession.completedRatio, 0.0001f)
        assertFalse(longSession.wasSkipped)

        // Replay >100% via scrub-back: 300s of 200s track -> completedRatio == 1.5f
        val replay = MediaMetadataSnapshot(title = "Replay Track", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = replay,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(150_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 20_000L))
        )
        clock.advanceBy(150_000L)
        val replaySession = engine.onSessionEnded(pkg).single()
        assertEquals(300_000L, replaySession.actualListenedMs)
        assertEquals(1.5f, replaySession.completedRatio, 0.001f)
        assertFalse(replaySession.wasSkipped)
    }

    @Test
    fun t2_b7_duplicateStatePlayingCallbacksAreIdempotent() {
        val track = MediaMetadataSnapshot(title = "Idempotent", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        val initialSegmentStart = engine.nowTracking.value!!.segmentStartElapsedMs

        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 5_000L))
        )
        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 10_000L))
        )

        assertEquals(initialSegmentStart, engine.nowTracking.value!!.segmentStartElapsedMs)

        val completed = engine.onSessionEnded(pkg).single()
        assertEquals(10_000L, completed.actualListenedMs)
    }
}
