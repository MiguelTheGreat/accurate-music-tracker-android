package com.musictracker.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.theme.MusicTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsPermissionBannerWhenMissingAndInvokesEnableCallback() {
        var enableClicks = 0
        val clock = FakeTrackerClock()

        composeTestRule.setContent {
            MusicTrackerTheme {
                HomeScreenContent(
                    uiState = HomeUiState(
                        isNotificationAccessGranted = false,
                        isPermissionBannerDismissed = false,
                        isLoading = false
                    ),
                    clock = clock,
                    onEnableNotificationAccess = { enableClicks++ },
                    onDismissPermissionBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_PERMISSION_ENABLE_BTN).performClick()
        assertEquals(1, enableClicks)
    }

    @Test
    fun liveCounterTicksAt1HzWhenPlayingAndFreezesWhenPaused() {
        val clock = FakeTrackerClock(elapsedMs = 100_000L)
        val playingState = NowTrackingState(
            sourcePackage = "com.google.android.apps.youtube.music",
            rawTitle = "Pink + White",
            rawArtist = "Frank Ocean",
            normalizedTitle = "Pink + White",
            normalizedArtist = "Frank Ocean",
            album = "Blonde",
            durationMs = 184_000L,
            artworkUri = null,
            isPlaying = true,
            accumulatedMs = 10_000L,
            segmentStartElapsedMs = 100_000L,
            isDebouncing = false
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MusicTrackerTheme {
                HomeScreenContent(
                    uiState = HomeUiState(
                        isNotificationAccessGranted = true,
                        nowTracking = playingState,
                        isLoading = false
                    ),
                    clock = clock,
                    onEnableNotificationAccess = {},
                    onDismissPermissionBanner = {}
                )
            }
        }

        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pink + White").assertIsDisplayed()
        composeTestRule.onNodeWithText("YouTube Music").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:10")

        // Advance both TrackerClock and Compose mainClock by 1 second
        clock.elapsedMs += 1_000L
        composeTestRule.mainClock.advanceTimeBy(1_000L)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:11")
    }

    @Test
    fun rendersPausedCounterFrozenSummaryBentoAndRecentListWithSkippedBadge() {
        val clock = FakeTrackerClock(elapsedMs = 50_000L, epochMs = 1_735_689_600_000L)
        val pausedState = NowTrackingState(
            sourcePackage = "com.spotify.music",
            rawTitle = "Nikes",
            rawArtist = "Frank Ocean",
            normalizedTitle = "Nikes",
            normalizedArtist = "Frank Ocean",
            album = "Blonde",
            durationMs = 314_000L,
            artworkUri = null,
            isPlaying = false,
            accumulatedMs = 45_000L,
            segmentStartElapsedMs = null,
            isDebouncing = false
        )

        composeTestRule.setContent {
            MusicTrackerTheme {
                HomeScreenContent(
                    uiState = HomeUiState(
                        isNotificationAccessGranted = true,
                        nowTracking = pausedState,
                        todaySummary = ListeningSummary(
                            totalListenedMs = 5_040_000L, // 1h 24m
                            playCount = 10,
                            skippedCount = 2,
                            skipRate = 0.20f
                        ),
                        recentHistory = listOf(
                            RecentListenItem(
                                eventId = 101L,
                                trackId = 1L,
                                title = "Self Control",
                                artist = "Frank Ocean",
                                album = "Blonde",
                                artworkUri = null,
                                sourcePackage = "com.spotify.music",
                                startedAtEpoch = clock.epochMs - 180_000L,
                                endedAtEpoch = clock.epochMs - 60_000L,
                                actualListenedMs = 20_000L,
                                completedRatio = 0.1f,
                                wasSkipped = true
                            )
                        ),
                        isLoading = false
                    ),
                    clock = clock,
                    onEnableNotificationAccess = {},
                    onDismissPermissionBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.HOME_PERMISSION_BANNER).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertTextContains("00:45")
        composeTestRule.onNodeWithTag(TestTags.HOME_STAT_TIME).assertTextContains("1h 24m")
        composeTestRule.onNodeWithTag(TestTags.HOME_STAT_PLAYS).assertTextContains("10")
        composeTestRule.onNodeWithTag(TestTags.HOME_STAT_SKIP_RATE).assertTextContains("20%")
        composeTestRule.onNodeWithTag(TestTags.HOME_RECENT_LIST)
            .performScrollToNode(hasTestTag(TestTags.homeRecentItem(101L)))
        composeTestRule.onNodeWithTag(TestTags.homeRecentItem(101L)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Skipped").assertIsDisplayed()
    }

    @Test
    fun rendersDashForSkipRateWhenNoPlaysLoggedToday() {
        composeTestRule.setContent {
            MusicTrackerTheme {
                HomeScreenContent(
                    uiState = HomeUiState(
                        isNotificationAccessGranted = true,
                        todaySummary = ListeningSummary(0L, 0, 0, 0f),
                        isLoading = false
                    ),
                    clock = FakeTrackerClock(),
                    onEnableNotificationAccess = {},
                    onDismissPermissionBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Nothing Playing").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_STAT_SKIP_RATE).assertTextContains("—")
    }
}
