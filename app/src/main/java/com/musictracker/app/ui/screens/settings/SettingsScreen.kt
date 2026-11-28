package com.musictracker.app.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.data.importer.ImportFormat
import com.musictracker.app.data.preferences.ThemeMode
import com.musictracker.app.tracking.SystemPermissionHelper
import com.musictracker.app.ui.components.content.StandardMaterialCard
import com.musictracker.app.ui.components.glass.LiquidGlassBanner
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassSegmentedControl
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassSwitch
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ExportTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    systemPermissionHelper: SystemPermissionHelper,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSystemStatus()
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportJson { context.contentResolver.openOutputStream(uri) }
        }
    }

    val sqliteExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.exportSqliteBackup { context.contentResolver.openOutputStream(uri) }
        }
    }

    var pendingImportFormat by remember { mutableStateOf<ImportFormat?>(null) }
    var showImportFormatSheet by remember { mutableStateOf(false) }

    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val format = pendingImportFormat
        pendingImportFormat = null
        if (uri != null && format != null) {
            viewModel.importData(format) { context.contentResolver.openInputStream(uri) }
        }
    }

    SettingsScreenContent(
        uiState = uiState,
        onOpenNotificationAccess = {
            systemPermissionHelper.openNotificationListenerSettings(context)
        },
        onBatteryOptimizationClick = {
            if (uiState.isIgnoringBatteryOptimizations) {
                systemPermissionHelper.openBatteryOptimizationSettings(context)
            } else {
                systemPermissionHelper.requestBatteryOptimizationExemption(context)
            }
        },
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onToggleAppAllowed = viewModel::onToggleAppAllowed,
        onExportJsonClick = {
            val timestamp = ExportTimestampFormatter.format(
                Instant.now().atZone(ZoneId.systemDefault())
            )
            try {
                jsonExportLauncher.launch("collecto-export-$timestamp.json")
            } catch (_: ActivityNotFoundException) {
                viewModel.reportExportError(
                    ExportFormat.JSON,
                    "No file manager available to save export"
                )
            }
        },
        onExportSqliteClick = {
            val timestamp = ExportTimestampFormatter.format(
                Instant.now().atZone(ZoneId.systemDefault())
            )
            try {
                sqliteExportLauncher.launch("collecto-backup-$timestamp.db")
            } catch (_: ActivityNotFoundException) {
                viewModel.reportExportError(
                    ExportFormat.SQLITE,
                    "No file manager available to save export"
                )
            }
        },
        onDismissExportFeedback = viewModel::clearExportFeedback,
        onImportClick = { showImportFormatSheet = true },
        onDismissImportFeedback = viewModel::clearImportFeedback,
        onToggleReduceTransparency = viewModel::onToggleReduceTransparency,
        onToggleOnlineArtwork = viewModel::onToggleOnlineArtwork,
        onCleanLibraryClick = viewModel::cleanAndRenormalizeLibrary,
        onSelectThemeMode = viewModel::onSelectThemeMode,
        modifier = modifier
    )

    if (showImportFormatSheet) {
        ImportFormatSheet(
            onDismiss = { showImportFormatSheet = false },
            onSelectFormat = { format ->
                showImportFormatSheet = false
                pendingImportFormat = format
                val mimeTypes = when (format) {
                    ImportFormat.LASTFM_CSV -> arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
                    ImportFormat.COLLECTO_JSON,
                    ImportFormat.LASTFM_JSON,
                    ImportFormat.SPOTIFY_EXTENDED_JSON -> arrayOf("application/json", "text/plain", "*/*")
                }
                try {
                    importDocumentLauncher.launch(mimeTypes)
                } catch (_: ActivityNotFoundException) {
                    pendingImportFormat = null
                    viewModel.reportImportError(
                        format,
                        "No file manager available to open import file"
                    )
                }
            }
        )
    }
}

