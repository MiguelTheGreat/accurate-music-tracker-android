package com.musictracker.app.ui

import app.cash.turbine.test
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.ui.screens.home.HomeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `propagates reactive nowTracking, todaySummary, recentHistory and permission banner`() = runTest {
        val repo = FakeListeningRepository()
        val engine = FakePlaySessionEngine()
        val permissions = FakeSystemPermissionHelper(notificationAccessGranted = false)
        val clock = FakeTrackerClock(
            elapsedMs = 20_000L,
            epochMs = Instant.parse("2025-02-10T14:30:00Z").toEpochMilli()
        )

        val viewModel = HomeViewModel(
            listeningRepository = repo,
            playSessionEngine = engine,
            systemPermissionHelper = permissions,
            clock = clock,
            zoneId = ZoneOffset.UTC
        )

        viewModel.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isNotificationAccessGranted)
            assertTrue(initial.showPermissionBanner)
            assertEquals(
                Instant.parse("2025-02-10T00:00:00Z").toEpochMilli(),
                repo.lastSummarySinceEpochMs
            )

            // Emit active track, summary, and recent history
            val active = NowTrackingState(
                sourcePackage = "com.google.android.apps.youtube.music",
                rawTitle = "Nights (Official Audio)",
                rawArtist = "Frank Ocean - Topic",
                normalizedTitle = "Nights",
                normalizedArtist = "Frank Ocean",
                album = "Blonde",
                durationMs = 307_000L,
                artworkUri = null,
                isPlaying = true,
                accumulatedMs = 15_000L,
                segmentStartElapsedMs = 20_000L,
                isDebouncing = false
            )
            engine.stateFlow.value = active
            repo.defaultSummaryFlow.value = ListeningSummary(
                totalListenedMs = 5_400_000L,
                playCount = 12,
                skippedCount = 3,
                skipRate = 0.25f
            )
            repo.recentHistoryFlow.value = listOf(
                RecentListenItem(
                    eventId = 42L,
                    trackId = 7L,
                    title = "Nights",
                    artist = "Frank Ocean",
                    album = "Blonde",
                    artworkUri = null,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    startedAtEpoch = clock.epochMs - 300_000L,
                    endedAtEpoch = clock.epochMs - 10_000L,
                    actualListenedMs = 290_000L,
                    completedRatio = 0.94f,
                    wasSkipped = false
                )
            )

            val updated = expectMostRecentItem()
            assertNotNull(updated.nowTracking)
            assertEquals("Nights", updated.nowTracking?.normalizedTitle)
            assertEquals(12, updated.todaySummary.playCount)
            assertEquals(1, updated.recentHistory.size)
            assertEquals(42L, updated.recentHistory.first().eventId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissPermissionBanner hides banner without altering permission flag`() = runTest {
        val repo = FakeListeningRepository()
        val engine = FakePlaySessionEngine()
        val permissions = FakeSystemPermissionHelper(notificationAccessGranted = false)
        val clock = FakeTrackerClock()

        val viewModel = HomeViewModel(repo, engine, permissions, clock, ZoneOffset.UTC)

        viewModel.uiState.test {
            val first = awaitItem()
            assertTrue(first.showPermissionBanner)

            viewModel.dismissPermissionBanner()
            val afterDismiss = expectMostRecentItem()
            assertFalse(afterDismiss.isNotificationAccessGranted)
            assertTrue(afterDismiss.isPermissionBannerDismissed)
            assertFalse(afterDismiss.showPermissionBanner)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshPermissionAndDay updates permission status and rolls over midnight cutoff`() = runTest {
        val repo = FakeListeningRepository()
        val engine = FakePlaySessionEngine()
        val permissions = FakeSystemPermissionHelper(notificationAccessGranted = false)
        val clock = FakeTrackerClock(
            epochMs = Instant.parse("2025-02-10T23:59:00Z").toEpochMilli()
        )

        val viewModel = HomeViewModel(repo, engine, permissions, clock, ZoneOffset.UTC)

        viewModel.uiState.test {
            awaitItem()
            assertEquals(
                Instant.parse("2025-02-10T00:00:00Z").toEpochMilli(),
                repo.lastSummarySinceEpochMs
            )

            // User grants permission & clock crosses midnight
            permissions.notificationAccessGranted = true
            clock.epochMs = Instant.parse("2025-02-11T00:05:00Z").toEpochMilli()
            viewModel.refreshPermissionAndDay()

            val refreshed = expectMostRecentItem()
            assertTrue(refreshed.isNotificationAccessGranted)
            assertFalse(refreshed.showPermissionBanner)
            assertEquals(
                Instant.parse("2025-02-11T00:00:00Z").toEpochMilli(),
                repo.lastSummarySinceEpochMs
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
