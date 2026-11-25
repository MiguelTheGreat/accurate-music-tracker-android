package com.musictracker.app.domain.model

sealed interface PlaybackEvent {
    data class MetadataChanged(
        val sourcePackage: String,
        val metadata: MediaMetadataSnapshot
    ) : PlaybackEvent

    data class StateChanged(
        val sourcePackage: String,
        val state: PlaybackStateSnapshot
    ) : PlaybackEvent

    data class SessionSnapshot(
        val sourcePackage: String,
        val metadata: MediaMetadataSnapshot?,
        val state: PlaybackStateSnapshot?
    ) : PlaybackEvent

    data object Tick : PlaybackEvent
}
