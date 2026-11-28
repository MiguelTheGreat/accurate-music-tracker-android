package com.musictracker.app.data.importer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.InputStream

enum class ImportFormat {
    COLLECTO_JSON,
    LASTFM_CSV,
    LASTFM_JSON,
    SPOTIFY_EXTENDED_JSON
}

sealed interface ImportResult {
    data class Success(
        val importedTracks: Int,
        val importedEvents: Int,
        val skippedDuplicates: Int,
        val skippedFiltered: Int
    ) : ImportResult

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ImportResult
}

interface DataImporter {
    suspend fun import(format: ImportFormat, inputStream: InputStream): ImportResult
}

@Serializable
internal data class SpotifyHistoryEntry(
    val ts: String? = null,
    @SerialName("ms_played")
    val msPlayed: Long = 0L,
    @SerialName("master_metadata_track_name")
    val trackName: String? = null,
    @SerialName("master_metadata_album_artist_name")
    val artistName: String? = null,
    @SerialName("master_metadata_album_album_name")
    val albumName: String? = null,
    @SerialName("episode_name")
    val episodeName: String? = null,
    val skipped: Boolean? = null
)

@Serializable
internal data class LastFmTrackDto(
    val name: String? = null,
    val track: String? = null,
    val artist: JsonElement? = null,
    val album: JsonElement? = null,
    val date: JsonElement? = null,
    val uts: JsonElement? = null,
    val timestamp: Long? = null
)
