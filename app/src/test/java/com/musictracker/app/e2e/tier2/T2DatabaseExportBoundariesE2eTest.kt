package com.musictracker.app.e2e.tier2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.data.preferences.AllowlistStore
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Tier 2 — Area C: Database, Allowlist & Export Boundaries (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T2DatabaseExportBoundariesE2eTest {

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
    fun t2_c1_emptyAllowlistPersistsWithoutReSeedingDefaults() = runBlocking {
        val initial = env.allowlistStore.allowedPackages.first()
        assertTrue(initial.isNotEmpty())

        for (pkg in initial) {
            env.allowlistStore.setPackageAllowed(pkg, false)
        }
        assertTrue(env.allowlistStore.allowedPackages.first().isEmpty())

        // Recreate store on the same DataStore to simulate reload — must remain empty
        val reloaded = AllowlistStore(dataStore = env.dataStore)
        assertTrue("Explicitly emptied allowlist must not re-seed defaults", reloaded.allowedPackages.first().isEmpty())
    }

    @Test
    fun t2_c2_renormalizationCollisionMergesTracksAndReassignsAllEvents() = runBlocking {
        // Insert two distinct tracks with legacy unnormalized dedup_keys that collide once normalized
        val trackId1 = env.database.trackDao().insertIgnore(
            TrackEntity(
                dedupKey = "legacy_key_1",
                normalizedTitle = "Blinding Lights (Official Audio)",
                normalizedArtist = "The Weeknd - Topic",
                album = "",
                durationMs = 200_000L,
                rawTitle = "Blinding Lights (Official Audio)",
                rawArtist = "The Weeknd - Topic"
            )
        )
        val trackId2 = env.database.trackDao().insertIgnore(
            TrackEntity(
                dedupKey = "legacy_key_2",
                normalizedTitle = "Blinding Lights",
                normalizedArtist = "The Weeknd",
                album = "After Hours",
                durationMs = 200_000L,
                rawTitle = "Blinding Lights",
                rawArtist = "The Weeknd"
            )
        )

        env.database.listenEventDao().insert(
            ListenEventEntity(
                trackId = trackId1,
                sourcePackage = "com.google.android.apps.youtube.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 181_000L,
                actualListenedMs = 180_000L,
                completedRatio = 0.90f,
                wasSkipped = false
            )
        )
        env.database.listenEventDao().insert(
            ListenEventEntity(
                trackId = trackId2,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 500_000L,
                endedAtEpoch = 700_000L,
                actualListenedMs = 200_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        val updatedCount = env.listeningRepository.renormalizeAllTracks()
        assertTrue(updatedCount >= 1)

        val tracks = env.database.trackDao().getAll()
        val events = env.database.listenEventDao().getAll()

        assertEquals("Colliding tracks must merge into 1 surviving track", 1, tracks.size)
        assertEquals("Blinding Lights", tracks.single().normalizedTitle)
        assertEquals("The Weeknd", tracks.single().normalizedArtist)
        assertEquals("All listen_events must survive reassignment", 2, events.size)
        assertTrue(events.all { it.trackId == tracks.single().id })
    }

    @Test
    fun t2_c3_emptyDatabaseZeroDivisionGuard() = runBlocking {
        val summary = env.listeningRepository.observeSummary(0L).first()
        assertEquals(0L, summary.totalListenedMs)
        assertEquals(0, summary.playCount)
        assertEquals(0, summary.skippedCount)
        assertEquals(0f, summary.skipRate, 0.0001f)
        assertFalse(summary.skipRate.isNaN())

        assertTrue(env.listeningRepository.observeTopTracks(0L).first().isEmpty())
        assertTrue(env.listeningRepository.observeTopArtists(0L).first().isEmpty())
        assertTrue(env.listeningRepository.observeRecentHistory().first().isEmpty())
    }

    @Test
    fun t2_c4_timeframeBoundaryInclusivity() = runBlocking {
        val sinceEpoch = 1_000_000L

        env.listeningRepository.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Exact Boundary",
                rawArtist = "Artist",
                album = "",
                durationMs = 200_000L,
                artworkUri = null,
                startedAtEpoch = sinceEpoch,
                endedAtEpoch = sinceEpoch + 60_000L,
                actualListenedMs = 60_000L,
                completedRatio = 0.30f,
                wasSkipped = true
            )
        )
        env.listeningRepository.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Just Before Boundary",
                rawArtist = "Artist",
                album = "",
                durationMs = 200_000L,
                artworkUri = null,
                startedAtEpoch = sinceEpoch - 1L,
                endedAtEpoch = sinceEpoch + 59_999L,
                actualListenedMs = 60_000L,
                completedRatio = 0.30f,
                wasSkipped = true
            )
        )

        val summary = env.listeningRepository.observeSummary(sinceEpoch).first()
        assertEquals(1, summary.playCount)
        assertEquals(60_000L, summary.totalListenedMs)

        val topTracks = env.listeningRepository.observeTopTracks(sinceEpoch).first()
        assertEquals(1, topTracks.size)
        assertEquals("Exact Boundary", topTracks.single().title)
    }

    @Test
    fun t2_c5_jsonExportOnEmptyDbAndIoErrorHandling() = runBlocking {
        val out = ByteArrayOutputStream()
        val emptyResult = env.dataExporter.exportJson(out)
        assertTrue(emptyResult is ExportResult.Success)
        val payload = createExportJson().decodeFromString<MusicTrackerExportPayload>(out.toString(Charsets.UTF_8.name()))
        assertTrue(payload.tracks.isEmpty())
        assertTrue(payload.listenEvents.isEmpty())

        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Disk full")
            }
        }
        val errorResult = env.dataExporter.exportJson(failingStream)
        assertTrue("IO failure must return ExportResult.Error cleanly", errorResult is ExportResult.Error)
    }

    @Test
    fun t2_c6_sqliteExportWithActiveWalWritesIncludesLatestRows() = runBlocking {
        val fileEnv = E2eTestEnvironment(context = context, fileBackedDb = true)
        try {
            // Write 3 events immediately before export without closing Room
            repeat(3) { idx ->
                fileEnv.listeningRepository.recordCompletedSession(
                    CompletedListenSession(
                        sourcePackage = "com.spotify.music",
                        rawTitle = "WAL Track $idx",
                        rawArtist = "WAL Artist",
                        album = "WAL Album",
                        durationMs = 180_000L,
                        artworkUri = null,
                        startedAtEpoch = 100_000L + idx * 200_000L,
                        endedAtEpoch = 280_000L + idx * 200_000L,
                        actualListenedMs = 180_000L,
                        completedRatio = 1.0f,
                        wasSkipped = false
                    )
                )
            }

            val exportedDb = File(fileEnv.tempDir, "wal_checked_backup.db")
            val result = exportedDb.outputStream().use { out ->
                fileEnv.dataExporter.exportSqliteBackup(out)
            }
            assertTrue(result is ExportResult.Success)

            val sqlite = SQLiteDatabase.openDatabase(exportedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val count = sqlite.rawQuery("SELECT COUNT(*) FROM listen_events", null).use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
                assertEquals("All 3 WAL-buffered rows must be checkpointed into backup", 3, count)
            } finally {
                sqlite.close()
            }
        } finally {
            fileEnv.close()
        }
    }
}
