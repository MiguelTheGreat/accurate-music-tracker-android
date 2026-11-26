package com.musictracker.app.e2e.tier1

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.ui.common.computeTimeframeSinceEpochMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset

/**
 * Tier 1 — Area C: Room Database, Allowlist & Data Export (Robolectric, F07, F18–F22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T1DatabaseAndExportE2eTest {

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context, fileBackedDb = false)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun t1_c1_defaultAllowlistPreSeedsMusicPlayersAndExcludesNonMusicApps() = runBlocking {
        val allowed = env.allowlistStore.allowedPackages.first()
        assertTrue(allowed.contains("com.google.android.apps.youtube.music"))
        assertTrue(allowed.contains("com.spotify.music"))
        assertTrue(allowed.contains("org.videolan.vlc"))
        assertFalse(allowed.contains("com.google.android.youtube"))
        assertFalse(allowed.contains("com.android.chrome"))
    }

    @Test
    fun t1_c2_rawMetadataPreservedAndDedupUpsertReusesTrackId() = runBlocking {
        val ytSession = CompletedListenSession(
            sourcePackage = "com.google.android.apps.youtube.music",
            rawTitle = "Blinding Lights (Official Audio)",
            rawArtist = "The Weeknd - Topic",
            album = "After Hours",
            durationMs = 200_000L,
            artworkUri = null,
            startedAtEpoch = 1_000_000L,
            endedAtEpoch = 1_180_000L,
            actualListenedMs = 180_000L,
            completedRatio = 0.90f,
            wasSkipped = false
        )
        val spotifySession = ytSession.copy(
            sourcePackage = "com.spotify.music",
            rawTitle = "Blinding Lights",
            rawArtist = "The Weeknd",
            startedAtEpoch = 2_000_000L,
            endedAtEpoch = 2_200_000L,
            actualListenedMs = 200_000L,
            completedRatio = 1.0f
        )

        env.listeningRepository.recordCompletedSession(ytSession)
        env.listeningRepository.recordCompletedSession(spotifySession)

        val tracks = env.database.trackDao().getAll()
        val events = env.database.listenEventDao().getAll()

        assertEquals(1, tracks.size)
        assertEquals("Blinding Lights", tracks.single().normalizedTitle)
        assertEquals("The Weeknd", tracks.single().normalizedArtist)
        assertEquals("Blinding Lights (Official Audio)", tracks.single().rawTitle)
        assertEquals("The Weeknd - Topic", tracks.single().rawArtist)

        assertEquals(2, events.size)
        assertEquals(tracks.single().id, events[0].trackId)
        assertEquals(tracks.single().id, events[1].trackId)
    }

    @Test
    fun t1_c3_foreignKeyCascadeAndUniqueDedupKey() = runBlocking {
        env.listeningRepository.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Cascade Song",
                rawArtist = "Cascade Artist",
                album = "Album",
                durationMs = 180_000L,
                artworkUri = null,
                startedAtEpoch = 5_000_000L,
                endedAtEpoch = 5_120_000L,
                actualListenedMs = 120_000L,
                completedRatio = 0.67f,
                wasSkipped = false
            )
        )

        val track = env.database.trackDao().getAll().single()
        assertEquals(1, env.database.listenEventDao().getAll().size)

        // Duplicate dedup_key insertion is ignored (returns -1L)
        val duplicateInsertId = env.database.trackDao().insertIgnore(
            TrackEntity(
                dedupKey = track.dedupKey,
                normalizedTitle = track.normalizedTitle,
                normalizedArtist = track.normalizedArtist,
                rawTitle = track.rawTitle,
                rawArtist = track.rawArtist
            )
        )
        assertEquals(-1L, duplicateInsertId)

        // Deleting TrackEntity cascades to ListenEventEntity
        env.database.trackDao().deleteById(track.id)
        assertTrue(env.database.trackDao().getAll().isEmpty())
        assertTrue(env.database.listenEventDao().getAll().isEmpty())
    }

    @Test
    fun t1_c4_todaySummaryAndRecentHistoryFlows() = runBlocking {
        val now = env.clock.currentTimeMillis()
        val todayStart = computeTimeframeSinceEpochMs(StatsTimeframe.TODAY, now, ZoneOffset.UTC)

        env.listeningRepository.recordCompletedSession(
            session("Song 1", "Artist 1", startedAt = todayStart + 10_000L, listenedMs = 180_000L, skipped = false)
        )
        env.listeningRepository.recordCompletedSession(
            session("Song 2", "Artist 2", startedAt = todayStart + 300_000L, listenedMs = 20_000L, skipped = true)
        )
        env.listeningRepository.recordCompletedSession(
            session("Song 3", "Artist 3", startedAt = todayStart + 600_000L, listenedMs = 160_000L, skipped = false)
        )

        val summary = env.listeningRepository.observeSummary(todayStart).first()
        assertEquals(360_000L, summary.totalListenedMs)
        assertEquals(3, summary.playCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(1f / 3f, summary.skipRate, 0.001f)

        val recent = env.listeningRepository.observeRecentHistory(limit = 10).first()
        assertEquals(3, recent.size)
        assertEquals("Song 3", recent[0].title)
        assertEquals("Song 2", recent[1].title)
        assertEquals("Song 1", recent[2].title)
    }

    @Test
    fun t1_c5_topTracksAndTopArtistsRankedByActualTimeAcrossTimeframes() = runBlocking {
        val now = env.clock.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1_000L

        // Track A: 1 play x 300s = 300s (Today)
        env.listeningRepository.recordCompletedSession(
            session("Deep Track A", "Artist A", startedAt = now - 60_000L, listenedMs = 300_000L, skipped = false)
        )
        // Track B: 4 plays x 30s = 120s (Today)
        repeat(4) { i ->
            env.listeningRepository.recordCompletedSession(
                session("Short Track B", "Artist B", startedAt = now - 50_000L + i * 5_000L, listenedMs = 30_000L, skipped = true)
            )
        }
        // Track C: 1 play x 500s (10 days ago — included in 30D & ALL_TIME, excluded from TODAY & 7D)
        env.listeningRepository.recordCompletedSession(
            session("Older Track C", "Artist C", startedAt = now - 10 * dayMs, listenedMs = 500_000L, skipped = false)
        )

        val todaySince = computeTimeframeSinceEpochMs(StatsTimeframe.TODAY, now, ZoneOffset.UTC)
        val sevenDaySince = computeTimeframeSinceEpochMs(StatsTimeframe.SEVEN_DAYS, now, ZoneOffset.UTC)
        val thirtyDaySince = computeTimeframeSinceEpochMs(StatsTimeframe.THIRTY_DAYS, now, ZoneOffset.UTC)
        val allTimeSince = computeTimeframeSinceEpochMs(StatsTimeframe.ALL_TIME, now, ZoneOffset.UTC)

        val todayTracks = env.listeningRepository.observeTopTracks(todaySince).first()
        assertEquals(2, todayTracks.size)
        assertEquals("Deep Track A", todayTracks[0].title)
        assertEquals(300_000L, todayTracks[0].totalListenedMs)
        assertEquals("Short Track B", todayTracks[1].title)
        assertEquals(120_000L, todayTracks[1].totalListenedMs)

        val todayArtists = env.listeningRepository.observeTopArtists(todaySince).first()
        assertEquals("Artist A", todayArtists[0].artist)
        assertEquals("Artist B", todayArtists[1].artist)

        val sevenDayTracks = env.listeningRepository.observeTopTracks(sevenDaySince).first()
        assertEquals(2, sevenDayTracks.size)

        val thirtyDayTracks = env.listeningRepository.observeTopTracks(thirtyDaySince).first()
        assertEquals(3, thirtyDayTracks.size)
        assertEquals("Older Track C", thirtyDayTracks[0].title)

        val allTimeTracks = env.listeningRepository.observeTopTracks(allTimeSince).first()
        assertEquals(3, allTimeTracks.size)
    }

    @Test
    fun t1_c6_jsonExportWritesValidSchemaAndAllRows() = runBlocking {
        env.listeningRepository.recordCompletedSession(
            session("Export Song", "Export Artist", startedAt = 10_000L, listenedMs = 150_000L, skipped = false)
        )

        val out = ByteArrayOutputStream()
        val result = env.dataExporter.exportJson(out)

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(1, success.trackCount)
        assertEquals(1, success.listenEventCount)
        assertTrue(success.bytesWritten > 0L)

        val jsonText = out.toString(Charsets.UTF_8.name())
        val payload = createExportJson().decodeFromString<MusicTrackerExportPayload>(jsonText)
        assertEquals(MusicTrackerExportPayload.CURRENT_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(1, payload.tracks.size)
        assertEquals("Export Song", payload.tracks.single().normalizedTitle)
        assertEquals(1, payload.listenEvents.size)
        assertEquals(150_000L, payload.listenEvents.single().actualListenedMs)
    }

    @Test
    fun t1_c7_sqliteBackupExportProducesReadableWalCheckpointedDatabase() = runBlocking {
        val fileEnv = E2eTestEnvironment(context = context, fileBackedDb = true)
        try {
            fileEnv.listeningRepository.recordCompletedSession(
                session("Backup Song", "Backup Artist", startedAt = 20_000L, listenedMs = 210_000L, skipped = false)
            )

            val backupFile = File(fileEnv.tempDir, "exported_backup.db")
            val result = backupFile.outputStream().use { out ->
                fileEnv.dataExporter.exportSqliteBackup(out)
            }

            assertTrue(result is ExportResult.Success)
            val bytes = backupFile.readBytes()
            val header = String(bytes.copyOfRange(0, 16), Charsets.US_ASCII)
            assertEquals("SQLite format 3\u0000", header)

            val opened = SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val trackCount = opened.rawQuery("SELECT COUNT(*) FROM tracks", null).use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
                val eventCount = opened.rawQuery("SELECT COUNT(*) FROM listen_events", null).use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
                assertEquals(1, trackCount)
                assertEquals(1, eventCount)
                assertNotEquals(0L, backupFile.length())
            } finally {
                opened.close()
            }
        } finally {
            fileEnv.close()
        }
    }

    private fun session(
        title: String,
        artist: String,
        startedAt: Long,
        listenedMs: Long,
        skipped: Boolean
    ) = CompletedListenSession(
        sourcePackage = "com.spotify.music",
        rawTitle = title,
        rawArtist = artist,
        album = "Test Album",
        durationMs = 200_000L,
        artworkUri = null,
        startedAtEpoch = startedAt,
        endedAtEpoch = startedAt + listenedMs,
        actualListenedMs = listenedMs,
        completedRatio = listenedMs.toFloat() / 200_000f,
        wasSkipped = skipped
    )
}
