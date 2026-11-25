package com.musictracker.app.domain.model

enum class PlaybackStatus {
    NONE,
    STOPPED,
    PAUSED,
    PLAYING,
    FAST_FORWARDING,
    REWINDING,
    BUFFERING,
    ERROR,
    CONNECTING,
    SKIPPING_TO_PREVIOUS,
    SKIPPING_TO_NEXT,
    SKIPPING_TO_QUEUE_ITEM
}

data class PlaybackStateSnapshot(
    val status: PlaybackStatus,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val lastPositionUpdateTimeMs: Long = 0L
)
