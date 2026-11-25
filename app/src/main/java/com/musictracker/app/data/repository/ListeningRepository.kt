package com.musictracker.app.data.repository

import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import kotlinx.coroutines.flow.Flow

interface ListeningRepository {
    suspend fun recordCompletedSession(session: CompletedListenSession)
    fun observeSummary(sinceEpochMs: Long): Flow<ListeningSummary>
    fun observeRecentHistory(limit: Int = 50): Flow<List<RecentListenItem>>
    fun observeTopTracks(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopTrackStat>>
    fun observeTopArtists(sinceEpochMs: Long, limit: Int = 25): Flow<List<TopArtistStat>>
    suspend fun renormalizeAllTracks(): Int
}
