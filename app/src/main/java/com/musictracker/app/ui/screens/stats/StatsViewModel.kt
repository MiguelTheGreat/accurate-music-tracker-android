package com.musictracker.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.ArtistDetailStats
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.model.TrackDetailStats
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
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val listeningRepository: ListeningRepository,
    private val clock: TrackerClock,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    val artworkResolver: suspend (key: String, isArtist: Boolean) -> File? = { _, _ -> null }
) : ViewModel() {

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

    val uiState: StateFlow<StatsUiState> = timeframeWindowFlow.flatMapLatest { window ->
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StatsUiState(isLoading = true)
    )

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
            artworkResolver: suspend (key: String, isArtist: Boolean) -> File? = { _, _ -> null }
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StatsViewModel(
                        listeningRepository = appContainer.listeningRepository,
                        clock = appContainer.clock,
                        artworkResolver = artworkResolver
                    ) as T
                }
            }
    }
}
