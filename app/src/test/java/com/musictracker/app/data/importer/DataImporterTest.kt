package com.musictracker.app.data.importer

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.export.ExportListenEventDto
import com.musictracker.app.data.export.ExportTrackDto
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.domain.normalization.CanonicalMetadataNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataImporterTest {

    private lateinit var database: MusicTrackerDatabase
    private lateinit var importer: DataImporterImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = DataImporterImpl(
            database = database,
            normalizer = CanonicalMetadataNormalizer(),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importCollectoJson_importsTracksAndEvents() = runTest {
        val payload = MusicTrackerExportPayload(
            exportedAtEpochMs = 1_750_000_000_000L,
            appVersion = "2.5.0",
            trackCount = 2,
            listenEventCount = 2,
            tracks = listOf(
                ExportTrackDto(
                    id = 10L,
                    normalizedTitle = "Blinding Lights",
                    normalizedArtist = "The Weeknd",
                    album = "After Hours",
                    durationMs = 200_000L,
                    rawTitle = "Blinding Lights",
                    rawArtist = "The Weeknd",
                    artworkUriOrHash = null
                ),
                ExportTrackDto(
                    id = 20L,
                    normalizedTitle = "Instant Crush",
                    normalizedArtist = "Daft Punk",
                    album = "Random Access Memories",
                    durationMs = 337_000L,
                    rawTitle = "Instant Crush",
                    rawArtist = "Daft Punk",
                    artworkUriOrHash = null
                )
            ),
            listenEvents = listOf(
                ExportListenEventDto(
                    id = 1L,
                    trackId = 10L,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 1_750_000_100_000L,
                    endedAtEpoch = 1_750_000_300_000L,
                    actualListenedMs = 200_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                ),
                ExportListenEventDto(
                    id = 2L,
                    trackId = 20L,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 1_750_000_400_000L,
                    endedAtEpoch = 1_750_000_737_000L,
                    actualListenedMs = 337_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        )
        val jsonText = createExportJson().encodeToString(MusicTrackerExportPayload.serializer(), payload)

        val result = importer.import(
            format = ImportFormat.COLLECTO_JSON,
            inputStream = jsonText.toInputStream()
        )

        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.importedTracks)
        assertEquals(2, success.importedEvents)
        assertEquals(0, success.skippedDuplicates)
        assertEquals(0, success.skippedFiltered)

        assertEquals(2, database.trackDao().getAll().size)
        assertEquals(2, database.listenEventDao().getAll().size)
    }

    @Test
    fun importLastFmCsv_parsesRfc4180QuotedFieldsWithCommasAndEscapedQuotes() = runTest {
        val csv = buildString {
            appendLine("artist,album,track,uts")
            appendLine("\"Earth, Wind & Fire\",\"I Am\",\"Boogie \"\"Wonderland\"\"\",1700000000")
            appendLine("\"Tyler, The Creator\",\"IGOR\",\"EARFQUAKE\",1700000500")
        }

        val result = importer.import(
            format = ImportFormat.LASTFM_CSV,
            inputStream = csv.toInputStream()
        )

        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.importedTracks)
        assertEquals(2, success.importedEvents)
        assertEquals(0, success.skippedDuplicates)
        assertEquals(0, success.skippedFiltered)

        val tracks = database.trackDao().getAll()
        assertEquals(2, tracks.size)
        assertTrue(tracks.any { it.rawArtist == "Earth, Wind & Fire" && it.rawTitle == "Boogie \"Wonderland\"" })
        assertTrue(tracks.any { it.rawArtist == "Tyler, The Creator" && it.rawTitle == "EARFQUAKE" })
    }

    @Test
    fun importSpotifyExtendedJson_filtersPodcastsAndShortSkips() = runTest {
        val spotifyJson = """
            [
              {
                "ts": "2026-06-15T12:00:00Z",
                "ms_played": 210000,
                "master_metadata_track_name": "Starboy",
                "master_metadata_album_artist_name": "The Weeknd",
                "master_metadata_album_album_name": "Starboy",
                "episode_name": null,
                "skipped": false
              },
              {
                "ts": "2026-06-15T12:05:00Z",
                "ms_played": 9999,
                "master_metadata_track_name": "Quick Skip",
                "master_metadata_album_artist_name": "The Weeknd",
                "master_metadata_album_album_name": "Starboy",
                "episode_name": null,
                "skipped": true
              },
              {
                "ts": "2026-06-15T12:10:00Z",
                "ms_played": 1800000,
                "master_metadata_track_name": null,
                "master_metadata_album_artist_name": null,
                "master_metadata_album_album_name": null,
                "episode_name": "Podcast Episode 42",
                "skipped": false
              }
            ]
        """.trimIndent()

        val result = importer.import(
            format = ImportFormat.SPOTIFY_EXTENDED_JSON,
            inputStream = spotifyJson.toInputStream()
        )

        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(1, success.importedTracks)
        assertEquals(1, success.importedEvents)
        assertEquals(0, success.skippedDuplicates)
        assertEquals(2, success.skippedFiltered)

        val events = database.listenEventDao().getAll()
        assertEquals(1, events.size)
        assertEquals(210_000L, events.first().actualListenedMs)
    }

    @Test
    fun import_deduplicatesWithin5SecondsAndAllowsEventsBeyond5Seconds() = runTest {
        val baseEpochMs = 1_750_000_000_000L
        val firstCsv = buildString {
            appendLine("artist,album,track,uts")
            appendLine("Daft Punk,Discovery,One More Time,$baseEpochMs")
        }

        val firstResult = importer.import(
            format = ImportFormat.LASTFM_CSV,
            inputStream = firstCsv.toInputStream()
        ) as ImportResult.Success
        assertEquals(1, firstResult.importedTracks)
        assertEquals(1, firstResult.importedEvents)

        // Second import: exact match (0ms), +4000ms (within 5s), -5000ms (boundary within 5s), +5001ms (outside 5s)
        val secondCsv = buildString {
            appendLine("artist,album,track,uts")
            appendLine("Daft Punk,Discovery,One More Time,$baseEpochMs")
            appendLine("Daft Punk,Discovery,One More Time,${baseEpochMs + 4_000L}")
            appendLine("Daft Punk,Discovery,One More Time,${baseEpochMs - 5_000L}")
            appendLine("Daft Punk,Discovery,One More Time,${baseEpochMs + 5_001L}")
        }

        val secondResult = importer.import(
            format = ImportFormat.LASTFM_CSV,
            inputStream = secondCsv.toInputStream()
        ) as ImportResult.Success

        assertEquals(0, secondResult.importedTracks)
        assertEquals(1, secondResult.importedEvents)
        assertEquals(3, secondResult.skippedDuplicates)
        assertEquals(2, database.listenEventDao().getAll().size)
    }

    private fun String.toInputStream(): ByteArrayInputStream =
        ByteArrayInputStream(this.toByteArray(StandardCharsets.UTF_8))
}
