package com.musictracker.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musictracker.app.data.export.DataExporter
import com.musictracker.app.data.preferences.AccessibilityPreferencesStore
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.ArtworkPreferencesStore
import com.musictracker.app.data.preferences.ThemeMode
import com.musictracker.app.data.preferences.ThemePreferencesStore
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.domain.model.LibraryCleanupResult
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.tracking.SystemPermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class SettingsViewModel(
    private val systemPermissionHelper: SystemPermissionHelper,
    private val allowlistRepository: AllowlistRepository,
    private val reduceTransparencyFlow: Flow<Boolean>,
    private val setReduceTransparency: suspend (Boolean) -> Unit,
    private val dataExporter: DataExporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onlineArtworkEnabledFlow: Flow<Boolean> = flowOf(true),
    private val setOnlineArtworkEnabled: suspend (Boolean) -> Unit = {},
    private val runLibraryCleanup: suspend () -> LibraryCleanupResult = { LibraryCleanupResult() },
    private val themeModeFlow: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM),
    private val setThemeMode: suspend (ThemeMode) -> Unit = {},
    private val onLibraryCleaned: () -> Unit = {}
) : ViewModel() {

    constructor(
        systemPermissionHelper: SystemPermissionHelper,
        allowlistRepository: AllowlistRepository,
        accessibilityPreferencesStore: AccessibilityPreferencesStore,
        dataExporter: DataExporter,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        artworkPreferencesStore: ArtworkPreferencesStore? = null,
        listeningRepository: ListeningRepository? = null,
        themePreferencesStore: ThemePreferencesStore? = null,
        onLibraryCleaned: () -> Unit = {}
    ) : this(
        systemPermissionHelper = systemPermissionHelper,
        allowlistRepository = allowlistRepository,
        reduceTransparencyFlow = accessibilityPreferencesStore.reduceTransparencyEnabled,
        setReduceTransparency = { enabled ->
            accessibilityPreferencesStore.setReduceTransparencyEnabled(enabled)
        },
        dataExporter = dataExporter,
        ioDispatcher = ioDispatcher,
        onlineArtworkEnabledFlow = artworkPreferencesStore?.onlineArtworkEnabled ?: flowOf(true),
        setOnlineArtworkEnabled = { enabled ->
            artworkPreferencesStore?.setOnlineArtworkEnabled(enabled)
        },
        runLibraryCleanup = {
            listeningRepository?.cleanAndRenormalizeLibrary() ?: LibraryCleanupResult()
        },
        themeModeFlow = themePreferencesStore?.themeMode ?: flowOf(ThemeMode.SYSTEM),
        setThemeMode = { mode ->
            themePreferencesStore?.setThemeMode(mode)
        },
        onLibraryCleaned = onLibraryCleaned
    )

    private val isNotificationAccessGrantedFlow = MutableStateFlow(
        systemPermissionHelper.isNotificationAccessGranted()
    )
    private val isIgnoringBatteryOptimizationsFlow = MutableStateFlow(
        systemPermissionHelper.isIgnoringBatteryOptimizations()
    )
    private val searchQueryFlow = MutableStateFlow("")
    private val installedAppsFlow = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val isLoadingAppsFlow = MutableStateFlow(true)
    private val exportStateFlow = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    private val isCleaningLibraryFlow = MutableStateFlow(false)
    private val libraryCleanupFeedbackFlow = MutableStateFlow<String?>(null)

    private val filteredAppsFlow = combine(
        installedAppsFlow,
        allowlistRepository.allowedPackages,
        searchQueryFlow
    ) { installedApps, allowedPackages, query ->
        val trimmedQuery = query.trim()
        installedApps
            .map { info ->
                AllowlistAppItem(
                    packageName = info.packageName,
                    appName = info.appName,
                    isAllowed = info.packageName in allowedPackages,
                    isDefaultMusicApp = info.isDefaultMusicApp
                )
            }
            .filter { item ->
                trimmedQuery.isEmpty() ||
                    item.appName.contains(trimmedQuery, ignoreCase = true) ||
                    item.packageName.contains(trimmedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<AllowlistAppItem> { it.isDefaultMusicApp }
                    .thenByDescending { it.isAllowed }
                    .thenBy { it.appName.lowercase() }
                    .thenBy { it.packageName }
            )
    }

    private data class SystemStatusSnapshot(
        val hasNotifAccess: Boolean,
        val isIgnoringBattery: Boolean,
        val searchQuery: String
    )

    private data class PreferenceTogglesSnapshot(
        val reduceTransparency: Boolean,
        val onlineArtworkEnabled: Boolean,
        val isCleaningLibrary: Boolean,
        val libraryCleanupFeedback: String?,
        val themeMode: ThemeMode
    )

    private val systemStatusFlow = combine(
        isNotificationAccessGrantedFlow,
        isIgnoringBatteryOptimizationsFlow,
        searchQueryFlow
    ) { hasNotif, isIgnoringBattery, query ->
        SystemStatusSnapshot(hasNotif, isIgnoringBattery, query)
    }

    private val preferenceTogglesFlow = combine(
        reduceTransparencyFlow,
        onlineArtworkEnabledFlow,
        isCleaningLibraryFlow,
        libraryCleanupFeedbackFlow,
        themeModeFlow
    ) { reduceTransparency, onlineArtwork, isCleaningLibrary, cleanupFeedback, themeMode ->
        PreferenceTogglesSnapshot(
            reduceTransparency = reduceTransparency,
            onlineArtworkEnabled = onlineArtwork,
            isCleaningLibrary = isCleaningLibrary,
            libraryCleanupFeedback = cleanupFeedback,
            themeMode = themeMode
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        systemStatusFlow,
        filteredAppsFlow,
        isLoadingAppsFlow,
        exportStateFlow,
        preferenceTogglesFlow
    ) { status, filteredApps, isLoadingApps, exportState, toggles ->
        SettingsUiState(
            isNotificationAccessGranted = status.hasNotifAccess,
            isIgnoringBatteryOptimizations = status.isIgnoringBattery,
            searchQuery = status.searchQuery,
            filteredApps = filteredApps,
            isLoadingApps = isLoadingApps,
            exportState = exportState,
            reduceTransparencyEnabled = toggles.reduceTransparency,
            onlineArtworkEnabled = toggles.onlineArtworkEnabled,
            isCleaningLibrary = toggles.isCleaningLibrary,
            libraryCleanupFeedback = toggles.libraryCleanupFeedback,
            themeMode = toggles.themeMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(
            isNotificationAccessGranted = isNotificationAccessGrantedFlow.value,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizationsFlow.value,
            isLoadingApps = true
        )
    )

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            isLoadingAppsFlow.value = true
            val apps = withContext(ioDispatcher) {
                runCatching { systemPermissionHelper.getInstalledLaunchableApps() }
                    .getOrDefault(emptyList())
            }
            installedAppsFlow.value = apps
            isLoadingAppsFlow.value = false
        }
    }

    fun refreshSystemStatus() {
        isNotificationAccessGrantedFlow.value = systemPermissionHelper.isNotificationAccessGranted()
        isIgnoringBatteryOptimizationsFlow.value = systemPermissionHelper.isIgnoringBatteryOptimizations()
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun onToggleAppAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch {
            allowlistRepository.setPackageAllowed(packageName, allowed)
        }
    }

    fun onToggleReduceTransparency(enabled: Boolean) {
        viewModelScope.launch {
            setReduceTransparency(enabled)
        }
    }

    fun onToggleOnlineArtwork(enabled: Boolean) {
        viewModelScope.launch {
            setOnlineArtworkEnabled(enabled)
        }
    }

    fun onSelectThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            setThemeMode(mode)
        }
    }

    fun cleanAndRenormalizeLibrary() {
        if (isCleaningLibraryFlow.value) return
        isCleaningLibraryFlow.value = true
        libraryCleanupFeedbackFlow.value = null

        viewModelScope.launch {
            val feedback = withContext(ioDispatcher) {
                runCatching {
                    val result = runLibraryCleanup()
                    if (result.hasChanges) {
                        onLibraryCleaned()
                    }
                    formatCleanupFeedback(result)
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    "Cleanup failed: ${throwable.message ?: "unexpected error"}"
                }
            }
            libraryCleanupFeedbackFlow.value = feedback
            isCleaningLibraryFlow.value = false
        }
    }

    private fun formatCleanupFeedback(result: LibraryCleanupResult): String {
        if (!result.hasChanges) {
            return "Library is already clean"
        }
        val parts = buildList {
            if (result.removedAdTracks > 0 || result.removedAdEvents > 0) {
                val count = maxOf(result.removedAdTracks, result.removedAdEvents)
                add("removed $count ad/invalid ${if (count == 1) "entry" else "entries"}")
            }
            if (result.mergedDuplicateTracks > 0) {
                add("merged ${result.mergedDuplicateTracks} duplicate ${if (result.mergedDuplicateTracks == 1) "track" else "tracks"}")
            }
            if (result.updatedTracks > 0) {
                add("updated ${result.updatedTracks} ${if (result.updatedTracks == 1) "track" else "tracks"}")
            }
        }
        return "Cleaned library: ${parts.joinToString(", ")}"
    }

    fun exportJson(openOutputStream: () -> OutputStream?) {
        runExport(ExportFormat.JSON, openOutputStream) { stream ->
            dataExporter.exportJson(stream)
        }
    }

    fun exportSqliteBackup(openOutputStream: () -> OutputStream?) {
        runExport(ExportFormat.SQLITE, openOutputStream) { stream ->
            dataExporter.exportSqliteBackup(stream)
        }
    }

    fun clearExportFeedback() {
        if (exportStateFlow.value !is ExportUiState.InProgress) {
            exportStateFlow.value = ExportUiState.Idle
        }
    }

    fun reportExportError(format: ExportFormat, message: String) {
        exportStateFlow.value = ExportUiState.Error(
            format = format,
            message = message
        )
    }

    private fun runExport(
        format: ExportFormat,
        openOutputStream: () -> OutputStream?,
        exporterAction: suspend (OutputStream) -> ExportResult
    ) {
        if (exportStateFlow.value is ExportUiState.InProgress) return
        exportStateFlow.value = ExportUiState.InProgress(format)

        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val stream = openOutputStream()
                        ?: return@runCatching ExportResult.Error("Unable to open destination file")
                    stream.use { outputStream ->
                        exporterAction(outputStream)
                    }
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    ExportResult.Error(
                        message = throwable.message ?: "Export failed unexpectedly",
                        cause = throwable
                    )
                }
            }

            exportStateFlow.value = when (result) {
                is ExportResult.Success -> {
                    val label = if (format == ExportFormat.JSON) "JSON export" else "SQLite backup"
                    val kb = (result.bytesWritten / 1024L).coerceAtLeast(1L)
                    ExportUiState.Success(
                        format = format,
                        message = "$label complete (${result.trackCount} tracks, ${result.listenEventCount} listens, ${kb} KB)"
                    )
                }

                is ExportResult.Error -> ExportUiState.Error(
                    format = format,
                    message = result.message
                )
            }
        }
    }

    companion object {
        fun factory(appContainer: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        systemPermissionHelper = appContainer.systemPermissionHelper,
                        allowlistRepository = appContainer.allowlistRepository,
                        accessibilityPreferencesStore = appContainer.accessibilityPreferencesStore,
                        dataExporter = appContainer.dataExporter,
                        ioDispatcher = appContainer.ioDispatcher,
                        artworkPreferencesStore = appContainer.artworkPreferencesStore,
                        listeningRepository = appContainer.listeningRepository,
                        themePreferencesStore = appContainer.themePreferencesStore,
                        onLibraryCleaned = { appContainer.widgetUpdater.requestUpdate() }
                    ) as T
                }
            }
    }
}
