package com.musictracker.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musictracker.app.data.artwork.ArtworkRepository
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.ArtistDetailStats
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.model.TrackDetailStats
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.stats.ListeningInsightsCalculator
import com.musictracker.app.ui.common.computeTimeframeSinceEpochMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val listeningRepository: ListeningRepository,
    private val clock: TrackerClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val artworkRepository: ArtworkRepository? = null,
    artworkResolver: (suspend (key: String, isArtist: Boolean) -> File?)? = null
) : ViewModel() {

    val artworkResolver: suspend (key: String, isArtist: Boolean) -> File? =
        artworkResolver ?: { key, isArtist ->
            resolveArtworkForShare(key = key, isArtist = isArtist)
        }

    private val selectedTimeframeFlow = MutableStateFlow(StatsTimeframe.TODAY)
    private val refreshTickFlow = MutableStateFlow(0L)
    private val drillDownTargetFlow = MutableStateFlow<DrillDownTarget?>(null)

    private data class TimeframeWindow(
        val timeframe: StatsTimeframe,
        val sinceEpochMs: Long,
        val nowEpochMs: Long
    )

    private data class DrillDownData(
        val target: DrillDownTarget? = null,
        val trackDetail: TrackDetailStats? = null,
        val artistDetail: ArtistDetailStats? = null,
        val artistTopTracks: List<TopTrackStat> = emptyList()
    )

    private val timeframeWindowFlow: Flow<TimeframeWindow> = combine(
        selectedTimeframeFlow,
        refreshTickFlow
    ) { timeframe, _ ->
        val nowEpochMs = clock.currentTimeMillis()
        TimeframeWindow(
            timeframe = timeframe,
            sinceEpochMs = computeTimeframeSinceEpochMs(
                timeframe = timeframe,
                nowEpochMs = nowEpochMs,
                zoneId = zoneId
            ),
            nowEpochMs = nowEpochMs
        )
    }.distinctUntilChanged()

    private val drillDownDataFlow: Flow<DrillDownData> = combine(
        timeframeWindowFlow,
        drillDownTargetFlow
    ) { window, target ->
        window.sinceEpochMs to target
    }.distinctUntilChanged().flatMapLatest { (sinceEpochMs, target) ->
        when (target) {
            null -> flowOf(DrillDownData())
            is DrillDownTarget.Track -> listeningRepository
                .observeTrackDetail(target.track.trackId, sinceEpochMs)
                .map { detail -> DrillDownData(target = target, trackDetail = detail) }

            is DrillDownTarget.Artist -> combine(
                listeningRepository.observeArtistDetail(target.artist.artist, sinceEpochMs),
                listeningRepository.observeTopTracksForArtist(
                    artistName = target.artist.artist,
                    sinceEpochMs = sinceEpochMs,
                    limit = TOP_LIST_LIMIT
                )
            ) { detail, topTracks ->
                DrillDownData(
                    target = target,
                    artistDetail = detail,
                    artistTopTracks = topTracks
                )
            }
        }
    }

    private val artworkRevisionFlow: Flow<Int> =
        artworkRepository?.artworkRevision ?: flowOf(0)

    private val baseUiStateFlow: Flow<StatsUiState> = timeframeWindowFlow.flatMapLatest { window ->
        combine(
            listeningRepository.observeSummary(window.sinceEpochMs),
            listeningRepository.observeTopTracks(window.sinceEpochMs, limit = TOP_LIST_LIMIT),
            listeningRepository.observeTopArtists(window.sinceEpochMs, limit = TOP_LIST_LIMIT),
            listeningRepository.observeAllListenEventTimestamps(),
            drillDownDataFlow
        ) { summary, topTracks, topArtists, allEvents, drillDown ->
            val insights = ListeningInsightsCalculator.computeAll(
                allEvents = allEvents,
                timeframeSinceEpochMs = window.sinceEpochMs,
                nowEpochMs = window.nowEpochMs,
                zoneId = zoneId
            )
            StatsUiState(
                selectedTimeframe = window.timeframe,
                summary = summary,
                topTracks = topTracks,
                topArtists = topArtists,
                insights = insights,
                drillDownTarget = drillDown.target,
                selectedTrackDetail = drillDown.trackDetail,
                selectedArtistDetail = drillDown.artistDetail,
                selectedArtistTopTracks = drillDown.artistTopTracks,
                isLoading = false
            )
        }
    }.onEach { state ->
        if (artworkRepository != null) {
            prefetchArtwork(
                topTracks = state.topTracks,
                topArtists = state.topArtists,
                artistTopTracks = state.selectedArtistTopTracks
            )
        }
    }

    val uiState: StateFlow<StatsUiState> = combine(
        baseUiStateFlow,
        artworkRevisionFlow
    ) { state, _ ->
        val repo = artworkRepository
        if (repo == null) {
            state
        } else {
            val allTracks = buildList {
                addAll(state.topTracks)
                addAll(state.selectedArtistTopTracks)
            }
            val trackPaths = buildMap {
                for (track in allTracks) {
                    val dedupKey = MusicMetadataNormalizer.buildDedupKey(track.artist, track.title)
                    repo.getCachedTrackArtworkFile(dedupKey)?.absolutePath?.let { path ->
                        put(track.trackId, path)
                    }
                }
            }
            val allArtistNames = buildSet {
                state.topArtists.forEach { add(it.artist) }
                (state.drillDownTarget as? DrillDownTarget.Artist)?.let { add(it.artist.artist) }
                state.selectedArtistDetail?.let { add(it.artist) }
            }
            val artistPaths = buildMap {
                for (artistName in allArtistNames) {
                    val artistKey = normalizeArtistKey(artistName)
                    val direct = repo.getCachedArtistArtworkFile(artistKey)?.absolutePath
                    val resolved = direct ?: findFallbackTrackDedupKey(artistName, allTracks)
                        ?.let { repo.getCachedTrackArtworkFile(it)?.absolutePath }
                    if (resolved != null) {
                        put(artistName, resolved)
                    }
                }
            }
            state.copy(
                artistArtworkPaths = artistPaths,
                trackArtworkPaths = trackPaths
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StatsUiState(isLoading = true)
    )

    private fun prefetchArtwork(
        topTracks: List<TopTrackStat>,
        topArtists: List<TopArtistStat>,
        artistTopTracks: List<TopTrackStat>
    ) {
        val repo = artworkRepository ?: return
        val combinedTracks = buildList {
            addAll(topTracks)
            addAll(artistTopTracks)
        }.distinctBy { it.trackId }

        for (track in combinedTracks) {
            val dedupKey = MusicMetadataNormalizer.buildDedupKey(track.artist, track.title)
            if (repo.getCachedTrackArtworkFile(dedupKey) == null) {
                viewModelScope.launch {
                    repo.resolveTrackArtwork(
                        dedupKey = dedupKey,
                        artist = track.artist,
                        title = track.title,
                        rawArtworkUri = track.artworkUri
                    )
                }
            }
        }

        for (artist in topArtists) {
            val artistKey = normalizeArtistKey(artist.artist)
            if (repo.getCachedArtistArtworkFile(artistKey) == null) {
                val fallbackDedupKey = findFallbackTrackDedupKey(artist.artist, combinedTracks)
                viewModelScope.launch {
                    repo.resolveArtistArtwork(
                        artistKey = artistKey,
                        artistName = artist.artist,
                        fallbackTrackDedupKey = fallbackDedupKey
                    )
                }
            }
        }
    }

    private suspend fun resolveArtworkForShare(key: String, isArtist: Boolean): File? {
        val repo = artworkRepository ?: return null
        val state = uiState.value
        val knownTracks = state.topTracks + state.selectedArtistTopTracks
        return if (isArtist) {
            val artistKey = normalizeArtistKey(key)
            val fallbackDedupKey = findFallbackTrackDedupKey(key, knownTracks)
            repo.resolveArtistArtwork(
                artistKey = artistKey,
                artistName = key,
                fallbackTrackDedupKey = fallbackDedupKey
            )
        } else {
            val parts = key.split("::", limit = 2)
            if (parts.size != 2) return null
            val artist = parts[0]
            val title = parts[1]
            val dedupKey = MusicMetadataNormalizer.buildDedupKey(artist, title)
            val rawUri = knownTracks.firstOrNull {
                it.artist.equals(artist, ignoreCase = true) &&
                    it.title.equals(title, ignoreCase = true)
            }?.artworkUri
            repo.resolveTrackArtwork(
                dedupKey = dedupKey,
                artist = artist,
                title = title,
                rawArtworkUri = rawUri
            )
        }
    }

    private fun findFallbackTrackDedupKey(
        artistName: String,
        tracks: List<TopTrackStat>
    ): String? {
        val match = tracks.firstOrNull { it.artist.equals(artistName, ignoreCase = true) }
            ?: return null
        return MusicMetadataNormalizer.buildDedupKey(match.artist, match.title)
    }

    private fun normalizeArtistKey(artistName: String): String =
        artistName.trim().lowercase(Locale.ROOT)

    fun selectTimeframe(timeframe: StatsTimeframe) {
        selectedTimeframeFlow.value = timeframe
    }

    fun openTrackDetail(track: TopTrackStat) {
        drillDownTargetFlow.value = DrillDownTarget.Track(track)
    }

    fun openArtistDetail(artist: TopArtistStat) {
        drillDownTargetFlow.value = DrillDownTarget.Artist(artist)
    }

    fun dismissDrillDown() {
        drillDownTargetFlow.value = null
    }

    fun refresh() {
        refreshTickFlow.value += 1L
    }

    companion object {
        private const val TOP_LIST_LIMIT = 5

        fun factory(
            appContainer: AppContainer,
            artworkResolver: (suspend (key: String, isArtist: Boolean) -> File?)? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StatsViewModel(
                        listeningRepository = appContainer.listeningRepository,
                        clock = appContainer.clock,
                        artworkRepository = appContainer.artworkRepository,
                        artworkResolver = artworkResolver
                    ) as T
                }
            }
    }
}
