package com.musictracker.app.data.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MusicTrackerExportPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val appVersion: String,
    val trackCount: Int,
    val listenEventCount: Int,
    val tracks: List<ExportTrackDto>,
    val listenEvents: List<ExportListenEventDto>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ExportTrackDto(
    val id: Long,
    val normalizedTitle: String,
    val normalizedArtist: String,
    val album: String,
    val durationMs: Long,
    val rawTitle: String,
    val rawArtist: String,
    val artworkUriOrHash: String?
)

@Serializable
data class ExportListenEventDto(
    val id: Long,
    val trackId: Long,
    val sourcePackage: String,
    val startedAtEpoch: Long,
    val endedAtEpoch: Long,
    val actualListenedMs: Long,
    val completedRatio: Float,
    val wasSkipped: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
fun createExportJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}
