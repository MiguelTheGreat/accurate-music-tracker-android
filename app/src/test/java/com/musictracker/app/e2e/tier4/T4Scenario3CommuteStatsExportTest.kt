package com.musictracker.app.e2e.tier4

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeActivityResultRegistryOwner
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsRoute
import com.musictracker.app.ui.screens.stats.StatsViewModel
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
 * Tier 4 — Scenario 3: Multi-Week Commute, Skips vs Deep-Listens Ranking,
 * Stats Timeframe Comparison & JSON + SQLite SAF Export Round-Trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T4Scenario3CommuteStatsExportTest {

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
    fun scenario3_commuteTimeframesDeepListenRankingAndDualExportRoundTrip() {
        val now = env.clock.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1_000L

        runBlocking {
            // 40 days ago (only in ALL_TIME)
            env.listeningRepository.recordCompletedSession(
                makeSession("Archive Symphony", "Old Maestro", now - 40 * dayMs, 300_000L, skipped = false)
            )
            // 15 days ago (in 30D + ALL_TIME)
            env.listeningRepository.recordCompletedSession(
                makeSession("Two Week Anthem", "Mid Artist", now - 15 * dayMs, 240_000L, skipped = false)
            )
            // 3 days ago (in 7D + 30D + ALL_TIME)
            env.listeningRepository.recordCompletedSession(
                makeSession("Commute Jam", "Road Artist", now - 3 * dayMs, 210_000L, skipped = false)
            )
            // Today: 1 deep listen (8 minutes = 480s, 1 play)
            env.listeningRepository.recordCompletedSession(
                makeSession("Deep Focus Opus", "Deep Artist", now - 600_000L, 480_000L, skipped = false)
            )
            // Today: 5 short skips (20s each = 100s total, 5 plays)
            repeat(5) { idx ->
                env.listeningRepository.recordCompletedSession(
                    makeSession(
                        "Skipped Pop Single",
                        "Pop Artist",
                        now - 100_000L + idx * 10_000L,
                        20_000L,
                        skipped = true
                    )
                )
            }
        }

        val jsonExportFile = File(env.tempDir, "commute_export.json")
        val sqliteExportFile = File(env.tempDir, "commute_export.db")
        val fakeSafOwner = FakeActivityResultRegistryOwner { suggested ->
            if (suggested.endsWith(".json")) Uri.fromFile(jsonExportFile) else Uri.fromFile(sqliteExportFile)
        }

        val permHelper = FakeSystemPermissionHelper(notificationAccessGranted = true)
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
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

        composeRule.setLiquidGlassContent(activityResultRegistryOwner = fakeSafOwner) {
            MusicTrackerNavHost(
                homeContent = {},
                statsContent = { StatsRoute(viewModel = statsVm) },
                settingsContent = { SettingsRoute(viewModel = settingsVm, systemPermissionHelper = permHelper) }
            )
        }

        // Navigate to Stats and verify all 4 timeframes
        composeRule.onNodeWithTag(TestTags.NAV_TAB_STATS).performClick()
        composeRule.waitUntilIdle {
            statsVm.uiState.value.selectedTimeframe == StatsTimeframe.TODAY &&
                statsVm.uiState.value.topTracks.size == 2
        }

        // Today: 8-minute single-play ranks #1 ABOVE 5-play skipped track (1m40s)
        val todayTracks = statsVm.uiState.value.topTracks
        assertEquals("Deep Focus Opus", todayTracks[0].title)
        assertEquals(480_000L, todayTracks[0].totalListenedMs)
        assertEquals("Skipped Pop Single", todayTracks[1].title)
        assertEquals(100_000L, todayTracks[1].totalListenedMs)

        // 7 Days: includes Today + 3 days ago (3 unique tracks)
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_7D).performClick()
        composeRule.waitUntilIdle {
            statsVm.uiState.value.selectedTimeframe == StatsTimeframe.SEVEN_DAYS &&
                statsVm.uiState.value.topTracks.size == 3
        }
        assertEquals("Deep Focus Opus", statsVm.uiState.value.topTracks[0].title)

        // 30 Days: includes 15 days ago (4 unique tracks)
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_30D).performClick()
        composeRule.waitUntilIdle {
            statsVm.uiState.value.selectedTimeframe == StatsTimeframe.THIRTY_DAYS &&
                statsVm.uiState.value.topTracks.size == 4
        }

        // All Time: includes 40 days ago (5 unique tracks, 9 total listen events)
        composeRule.onNodeWithTag(TestTags.STATS_TIMEFRAME_ALL).performClick()
        composeRule.waitUntilIdle {
            statsVm.uiState.value.selectedTimeframe == StatsTimeframe.ALL_TIME &&
                statsVm.uiState.value.topTracks.size == 5
        }
        assertEquals(9, statsVm.uiState.value.summary.playCount)

        // Navigate to Settings and trigger both JSON and SQLite SAF exports
        composeRule.onNodeWithTag(TestTags.NAV_TAB_SETTINGS).performClick()
        composeRule.waitForIdle()

        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_JSON_BTN)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeRule.waitUntilIdle { jsonExportFile.exists() && jsonExportFile.length() > 0L }
        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_FEEDBACK)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()

        composeRule.scrollToTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN)
        composeRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN).performClick()
        composeRule.waitUntilIdle { sqliteExportFile.exists() && sqliteExportFile.length() > 0L }

        // Verify JSON export has 100% row-for-row fidelity (5 tracks, 9 listen_events)
        val payload = createExportJson().decodeFromString<MusicTrackerExportPayload>(jsonExportFile.readText())
        assertEquals(5, payload.tracks.size)
        assertEquals(9, payload.listenEvents.size)
        assertTrue(payload.tracks.any { it.normalizedTitle == "Deep Focus Opus" })
        assertTrue(payload.tracks.any { it.normalizedTitle == "Archive Symphony" })

        // Verify SQLite backup opens cleanly and matches row-for-row (5 tracks, 9 listen_events)
        val sqlite = SQLiteDatabase.openDatabase(sqliteExportFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val dbTrackCount = sqlite.rawQuery("SELECT COUNT(*) FROM tracks", null).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            val dbEventCount = sqlite.rawQuery("SELECT COUNT(*) FROM listen_events", null).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            assertEquals(5, dbTrackCount)
            assertEquals(9, dbEventCount)
        } finally {
            sqlite.close()
        }
    }

    private fun makeSession(
        title: String,
        artist: String,
        startedAt: Long,
        listenedMs: Long,
        skipped: Boolean
    ) = CompletedListenSession(
        sourcePackage = "com.spotify.music",
        rawTitle = title,
        rawArtist = artist,
        album = "Commute Album",
        durationMs = 480_000L,
        artworkUri = null,
        startedAtEpoch = startedAt,
        endedAtEpoch = startedAt + listenedMs,
        actualListenedMs = listenedMs,
        completedRatio = listenedMs.toFloat() / 480_000f,
        wasSkipped = skipped
    )
}
