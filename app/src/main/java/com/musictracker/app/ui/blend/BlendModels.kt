package com.musictracker.app.ui.blend

import kotlinx.serialization.Serializable

@Serializable
data class BlendArtistEntry(
    val name: String,
    val plays: Int
)

@Serializable
data class BlendTrackEntry(
    val title: String,
    val artist: String,
    val plays: Int
)

@Serializable
data class BlendPayload(
    val v: Int = 1,
    val name: String = "Collecto Listener",
    val artists: List<BlendArtistEntry> = emptyList(),
    val tracks: List<BlendTrackEntry> = emptyList()
)

data class BlendComparisonResult(
    val matchPercent: Int,
    val sharedArtists: List<String>,
    val sharedTracks: List<String>,
    val myTopArtist: String?,
    val friendTopArtist: String?,
    val vibeLabel: String
)
