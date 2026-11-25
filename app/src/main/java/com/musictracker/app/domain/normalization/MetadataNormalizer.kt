package com.musictracker.app.domain.normalization

data class NormalizedTrack(
    val normalizedTitle: String,
    val normalizedArtist: String,
    val dedupKey: String
)

interface MetadataNormalizer {
    fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack
}
