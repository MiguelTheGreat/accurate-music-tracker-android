package com.musictracker.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.musictracker.app.data.artwork.ArtworkCacheManager
import com.musictracker.app.data.artwork.ArtworkHttpClient
import com.musictracker.app.data.artwork.ArtworkRepository
import com.musictracker.app.data.artwork.OnlineArtworkFetcher
import com.musictracker.app.data.artwork.UrlConnectionArtworkHttpClient
import com.musictracker.app.data.clock.SystemTrackerClock
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.export.DataExporter
import com.musictracker.app.data.export.DataExporterImpl
import com.musictracker.app.data.preferences.AccessibilityPreferencesStore
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.AllowlistStore
import com.musictracker.app.data.preferences.ArtworkPreferencesStore
import com.musictracker.app.data.preferences.ThemePreferencesStore
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
import com.musictracker.app.widget.CollectoWidgetUpdater
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

    val artworkPreferencesStore: ArtworkPreferencesStore by lazy {
        ArtworkPreferencesStore(dataStore = preferencesDataStore)
    }

    val themePreferencesStore: ThemePreferencesStore by lazy {
        ThemePreferencesStore(dataStore = preferencesDataStore)
    }

    val artworkHttpClient: ArtworkHttpClient by lazy {
        UrlConnectionArtworkHttpClient(ioDispatcher = ioDispatcher)
    }

    val artworkCacheManager: ArtworkCacheManager by lazy {
        ArtworkCacheManager(context = appContext)
    }

    val onlineArtworkFetcher: OnlineArtworkFetcher by lazy {
        OnlineArtworkFetcher(httpClient = artworkHttpClient)
    }

    val artworkRepository: ArtworkRepository by lazy {
        ArtworkRepository(
            cacheManager = artworkCacheManager,
            onlineFetcher = onlineArtworkFetcher,
            onlineArtworkEnabledFlow = artworkPreferencesStore.onlineArtworkEnabled,
            contentResolver = appContext.contentResolver,
            ioDispatcher = ioDispatcher
        )
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

    val widgetUpdater: CollectoWidgetUpdater by lazy {
        CollectoWidgetUpdater(
            context = appContext,
            scope = applicationScope,
            ioDispatcher = ioDispatcher
        )
    }

    val mediaSessionCoordinator: MediaSessionCoordinator by lazy {
        MediaSessionCoordinator(
            playSessionEngine = playSessionEngine,
            allowlistRepository = allowlistRepository,
            listeningRepository = listeningRepository,
            clock = clock,
            scope = applicationScope,
            ioDispatcher = ioDispatcher,
            schedulerDispatcher = schedulerDispatcher,
            artworkRepository = artworkRepository,
            metadataNormalizer = metadataNormalizer,
            onWidgetRefreshRequested = { widgetUpdater.requestUpdate() }
        )
    }

    val isListenerConnected: StateFlow<Boolean>
        get() = mediaSessionCoordinator.isListenerConnected

    companion object {
        private const val TAG = "AppContainer"
        private const val PREFERENCES_FILE_NAME = "music_tracker_prefs"
    }
}
