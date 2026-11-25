package com.musictracker.app.domain.model

data class TopArtistStat(
    val artist: String,
    val totalListenedMs: Long,
    val playCount: Int,
    val uniqueTrackCount: Int
)
