package com.musictracker.app.tracking

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemPermissionHelperTest {

    private lateinit var appContext: Application
    private lateinit var helper: AndroidSystemPermissionHelper

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        helper = AndroidSystemPermissionHelper(appContext)
    }

    @Test
    fun isNotificationAccessGranted_reflectsShadowNotificationManagerOnApi34() {
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(notificationManager)
        val component = ComponentName(appContext, MusicNotificationListenerService::class.java)

        assertFalse(helper.isNotificationAccessGranted())

        shadowNm.setNotificationListenerAccessGranted(component, true)
        assertTrue(helper.isNotificationAccessGranted())

        shadowNm.setNotificationListenerAccessGranted(component, false)
        assertFalse(helper.isNotificationAccessGranted())
    }

    @Test
    @Config(sdk = [26], application = Application::class)
    fun isNotificationAccessGranted_api26FallbackReadsEnabledNotificationListeners() {
        val api26Helper = AndroidSystemPermissionHelper(appContext)
        val component = ComponentName(appContext, MusicNotificationListenerService::class.java)

        Settings.Secure.putString(appContext.contentResolver, "enabled_notification_listeners", "")
        assertFalse(api26Helper.isNotificationAccessGranted())

        Settings.Secure.putString(
            appContext.contentResolver,
            "enabled_notification_listeners",
            component.flattenToString()
        )
        assertTrue(api26Helper.isNotificationAccessGranted())
    }

    @Test
    fun openNotificationListenerSettings_addsNewTaskForAppContextAndOmitsForActivity() {
        val appResult = helper.openNotificationListenerSettings(appContext)
        assertTrue(appResult)

        val appIntent = shadowOf(appContext).nextStartedActivity
        assertNotNull(appIntent)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, appIntent.action)
        assertTrue((appIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val activityResult = helper.openNotificationListenerSettings(activity)
        assertTrue(activityResult)

        val activityIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(activityIntent)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, activityIntent.action)
        assertEquals(0, activityIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun openNotificationListenerSettings_fallsBackToActionSettingsOnFailure() {
        val launched = mutableListOf<Intent>()
        val fallbackContext = object : ContextWrapper(appContext) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                    throw ActivityNotFoundException("Not supported on OEM ROM")
                }
                launched += intent
            }
        }

        assertTrue(helper.openNotificationListenerSettings(fallbackContext))
        assertEquals(1, launched.size)
        assertEquals(Settings.ACTION_SETTINGS, launched.single().action)
        assertTrue((launched.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val alwaysFailingContext = object : ContextWrapper(appContext) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("No settings activity")
            }
        }
        assertFalse(helper.openNotificationListenerSettings(alwaysFailingContext))
    }

    @Test
    fun isIgnoringBatteryOptimizations_reflectsShadowPowerManager() {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val shadowPm = shadowOf(powerManager)

        shadowPm.setIgnoringBatteryOptimizations(appContext.packageName, false)
        assertFalse(helper.isIgnoringBatteryOptimizations())

        shadowPm.setIgnoringBatteryOptimizations(appContext.packageName, true)
        assertTrue(helper.isIgnoringBatteryOptimizations())
    }

    @Test
    fun requestBatteryOptimizationExemption_launchesRequestIntentWithPackageUri() {
        assertTrue(helper.requestBatteryOptimizationExemption(appContext))

        val intent = shadowOf(appContext).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${appContext.packageName}", intent.dataString)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun requestBatteryOptimizationExemption_fallsBackOnActivityNotFoundOrSecurityException() {
        val launchedOnNotFound = mutableListOf<Intent>()
        val notFoundContext = object : ContextWrapper(appContext) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    throw ActivityNotFoundException("Unavailable")
                }
                launchedOnNotFound += intent
            }
        }
        assertTrue(helper.requestBatteryOptimizationExemption(notFoundContext))
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            launchedOnNotFound.single().action
        )

        val launchedOnSecurity = mutableListOf<Intent>()
        val securityContext = object : ContextWrapper(appContext) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    throw SecurityException("Permission denied")
                }
                launchedOnSecurity += intent
            }
        }
        assertTrue(helper.requestBatteryOptimizationExemption(securityContext))
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            launchedOnSecurity.single().action
        )
    }

    @Test
    fun openBatteryOptimizationSettings_launchesSettingsAndFallsBack() {
        assertTrue(helper.openBatteryOptimizationSettings(appContext))
        val primary = shadowOf(appContext).nextStartedActivity
        assertNotNull(primary)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, primary.action)

        val launched = mutableListOf<Intent>()
        val fallbackContext = object : ContextWrapper(appContext) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                    throw ActivityNotFoundException("Missing battery optimization settings")
                }
                launched += intent
            }
        }
        assertTrue(helper.openBatteryOptimizationSettings(fallbackContext))
        assertEquals(Settings.ACTION_SETTINGS, launched.single().action)
    }

    @Test
    fun getInstalledLaunchableApps_deduplicatesExcludesSelfSortsAndFlagsDefaultMusicApps() {
        val shadowPm = shadowOf(appContext.packageManager)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        shadowPm.setResolveInfosForIntent(
            launcherIntent,
            listOf(
                createResolveInfo(appContext.packageName, "MainActivity", "Music Tracker"),
                createResolveInfo("com.google.android.youtube", "HomeActivity", "YouTube"),
                createResolveInfo("com.spotify.music", "MainActivity", "Spotify"),
                createResolveInfo("com.spotify.music", "AliasActivity", "Spotify Duplicate"),
                createResolveInfo("com.android.chrome", "Main", "Chrome"),
                createResolveInfo("com.google.android.apps.youtube.music", "Main", "YouTube Music")
            )
        )

        val apps = helper.getInstalledLaunchableApps()

        assertEquals(4, apps.size)
        // Default music apps first (alphabetical), then non-music apps (alphabetical); self excluded.
        assertEquals(
            listOf(
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true),
                InstalledAppInfo("com.google.android.apps.youtube.music", "YouTube Music", isDefaultMusicApp = true),
                InstalledAppInfo("com.android.chrome", "Chrome", isDefaultMusicApp = false),
                InstalledAppInfo("com.google.android.youtube", "YouTube", isDefaultMusicApp = false)
            ),
            apps
        )
    }

    private fun createResolveInfo(
        packageName: String,
        activityName: String,
        label: String
    ): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = "$packageName.$activityName"
            this.nonLocalizedLabel = label
            this.applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.nonLocalizedLabel = label
            }
        }
    }
}
