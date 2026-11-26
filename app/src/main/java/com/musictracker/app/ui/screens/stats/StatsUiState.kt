package com.musictracker.app.ui.screens.stats

import androidx.compose.runtime.Immutable
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat

@Immutable
data class StatsUiState(
    val selectedTimeframe: StatsTimeframe = StatsTimeframe.TODAY,
    val summary: ListeningSummary = ListeningSummary(),
    val topTracks: List<TopTrackStat> = emptyList(),
    val topArtists: List<TopArtistStat> = emptyList(),
    val isLoading: Boolean = true
)
