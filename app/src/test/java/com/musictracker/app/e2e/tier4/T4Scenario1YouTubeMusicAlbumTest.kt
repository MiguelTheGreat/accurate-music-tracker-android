package com.musictracker.app.e2e.tier4

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToText
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeRoute
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Tier 4 — Scenario 1: YouTube Music Album Listen with Scrubbing, Late Duration,
 * Accidental 1.0s Skip (Debounced), and Repeat-One Loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T4Scenario1YouTubeMusicAlbumTest {

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
    fun scenario1_youtubeMusicAlbumWithScrubbingDebounceAndRepeatOne() {
        val ytMusicPkg = "com.google.android.apps.youtube.music"
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val ytDriver = FakeMediaControllerDriver(packageName = ytMusicPkg)
        coordinator.onActiveSessionsChanged(listOf(ytDriver))

        // Track 1: Partial metadata arrives first (durationMs = 0), then DURATION patches at +800ms.
        // Mid-track scrub-back replays chorus so wall-clock (240s) > track duration (200s).
        val t1RawTitle = "Instant Crush (feat. Julian Casablancas) (Official Video)"
        val t1RawArtist = "Daft Punk - Topic"
        ytDriver.emitMetadata(MediaMetadataSnapshot(title = t1RawTitle, artist = t1RawArtist, album = "", durationMs = 0L))
        ytDriver.play(positionMs = 0L)

        env.clock.advanceBy(800L)
        ytDriver.emitMetadata(
            MediaMetadataSnapshot(
                title = t1RawTitle,
                artist = t1RawArtist,
                album = "Random Access Memories",
                durationMs = 200_000L
            )
        )
        env.clock.advanceBy(89_200L) // 90s listened
        ytDriver.play(positionMs = 90_000L)
        // Scrub back to 30s and listen another 150s -> total 240s (> 200s track duration)
        ytDriver.play(positionMs = 30_000L)
        env.clock.advanceBy(150_000L)

        // Accidental 1.0s skip on Track 1.5 -> must be discarded by 1.5s debounce
        ytDriver.emitMetadata(
            MediaMetadataSnapshot(
                title = "Accidental Skip (Official Audio)",
                artist = "Daft Punk - Topic",
                album = "Random Access Memories",
                durationMs = 180_000L
            )
        )
        ytDriver.play(positionMs = 0L)
        env.clock.advanceBy(1_000L)

        // Track 2: Normal full listen (180s)
        ytDriver.emitMetadata(
            MediaMetadataSnapshot(
                title = "Get Lucky (feat. Pharrell Williams) (Official Audio)",
                artist = "Daft Punk - Topic",
                album = "Random Access Memories",
                durationMs = 240_000L
            )
        )
        ytDriver.play(positionMs = 0L)
        env.clock.advanceBy(180_000L)

        // Track 3: Repeat-One looping twice (200s + 200s = 400s total -> #1 ranked track)
        ytDriver.emitMetadata(
            MediaMetadataSnapshot(
                title = "Touch (Official Audio)",
                artist = "Daft Punk - Topic",
                album = "Random Access Memories",
                durationMs = 200_000L
            )
        )
        ytDriver.play(positionMs = 0L)
        env.clock.advanceBy(197_000L)
        ytDriver.play(positionMs = 197_000L)
        env.clock.advanceBy(3_000L) // 200s
        // Loop back to 200ms -> finalizes listen #1 of Track 3 and starts listen #2
        ytDriver.play(positionMs = 200L)
        env.clock.advanceBy(200_000L)

        runBlocking {
            coordinator.flushAndStop().join()
            env.waitForEventCount(4)
        }

        // Assert DB: 4 persisted listen_events (not 5 — accidental 1.0s skip discarded), 3 tracks rows
        val tracks = runBlocking { env.database.trackDao().getAll() }
        val events = runBlocking { env.database.listenEventDao().getAll() }
        assertEquals("Expected exactly 3 unique tracks", 3, tracks.size)
        assertEquals("Expected 4 listen_events (accidental 1s skip discarded)", 4, events.size)

        val t1Entity = tracks.single { it.normalizedTitle == "Instant Crush (feat. Julian Casablancas)" }
        assertEquals("Daft Punk", t1Entity.normalizedArtist)
        assertEquals(t1RawTitle, t1Entity.rawTitle)
        assertEquals(t1RawArtist, t1Entity.rawArtist)
        assertEquals(200_000L, t1Entity.durationMs)

        // Expected total wall-clock: 240s + 180s + 200s + 200s = 820s (13m 40s -> "13m")
        val expectedTotalMs = 240_000L + 180_000L + 200_000L + 200_000L
        assertEquals(expectedTotalMs, events.sumOf { it.actualListenedMs })

        // Verify Stats ranks Track 3 ("Touch", 400s) #1 by actual time listened
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        statsVm.selectTimeframe(StatsTimeframe.TODAY)
        val topTracks = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.topTracks.size == 3 }.topTracks
        assertEquals("Touch", topTracks[0].title)
        assertEquals(400_000L, topTracks[0].totalListenedMs)
        assertEquals("Instant Crush (feat. Julian Casablancas)", topTracks[1].title)
        assertEquals(240_000L, topTracks[1].totalListenedMs)
        assertEquals("Get Lucky (feat. Pharrell Williams)", topTracks[2].title)
        assertEquals(180_000L, topTracks[2].totalListenedMs)

        // Verify Home UI displays exact play count (4), total time ("13m"), and recent list
        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = true)
        val homeVm = HomeViewModel(
            listeningRepository = env.listeningRepository,
            playSessionEngine = env.playSessionStateMachine,
            systemPermissionHelper = permHelper,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )

        composeRule.setLiquidGlassContent {
            HomeRoute(viewModel = homeVm, clock = env.clock, systemPermissionHelper = permHelper)
        }
        composeRule.waitUntilIdle {
            homeVm.uiState.value.todaySummary.playCount == 4 &&
                homeVm.uiState.value.recentHistory.size == 4
        }

        composeRule.onNodeWithTag(TestTags.HOME_STAT_PLAYS).assertTextContains("4")
        composeRule.onNodeWithTag(TestTags.HOME_STAT_TIME).assertTextContains("13m")
        composeRule.onNodeWithTag(TestTags.HOME_RECENT_LIST).assertIsDisplayed()
        composeRule.scrollToText("Instant Crush (feat. Julian Casablancas)")
        composeRule.onNodeWithText("Instant Crush (feat. Julian Casablancas)").assertIsDisplayed()
    }
}
