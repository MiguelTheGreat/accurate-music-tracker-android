package com.musictracker.app.e2e.tier1

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeActivityResultRegistryOwner
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.FakeTrackerClock
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsRoute
import com.musictracker.app.ui.screens.stats.StatsViewModel
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.LocalLiquidGlassAccessibility
import com.musictracker.app.ui.theme.concentricCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

/**
 * Tier 1 — Area E: Liquid Glass Design System & Compose UI (Robolectric, F23–F26).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T1LiquidGlassUiE2eTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context, fileBackedDb = true)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun t1_e1_concentricCornerRadiusAndAccessibilityCompositionLocal() {
        assertEquals(16.dp, concentricCornerRadius(outerRadius = 28.dp, padding = 12.dp))
        assertEquals(0.dp, concentricCornerRadius(outerRadius = 8.dp, padding = 12.dp))

        var observed: LiquidGlassAccessibilityState? = null
        val expected = LiquidGlassAccessibilityState(
            reduceTransparency = true,
            highContrast = true,
            reduceMotion = true
        )

        composeRule.setLiquidGlassContent(accessibilityOverride = expected) {
            observed = LocalLiquidGlassAccessibility.current
            Text("A11y Check")
        }

        composeRule.waitForIdle()
        assertEquals(expected, observed)
    }

    @Test
    fun t1_e2_navigationCapsuleSwitchesBetweenHomeStatsAndSettings() {
        composeRule.setLiquidGlassContent {
            MusicTrackerNavHost(
                homeContent = { Text("Screen: Home") },
                statsContent = { Text("Screen: Stats") },
                settingsContent = { Text("Screen: Settings") }
            )
        }

        composeRule.onNodeWithTag(TestTags.NAV_BAR).assertIsDisplayed()
        composeRule.onNodeWithText("Screen: Home").assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.onNodeWithText("Screen: Stats").assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.onNodeWithText("Screen: Settings").assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.NAV_TAB_HOME).performClick()
        composeRule.onNodeWithText("Screen: Home").assertIsDisplayed()
    }

    @Test
    fun t1_e3_homeLiveNowTrackingCardAndTickingCounter() {
        val clock = FakeTrackerClock(initialElapsedMs = 50_000L)
        val activeState = NowTrackingState(
            sourcePackage = "com.google.android.apps.youtube.music",
            rawTitle = "Instant Crush (Official Video)",
            rawArtist = "Daft Punk - Topic",
            normalizedTitle = "Instant Crush",
            normalizedArtist = "Daft Punk",
            album = "Random Access Memories",
            durationMs = 337_000L,
            artworkUri = null,
            isPlaying = true,
            accumulatedMs = 10_000L,
            segmentStartElapsedMs = 50_000L,
            isDebouncing = false
        )

        composeRule.setLiquidGlassContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    isNotificationAccessGranted = true,
                    nowTracking = activeState,
                    isLoading = false
                ),
                clock = clock,
                onEnableNotificationAccess = {},
                onDismissPermissionBanner = {}
            )
        }

        composeRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Instant Crush").assertIsDisplayed()
        composeRule.onNodeWithText("Daft Punk").assertIsDisplayed()
        composeRule.onNodeWithText("YouTube Music", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:10")

        // Advance clock and Compose main clock by 1 second
        clock.advanceBy(1_000L)
        composeRule.mainClock.advanceTimeBy(1_050L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:11")
    }

    @Test
    fun t1_e4_homeTodaySummaryAndRecentHistoryRender() {
        val now = env.clock.currentTimeMillis()
        val uiState = HomeUiState(
            isNotificationAccessGranted = true,
            nowTracking = null,
            todaySummary = ListeningSummary(
                totalListenedMs = 45 * 60_000L,
                playCount = 10,
                skippedCount = 2,
                skipRate = 0.20f
            ),
            recentHistory = listOf(
                RecentListenItem(
                    eventId = 101L,
                    trackId = 1L,
                    title = "Midnight City",
                    artist = "M83",
                    album = "Hurry Up, We're Dreaming",
                    artworkUri = null,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = now - 300_000L,
                    endedAtEpoch = now - 60_000L,
                    actualListenedMs = 240_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            ),
            isLoading = false
        )

        composeRule.setLiquidGlassContent {
            HomeScreenContent(
                uiState = uiState,
                clock = env.clock,
                onEnableNotificationAccess = {},
                onDismissPermissionBanner = {}
            )
        }

        composeRule.onNodeWithTag(TestTags.HOME_STAT_TIME).assertTextContains("45m")
        composeRule.onNodeWithTag(TestTags.HOME_STAT_PLAYS).assertTextContains("10")
        composeRule.onNodeWithTag(TestTags.HOME_STAT_SKIP_RATE).assertTextContains("20%")
        composeRule.onNodeWithTag(TestTags.HOME_RECENT_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.homeRecentItem(101L)).assertIsDisplayed()
        composeRule.onNodeWithText("Midnight City").assertIsDisplayed()
    }

    @Test
    fun t1_e5_statsTimeframeSwitcherUpdatesTopTracksAndArtists() {
        val now = env.clock.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1_000L

        runBlocking {
            // Today: Frequent Skip Song has 3 plays × 40s = 120s
            repeat(3) { idx ->
                env.listeningRepository.recordCompletedSession(
                    CompletedListenSession(
                        sourcePackage = "com.spotify.music",
                        rawTitle = "Frequent Skip Song",
                        rawArtist = "Skip Artist",
                        album = "Album",
                        durationMs = 200_000L,
                        artworkUri = null,
                        startedAtEpoch = now - (4 - idx) * 60_000L,
                        endedAtEpoch = now - (4 - idx) * 60_000L + 40_000L,
                        actualListenedMs = 40_000L,
                        completedRatio = 0.2f,
                        wasSkipped = true
                    )
                )
            }
            // Today: Deep Listen Song has 1 play × 240s = 240s (must rank #1 over 3-play 120s track)
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Deep Listen Song",
                    rawArtist = "Deep Artist",
                    album = "Album",
                    durationMs = 240_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 30_000L,
                    endedAtEpoch = now,
                    actualListenedMs = 240_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
            // 3 days ago: included in 7D, 30D, ALL (1 play × 300s = 300s -> ranks #1 in 7D)
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Three Day Song",
                    rawArtist = "Mid Artist",
                    album = "Album",
                    durationMs = 300_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 3 * dayMs,
                    endedAtEpoch = now - 3 * dayMs + 300_000L,
                    actualListenedMs = 300_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
            // 15 days ago: included in 30D, ALL (1 play × 420s = 420s -> ranks #1 in 30D)
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Fifteen Day Song",
                    rawArtist = "Old Artist",
                    album = "Album",
                    durationMs = 420_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 15 * dayMs,
                    endedAtEpoch = now - 15 * dayMs + 420_000L,
                    actualListenedMs = 420_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
            // 45 days ago: included ONLY in ALL (1 play × 600s = 600s -> ranks #1 in ALL)
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Forty Five Day Song",
                    rawArtist = "Ancient Artist",
                    album = "Album",
                    durationMs = 600_000L,
                    artworkUri = null,
                    startedAtEpoch = now - 45 * dayMs,
                    endedAtEpoch = now - 45 * dayMs + 600_000L,
                    actualListenedMs = 600_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        }

        val vm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )

        composeRule.setLiquidGlassContent {
            StatsRoute(viewModel = vm)
        }

        // 1. TODAY: 2 tracks, 2 artists; Deep Listen Song (1 play, 240s) ranks above Frequent Skip Song (3 plays, 120s)
        composeRule.waitUntilIdle {
            vm.uiState.value.topTracks.size == 2 && vm.uiState.value.topArtists.size == 2
        }
        assertEquals("Deep Listen Song", vm.uiState.value.topTracks[0].title)
        assertEquals(240_000L, vm.uiState.value.topTracks[0].totalListenedMs)
        assertEquals("Frequent Skip Song", vm.uiState.value.topTracks[1].title)
        assertEquals(120_000L, vm.uiState.value.topTracks[1].totalListenedMs)
        assertEquals("Deep Artist", vm.uiState.value.topArtists[0].artist)
        assertEquals("Skip Artist", vm.uiState.value.topArtists[1].artist)

        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_CONTROL).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_TRACKS_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("Deep Listen Song").assertIsDisplayed()
        composeRule.onNodeWithText("Top 5 Artists").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_ARTISTS_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.statsArtistItem(0)).assertIsDisplayed()
        composeRule.onNodeWithText("Top 5 Tracks").performClick()
        composeRule.waitForIdle()

        // 2. 7 DAYS: 3 tracks, 3 artists; Three Day Song (300s) ranks #1
        composeRule.scrollToTag(TestTags.STATS_TIMEFRAME_CONTROL)
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_7D).performClick()
        composeRule.waitUntilIdle {
            vm.uiState.value.selectedTimeframe == StatsTimeframe.SEVEN_DAYS &&
                vm.uiState.value.topTracks.size == 3 &&
                vm.uiState.value.topArtists.size == 3
        }
        assertEquals("Three Day Song", vm.uiState.value.topTracks[0].title)
        assertEquals("Mid Artist", vm.uiState.value.topArtists[0].artist)
        composeRule.onNodeWithText("Three Day Song").assertIsDisplayed()

        // 3. 30 DAYS: 4 tracks, 4 artists; Fifteen Day Song (420s) ranks #1
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_30D).performClick()
        composeRule.waitUntilIdle {
            vm.uiState.value.selectedTimeframe == StatsTimeframe.THIRTY_DAYS &&
                vm.uiState.value.topTracks.size == 4 &&
                vm.uiState.value.topArtists.size == 4
        }
        assertEquals("Fifteen Day Song", vm.uiState.value.topTracks[0].title)
        assertEquals("Old Artist", vm.uiState.value.topArtists[0].artist)
        composeRule.onNodeWithText("Fifteen Day Song").assertIsDisplayed()

        // 4. ALL TIME: 5 tracks, 5 artists; Forty Five Day Song (600s) ranks #1
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_ALL).performClick()
        composeRule.waitUntilIdle {
            vm.uiState.value.selectedTimeframe == StatsTimeframe.ALL_TIME &&
                vm.uiState.value.topTracks.size == 5 &&
                vm.uiState.value.topArtists.size == 5
        }
        assertEquals("Forty Five Day Song", vm.uiState.value.topTracks[0].title)
        assertEquals("Ancient Artist", vm.uiState.value.topArtists[0].artist)
        composeRule.onNodeWithText("Forty Five Day Song").assertIsDisplayed()
        composeRule.onNodeWithText("Top 5 Artists").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.STATS_TOP_ARTISTS_LIST).assertIsDisplayed()

        // 5. Switch back to TODAY: filters back to 2 tracks, 2 artists
        composeRule.scrollToTag(TestTags.STATS_TIMEFRAME_CONTROL)
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_TODAY).performClick()
        composeRule.waitUntilIdle {
            vm.uiState.value.selectedTimeframe == StatsTimeframe.TODAY &&
                vm.uiState.value.topTracks.size == 2 &&
                vm.uiState.value.topArtists.size == 2
        }
        assertEquals("Deep Listen Song", vm.uiState.value.topTracks[0].title)
    }

    @Test
    fun t1_e6_settingsPermissionsAndAllowlistSearchToggle() {
        val permHelper = FakeSystemPermissionHelper(
            notificationAccessGranted = true,
            ignoringBatteryOptimizations = true,
            installedApps = listOf(
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true),
                InstalledAppInfo("com.google.android.youtube", "YouTube", isDefaultMusicApp = false)
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

        composeRule.waitUntilIdle {
            !vm.uiState.value.isLoadingApps
        }

        composeRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertTextContains("Active")
        composeRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_STATUS).assertTextContains("Unrestricted")

        // Scroll LazyColumn to search field, search for YouTube, and toggle it on
        composeRule.scrollToTag(TestTags.SETTINGS_ALLOWLIST_SEARCH)
        composeRule.onNodeWithTag(TestTags.SETTINGS_ALLOWLIST_SEARCH).performTextInput("YouTube")
        composeRule.waitForIdle()
        composeRule.scrollToTag(TestTags.settingsAllowlistSwitch("com.google.android.youtube"))
        composeRule.onNodeWithTag(TestTags.settingsAllowlistSwitch("com.google.android.youtube"))
            .performClick()

        composeRule.waitUntilIdle {
            vm.uiState.value.filteredApps.any { it.packageName == "com.google.android.youtube" && it.isAllowed }
        }
        assertTrue(vm.uiState.value.filteredApps.single { it.packageName == "com.google.android.youtube" }.isAllowed)
    }

    @Test
    fun t1_e7_settingsSafJsonAndSqliteExportTriggersSurfaceFeedback() {
        runBlocking {
            env.listeningRepository.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "SAF Export Track",
                    rawArtist = "SAF Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    artworkUri = null,
                    startedAtEpoch = 50_000L,
                    endedAtEpoch = 230_000L,
                    actualListenedMs = 180_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        }

        val jsonOutFile = File(env.tempDir, "saf_export.json")
        val sqliteOutFile = File(env.tempDir, "saf_export.db")

        val fakeSafOwner = FakeActivityResultRegistryOwner { suggestedName ->
            if (suggestedName.endsWith(".json")) {
                Uri.fromFile(jsonOutFile)
            } else {
                Uri.fromFile(sqliteOutFile)
            }
        }

        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = true)
        val vm = SettingsViewModel(
            systemPermissionHelper = permHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        composeRule.setLiquidGlassContent(activityResultRegistryOwner = fakeSafOwner) {
            SettingsRoute(viewModel = vm, systemPermissionHelper = permHelper)
        }
        composeRule.waitForIdle()

        // Scroll LazyColumn to JSON export button and trigger export
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TestTags.SETTINGS_EXPORT_JSON_BTN))
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            jsonOutFile.exists() && jsonOutFile.length() > 0L
        }
        composeRule.waitForIdle()
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TestTags.SETTINGS_EXPORT_FEEDBACK))
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()
        assertTrue(jsonOutFile.readText().contains("SAF Export Track"))

        // Trigger SQLite export
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN))
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            sqliteOutFile.exists() && sqliteOutFile.length() > 0L
        }
        composeRule.waitForIdle()
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TestTags.SETTINGS_EXPORT_FEEDBACK))
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()
        assertEquals(2, fakeSafOwner.launchedFilenames.size)
    }
}
