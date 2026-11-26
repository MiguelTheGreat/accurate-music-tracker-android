package com.musictracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import kotlinx.coroutines.flow.Flow

data class ListeningSummaryRow(
    val totalListenedMs: Long,
    val playCount: Int,
    val skippedCount: Int
)

@Dao
interface ListenEventDao {
    @Insert
    suspend fun insert(event: ListenEventEntity): Long

    @Query("SELECT * FROM listen_events ORDER BY started_at_epoch ASC, id ASC")
    suspend fun getAll(): List<ListenEventEntity>

    @Query("UPDATE listen_events SET track_id = :newTrackId WHERE track_id = :oldTrackId")
    suspend fun reassignTrackId(oldTrackId: Long, newTrackId: Long): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(actual_listened_ms), 0) AS totalListenedMs,
            COUNT(*) AS playCount,
            COALESCE(SUM(CASE WHEN was_skipped = 1 THEN 1 ELSE 0 END), 0) AS skippedCount
        FROM listen_events
        WHERE started_at_epoch >= :sinceEpochMs
        """
    )
    fun observeSummaryRow(sinceEpochMs: Long): Flow<ListeningSummaryRow>

    @Query(
        """
        SELECT
            e.id AS eventId,
            e.track_id AS trackId,
            t.normalized_title AS title,
            t.normalized_artist AS artist,
            t.album AS album,
            t.artwork_uri_or_hash AS artworkUri,
            e.source_package AS sourcePackage,
            e.started_at_epoch AS startedAtEpoch,
            e.ended_at_epoch AS endedAtEpoch,
            e.actual_listened_ms AS actualListenedMs,
            e.completed_ratio AS completedRatio,
            e.was_skipped AS wasSkipped
        FROM listen_events e
        INNER JOIN tracks t ON e.track_id = t.id
        ORDER BY e.started_at_epoch DESC, e.id DESC
        LIMIT :limit
        """
    )
    fun observeRecentHistory(limit: Int): Flow<List<RecentListenItem>>

    @Query(
        """
        SELECT
            t.id AS trackId,
            t.normalized_title AS title,
            t.normalized_artist AS artist,
            t.album AS album,
            t.artwork_uri_or_hash AS artworkUri,
            COALESCE(SUM(e.actual_listened_ms), 0) AS totalListenedMs,
            COUNT(e.id) AS playCount
        FROM listen_events e
        INNER JOIN tracks t ON e.track_id = t.id
        WHERE e.started_at_epoch >= :sinceEpochMs
        GROUP BY t.id
        ORDER BY totalListenedMs DESC, playCount DESC, t.id ASC
        LIMIT :limit
        """
    )
    fun observeTopTracks(sinceEpochMs: Long, limit: Int): Flow<List<TopTrackStat>>

    @Query(
        """
        SELECT
            MIN(t.normalized_artist) AS artist,
            COALESCE(SUM(e.actual_listened_ms), 0) AS totalListenedMs,
            COUNT(e.id) AS playCount,
            COUNT(DISTINCT t.id) AS uniqueTrackCount
        FROM listen_events e
        INNER JOIN tracks t ON e.track_id = t.id
        WHERE e.started_at_epoch >= :sinceEpochMs
        GROUP BY CASE WHEN INSTR(t.dedup_key, '::') > 1 THEN SUBSTR(t.dedup_key, 1, INSTR(t.dedup_key, '::') - 1) ELSE LOWER(t.normalized_artist) END
        ORDER BY totalListenedMs DESC, playCount DESC, artist ASC
        LIMIT :limit
        """
    )
    fun observeTopArtists(sinceEpochMs: Long, limit: Int): Flow<List<TopArtistStat>>
}
