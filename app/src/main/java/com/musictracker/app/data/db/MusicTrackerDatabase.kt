package com.musictracker.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, ListenEventEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MusicTrackerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun listenEventDao(): ListenEventDao

    companion object {
        const val DATABASE_NAME = "music_tracker.db"
    }
}
