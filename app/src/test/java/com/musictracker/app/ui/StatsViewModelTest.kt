package com.musictracker.app.ui

import app.cash.turbine.test
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `selectTimeframe switches reactive queries with exact TrackerClock cutoffs`() = runTest {
        val nowMs = Instant.parse("2025-03-15T12:00:00Z").toEpochMilli()
        val todayCutoff = Instant.parse("2025-03-15T00:00:00Z").toEpochMilli()
        val sevenDaysCutoff = nowMs - 7L * 86_400_000L
        val thirtyDaysCutoff = nowMs - 30L * 86_400_000L

        val repo = FakeListeningRepository()
        val trackToday = TopTrackStat(1L, "Today Song", "Artist A", "Alb", null, 300_000L, 2)
        val trackAll = TopTrackStat(2L, "All-Time Anthem", "Artist B", "Alb", null, 9_600_000L, 40)
        val artistAll = TopArtistStat("Artist B", 9_600_000L, 40, 5)

        repo.topTracksByCutoff[todayCutoff] = MutableStateFlow(listOf(trackToday))
        repo.topTracksByCutoff[0L] = MutableStateFlow(listOf(trackAll))
        repo.topArtistsByCutoff[0L] = MutableStateFlow(listOf(artistAll))
        repo.summaryByCutoff[0L] = MutableStateFlow(
            ListeningSummary(totalListenedMs = 9_600_000L, playCount = 40, skippedCount = 4, skipRate = 0.10f)
        )

        val clock = FakeTrackerClock(epochMs = nowMs)
        val viewModel = StatsViewModel(repo, clock, ZoneOffset.UTC)

        viewModel.uiState.test {
            val todayState = expectMostRecentItem()
            assertEquals(StatsTimeframe.TODAY, todayState.selectedTimeframe)
            assertEquals(todayCutoff, repo.lastTopTracksSinceEpochMs)
            assertEquals(listOf(trackToday), todayState.topTracks)

            viewModel.selectTimeframe(StatsTimeframe.SEVEN_DAYS)
            val sevenDayState = expectMostRecentItem()
            assertEquals(StatsTimeframe.SEVEN_DAYS, sevenDayState.selectedTimeframe)
            assertEquals(sevenDaysCutoff, repo.lastTopTracksSinceEpochMs)
            assertEquals(sevenDaysCutoff, repo.lastTopArtistsSinceEpochMs)

            viewModel.selectTimeframe(StatsTimeframe.THIRTY_DAYS)
            val thirtyDayState = expectMostRecentItem()
            assertEquals(StatsTimeframe.THIRTY_DAYS, thirtyDayState.selectedTimeframe)
            assertEquals(thirtyDaysCutoff, repo.lastTopTracksSinceEpochMs)

            viewModel.selectTimeframe(StatsTimeframe.ALL_TIME)
            val allTimeState = expectMostRecentItem()
            assertEquals(StatsTimeframe.ALL_TIME, allTimeState.selectedTimeframe)
            assertEquals(0L, repo.lastTopTracksSinceEpochMs)
            assertEquals(listOf(trackAll), allTimeState.topTracks)
            assertEquals(listOf(artistAll), allTimeState.topArtists)
            assertEquals(9_600_000L, allTimeState.summary.totalListenedMs)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
