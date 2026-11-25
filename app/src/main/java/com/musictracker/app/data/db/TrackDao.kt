package com.musictracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE dedup_key = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: TrackEntity): Long

    @Query(
        """
        UPDATE tracks
        SET
            album = CASE WHEN album = '' AND :album != '' THEN :album ELSE album END,
            duration_ms = CASE WHEN duration_ms <= 0 AND :durationMs > 0 THEN :durationMs ELSE duration_ms END,
            artwork_uri_or_hash = CASE
                WHEN (artwork_uri_or_hash IS NULL OR artwork_uri_or_hash = '')
                     AND :artworkUriOrHash IS NOT NULL AND :artworkUriOrHash != ''
                THEN :artworkUriOrHash
                ELSE artwork_uri_or_hash
            END
        WHERE id = :id
        """
    )
    suspend fun patchMissingMetadata(
        id: Long,
        album: String,
        durationMs: Long,
        artworkUriOrHash: String?
    )

    @Query("SELECT * FROM tracks ORDER BY id ASC")
    suspend fun getAll(): List<TrackEntity>

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET dedup_key = :dedupKey WHERE id = :id")
    suspend fun updateDedupKey(id: Long, dedupKey: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
