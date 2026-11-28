package com.musictracker.app.ui.screens.stats

import androidx.compose.runtime.Immutable
import com.musictracker.app.domain.model.ArtistDetailStats
import com.musictracker.app.domain.model.DiscoveryTag
import com.musictracker.app.domain.model.ListeningInsights
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.RankDelta
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.SummaryDelta
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.model.TrackDetailStats

@Immutable
data class StatsUiState(
    val selectedTimeframe: StatsTimeframe = StatsTimeframe.TODAY,
    val summary: ListeningSummary = ListeningSummary(),
    val summaryDelta: SummaryDelta? = null,
    val topTracks: List<TopTrackStat> = emptyList(),
    val topArtists: List<TopArtistStat> = emptyList(),
    val blendTopTracks: List<TopTrackStat> = emptyList(),
    val blendTopArtists: List<TopArtistStat> = emptyList(),
    val trackRankDeltas: Map<Long, RankDelta> = emptyMap(),
    val artistRankDeltas: Map<String, RankDelta> = emptyMap(),
    val insights: ListeningInsights = ListeningInsights(),
    val drillDownTarget: DrillDownTarget? = null,
    val selectedTrackDetail: TrackDetailStats? = null,
    val selectedArtistDetail: ArtistDetailStats? = null,
    val selectedArtistTopTracks: List<TopTrackStat> = emptyList(),
    val selectedDrillDownFirstListenEpoch: Long? = null,
    val selectedDrillDownDiscoveryTag: DiscoveryTag? = null,
    val artistArtworkPaths: Map<String, String> = emptyMap(),
    val trackArtworkPaths: Map<Long, String> = emptyMap(),
    val isLoading: Boolean = true
)
