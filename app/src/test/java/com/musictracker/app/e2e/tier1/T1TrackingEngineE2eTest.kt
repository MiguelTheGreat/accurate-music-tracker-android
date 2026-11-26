package com.musictracker.app.e2e.tier1

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.tracking.AndroidSystemPermissionHelper
import com.musictracker.app.tracking.MusicNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tier 1 — Area D: Core Tracking Engine & Permissions (Robolectric, F01–F06, F08).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T1TrackingEngineE2eTest {

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
    fun t1_d1_notificationAccessDetectionReflectsSystemState() {
        val helper = AndroidSystemPermissionHelper(app)
        val notifManager = app.getSystemService(android.app.NotificationManager::class.java)
        val component = ComponentName(app, MusicNotificationListenerService::class.java)

        shadowOf(notifManager).setNotificationListenerAccessGranted(component, false)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        assertFalse(helper.isNotificationAccessGranted())

        shadowOf(notifManager).setNotificationListenerAccessGranted(component, true)
        Settings.Secure.putString(
            app.contentResolver,
            "enabled_notification_listeners",
            component.flattenToString()
        )
        assertTrue(helper.isNotificationAccessGranted())
    }

    @Test
    fun t1_d2_notificationAccessDeepLinkLaunchesSettingsIntent() {
        val helper = AndroidSystemPermissionHelper(app)
        val launched = helper.openNotificationListenerSettings(app)
        assertTrue(launched)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, startedIntent.action)
    }

    @Test
    fun t1_d3_batteryOptimizationStatusAndExemptionIntent() {
        val helper = AndroidSystemPermissionHelper(app)
        val powerManager = app.getSystemService(PowerManager::class.java)
        val shadowPower = shadowOf(powerManager)

        assertFalse(helper.isIgnoringBatteryOptimizations())
        shadowPower.setIgnoringBatteryOptimizations(app.packageName, true)
        assertTrue(helper.isIgnoringBatteryOptimizations())

        val launched = helper.requestBatteryOptimizationExemption(app)
        assertTrue(launched)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, startedIntent.action)
        assertEquals(Uri.parse("package:${app.packageName}"), startedIntent.data)
    }

    @Test
    fun t1_d4_serviceLifecycleConnectAndDisconnectFlushesActiveSession() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val controller = Robolectric.buildService(MusicNotificationListenerService::class.java).create()
        val service = controller.get()
        service.coordinatorOverride = coordinator

        service.onListenerConnected()
        assertTrue(coordinator.isListenerConnected.value)

        // Drive an active allowlisted session while connected
        val driver = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot(title = "Disconnect Song", artist = "Artist", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(driver))
        env.clock.advanceBy(15_000L)

        // Disconnect must flush active session to Room
        service.onListenerDisconnected()
        val deadline = System.currentTimeMillis() + 3_000L
        while (env.database.listenEventDao().getAll().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }

        assertFalse(coordinator.isListenerConnected.value)
        val events = env.database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(15_000L, events.single().actualListenedMs)
    }

    @Test
    fun t1_d5_activeMediaSessionDiscoverySeedsAlreadyPlayingController() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val alreadyPlaying = FakeMediaControllerDriver(
            packageName = "com.google.android.apps.youtube.music",
            initialMetadata = MediaMetadataSnapshot(title = "Seeded Track (Official Audio)", artist = "Artist - Topic", durationMs = 190_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 12_000L)
        )

        coordinator.onActiveSessionsChanged(listOf(alreadyPlaying))

        assertTrue(alreadyPlaying.isRegistered)
        val tracking = coordinator.nowTracking.value
        assertNotNull(tracking)
        assertEquals("Seeded Track", tracking!!.normalizedTitle)
        assertEquals("Artist", tracking.normalizedArtist)
        assertTrue(tracking.isPlaying)
    }

    @Test
    fun t1_d6_multiSessionArbitrationSwitchesFocusAndFinalizesPrevious() = runBlocking {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val controllerA = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            tokenId = "spotify_a",
            initialMetadata = MediaMetadataSnapshot(title = "Song A", artist = "Artist A", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        val controllerB = FakeMediaControllerDriver(
            packageName = "com.google.android.apps.youtube.music",
            tokenId = "ytm_b",
            initialMetadata = MediaMetadataSnapshot(title = "Song B", artist = "Artist B", durationMs = 200_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )

        coordinator.onActiveSessionsChanged(listOf(controllerA, controllerB))
        assertEquals("Song A", coordinator.nowTracking.value?.normalizedTitle)

        env.clock.advanceBy(25_000L)

        // Controller A pauses, Controller B starts playing
        controllerA.pause(25_000L)
        controllerB.play(0L)

        assertEquals("Song B", coordinator.nowTracking.value?.normalizedTitle)

        // Finalize B as well and verify both persisted
        env.clock.advanceBy(30_000L)
        coordinator.flushAndStop().join()

        val events = env.database.listenEventDao().getAll()
        assertEquals(2, events.size)
        val tracks = env.database.trackDao().getAll().associateBy { it.id }
        val byTitle = events.associate { tracks[it.trackId]!!.normalizedTitle to it.actualListenedMs }
        assertEquals(25_000L, byTitle["Song A"])
        assertEquals(30_000L, byTitle["Song B"])
    }

    @Test
    fun t1_d7_installedAppsDiscoveryExcludesSelfAndAllowlistTogglePersists() = runBlocking {
        val shadowPm = shadowOf(app.packageManager)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val selfResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = app.packageName
                name = "${app.packageName}.MainActivity"
                applicationInfo = ApplicationInfo().apply { packageName = app.packageName }
            }
        }
        val spotifyResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.spotify.music"
                name = "com.spotify.music.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.spotify.music"
                    nonLocalizedLabel = "Spotify"
                }
            }
        }
        val customResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.customplayer"
                name = "com.example.customplayer.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.example.customplayer"
                    nonLocalizedLabel = "Custom Player"
                }
            }
        }
        shadowPm.addResolveInfoForIntent(launcherIntent, selfResolve)
        shadowPm.addResolveInfoForIntent(launcherIntent, spotifyResolve)
        shadowPm.addResolveInfoForIntent(launcherIntent, customResolve)

        val helper = AndroidSystemPermissionHelper(app)
        val discovered = helper.getInstalledLaunchableApps()
        val packages = discovered.map { it.packageName }

        assertFalse(packages.contains(app.packageName))
        assertTrue(packages.contains("com.spotify.music"))
        assertTrue(packages.contains("com.example.customplayer"))

        // Toggle custom player on and verify persistence
        env.allowlistStore.setPackageAllowed("com.example.customplayer", true)
        assertTrue(env.allowlistStore.allowedPackages.first().contains("com.example.customplayer"))
        env.allowlistStore.setPackageAllowed("com.example.customplayer", false)
        assertFalse(env.allowlistStore.allowedPackages.first().contains("com.example.customplayer"))
    }
}
