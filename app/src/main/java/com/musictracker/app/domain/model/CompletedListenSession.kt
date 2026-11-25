package com.musictracker.app.domain.model

data class CompletedListenSession(
    val sourcePackage: String,
    val rawTitle: String,
    val rawArtist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val startedAtEpoch: Long,
    val endedAtEpoch: Long,
    val actualListenedMs: Long,
    val completedRatio: Float,
    val wasSkipped: Boolean
)
