package com.musictracker.app.domain.model

data class RecentListenItem(
    val eventId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val sourcePackage: String,
    val startedAtEpoch: Long,
    val endedAtEpoch: Long,
    val actualListenedMs: Long,
    val completedRatio: Float,
    val wasSkipped: Boolean
)
