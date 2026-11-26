package com.musictracker.app.e2e.tier2

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeActivityResultRegistryOwner
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.FakeTrackerClock
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.scrollToText
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.components.glass.LiquidGlassBackdropHost
import com.musictracker.app.ui.components.glass.LiquidGlassSurface
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.components.glass.LocalLiquidGlassBackdrop
import com.musictracker.app.ui.components.glass.estimateColorLuminance
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.screens.settings.ExportUiState
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsScreenContent
import com.musictracker.app.ui.screens.settings.SettingsUiState
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsScreenContent
import com.musictracker.app.ui.screens.stats.StatsUiState
import com.musictracker.app.ui.theme.DarkLiquidGlassPalette
import com.musictracker.app.ui.theme.LightLiquidGlassPalette
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LocalLiquidGlassAccessibility
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

/**
 * Tier 2 — Area E: UI & Liquid Glass Accessibility Boundaries (Robolectric + Compose).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T2UiAccessibilityBoundariesE2eTest {

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
    fun t2_e1_zeroBlurRadiusGuardNeverCrashes() {
        var reduceTransparency by mutableStateOf(false)
        var backdropObserved = false

        composeRule.setLiquidGlassContent {
            val a11y = LiquidGlassAccessibilityState(reduceTransparency = reduceTransparency)
            CompositionLocalProvider(LocalLiquidGlassAccessibility provides a11y) {
                LiquidGlassBackdropHost(
                    overlay = {
                        backdropObserved = LocalLiquidGlassBackdrop.current != null
                        Column {
                            LiquidGlassSurface(
                                variant = LiquidGlassVariant.Regular,
                                blurRadiusOverride = 0.dp
                            ) {
                                Text("Zero Blur Safe")
                            }
                            LiquidGlassSurface(
                                variant = LiquidGlassVariant.Regular,
                                blurRadiusOverride = 0.1.dp
                            ) {
                                Text("Sub-Threshold Blur Safe")
                            }
                        }
                    },
                    content = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray)
                        )
                    }
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(
            "LiquidGlassBackdropHost must provide non-null LocalLiquidGlassBackdrop so RenderEffect blur guard is exercised",
            backdropObserved
        )
        composeRule.onNodeWithText("Zero Blur Safe").assertIsDisplayed()
        composeRule.onNodeWithText("Sub-Threshold Blur Safe").assertIsDisplayed()

        // Also verify with reduceTransparency = true
        reduceTransparency = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Zero Blur Safe").assertIsDisplayed()
        composeRule.onNodeWithText("Sub-Threshold Blur Safe").assertIsDisplayed()
    }

    @Test
    fun t2_e2_clearGlass35PercentDimmingScrimOverBrightMedia() {
        // Verify palette token adheres strictly to 35% dimming rule (0x59 / 255 ≈ 0.349)
        assertEquals(0.35f, DarkLiquidGlassPalette.clearBrightMediaDimScrim.alpha, 0.01f)
        assertEquals(0.35f, LightLiquidGlassPalette.clearBrightMediaDimScrim.alpha, 0.01f)
        assertTrue(estimateColorLuminance(Color.White) > LiquidGlassMetrics.BrightMediaLuminanceThreshold)
        assertTrue(estimateColorLuminance(Color.Black) < LiquidGlassMetrics.BrightMediaLuminanceThreshold)

        composeRule.setLiquidGlassContent {
            LiquidGlassBackdropHost(
                overlay = {
                    LiquidGlassSurface(
                        variant = LiquidGlassVariant.Clear,
                        isBrightMediaBehind = true
                    ) {
                        Text("Bright Media Legible")
                    }
                },
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            )
        }
        composeRule.onNodeWithText("Bright Media Legible").assertIsDisplayed()
    }

    @Test
    fun t2_e3_allThreeAccessibilityModesActiveSimultaneouslyRenderAllScreens() {
        val allA11y = LiquidGlassAccessibilityState(
            reduceTransparency = true,
            highContrast = true,
            reduceMotion = true
        )

        composeRule.setLiquidGlassContent(accessibilityOverride = allA11y) {
            MusicTrackerNavHost(
                homeContent = {
                    HomeScreenContent(
                        uiState = HomeUiState(isNotificationAccessGranted = false, isLoading = false),
                        clock = env.clock,
                        onEnableNotificationAccess = {},
                        onDismissPermissionBanner = {}
                    )
                },
                statsContent = {
                    StatsScreenContent(
                        uiState = StatsUiState(
                            selectedTimeframe = StatsTimeframe.TODAY,
                            summary = ListeningSummary(180_000L, 1, 0, 0f),
                            topTracks = listOf(
                                TopTrackStat(
                                    trackId = 1L,
                                    title = "Accessible Track",
                                    artist = "Accessible Artist",
                                    album = "Album",
                                    artworkUri = null,
                                    totalListenedMs = 180_000L,
                                    playCount = 1
                                )
                            ),
                            topArtists = emptyList(),
                            isLoading = false
                        ),
                        onSelectTimeframe = {}
                    )
                },
                settingsContent = {
                    SettingsScreenContent(
                        uiState = SettingsUiState(
                            isNotificationAccessGranted = true,
                            isIgnoringBatteryOptimizations = true,
                            reduceTransparencyEnabled = true,
                            isLoadingApps = false
                        ),
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

        // 1. Home screen
        composeRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_STAT_TIME).assertIsDisplayed()

        // 2. Stats screen
        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_CONTROL).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_TRACKS_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("Accessible Track").assertIsDisplayed()

        // 3. Settings screen
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_STATUS).assertIsDisplayed()
    }

    @Test
    fun t2_e4_homeZeroPlaysSkipRateDashAndPauseFreezesLiveCounter() {
        val clock = FakeTrackerClock(initialElapsedMs = 40_000L)
        val pausedState = NowTrackingState(
            sourcePackage = "com.spotify.music",
            rawTitle = "Paused Track",
            rawArtist = "Artist",
            normalizedTitle = "Paused Track",
            normalizedArtist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            artworkUri = null,
            isPlaying = false,
            accumulatedMs = 25_000L,
            segmentStartElapsedMs = null,
            isDebouncing = false
        )

        composeRule.setLiquidGlassContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    isNotificationAccessGranted = true,
                    nowTracking = pausedState,
                    todaySummary = ListeningSummary(totalListenedMs = 0L, playCount = 0, skippedCount = 0, skipRate = 0f),
                    isLoading = false
                ),
                clock = clock,
                onEnableNotificationAccess = {},
                onDismissPermissionBanner = {}
            )
        }

        composeRule.onNodeWithTag(TestTags.HOME_STAT_SKIP_RATE).assertTextContains("—")
        composeRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:25")

        // Advance clock 10 seconds — paused counter must stay frozen at 00:25
        clock.advanceBy(10_000L)
        composeRule.mainClock.advanceTimeBy(2_500L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:25")
    }

    @Test
    fun t2_e5_statsEmptyTimeframeAndExtremeLongTrackTitle() {
        val longTitle = "A".repeat(120)
        val longArtist = "B".repeat(120)

        var uiState by mutableStateOf(
            StatsUiState(
                selectedTimeframe = StatsTimeframe.TODAY,
                summary = ListeningSummary(totalListenedMs = 0L, playCount = 0, skippedCount = 0, skipRate = 0f),
                topTracks = emptyList(),
                topArtists = emptyList(),
                isLoading = false
            )
        )

        composeRule.setLiquidGlassContent {
            StatsScreenContent(
                uiState = uiState,
                onSelectTimeframe = {}
            )
        }

        // (a) Empty timeframe renders clean empty state
        composeRule.onNodeWithText("No Data for Today").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_TRACKS_LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_ARTISTS_LIST).assertDoesNotExist()

        // (b) 120-character track/artist title renders cleanly without layout crash
        uiState = StatsUiState(
            selectedTimeframe = StatsTimeframe.TODAY,
            summary = ListeningSummary(totalListenedMs = 180_000L, playCount = 1, skippedCount = 0, skipRate = 0f),
            topTracks = listOf(
                TopTrackStat(
                    trackId = 1L,
                    title = longTitle,
                    artist = longArtist,
                    album = "Album",
                    artworkUri = null,
                    totalListenedMs = 180_000L,
                    playCount = 1
                )
            ),
            topArtists = emptyList(),
            isLoading = false
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.STATS_TOP_TRACKS_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.statsTrackItem(1L)).assertIsDisplayed()
    }

    @Test
    fun t2_e6_settingsSafPickerCancellationLeavesExportIdle() {
        val cancellingOwner = FakeActivityResultRegistryOwner { null }
        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = true)
        val vm = SettingsViewModel(
            systemPermissionHelper = permHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        composeRule.setLiquidGlassContent(activityResultRegistryOwner = cancellingOwner) {
            SettingsRoute(viewModel = vm, systemPermissionHelper = permHelper)
        }

        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TestTags.SETTINGS_EXPORT_JSON_BTN))
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeRule.waitForIdle()

        assertEquals(ExportUiState.Idle, vm.uiState.value.exportState)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertDoesNotExist()
    }

    @Test
    fun t2_e7_settingsAllowlistEmptySearchDisplaysNoMatchMessage() {
        val permHelper = FakeSystemPermissionHelper(
            notificationAccessGranted = true,
            installedApps = listOf(
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true)
            )
        )
        val vm = SettingsViewModel(
            systemPermissionHelper = permHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        composeRule.setLiquidGlassContent {
            SettingsRoute(viewModel = vm, systemPermissionHelper = permHelper)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) { !vm.uiState.value.isLoadingApps }

        composeRule.scrollToTag(TestTags.SETTINGS_ALLOWLIST_SEARCH)
        composeRule.onNodeWithTag(TestTags.SETTINGS_ALLOWLIST_SEARCH)
            .performTextInput("zzz_nonexistent_pkg")
        composeRule.waitForIdle()

        val expectedMsg = "No matching apps for \"zzz_nonexistent_pkg\""
        composeRule.scrollToText(expectedMsg)
        composeRule.onNodeWithText(expectedMsg).assertIsDisplayed()
    }
}
