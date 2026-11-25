package com.musictracker.app.data.export

import androidx.room.withTransaction
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.ExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

class DataExporterImpl(
    private val database: MusicTrackerDatabase,
    private val dbFileProvider: () -> File,
    private val clock: TrackerClock,
    private val appVersion: String = "1.0.0",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = createExportJson()
) : DataExporter {

    override suspend fun exportJson(outputStream: OutputStream): ExportResult =
        withContext(ioDispatcher) {
            try {
                val (tracks, events) = database.withTransaction {
                    database.trackDao().getAll() to database.listenEventDao().getAll()
                }

                val payload = MusicTrackerExportPayload(
                    schemaVersion = MusicTrackerExportPayload.CURRENT_SCHEMA_VERSION,
                    exportedAtEpochMs = clock.currentTimeMillis(),
                    appVersion = appVersion,
                    trackCount = tracks.size,
                    listenEventCount = events.size,
                    tracks = tracks.map(TrackEntity::toExportDto),
                    listenEvents = events.map(ListenEventEntity::toExportDto)
                )

                val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                outputStream.write(bytes)
                outputStream.flush()

                ExportResult.Success(
                    bytesWritten = bytes.size.toLong(),
                    trackCount = tracks.size,
                    listenEventCount = events.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportResult.Error(
                    message = e.message ?: "JSON export failed",
                    cause = e
                )
            }
        }

    override suspend fun exportSqliteBackup(outputStream: OutputStream): ExportResult =
        withContext(ioDispatcher) {
            try {
                val (tracks, events) = database.withTransaction {
                    database.trackDao().getAll() to database.listenEventDao().getAll()
                }

                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { cursor ->
                        cursor.moveToFirst()
                    }

                val dbFile = dbFileProvider()
                if (!dbFile.exists() || !dbFile.isFile) {
                    return@withContext ExportResult.Error(
                        message = "Database file not found: ${dbFile.absolutePath}"
                    )
                }

                val bytesWritten = FileInputStream(dbFile).buffered().use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()

                ExportResult.Success(
                    bytesWritten = bytesWritten,
                    trackCount = tracks.size,
                    listenEventCount = events.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportResult.Error(
                    message = e.message ?: "SQLite backup failed",
                    cause = e
                )
            }
        }
}

private fun TrackEntity.toExportDto(): ExportTrackDto = ExportTrackDto(
    id = id,
    normalizedTitle = normalizedTitle,
    normalizedArtist = normalizedArtist,
    album = album,
    durationMs = durationMs,
    rawTitle = rawTitle,
    rawArtist = rawArtist,
    artworkUriOrHash = artworkUriOrHash
)

private fun ListenEventEntity.toExportDto(): ExportListenEventDto = ExportListenEventDto(
    id = id,
    trackId = trackId,
    sourcePackage = sourcePackage,
    startedAtEpoch = startedAtEpoch,
    endedAtEpoch = endedAtEpoch,
    actualListenedMs = actualListenedMs,
    completedRatio = completedRatio,
    wasSkipped = wasSkipped
)
