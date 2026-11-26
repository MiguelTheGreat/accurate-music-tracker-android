package com.musictracker.app.e2e.tier4

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.tracking.AndroidSystemPermissionHelper
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeRoute
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsRoute
import com.musictracker.app.ui.screens.stats.StatsViewModel
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.MusicTrackerTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 4 — Scenario 4: Fresh Install Onboarding, Permission Grant Flow,
 * OEM Battery Fallback & Full Liquid Glass Accessibility across all 3 screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class T4Scenario4OnboardingAndA11yTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = app)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun scenario4_freshInstallOnboardingPermissionsAndAllAccessibilityModes() {
        // Verify real OEM fallback behavior via AndroidSystemPermissionHelper
        val realPermHelper = AndroidSystemPermissionHelper(app)
        assertTrue(realPermHelper.openNotificationListenerSettings(app))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(app).nextStartedActivity.action)

        val oemActions = mutableListOf<String?>()
        val oemContext = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                oemActions.add(intent?.action)
                if (intent?.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    throw ActivityNotFoundException("OEM blocked")
                }
            }
        }
        assertTrue(realPermHelper.requestBatteryOptimizationExemption(oemContext))
        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),
            oemActions
        )

        // Fresh app launch with Notification Access revoked and Battery Optimization active
        val permHelper = FakeSystemPermissionHelper(
            notificationAccessGranted = false,
            ignoringBatteryOptimizations = false
        )
        val homeVm = HomeViewModel(
            listeningRepository = env.listeningRepository,
            playSessionEngine = env.playSessionStateMachine,
            systemPermissionHelper = permHelper,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        val settingsVm = SettingsViewModel(
            systemPermissionHelper = permHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val highContrastAndReducedMotion = mutableStateOf(false)

        composeRule.setContent {
            val reduceTransparency by env.accessibilityPreferencesStore.reduceTransparencyEnabled
                .collectAsState(initial = false)
            val a11yOverride = if (highContrastAndReducedMotion.value) {
                LiquidGlassAccessibilityState(
                    reduceTransparency = reduceTransparency,
                    highContrast = true,
                    reduceMotion = true
                )
            } else {
                null
            }
            MusicTrackerTheme(
                reduceTransparency = reduceTransparency,
                accessibilityOverride = a11yOverride
            ) {
                MusicTrackerNavHost(
                    homeContent = { HomeRoute(viewModel = homeVm, clock = env.clock, systemPermissionHelper = permHelper) },
                    statsContent = { StatsRoute(viewModel = statsVm) },
                    settingsContent = { SettingsRoute(viewModel = settingsVm, systemPermissionHelper = permHelper) }
                )
            }
        }

        // 1. Home shows permission banner, idle "Nothing Playing" card, and "—" skip rate
        composeRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText("Nothing Playing").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_STAT_SKIP_RATE).assertTextContains("—")

        // 2. Navigate to Settings, tap Notification Access & Battery buttons
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertTextContains("Not Granted")
        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_BTN).performClick()
        assertEquals(1, permHelper.openNotificationSettingsCalls)

        composeRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_BTN).performClick()
        composeRule.waitForIdle()
        assertEquals(1, permHelper.requestBatteryExemptionCalls)

        // 3. User grants both permissions and returns (ON_RESUME)
        permHelper.notificationAccessGranted = true
        permHelper.ignoringBatteryOptimizations = true
        settingsVm.refreshSystemStatus()
        homeVm.refreshPermissionAndDay()
        composeRule.waitUntilIdle {
            settingsVm.uiState.value.isNotificationAccessGranted &&
                settingsVm.uiState.value.isIgnoringBatteryOptimizations
        }

        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertTextContains("Active")
        composeRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_STATUS).assertTextContains("Unrestricted")

        // 4. Toggle Reduce Transparency ON and enable High Contrast + Reduced Motion across all 3 screens
        composeRule.scrollToTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH)
        composeRule.onNodeWithTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH).performClick()
        composeRule.waitUntilIdle { settingsVm.uiState.value.reduceTransparencyEnabled }

        highContrastAndReducedMotion.value = true
        composeRule.waitForIdle()

        // Home: banner auto-dismissed under all 3 accessibility modes
        composeRule.onNodeWithTag(TestTags.NAV_TAB_HOME).performClick()
        composeRule.waitUntilIdle { homeVm.uiState.value.isNotificationAccessGranted }
        composeRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()

        // Stats: renders cleanly under all 3 accessibility modes
        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_CONTROL).assertIsDisplayed()

        // Settings: renders cleanly under all 3 accessibility modes (scroll back to top card)
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.waitForIdle()
        composeRule.scrollToTag(TestTags.SETTINGS_NOTIF_STATUS)
        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertIsDisplayed()
    }
}
