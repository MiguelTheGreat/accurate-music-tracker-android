package com.musictracker.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.ListenEventEntity
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.db.TrackEntity
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListenEventInsightSample
import com.musictracker.app.domain.model.ListeningStreak
import com.musictracker.app.domain.model.MilestoneType
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.stats.ListeningInsightsCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtistDeduplicationAndDataCleanerTest {

    private lateinit var database: MusicTrackerDatabase
    private lateinit var repository: ListeningRepositoryImpl
    private val normalizer = MusicMetadataNormalizer()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ListeningRepositoryImpl(
            database = database,
            normalizer = normalizer
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun normalizer_stripsVevoOfficialChannelAndUnpacksRecommendedForYou() {
        val variants = listOf(
            "Drake VEVO",
            "Drake - VEVO",
            "Drake (VEVO)",
            "Drake [VEVO]",
            "Drake Official Channel",
            "Drake - Official Music",
            "Drake Official Artist",
            "Drake - Topic",
            "  drake  "
        )

        variants.forEach { rawArtist ->
            val norm = normalizer.normalize("God's Plan", rawArtist)
            assertEquals("Failed dedupKey for '$rawArtist'", "drake::god s plan", norm.dedupKey)
        }

        val attachedVevo = normalizer.normalize("Blinding Lights", "TheWeekndVEVO")
        assertEquals("TheWeeknd", attachedVevo.normalizedArtist)

        val unpacked = normalizer.normalize("The Weeknd - Starboy (Official Audio)", "Recommended for you")
        assertEquals("The Weeknd", unpacked.normalizedArtist)
        assertEquals("Starboy", unpacked.normalizedTitle)
        assertEquals("the weeknd::starboy", unpacked.dedupKey)

        // When title has no "Artist - Title" separator, placeholder/platform artists must map to Unknown Artist.
        val placeholderArtists = listOf(
            "Recommended for you",
            "Spotify",
            "YouTube Music",
            "YouTube",
            "<unknown>",
            "unknown",
            "null",
            "N/A",
            "none",
            "SoundCloud",
            "Apple Music",
            "TIDAL",
            "Deezer",
            "Amazon Music"
        )
        placeholderArtists.forEach { placeholder ->
            val normalized = normalizer.normalize("Blinding Lights", placeholder)
            assertEquals(
                "Placeholder artist '$placeholder' must map to Unknown Artist",
                "Unknown Artist",
                normalized.normalizedArtist
            )
            assertEquals("unknown artist::blinding lights", normalized.dedupKey)
        }

        // Preserve "Various Artists" when title has no dash.
        assertEquals(
            "Various Artists",
            normalizer.normalize("Ambient Interlude", "Various Artists").normalizedArtist
        )
    }

    @Test
    fun daoObserveTopArtists_groupsLegacyWhitespaceAndMixedCaseRows() = runTest {
        val trackDao = database.trackDao()
        val eventDao = database.listenEventDao()

        val id1 = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = " Drake ::track one",
                normalizedTitle = "Track One",
                normalizedArtist = "  Drake ",
                album = "Views",
                durationMs = 200_000L,
                rawTitle = "Track One",
                rawArtist = "  Drake ",
                artworkUriOrHash = null
            )
        )
        val id2 = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "drake::track two",
                normalizedTitle = "Track Two",
                normalizedArtist = "drake",
                album = "Views",
                durationMs = 210_000L,
                rawTitle = "Track Two",
                rawArtist = "drake",
                artworkUriOrHash = null
            )
        )

        eventDao.insert(
            ListenEventEntity(
                trackId = id1,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 101_000L,
                actualListenedMs = 100_000L,
                completedRatio = 0.5f,
                wasSkipped = false
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = id2,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 200_000L,
                endedAtEpoch = 350_000L,
                actualListenedMs = 150_000L,
                completedRatio = 0.71f,
                wasSkipped = false
            )
        )

        val topArtists = eventDao.observeTopArtists(sinceEpochMs = 0L, limit = 10).first()
        assertEquals(1, topArtists.size)
        assertEquals("Drake", topArtists[0].artist)
        assertEquals(250_000L, topArtists[0].totalListenedMs)
        assertEquals(2, topArtists[0].playCount)
        assertEquals(2, topArtists[0].uniqueTrackCount)
    }

    @Test
    fun repository_guardsRecordCompletedSessionAgainstAdsBlankAndZeroDuration() = runTest {
        val adSession = CompletedListenSession(
            sourcePackage = "com.spotify.music",
            rawTitle = "Advertisement",
            rawArtist = "Spotify",
            album = "",
            durationMs = 30_000L,
            artworkUri = null,
            startedAtEpoch = 1_000L,
            endedAtEpoch = 31_000L,
            actualListenedMs = 30_000L,
            completedRatio = 1.0f,
            wasSkipped = false
        )
        val zeroDurationSession = CompletedListenSession(
            sourcePackage = "com.spotify.music",
            rawTitle = "Blinding Lights",
            rawArtist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_000L,
            artworkUri = null,
            startedAtEpoch = 1_000L,
            endedAtEpoch = 1_000L,
            actualListenedMs = 0L,
            completedRatio = 0f,
            wasSkipped = true
        )
        val blankTitleSession = zeroDurationSession.copy(rawTitle = "   ", actualListenedMs = 50_000L)

        val spotifyArtistAdSession = adSession.copy(
            rawTitle = "Spotify",
            rawArtist = "Advertisement • 1 of 2"
        )
        val spotifyArtistAdSession2 = adSession.copy(
            rawTitle = "Spotify",
            rawArtist = "Advertisement • 2 of 2"
        )

        repository.recordCompletedSession(adSession)
        repository.recordCompletedSession(spotifyArtistAdSession)
        repository.recordCompletedSession(spotifyArtistAdSession2)
        repository.recordCompletedSession(zeroDurationSession)
        repository.recordCompletedSession(blankTitleSession)

        assertTrue(database.trackDao().getAll().isEmpty())
        assertTrue(database.listenEventDao().getAll().isEmpty())
    }

    @Test
    fun cleanAndRenormalizeLibrary_removesLegacySpotifyArtistAdRows() = runTest {
        val trackDao = database.trackDao()
        val eventDao = database.listenEventDao()

        val spotifyAd1Id = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "advertisement 1 of 2::spotify",
                normalizedTitle = "Spotify",
                normalizedArtist = "Advertisement • 1 of 2",
                album = "",
                durationMs = 30_000L,
                rawTitle = "Spotify",
                rawArtist = "Advertisement • 1 of 2",
                artworkUriOrHash = null
            )
        )
        val spotifyAd2Id = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "advertisement 2 of 2::spotify",
                normalizedTitle = "Spotify",
                normalizedArtist = "Advertisement • 2 of 2",
                album = "",
                durationMs = 15_000L,
                rawTitle = "Spotify",
                rawArtist = "Advertisement • 2 of 2",
                artworkUriOrHash = null
            )
        )
        val realTrackId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "the weeknd::blinding lights",
                normalizedTitle = "Blinding Lights",
                normalizedArtist = "The Weeknd",
                album = "After Hours",
                durationMs = 200_000L,
                rawTitle = "Blinding Lights",
                rawArtist = "The Weeknd",
                artworkUriOrHash = null
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = spotifyAd1Id,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 31_000L,
                actualListenedMs = 30_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = spotifyAd2Id,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 32_000L,
                endedAtEpoch = 47_000L,
                actualListenedMs = 15_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = realTrackId,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 50_000L,
                endedAtEpoch = 250_000L,
                actualListenedMs = 200_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        val result = repository.cleanAndRenormalizeLibrary()
        assertEquals(2, result.removedAdTracks)
        assertEquals(2, result.removedAdEvents)

        val remainingTracks = trackDao.getAll()
        assertEquals(1, remainingTracks.size)
        assertEquals("Blinding Lights", remainingTracks[0].normalizedTitle)

        val remainingEvents = eventDao.getAll()
        assertEquals(1, remainingEvents.size)
        assertEquals(realTrackId, remainingEvents[0].trackId)
    }

    @Test
    fun cleanAndRenormalizeLibrary_removesAdsMergesDuplicatesAndIsIdempotent() = runTest {
        val trackDao = database.trackDao()
        val eventDao = database.listenEventDao()

        // 1. Pre-existing ad track + listen event
        val adTrackId = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "spotify::advertisement",
                normalizedTitle = "Advertisement",
                normalizedArtist = "Spotify",
                album = "",
                durationMs = 30_000L,
                rawTitle = "Advertisement",
                rawArtist = "Spotify",
                artworkUriOrHash = null
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = adTrackId,
                sourcePackage = "com.spotify.music",
                startedAtEpoch = 1_000L,
                endedAtEpoch = 31_000L,
                actualListenedMs = 30_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        // 2. Two legacy duplicate tracks for Drake ("Drake - Topic" & "Drake VEVO")
        val drake1Id = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "drake topic::one dance",
                normalizedTitle = "One Dance",
                normalizedArtist = "Drake - Topic",
                album = "",
                durationMs = 0L,
                rawTitle = "One Dance (Official Audio)",
                rawArtist = "Drake - Topic",
                artworkUriOrHash = null
            )
        )
        val drake2Id = trackDao.insertIgnore(
            TrackEntity(
                dedupKey = "drake vevo::one dance",
                normalizedTitle = "One Dance",
                normalizedArtist = "Drake VEVO",
                album = "Views",
                durationMs = 174_000L,
                rawTitle = "One Dance",
                rawArtist = "Drake VEVO",
                artworkUriOrHash = "content://art/views"
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = drake1Id,
                sourcePackage = "com.google.android.apps.youtube.music",
                startedAtEpoch = 100_000L,
                endedAtEpoch = 200_000L,
                actualListenedMs = 100_000L,
                completedRatio = 0.57f,
                wasSkipped = false
            )
        )
        eventDao.insert(
            ListenEventEntity(
                trackId = drake2Id,
                sourcePackage = "com.google.android.apps.youtube.music",
                startedAtEpoch = 300_000L,
                endedAtEpoch = 474_000L,
                actualListenedMs = 174_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )
        // Corrupt 0ms event
        eventDao.insert(
            ListenEventEntity(
                trackId = drake1Id,
                sourcePackage = "com.google.android.apps.youtube.music",
                startedAtEpoch = 500_000L,
                endedAtEpoch = 500_000L,
                actualListenedMs = 0L,
                completedRatio = 0f,
                wasSkipped = true
            )
        )

        val firstRun = repository.cleanAndRenormalizeLibrary()
        assertEquals(1, firstRun.removedAdTracks)
        assertEquals(2, firstRun.removedAdEvents) // 1 ad event + 1 zero-duration event
        assertEquals(1, firstRun.mergedDuplicateTracks)
        assertEquals(1, firstRun.updatedTracks)
        assertTrue(firstRun.hasChanges)

        val remainingTracks = database.trackDao().getAll()
        assertEquals(1, remainingTracks.size)
        assertEquals("Drake", remainingTracks[0].normalizedArtist)
        assertEquals("One Dance", remainingTracks[0].normalizedTitle)
        assertEquals("Views", remainingTracks[0].album)
        assertEquals(174_000L, remainingTracks[0].durationMs)
        assertEquals("content://art/views", remainingTracks[0].artworkUriOrHash)

        val remainingEvents = database.listenEventDao().getAll()
        assertEquals(2, remainingEvents.size)
        assertEquals(274_000L, remainingEvents.sumOf { it.actualListenedMs })

        // Second run must be completely idempotent
        val secondRun = repository.cleanAndRenormalizeLibrary()
        assertFalse(secondRun.hasChanges)
        assertEquals(0, secondRun.removedAdTracks)
        assertEquals(0, secondRun.removedAdEvents)
        assertEquals(0, secondRun.mergedDuplicateTracks)
        assertEquals(0, secondRun.updatedTracks)
    }

    @Test
    fun insightsCalculator_excludesUnknownArtistFromArtist100Milestone() {
        val unknownEvents = List(120) { index ->
            ListenEventInsightSample(
                startedAtEpoch = 1_700_000_000_000L + index * 60_000L,
                actualListenedMs = 60_000L,
                trackId = 1L,
                artistKey = if (index % 2 == 0) "unknown artist" else "   ",
                wasSkipped = false
            )
        }
        val milestones = ListeningInsightsCalculator.computeMilestones(
            events = unknownEvents,
            streak = ListeningStreak()
        )
        val artist100 = milestones.first { it.type == MilestoneType.ARTIST_100 }
        assertEquals(0L, artist100.currentValue)
        assertFalse(artist100.unlocked)
    }
}
