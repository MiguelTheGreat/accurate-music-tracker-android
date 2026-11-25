package com.musictracker.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.musictracker.app.data.db.ListenEventDao
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.ListeningSummaryRow
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackDao
import com.musictracker.app.data.db.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicTrackerDaoTest {

    private lateinit var database: MusicTrackerDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var listenEventDao: ListenEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackDao = database.trackDao()
        listenEventDao = database.listenEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertTrackAndFindByDedupKey_returnsInsertedTrack() = runTest {
        val insertedId = trackDao.insertIgnore(
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

        assertTrue(insertedId > 0L)
        val found = trackDao.findByDedupKey("the weeknd::blinding lights")
        assertNotNull(found)
        assertEquals(insertedId, found!!.id)
        assertEquals("Blinding Lights", found.normalizedTitle)
        assertEquals("The Weeknd", found.normalizedArtist)
        assertEquals("After Hours", found.album)
        assertEquals(200_000L, found.durationMs)
        assertEquals("Blinding Lights (Official Audio)", found.rawTitle)
        assertEquals("The Weeknd - Topic", found.rawArtist)
        assertEquals("content://art/1", found.artworkUriOrHash)
        assertEquals(1, trackDao.getAll().size)
    }

    @Test
    fun insertDuplicateDedupKey_isIgnoredAndPreservesOriginalRow() = runTest {
        val firstId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "daft punk::instant crush",
                normalizedTitle = "Instant Crush",
                normalizedArtist = "Daft Punk",
                rawTitle = "Instant Crush",
                rawArtist = "Daft Punk"
            )
        )
        val duplicateId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "daft punk::instant crush",
                normalizedTitle = "Instant Crush (Duplicate)",
                normalizedArtist = "Daft Punk",
                rawTitle = "Instant Crush (Duplicate)",
                rawArtist = "Daft Punk"
            )
        )

        assertTrue(firstId > 0L)
        assertEquals(-1L, duplicateId)
        val all = trackDao.getAll()
        assertEquals(1, all.size)
        assertEquals("Instant Crush", all.first().normalizedTitle)
    }

    @Test
    fun patchMissingMetadata_patchesBlankOrZeroFieldsWithoutOverwritingExisting() = runTest {
        val id = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "artist::song",
                normalizedTitle = "Song",
                normalizedArtist = "Artist",
                album = "",
                durationMs = 0L,
                rawTitle = "Song",
                rawArtist = "Artist",
                artworkUriOrHash = null
            )
        )

        trackDao.patchMissingMetadata(
            id = id,
            album = "Original Album",
            durationMs = 185_000L,
            artworkUriOrHash = "content://art/original"
        )

        val patched = trackDao.findById(id)!!
        assertEquals("Original Album", patched.album)
        assertEquals(185_000L, patched.durationMs)
        assertEquals("content://art/original", patched.artworkUriOrHash)

        // Attempt to overwrite existing populated values
        trackDao.patchMissingMetadata(
            id = id,
            album = "Other Album",
            durationMs = 999_000L,
            artworkUriOrHash = "content://art/other"
        )
        trackDao.patchMissingMetadata(
            id = id,
            album = "",
            durationMs = 0L,
            artworkUriOrHash = null
        )

        val preserved = trackDao.findById(id)!!
        assertEquals("Original Album", preserved.album)
        assertEquals(185_000L, preserved.durationMs)
        assertEquals("content://art/original", preserved.artworkUriOrHash)
    }

    @Test
    fun deleteTrack_cascadesDeleteToListenEvents() = runTest {
        val trackId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "cascade::test",
                normalizedTitle = "Test",
                normalizedArtist = "Cascade",
                rawTitle = "Test",
                rawArtist = "Cascade"
            )
        )

        listenEventDao.insert(
            ListenEventEntity(
                trackId = trackId,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 61_000L,
                actualListenedMs = 60_000L,
                completedRatio = 0.5f,
                wasSkipped = false
            )
        )
        listenEventDao.insert(
            ListenEventEntity(
                trackId = trackId,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 70_000L,
                endedAtEpoch = 130_000L,
                actualListenedMs = 60_000L,
                completedRatio = 0.5f,
                wasSkipped = false
            )
        )
        assertEquals(2, listenEventDao.getAll().size)

        trackDao.deleteById(trackId)

        assertNull(trackDao.findById(trackId))
        assertTrue(listenEventDao.getAll().isEmpty())
    }

    @Test
    fun emptyDatabase_allReactiveQueriesEmitZeroSafeDefaults() = runTest {
        assertEquals(ListeningSummaryRow(0L, 0, 0), listenEventDao.observeSummaryRow(0L).first())
        assertTrue(listenEventDao.observeRecentHistory(50).first().isEmpty())
        assertTrue(listenEventDao.observeTopTracks(0L, 25).first().isEmpty())
        assertTrue(listenEventDao.observeTopArtists(0L, 25).first().isEmpty())
    }

    @Test
    fun observeSummary_filtersByTimeframeAndEmitsReactively() = runTest {
        val trackId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "summary::track",
                normalizedTitle = "Track",
                normalizedArtist = "Summary",
                rawTitle = "Track",
                rawArtist = "Summary"
            )
        )
        val sinceEpoch = 100_000L

        listenEventDao.observeSummaryRow(sinceEpoch).test {
            assertEquals(ListeningSummaryRow(0L, 0, 0), awaitItem())

            // Old event (< sinceEpoch) - should not count toward filtered summary
            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 50_000L,
                    endedAtEpoch = 90_000L,
                    actualListenedMs = 40_000L,
                    completedRatio = 0.8f,
                    wasSkipped = false
                )
            )
            // Room invalidates on table write; consume if emitted, then insert recent events
            val afterOld = awaitItem()
            assertEquals(ListeningSummaryRow(0L, 0, 0), afterOld)

            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 100_000L,
                    endedAtEpoch = 220_000L,
                    actualListenedMs = 120_000L,
                    completedRatio = 0.9f,
                    wasSkipped = false
                )
            )
            assertEquals(ListeningSummaryRow(120_000L, 1, 0), awaitItem())

            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 230_000L,
                    endedAtEpoch = 245_000L,
                    actualListenedMs = 15_000L,
                    completedRatio = 0.1f,
                    wasSkipped = true
                )
            )
            assertEquals(ListeningSummaryRow(135_000L, 2, 1), awaitItem())

            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 250_000L,
                    endedAtEpoch = 350_000L,
                    actualListenedMs = 100_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
            assertEquals(ListeningSummaryRow(235_000L, 3, 1), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeRecentHistory_ordersNewestFirstAndRespectsLimit() = runTest {
        val trackId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "recent::track",
                normalizedTitle = "Midnight City",
                normalizedArtist = "M83",
                album = "Hurry Up, We're Dreaming",
                rawTitle = "Midnight City",
                rawArtist = "M83"
            )
        )

        listOf(10_000L, 40_000L, 20_000L, 30_000L).forEach { start ->
            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackId,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    startedAtEpoch = start,
                    endedAtEpoch = start + 5_000L,
                    actualListenedMs = 5_000L,
                    completedRatio = 0.5f,
                    wasSkipped = false
                )
            )
        }

        val recent = listenEventDao.observeRecentHistory(limit = 2).first()
        assertEquals(2, recent.size)
        assertEquals(40_000L, recent[0].startedAtEpoch)
        assertEquals(30_000L, recent[1].startedAtEpoch)
        assertEquals("Midnight City", recent[0].title)
        assertEquals("M83", recent[0].artist)
        assertEquals("Hurry Up, We're Dreaming", recent[0].album)
    }

    @Test
    fun observeTopTracks_ranksByActualListenedMsDescNotPlayCount_andFiltersTimeframe() = runTest {
        val trackA = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "artist a::short plays",
                normalizedTitle = "Short Plays",
                normalizedArtist = "Artist A",
                rawTitle = "Short Plays",
                rawArtist = "Artist A"
            )
        )
        val trackB = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "artist b::long play",
                normalizedTitle = "Long Play",
                normalizedArtist = "Artist B",
                rawTitle = "Long Play",
                rawArtist = "Artist B"
            )
        )
        val trackC = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "artist c::old play",
                normalizedTitle = "Old Play",
                normalizedArtist = "Artist C",
                rawTitle = "Old Play",
                rawArtist = "Artist C"
            )
        )

        val sinceEpoch = 1_000_000L
        // Track A: 5 plays x 10s = 50s total
        repeat(5) { idx ->
            listenEventDao.insert(
                ListenEventEntity(
                    trackId = trackA,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = sinceEpoch + idx * 20_000L,
                    endedAtEpoch = sinceEpoch + idx * 20_000L + 10_000L,
                    actualListenedMs = 10_000L,
                    completedRatio = 0.1f,
                    wasSkipped = true
                )
            )
        }
        // Track B: 1 play x 240s = 240s total
        listenEventDao.insert(
            ListenEventEntity(
                trackId = trackB,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = sinceEpoch + 200_000L,
                endedAtEpoch = sinceEpoch + 440_000L,
                actualListenedMs = 240_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )
        // Track C: 1 old play x 500s (< sinceEpoch)
        listenEventDao.insert(
            ListenEventEntity(
                trackId = trackC,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 10_000L,
                endedAtEpoch = 510_000L,
                actualListenedMs = 500_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        val recentTop = listenEventDao.observeTopTracks(sinceEpochMs = sinceEpoch, limit = 10).first()
        assertEquals(2, recentTop.size)
        assertEquals(trackB, recentTop[0].trackId)
        assertEquals(240_000L, recentTop[0].totalListenedMs)
        assertEquals(1, recentTop[0].playCount)
        assertEquals(trackA, recentTop[1].trackId)
        assertEquals(50_000L, recentTop[1].totalListenedMs)
        assertEquals(5, recentTop[1].playCount)

        val allTimeTop = listenEventDao.observeTopTracks(sinceEpochMs = 0L, limit = 10).first()
        assertEquals(3, allTimeTop.size)
        assertEquals(trackC, allTimeTop[0].trackId)
        assertEquals(trackB, allTimeTop[1].trackId)
        assertEquals(trackA, allTimeTop[2].trackId)
    }

    @Test
    fun observeTopArtists_ranksByActualListenedMsDesc_andGroupsCaseInsensitively() = runTest {
        val weeknd1 = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "the weeknd::starboy",
                normalizedTitle = "Starboy",
                normalizedArtist = "The Weeknd",
                rawTitle = "Starboy",
                rawArtist = "The Weeknd"
            )
        )
        val weeknd2 = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "the weeknd::save your tears",
                normalizedTitle = "Save Your Tears",
                normalizedArtist = "the weeknd",
                rawTitle = "Save Your Tears",
                rawArtist = "the weeknd"
            )
        )
        val artistB = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "artist b::hit",
                normalizedTitle = "Hit",
                normalizedArtist = "Artist B",
                rawTitle = "Hit",
                rawArtist = "Artist B"
            )
        )

        listenEventDao.insert(
            ListenEventEntity(
                trackId = weeknd1,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 121_000L,
                actualListenedMs = 120_000L,
                completedRatio = 0.8f,
                wasSkipped = false
            )
        )
        listenEventDao.insert(
            ListenEventEntity(
                trackId = weeknd2,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 200_000L,
                endedAtEpoch = 300_000L,
                actualListenedMs = 100_000L,
                completedRatio = 0.7f,
                wasSkipped = false
            )
        )
        repeat(4) { i ->
            listenEventDao.insert(
                ListenEventEntity(
                    trackId = artistB,
                    sourcePackage = "com.spotify.music",
                    startedAtEpoch = 400_000L + i * 40_000L,
                    endedAtEpoch = 430_000L + i * 40_000L,
                    actualListenedMs = 30_000L,
                    completedRatio = 0.2f,
                    wasSkipped = true
                )
            )
        }

        val topArtists = listenEventDao.observeTopArtists(sinceEpochMs = 0L, limit = 10).first()
        assertEquals(2, topArtists.size)
        assertEquals("The Weeknd", topArtists[0].artist)
        assertEquals(220_000L, topArtists[0].totalListenedMs)
        assertEquals(2, topArtists[0].playCount)
        assertEquals(2, topArtists[0].uniqueTrackCount)

        assertEquals("Artist B", topArtists[1].artist)
        assertEquals(120_000L, topArtists[1].totalListenedMs)
        assertEquals(4, topArtists[1].playCount)
        assertEquals(1, topArtists[1].uniqueTrackCount)
    }
}
