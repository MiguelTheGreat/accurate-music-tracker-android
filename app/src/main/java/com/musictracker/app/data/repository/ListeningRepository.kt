package com.musictracker.app.data.repository

import com.musictracker.app.domain.model.ArtistDetailStats
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListenEventInsightSample
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.model.TrackDetailStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ListeningRepository {
    suspend fun recordCompletedSession(session: CompletedListenSession)
    fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary>
    fun observeRecentHistory(limit: Int = 50): Flow<List<RecentListenItem>>
    fun observeTopTracks(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopTrackStat>>
    fun observeTopArtists(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopArtistStat>>
    fun observeTrackDetail(trackId: Long, sinceEpochMs: Long): Flow<TrackDetailStats?> = flowOf(null)
    fun observeArtistDetail(artistName: String, sinceEpochMs: Long): Flow<ArtistDetailStats?> = flowOf(null)
    fun observeTopTracksForArtist(
        artistName: String,
        sinceEpochMs: Long,
        limit: Int = 5
    ): Flow<List<TopTrackStat>> = flowOf(emptyList())
    fun observeAllListenEventTimestamps(): Flow<List<ListenEventInsightSample>> = flowOf(emptyList())
    suspend fun renormalizeAllTracks(): Int
}
