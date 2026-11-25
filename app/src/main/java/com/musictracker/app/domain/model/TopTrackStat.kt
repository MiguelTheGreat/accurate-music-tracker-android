package com.musictracker.app.domain.model

data class TopTrackStat(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val totalListenedMs: Long,
    val playCount: Int
)
