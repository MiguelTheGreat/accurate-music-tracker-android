package com.musictracker.app.e2e.tier2

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.tracking.AndroidSystemPermissionHelper
import com.musictracker.app.tracking.MediaSessionGateway
import com.musictracker.app.tracking.MusicNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tier 2 — Area D: Core Tracking Engine Boundaries (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T2TrackingBoundariesE2eTest {

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
    fun t2_d1_revokedPermissionAndNullSecureSettingReturnFalseSafely() {
        val notifManager = app.getSystemService(android.app.NotificationManager::class.java)
        val component = ComponentName(app, MusicNotificationListenerService::class.java)
        shadowOf(notifManager).setNotificationListenerAccessGranted(component, false)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", null)

        val helper = AndroidSystemPermissionHelper(app)
        assertFalse(helper.isNotificationAccessGranted())
    }

    @Test
    fun t2_d2_oemActivityNotFoundFallbackForNotificationSettings() {
        val launchedActions = mutableListOf<String?>()
        val oemContext = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                launchedActions.add(intent?.action)
                if (intent?.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                    throw ActivityNotFoundException("OEM removed notification listener settings")
                }
            }
        }

        val helper = AndroidSystemPermissionHelper(app)
        val result = helper.openNotificationListenerSettings(oemContext)
        assertTrue(result)
        assertEquals(
            listOf(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, Settings.ACTION_SETTINGS),
            launchedActions
        )
    }

    @Test
    fun t2_d3_oemActivityNotFoundFallbackForBatteryOptimization() {
        val launchedActions = mutableListOf<String?>()
        val oemContext = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                launchedActions.add(intent?.action)
                if (intent?.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    throw ActivityNotFoundException("OEM blocked direct battery dialog")
                }
            }
        }

        val helper = AndroidSystemPermissionHelper(app)
        val result = helper.requestBatteryOptimizationExemption(oemContext)
        assertTrue(result)
        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),
            launchedActions
        )
    }

    @Test
    fun t2_d4_securityExceptionOnGetActiveSessionsAndUncleanOnDestroyFlush() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val throwingGateway = object : MediaSessionGateway {
            override fun addOnActiveSessionsChangedListener(
                listener: MediaSessionManager.OnActiveSessionsChangedListener,
                notificationListener: ComponentName,
                handler: Handler
            ) {
                throw SecurityException("Permission revoked")
            }

            override fun removeOnActiveSessionsChangedListener(
                listener: MediaSessionManager.OnActiveSessionsChangedListener
            ) = Unit

            override fun getActiveSessions(notificationListener: ComponentName): List<MediaController> {
                throw SecurityException("Permission revoked")
            }
        }

        val service = Robolectric.buildService(MusicNotificationListenerService::class.java).create().get()
        service.coordinatorOverride = coordinator
        service.mediaSessionGatewayOverride = throwingGateway

        // Must catch SecurityException without crashing
        service.onListenerConnected()

        // Drive an active session and trigger unclean onDestroy()
        val driver = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot(title = "Crash Survive", artist = "Artist", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(driver))
        env.clock.advanceBy(20_000L)

        service.onDestroy()
        val deadline = System.currentTimeMillis() + 3_000L
        while (env.database.listenEventDao().getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }

        val events = env.database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(20_000L, events.single().actualListenedMs)
    }

    @Test
    fun t2_d5_nullOrEmptyActiveSessionsListDetachesStaleControllers() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val driver = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot(title = "Stale Song", artist = "Artist", durationMs = 180_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(driver))
        assertTrue(driver.isRegistered)
        env.clock.advanceBy(12_000L)

        // Passing null via Android entry point or emptyList detaches stale controller and ends tracking
        coordinator.onAndroidSessionsChanged(null)
        env.waitForEventCount(1)

        assertFalse(driver.isRegistered)
        assertNull(coordinator.nowTracking.value)

        val events = env.database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(12_000L, events.single().actualListenedMs)
    }

    @Test
    fun t2_d6_nonAllowlistedAppIgnoredWhileAllowlistedAppPauses() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val spotify = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            tokenId = "spotify_1",
            initialMetadata = MediaMetadataSnapshot(title = "Music Track", artist = "Artist", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        val youtubeVideo = FakeMediaControllerDriver(
            packageName = "com.google.android.youtube",
            tokenId = "yt_video_1",
            initialMetadata = MediaMetadataSnapshot(title = "Tech Review Video", artist = "Reviewer", durationMs = 600_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )

        coordinator.onActiveSessionsChanged(listOf(spotify, youtubeVideo))
        assertTrue(spotify.isRegistered)
        assertFalse("Non-allowlisted YouTube must not be registered", youtubeVideo.isRegistered)

        env.clock.advanceBy(20_000L)
        spotify.pause(20_000L)
        youtubeVideo.play(0L)

        // Advance 300s while YouTube plays and Spotify is paused
        env.clock.advanceBy(300_000L)
        coordinator.flushAndStop().join()

        val events = env.database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals("com.spotify.music", events.single().sourcePackage)
        assertEquals("Paused Spotify must not accumulate YouTube watch time", 20_000L, events.single().actualListenedMs)
    }

    @Test
    fun t2_d7_disablingCurrentlyPlayingAppInAllowlistDetachesAndFinalizes() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val spotify = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot(title = "Mid-Song Disable", artist = "Artist", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(spotify))
        assertTrue(spotify.isRegistered)
        env.clock.advanceBy(18_000L)

        // User toggles Spotify off in Settings allowlist mid-song
        env.allowlistStore.setPackageAllowed("com.spotify.music", false)

        // Wait briefly for DataStore flow propagation + Room commit
        val deadline = System.currentTimeMillis() + 3_000L
        while (spotify.isRegistered && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        env.waitForEventCount(1)

        assertFalse("Disabled app controller must be detached", spotify.isRegistered)
        assertNull("NowTracking must clear when active app is disabled", coordinator.nowTracking.value)

        val events = env.database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(18_000L, events.single().actualListenedMs)
    }
}
