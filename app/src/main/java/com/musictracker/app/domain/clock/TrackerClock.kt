package com.musictracker.app.domain.clock

interface TrackerClock {
    fun elapsedRealtimeMs(): Long
    fun currentTimeMillis(): Long
}
