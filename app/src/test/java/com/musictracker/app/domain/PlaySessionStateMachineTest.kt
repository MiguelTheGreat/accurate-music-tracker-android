package com.musictracker.app.domain

import app.cash.turbine.test
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaySessionStateMachineTest {

    private class FakeTrackerClock(
        var elapsedMs: Long = 10_000L,
        var epochMs: Long = 1_700_000_000_000L
    ) : TrackerClock {
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun currentTimeMillis(): Long = epochMs

        fun advanceBy(ms: Long) {
            elapsedMs += ms
            epochMs += ms
        }
    }

    private lateinit var clock: FakeTrackerClock
    private lateinit var engine: PlaySessionStateMachine

    private val ytMusicPkg = "com.google.android.apps.youtube.music"
    private val spotifyPkg = "com.spotify.music"

    private val blindingLights = MediaMetadataSnapshot(
        title = "Blinding Lights (Official Audio)",
        artist = "The Weeknd - Topic",
        album = "After Hours",
        durationMs = 200_000L,
        artworkUri = "content://art/blinding"
    )

    private val starboy = MediaMetadataSnapshot(
        title = "Starboy",
        artist = "The Weeknd",
        album = "Starboy",
        durationMs = 230_000L,
        artworkUri = "content://art/starboy"
    )

    @Before
    fun setUp() {
        clock = FakeTrackerClock()
        engine = PlaySessionStateMachine(
            clock = clock,
            normalizer = MusicMetadataNormalizer()
        )
    }

    // --- F10 / E01: Debouncing ---

    @Test
    fun debounce_shortPlayBelowThresholdDiscardedOnTrackChange() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        assertTrue(engine.nowTracking.value!!.isDebouncing)

        clock.advanceBy(800L)
        val emitted = engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, starboy))
        assertTrue(emitted.isEmpty())
        assertEquals("Starboy", engine.nowTracking.value?.normalizedTitle)
        assertTrue(engine.nowTracking.value!!.isDebouncing)
    }

    @Test
    fun debounce_shortPlayDiscardedOnStopSessionEndAndFlush() {
        // Stop before 1500ms
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_499L)
        assertTrue(
            engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
                .isEmpty()
        )
        assertNull(engine.nowTracking.value)

        // onSessionEnded before 1500ms
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_000L)
        assertTrue(engine.onSessionEnded(ytMusicPkg).isEmpty())
        assertNull(engine.nowTracking.value)

        // flushActiveSession before 1500ms
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_499L)
        assertTrue(engine.flushActiveSession().isEmpty())
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun debounce_exactThreshold1500msPromotesAndPersistsWithoutPriorTick() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_500L)

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emitted.size)
        assertEquals(1_500L, emitted[0].actualListenedMs)
        assertTrue(emitted[0].wasSkipped)
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun debounce_onTickPromotesPendingToActiveWithoutFurtherCallbacks() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(1_499L)
        assertTrue(engine.onTick().isEmpty())
        assertTrue(engine.nowTracking.value!!.isDebouncing)

        clock.advanceBy(1L)
        assertTrue(engine.onTick().isEmpty())
        assertFalse(engine.nowTracking.value!!.isDebouncing)
        assertTrue(engine.nowTracking.value!!.isPlaying)
    }

    @Test
    fun debounce_bufferingFlickerDuringPendingAccumulatesAcrossSegments() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(800L)

        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.BUFFERING, 800L)))
        assertTrue(engine.nowTracking.value!!.isDebouncing)
        assertEquals(800L, engine.nowTracking.value!!.accumulatedMs)
        assertFalse(engine.nowTracking.value!!.isPlaying)

        clock.advanceBy(500L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 800L)))
        clock.advanceBy(700L)
        engine.onTick()

        assertFalse(engine.nowTracking.value!!.isDebouncing)
        assertEquals(800L, engine.nowTracking.value!!.accumulatedMs)
    }

    // --- F11 / E02, E03: Late Metadata Patching & Blank Title Rejection ---

    @Test
    fun lateMetadata_durationPatchPreservesStartAndAccumulatedTime() {
        val initialMeta = MediaMetadataSnapshot(
            title = "Blinding Lights (Official Audio)",
            artist = "The Weeknd - Topic",
            album = "",
            durationMs = 0L,
            artworkUri = null
        )
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, initialMeta))

        clock.advanceBy(200L)
        val expectedStartedAt = clock.epochMs
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(900L)
        val patchedMeta = initialMeta.copy(
            album = "After Hours",
            durationMs = 210_000L,
            artworkUri = "content://art/after_hours"
        )
        val midEmitted = engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, patchedMeta))
        assertTrue(midEmitted.isEmpty())
        assertEquals(210_000L, engine.nowTracking.value!!.durationMs)
        assertEquals("After Hours", engine.nowTracking.value!!.album)
        assertEquals("content://art/after_hours", engine.nowTracking.value!!.artworkUri)

        clock.advanceBy(600L)
        engine.onTick()
        assertFalse(engine.nowTracking.value!!.isDebouncing)

        clock.advanceBy(98_500L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, emitted.size)
        assertEquals(expectedStartedAt, emitted[0].startedAtEpoch)
        assertEquals(210_000L, emitted[0].durationMs)
        assertEquals("After Hours", emitted[0].album)
        assertEquals("content://art/after_hours", emitted[0].artworkUri)
        assertEquals(100_000L, emitted[0].actualListenedMs)
    }

    @Test
    fun lateMetadata_albumAndArtworkNeverOverwrittenByBlankOrZero() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(2_000L)
        val degradedMeta = blindingLights.copy(album = "   ", durationMs = 0L, artworkUri = null)
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, degradedMeta))

        val tracking = engine.nowTracking.value!!
        assertEquals("After Hours", tracking.album)
        assertEquals(200_000L, tracking.durationMs)
        assertEquals("content://art/blinding", tracking.artworkUri)
    }

    @Test
    fun blankTitleMetadata_ignoredInIdlePendingAndActiveStates() {
        val blankMeta = MediaMetadataSnapshot(title = "   ", artist = "The Weeknd")

        // IDLE
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blankMeta))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        assertNull(engine.nowTracking.value)

        // Start valid session -> PENDING
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        assertNotNull(engine.nowTracking.value)
        clock.advanceBy(500L)
        assertTrue(engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blankMeta)).isEmpty())
        assertEquals("Blinding Lights", engine.nowTracking.value?.normalizedTitle)

        // Promote to ACTIVE -> blank metadata still ignored
        clock.advanceBy(2_000L)
        engine.onTick()
        assertFalse(engine.nowTracking.value!!.isDebouncing)
        assertTrue(engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blankMeta)).isEmpty())
        assertEquals("Blinding Lights", engine.nowTracking.value?.normalizedTitle)
    }

    // --- F09: Wall-Clock Accumulation ---

    @Test
    fun wallClock_multiplePauseResumeSegmentsSumAccurately() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(10_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 10_000L)))
        clock.advanceBy(50_000L)

        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 10_000L)))
        clock.advanceBy(20_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.BUFFERING, 30_000L)))
        clock.advanceBy(5_000L)

        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 30_000L)))
        clock.advanceBy(15_000L)

        val emitted = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertEquals(1, emitted.size)
        assertEquals(45_000L, emitted[0].actualListenedMs)
    }

    @Test
    fun wallClock_duplicatePlayingAndPausedEventsAreIdempotent() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        val initialSegmentStart = engine.nowTracking.value!!.segmentStartElapsedMs

        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 5_000L)))
        assertEquals(initialSegmentStart, engine.nowTracking.value!!.segmentStartElapsedMs)

        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 10_000L)))

        // Duplicate PAUSED 2 min later must NOT reset pausedSinceElapsedMs
        clock.advanceBy(2L * 60L * 1_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 10_000L)))
        assertNotNull(engine.nowTracking.value)

        // 1 more minute (total 3 min paused) -> times out and finalizes
        clock.advanceBy(1L * 60L * 1_000L)
        val emitted = engine.onTick()
        assertEquals(1, emitted.size)
        assertEquals(10_000L, emitted[0].actualListenedMs)
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun wallClock_negativeClockDeltaClampedToZero() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        val startedEpoch = clock.epochMs

        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 5_000L)))

        // Resume and then move clock backwards
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 5_000L)))
        clock.elapsedMs -= 3_000L
        clock.epochMs = startedEpoch - 10_000L

        val emitted = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertEquals(1, emitted.size)
        assertEquals(5_000L, emitted[0].actualListenedMs)
        assertTrue(emitted[0].endedAtEpoch >= emitted[0].startedAtEpoch)
    }

    // --- F12 & F13 / E04, E05, E06, E07, E08: Loop Detection & Scrubbing ---

    @Test
    fun loopDetection_nearEndDropToZeroEmitsSessionAndStartsRepeat() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(196_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 196_000L)))

        clock.advanceBy(500L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 300L))
        )
        assertEquals(1, emitted.size)
        assertEquals(196_500L, emitted[0].actualListenedMs)
        assertFalse(emitted[0].wasSkipped)

        val repeatState = engine.nowTracking.value!!
        assertEquals("Blinding Lights", repeatState.normalizedTitle)
        assertEquals(0L, repeatState.accumulatedMs)
        assertTrue(repeatState.isPlaying)
        assertTrue(repeatState.isDebouncing)
    }

    @Test
    fun scrubbing_midTrackSeekBackToZeroDoesNotSplitSession() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(90_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 90_000L)))

        val midEmitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertTrue(midEmitted.isEmpty())

        clock.advanceBy(120_000L)
        val finalEmitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED))
        )
        assertEquals(1, finalEmitted.size)
        assertEquals(210_000L, finalEmitted[0].actualListenedMs)
        assertEquals(1.05f, finalEmitted[0].completedRatio, 0.001f)
        assertFalse(finalEmitted[0].wasSkipped)
    }

    @Test
    fun loopDetection_pauseAtEndSeekToZeroThenResumeTriggersLoop() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(198_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 198_000L)))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 198_000L)))

        clock.advanceBy(2_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 0L)))

        clock.advanceBy(1_000L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertEquals(1, emitted.size)
        assertEquals(198_000L, emitted[0].actualListenedMs)
        assertFalse(emitted[0].wasSkipped)
        assertNotNull(engine.nowTracking.value)
        assertTrue(engine.nowTracking.value!!.isPlaying)
        assertTrue(engine.nowTracking.value!!.isDebouncing)
    }

    @Test
    fun loopDetection_shortInterlude12sTriggersLoop() {
        val interlude = MediaMetadataSnapshot(
            title = "Interlude",
            artist = "Artist",
            durationMs = 12_000L
        )
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, interlude))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(11_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 11_000L)))

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertEquals(1, emitted.size)
        assertEquals(11_000L, emitted[0].actualListenedMs)
        assertFalse(emitted[0].wasSkipped)
    }

    @Test
    fun loopDetection_unknownDurationDisablesLoopDetection() {
        val stream = MediaMetadataSnapshot(
            title = "Live Stream",
            artist = "DJ",
            durationMs = 0L
        )
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, stream))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(198_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 198_000L)))

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun loopDetection_rapidScrubToEndAndBackBelowMinListenedDoesNotLoop() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(3_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 198_000L)))

        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun loopDetection_unknownPositionNegativeOneIgnored() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))

        clock.advanceBy(198_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 198_000L)))

        // Unknown position (-1L) must neither trigger a loop nor overwrite lastPlayingPositionMs
        assertTrue(
            engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, -1L)))
                .isEmpty()
        )

        // Subsequent drop to 0L still detects the loop cleanly
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertEquals(1, emitted.size)
        assertFalse(emitted[0].wasSkipped)
    }

    // --- F14 / E09, E17, E18: Idle Timeout & Skip Classification ---

    @Test
    fun idlePauseTimeout_30MinPauseFinalizesOnTickAndResumeStartsFresh() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(120_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 120_000L)))

        // 2m59s -> still alive (DEFAULT_IDLE_PAUSE_TIMEOUT_MS = 3m)
        clock.advanceBy(2L * 60L * 1_000L + 59_000L)
        assertTrue(engine.onTick().isEmpty())
        assertNotNull(engine.nowTracking.value)

        // 3m00s -> finalized
        clock.advanceBy(1_000L)
        val emitted = engine.onTick()
        assertEquals(1, emitted.size)
        assertEquals(120_000L, emitted[0].actualListenedMs)
        assertFalse(emitted[0].wasSkipped)
        assertNull(engine.nowTracking.value)

        // Resume 2 hours later -> fresh PENDING session using cached metadata
        clock.advanceBy(2L * 60L * 60L * 1_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 120_000L)))
        val resumed = engine.nowTracking.value!!
        assertEquals("Blinding Lights", resumed.normalizedTitle)
        assertEquals(0L, resumed.accumulatedMs)
        assertTrue(resumed.isDebouncing)
        assertTrue(resumed.isPlaying)
    }

    @Test
    fun idlePauseTimeout_pendingSessionDiscardedAfter30Min() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(800L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 800L)))

        clock.advanceBy(3L * 60L * 1_000L)
        assertTrue(engine.onTick().isEmpty())
        assertNull(engine.nowTracking.value)
    }

    @Test
    fun skipClassification_unknownDurationThreshold30s() {
        val unknownDur = MediaMetadataSnapshot(title = "Radio Track", artist = "Host", durationMs = 0L)

        // 29_999ms -> skipped
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, unknownDur))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(29_999L)
        val shortSession = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertEquals(1, shortSession.size)
        assertEquals(0f, shortSession[0].completedRatio, 0.0001f)
        assertTrue(shortSession[0].wasSkipped)

        // 30_000ms -> not skipped
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, unknownDur))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(30_000L)
        val boundarySession = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertEquals(1, boundarySession.size)
        assertEquals(0f, boundarySession[0].completedRatio, 0.0001f)
        assertFalse(boundarySession[0].wasSkipped)
    }

    @Test
    fun skipClassification_knownDurationThreshold50PercentAndOver100Percent() {
        // 99_999ms of 200_000ms -> skipped
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(99_999L)
        val skipped = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertTrue(skipped[0].wasSkipped)
        assertTrue(skipped[0].completedRatio < 0.50f)

        // 100_000ms of 200_000ms -> not skipped
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(100_000L)
        val exactHalf = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertFalse(exactHalf[0].wasSkipped)
        assertEquals(0.50f, exactHalf[0].completedRatio, 0.0001f)

        // 300_000ms of 200_000ms -> ratio 1.5f, not skipped
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(300_000L)
        val overPlayed = engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
        assertFalse(overPlayed[0].wasSkipped)
        assertEquals(1.5f, overPlayed[0].completedRatio, 0.0001f)
    }

    // --- E10 & Lifecycle ---

    @Test
    fun multiPackage_backgroundPauseIgnoredAndBackgroundPlaySwitchesFocus() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(30_000L)

        // Background package metadata & PAUSED must not disturb active package
        engine.onPlaybackEvent(PlaybackEvent.SessionSnapshot(spotifyPkg, starboy, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 0L)))
        assertEquals(ytMusicPkg, engine.nowTracking.value?.sourcePackage)

        // Background package starts PLAYING -> finalizes ytMusicPkg and switches focus to spotifyPkg
        clock.advanceBy(10_000L)
        val emitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(spotifyPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L))
        )
        assertEquals(1, emitted.size)
        assertEquals(ytMusicPkg, emitted[0].sourcePackage)
        assertEquals(40_000L, emitted[0].actualListenedMs)
        assertEquals(spotifyPkg, engine.nowTracking.value?.sourcePackage)
        assertEquals("Starboy", engine.nowTracking.value?.normalizedTitle)
    }

    @Test
    fun lifecycle_sessionSnapshotPausedThenPlayLaterStartsSession() {
        engine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(ytMusicPkg, blindingLights, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 5_000L))
        )
        assertNull(engine.nowTracking.value)

        clock.advanceBy(60_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 5_000L)))
        assertNotNull(engine.nowTracking.value)
        assertEquals("Blinding Lights", engine.nowTracking.value?.normalizedTitle)
        assertTrue(engine.nowTracking.value!!.isPlaying)
    }

    @Test
    fun lifecycle_outOfOrderPlayingBeforeMetadataStartsOnMetadata() {
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        assertNull(engine.nowTracking.value)

        clock.advanceBy(1_000L)
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        assertNotNull(engine.nowTracking.value)
        assertTrue(engine.nowTracking.value!!.isPlaying)
        assertEquals(clock.elapsedMs, engine.nowTracking.value!!.segmentStartElapsedMs)
    }

    @Test
    fun lifecycle_onSessionEndedIgnoresOtherPackageAndFinalizesActive() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(20_000L)

        assertTrue(engine.onSessionEnded(spotifyPkg).isEmpty())
        assertNotNull(engine.nowTracking.value)

        val emitted = engine.onSessionEnded(ytMusicPkg)
        assertEquals(1, emitted.size)
        assertEquals(20_000L, emitted[0].actualListenedMs)
        assertNull(engine.nowTracking.value)
    }

    // --- F15: Live NowTrackingState Flow ---

    @Test
    fun nowTrackingFlow_emitsExpectedLifecycleTransitions() = runTest {
        engine.nowTracking.test {
            assertNull(awaitItem())

            engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
            engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
            val pending = awaitItem()!!
            assertTrue(pending.isDebouncing)
            assertTrue(pending.isPlaying)
            assertEquals("Blinding Lights", pending.normalizedTitle)
            assertEquals("The Weeknd", pending.normalizedArtist)

            clock.advanceBy(1_500L)
            engine.onTick()
            val active = awaitItem()!!
            assertFalse(active.isDebouncing)
            assertTrue(active.isPlaying)

            clock.advanceBy(8_500L)
            engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 10_000L)))
            val paused = awaitItem()!!
            assertFalse(paused.isPlaying)
            assertNull(paused.segmentStartElapsedMs)
            assertEquals(10_000L, paused.accumulatedMs)

            engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.STOPPED)))
            assertNull(awaitItem())
        }
    }

    @Test
    fun activePlayingHeartbeats_suppressNowTrackingStateReallocation_whilePreservingLoopDetection() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_500L)
        engine.onTick()

        val activeRef = engine.nowTracking.value!!
        assertFalse(activeRef.isDebouncing)
        assertTrue(activeRef.isPlaying)

        val heartbeatPositions = listOf(5_000L, 30_000L, 60_000L, 120_000L, 198_000L)
        for (pos in heartbeatPositions) {
            clock.advanceBy(10_000L)
            val emitted = engine.onPlaybackEvent(
                PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, pos))
            )
            assertTrue(emitted.isEmpty())
            assertSame(activeRef, engine.nowTracking.value)
        }

        // Loop wrap from near end (198s) to start (500ms) must still emit & publish fresh PENDING state.
        clock.advanceBy(2_000L)
        val loopEmitted = engine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 500L))
        )
        assertEquals(1, loopEmitted.size)
        assertFalse(loopEmitted.single().wasSkipped)
        assertNotSame(activeRef, engine.nowTracking.value)
        assertTrue(engine.nowTracking.value!!.isDebouncing)
    }

    @Test
    fun activePlayingHeartbeats_stillPublishOnMetadataEnrichmentAndPhaseTransitions() {
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, blindingLights))
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 0L)))
        clock.advanceBy(1_500L)
        engine.onTick()

        val activeRef = engine.nowTracking.value!!

        // Late artwork enrichment must publish updated NowTrackingState.
        val enriched = blindingLights.copy(artworkUri = "content://art/updated")
        engine.onPlaybackEvent(PlaybackEvent.MetadataChanged(ytMusicPkg, enriched))
        val afterEnrich = engine.nowTracking.value!!
        assertNotSame(activeRef, afterEnrich)
        assertEquals("content://art/updated", afterEnrich.artworkUri)

        // Pause -> Resume transitions must publish.
        clock.advanceBy(5_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PAUSED, 6_500L)))
        assertFalse(engine.nowTracking.value!!.isPlaying)

        clock.advanceBy(2_000L)
        engine.onPlaybackEvent(PlaybackEvent.StateChanged(ytMusicPkg, PlaybackStateSnapshot(PlaybackStatus.PLAYING, 6_500L)))
        assertTrue(engine.nowTracking.value!!.isPlaying)
    }
}
