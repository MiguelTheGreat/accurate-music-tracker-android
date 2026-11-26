package com.musictracker.app.e2e.tier3

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeRoute
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsRoute
import com.musictracker.app.ui.screens.stats.StatsViewModel
import com.musictracker.app.ui.theme.LocalLiquidGlassAccessibility
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
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 3 — T3-4: Permission lifecycle + Navigation state preservation + Liquid Glass Accessibility modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T3PermissionsNavAndGlassE2eTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun t3_4_permissionBannerDismissesOnGrantAndNavPreservesStatsTimeframeUnderReducedTransparency() {
        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = false)

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

        var observedReduceTransparency = false

        composeRule.setContent {
            val reduceTransparency by env.accessibilityPreferencesStore.reduceTransparencyEnabled
                .collectAsState(initial = false)
            MusicTrackerTheme(reduceTransparency = reduceTransparency) {
                observedReduceTransparency = LocalLiquidGlassAccessibility.current.reduceTransparency
                MusicTrackerNavHost(
                    homeContent = { HomeRoute(viewModel = homeVm, clock = env.clock, systemPermissionHelper = permHelper) },
                    statsContent = { StatsRoute(viewModel = statsVm) },
                    settingsContent = { SettingsRoute(viewModel = settingsVm, systemPermissionHelper = permHelper) }
                )
            }
        }

        // 1. Initially Notification Access is revoked -> HOME_PERMISSION_BANNER is visible
        composeRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()

        // 2. Navigate to Stats and select 7 Days
        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_7D).performClick()
        composeRule.waitUntilIdle {
            statsVm.uiState.value.selectedTimeframe == StatsTimeframe.SEVEN_DAYS
        }

        // 3. Navigate to Settings and enable Reduce Transparency
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.scrollToTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH)
        composeRule.onNodeWithTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH).performClick()
        composeRule.waitUntilIdle { observedReduceTransparency }
        assertTrue(observedReduceTransparency)

        // 4. Grant Notification Access and trigger resume refresh
        permHelper.notificationAccessGranted = true
        homeVm.refreshPermissionAndDay()
        settingsVm.refreshSystemStatus()

        // 5. Navigate back to Home (banner gone) and Stats (7 Days preserved) under Reduced Transparency
        composeRule.onNodeWithTag(TestTags.NAV_TAB_HOME).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertDoesNotExist()

        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.waitForIdle()
        assertEquals(StatsTimeframe.SEVEN_DAYS, statsVm.uiState.value.selectedTimeframe)
        assertTrue(observedReduceTransparency)
    }
}
