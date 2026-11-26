package com.musictracker.app.e2e.tier5

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeActivityResultRegistryOwner
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.FakeTrackerClock
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.tracking.SystemPermissionHelper
import com.musictracker.app.ui.common.computeLiveListenedMs
import com.musictracker.app.ui.common.formatEventDuration
import com.musictracker.app.ui.common.formatLiveCounter
import com.musictracker.app.ui.common.formatRelativeTime
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSourceAppName
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.deterministicArtworkColors
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassChip
import com.musictracker.app.ui.components.glass.LiquidGlassNavBar
import com.musictracker.app.ui.components.glass.LiquidGlassNavItem
import com.musictracker.app.ui.components.glass.LiquidGlassSegmentedControl
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassSurface
import com.musictracker.app.ui.components.glass.LocalInsideLiquidGlass
import com.musictracker.app.ui.components.glass.ScrollEdgeContainer
import com.musictracker.app.ui.components.glass.ScrollEdgeStyle
import com.musictracker.app.ui.components.glass.rememberNavBarScrollConnection
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsScreenContent
import com.musictracker.app.ui.screens.stats.StatsUiState
import com.musictracker.app.ui.screens.stats.StatsViewModel
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.LocalLiquidGlassAccessibility
import com.musictracker.app.ui.theme.MusicTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 5 — White-Box Adversarial Tests (UI, ViewModels, Formatters, SAF, DI, Liquid Glass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T5UiAdversarialTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private fun newEnv(clock: FakeTrackerClock = FakeTrackerClock()): E2eTestEnvironment {
        val env = E2eTestEnvironment(context = context, clock = clock)
        environments += env
        return env
    }

    // -------------------------------------------------------------------------
    // T5-UI-1: Formatters & Deterministic Artwork Colors Adversarial Boundaries
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui1_formattersAdversarialBoundariesNaNOverflowAndNegativeDeltas() {
        // formatSkipRate: NaN, negative, >1, +Inf, playCount <= 0
        assertEquals("—", formatSkipRate(playCount = 5, skipRate = Float.NaN))
        assertEquals("—", formatSkipRate(playCount = 0, skipRate = 0.5f))
        assertEquals("—", formatSkipRate(playCount = -1, skipRate = 0.5f))
        assertEquals("0%", formatSkipRate(playCount = 4, skipRate = -0.5f))
        assertEquals("100%", formatSkipRate(playCount = 4, skipRate = 1.5f))
        assertEquals("100%", formatSkipRate(playCount = 4, skipRate = Float.POSITIVE_INFINITY))
        assertEquals("0%", formatSkipRate(playCount = 4, skipRate = Float.NEGATIVE_INFINITY))

        // formatLiveCounter: negative, 59_999ms, 3_599_000ms, 3_600_000ms, 100h
        assertEquals("00:00", formatLiveCounter(-10_000L))
        assertEquals("00:59", formatLiveCounter(59_999L))
        assertEquals("59:59", formatLiveCounter(3_599_000L))
        assertEquals("1:00:00", formatLiveCounter(3_600_000L))
        assertEquals("100:00:00", formatLiveCounter(100L * 3_600_000L))

        // computeLiveListenedMs: negative clock delta & null segmentStartElapsedMs
        val playingState = NowTrackingState(
            sourcePackage = "com.spotify.music",
            rawTitle = "T",
            rawArtist = "A",
            normalizedTitle = "T",
            normalizedArtist = "A",
            album = "",
            durationMs = 180_000L,
            artworkUri = null,
            isPlaying = true,
            accumulatedMs = 12_000L,
            segmentStartElapsedMs = 50_000L,
            isDebouncing = false
        )
        assertEquals(12_000L, computeLiveListenedMs(playingState, elapsedRealtimeMs = 40_000L))
        assertEquals(
            12_000L,
            computeLiveListenedMs(playingState.copy(segmentStartElapsedMs = null), elapsedRealtimeMs = 60_000L)
        )

        // formatSummaryDuration & formatEventDuration
        assertEquals("0m", formatSummaryDuration(-1L))
        assertEquals("0m", formatSummaryDuration(0L))
        assertEquals("0m", formatSummaryDuration(500L))
        assertEquals("1h 0m", formatSummaryDuration(3_600_000L))

        assertEquals("0s", formatEventDuration(-1L))
        assertEquals("0s", formatEventDuration(0L))
        assertEquals("0s", formatEventDuration(500L))
        assertEquals("1h 00m", formatEventDuration(3_600_000L))

        // formatRelativeTime: future timestamp clamps to "Just now"
        assertEquals("Just now", formatRelativeTime(endedAtEpochMs = 2_000_000L, nowEpochMs = 1_000_000L))

        // formatSourceAppName: blank, trailing dot, unknown package
        assertEquals("", formatSourceAppName(""))
        assertEquals("Com.example.", formatSourceAppName("com.example."))
        assertEquals("Myplayer", formatSourceAppName("com.example.myplayer"))

        // deterministicArtworkColors with Int.MIN_VALUE hashCode ("polygenelubricants".hashCode() == Int.MIN_VALUE)
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        val colors = deterministicArtworkColors("polygenelubricants")
        assertNotNull(colors.first)
        assertNotNull(colors.second)
    }

    // -------------------------------------------------------------------------
    // T5-UI-2: Glass Nesting Demotion & Untested Glass Primitives
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui2_glassNestingDemotionAndUntestedGlassPrimitives() {
        var outerInsideGlass: Boolean? = null
        var innerInsideGlass: Boolean? = null
        var sheetExpanded by mutableStateOf(false)
        var disabledClicks = 0
        var chipClicks = 0

        composeRule.setLiquidGlassContent {
            ScrollEdgeContainer(style = ScrollEdgeStyle.Hard) {
                Column {
                    outerInsideGlass = LocalInsideLiquidGlass.current
                    LiquidGlassSurface {
                        innerInsideGlass = LocalInsideLiquidGlass.current
                        LiquidGlassSurface {
                            Text("Nested Glass Safe")
                        }
                    }

                    LiquidGlassSheet(isExpanded = sheetExpanded) {
                        Text(if (sheetExpanded) "Expanded Sheet" else "Half Sheet")
                    }

                    LiquidGlassChip(
                        label = "Selected Chip",
                        selected = true,
                        onClick = { chipClicks++ }
                    )
                    LiquidGlassChip(
                        label = "Unselected Chip",
                        selected = false,
                        onClick = { chipClicks++ }
                    )

                    LiquidGlassButton(
                        onClick = { disabledClicks++ },
                        enabled = false,
                        modifier = Modifier.testTag("disabled_glass_btn")
                    ) {
                        Text("Disabled CTA")
                    }

                    // Empty options guard & unknown selectedOption fallback
                    LiquidGlassSegmentedControl(
                        options = emptyList<String>(),
                        selectedOption = "",
                        onOptionSelected = {},
                        labelProvider = { it }
                    )
                    LiquidGlassSegmentedControl(
                        options = listOf("One", "Two"),
                        selectedOption = "UnknownOption",
                        onOptionSelected = {},
                        labelProvider = { it }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(false, outerInsideGlass)
        assertEquals(true, innerInsideGlass)
        composeRule.onNodeWithText("Nested Glass Safe").assertIsDisplayed()
        composeRule.onNodeWithText("Half Sheet").assertIsDisplayed()

        // Toggle sheet to expanded
        composeRule.runOnIdle { sheetExpanded = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Expanded Sheet").assertIsDisplayed()

        // Disabled button click must be suppressed
        composeRule.onNodeWithTag("disabled_glass_btn").performClick()
        composeRule.waitForIdle()
        assertEquals(0, disabledClicks)

        // Chip click works
        composeRule.onNodeWithText("Unselected Chip").performClick()
        composeRule.waitForIdle()
        assertEquals(1, chipClicks)
    }

    // -------------------------------------------------------------------------
    // T5-UI-3: NavBar Scroll Minimization & Reduced Motion Override
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui3_navBarScrollMinimizationAndReducedMotionOverride() {
        var minimized by mutableStateOf(false)
        var reduceMotion by mutableStateOf(false)
        lateinit var scrollConnection: NestedScrollConnection

        val navItems = listOf(
            LiquidGlassNavItem("home", "Home", Icons.Rounded.Home, testTag = TestTags.NAV_TAB_HOME),
            LiquidGlassNavItem("settings", "Settings", Icons.Rounded.Settings, testTag = TestTags.NAV_TAB_SETTINGS)
        )

        composeRule.setLiquidGlassContent {
            CompositionLocalProvider(
                LocalLiquidGlassAccessibility provides LiquidGlassAccessibilityState(reduceMotion = reduceMotion)
            ) {
                scrollConnection = rememberNavBarScrollConnection(onMinimizedChange = { minimized = it })
                LiquidGlassNavBar(
                    items = navItems,
                    selectedId = "home",
                    onSelect = {},
                    isMinimized = minimized
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()

        // Scroll down (y = -20f) -> minimizes and hides tab labels
        composeRule.runOnIdle {
            scrollConnection.onPreScroll(Offset(0f, -20f), NestedScrollSource.UserInput)
        }
        composeRule.waitForIdle()
        assertTrue(minimized)
        composeRule.onNodeWithText("Home").assertDoesNotExist()

        // Enable Reduced Motion while minimized -> effectiveMinimized becomes false, labels reappear
        composeRule.runOnIdle { reduceMotion = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home").assertIsDisplayed()

        // Disable Reduced Motion & scroll up (y = +20f) -> expands and keeps labels visible
        composeRule.runOnIdle {
            reduceMotion = false
            scrollConnection.onPreScroll(Offset(0f, +20f), NestedScrollSource.UserInput)
        }
        composeRule.waitForIdle()
        assertFalse(minimized)
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // T5-UI-4: System Accessibility Settings Detection via Robolectric
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui4_systemAccessibilitySettingsDetectionViaRobolectric() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Settings.Secure.putInt(context.contentResolver, "high_text_contrast_enabled", 1)

        var resolvedA11y: LiquidGlassAccessibilityState? = null
        composeRule.setContent {
            MusicTrackerTheme(darkTheme = true, accessibilityOverride = null) {
                resolvedA11y = LocalLiquidGlassAccessibility.current
                Text("A11y Probe")
            }
        }

        composeRule.waitForIdle()
        assertNotNull(resolvedA11y)
        assertTrue("ANIMATOR_DURATION_SCALE=0f must resolve reduceMotion=true", resolvedA11y!!.reduceMotion)
        assertTrue("high_text_contrast_enabled=1 must resolve highContrast=true", resolvedA11y!!.highContrast)
    }

    // -------------------------------------------------------------------------
    // T5-UI-5: Settings SAF ActivityNotFound, Stream Error, Dismiss, Already-Exempt Battery & App Load Error
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui5_settingsSafActivityNotFoundStreamErrorDismissAndAlreadyExemptBattery() {
        val env = newEnv()

        // 1) Throwing getInstalledLaunchableApps recovers cleanly to isLoadingApps = false & empty message.
        val throwingPermHelper = object : SystemPermissionHelper {
            var openBatterySettingsCalls = 0
            var requestBatteryExemptionCalls = 0

            override fun isNotificationAccessGranted(): Boolean = true
            override fun openNotificationListenerSettings(context: Context): Boolean = true
            override fun isIgnoringBatteryOptimizations(): Boolean = true
            override fun requestBatteryOptimizationExemption(context: Context): Boolean {
                requestBatteryExemptionCalls++
                return true
            }

            override fun openBatteryOptimizationSettings(context: Context): Boolean {
                openBatterySettingsCalls++
                return true
            }

            override fun getInstalledLaunchableApps(): List<InstalledAppInfo> {
                throw RuntimeException("PackageManager failure")
            }
        }

        val viewModel = SettingsViewModel(
            systemPermissionHelper = throwingPermHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val throwingRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    throw ActivityNotFoundException("No DocumentsUI installed")
                }
            }
        }

        composeRule.setLiquidGlassContent(activityResultRegistryOwner = throwingRegistryOwner) {
            SettingsRoute(
                viewModel = viewModel,
                systemPermissionHelper = throwingPermHelper
            )
        }

        composeRule.waitUntilIdle { !viewModel.uiState.value.isLoadingApps }

        // Battery button when ALREADY exempted must call openBatteryOptimizationSettings (NOT requestBatteryOptimizationExemption).
        composeRule.scrollToTag(TestTags.SETTINGS_BATTERY_BTN)
        composeRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_BTN).performClick()
        composeRule.waitForIdle()
        assertEquals(1, throwingPermHelper.openBatterySettingsCalls)
        assertEquals(0, throwingPermHelper.requestBatteryExemptionCalls)

        // JSON SAF launch throws ActivityNotFoundException -> no crash, error banner shown, dismiss clears it.
        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_JSON_BTN)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeRule.waitUntilIdle { viewModel.uiState.value.exportState is com.musictracker.app.ui.screens.settings.ExportUiState.Error }
        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_FEEDBACK)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss export feedback").performClick()
        composeRule.waitForIdle()
        assertEquals(
            com.musictracker.app.ui.screens.settings.ExportUiState.Idle,
            viewModel.uiState.value.exportState
        )

        // SQLite SAF launch throws ActivityNotFoundException -> error banner shown.
        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN).performClick()
        composeRule.waitUntilIdle { viewModel.uiState.value.exportState is com.musictracker.app.ui.screens.settings.ExportUiState.Error }
        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_FEEDBACK)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()

        // Null output stream & SecurityException during exportJson surface ExportUiState.Error.
        viewModel.clearExportFeedback()
        viewModel.exportJson { null }
        composeRule.waitUntilIdle { viewModel.uiState.value.exportState is com.musictracker.app.ui.screens.settings.ExportUiState.Error }
        val nullStreamError = viewModel.uiState.value.exportState as com.musictracker.app.ui.screens.settings.ExportUiState.Error
        assertTrue(nullStreamError.message.contains("Unable to open", ignoreCase = true))

        viewModel.clearExportFeedback()
        viewModel.exportJson { throw SecurityException("SAF permission revoked") }
        composeRule.waitUntilIdle { viewModel.uiState.value.exportState is com.musictracker.app.ui.screens.settings.ExportUiState.Error }
        val secError = viewModel.uiState.value.exportState as com.musictracker.app.ui.screens.settings.ExportUiState.Error
        assertTrue(secError.message.contains("SAF permission revoked"))

        // Empty launchable apps fallback text is shown after PackageManager exception.
        composeRule.scrollToTag(TestTags.SETTINGS_ALLOWLIST_SEARCH)
        composeRule.onNodeWithText("No launchable apps found.").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // T5-UI-6: Stats Midnight Rollover Refresh, Emoji Monogram & Zero-Duration Guard
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui6_statsMidnightRolloverRefreshEmojiMonogramAndZeroDurationGuard() {
        // Day 1: 2025-06-15T23:55:00Z
        val day1Late = 1_750_031_700_000L
        val clock = FakeTrackerClock(initialEpochMs = day1Late)
        val env = newEnv(clock = clock)

        // Seed 1 listen event on Day 1.
        kotlinx.coroutines.runBlocking {
            env.listeningRepository.recordCompletedSession(
                com.musictracker.app.domain.model.CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Late Night Track",
                    rawArtist = "Midnight Artist",
                    album = "",
                    durationMs = 180_000L,
                    artworkUri = null,
                    startedAtEpoch = day1Late - 60_000L,
                    endedAtEpoch = day1Late,
                    actualListenedMs = 60_000L,
                    completedRatio = 0.33f,
                    wasSkipped = true
                )
            )
        }

        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = clock,
            zoneId = ZoneOffset.UTC
        )

        val day1State = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.summary.playCount == 1 }
        assertEquals(1, day1State.summary.playCount)

        // Advance clock 10 minutes past midnight into Day 2 (2025-06-16T00:05:00Z) and call refresh().
        clock.advanceBy(10L * 60_000L)
        statsVm.refresh()

        val day2State = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.summary.playCount == 0 }
        assertEquals("After midnight rollover + refresh(), TODAY summary must reset to 0 plays", 0, day2State.summary.playCount)

        // Render StatsScreenContent with emoji-prefixed artist, blank artist, and 0ms duration.
        composeRule.setLiquidGlassContent {
            StatsScreenContent(
                uiState = StatsUiState(
                    summary = ListeningSummary(totalListenedMs = 0L, playCount = 0, skippedCount = 0),
                    topTracks = listOf(
                        TopTrackStat(
                            trackId = 1L,
                            title = "Zero Track",
                            artist = "🎵 Lo-Fi Girl",
                            album = "",
                            artworkUri = null,
                            totalListenedMs = 0L,
                            playCount = 1
                        )
                    ),
                    topArtists = listOf(
                        TopArtistStat(
                            artist = "🎵 Lo-Fi Girl",
                            totalListenedMs = 0L,
                            playCount = 1,
                            uniqueTrackCount = 1
                        ),
                        TopArtistStat(
                            artist = "   ",
                            totalListenedMs = 0L,
                            playCount = 1,
                            uniqueTrackCount = 1
                        )
                    ),
                    isLoading = false
                ),
                onSelectTimeframe = {}
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Top 5 Artists").performClick()
        composeRule.waitForIdle()
        // Full emoji code point (not broken high surrogate) and "?" fallback for blank artist.
        composeRule.onNodeWithText("🎵").assertIsDisplayed()
        composeRule.onNodeWithText("?").assertExists()
    }

    // -------------------------------------------------------------------------
    // T5-UI-7: Home Banner Reappears After Grant -> Revoke & NowTracking Progress Edges
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui7_homeBannerReappearsAfterGrantThenRevokeAndNowTrackingProgressEdges() {
        val clock = FakeTrackerClock()
        val env = newEnv(clock = clock)
        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = false)

        val homeVm = HomeViewModel(
            listeningRepository = env.listeningRepository,
            playSessionEngine = env.playSessionStateMachine,
            systemPermissionHelper = permHelper,
            clock = clock,
            zoneId = ZoneOffset.UTC
        )

        // 1) Initially missing permission -> banner shown.
        composeRule.waitForIdle()
        val s1 = env.awaitStateFlow(homeVm.uiState, timeoutMs = 15_000L) { !it.isLoading && it.showPermissionBanner }
        assertTrue(s1.showPermissionBanner)

        // 2) Dismiss banner -> hidden.
        homeVm.dismissPermissionBanner()
        val s2 = env.awaitStateFlow(homeVm.uiState, timeoutMs = 15_000L) { !it.showPermissionBanner }
        assertFalse(s2.showPermissionBanner)

        // 3) Grant permission + refresh -> resets dismissal flag.
        permHelper.notificationAccessGranted = true
        homeVm.refreshPermissionAndDay()
        val s3 = env.awaitStateFlow(homeVm.uiState, timeoutMs = 15_000L) {
            it.isNotificationAccessGranted && !it.isPermissionBannerDismissed
        }
        assertTrue(s3.isNotificationAccessGranted)
        assertFalse(s3.isPermissionBannerDismissed)

        // 4) Revoke permission + refresh -> banner MUST reappear.
        permHelper.notificationAccessGranted = false
        homeVm.refreshPermissionAndDay()
        val s4 = env.awaitStateFlow(homeVm.uiState, timeoutMs = 15_000L) {
            !it.isNotificationAccessGranted && it.showPermissionBanner
        }
        assertTrue("Permission banner must reappear after grant -> re-revoke", s4.showPermissionBanner)

        // 5) Render HomeScreenContent with durationMs = 0L and accumulatedMs > durationMs (>100% clamp).
        var nowTracking by mutableStateOf(
            NowTrackingState(
                sourcePackage = "com.google.android.apps.youtube.music",
                rawTitle = "Unknown Duration Track",
                rawArtist = "Live Stream",
                normalizedTitle = "Unknown Duration Track",
                normalizedArtist = "Live Stream",
                album = "",
                durationMs = 0L,
                artworkUri = null,
                isPlaying = true,
                accumulatedMs = 15_000L,
                segmentStartElapsedMs = clock.elapsedRealtimeMs(),
                isDebouncing = false
            )
        )

        composeRule.setLiquidGlassContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    isNotificationAccessGranted = true,
                    nowTracking = nowTracking,
                    todaySummary = ListeningSummary(totalListenedMs = 0L, playCount = 0, skippedCount = 0),
                    recentHistory = emptyList(),
                    isLoading = false
                ),
                clock = clock,
                onEnableNotificationAccess = {},
                onDismissPermissionBanner = {}
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unknown Duration Track").assertIsDisplayed()

        // Switch to accumulatedMs > durationMs (200% replay) -> clamps cleanly to 1f without crash.
        composeRule.runOnIdle {
            nowTracking = nowTracking.copy(
                normalizedTitle = "Replayed Track",
                durationMs = 60_000L,
                accumulatedMs = 120_000L
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Replayed Track").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // T5-UI-8: AppContainer DI Wiring & ViewModel Factories Smoke Test
    // -------------------------------------------------------------------------

    @Test
    fun t5_ui8_appContainerDiWiringAndViewModelFactoriesSmokeTest() {
        val clock = FakeTrackerClock()
        val container = AppContainer(
            context = context,
            clock = clock
        )
        try {
            assertNotNull(container.database)
            assertNotNull(container.listeningRepository)
            assertNotNull(container.allowlistRepository)
            assertNotNull(container.accessibilityPreferencesStore)
            assertNotNull(container.dataExporter)
            assertNotNull(container.systemPermissionHelper)
            assertNotNull(container.mediaSessionCoordinator)
            assertFalse(container.isListenerConnected.value)

            val homeVm = HomeViewModel.factory(container).create(HomeViewModel::class.java)
            val statsVm = StatsViewModel.factory(container).create(StatsViewModel::class.java)
            val settingsVm = SettingsViewModel.factory(container).create(SettingsViewModel::class.java)

            assertNotNull(homeVm)
            assertNotNull(statsVm)
            assertNotNull(settingsVm)
        } finally {
            runCatching { container.applicationScope.cancel() }
            runCatching { container.database.close() }
        }
    }
}
