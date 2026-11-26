package com.musictracker.app.e2e.tier5

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.normalization.NormalizedTrack
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.e2e.harness.FakeTrackerClock
import com.musictracker.app.tracking.MediaSessionCoordinator
import com.musictracker.app.tracking.MediaSessionGateway
import com.musictracker.app.tracking.MusicNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier 5 — White-Box Adversarial Tests (Core: domain/, data/, tracking/).
 * Targets corner cases, concurrency, Unicode/SQLite edge cases, and failure resilience.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class T5CoreAdversarialTest {

    private lateinit var context: Context
    private val environments = mutableListOf<E2eTestEnvironment>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        environments.forEach { it.close() }
        environments.clear()
    }

    private fun newEnv(
        clock: FakeTrackerClock = FakeTrackerClock(),
        normalizer: MetadataNormalizer = MusicMetadataNormalizer(),
        fileBackedDb: Boolean = false
    ): E2eTestEnvironment {
        val env = E2eTestEnvironment(
            context = context,
            clock = clock,
            normalizer = normalizer,
            fileBackedDb = fileBackedDb
        )
        environments += env
        return env
    }

    // -------------------------------------------------------------------------
    // Domain Adversarial Tests (T5-CORE-01 .. T5-CORE-04)
    // -------------------------------------------------------------------------

    @Test
    fun t5_core01_shortTrackUnder5s_midTrackSeekDoesNotFalseLoop_nearEnd90PctStillLoops() {
        val clock = FakeTrackerClock()
        val sm = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())
        val pkg = "com.google.android.apps.youtube.music"
        val shortTrack = MediaMetadataSnapshot(
            title = "Short Interlude",
            artist = "Intro Artist",
            durationMs = 4_000L
        )

        // Start playing a 4-second track.
        assertTrue(
            sm.onPlaybackEvent(
                PlaybackEvent.SessionSnapshot(
                    sourcePackage = pkg,
                    metadata = shortTrack,
                    state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
                )
            ).isEmpty()
        )

        // Advance 2.1s (52.5% of 4s) and update position to 2_100ms — promotes to ACTIVE.
        clock.advanceBy(2_100L)
        assertTrue(
            sm.onPlaybackEvent(
                PlaybackEvent.StateChanged(
                    sourcePackage = pkg,
                    state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 2_100L)
                )
            ).isEmpty()
        )
        assertFalse(sm.nowTracking.value!!.isDebouncing)

        // Scrub back to 0ms from 52.5% — MUST NOT trigger a spurious single-track loop split.
        clock.advanceBy(100L)
        val afterMidScrub = sm.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = pkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        assertTrue("Mid-track scrub on <=5s track must not false-loop", afterMidScrub.isEmpty())

        // Now advance near the end (3_700ms = 92.5% >= 90% LOOP_END_RATIO) and loop back to 0ms.
        clock.advanceBy(3_700L)
        assertTrue(
            sm.onPlaybackEvent(
                PlaybackEvent.StateChanged(
                    sourcePackage = pkg,
                    state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 3_700L)
                )
            ).isEmpty()
        )

        clock.advanceBy(100L)
        val afterRealLoop = sm.onPlaybackEvent(
            PlaybackEvent.StateChanged(
                sourcePackage = pkg,
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        assertEquals("True near-end repeat (>=90%) on 4s track must emit 1 loop session", 1, afterRealLoop.size)
        assertFalse("Loop-completed session must not be marked skipped", afterRealLoop.single().wasSkipped)
        assertEquals(6_000L, afterRealLoop.single().actualListenedMs)
    }

    @Test
    fun t5_core02_punctuationAndEmojiOnlyTracks_produceDistinctDedupKeysAndRoomRows() = runBlocking {
        val env = newEnv()
        val normalizer = MusicMetadataNormalizer()

        val titles = listOf("!!!", "???", "🔥", "❤️")
        val normalizedList = titles.map { normalizer.normalize(rawTitle = it, rawArtist = "Chk Chk Chk") }
        val distinctKeys = normalizedList.map { it.dedupKey }.toSet()
        assertEquals("All 4 punctuation/emoji-only titles must produce distinct dedupKeys", 4, distinctKeys.size)

        // Also verify punctuation-only artist names don't collide.
        val artistKey1 = normalizer.normalize("Song", "!!!").dedupKey
        val artistKey2 = normalizer.normalize("Song", "???").dedupKey
        assertNotEquals("Punctuation-only artists must not collide", artistKey1, artistKey2)

        titles.forEachIndexed { idx, title ->
            val start = 1_750_000_000_000L + idx * 100_000L
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = title,
                    rawArtist = "Chk Chk Chk",
                    album = "Album $idx",
                    durationMs = 180_000L,
                    artworkUri = null,
                    startedAtEpoch = start,
                    endedAtEpoch = start + 60_000L,
                    actualListenedMs = 60_000L,
                    completedRatio = 0.33f,
                    wasSkipped = true
                )
            )
        }

        val tracks = env.database.trackDao().getAll()
        assertEquals("Room must persist 4 separate track rows without collision", 4, tracks.size)
    }

    @Test
    fun t5_core03_sessionSnapshotWithNullStateOnTrackChange_preservesPlayingContinuity() {
        val clock = FakeTrackerClock()
        val sm = PlaySessionStateMachine(clock = clock, normalizer = MusicMetadataNormalizer())
        val pkg = "com.google.android.apps.youtube.music"

        // Start Track A in PLAYING state.
        sm.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = MediaMetadataSnapshot(title = "Track A", artist = "Artist A", durationMs = 180_000L),
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        clock.advanceBy(10_000L)

        // Deliver SessionSnapshot for Track B with state = null while Track A is actively playing.
        val emitted = sm.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = pkg,
                metadata = MediaMetadataSnapshot(title = "Track B", artist = "Artist B", durationMs = 200_000L),
                state = null
            )
        )

        assertEquals("Track A should finalize cleanly", 1, emitted.size)
        assertEquals("Track A", emitted.single().rawTitle)
        assertEquals(10_000L, emitted.single().actualListenedMs)

        val nowTracking = sm.nowTracking.value
        assertNotNull("Track B should immediately start tracking", nowTracking)
        assertEquals("Track B", nowTracking!!.rawTitle)
        assertTrue("Track B should preserve playing continuity", nowTracking.isPlaying)
    }

    @Test
    fun t5_core04_rapidSkipChainUnderDebounce_discardsIntermediatesAndPromotesFinalTrackAtExactThreshold() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeTrackerClock()
        val env = newEnv(clock = clock)
        val scope = TestScope(dispatcher)
        val coordinator = env.createCoordinator(
            scope = scope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher,
            debounceMs = 1_500L
        )

        val controller = FakeMediaControllerDriver(
            packageName = "com.google.android.apps.youtube.music",
            initialMetadata = MediaMetadataSnapshot("Track 1", "Artist", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )

        coordinator.start()
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()
        assertTrue(coordinator.nowTracking.value!!.isDebouncing)

        // Play Track 1 for 800ms (< 1500ms) -> skip to Track 2.
        clock.advanceBy(800L)
        advanceTimeBy(800L)
        runCurrent()
        controller.emitMetadata(MediaMetadataSnapshot("Track 2", "Artist", durationMs = 180_000L))
        runCurrent()
        assertEquals("Track 2", coordinator.nowTracking.value!!.rawTitle)
        assertTrue(coordinator.nowTracking.value!!.isDebouncing)

        // Play Track 2 for 700ms (< 1500ms) -> skip to Track 3.
        clock.advanceBy(700L)
        advanceTimeBy(700L)
        runCurrent()
        controller.emitMetadata(MediaMetadataSnapshot("Track 3", "Artist", durationMs = 180_000L))
        runCurrent()
        assertEquals("Track 3", coordinator.nowTracking.value!!.rawTitle)
        assertTrue("Track 3 must start in debouncing state", coordinator.nowTracking.value!!.isDebouncing)

        // Advance 1_499ms on Track 3 -> still debouncing.
        clock.advanceBy(1_499L)
        advanceTimeBy(1_499L)
        runCurrent()
        assertTrue("Track 3 must still be debouncing at 1499ms", coordinator.nowTracking.value!!.isDebouncing)

        // Advance final 1ms (exact 1_500ms threshold) -> promotes to ACTIVE via rescheduled debounceJob.
        clock.advanceBy(1L)
        advanceTimeBy(1L)
        runCurrent()
        assertFalse("Track 3 must promote at exact 1500ms threshold", coordinator.nowTracking.value!!.isDebouncing)

        // Stop and flush: only Track 3 should be persisted; Tracks 1 & 2 discarded.
        clock.advanceBy(5_000L)
        coordinator.flushAndStop().join()
        runCurrent()

        val events = env.database.listenEventDao().getAll()
        val tracks = env.database.trackDao().getAll()
        assertEquals("Only Track 3 must be persisted", 1, events.size)
        assertEquals(1, tracks.size)
        assertEquals("Track 3", tracks.single().normalizedTitle)
        assertEquals(6_500L, events.single().actualListenedMs)
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Data Layer Adversarial Tests (T5-CORE-05 .. T5-CORE-09)
    // -------------------------------------------------------------------------

    @Test
    fun t5_core05_recordCompletedSession_sanitizesNaNInfinityAndNegativeMetrics() = runBlocking {
        val env = newEnv()

        // (a) Sessions with actualListenedMs <= 0L must be cleanly rejected (not inserted).
        val zeroOrNegativeSessions = listOf(
            Triple(Float.NaN, -5_000L, "Negative Duration Track"),
            Triple(Float.POSITIVE_INFINITY, -1L, "Negative One Track"),
            Triple(-0.5f, 0L, "Zero Duration Track")
        )
        zeroOrNegativeSessions.forEachIndexed { idx, (ratio, listenedMs, title) ->
            val start = 1_750_000_000_000L + idx * 10_000L
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = title,
                    rawArtist = "Sanitize Artist",
                    album = "",
                    durationMs = 180_000L,
                    artworkUri = null,
                    startedAtEpoch = start,
                    endedAtEpoch = start + 1_000L,
                    actualListenedMs = listenedMs,
                    completedRatio = ratio,
                    wasSkipped = true
                )
            )
        }
        assertTrue(
            "Sessions with actualListenedMs <= 0L must be rejected",
            env.database.listenEventDao().getAll().isEmpty()
        )

        // (b) Positive-duration sessions with NaN / Infinity / negative completedRatio
        //     (and endedAtEpoch < startedAtEpoch) must be persisted with sanitized metrics.
        val badRatioSessions = listOf(
            Triple(Float.NaN, 5_000L, "NaN Track"),
            Triple(Float.POSITIVE_INFINITY, 10_000L, "Inf Track"),
            Triple(-0.5f, 15_000L, "NegRatio Track")
        )
        badRatioSessions.forEachIndexed { idx, (ratio, listenedMs, title) ->
            val start = 1_750_000_100_000L + idx * 10_000L
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = title,
                    rawArtist = "Sanitize Artist",
                    album = "",
                    durationMs = 180_000L,
                    artworkUri = null,
                    startedAtEpoch = start,
                    endedAtEpoch = if (idx == 0) start - 5_000L else start + listenedMs,
                    actualListenedMs = listenedMs,
                    completedRatio = ratio,
                    wasSkipped = true
                )
            )
        }

        val events = env.database.listenEventDao().getAll()
        assertEquals(3, events.size)
        events.forEach { event ->
            assertTrue("actualListenedMs must remain positive", event.actualListenedMs > 0L)
            assertTrue("completedRatio must be finite", event.completedRatio.isFinite())
            assertEquals("completedRatio must be sanitized to 0f", 0f, event.completedRatio)
            assertTrue("endedAtEpoch must be >= startedAtEpoch", event.endedAtEpoch >= event.startedAtEpoch)
        }

        // Subsequent JSON export must succeed without JSONException on NaN/Infinity.
        val out = ByteArrayOutputStream()
        val exportResult = env.dataExporter.exportJson(out)
        assertTrue("JSON export must succeed after sanitization", exportResult is ExportResult.Success)
        val parsed = JSONObject(out.toString(Charsets.UTF_8.name()))
        assertEquals(3, parsed.getJSONArray("listenEvents").length())
    }

    @Test
    fun t5_core06_concurrentRecordCompletedSession_sameNewTrackDeduplicatesAndPatchesAtomically() = runBlocking {
        val env = newEnv()
        val baseStart = 1_750_000_000_000L

        val jobs = (0 until 8).map { idx ->
            async(Dispatchers.IO) {
                val hasFullMeta = idx % 2 == 1
                env.listeningRepository.recordCompletedSession(
                    CompletedListenSession(
                        sourcePackage = "com.google.android.apps.youtube.music",
                        rawTitle = "Concurrent Anthem (Official Audio)",
                        rawArtist = "Parallel Band - Topic",
                        album = if (hasFullMeta) "Full Album" else "",
                        durationMs = if (hasFullMeta) 215_000L else 0L,
                        artworkUri = if (hasFullMeta) "https://example.com/art.jpg" else null,
                        startedAtEpoch = baseStart + idx * 1_000L,
                        endedAtEpoch = baseStart + idx * 1_000L + 45_000L,
                        actualListenedMs = 45_000L,
                        completedRatio = 0.21f,
                        wasSkipped = true
                    )
                )
            }
        }
        jobs.awaitAll()

        val tracks = env.database.trackDao().getAll()
        val events = env.database.listenEventDao().getAll()
        assertEquals("All 8 concurrent writes must deduplicate into 1 track row", 1, tracks.size)
        assertEquals("All 8 listen events must be persisted", 8, events.size)

        val track = tracks.single()
        assertEquals("Full Album", track.album)
        assertEquals(215_000L, track.durationMs)
        assertEquals("https://example.com/art.jpg", track.artworkUriOrHash)
    }

    @Test
    fun t5_core07_renormalizeAllTracks_threeCycleDedupKeyRotationAndMultiDuplicateMetadataSalvage() = runBlocking {
        // Custom normalizer that rotates A->B->C->A and merges Dup1/Dup2/Dup3 into MergedKey.
        val rotatingNormalizer = object : MetadataNormalizer {
            override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack = when (rawTitle) {
                "CycleA" -> NormalizedTrack("CycleB", "Rotator", "rotator::cycleb")
                "CycleB" -> NormalizedTrack("CycleC", "Rotator", "rotator::cyclec")
                "CycleC" -> NormalizedTrack("CycleA", "Rotator", "rotator::cyclea")
                "Dup1", "Dup2", "Dup3" -> NormalizedTrack("Merged Song", "Merged Artist", "merged artist::merged song")
                else -> MusicMetadataNormalizer().normalize(rawTitle, rawArtist)
            }
        }

        val env = newEnv(normalizer = rotatingNormalizer)
        val trackDao = env.database.trackDao()
        val eventDao = env.database.listenEventDao()

        // Seed 3-cycle tracks with initial dedup keys A, B, C.
        val idA = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "CycleA",
                normalizedArtist = "Rotator",
                album = "AlbA",
                durationMs = 100_000L,
                rawTitle = "CycleA",
                rawArtist = "Rotator",
                artworkUriOrHash = null,
                dedupKey = "rotator::cyclea"
            )
        )
        val idB = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "CycleB",
                normalizedArtist = "Rotator",
                album = "AlbB",
                durationMs = 110_000L,
                rawTitle = "CycleB",
                rawArtist = "Rotator",
                artworkUriOrHash = null,
                dedupKey = "rotator::cycleb"
            )
        )
        val idC = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "CycleC",
                normalizedArtist = "Rotator",
                album = "AlbC",
                durationMs = 120_000L,
                rawTitle = "CycleC",
                rawArtist = "Rotator",
                artworkUriOrHash = null,
                dedupKey = "rotator::cyclec"
            )
        )

        // Seed 3 duplicate tracks where album comes from dup #2 and duration/artwork from dup #3.
        val dup1 = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "Dup1",
                normalizedArtist = "Artist",
                album = "",
                durationMs = 0L,
                rawTitle = "Dup1",
                rawArtist = "Artist",
                artworkUriOrHash = null,
                dedupKey = "artist::dup1"
            )
        )
        val dup2 = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "Dup2",
                normalizedArtist = "Artist",
                album = "Salvaged Album",
                durationMs = 0L,
                rawTitle = "Dup2",
                rawArtist = "Artist",
                artworkUriOrHash = null,
                dedupKey = "artist::dup2"
            )
        )
        val dup3 = trackDao.insertIgnore(
            TrackEntity(
                normalizedTitle = "Dup3",
                normalizedArtist = "Artist",
                album = "",
                durationMs = 240_000L,
                rawTitle = "Dup3",
                rawArtist = "Artist",
                artworkUriOrHash = "https://art/dup3.png",
                dedupKey = "artist::dup3"
            )
        )

        // Attach listen events to all 6 tracks.
        listOf(idA, idB, idC, dup1, dup2, dup3).forEachIndexed { idx, tid ->
            eventDao.insert(
                ListenEventEntity(
                    trackId = tid,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 1_750_000_000_000L + idx * 60_000L,
                    endedAtEpoch = 1_750_000_030_000L + idx * 60_000L,
                    actualListenedMs = 30_000L,
                    completedRatio = 0.5f,
                    wasSkipped = false
                )
            )
        }

        // First renormalize: must succeed without SQLiteConstraintException.
        val updatedCount = env.listeningRepository.renormalizeAllTracks()
        assertTrue("Expected tracks to be updated", updatedCount > 0)

        val remainingTracks = trackDao.getAll()
        assertEquals("3 cycle tracks + 1 merged duplicate = 4 tracks", 4, remainingTracks.size)

        val merged = remainingTracks.single { it.dedupKey == "merged artist::merged song" }
        assertEquals("Salvaged Album", merged.album)
        assertEquals(240_000L, merged.durationMs)
        assertEquals("https://art/dup3.png", merged.artworkUriOrHash)

        // All 6 listen events must still exist and point to valid tracks; 3 must point to merged.
        val allEvents = eventDao.getAll()
        assertEquals(6, allEvents.size)
        assertEquals(3, allEvents.count { it.trackId == merged.id })

        // Second renormalize with an idempotent normalizer must return 0.
        val stableRepo = ListeningRepositoryImpl(
            database = env.database,
            normalizer = object : MetadataNormalizer {
                override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack =
                    when (rawTitle) {
                        "CycleA" -> NormalizedTrack("CycleB", "Rotator", "rotator::cycleb")
                        "CycleB" -> NormalizedTrack("CycleC", "Rotator", "rotator::cyclec")
                        "CycleC" -> NormalizedTrack("CycleA", "Rotator", "rotator::cyclea")
                        else -> NormalizedTrack("Merged Song", "Merged Artist", "merged artist::merged song")
                    }
            }
        )
        assertEquals("Second renormalize pass must be idempotent (0 changes)", 0, stableRepo.renormalizeAllTracks())
    }

    @Test
    fun t5_core08_observeTopArtists_aggregatesDiacriticAndNonAsciiCaseVariants() = runBlocking {
        val env = newEnv()
        val base = 1_750_000_000_000L

        val sessions = listOf(
            Triple("Halo", "Beyoncé", 120_000L),
            Triple("Single Ladies", "Beyonce", 90_000L),
            Triple("La Vie En Rose", "Édith Piaf", 100_000L),
            Triple("Non, Je Ne Regrette Rien", "édith piaf", 80_000L)
        )

        sessions.forEachIndexed { idx, (title, artist, listenedMs) ->
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = title,
                    rawArtist = artist,
                    album = "",
                    durationMs = 200_000L,
                    artworkUri = null,
                    startedAtEpoch = base + idx * 200_000L,
                    endedAtEpoch = base + idx * 200_000L + listenedMs,
                    actualListenedMs = listenedMs,
                    completedRatio = listenedMs / 200_000f,
                    wasSkipped = false
                )
            )
        }

        val topArtists = env.listeningRepository.observeTopArtists(sinceEpochMs = 0L, limit = 10).first()
        assertEquals("Diacritic & non-ASCII case variants must collapse into 2 artists", 2, topArtists.size)

        // Beyonce/Beyoncé = 210_000ms, Edith Piaf = 180_000ms.
        assertEquals(210_000L, topArtists[0].totalListenedMs)
        assertEquals(2, topArtists[0].uniqueTrackCount)
        assertEquals(2, topArtists[0].playCount)

        assertEquals(180_000L, topArtists[1].totalListenedMs)
        assertEquals(2, topArtists[1].uniqueTrackCount)
        assertEquals(2, topArtists[1].playCount)
    }

    @Test
    fun t5_core09_exportJson_specialCharactersAndSqliteBackupWithActiveFlowCollector() = runBlocking {
        val env = newEnv(fileBackedDb = true)
        val specialTitle = "Quote \"Test\" \\ Backslash\nNewline\tTab 日本語 🎵🔥"
        val specialArtist = "Artist \"A\" \\ 音楽 🎧"

        // Subscribe an active Flow collector before writing & exporting.
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observedCounts = mutableListOf<Int>()
        val collectorJob = collectorScope.launch {
            env.listeningRepository.observeRecentHistory(limit = 10).collect { list ->
                synchronized(observedCounts) { observedCounts += list.size }
            }
        }

        try {
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.google.android.apps.youtube.music",
                    rawTitle = specialTitle,
                    rawArtist = specialArtist,
                    album = "Album \"Special\" \\ 1",
                    durationMs = 180_000L,
                    artworkUri = "https://example.com/a?b=1&c=\"2\"",
                    startedAtEpoch = 1_750_000_000_000L,
                    endedAtEpoch = 1_750_000_090_000L,
                    actualListenedMs = 90_000L,
                    completedRatio = 0.5f,
                    wasSkipped = false
                )
            )

            // 1) JSON round-trip with special characters.
            val jsonOut = ByteArrayOutputStream()
            val jsonResult = env.dataExporter.exportJson(jsonOut)
            assertTrue(jsonResult is ExportResult.Success)
            val root = JSONObject(jsonOut.toString(Charsets.UTF_8.name()))
            val trackJson = root.getJSONArray("tracks").getJSONObject(0)
            assertEquals(specialTitle, trackJson.getString("rawTitle"))
            assertEquals(specialArtist, trackJson.getString("rawArtist"))

            // 2) WAL-checkpointed SQLite backup while active Flow collector is subscribed.
            val backupFile = File(env.tempDir, "t5_backup.db")
            val sqliteResult = FileOutputStream(backupFile).use { fos ->
                env.dataExporter.exportSqliteBackup(fos)
            }
            assertTrue("SQLite backup must succeed with active Flow collector", sqliteResult is ExportResult.Success)
            assertTrue(backupFile.length() > 0L)

            val restoredDb = Room.databaseBuilder(
                context,
                MusicTrackerDatabase::class.java,
                backupFile.absolutePath
            ).allowMainThreadQueries().build()
            try {
                val restoredTracks = restoredDb.trackDao().getAll()
                val restoredEvents = restoredDb.listenEventDao().getAll()
                assertEquals(1, restoredTracks.size)
                assertEquals(1, restoredEvents.size)
                assertEquals(specialTitle, restoredTracks.single().rawTitle)
            } finally {
                restoredDb.close()
            }
        } finally {
            collectorJob.cancel()
            collectorScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // Tracking Engine Adversarial Tests (T5-CORE-10 .. T5-CORE-12)
    // -------------------------------------------------------------------------

    @Test
    fun t5_core10_persistSessionsFailure_doesNotCrashCoordinatorOrDropSubsequentSessions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeTrackerClock()
        val env = newEnv(clock = clock)
        val scope = TestScope(dispatcher)

        val attemptCounter = AtomicInteger(0)
        val persistedSessions = mutableListOf<CompletedListenSession>()
        val flakyRepo = object : ListeningRepository {
            override suspend fun recordCompletedSession(session: CompletedListenSession) {
                if (attemptCounter.incrementAndGet() == 1) {
                    throw RuntimeException("SQLiteFull simulated failure")
                }
                persistedSessions += session
                env.listeningRepository.recordCompletedSession(session)
            }

            override fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary> =
                env.listeningRepository.observeSummary(sinceEpochMs)

            override fun observeRecentHistory(limit: Int): Flow<List<RecentListenItem>> =
                env.listeningRepository.observeRecentHistory(limit)

            override fun observeTopTracks(sinceEpochMs: Long, limit: Int): Flow<List<TopTrackStat>> =
                env.listeningRepository.observeTopTracks(sinceEpochMs, limit)

            override fun observeTopArtists(sinceEpochMs: Long, limit: Int): Flow<List<TopArtistStat>> =
                env.listeningRepository.observeTopArtists(sinceEpochMs, limit)

            override suspend fun renormalizeAllTracks(): Int =
                env.listeningRepository.renormalizeAllTracks()
        }

        val coordinator = MediaSessionCoordinator(
            playSessionEngine = env.playSessionStateMachine,
            allowlistRepository = env.allowlistStore,
            listeningRepository = flakyRepo,
            clock = clock,
            scope = scope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher
        )

        val controller = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot("Failing Track", "Artist 1", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )

        coordinator.start()
        coordinator.onActiveSessionsChanged(listOf(controller))
        runCurrent()

        // Play Failing Track for 10s, then switch to Surviving Track (triggers first failing persist).
        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        controller.emitMetadata(MediaMetadataSnapshot("Surviving Track", "Artist 2", durationMs = 180_000L))
        runCurrent()

        // Play Surviving Track for 10s, then stop (triggers second succeeding persist).
        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        controller.stop()
        runCurrent()

        assertEquals("Coordinator must attempt both sessions without crashing", 2, attemptCounter.get())
        assertEquals("Second session must persist cleanly after first failed", 1, persistedSessions.size)
        assertEquals("Surviving Track", persistedSessions.single().rawTitle)

        coordinator.flushAndStop().join()
        scope.cancel()
    }

    @Test
    fun t5_core11_samePackageDualTokenBlankMetadataFocusSwitch_finalizesPreviousImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeTrackerClock()
        val env = newEnv(clock = clock)
        val scope = TestScope(dispatcher)
        val coordinator = env.createCoordinator(
            scope = scope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher
        )

        val ytmPkg = "com.google.android.apps.youtube.music"
        val localController = FakeMediaControllerDriver(
            packageName = ytmPkg,
            tokenId = "ytm_local_token",
            initialMetadata = MediaMetadataSnapshot("Local Song", "Local Artist", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        val castController = FakeMediaControllerDriver(
            packageName = ytmPkg,
            tokenId = "ytm_cast_token",
            initialMetadata = MediaMetadataSnapshot("", "", durationMs = 0L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )

        coordinator.start()
        coordinator.onActiveSessionsChanged(listOf(localController, castController))
        runCurrent()

        // Local token plays for 10 seconds.
        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        runCurrent()

        // Cast token (same package, blank metadata) starts PLAYING -> local session MUST finalize immediately.
        castController.play(positionMs = 0L)
        runCurrent()
        env.waitForEventCount(1)

        // Advance another 30 seconds while cast still has blank metadata — must NOT leak phantom time to Local Song.
        clock.advanceBy(30_000L)
        advanceTimeBy(30_000L)
        runCurrent()

        val events = env.database.listenEventDao().getAll()
        assertEquals("Local Song must finalize immediately on blank-metadata dual-token focus switch", 1, events.size)
        assertEquals("No phantom time should be attributed to Local Song", 10_000L, events.single().actualListenedMs)
        assertNull("NowTracking must be null while cast metadata is blank", coordinator.nowTracking.value)

        coordinator.flushAndStop().join()
        scope.cancel()
    }

    @Test
    fun t5_core12_rapidListenerConnectDisconnectReconnect_noCallbackLeaksAndIdempotentFlush() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeTrackerClock()
        val env = newEnv(clock = clock)
        val scope = TestScope(dispatcher)
        val coordinator = env.createCoordinator(
            scope = scope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher
        )

        val gateway = object : MediaSessionGateway {
            var registerCalls = 0
            var unregisterCalls = 0
            val activeListeners = mutableListOf<MediaSessionManager.OnActiveSessionsChangedListener>()

            override fun addOnActiveSessionsChangedListener(
                listener: MediaSessionManager.OnActiveSessionsChangedListener,
                notificationListener: ComponentName,
                handler: Handler
            ) {
                registerCalls++
                activeListeners += listener
            }

            override fun removeOnActiveSessionsChangedListener(
                listener: MediaSessionManager.OnActiveSessionsChangedListener
            ) {
                unregisterCalls++
                activeListeners.remove(listener)
            }

            override fun getActiveSessions(notificationListener: ComponentName): List<MediaController> = emptyList()
        }

        val serviceController = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = serviceController.get()
        service.coordinatorOverride = coordinator
        service.mediaSessionGatewayOverride = gateway

        // Cycle 1: Connect -> active play -> Disconnect.
        service.onListenerConnected()
        runCurrent()
        assertTrue(coordinator.isListenerConnected.value)

        val ctrl1 = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            tokenId = "cycle1_token",
            initialMetadata = MediaMetadataSnapshot("Cycle 1 Song", "Artist", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(ctrl1))
        clock.advanceBy(10_000L)
        advanceTimeBy(10_000L)
        runCurrent()

        service.onListenerDisconnected()
        runCurrent()
        env.waitForEventCount(1)
        assertFalse(coordinator.isListenerConnected.value)
        assertEquals(1, env.database.listenEventDao().getAll().size)

        // Cycle 2: Reconnect -> active play -> Destroy.
        service.onListenerConnected()
        runCurrent()
        assertTrue(coordinator.isListenerConnected.value)

        val ctrl2 = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            tokenId = "cycle2_token",
            initialMetadata = MediaMetadataSnapshot("Cycle 2 Song", "Artist", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(ctrl2))
        clock.advanceBy(12_000L)
        advanceTimeBy(12_000L)
        runCurrent()

        serviceController.destroy()
        runCurrent()
        env.waitForEventCount(2)

        assertFalse(coordinator.isListenerConnected.value)
        assertEquals("Exact 1-to-1 listener register/unregister counts", 2, gateway.registerCalls)
        assertEquals("Exact 1-to-1 listener register/unregister counts", 2, gateway.unregisterCalls)
        assertTrue("No leaked listeners remaining", gateway.activeListeners.isEmpty())

        val events = env.database.listenEventDao().getAll()
        assertEquals("Both sessions persisted with 0 duplicates", 2, events.size)
        assertEquals(listOf(10_000L, 12_000L), events.map { it.actualListenedMs })

        scope.cancel()
    }
}
