package com.musictracker.app.data.clock

import android.os.SystemClock
import com.musictracker.app.domain.clock.TrackerClock

class SystemTrackerClock : TrackerClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
