package com.musictracker.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.screens.settings.SettingsScreenContent
import com.musictracker.app.ui.screens.settings.SettingsUiState
import com.musictracker.app.ui.screens.stats.StatsScreenContent
import com.musictracker.app.ui.screens.stats.StatsUiState
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.MusicTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationAndAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun navBarSwitchesAcrossAllThreeTabs() {
        val clock = FakeTrackerClock()

        composeTestRule.setContent {
            MusicTrackerTheme {
                MusicTrackerNavHost(
                    homeContent = {
                        HomeScreenContent(
                            uiState = HomeUiState(isNotificationAccessGranted = true, isLoading = false),
                            clock = clock,
                            onEnableNotificationAccess = {},
                            onDismissPermissionBanner = {}
                        )
                    },
                    statsContent = {
                        StatsScreenContent(
                            uiState = StatsUiState(isLoading = false),
                            onSelectTimeframe = {}
                        )
                    },
                    settingsContent = {
                        SettingsScreenContent(
                            uiState = SettingsUiState(isLoadingApps = false),
                            onOpenNotificationAccess = {},
                            onBatteryOptimizationClick = {},
                            onSearchQueryChange = {},
                            onToggleAppAllowed = { _, _ -> },
                            onExportJsonClick = {},
                            onExportSqliteClick = {},
                            onDismissExportFeedback = {},
                            onToggleReduceTransparency = {}
                        )
                    }
                )
            }
        }

        // Starts on Home
        composeTestRule.onNodeWithTag(TestTags.NAV_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()

        // Switch to Stats
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_STATS).assertIsSelected()
        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_CONTROL).assertIsDisplayed()

        // Switch to Settings
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).assertIsSelected()
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertIsDisplayed()

        // Switch back to Home
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_HOME).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
    }

    @Test
    fun rendersAllThreeAccessibilityModesCleanly() {
        val clock = FakeTrackerClock()
        val pausedTrack = NowTrackingState(
            sourcePackage = "com.google.android.apps.youtube.music",
            rawTitle = "White Ferrari",
            rawArtist = "Frank Ocean",
            normalizedTitle = "White Ferrari",
            normalizedArtist = "Frank Ocean",
            album = "Blonde",
            durationMs = 248_000L,
            artworkUri = null,
            isPlaying = false,
            accumulatedMs = 62_000L,
            segmentStartElapsedMs = null,
            isDebouncing = false
        )

        composeTestRule.setContent {
            MusicTrackerTheme(
                accessibilityOverride = LiquidGlassAccessibilityState(
                    reduceTransparency = true,
                    highContrast = true,
                    reduceMotion = true
                )
            ) {
                MusicTrackerNavHost(
                    homeContent = {
                        HomeScreenContent(
                            uiState = HomeUiState(
                                isNotificationAccessGranted = false,
                                nowTracking = pausedTrack,
                                isLoading = false
                            ),
                            clock = clock,
                            onEnableNotificationAccess = {},
                            onDismissPermissionBanner = {}
                        )
                    },
                    statsContent = {},
                    settingsContent = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.NAV_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithText("White Ferrari").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28], application = Application::class)
    fun rendersApi28FrostedFallbackWithoutRenderEffectCrash() {
        val clock = FakeTrackerClock()

        composeTestRule.setContent {
            MusicTrackerTheme {
                MusicTrackerNavHost(
                    homeContent = {
                        HomeScreenContent(
                            uiState = HomeUiState(
                                isNotificationAccessGranted = false,
                                isLoading = false
                            ),
                            clock = clock,
                            onEnableNotificationAccess = {},
                            onDismissPermissionBanner = {}
                        )
                    },
                    statsContent = {},
                    settingsContent = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.NAV_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
    }
}
