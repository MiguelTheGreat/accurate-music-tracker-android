package com.musictracker.app.e2e.tier1

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 — Area B: Play-Session State Machine (Pure JVM, F09–F15).
 */
class T1PlaySessionStateMachineE2eTest {

    private lateinit var clock: FakeTrackerClock
    private lateinit var engine: PlaySessionEngine

    private val ytMusicPkg = "com.google.android.apps.youtube.music"

    @Before
    fun setUp() {
        clock = FakeTrackerClock()
        engine = PlaySessionStateMachine(
            clock = clock,
            normalizer = MusicMetadataNormalizer()
        )
    }

    @Test
    fun t1_b1_wallClockAccumulationExcludesPausedTime() {
        val track1 = MediaMetadataSnapshot(title = "Track One", artist = "Artist A", durationMs = 200_000L)
        val track2 = MediaMetadataSnapshot(title = "Track Two", artist = "Artist A", durationMs = 200_000L)

        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = track1,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        // Play 20s
        clock.advanceBy(20_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 20_000L)
            )
        )
        // Pause 60s (must not count)
        clock.advanceBy(60_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 20_000L)
            )
        )
        // Play another 15s
        clock.advanceBy(15_000L)

        // Track change finalizes track1
        val completed = engine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(sourcePackage = ytMusicPkg, metadata = track2)
        )

        assertEquals(1, completed.size)
        assertEquals("Track One", completed.single().rawTitle)
        assertEquals(35_000L, completed.single().actualListenedMs)
    }

    @Test
    fun t1_b2_debouncePromotionAt1500ms() {
        val track = MediaMetadataSnapshot(title = "Digital Love", artist = "Daft Punk", durationMs = 240_000L)

        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        val initial = engine.nowTracking.value
        assertNotNull(initial)
        assertTrue(initial!!.isDebouncing)

        clock.advanceBy(2_000L)
        engine.onTick()

        val promoted = engine.nowTracking.value
        assertNotNull(promoted)
        assertFalse(promoted!!.isDebouncing)

        val completed = engine.onSessionEnded(ytMusicPkg)
        assertEquals(1, completed.size)
        assertEquals(2_000L, completed.single().actualListenedMs)
    }

    @Test
    fun t1_b3_lateArrivingDurationAndAlbumPatchPreservesStartAndAccumulation() {
        val startEpoch = clock.currentTimeMillis()
        val partialMeta = MediaMetadataSnapshot(
            title = "Aerodynamic",
            artist = "Daft Punk",
            album = "",
            durationMs = 0L
        )
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = partialMeta,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )

        clock.advanceBy(900L)
        val fullMeta = MediaMetadataSnapshot(
            title = "Aerodynamic",
            artist = "Daft Punk",
            album = "Discovery",
            durationMs = 210_000L,
            artworkUri = "content://art/discovery"
        )
        val midCompleted = engine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(sourcePackage = ytMusicPkg, metadata = fullMeta)
        )
        assertTrue(midCompleted.isEmpty())

        clock.advanceBy(99_100L) // total 100s
        val completed = engine.flushActiveSession()
        assertEquals(1, completed.size)
        val session = completed.single()
        assertEquals(startEpoch, session.startedAtEpoch)
        assertEquals(100_000L, session.actualListenedMs)
        assertEquals(210_000L, session.durationMs)
        assertEquals("Discovery", session.album)
        assertEquals("content://art/discovery", session.artworkUri)
    }

    @Test
    fun t1_b4_singleTrackLoopDetectionFinalizesAndStartsNewSession() {
        val track = MediaMetadataSnapshot(title = "Loop Song", artist = "Loop Artist", durationMs = 200_000L)

        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        // Advance through track to position 196s (>= duration - 5s)
        clock.advanceBy(196_000L)
        val preLoop = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 196_000L)
            )
        )
        assertTrue(preLoop.isEmpty())

        // Track loops back to ~300ms while still PLAYING
        clock.advanceBy(1_000L)
        val loopCompleted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 300L)
            )
        )

        assertEquals(1, loopCompleted.size)
        assertEquals(197_000L, loopCompleted.single().actualListenedMs)
        assertFalse(loopCompleted.single().wasSkipped)

        val newTracking = engine.nowTracking.value
        assertNotNull(newTracking)
        assertTrue(newTracking!!.isPlaying)
        assertEquals(0L, newTracking.accumulatedMs)
    }

    @Test
    fun t1_b5_scrubbingBackCountsTrueWallClockWithoutSpuriousSplit() {
        val track = MediaMetadataSnapshot(title = "Chorus Replay", artist = "Artist", durationMs = 200_000L)

        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(40_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 40_000L)
            )
        )
        // User scrubs back from 40s to 10s
        val scrubResult = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 10_000L)
            )
        )
        assertTrue(scrubResult.isEmpty())

        // Plays another 50s
        clock.advanceBy(50_000L)
        val completed = engine.onSessionEnded(ytMusicPkg)
        assertEquals(1, completed.size)
        assertEquals(90_000L, completed.single().actualListenedMs)
    }

    @Test
    fun t1_b6_completionRatioAndSkipClassification() {
        val trackA = MediaMetadataSnapshot(title = "Full Listen", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = trackA,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(180_000L)
        val fullSession = engine.onSessionEnded(ytMusicPkg).single()
        assertEquals(0.90f, fullSession.completedRatio, 0.001f)
        assertFalse(fullSession.wasSkipped)

        val trackB = MediaMetadataSnapshot(title = "Skipped Track", artist = "Artist", durationMs = 200_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = trackB,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(30_000L)
        val skippedSession = engine.onSessionEnded(ytMusicPkg).single()
        assertEquals(0.15f, skippedSession.completedRatio, 0.001f)
        assertTrue(skippedSession.wasSkipped)
    }

    @Test
    fun t1_b7_liveNowTrackingStateEmissionAcrossPlayPauseStop() {
        assertNull(engine.nowTracking.value)

        val track = MediaMetadataSnapshot(title = "Live Track (Official Audio)", artist = "Band - Topic", durationMs = 180_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusicPkg,
                metadata = track,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )

        val playing = engine.nowTracking.value
        assertNotNull(playing)
        assertTrue(playing!!.isPlaying)
        assertNotNull(playing.segmentStartElapsedMs)
        assertEquals("Live Track", playing.normalizedTitle)
        assertEquals("Band", playing.normalizedArtist)

        clock.advanceBy(10_000L)
        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 10_000L)
            )
        )

        val paused = engine.nowTracking.value
        assertNotNull(paused)
        assertFalse(paused!!.isPlaying)
        assertNull(paused.segmentStartElapsedMs)
        assertEquals(10_000L, paused.accumulatedMs)

        engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = ytMusicPkg,
                state = PlaybackStateSnapshot(PlaybackStatus.STOPPED, positionMs = 10_000L)
            )
        )
        assertNull(engine.nowTracking.value)
    }
}
