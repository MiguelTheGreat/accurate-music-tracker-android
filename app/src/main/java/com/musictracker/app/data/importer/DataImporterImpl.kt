package com.musictracker.app.data.importer

import androidx.room.withTransaction
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.domain.normalization.AdAndPlaceholderFilter
import com.musictracker.app.domain.normalization.MetadataNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class DataImporterImpl(
    private val database: MusicTrackerDatabase,
    private val normalizer: MetadataNormalizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) : DataImporter {

    private data class ParsedListenRecord(
        val rawTitle: String,
        val rawArtist: String,
        val album: String,
        val durationMs: Long,
        val artworkUri: String?,
        val sourcePackage: String,
        val startedAtEpoch: Long,
        val endedAtEpoch: Long,
        val actualListenedMs: Long,
        val completedRatio: Float,
        val wasSkipped: Boolean
    )

    private data class ParseOutcome(
        val records: List<ParsedListenRecord>,
        val preFilteredCount: Int
    )

    override suspend fun import(format: ImportFormat, inputStream: InputStream): ImportResult =
        withContext(ioDispatcher) {
            try {
                val rawText = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                if (rawText.isBlank()) {
                    return@withContext ImportResult.Error("Selected file is empty")
                }

                val parseOutcome = when (format) {
                    ImportFormat.COLLECTO_JSON -> parseCollectoJson(rawText)
                    ImportFormat.LASTFM_CSV -> parseLastFmCsv(rawText)
                    ImportFormat.LASTFM_JSON -> parseLastFmJson(rawText)
                    ImportFormat.SPOTIFY_EXTENDED_JSON -> parseSpotifyExtendedJson(rawText)
                }

                persistRecords(parseOutcome.records, parseOutcome.preFilteredCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Error(
                    message = e.message ?: "Failed to import ${format.name} data",
                    cause = e
                )
            }
        }

    private suspend fun persistRecords(
        records: List<ParsedListenRecord>,
        preFilteredCount: Int
    ): ImportResult.Success {
        val trackDao = database.trackDao()
        val listenEventDao = database.listenEventDao()

        var importedEvents = 0
        var skippedDuplicates = 0
        var skippedFiltered = preFilteredCount
        val newlyCreatedTrackIds = HashSet<Long>()
        val trackIdCache = HashMap<String, Long>()

        database.withTransaction {
            val existingEventsByTrackId = HashMap<Long, MutableList<Long>>()
            for (existing in listenEventDao.getAll()) {
                existingEventsByTrackId
                    .getOrPut(existing.trackId) { mutableListOf() }
                    .add(existing.startedAtEpoch)
            }

            for (record in records) {
                if (record.rawTitle.isBlank() || record.actualListenedMs <= 0L) {
                    skippedFiltered++
                    continue
                }
                if (AdAndPlaceholderFilter.isAdOrPlaceholder(record.rawTitle, record.rawArtist, record.album)) {
                    skippedFiltered++
                    continue
                }

                val normalized = normalizer.normalize(record.rawTitle, record.rawArtist)
                val cleanAlbum = record.album.trim()
                val cleanDuration = record.durationMs.coerceAtLeast(0L)
                val cleanArtwork = record.artworkUri?.trim()?.takeIf { it.isNotEmpty() }

                val trackId = trackIdCache[normalized.dedupKey] ?: run {
                    val candidate = TrackEntity(
                        dedupKey = normalized.dedupKey,
                        normalizedTitle = normalized.normalizedTitle,
                        normalizedArtist = normalized.normalizedArtist,
                        album = cleanAlbum,
                        durationMs = cleanDuration,
                        rawTitle = record.rawTitle,
                        rawArtist = record.rawArtist,
                        artworkUriOrHash = cleanArtwork
                    )
                    val insertedId = trackDao.insertIgnore(candidate)
                    val resolvedId = if (insertedId != -1L) {
                        newlyCreatedTrackIds.add(insertedId)
                        insertedId
                    } else {
                        val existing = checkNotNull(trackDao.findByDedupKey(normalized.dedupKey)) {
                            "Expected existing track for dedupKey=${normalized.dedupKey}"
                        }
                        if (cleanAlbum.isNotEmpty() || cleanDuration > 0L || cleanArtwork != null) {
                            trackDao.patchMissingMetadata(
                                id = existing.id,
                                album = cleanAlbum,
                                durationMs = cleanDuration,
                                artworkUriOrHash = cleanArtwork
                            )
                        }
                        existing.id
                    }
                    trackIdCache[normalized.dedupKey] = resolvedId
                    resolvedId
                }

                val timestamps = existingEventsByTrackId.getOrPut(trackId) { mutableListOf() }
                val isDuplicate = timestamps.any { existingEpoch ->
                    abs(existingEpoch - record.startedAtEpoch) <= DEDUP_WINDOW_MS
                }
                if (isDuplicate) {
                    skippedDuplicates++
                    continue
                }

                val safeListenedMs = record.actualListenedMs.coerceAtLeast(0L)
                val safeRatio = record.completedRatio.takeIf { it.isFinite() && it >= 0f } ?: 0f
                val safeEndedAt = record.endedAtEpoch.coerceAtLeast(record.startedAtEpoch)

                listenEventDao.insert(
                    ListenEventEntity(
                        trackId = trackId,
                        sourcePackage = record.sourcePackage,
                        startedAtEpoch = record.startedAtEpoch,
                        endedAtEpoch = safeEndedAt,
                        actualListenedMs = safeListenedMs,
                        completedRatio = safeRatio,
                        wasSkipped = record.wasSkipped
                    )
                )
                timestamps.add(record.startedAtEpoch)
                importedEvents++
            }
        }

        return ImportResult.Success(
            importedTracks = newlyCreatedTrackIds.size,
            importedEvents = importedEvents,
            skippedDuplicates = skippedDuplicates,
            skippedFiltered = skippedFiltered
        )
    }

    private fun parseCollectoJson(rawText: String): ParseOutcome {
        val payload = json.decodeFromString(MusicTrackerExportPayload.serializer(), rawText)
        val tracksById = payload.tracks.associateBy { it.id }
        val records = ArrayList<ParsedListenRecord>(payload.listenEvents.size)
        var preFiltered = 0

        for (event in payload.listenEvents) {
            val track = tracksById[event.trackId]
            if (track == null) {
                preFiltered++
                continue
            }
            val rawTitle = track.rawTitle.ifBlank { track.normalizedTitle }
            val rawArtist = track.rawArtist.ifBlank { track.normalizedArtist }
            records.add(
                ParsedListenRecord(
                    rawTitle = rawTitle,
                    rawArtist = rawArtist,
                    album = track.album,
                    durationMs = track.durationMs,
                    artworkUri = track.artworkUriOrHash,
                    sourcePackage = event.sourcePackage.ifBlank { COLLECTO_IMPORT_SOURCE },
                    startedAtEpoch = event.startedAtEpoch,
                    endedAtEpoch = event.endedAtEpoch,
                    actualListenedMs = event.actualListenedMs,
                    completedRatio = event.completedRatio,
                    wasSkipped = event.wasSkipped
                )
            )
        }

        return ParseOutcome(records = records, preFilteredCount = preFiltered)
    }

    private fun parseSpotifyExtendedJson(rawText: String): ParseOutcome {
        val entries = json.decodeFromString<List<SpotifyHistoryEntry>>(rawText)
        val records = ArrayList<ParsedListenRecord>(entries.size)
        var preFiltered = 0

        for (entry in entries) {
            if (entry.episodeName != null || entry.trackName.isNullOrBlank()) {
                preFiltered++
                continue
            }
            if (entry.msPlayed < SPOTIFY_MIN_PLAY_MS) {
                preFiltered++
                continue
            }
            val endEpochMs = entry.ts?.let { parseIsoInstant(it) }
            if (endEpochMs == null) {
                preFiltered++
                continue
            }
            val startEpochMs = (endEpochMs - entry.msPlayed).coerceAtLeast(0L)
            records.add(
                ParsedListenRecord(
                    rawTitle = entry.trackName,
                    rawArtist = entry.artistName.orEmpty(),
                    album = entry.albumName.orEmpty(),
                    durationMs = 0L,
                    artworkUri = null,
                    sourcePackage = SPOTIFY_SOURCE_PACKAGE,
                    startedAtEpoch = startEpochMs,
                    endedAtEpoch = endEpochMs,
                    actualListenedMs = entry.msPlayed,
                    completedRatio = 1.0f,
                    wasSkipped = entry.skipped == true
                )
            )
        }

        return ParseOutcome(records = records, preFilteredCount = preFiltered)
    }

    private fun parseLastFmCsv(rawText: String): ParseOutcome {
        val rows = parseRfc4180Csv(rawText)
        if (rows.isEmpty()) {
            return ParseOutcome(records = emptyList(), preFilteredCount = 0)
        }

        val firstRowLower = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val hasHeader = firstRowLower.contains("track") || firstRowLower.contains("artist") || firstRowLower.contains("uts")

        val dataRows: List<List<String>>
        val artistIdx: Int
        val albumIdx: Int
        val trackIdx: Int
        val utsIdx: Int
        val dateIdx: Int

        if (hasHeader) {
            dataRows = rows.drop(1)
            artistIdx = firstRowLower.indexOfFirst { it == "artist" || it == "artist_name" }
            albumIdx = firstRowLower.indexOfFirst { it == "album" || it == "album_name" }
            trackIdx = firstRowLower.indexOfFirst { it == "track" || it == "track_name" || it == "song" }
            utsIdx = firstRowLower.indexOfFirst { it == "uts" || it == "timestamp" }
            dateIdx = firstRowLower.indexOfFirst { it == "date" || it == "utc_time" || it == "date_uts" }
        } else {
            dataRows = rows
            artistIdx = 0
            albumIdx = 1
            trackIdx = 2
            utsIdx = -1
            dateIdx = 3
        }

        val records = ArrayList<ParsedListenRecord>(dataRows.size)
        var preFiltered = 0

        for (row in dataRows) {
            if (row.all { it.isBlank() }) continue
            val track = row.getOrNull(trackIdx)?.trim().orEmpty()
            val artist = row.getOrNull(artistIdx)?.trim().orEmpty()
            val album = row.getOrNull(albumIdx)?.trim().orEmpty()
            val rawUts = row.getOrNull(utsIdx)?.trim()
            val rawDate = row.getOrNull(dateIdx)?.trim()

            if (track.isBlank()) {
                preFiltered++
                continue
            }

            val startedAtEpoch = parseFlexibleTimestamp(rawUts) ?: parseFlexibleTimestamp(rawDate)
            if (startedAtEpoch == null) {
                preFiltered++
                continue
            }

            records.add(
                ParsedListenRecord(
                    rawTitle = track,
                    rawArtist = artist,
                    album = album,
                    durationMs = DEFAULT_LASTFM_DURATION_MS,
                    artworkUri = null,
                    sourcePackage = LASTFM_SOURCE_PACKAGE,
                    startedAtEpoch = startedAtEpoch,
                    endedAtEpoch = startedAtEpoch + DEFAULT_LASTFM_DURATION_MS,
                    actualListenedMs = DEFAULT_LASTFM_DURATION_MS,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        }

        return ParseOutcome(records = records, preFilteredCount = preFiltered)
    }

    private fun parseLastFmJson(rawText: String): ParseOutcome {
        val root = json.parseToJsonElement(rawText)
        val trackElements: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val recentTracks = root["recenttracks"] as? JsonObject
                val fromRecent = recentTracks?.get("track") as? JsonArray
                val fromTopTrack = root["track"] as? JsonArray
                val fromTopTracks = root["tracks"] as? JsonArray
                fromRecent ?: fromTopTrack ?: fromTopTracks
                    ?: throw IllegalArgumentException("Unrecognized Last.fm JSON structure")
            }
            else -> throw IllegalArgumentException("Expected JSON array or object for Last.fm import")
        }

        val records = ArrayList<ParsedListenRecord>(trackElements.size)
        var preFiltered = 0

        for (element in trackElements) {
            val dto = runCatching {
                json.decodeFromJsonElement(LastFmTrackDto.serializer(), element)
            }.getOrNull()
            if (dto == null) {
                preFiltered++
                continue
            }

            val title = (dto.name ?: dto.track)?.trim().orEmpty()
            val artist = extractLastFmText(dto.artist).orEmpty()
            val album = extractLastFmText(dto.album).orEmpty()
            val startedAtEpoch = extractLastFmTimestamp(dto)

            if (title.isBlank() || startedAtEpoch == null) {
                preFiltered++
                continue
            }

            records.add(
                ParsedListenRecord(
                    rawTitle = title,
                    rawArtist = artist,
                    album = album,
                    durationMs = DEFAULT_LASTFM_DURATION_MS,
                    artworkUri = null,
                    sourcePackage = LASTFM_SOURCE_PACKAGE,
                    startedAtEpoch = startedAtEpoch,
                    endedAtEpoch = startedAtEpoch + DEFAULT_LASTFM_DURATION_MS,
                    actualListenedMs = DEFAULT_LASTFM_DURATION_MS,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        }

        return ParseOutcome(records = records, preFilteredCount = preFiltered)
    }

    private fun extractLastFmText(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()
        is JsonObject -> {
            (element["#text"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: (element["name"] as? JsonPrimitive)?.contentOrNull?.trim()
        }
        else -> null
    }

    private fun extractLastFmTimestamp(dto: LastFmTrackDto): Long? {
        dto.timestamp?.let { return normalizeEpochMillis(it) }
        (dto.uts as? JsonPrimitive)?.contentOrNull?.let { parseFlexibleTimestamp(it)?.let { ts -> return ts } }

        return when (val date = dto.date) {
            is JsonPrimitive -> parseFlexibleTimestamp(date.contentOrNull)
            is JsonObject -> {
                val uts = (date["uts"] as? JsonPrimitive)?.contentOrNull
                val text = (date["#text"] as? JsonPrimitive)?.contentOrNull
                parseFlexibleTimestamp(uts) ?: parseFlexibleTimestamp(text)
            }
            else -> null
        }
    }

    private fun parseIsoInstant(value: String): Long? =
        runCatching { Instant.parse(value.trim()).toEpochMilli() }.getOrNull()

    private fun parseFlexibleTimestamp(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { numeric ->
            return normalizeEpochMillis(numeric)
        }
        parseIsoInstant(value)?.let { return it }

        for (formatter in LASTFM_DATE_FORMATTERS) {
            val parsed = runCatching {
                LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun normalizeEpochMillis(rawEpoch: Long): Long {
        if (rawEpoch <= 0L) return rawEpoch
        // Unix timestamps in seconds are ~10 digits (< 10_000_000_000L until year 2286)
        return if (rawEpoch < 10_000_000_000L) rawEpoch * 1_000L else rawEpoch
    }

    internal fun parseRfc4180Csv(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val length = content.length

        // Strip UTF-8 BOM if present
        if (length > 0 && content[0] == '\uFEFF') {
            i = 1
        }

        while (i < length) {
            val c = content[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && content[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(c)
                    i++
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    ',' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        i++
                    }
                    '\r' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow.toList())
                        currentRow.clear()
                        i += if (i + 1 < length && content[i + 1] == '\n') 2 else 1
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow.toList())
                        currentRow.clear()
                        i++
                    }
                    else -> {
                        field.append(c)
                        i++
                    }
                }
            }
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow.toList())
        }

        return rows
    }

    companion object {
        internal const val DEDUP_WINDOW_MS = 5_000L
        internal const val SPOTIFY_MIN_PLAY_MS = 10_000L
        internal const val DEFAULT_LASTFM_DURATION_MS = 180_000L
        private const val COLLECTO_IMPORT_SOURCE = "com.musictracker.app.import"
        private const val SPOTIFY_SOURCE_PACKAGE = "com.spotify.music"
        private const val LASTFM_SOURCE_PACKAGE = "fm.last.android"

        private val LASTFM_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        )
    }
}
