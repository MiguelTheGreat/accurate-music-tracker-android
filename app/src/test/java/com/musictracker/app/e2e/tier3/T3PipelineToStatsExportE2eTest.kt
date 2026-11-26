package com.musictracker.app.e2e.tier3

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.export.MusicTrackerExportPayload
import com.musictracker.app.data.export.createExportJson
import com.musictracker.app.domain.model.ExportResult
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.ui.screens.stats.StatsViewModel
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
import java.time.ZoneOffset

/**
 * Tier 3 — T3-1: Full pipeline from noisy YT Music metadata + late-arriving duration +
 * scrubbing + repeat-one loop -> PlaySessionStateMachine -> ListeningRepository (Room) ->
 * StatsViewModel -> DataExporter (JSON + WAL-checkpointed SQLite).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T3PipelineToStatsExportE2eTest {

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        env = E2eTestEnvironment(context = context, fileBackedDb = true)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun t3_1_fullPipelineAgreesAcrossStateMachineRoomStatsAndExports() {
        val ytMusic = "com.google.android.apps.youtube.music"
        val rawTitle = "Instant Crush (feat. Julian Casablancas) (Official Video)"
        val rawArtist = "Daft Punk - Topic"
        val expectedNormTitle = "Instant Crush (feat. Julian Casablancas)"
        val expectedNormArtist = "Daft Punk"

        // 1. Start playing with partial metadata (durationMs = 0)
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = ytMusic,
                metadata = MediaMetadataSnapshot(title = rawTitle, artist = rawArtist, album = "", durationMs = 0L),
                state = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
            )
        )
        // Late-arriving duration + album at +800ms
        env.clock.advanceBy(800L)
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.MetadataChanged(
                sourcePackage = ytMusic,
                metadata = MediaMetadataSnapshot(
                    title = rawTitle,
                    artist = rawArtist,
                    album = "Random Access Memories",
                    durationMs = 200_000L
                )
            )
        )

        // Play to 60s, scrub back to 20s (40s extra chorus replay), then play to near end (196s)
        env.clock.advanceBy(59_200L) // total 60s
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusic, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 60_000L))
        )
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusic, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 20_000L))
        )
        env.clock.advanceBy(176_000L) // total 236s
        env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusic, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 196_000L))
        )

        // Repeat-One loop back to 200ms -> finalizes listen #1 (236s) and starts listen #2
        env.clock.advanceBy(1_000L) // 237s
        val loop1 = env.playSessionStateMachine.onPlaybackEvent(
            PlaybackEvent.StateChanged(ytMusic, PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 200L))
        )
        assertEquals(1, loop1.size)
        assertEquals(237_000L, loop1.single().actualListenedMs)
        assertFalse(loop1.single().wasSkipped)
        runBlocking { loop1.forEach { env.listeningRepository.recordCompletedSession(it) } }

        // Listen #2 plays for 180s then ends
        env.clock.advanceBy(180_000L)
        val listen2 = env.playSessionStateMachine.onSessionEnded(ytMusic)
        assertEquals(1, listen2.size)
        assertEquals(180_000L, listen2.single().actualListenedMs)
        runBlocking { listen2.forEach { env.listeningRepository.recordCompletedSession(it) } }

        val expectedTotalMs = 237_000L + 180_000L

        // 2. Verify Room deduplicated into 1 track and 2 listen_events
        val dbTracks = runBlocking { env.database.trackDao().getAll() }
        val dbEvents = runBlocking { env.database.listenEventDao().getAll() }
        assertEquals(1, dbTracks.size)
        assertEquals(2, dbEvents.size)
        assertEquals(expectedNormTitle, dbTracks.single().normalizedTitle)
        assertEquals(expectedNormArtist, dbTracks.single().normalizedArtist)
        assertEquals(rawTitle, dbTracks.single().rawTitle)
        assertEquals(rawArtist, dbTracks.single().rawArtist)

        // 3. Verify StatsViewModel agrees on totals and ranking
        val statsVm = StatsViewModel(
            listeningRepository = env.listeningRepository,
            clock = env.clock,
            zoneId = ZoneOffset.UTC
        )
        statsVm.selectTimeframe(StatsTimeframe.TODAY)
        val statsState = env.awaitStateFlow(statsVm.uiState) { !it.isLoading && it.topTracks.isNotEmpty() }
        assertEquals(expectedTotalMs, statsState.summary.totalListenedMs)
        assertEquals(2, statsState.summary.playCount)
        assertEquals(expectedNormTitle, statsState.topTracks.single().title)
        assertEquals(expectedTotalMs, statsState.topTracks.single().totalListenedMs)
        assertEquals(expectedNormArtist, statsState.topArtists.single().artist)
        assertEquals(expectedTotalMs, statsState.topArtists.single().totalListenedMs)

        // 4. Verify JSON export agrees row-for-row
        val jsonOut = ByteArrayOutputStream()
        val jsonResult = runBlocking { env.dataExporter.exportJson(jsonOut) }
        assertTrue(jsonResult is ExportResult.Success)
        val payload = createExportJson().decodeFromString<MusicTrackerExportPayload>(jsonOut.toString(Charsets.UTF_8.name()))
        assertEquals(1, payload.tracks.size)
        assertEquals(2, payload.listenEvents.size)
        assertEquals(expectedNormTitle, payload.tracks.single().normalizedTitle)
        assertEquals(rawTitle, payload.tracks.single().rawTitle)
        assertEquals(expectedTotalMs, payload.listenEvents.sumOf { it.actualListenedMs })

        // 5. Verify WAL-checkpointed SQLite backup agrees row-for-row
        val backupFile = File(env.tempDir, "t3_1_backup.db")
        val sqliteResult = runBlocking { backupFile.outputStream().use { env.dataExporter.exportSqliteBackup(it) } }
        assertTrue(sqliteResult is ExportResult.Success)
        val sqlite = SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val sumMs = sqlite.rawQuery("SELECT SUM(actual_listened_ms) FROM listen_events", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            assertEquals(expectedTotalMs, sumMs)
        } finally {
            sqlite.close()
        }
    }
}