@Composable
fun SettingsScreenContent(
    uiState: SettingsUiState,
    onOpenNotificationAccess: () -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleAppAllowed: (packageName: String, allowed: Boolean) -> Unit,
    onExportJsonClick: () -> Unit,
    onExportSqliteClick: () -> Unit,
    onDismissExportFeedback: () -> Unit,
    onToggleReduceTransparency: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onToggleOnlineArtwork: (Boolean) -> Unit = {},
    onCleanLibraryClick: () -> Unit = {},
    onSelectThemeMode: (ThemeMode) -> Unit = {},
    onImportClick: () -> Unit = {},
    onDismissImportFeedback: () -> Unit = {}
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.contentBackground),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBarTop + 16.dp,
            bottom = navBarBottom + 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "settings_header") {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.labelPrimary
            )
        }

        // Export Feedback Banner (floating Regular glass when Success or Error)
        val exportState = uiState.exportState
        if (exportState is ExportUiState.Success || exportState is ExportUiState.Error) {
            item(key = "settings_export_feedback") {
                val isError = exportState is ExportUiState.Error
                val title = if (isError) "Export Failed" else "Export Complete"
                val message = when (exportState) {
                    is ExportUiState.Success -> exportState.message
                    is ExportUiState.Error -> exportState.message
                    else -> ""
                }
                LiquidGlassBanner(
                    title = title,
                    subtitle = message,
                    onDismiss = onDismissExportFeedback,
                    dismissContentDescription = "Dismiss export feedback",
                    isError = isError,
                    modifier = Modifier.testTag(TestTags.SETTINGS_EXPORT_FEEDBACK)
                )
            }
        }

        // Import Feedback Banner (floating Regular glass when Success or Error)
        val importState = uiState.importState
        if (importState is ImportUiState.Success || importState is ImportUiState.Error) {
            item(key = "settings_import_feedback") {
                val isError = importState is ImportUiState.Error
                val title = if (isError) "Import Failed" else "Import Complete"
                val message = when (importState) {
                    is ImportUiState.Success -> importState.message
                    is ImportUiState.Error -> importState.message
                    else -> ""
                }
                LiquidGlassBanner(
                    title = title,
                    subtitle = message,
                    onDismiss = onDismissImportFeedback,
                    dismissContentDescription = "Dismiss import feedback",
                    isError = isError,
                    modifier = Modifier.testTag("settings_import_feedback")
                )
            }
        }

        // Section 1: Tracking Permissions
        item(key = "settings_permissions_section") {
            SettingsSectionTitle(text = "Tracking Permissions")
            Spacer(modifier = Modifier.height(8.dp))
            StandardMaterialCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                PermissionRow(
                    title = "Notification Access",
                    description = "Reads active Android media sessions locally without root or Accessibility Services.",
                    statusText = if (uiState.isNotificationAccessGranted) "Active" else "Not Granted",
                    statusPositive = uiState.isNotificationAccessGranted,
                    statusTestTag = TestTags.SETTINGS_NOTIF_STATUS,
                    buttonLabel = if (uiState.isNotificationAccessGranted) "Manage" else "Grant Access",
                    buttonTestTag = TestTags.SETTINGS_NOTIF_BTN,
                    onButtonClick = onOpenNotificationAccess
                )

                HorizontalDivider(
                    color = colors.standardCardBorder,
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                PermissionRow(
                    title = "Battery Optimization",
                    description = "Allow unrestricted background execution so the session listener stays alive.",
                    statusText = if (uiState.isIgnoringBatteryOptimizations) {
                        "Unrestricted"
                    } else {
                        "Optimized (May Interrupt Tracking)"
                    },
                    statusPositive = uiState.isIgnoringBatteryOptimizations,
                    statusTestTag = TestTags.SETTINGS_BATTERY_STATUS,
                    buttonLabel = if (uiState.isIgnoringBatteryOptimizations) "Manage" else "Exempt App",
                    buttonTestTag = TestTags.SETTINGS_BATTERY_BTN,
                    onButtonClick = onBatteryOptimizationClick
                )
            }
        }

        // Section 2: Data Ownership & SAF Export
        item(key = "settings_data_section") {
            SettingsSectionTitle(text = "Data Ownership")
            Spacer(modifier = Modifier.height(8.dp))
            StandardMaterialCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    text = "Export your entire local listening history anytime using Android's Storage Access Framework.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.labelSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isJsonExporting = exportState is ExportUiState.InProgress &&
                        exportState.format == ExportFormat.JSON
                    val isSqliteExporting = exportState is ExportUiState.InProgress &&
                        exportState.format == ExportFormat.SQLITE

                    LiquidGlassButton(
                        onClick = onExportJsonClick,
                        enabled = !uiState.isExporting,
                        variant = LiquidGlassVariant.Prominent,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.SETTINGS_EXPORT_JSON_BTN)
                    ) {
                        if (isJsonExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.onProminent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "Export JSON",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }

                    LiquidGlassButton(
                        onClick = onExportSqliteClick,
                        enabled = !uiState.isExporting,
                        variant = LiquidGlassVariant.Regular,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN)
                    ) {
                        if (isSqliteExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.labelPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Backup,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "SQLite Backup",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                LiquidGlassButton(
                    onClick = onImportClick,
                    enabled = !uiState.isImporting && !uiState.isExporting && !uiState.isCleaningLibrary,
                    variant = LiquidGlassVariant.Regular,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_import_btn")
                ) {
                    if (uiState.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.labelPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = if (uiState.isImporting) {
                            "Importing History..."
                        } else {
                            "Import & Restore"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }

                HorizontalDivider(
                    color = colors.standardCardBorder,
                    modifier = Modifier.padding(vertical = 14.dp)
                )

                Text(
                    text = "Remove recorded streaming ads and merge duplicate tracks or artists using preserved raw metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                LiquidGlassButton(
                    onClick = onCleanLibraryClick,
                    enabled = !uiState.isCleaningLibrary && !uiState.isExporting,
                    variant = LiquidGlassVariant.Regular,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.SETTINGS_CLEAN_LIBRARY_BTN)
                ) {
                    if (uiState.isCleaningLibrary) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.labelPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = if (uiState.isCleaningLibrary) {
                            "Cleaning Library..."
                        } else {
                            "Clean & Re-normalize Library"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }

                uiState.libraryCleanupFeedback?.let { feedback ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentGreen,
                        modifier = Modifier.testTag(TestTags.SETTINGS_CLEAN_LIBRARY_FEEDBACK)
                    )
                }
            }
        }

        // Section 3: Appearance
        item(key = "settings_appearance_section") {
            SettingsSectionTitle(text = "Appearance")
            Spacer(modifier = Modifier.height(8.dp))
            StandardMaterialCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Theme Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.labelPrimary
                        )
                        Text(
                            text = "Follow your system theme or force Light, Dark, or AMOLED Black Liquid Glass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.labelSecondary
                        )
                    }
                    LiquidGlassSegmentedControl(
                        options = listOf(
                            ThemeMode.SYSTEM,
                            ThemeMode.LIGHT,
                            ThemeMode.DARK,
                            ThemeMode.AMOLED
                        ),
                        selectedOption = uiState.themeMode,
                        onOptionSelected = onSelectThemeMode,
                        labelProvider = { mode ->
                            when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.AMOLED -> "AMOLED"
                            }
                        },
                        testTagProvider = TestTags::settingsThemeMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.SETTINGS_THEME_CONTROL)
                    )
                }
            }
        }

        // Section 4: Accessibility
        item(key = "settings_accessibility_section") {
            SettingsSectionTitle(text = "Accessibility")
            Spacer(modifier = Modifier.height(8.dp))
            StandardMaterialCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Reduce Transparency",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.labelPrimary
                        )
                        Text(
                            text = "Use higher-opacity frosted chrome with blur disabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.labelSecondary
                        )
                    }
                    LiquidGlassSwitch(
                        checked = uiState.reduceTransparencyEnabled,
                        onCheckedChange = onToggleReduceTransparency,
                        modifier = Modifier.testTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH)
                    )
                }

                if (accessibility.highContrast || accessibility.reduceMotion) {
                    HorizontalDivider(
                        color = colors.standardCardBorder,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    val activeModes = buildList {
                        if (accessibility.highContrast) add("High Contrast")
                        if (accessibility.reduceMotion) add("Reduced Motion")
                    }.joinToString(" · ")
                    Text(
                        text = "System modes active: $activeModes",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.labelSecondary
                    )
                }
            }
        }

        // Section 4: Artwork & Artist Photos
        item(key = "settings_artwork_section") {
            SettingsSectionTitle(text = "Artwork & Photos")
            Spacer(modifier = Modifier.height(8.dp))
            StandardMaterialCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Online Artwork & Artist Photos",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.labelPrimary
                        )
                        Text(
                            text = "Fetch artist photos and missing album art online, then cache locally for offline use.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.labelSecondary
                        )
                    }
                    LiquidGlassSwitch(
                        checked = uiState.onlineArtworkEnabled,
                        onCheckedChange = onToggleOnlineArtwork,
                        modifier = Modifier.testTag("settings_online_artwork_switch")
                    )
                }
            }
        }

        // Section 5: App Allowlist Manager
        item(key = "settings_allowlist_header") {
            SettingsSectionTitle(text = "App Allowlist")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SETTINGS_ALLOWLIST_SEARCH),
                placeholder = {
                    Text(
                        text = "Search installed apps",
                        color = colors.labelTertiary
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.labelSecondary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.standardCardFill,
                    unfocusedContainerColor = colors.standardCardFill,
                    focusedBorderColor = colors.accentBlue,
                    unfocusedBorderColor = colors.standardCardBorder,
                    focusedTextColor = colors.labelPrimary,
                    unfocusedTextColor = colors.labelPrimary
                )
            )
        }

        if (uiState.isLoadingApps) {
            item(key = "settings_allowlist_loading") {
                StandardMaterialCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Loading installed apps...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.labelSecondary
                        )
                    }
                }
            }
        } else if (uiState.filteredApps.isEmpty()) {
            item(key = "settings_allowlist_empty") {
                StandardMaterialCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (uiState.searchQuery.isBlank()) {
                            "No launchable apps found."
                        } else {
                            "No matching apps for \"${uiState.searchQuery}\""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.labelSecondary
                    )
                }
            }
        } else {
            items(
                items = uiState.filteredApps,
                key = { it.packageName }
            ) { app ->
                AllowlistAppRow(
                    app = app,
                    onToggle = { allowed -> onToggleAppAllowed(app.packageName, allowed) }
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    statusText: String,
    statusPositive: Boolean,
    statusTestTag: String,
    buttonLabel: String,
    buttonTestTag: String,
    onButtonClick: () -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val statusColor = if (statusPositive) colors.accentGreen else colors.accentAmber

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.labelPrimary
            )
            Box(
                modifier = Modifier
                    .testTag(statusTestTag)
                    .semantics(mergeDescendants = true) {}
                    .clip(LiquidGlassShapes.Capsule)
                    .background(statusColor.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.labelSecondary
        )
        LiquidGlassButton(
            onClick = onButtonClick,
            variant = if (statusPositive) LiquidGlassVariant.Regular else LiquidGlassVariant.Prominent,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier.testTag(buttonTestTag)
        ) {
            Text(
                text = buttonLabel,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun AllowlistAppRow(
    app: AllowlistAppItem,
    onToggle: (Boolean) -> Unit
) {
    val colors = LiquidGlassTheme.colors

    StandardMaterialCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = LiquidGlassMetrics.MinRowHeight - 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isDefaultMusicApp) {
                        Box(
                            modifier = Modifier
                                .clip(LiquidGlassShapes.Capsule)
                                .background(colors.accentBlue.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Music App",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accentBlue
                            )
                        }
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LiquidGlassSwitch(
                checked = app.isAllowed,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(TestTags.settingsAllowlistSwitch(app.packageName))
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = LiquidGlassTheme.colors.labelPrimary,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportFormatSheet(
    onDismiss: () -> Unit,
    onSelectFormat: (ImportFormat) -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        LiquidGlassSheet(isExpanded = false) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Import & Restore History",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.labelPrimary
                )
                Text(
                    text = "Choose a format to merge into your local Collecto library. Duplicate listens within 5 seconds are automatically skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                ImportFormatOption(
                    title = "Collecto JSON Backup",
                    subtitle = "Restore tracks & listens exported from Collecto",
                    variant = LiquidGlassVariant.Prominent,
                    onClick = { onSelectFormat(ImportFormat.COLLECTO_JSON) }
                )
                ImportFormatOption(
                    title = "Spotify Extended History (JSON)",
                    subtitle = "Streaming_History_Audio_*.json / endsong_*.json",
                    variant = LiquidGlassVariant.Regular,
                    onClick = { onSelectFormat(ImportFormat.SPOTIFY_EXTENDED_JSON) }
                )
                ImportFormatOption(
                    title = "Last.fm Scrobbles (CSV)",
                    subtitle = "Standard Last.fm CSV export (artist, album, track, date)",
                    variant = LiquidGlassVariant.Regular,
                    onClick = { onSelectFormat(ImportFormat.LASTFM_CSV) }
                )
                ImportFormatOption(
                    title = "Last.fm Scrobbles (JSON)",
                    subtitle = "Last.fm API recenttracks or JSON array export",
                    variant = LiquidGlassVariant.Regular,
                    onClick = { onSelectFormat(ImportFormat.LASTFM_JSON) }
                )
            }
        }
    }
}

@Composable
private fun ImportFormatOption(
    title: String,
    subtitle: String,
    variant: LiquidGlassVariant,
    onClick: () -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val titleColor = if (variant == LiquidGlassVariant.Prominent) colors.onProminent else colors.labelPrimary
    val subtitleColor = if (variant == LiquidGlassVariant.Prominent) {
        colors.onProminent.copy(alpha = 0.8f)
    } else {
        colors.labelSecondary
    }

    LiquidGlassButton(
        onClick = onClick,
        variant = variant,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
