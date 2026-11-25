package com.musictracker.app.tracking

import android.app.Application
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.DefaultAllowlist
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicNotificationListenerServiceTest {

    private class FakeTrackerClock(
        private var elapsedMs: Long = 1_000L,
        private var epochMs: Long = 1_700_000_000_000L
    ) : TrackerClock {
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun currentTimeMillis(): Long = epochMs

        fun advanceBy(deltaMs: Long) {
            elapsedMs += deltaMs
            epochMs += deltaMs
        }
    }

    private class FakeAllowlistRepository : AllowlistRepository {
        private val state = MutableStateFlow(DefaultAllowlist.packages)
        override val allowedPackages: Flow<Set<String>> = state.asStateFlow()
        override suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
            state.value = if (allowed) state.value + packageName else state.value - packageName
        }

        override suspend fun resetToDefaults() {
            state.value = DefaultAllowlist.packages
        }
    }

    private class FakeMediaSessionGateway(
        private val throwSecurityExceptionOnRegister: Boolean = false,
        private val throwSecurityExceptionOnGetActiveSessions: Boolean = false
    ) : MediaSessionGateway {
        var registerCalls: Int = 0
            private set
        var unregisterCalls: Int = 0
            private set
        var getActiveSessionsCalls: Int = 0
            private set
        var lastComponentName: ComponentName? = null
            private set
        val registeredListeners: MutableList<MediaSessionManager.OnActiveSessionsChangedListener> =
            mutableListOf()

        override fun addOnActiveSessionsChangedListener(
            listener: MediaSessionManager.OnActiveSessionsChangedListener,
            notificationListener: ComponentName,
            handler: Handler
        ) {
            registerCalls++
            lastComponentName = notificationListener
            if (throwSecurityExceptionOnRegister) {
                throw SecurityException("Notification access not granted")
            }
            registeredListeners += listener
        }

        override fun removeOnActiveSessionsChangedListener(
            listener: MediaSessionManager.OnActiveSessionsChangedListener
        ) {
            unregisterCalls++
            registeredListeners.remove(listener)
        }

        override fun getActiveSessions(notificationListener: ComponentName): List<MediaController> {
            getActiveSessionsCalls++
            lastComponentName = notificationListener
            if (throwSecurityExceptionOnGetActiveSessions) {
                throw SecurityException("Notification access revoked before getActiveSessions")
            }
            return emptyList()
        }
    }

    private class StubTrackedController(
        override val tokenId: Any,
        override val packageName: String,
        override val currentMetadata: MediaMetadataSnapshot?,
        override val currentPlaybackState: PlaybackStateSnapshot?
    ) : TrackedMediaController {
        override fun registerCallback(listener: TrackedMediaController.Listener) = Unit
        override fun unregisterCallback() = Unit
    }

    private lateinit var database: MusicTrackerDatabase
    private lateinit var repository: ListeningRepositoryImpl
    private lateinit var clock: FakeTrackerClock
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
        stateMachine = PlaySessionStateMachine(clock = clock, normalizer = normalizer)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createCoordinator(testScope: TestScope): MediaSessionCoordinator {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        return MediaSessionCoordinator(
            playSessionEngine = stateMachine,
            allowlistRepository = FakeAllowlistRepository(),
            listeningRepository = repository,
            clock = clock,
            scope = testScope,
            ioDispatcher = dispatcher,
            schedulerDispatcher = dispatcher
        )
    }

    @Test
    fun onListenerConnected_registersSessionListenerQueriesActiveSessionsAndMarksConnectedTrue() = runTest {
        val coordinator = createCoordinator(this)
        val gateway = FakeMediaSessionGateway()
        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get().apply {
            coordinatorOverride = coordinator
            mediaSessionGatewayOverride = gateway
        }

        assertFalse(coordinator.isListenerConnected.value)

        service.onListenerConnected()
        runCurrent()

        assertTrue(coordinator.isListenerConnected.value)
        assertEquals(1, gateway.registerCalls)
        assertEquals(1, gateway.getActiveSessionsCalls)
        assertEquals(
            MusicNotificationListenerService::class.java.name,
            gateway.lastComponentName?.className
        )

        controller.destroy()
        runCurrent()
    }

    @Test
    fun onListenerConnected_handlesSecurityExceptionGracefullyAndMarksConnectedFalse() = runTest {
        val coordinator = createCoordinator(this)
        val gateway = FakeMediaSessionGateway(throwSecurityExceptionOnRegister = true)
        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get().apply {
            coordinatorOverride = coordinator
            mediaSessionGatewayOverride = gateway
        }

        // Must not throw SecurityException.
        service.onListenerConnected()
        runCurrent()

        assertFalse(coordinator.isListenerConnected.value)
        assertEquals(1, gateway.registerCalls)
        assertEquals(0, gateway.getActiveSessionsCalls)
        assertEquals(0, gateway.unregisterCalls)
        assertTrue(gateway.registeredListeners.isEmpty())

        controller.destroy()
        runCurrent()
    }

    @Test
    fun onListenerConnected_securityExceptionOnGetActiveSessions_unregistersListenerWithoutLeak() = runTest {
        val coordinator = createCoordinator(this)
        val gateway = FakeMediaSessionGateway(throwSecurityExceptionOnGetActiveSessions = true)
        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get().apply {
            coordinatorOverride = coordinator
            mediaSessionGatewayOverride = gateway
        }

        service.onListenerConnected()
        runCurrent()

        assertFalse(coordinator.isListenerConnected.value)
        assertEquals(1, gateway.registerCalls)
        assertEquals(1, gateway.getActiveSessionsCalls)
        assertEquals(1, gateway.unregisterCalls)
        assertTrue(gateway.registeredListeners.isEmpty())

        // Subsequent destroy must not double-unregister or leak.
        controller.destroy()
        runCurrent()
        assertEquals(1, gateway.unregisterCalls)
        assertTrue(gateway.registeredListeners.isEmpty())
    }

    @Test
    fun onListenerDisconnected_unregistersFlushesActiveSessionToDbMarksDisconnectedAndRequestsRebind() = runTest {
        val coordinator = createCoordinator(this)
        val gateway = FakeMediaSessionGateway()
        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get().apply {
            coordinatorOverride = coordinator
            mediaSessionGatewayOverride = gateway
        }

        val rebindBefore = ShadowNotificationListenerService.getRebindRequestCount()

        service.onListenerConnected()
        runCurrent()

        // Seed an active playing session and promote it past debounce.
        coordinator.onActiveSessionsChanged(
            listOf(
                StubTrackedController(
                    tokenId = "ytm-active",
                    packageName = "com.google.android.apps.youtube.music",
                    currentMetadata = MediaMetadataSnapshot(
                        title = "Disconnect Flush Track",
                        artist = "Test Artist",
                        durationMs = 180_000L
                    ),
                    currentPlaybackState = PlaybackStateSnapshot(
                        status = PlaybackStatus.PLAYING,
                        positionMs = 0L
                    )
                )
            )
        )
        clock.advanceBy(5_000L)
        advanceTimeBy(5_000L)
        runCurrent()

        service.onListenerDisconnected()
        runCurrent()

        assertFalse(coordinator.isListenerConnected.value)
        assertEquals(1, gateway.unregisterCalls)
        assertEquals(rebindBefore + 1, ShadowNotificationListenerService.getRebindRequestCount())
        assertNull(coordinator.nowTracking.value)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(5_000L, events.single().actualListenedMs)

        // Subsequent onDestroy must be idempotent and not double-persist.
        controller.destroy()
        runCurrent()
        assertEquals(1, database.listenEventDao().getAll().size)
    }

    @Test
    fun onDestroy_unregistersListenerAndFlushesActiveSessionNonCancellablyToDb() = runTest {
        val coordinator = createCoordinator(this)
        val gateway = FakeMediaSessionGateway()
        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get().apply {
            coordinatorOverride = coordinator
            mediaSessionGatewayOverride = gateway
        }

        service.onListenerConnected()
        runCurrent()

        coordinator.onActiveSessionsChanged(
            listOf(
                StubTrackedController(
                    tokenId = "sp-active",
                    packageName = "com.spotify.music",
                    currentMetadata = MediaMetadataSnapshot(
                        title = "Destroy Flush Track",
                        artist = "Destroy Artist",
                        durationMs = 200_000L
                    ),
                    currentPlaybackState = PlaybackStateSnapshot(
                        status = PlaybackStatus.PLAYING,
                        positionMs = 0L
                    )
                )
            )
        )
        clock.advanceBy(7_000L)
        advanceTimeBy(7_000L)
        runCurrent()

        controller.destroy()
        runCurrent()

        assertFalse(coordinator.isListenerConnected.value)
        assertEquals(1, gateway.unregisterCalls)
        assertNull(coordinator.nowTracking.value)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(7_000L, events.single().actualListenedMs)
        assertEquals("Destroy Flush Track", database.trackDao().getAll().single().normalizedTitle)
    }
}
