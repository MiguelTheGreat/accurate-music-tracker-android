package com.musictracker.app.ui

import app.cash.turbine.test
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.screens.settings.ExportFormat
import com.musictracker.app.ui.screens.settings.ExportUiState
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads allowlist apps, pins default music apps, filters case-insensitively, and toggles`() = runTest {
        val permissions = FakeSystemPermissionHelper(
            notificationAccessGranted = false,
            ignoringBatteryOptimizations = false,
            installedApps = listOf(
                InstalledAppInfo("com.browser.app", "Chrome Browser", isDefaultMusicApp = false),
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true),
                InstalledAppInfo("com.google.android.apps.youtube.music", "YouTube Music", isDefaultMusicApp = true)
            )
        )
        val allowlist = FakeAllowlistRepository(setOf("com.google.android.apps.youtube.music"))
        val reduceTransparencyFlow = MutableStateFlow(false)
        val exporter = FakeDataExporter()

        val viewModel = SettingsViewModel(
            systemPermissionHelper = permissions,
            allowlistRepository = allowlist,
            reduceTransparencyFlow = reduceTransparencyFlow,
            setReduceTransparency = { reduceTransparencyFlow.value = it },
            dataExporter = exporter,
            ioDispatcher = mainDispatcherRule.dispatcher
        )

        viewModel.uiState.test {
            val loaded = expectMostRecentItem()
            assertFalse(loaded.isLoadingApps)
            // Default music apps pinned first, allowed before non-allowed
            assertEquals(
                listOf(
                    "com.google.android.apps.youtube.music",
                    "com.spotify.music",
                    "com.browser.app"
                ),
                loaded.filteredApps.map { it.packageName }
            )

            // Case-insensitive search by package name
            viewModel.onSearchQueryChange("SPOTIFY")
            val filtered = expectMostRecentItem()
            assertEquals(listOf("com.spotify.music"), filtered.filteredApps.map { it.packageName })

            // Toggle app allowed
            viewModel.onToggleAppAllowed("com.spotify.music", true)
            val toggled = expectMostRecentItem()
            assertTrue(toggled.filteredApps.first().isAllowed)
            assertEquals(listOf("com.spotify.music" to true), allowlist.toggleHistory)

            // Toggle Reduce Transparency
            viewModel.onToggleReduceTransparency(true)
            assertTrue(expectMostRecentItem().reduceTransparencyEnabled)

            // Refresh system status
            permissions.notificationAccessGranted = true
            permissions.ignoringBatteryOptimizations = true
            viewModel.refreshSystemStatus()
            val refreshed = expectMostRecentItem()
            assertTrue(refreshed.isNotificationAccessGranted)
            assertTrue(refreshed.isIgnoringBatteryOptimizations)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportJson and exportSqliteBackup manage InProgress, Success, Error, null stream, and duplicate guard`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val exporter = FakeDataExporter(gate = gate)
        val viewModel = SettingsViewModel(
            systemPermissionHelper = FakeSystemPermissionHelper(),
            allowlistRepository = FakeAllowlistRepository(),
            reduceTransparencyFlow = MutableStateFlow(false),
            setReduceTransparency = {},
            dataExporter = exporter,
            ioDispatcher = mainDispatcherRule.dispatcher
        )

        viewModel.uiState.test {
            assertEquals(ExportUiState.Idle, expectMostRecentItem().exportState)

            val outputStream = ByteArrayOutputStream()
            viewModel.exportJson { outputStream }

            // Duplicate call while InProgress must be ignored
            viewModel.exportSqliteBackup { ByteArrayOutputStream() }
            assertEquals(ExportUiState.InProgress(ExportFormat.JSON), expectMostRecentItem().exportState)
            assertEquals(1, exporter.jsonExportCalls)
            assertEquals(0, exporter.sqliteExportCalls)

            // Complete export
            gate.complete(Unit)
            val successState = expectMostRecentItem().exportState as ExportUiState.Success
            assertEquals(ExportFormat.JSON, successState.format)
            assertTrue(successState.message.contains("5 tracks"))
            assertTrue(outputStream.size() > 0)

            // Clear feedback
            viewModel.clearExportFeedback()
            assertEquals(ExportUiState.Idle, expectMostRecentItem().exportState)

            // Null stream yields Error cleanly
            exporter.gate = null
            viewModel.exportSqliteBackup { null }
            val nullStreamError = expectMostRecentItem().exportState as ExportUiState.Error
            assertEquals(ExportFormat.SQLITE, nullStreamError.format)
            assertTrue(nullStreamError.message.contains("Unable to open"))

            // Exporter error propagates
            exporter.sqliteResult = ExportResult.Error("Disk full")
            viewModel.exportSqliteBackup { ByteArrayOutputStream() }
            val diskError = expectMostRecentItem().exportState as ExportUiState.Error
            assertEquals("Disk full", diskError.message)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
