package com.musictracker.app.tracking

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.DefaultAllowlist
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaSessionCoordinatorTest {

    private class FakeTrackerClock(
        private var elapsedMs: Long = 10_000L,
        private var epochMs: Long = 1_700_000_000_000L
    ) : TrackerClock {
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun currentTimeMillis(): Long = epochMs

        fun advanceBy(deltaMs: Long) {
            elapsedMs += deltaMs
            epochMs += deltaMs
        }
    }

    private class FakeAllowlistRepository(
        initial: Set<String> = DefaultAllowlist.packages
    ) : AllowlistRepository {
        private val state = MutableStateFlow(initial)
        override val allowedPackages: Flow<Set<String>> = state.asStateFlow()

        override suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
            state.value = if (allowed) {
                state.value + packageName
            } else {
                state.value - packageName
            }
        }

        override suspend fun resetToDefaults() {
            state.value = DefaultAllowlist.packages
        }
    }

    private class FakeTrackedMediaController(
        override val tokenId: Any,
        override val packageName: String,
        private var metadata: MediaMetadata? = null,
        private var playbackState: PlaybackState? = null
    ) : TrackedMediaController {

        private var listener: TrackedMediaController.Listener? = null
        var registerCount: Int = 0
            private set
        var unregisterCount: Int = 0
            private set

        val isRegistered: Boolean
            get() = listener != null

        override val currentMetadata: MediaMetadataSnapshot?
            get() = metadata?.toDomainSnapshot()

        override val currentPlaybackState: PlaybackStateSnapshot?
            get() = playbackState?.toDomainSnapshot()

        override fun registerCallback(listener: TrackedMediaController.Listener) {
            registerCount++
            this.listener = listener
        }

        override fun unregisterCallback() {
            if (this.listener != null) {
                unregisterCount++
                this.listener = null
            }
        }

        fun emitMetadata(newMetadata: MediaMetadata?) {
            metadata = newMetadata
            listener?.onMetadataChanged(newMetadata?.toDomainSnapshot())
        }

        fun emitPlaybackState(newState: PlaybackState?) {
            playbackState = newState
            listener?.onPlaybackStateChanged(newState?.toDomainSnapshot())
        }

        fun destroy() {
            listener?.onSessionDestroyed()
        }
    }

    private lateinit var database: MusicTrackerDatabase
    private lateinit var repository: ListeningRepositoryImpl
    private lateinit var clock: FakeTrackerClock
    private lateinit var allowlistRepository: FakeAllowlistRepository
    private lateinit var stateMachine: PlaySessionStateMachine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        val normalizer = MusicMetadataNormalizer()
        repository = ListeningRepositoryImpl(database = database, normalizer = normalizer)
        clock = FakeTrackerClock()
        allowlistRepository = FakeAllowlistRepository()
        stateMachine = PlaySessionStateMachine(clock = clock, normalizer = normalizer)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class CountingPlaySessionEngine(
        private val delegate: PlaySessionEngine
    ) : PlaySessionEngine {
        var onPlaybackEventCalls: Int = 0
            private set
        var onSessionEndedCalls: Int = 0
            private set

        override val nowTracking: StateFlow<NowTrackingState?>
            get() = delegate.nowTracking

        override fun onPlaybackEvent(event: PlaybackEvent): List<CompletedListenSession> {
            onPlaybackEventCalls++
            return delegate.onPlaybackEvent(event)
        }

        override fun onSessionEnded(sourcePackage: String): List<CompletedListenSession> {
            onSessionEndedCalls++
            return delegate.onSessionEnded(sourcePackage)
        }

        override fun flushActiveSession(): List<CompletedListenSession> =
            delegate.flushActiveSession()
    }

    private fun createCoordinator(
        testScope: TestScope,
        engine: PlaySessionEngine = stateMachine
    ): MediaSessionCoordinator {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        return MediaSessionCoordinator(
            playSessionEngine = engine,
            allowlistRepository = allowlistRepository,
            listeningRepository = repository,
            clock = clock,
            scope = testScope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher
        )
    }

    private fun buildMetadata(
        title: String = "Blinding Lights (Official Audio)",
        artist: String = "The Weeknd - Topic",
        album: String = "After Hours",
        durationMs: Long = 200_000L,
        artworkUri: String? = "https://art.example/after_hours.jpg"
    ): MediaMetadata = MediaMetadata.Builder().apply {
        putString(MediaMetadata.METADATA_KEY_TITLE, title)
        putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        if (artworkUri != null) {
            putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUri)
        }
    }.build()

    private fun buildPlaybackState(
        state: Int,
        positionMs: Long = 0L,
        speed: Float = 1.0f
    ): PlaybackState = PlaybackState.Builder()
        .setState(state, positionMs, speed, clock.elapsedRealtimeMs())
        .build()

    @Test
    fun androidMappers_extractFallbackKeysAndPreserveUnknownPosition() {
        val fallbackMetadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Display Song")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Display Artist")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "Display Album")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000L)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, "content://icon/1")
            .build()
            .toDomainSnapshot()

        assertEquals("Display Song", fallbackMetadata.title)
        assertEquals("Display Artist", fallbackMetadata.artist)
        assertEquals("Display Album", fallbackMetadata.album)
        assertEquals(180_000L, fallbackMetadata.durationMs)
        assertEquals("content://icon/1", fallbackMetadata.artworkUri)

        val unknownPosState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f, 5_000L)
            .build()
            .toDomainSnapshot()

        assertEquals(PlaybackStatus.PLAYING, unknownPosState.status)
        assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, unknownPosState.positionMs)
        assertEquals(-1L, unknownPosState.positionMs)
    }

    @Test
    fun allowlistFiltering_tracksYtMusicAndSpotify_ignoresYoutubeVideoAndChrome() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val youtubeVideo = FakeTrackedMediaController(
            tokenId = "yt-video",
            packageName = "com.google.android.youtube",
            metadata = buildMetadata(title = "10 Hour Vlog", artist = "Creator"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val chrome = FakeTrackedMediaController(
            tokenId = "chrome",
            packageName = "com.android.chrome",
            metadata = buildMetadata(title = "Podcast", artist = "Web"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )

        coordinator.onActiveSessionsChanged(listOf(youtubeVideo, chrome, ytMusic))
        runCurrent()

        assertFalse(youtubeVideo.isRegistered)
        assertFalse(chrome.isRegistered)
        assertTrue(ytMusic.isRegistered)
        assertEquals("com.google.android.apps.youtube.music", coordinator.nowTracking.value?.sourcePackage)
        assertEquals("Blinding Lights", coordinator.nowTracking.value?.normalizedTitle)
        assertEquals("The Weeknd", coordinator.nowTracking.value?.normalizedArtist)

        coordinator.flushAndStop().join()
        assertEquals(0, database.listenEventDao().getAll().size)
    }

    @Test
    fun debounceTickPromotion_promotesAt1500msAndPersistsCompletedSessionToRoomDb() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm-1",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING, positionMs = 0L)
        )

        coordinator.onActiveSessionsChanged(listOf(ytMusic))
        runCurrent()

        assertTrue(coordinator.nowTracking.value!!.isDebouncing)

        // At 1_000ms, still debouncing.
        clock.advanceBy(1_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(coordinator.nowTracking.value!!.isDebouncing)

        // At 1_500ms, scheduled debounce tick promotes PENDING -> ACTIVE.
        clock.advanceBy(500L)
        advanceTimeBy(500L)
        runCurrent()
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)

        // Play for another 118.5s (120_000ms total out of 200_000ms -> 60% -> not skipped).
        clock.advanceBy(118_500L)
        ytMusic.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_STOPPED, positionMs = 120_000L))
        runCurrent()

        assertNull(coordinator.nowTracking.value)

        val tracks = database.trackDao().getAll()
        val events = database.listenEventDao().getAll()
        assertEquals(1, tracks.size)
        assertEquals("Blinding Lights", tracks.single().normalizedTitle)
        assertEquals("The Weeknd", tracks.single().normalizedArtist)
        assertEquals("After Hours", tracks.single().album)
        assertEquals(200_000L, tracks.single().durationMs)

        assertEquals(1, events.size)
        assertEquals("com.google.android.apps.youtube.music", events.single().sourcePackage)
        assertEquals(120_000L, events.single().actualListenedMs)
        assertFalse(events.single().wasSkipped)

        coordinator.flushAndStop().join()
    }

    @Test
    fun shortPlaybackUnderDebounce_isDiscardedAndNotPersistedToRoomDb() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val spotify = FakeTrackedMediaController(
            tokenId = "sp-1",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Quick Skip", artist = "Artist"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        coordinator.onActiveSessionsChanged(listOf(spotify))
        runCurrent()

        clock.advanceBy(900L)
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_STOPPED))
        runCurrent()

        assertNull(coordinator.nowTracking.value)
        assertTrue(database.listenEventDao().getAll().isEmpty())

        coordinator.flushAndStop().join()
    }

    @Test
    fun multiSessionArbitration_prefersIndexZeroPlaying_ignoresNonFocusedPause_andSwitchesFocusOnPlay() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Track A", artist = "Artist A", durationMs = 200_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val spotify = FakeTrackedMediaController(
            tokenId = "spotify",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Track B", artist = "Artist B", durationMs = 180_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )

        // Both playing on attach: index 0 (ytMusic) wins priority.
        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotify))
        runCurrent()
        assertEquals("com.google.android.apps.youtube.music", coordinator.nowTracking.value?.sourcePackage)

        // Promote ytMusic past debounce.
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)

        // Non-focused Spotify emitting PAUSED / STOPPED / metadata updates must not interrupt ytMusic.
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_PAUSED))
        spotify.emitMetadata(buildMetadata(title = "Track B2", artist = "Artist B", durationMs = 180_000L))
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_STOPPED))
        runCurrent()
        assertEquals("com.google.android.apps.youtube.music", coordinator.nowTracking.value?.sourcePackage)
        assertTrue(coordinator.nowTracking.value!!.isPlaying)
        assertEquals(0, database.listenEventDao().getAll().size)

        // Listen 8s more (10_000ms total), then Spotify transitions to PLAYING -> focus switch!
        clock.advanceBy(8_000L)
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_PLAYING))
        runCurrent()

        assertEquals("com.spotify.music", coordinator.nowTracking.value?.sourcePackage)
        assertEquals("Track B2", coordinator.nowTracking.value?.normalizedTitle)

        // Previous YT Music session was finalized and persisted to Room DB.
        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals("com.google.android.apps.youtube.music", events.single().sourcePackage)
        assertEquals(10_000L, events.single().actualListenedMs)

        coordinator.flushAndStop().join()
    }

    @Test
    fun dynamicAllowlistToggleOffAndOn_finalizesOnDisableAndReattachesOnEnable() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Dynamic Song", artist = "Dynamic Artist"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        coordinator.onActiveSessionsChanged(listOf(ytMusic))
        runCurrent()
        assertTrue(ytMusic.isRegistered)

        clock.advanceBy(5_000L)
        advanceTimeBy(5_000L)
        runCurrent()

        // Toggle YT Music OFF in allowlist mid-playback -> detach, finalize & persist to Room DB.
        allowlistRepository.setPackageAllowed("com.google.android.apps.youtube.music", false)
        runCurrent()

        assertFalse(ytMusic.isRegistered)
        assertNull(coordinator.nowTracking.value)
        val eventsAfterDisable = database.listenEventDao().getAll()
        assertEquals(1, eventsAfterDisable.size)
        assertEquals(5_000L, eventsAfterDisable.single().actualListenedMs)

        // Toggle YT Music back ON while still in lastKnownControllers -> reattaches & seeds state.
        allowlistRepository.setPackageAllowed("com.google.android.apps.youtube.music", true)
        runCurrent()

        assertTrue(ytMusic.isRegistered)
        assertNotNull(coordinator.nowTracking.value)
        assertEquals("Dynamic Song", coordinator.nowTracking.value?.normalizedTitle)

        coordinator.flushAndStop().join()
    }

    @Test
    fun onSessionDestroyed_finalizesPersistsAndFallsBackToRemainingPlayingController() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "First Song", artist = "Artist 1"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val spotify = FakeTrackedMediaController(
            tokenId = "sp",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Backup Song", artist = "Artist 2"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )

        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotify))
        runCurrent()

        clock.advanceBy(4_000L)
        advanceTimeBy(4_000L)
        runCurrent()

        ytMusic.destroy()
        runCurrent()

        assertFalse(ytMusic.isRegistered)
        assertEquals(1, ytMusic.unregisterCount)
        assertEquals("com.spotify.music", coordinator.nowTracking.value?.sourcePackage)
        assertEquals("Backup Song", coordinator.nowTracking.value?.normalizedTitle)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals("com.google.android.apps.youtube.music", events.single().sourcePackage)
        assertEquals(4_000L, events.single().actualListenedMs)

        coordinator.flushAndStop().join()
    }

    @Test
    fun activeSessionsDiffing_doesNotReattachUnchangedControllers_andDetachesRemoved() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val spotify = FakeTrackedMediaController(
            tokenId = "sp",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Other", artist = "Other"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PAUSED)
        )

        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotify))
        runCurrent()
        assertEquals(1, ytMusic.registerCount)
        assertEquals(1, spotify.registerCount)

        // Re-sending ytMusic only should NOT re-register ytMusic, and should detach spotify.
        coordinator.onActiveSessionsChanged(listOf(ytMusic))
        runCurrent()
        assertEquals(1, ytMusic.registerCount)
        assertTrue(ytMusic.isRegistered)
        assertFalse(spotify.isRegistered)
        assertEquals(1, spotify.unregisterCount)

        coordinator.flushAndStop().join()
    }

    @Test
    fun flushAndStop_detachesAllControllersAndPersistsActiveSessionToRoomDb() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Flushed Song", artist = "Flushed Artist"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        coordinator.onActiveSessionsChanged(listOf(ytMusic))
        runCurrent()

        clock.advanceBy(6_000L)
        advanceTimeBy(6_000L)
        runCurrent()

        val flushJob = coordinator.flushAndStop()
        runCurrent()
        flushJob.join()

        assertFalse(ytMusic.isRegistered)
        assertNull(coordinator.nowTracking.value)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(6_000L, events.single().actualListenedMs)
        assertEquals("Flushed Song", database.trackDao().getAll().single().normalizedTitle)
    }

    @Test
    fun pausedFocusedController_switchesFocusToNewlyAttachedPlayingControllerAndPersistsPrevious() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm-paused",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Paused Track", artist = "Artist A", durationMs = 200_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        coordinator.onActiveSessionsChanged(listOf(ytMusic))
        runCurrent()

        // Promote past debounce and accumulate 3_000ms, then pause.
        clock.advanceBy(3_000L)
        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)

        ytMusic.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_PAUSED, positionMs = 3_000L))
        runCurrent()
        assertEquals("com.google.android.apps.youtube.music", coordinator.nowTracking.value?.sourcePackage)
        assertFalse(coordinator.nowTracking.value!!.isPlaying)

        // New allowlisted controller arrives already PLAYING via onActiveSessionsChanged.
        val spotify = FakeTrackedMediaController(
            tokenId = "sp-playing",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Fresh Track", artist = "Artist B", durationMs = 180_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING, positionMs = 0L)
        )
        clock.advanceBy(2_000L)
        coordinator.onActiveSessionsChanged(listOf(spotify, ytMusic))
        runCurrent()

        assertEquals("com.spotify.music", coordinator.nowTracking.value?.sourcePackage)
        assertEquals("Fresh Track", coordinator.nowTracking.value?.normalizedTitle)
        assertTrue(coordinator.nowTracking.value!!.isPlaying)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals("com.google.android.apps.youtube.music", events.single().sourcePackage)
        assertEquals(3_000L, events.single().actualListenedMs)

        coordinator.flushAndStop().join()
    }

    @Test
    fun focusSwitchToPlayingControllerWithBlankTitleMetadata_finalizesPreviousImmediatelyWithZeroPhantomTime() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm-active",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Active Song", artist = "Artist A", durationMs = 200_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val spotifyBlankMeta = FakeTrackedMediaController(
            tokenId = "sp-blank",
            packageName = "com.spotify.music",
            metadata = MediaMetadata.Builder().build(),
            playbackState = buildPlaybackState(PlaybackState.STATE_PAUSED)
        )
        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotifyBlankMeta))
        runCurrent()

        clock.advanceBy(3_000L)
        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(coordinator.nowTracking.value!!.isDebouncing)

        // Non-focused Spotify transitions to PLAYING while its MediaMetadata has a blank title.
        spotifyBlankMeta.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_PLAYING))
        runCurrent()

        // Previous YT Music session must be finalized & persisted immediately with exact 3_000ms.
        assertNull(coordinator.nowTracking.value)
        val eventsAfterSwitch = database.listenEventDao().getAll()
        assertEquals(1, eventsAfterSwitch.size)
        assertEquals("com.google.android.apps.youtube.music", eventsAfterSwitch.single().sourcePackage)
        assertEquals(3_000L, eventsAfterSwitch.single().actualListenedMs)

        // Advancing time and stopping YT Music later must not leak phantom listening time or duplicate rows.
        clock.advanceBy(60_000L)
        ytMusic.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_STOPPED))
        runCurrent()

        val eventsAfterStop = database.listenEventDao().getAll()
        assertEquals(1, eventsAfterStop.size)
        assertEquals(3_000L, eventsAfterStop.single().actualListenedMs)

        coordinator.flushAndStop().join()
    }

    @Test
    fun idleFastPath_andUnchangedTopology_avoidEngineAndControllerChurn() = runTest {
        val countingEngine = CountingPlaySessionEngine(stateMachine)
        val coordinator = createCoordinator(this, engine = countingEngine)
        coordinator.start()
        runCurrent()

        val youtubeVideo = FakeTrackedMediaController(
            tokenId = "yt-video",
            packageName = "com.google.android.youtube",
            metadata = buildMetadata(title = "Vlog", artist = "Creator"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )

        coordinator.onActiveSessionsChanged(emptyList())
        coordinator.onActiveSessionsChanged(listOf(youtubeVideo))
        runCurrent()

        assertEquals(0, countingEngine.onPlaybackEventCalls)
        assertEquals(0, countingEngine.onSessionEndedCalls)
        assertEquals(0, youtubeVideo.registerCount)

        val ytMusicPaused = FakeTrackedMediaController(
            tokenId = "ytm-paused",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Paused Track", artist = "Artist"),
            playbackState = buildPlaybackState(PlaybackState.STATE_PAUSED)
        )
        coordinator.onActiveSessionsChanged(listOf(ytMusicPaused))
        runCurrent()
        assertEquals(1, ytMusicPaused.registerCount)
        assertEquals(0, countingEngine.onPlaybackEventCalls)

        // Same eligible topology with extra non-allowlisted controller must hit fast-path.
        coordinator.onActiveSessionsChanged(listOf(ytMusicPaused, youtubeVideo))
        runCurrent()
        assertEquals(1, ytMusicPaused.registerCount)
        assertEquals(0, countingEngine.onPlaybackEventCalls)

        coordinator.flushAndStop().join()
    }

    @Test
    fun duplicateNonPlayingStates_andUnfocusedCallbacks_shortCircuitWithoutEngineChurn() = runTest {
        val countingEngine = CountingPlaySessionEngine(stateMachine)
        val coordinator = createCoordinator(this, engine = countingEngine)
        coordinator.start()
        runCurrent()

        val ytMusic = FakeTrackedMediaController(
            tokenId = "ytm",
            packageName = "com.google.android.apps.youtube.music",
            metadata = buildMetadata(title = "Song A", artist = "Artist A", durationMs = 200_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PLAYING)
        )
        val spotify = FakeTrackedMediaController(
            tokenId = "sp",
            packageName = "com.spotify.music",
            metadata = buildMetadata(title = "Song B", artist = "Artist B", durationMs = 200_000L),
            playbackState = buildPlaybackState(PlaybackState.STATE_PAUSED)
        )

        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotify))
        runCurrent()
        clock.advanceBy(2_000L)
        advanceTimeBy(2_000L)
        runCurrent()

        val pausedState = buildPlaybackState(PlaybackState.STATE_PAUSED, positionMs = 2_000L)
        ytMusic.emitPlaybackState(pausedState)
        runCurrent()
        val callsAfterPause = countingEngine.onPlaybackEventCalls

        // Duplicate identical PAUSED states must be deduplicated before hitting engine.
        repeat(5) {
            ytMusic.emitPlaybackState(pausedState)
        }
        runCurrent()
        assertEquals(callsAfterPause, countingEngine.onPlaybackEventCalls)

        // Unfocused controller metadata and non-playing state callbacks must not touch engine.
        spotify.emitMetadata(buildMetadata(title = "Song B2", artist = "Artist B"))
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_BUFFERING, positionMs = 1_000L))
        spotify.emitPlaybackState(buildPlaybackState(PlaybackState.STATE_PAUSED, positionMs = 1_000L))
        runCurrent()
        assertEquals(callsAfterPause, countingEngine.onPlaybackEventCalls)
        assertEquals("com.google.android.apps.youtube.music", coordinator.nowTracking.value?.sourcePackage)
        assertFalse(coordinator.nowTracking.value!!.isPlaying)

        coordinator.flushAndStop().join()
    }
}
