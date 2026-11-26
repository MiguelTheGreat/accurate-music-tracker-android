package com.musictracker.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.musictracker.app.data.clock.SystemTrackerClock
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.export.DataExporter
import com.musictracker.app.data.export.DataExporterImpl
import com.musictracker.app.data.preferences.AccessibilityPreferencesStore
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.AllowlistStore
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import com.musictracker.app.tracking.AndroidSystemPermissionHelper
import com.musictracker.app.tracking.MediaSessionCoordinator
import com.musictracker.app.tracking.SystemPermissionHelper
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class AppContainer(
    context: Context,
    val clock: TrackerClock = SystemTrackerClock(),
    val metadataNormalizer: MetadataNormalizer = MusicMetadataNormalizer(),
    val playSessionEngine: PlaySessionEngine = PlaySessionStateMachine(
        clock = clock,
        normalizer = metadataNormalizer
    ),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val schedulerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + schedulerDispatcher + CoroutineExceptionHandler { _, throwable ->
            runCatching { Log.e(TAG, "Uncaught coroutine exception", throwable) }
        }
    )
) {
    private val appContext = context.applicationContext

    private val preferencesDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(PREFERENCES_FILE_NAME) }
        )
    }

    val database: MusicTrackerDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            MusicTrackerDatabase::class.java,
            MusicTrackerDatabase.DATABASE_NAME
        ).build()
    }

    val listeningRepository: ListeningRepository by lazy {
        ListeningRepositoryImpl(
            database = database,
            normalizer = metadataNormalizer
        )
    }

    val allowlistRepository: AllowlistRepository by lazy {
        AllowlistStore(dataStore = preferencesDataStore)
    }

    val accessibilityPreferencesStore: AccessibilityPreferencesStore by lazy {
        AccessibilityPreferencesStore(dataStore = preferencesDataStore)
    }

    val dataExporter: DataExporter by lazy {
        DataExporterImpl(
            database = database,
            dbFileProvider = { appContext.getDatabasePath(MusicTrackerDatabase.DATABASE_NAME) },
            clock = clock
        )
    }

    val systemPermissionHelper: SystemPermissionHelper by lazy {
        AndroidSystemPermissionHelper(appContext)
    }

    val mediaSessionCoordinator: MediaSessionCoordinator by lazy {
        MediaSessionCoordinator(
            playSessionEngine = playSessionEngine,
            allowlistRepository = allowlistRepository,
            listeningRepository = listeningRepository,
            clock = clock,
            scope = applicationScope,
            ioDispatcher = ioDispatcher,
            schedulerDispatcher = schedulerDispatcher
        )
    }

    val isListenerConnected: StateFlow<Boolean>
        get() = mediaSessionCoordinator.isListenerConnected

    companion object {
        private const val TAG = "AppContainer"
        private const val PREFERENCES_FILE_NAME = "music_tracker_prefs"
    }
}
