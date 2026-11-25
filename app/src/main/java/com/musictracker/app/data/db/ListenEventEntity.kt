package com.musictracker.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "listen_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["track_id"], name = "index_listen_events_track_id"),
        Index(value = ["started_at_epoch"], name = "index_listen_events_started_at_epoch")
    ]
)
data class ListenEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "track_id")
    val trackId: Long,

    @ColumnInfo(name = "source_package")
    val sourcePackage: String,

    @ColumnInfo(name = "started_at_epoch")
    val startedAtEpoch: Long,

    @ColumnInfo(name = "ended_at_epoch")
    val endedAtEpoch: Long,

    @ColumnInfo(name = "actual_listened_ms")
    val actualListenedMs: Long,

    @ColumnInfo(name = "completed_ratio")
    val completedRatio: Float,

    @ColumnInfo(name = "was_skipped")
    val wasSkipped: Boolean
)
