package com.musictracker.app.domain.model

data class MediaMetadataSnapshot(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    val isAdvertisement: Boolean = false
)
