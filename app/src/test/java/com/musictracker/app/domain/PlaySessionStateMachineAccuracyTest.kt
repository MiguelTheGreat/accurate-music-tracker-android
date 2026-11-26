package com.musictracker.app.domain

import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionStateMachineAccuracyTest {

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

    private val pkg = "com.google.android.apps.youtube.music"
    private val blindingLights = MediaMetadataSnapshot(
        title = "Blinding Lights",
        artist = "The Weeknd",
        album = "After Hours",
        durationMs = 200_000L
    )

    @Test
    fun stalePause_usesLastPositionUpdateTimeMsInsteadOfNow() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        // Player actually paused at t = +60s, but Collecto only learns about it 2 hours later.
        val actualPauseElapsedMs = clock.elapsedMs + 60_000L
        clock.advanceBy(2L * 60L * 60L * 1_000L)

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PAUSED,
                    positionMs = 60_000L,
                    playbackSpeed = 0f,
                    lastPositionUpdateTimeMs = actualPauseElapsedMs
                )
            )
        )

        // Stale pause older than 3m idle timeout finalizes immediately with exact 60_000ms.
        assertEquals(1, emitted.size)
        assertEquals(60_000L, emitted[0].actualListenedMs)
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun dozeOrSilentKill_onSessionEndedCapsAtProjectedTrackEnd() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        // 3 hours pass with no callbacks (Doze / OEM kill) before session ends.
        clock.advanceBy(3L * 60L * 60L * 1_000L)
        val emitted = engine.onSessionEnded(pkg)

        assertEquals(1, emitted.size)
        val maxAllowed = blindingLights.durationMs + PlaySessionStateMachine.TRACK_OVERRUN_GRACE_MS
        assertTrue(
            "Expected actualListenedMs <= $maxAllowed but was ${emitted[0].actualListenedMs}",
            emitted[0].actualListenedMs <= maxAllowed
        )
        assertEquals(maxAllowed, emitted[0].actualListenedMs)
    }

    @Test
    fun playbackSpeedZero_treatedAsPausedNotAccumulating() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        clock.advanceBy(30_000L)
        // Player reports PLAYING with playbackSpeed = 0f -> treated as paused
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 30_000L,
                    playbackSpeed = 0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )
        assertNotNull(engine.nowTracking.value)
        assertFalse(engine.nowTracking.value!!.isPlaying)

        clock.advanceBy(60_000L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emitted.size)
        assertEquals(30_000L, emitted[0].actualListenedMs)
    }

    @Test
    fun stalledPosition_stopsAccumulatingAfterStallThreshold() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        // Advance normally to 20s
        clock.advanceBy(20_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 20_000L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        // Position freezes at 20_000L for 25s (> STALL_THRESHOLD_MS = 20_000L)
        clock.advanceBy(25_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 20_000L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        assertNotNull(engine.nowTracking.value)
        assertFalse("Stalled session should pause accumulation", engine.nowTracking.value!!.isPlaying)

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emitted.size)
        assertEquals(20_000L, emitted[0].actualListenedMs)
    }

    @Test
    fun stalePreExistingPlayingSession_ignoredOnAttach() {
        val clock = FakeClock(elapsedMs = 10_000_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        // Session claims PLAYING, but lastPositionUpdateTimeMs was 2 hours ago on a 200s track
        val twoHoursAgo = clock.elapsedMs - (2L * 60L * 60L * 1_000L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = blindingLights,
                state = PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 10_000L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = twoHoursAgo
                )
            )
        )

        assertTrue(emitted.isEmpty())
        assertNull("Stale pre-existing PLAYING session must not attach", engine.nowTracking.value)
    }

    @Test
    fun midTrackMetadataVariant_patchesInPlaceWithoutSplittingSession() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        val ytVariant = MediaMetadataSnapshot(
            title = "Blinding Lights (Official Audio)",
            artist = "The Weeknd - Topic",
            durationMs = 200_000L
        )
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, ytVariant))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )

        clock.advanceBy(60_000L)
        // Clean variant arrives mid-song -> same dedupKey, must NOT split session
        val midEmitted = engine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(pkg, blindingLights)
        )
        assertTrue("Mid-track metadata variant must not split session", midEmitted.isEmpty())

        clock.advanceBy(80_000L)
        val finalEmitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, finalEmitted.size)
        assertEquals(140_000L, finalEmitted[0].actualListenedMs)
        assertFalse(finalEmitted[0].wasSkipped)
    }

    @Test
    fun adInterruptingRealSong_finalizesSongAndDoesNotTrackAd() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )

        clock.advanceBy(40_000L)
        val adMeta = MediaMetadataSnapshot(
            title = "Advertisement",
            artist = "Spotify",
            durationMs = 30_000L
        )
        val emitted = engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, adMeta))

        assertEquals(1, emitted.size)
        assertEquals("Blinding Lights", emitted[0].rawTitle)
        assertEquals(40_000L, emitted[0].actualListenedMs)
        assertNull("Ad must not become active NowTracking", engine.nowTracking.value)

        // Playing ticks during the ad must NOT resurrect the previous song or track the ad
        clock.advanceBy(15_000L)
        val duringAd = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 15_000L))
        )
        assertTrue(duringAd.isEmpty())
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun regressionCheck_validScrubbingAndSingleTrackLoopPreserved() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        // Valid scrubbing: 300s listened on a 200s track -> 300_000L / 1.5f
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        clock.advanceBy(300_000L)
        val overPlayed = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, overPlayed.size)
        assertEquals(300_000L, overPlayed[0].actualListenedMs)
        assertEquals(1.5f, overPlayed[0].completedRatio, 0.001f)
        assertFalse(overPlayed[0].wasSkipped)

        // Single-track loop: near end -> 0ms splits cleanly
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        clock.advanceBy(197_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 197_000L))
        )
        val loopEmitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 200L))
        )
        assertEquals(1, loopEmitted.size)
        assertEquals(197_000L, loopEmitted[0].actualListenedMs)
        assertFalse(loopEmitted[0].wasSkipped)
        assertNotNull(engine.nowTracking.value)
        assertTrue(engine.nowTracking.value!!.isDebouncing)
    }

    @Test
    fun autoAdvance_longTrackAToShortTrackB_metadataBeforeStateStartsTrackBCleanly() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        val trackA = MediaMetadataSnapshot(
            title = "Long Track A",
            artist = "Artist A",
            album = "Album A",
            durationMs = 300_000L
        )
        val trackB = MediaMetadataSnapshot(
            title = "Short Track B",
            artist = "Artist B",
            album = "Album B",
            durationMs = 120_000L
        )

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, trackA))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        // Track A plays uninterrupted for its full 300s (unchanged PlaybackState).
        clock.advanceBy(300_000L)

        // Player auto-advances to 120s Track B and emits MetadataChanged BEFORE StateChanged.
        val emittedA = engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, trackB))
        assertEquals(1, emittedA.size)
        assertEquals("Long Track A", emittedA[0].rawTitle)
        assertEquals(300_000L, emittedA[0].actualListenedMs)

        val nowTrackingB = engine.nowTracking.value
        assertNotNull("Track B must start cleanly on auto-advance MetadataChanged", nowTrackingB)
        assertEquals("Short Track B", nowTrackingB!!.normalizedTitle)
        assertTrue("Track B must be actively playing", nowTrackingB.isPlaying)

        // Track B plays for 60s and stops.
        clock.advanceBy(60_000L)
        val emittedB = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emittedB.size)
        assertEquals("Short Track B", emittedB[0].rawTitle)
        assertEquals(60_000L, emittedB[0].actualListenedMs)
    }

    @Test
    fun playbackSpeedNaN_treatedAsPausedNotAccumulating() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 0L,
                    playbackSpeed = 1.0f,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        clock.advanceBy(25_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                pkg,
                PlaybackStateSnapshot(
                    status = PlaybackStatus.PLAYING,
                    positionMs = 25_000L,
                    playbackSpeed = Float.NaN,
                    lastPositionUpdateTimeMs = clock.elapsedMs
                )
            )
        )

        assertNotNull(engine.nowTracking.value)
        assertFalse("NaN playbackSpeed must be treated as paused", engine.nowTracking.value!!.isPlaying)

        clock.advanceBy(40_000L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(pkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emitted.size)
        assertEquals(25_000L, emitted[0].actualListenedMs)
    }

    @Test
    fun stuckPlayingWithoutFreshTelemetry_stoppedByOverrunGuardAfterDurationPlusGrace() {
        val clock = FakeClock(elapsedMs = 10_000L)
        val engine = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())

        val initialState = PlaybackStateSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = 0L,
            playbackSpeed = 1.0f,
            lastPositionUpdateTimeMs = clock.elapsedMs
        )
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(pkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(pkg, initialState))

        // Unchanged telemetry at +60s and +180s must NOT trigger false stall.
        clock.advanceBy(60_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(pkg, initialState))
        assertTrue(engine.nowTracking.value!!.isPlaying)

        clock.advanceBy(120_000L) // total 180s < 200s + 15s grace
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(pkg, initialState))
        assertTrue(engine.nowTracking.value!!.isPlaying)

        // Past duration + TRACK_OVERRUN_GRACE_MS (200s + 15s = 215s) -> overrun guard pauses at 215s.
        clock.advanceBy(60_000L) // total 240s
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(pkg, initialState))
        val paused = engine.nowTracking.value
        assertNotNull(paused)
        assertFalse("Stuck PLAYING beyond duration + grace must pause", paused!!.isPlaying)
        assertEquals(
            blindingLights.durationMs + PlaySessionStateMachine.TRACK_OVERRUN_GRACE_MS,
            paused.accumulatedMs
        )
    }
}
