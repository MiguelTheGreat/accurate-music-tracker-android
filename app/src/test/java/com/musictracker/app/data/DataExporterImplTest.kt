package com.musictracker.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.export.DataExporterImpl
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.ExportResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataExporterImplTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var inMemoryDb: MusicTrackerDatabase
    private val fakeClock = object : TrackerClock {
        override fun elapsedRealtimeMs(): Long = 50_000L
        override fun currentTimeMillis(): Long = 1_764_070_389_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        inMemoryDb.close()
    }

    @Test
    fun exportJson_writesPrettyPrintedUtf8JsonWithValidRoundTripAndExactCounts() = runTest {
        val track1Id = inMemoryDb.trackDao().insertIgnore(
            TrackEntity(
                dedupKey = "the weeknd::blinding lights",
                normalizedTitle = "Blinding Lights",
                normalizedArtist = "The Weeknd",
                album = "After Hours",
                durationMs = 200_000L,
                rawTitle = "Blinding Lights (Official Audio)",
                rawArtist = "The Weeknd - Topic",
                artworkUriOrHash = "content://art/1"
            )
        )
        val track2Id = inMemoryDb.trackDao().insertIgnore(
            TrackEntity(
                dedupKey = "daft punk::voyager",
                normalizedTitle = "Voyager",
                normalizedArtist = "Daft Punk",
                album = "Discovery",
                durationMs = 227_000L,
                rawTitle = "Voyager",
                rawArtist = "Daft Punk",
                artworkUriOrHash = null
            )
        )
        inMemoryDb.listenEventDao().insert(
            ListenEventEntity(
                trackId = track1Id,
                sourcePackage = "com.google.android.apps.youtube.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 201_000L,
                actualListenedMs = 200_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )
        inMemoryDb.listenEventDao().insert(
            ListenEventEntity(
                trackId = track1Id,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 300_000L,
                endedAtEpoch = 320_000L,
                actualListenedMs = 20_000L,
                completedRatio = 0.1f,
                wasSkipped = true
            )
        )
        inMemoryDb.listenEventDao().insert(
            ListenEventEntity(
                trackId = track2Id,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 400_000L,
                endedAtEpoch = 627_000L,
                actualListenedMs = 227_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        val exporter = DataExporterImpl(
            database = inMemoryDb,
            dbFileProvider = { File(tempFolder.root, "unused.db") },
            clock = fakeClock,
            appVersion = "1.0.0"
        )
        val out = ByteArrayOutputStream()
        val result = exporter.exportJson(out)

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(out.size().toLong(), success.bytesWritten)
        assertEquals(2, success.trackCount)
        assertEquals(3, success.listenEventCount)

        val jsonText = out.toString(Charsets.UTF_8.name())
        assertTrue(jsonText.contains("\n"))
        assertTrue(jsonText.contains("\"schemaVersion\": 1"))
        assertTrue(jsonText.contains("\"artworkUriOrHash\": null"))

        val decoded = createExportJson().decodeFromString<MusicTrackerExportPayload>(jsonText)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(1_764_070_389_000L, decoded.exportedAtEpochMs)
        assertEquals("1.0.0", decoded.appVersion)
        assertEquals(2, decoded.trackCount)
        assertEquals(3, decoded.listenEventCount)
        assertEquals(2, decoded.tracks.size)
        assertEquals(3, decoded.listenEvents.size)
        assertEquals("Blinding Lights", decoded.tracks[0].normalizedTitle)
        assertEquals("content://art/1", decoded.tracks[0].artworkUriOrHash)
        assertNull(decoded.tracks[1].artworkUriOrHash)
        assertEquals(227_000L, decoded.listenEvents[2].actualListenedMs)
    }

    @Test
    fun exportJson_emptyDatabase_writesValidEmptyPayload() = runTest {
        val exporter = DataExporterImpl(
            database = inMemoryDb,
            dbFileProvider = { File(tempFolder.root, "unused.db") },
            clock = fakeClock
        )
        val out = ByteArrayOutputStream()
        val result = exporter.exportJson(out)

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(0, success.trackCount)
        assertEquals(0, success.listenEventCount)

        val decoded = createExportJson().decodeFromString<MusicTrackerExportPayload>(
            out.toString(Charsets.UTF_8.name())
        )
        assertEquals(1, decoded.schemaVersion)
        assertEquals("2.0.0", decoded.appVersion)
        assertTrue(decoded.tracks.isEmpty())
        assertTrue(decoded.listenEvents.isEmpty())
    }

    @Test
    fun exportJson_outputStreamThrowsIoException_returnsErrorResult() = runTest {
        val exporter = DataExporterImpl(
            database = inMemoryDb,
            dbFileProvider = { File(tempFolder.root, "unused.db") },
            clock = fakeClock
        )
        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Disk full")
            }
        }

        val result = exporter.exportJson(failingStream)
        assertTrue(result is ExportResult.Error)
        val error = result as ExportResult.Error
        assertTrue(error.message.contains("Disk full"))
        assertTrue(error.cause is IOException)
    }

    @Test
    fun exportSqliteBackup_checkpointsWalAndProducesReadableStandaloneDatabase() = runTest {
        val dbDir = tempFolder.newFolder("wal_db")
        val dbFile = File(dbDir, "music_tracker_backup_test.db")
        val fileBackedDb = Room.databaseBuilder(
            context,
            MusicTrackerDatabase::class.java,
            dbFile.absolutePath
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()

        try {
            val t1 = fileBackedDb.trackDao().insertIgnore(
                TrackEntity(
                    dedupKey = "artist1::track1",
                    normalizedTitle = "Track 1",
                    normalizedArtist = "Artist 1",
                    rawTitle = "Track 1",
                    rawArtist = "Artist 1"
                )
            )
            val t2 = fileBackedDb.trackDao().insertIgnore(
                TrackEntity(
                    dedupKey = "artist2::track2",
                    normalizedTitle = "Track 2",
                    normalizedArtist = "Artist 2",
                    rawTitle = "Track 2",
                    rawArtist = "Artist 2"
                )
            )
            fileBackedDb.listenEventDao().insert(
                ListenEventEntity(
                    trackId = t1,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 1_000L,
                    endedAtEpoch = 101_000L,
                    actualListenedMs = 100_000L,
                    completedRatio = 0.8f,
                    wasSkipped = false
                )
            )
            fileBackedDb.listenEventDao().insert(
                ListenEventEntity(
                    trackId = t1,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 200_000L,
                    endedAtEpoch = 250_000L,
                    actualListenedMs = 50_000L,
                    completedRatio = 0.4f,
                    wasSkipped = true
                )
            )
            fileBackedDb.listenEventDao().insert(
                ListenEventEntity(
                    trackId = t2,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    startedAtEpoch = 300_000L,
                    endedAtEpoch = 480_000L,
                    actualListenedMs = 180_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )

            val exporter = DataExporterImpl(
                database = fileBackedDb,
                dbFileProvider = { dbFile },
                clock = fakeClock
            )
            val isolatedDir = tempFolder.newFolder("isolated_backup")
            val backupFile = File(isolatedDir, "backup.db")

            val result = FileOutputStream(backupFile).use { output ->
                exporter.exportSqliteBackup(output)
            }

            assertTrue(result is ExportResult.Success)
            val success = result as ExportResult.Success
            assertEquals(backupFile.length(), success.bytesWritten)
            assertTrue(success.bytesWritten > 0L)
            assertEquals(2, success.trackCount)
            assertEquals(3, success.listenEventCount)

            // Open standalone backup.db (no -wal/-shm companion files) and verify all rows survived
            SQLiteDatabase.openDatabase(
                backupFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { sqlite ->
                sqlite.rawQuery("SELECT COUNT(*) FROM tracks", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
                sqlite.rawQuery("SELECT COUNT(*) FROM listen_events", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
            }
        } finally {
            fileBackedDb.close()
        }
    }

    @Test
    fun exportSqliteBackup_missingDatabaseFile_returnsErrorResult() = runTest {
        val missingFile = File(tempFolder.root, "does_not_exist.db")
        val exporter = DataExporterImpl(
            database = inMemoryDb,
            dbFileProvider = { missingFile },
            clock = fakeClock
        )

        val result = exporter.exportSqliteBackup(ByteArrayOutputStream())
        assertTrue(result is ExportResult.Error)
        val error = result as ExportResult.Error
        assertTrue(error.message.contains("Database file not found"))
    }

    @Test
    fun exportSqliteBackup_outputStreamThrowsIoException_returnsErrorResult() = runTest {
        val dbDir = tempFolder.newFolder("wal_err_db")
        val dbFile = File(dbDir, "music_tracker_err.db")
        val fileBackedDb = Room.databaseBuilder(
            context,
            MusicTrackerDatabase::class.java,
            dbFile.absolutePath
        )
            .allowMainThreadQueries()
            .build()

        try {
            val exporter = DataExporterImpl(
                database = fileBackedDb,
                dbFileProvider = { dbFile },
                clock = fakeClock
            )
            val failingStream = object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("Stream closed")
                }
            }

            val result = exporter.exportSqliteBackup(failingStream)
            assertTrue(result is ExportResult.Error)
            val error = result as ExportResult.Error
            assertTrue(error.message.contains("Stream closed"))
            assertTrue(error.cause is IOException)
        } finally {
            fileBackedDb.close()
        }
    }
}
