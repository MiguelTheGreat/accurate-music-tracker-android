package com.musictracker.app.ui

import android.content.Context
import com.musictracker.app.data.export.DataExporter
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.tracking.SystemPermissionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class FakeTrackerClock(
    var elapsedMs: Long = 10_000L,
    var epochMs: Long = 1_735_689_600_000L // 2025-01-01T00:00:00Z
) : TrackerClock {
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun currentTimeMillis(): Long = epochMs
}

class FakeListeningRepository : ListeningRepository {
    val summaryByCutoff = mutableMapOf<Long, MutableStateFlow<ListeningSummary>>()
    val defaultSummaryFlow = MutableStateFlow(ListeningSummary())
    val recentHistoryFlow = MutableStateFlow<List<RecentListenItem>>(emptyList())
    val topTracksByCutoff = mutableMapOf<Long, MutableStateFlow<List<TopTrackStat>>>()
    val defaultTopTracksFlow = MutableStateFlow<List<TopTrackStat>>(emptyList())
    val topArtistsByCutoff = mutableMapOf<Long, MutableStateFlow<List<TopArtistStat>>>()
    val defaultTopArtistsFlow = MutableStateFlow<List<TopArtistStat>>(emptyList())

    var lastSummarySinceEpochMs: Long? = null
    var lastTopTracksSinceEpochMs: Long? = null
    var lastTopArtistsSinceEpochMs: Long? = null

    override suspend fun recordCompletedSession(session: CompletedListenSession) = Unit

    override fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary> {
        lastSummarySinceEpochMs = sinceEpochMs
        return summaryByCutoff[sinceEpochMs] ?: defaultSummaryFlow
    }

    override fun observeRecentHistory(limit: Int): Flow<List<RecentListenItem>> = recentHistoryFlow

    override fun observeTopTracks(sinceEpochMs: Long, limit: Int): Flow<List<TopTrackStat>> {
        lastTopTracksSinceEpochMs = sinceEpochMs
        return topTracksByCutoff[sinceEpochMs] ?: defaultTopTracksFlow
    }

    override fun observeTopArtists(sinceEpochMs: Long, limit: Int): Flow<List<TopArtistStat>> {
        lastTopArtistsSinceEpochMs = sinceEpochMs
        return topArtistsByCutoff[sinceEpochMs] ?: defaultTopArtistsFlow
    }

    override suspend fun renormalizeAllTracks(): Int = 0
}

class FakePlaySessionEngine(
    initial: NowTrackingState? = null
) : PlaySessionEngine {
    val stateFlow = MutableStateFlow(initial)
    override val nowTracking: StateFlow<NowTrackingState?> = stateFlow.asStateFlow()

    override fun onPlaybackEvent(event: PlaybackEvent): List<CompletedListenSession> = emptyList()
    override fun onSessionEnded(sourcePackage: String): List<CompletedListenSession> = emptyList()
    override fun flushActiveSession(): List<CompletedListenSession> = emptyList()
}

class FakeAllowlistRepository(
    initialPackages: Set<String> = emptySet()
) : AllowlistRepository {
    private val _allowedPackages = MutableStateFlow(initialPackages)
    override val allowedPackages: Flow<Set<String>> = _allowedPackages.asStateFlow()

    val toggleHistory = mutableListOf<Pair<String, Boolean>>()

    override suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
        toggleHistory += packageName to allowed
        _allowedPackages.value = if (allowed) {
            _allowedPackages.value + packageName
        } else {
            _allowedPackages.value - packageName
        }
    }

    override suspend fun resetToDefaults() {
        _allowedPackages.value = emptySet()
    }
}

class FakeSystemPermissionHelper(
    var notificationAccessGranted: Boolean = false,
    var ignoringBatteryOptimizations: Boolean = false,
    var installedApps: List<InstalledAppInfo> = emptyList()
) : SystemPermissionHelper {
    var openNotificationSettingsCalls = 0
    var requestBatteryExemptionCalls = 0
    var openBatterySettingsCalls = 0

    override fun isNotificationAccessGranted(): Boolean = notificationAccessGranted

    override fun openNotificationListenerSettings(context: Context): Boolean {
        openNotificationSettingsCalls++
        return true
    }

    override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

    override fun requestBatteryOptimizationExemption(context: Context): Boolean {
        requestBatteryExemptionCalls++
        return true
    }

    override fun openBatteryOptimizationSettings(context: Context): Boolean {
        openBatterySettingsCalls++
        return true
    }

    override fun getInstalledLaunchableApps(): List<InstalledAppInfo> = installedApps
}

class FakeDataExporter(
    var jsonResult: ExportResult = ExportResult.Success(
        bytesWritten = 4096L,
        trackCount = 5,
        listenEventCount = 12
    ),
    var sqliteResult: ExportResult = ExportResult.Success(
        bytesWritten = 16384L,
        trackCount = 5,
        listenEventCount = 12
    ),
    var jsonPayload: ByteArray = """{"schema_version":1,"tracks":[],"listen_events":[]}""".toByteArray(),
    var sqlitePayload: ByteArray = "SQLite format 3\u0000".toByteArray(),
    var gate: CompletableDeferred<Unit>? = null
) : DataExporter {
    var jsonExportCalls = 0
    var sqliteExportCalls = 0

    override suspend fun exportJson(outputStream: OutputStream): ExportResult {
        jsonExportCalls++
        gate?.await()
        if (jsonResult is ExportResult.Success) {
            outputStream.write(jsonPayload)
            outputStream.flush()
        }
        return jsonResult
    }

    override suspend fun exportSqliteBackup(outputStream: OutputStream): ExportResult {
        sqliteExportCalls++
        gate?.await()
        if (sqliteResult is ExportResult.Success) {
            outputStream.write(sqlitePayload)
            outputStream.flush()
        }
        return sqliteResult
    }
}
