package com.musictracker.app.e2e.tier3

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeRoute
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 3 — T3-2: AllowlistStore + MediaSessionCoordinator + PlaySessionStateMachine +
 * HomeViewModel + HomeScreen + SettingsScreen live end-to-end flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T3AllowlistToLiveUiE2eTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment
    private lateinit var coordinatorScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context)
        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
        env.close()
    }

    @Test
    fun t3_2_disablingSpotifyInSettingsFinalizesSessionAndUpdatesHomeUi() {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val permHelper = FakeSystemPermissionHelper(
            notificationAccessGranted = true,
            ignoringBatteryOptimizations = true,
            installedApps = listOf(
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true)
            )
        )

        val homeVm = HomeViewModel(
            listeningRepository = env.listeningRepository,
            playSessionEngine = env.playSessionStateMachine,
            systemPermissionHelper = permHelper,
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

        composeRule.setLiquidGlassContent {
            MusicTrackerNavHost(
                homeContent = { HomeRoute(viewModel = homeVm, clock = env.clock, systemPermissionHelper = permHelper) },
                statsContent = {},
                settingsContent = { SettingsRoute(viewModel = settingsVm, systemPermissionHelper = permHelper) }
            )
        }
        composeRule.waitForIdle()

        // Start playback on Spotify and wait until coordinator has observed the allowlist and registered it
        val spotify = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            initialMetadata = MediaMetadataSnapshot(title = "Time", artist = "Pink Floyd", durationMs = 413_000L),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        coordinator.onActiveSessionsChanged(listOf(spotify))
        composeRule.waitUntilIdle {
            spotify.isRegistered && homeVm.uiState.value.nowTracking?.normalizedTitle == "Time"
        }

        // Now advance wall-clock 15s and tick the Compose UI clock so live counter updates to 00:15
        env.clock.advanceBy(15_000L)
        composeRule.mainClock.advanceTimeBy(1_100L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Time").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:15")

        // Navigate to Settings and disable Spotify in the allowlist
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.waitUntilIdle { !settingsVm.uiState.value.isLoadingApps }

        val spotifySwitchTag = TestTags.settingsAllowlistSwitch("com.spotify.music")
        composeRule.scrollToTag(spotifySwitchTag)
        composeRule.onNodeWithTag(spotifySwitchTag).performClick()

        // Coordinator detaches Spotify and finalizes the 15s session into Room
        composeRule.waitUntilIdle {
            coordinator.nowTracking.value == null &&
                homeVm.uiState.value.recentHistory.any { it.title == "Time" }
        }

        // Navigate back to Home — Now Tracking is idle and Recent History shows the finalized Spotify listen
        composeRule.onNodeWithTag(TestTags.NAV_TAB_HOME).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nothing Playing").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.HOME_RECENT_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("Time").assertIsDisplayed()
    }
}
