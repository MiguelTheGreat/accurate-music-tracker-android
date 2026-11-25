package com.musictracker.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["dedup_key"], unique = true, name = "index_tracks_dedup_key"),
        Index(value = ["normalized_artist"], name = "index_tracks_normalized_artist")
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "dedup_key")
    val dedupKey: String,

    @ColumnInfo(name = "normalized_title")
    val normalizedTitle: String,

    @ColumnInfo(name = "normalized_artist")
    val normalizedArtist: String,

    @ColumnInfo(name = "album")
    val album: String = "",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "raw_title")
    val rawTitle: String,

    @ColumnInfo(name = "raw_artist")
    val rawArtist: String,

    @ColumnInfo(name = "artwork_uri_or_hash")
    val artworkUriOrHash: String? = null
)
