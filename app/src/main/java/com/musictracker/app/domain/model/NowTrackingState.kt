package com.musictracker.app.domain.model

data class NowTrackingState(
    val sourcePackage: String,
    val rawTitle: String,
    val rawArtist: String,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val accumulatedMs: Long,
    val segmentStartElapsedMs: Long?,
    val isDebouncing: Boolean
)
