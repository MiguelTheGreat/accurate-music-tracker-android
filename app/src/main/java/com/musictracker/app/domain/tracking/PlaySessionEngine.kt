package com.musictracker.app.domain.tracking

import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import kotlinx.coroutines.flow.StateFlow

interface PlaySessionEngine {
    val nowTracking: StateFlow<NowTrackingState?>
    fun onPlaybackEvent(event: PlaybackEvent): List<CompletedListenSession>
    fun onSessionEnded(sourcePackage: String): List<CompletedListenSession>
    fun flushActiveSession(): List<CompletedListenSession>
}
