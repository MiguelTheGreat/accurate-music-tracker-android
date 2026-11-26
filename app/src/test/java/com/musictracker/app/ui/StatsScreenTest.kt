package com.musictracker.app.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.stats.StatsScreenContent
import com.musictracker.app.ui.screens.stats.StatsUiState
import com.musictracker.app.ui.theme.MusicTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun timeframeSegmentedControlSwitchesAllFourTimeframes() {
        val selected = mutableListOf<StatsTimeframe>()

        composeTestRule.setContent {
            MusicTrackerTheme {
                StatsScreenContent(
                    uiState = StatsUiState(
                        selectedTimeframe = StatsTimeframe.TODAY,
                        isLoading = false
                    ),
                    onSelectTimeframe = { selected += it }
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_CONTROL).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_7D).performClick()
        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_30D).performClick()
        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_ALL).performClick()
        composeTestRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_TODAY).performClick()

        assertEquals(
            listOf(
                StatsTimeframe.SEVEN_DAYS,
                StatsTimeframe.THIRTY_DAYS,
                StatsTimeframe.ALL_TIME,
                StatsTimeframe.TODAY
            ),
            selected
        )
    }

    @Test
    fun rendersTopTracksAndTopArtistsRankedByActualTimeListened() {
        composeTestRule.setContent {
            MusicTrackerTheme {
                StatsScreenContent(
                    uiState = StatsUiState(
                        selectedTimeframe = StatsTimeframe.SEVEN_DAYS,
                        summary = ListeningSummary(
                            totalListenedMs = 7_200_000L,
                            playCount = 24,
                            skippedCount = 2,
                            skipRate = 0.083f
                        ),
                        topTracks = listOf(
                            TopTrackStat(10L, "Pyramids", "Frank Ocean", "Channel Orange", null, 3_600_000L, 6),
                            TopTrackStat(20L, "Lost", "Frank Ocean", "Channel Orange", null, 1_800_000L, 9)
                        ),
                        topArtists = listOf(
                            TopArtistStat("Frank Ocean", 5_400_000L, 15, 2)
                        ),
                        isLoading = false
                    ),
                    onSelectTimeframe = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.STATS_TOP_TRACKS_LIST).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.statsTrackItem(10L)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pyramids").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.statsTrackItem(20L)).assertExists()

        composeTestRule.onNodeWithText("Top 5 Artists").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.STATS_TOP_ARTISTS_LIST).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.statsArtistItem(0)).assertIsDisplayed()
        composeTestRule.onNodeWithText("15 plays · 2 tracks").assertIsDisplayed()
    }

    @Test
    fun rendersEmptyStateWhenTimeframeHasNoData() {
        composeTestRule.setContent {
            MusicTrackerTheme {
                StatsScreenContent(
                    uiState = StatsUiState(
                        selectedTimeframe = StatsTimeframe.THIRTY_DAYS,
                        isLoading = false
                    ),
                    onSelectTimeframe = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No Data for Last 30 Days").assertIsDisplayed()
    }
}
