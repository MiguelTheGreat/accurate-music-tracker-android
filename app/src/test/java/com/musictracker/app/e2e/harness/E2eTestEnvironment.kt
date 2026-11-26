package com.musictracker.app.e2e.harness

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.export.DataExporterImpl
import com.musictracker.app.data.preferences.AccessibilityPreferencesStore
import com.musictracker.app.data.preferences.AllowlistStore
import com.musictracker.app.data.preferences.DefaultAllowlist
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import com.musictracker.app.tracking.MediaSessionCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * Self-contained E2E test environment wiring real production components:
 * - Real Room [MusicTrackerDatabase] (in-memory or file-backed WAL for SQLite backup tests)
 * - Real Jetpack DataStore in an isolated temp directory
 * - Real [MusicMetadataNormalizer], [PlaySessionStateMachine], [ListeningRepositoryImpl],
 *   [AllowlistStore], [AccessibilityPreferencesStore], and [DataExporterImpl]
 */
class E2eTestEnvironment(
    context: Context,
    val clock: FakeTrackerClock = FakeTrackerClock(),
    val normalizer: MetadataNormalizer = MusicMetadataNormalizer(),
    fileBackedDb: Boolean = false,
    defaultAllowlistPackages: Set<String> = DefaultAllowlist.packages,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    val tempDir: File = Files.createTempDirectory("musictracker_e2e_").toFile()
    val dbFile: File = File(tempDir, MusicTrackerDatabase.DATABASE_NAME)
    val prefsFile: File = File(tempDir, "e2e_prefs.preferences_pb")

    val dataStoreScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: MusicTrackerDatabase = if (fileBackedDb) {
        Room.databaseBuilder(context, MusicTrackerDatabase::class.java, dbFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
    } else {
        Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { prefsFile }
    )

    val allowlistStore: AllowlistStore = AllowlistStore(
        dataStore = dataStore,
        defaultPackages = defaultAllowlistPackages
    )

    val accessibilityPreferencesStore: AccessibilityPreferencesStore =
        AccessibilityPreferencesStore(dataStore = dataStore)

    val listeningRepository: ListeningRepositoryImpl = ListeningRepositoryImpl(
        database = database,
        normalizer = normalizer
    )

    val playSessionStateMachine: PlaySessionStateMachine = PlaySessionStateMachine(
        clock = clock,
        normalizer = normalizer
    )

    val dataExporter: DataExporterImpl = DataExporterImpl(
        database = database,
        dbFileProvider = { dbFile },
        clock = clock,
        ioDispatcher = ioDispatcher
    )

    fun createCoordinator(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        schedulerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        debounceMs: Long = PlaySessionStateMachine.DEFAULT_DEBOUNCE_MS,
        idlePauseTimeoutMs: Long = PlaySessionStateMachine.DEFAULT_IDLE_PAUSE_TIMEOUT_MS
    ): MediaSessionCoordinator = MediaSessionCoordinator(
        playSessionEngine = playSessionStateMachine,
        allowlistRepository = allowlistStore,
        listeningRepository = listeningRepository,
        clock = clock,
        scope = scope,
        ioDispatcher = ioDispatcher,
        schedulerDispatcher = schedulerDispatcher,
        debounceMs = debounceMs,
        idlePauseTimeoutMs = idlePauseTimeoutMs
    )

    suspend fun waitForEventCount(expectedCount: Int, timeoutMs: Long = 3_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (database.listenEventDao().getAll().size < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
    }

    /**
     * Subscribes to a [kotlinx.coroutines.flow.StateFlow] (activating `WhileSubscribed` upstream Flows)
     * and pumps Robolectric's Main Looper until [predicate] matches or [timeoutMs] elapses.
     * Prevents Main-thread deadlocks when testing `viewModelScope` Flows under Robolectric.
     */
    fun <T> awaitStateFlow(
        flow: kotlinx.coroutines.flow.StateFlow<T>,
        timeoutMs: Long = 5_000L,
        predicate: (T) -> Boolean
    ): T {
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = collectorScope.launch {
            flow.collect { }
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val current = flow.value
                if (predicate(current)) return current
                Thread.sleep(15L)
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val finalVal = flow.value
            if (predicate(finalVal)) return finalVal
            throw AssertionError("Timed out after ${timeoutMs}ms awaiting StateFlow; last value: $finalVal")
        } finally {
            job.cancel()
            collectorScope.cancel()
        }
    }

    override fun close() {
        runCatching { database.close() }
        runCatching { dataStoreScope.cancel() }
        runCatching { tempDir.deleteRecursively() }
    }
}
