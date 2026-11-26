package com.musictracker.app.ui.screens.settings

import androidx.compose.runtime.Immutable
import com.musictracker.app.data.preferences.ThemeMode

@Immutable
data class AllowlistAppItem(
    val packageName: String,
    val appName: String,
    val isAllowed: Boolean,
    val isDefaultMusicApp: Boolean
)

enum class ExportFormat {
    JSON,
    SQLITE
}

@Immutable
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class InProgress(val format: ExportFormat) : ExportUiState
    data class Success(val format: ExportFormat, val message: String) : ExportUiState
    data class Error(val format: ExportFormat, val message: String) : ExportUiState
}

@Immutable
data class SettingsUiState(
    val isNotificationAccessGranted: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val searchQuery: String = "",
    val filteredApps: List<AllowlistAppItem> = emptyList(),
    val isLoadingApps: Boolean = true,
    val exportState: ExportUiState = ExportUiState.Idle,
    val reduceTransparencyEnabled: Boolean = false,
    val onlineArtworkEnabled: Boolean = true,
    val isCleaningLibrary: Boolean = false,
    val libraryCleanupFeedback: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
) {
    val isExporting: Boolean
        get() = exportState is ExportUiState.InProgress
}
