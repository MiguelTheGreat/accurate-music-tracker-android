package com.musictracker.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.ui.common.computeTimeframeSinceEpochMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val listeningRepository: ListeningRepository,
    private val clock: TrackerClock,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val selectedTimeframeFlow = MutableStateFlow(StatsTimeframe.TODAY)
    private val refreshTickFlow = MutableStateFlow(0L)

    val uiState: StateFlow<StatsUiState> = combine(
        selectedTimeframeFlow,
        refreshTickFlow
    ) { timeframe, _ ->
        timeframe to computeTimeframeSinceEpochMs(
            timeframe = timeframe,
            nowEpochMs = clock.currentTimeMillis(),
            zoneId = zoneId
        )
    }.distinctUntilChanged().flatMapLatest { (timeframe, sinceEpochMs) ->
        combine(
            listeningRepository.observeSummary(sinceEpochMs),
            listeningRepository.observeTopTracks(sinceEpochMs, limit = TOP_LIST_LIMIT),
            listeningRepository.observeTopArtists(sinceEpochMs, limit = TOP_LIST_LIMIT)
        ) { summary, topTracks, topArtists ->
            StatsUiState(
                selectedTimeframe = timeframe,
                summary = summary,
                topTracks = topTracks,
                topArtists = topArtists,
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

    fun refresh() {
        refreshTickFlow.value += 1L
    }

    companion object {
        private const val TOP_LIST_LIMIT = 25

        fun factory(appContainer: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StatsViewModel(
                        listeningRepository = appContainer.listeningRepository,
                        clock = appContainer.clock
                    ) as T
                }
            }
    }
}
