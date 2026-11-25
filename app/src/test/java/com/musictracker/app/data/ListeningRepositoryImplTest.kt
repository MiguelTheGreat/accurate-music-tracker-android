package com.musictracker.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.data.db.MusicTrackerDatabase
import com.musictracker.app.data.repository.ListeningRepositoryImpl
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.normalization.CanonicalMetadataNormalizer
import com.musictracker.app.domain.normalization.MetadataNormalizer
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.domain.normalization.NormalizedTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningRepositoryImplTest {

    private lateinit var database: MusicTrackerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MusicTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalMetadataNormalizer_collapsesWhitespaceHandlesBlankArtistAndBuildsCanonicalKey() {
        val normalizer = CanonicalMetadataNormalizer()

        val result1 = normalizer.normalize("  Blinding   Lights!  ", "  The   Weeknd ")
        val result2 = normalizer.normalize("blinding lights", "the weeknd")
        assertEquals("Blinding Lights!", result1.normalizedTitle)
        assertEquals("The Weeknd", result1.normalizedArtist)
        assertEquals("the weeknd::blinding lights", result1.dedupKey)
        assertEquals(result1.dedupKey, result2.dedupKey)

        val blankArtist = normalizer.normalize("Untitled Track", "   ")
        assertEquals("Unknown Artist", blankArtist.normalizedArtist)
        assertEquals("unknown artist::untitled track", blankArtist.dedupKey)
    }

    @Test
    fun recordCompletedSession_persistsNormalizedTrackAndListenEvent() = runTest {
        val fakeNormalizer = object : MetadataNormalizer {
            override fun normalize(rawTitle: String, rawArtist: String) = NormalizedTrack(
                normalizedTitle = rawTitle.removeSuffix(" (Official Audio)"),
                normalizedArtist = rawArtist.removeSuffix(" - Topic"),
                dedupKey = "the weeknd::blinding lights"
            )
        }
        val repo = ListeningRepositoryImpl(database, fakeNormalizer)

        repo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.google.android.apps.youtube.music",
                rawTitle = "Blinding Lights (Official Audio)",
                rawArtist = "The Weeknd - Topic",
                album = "After Hours",
                durationMs = 200_000L,
                artworkUri = "https://example.com/art.jpg",
                startedAtEpoch = 10_000L,
                endedAtEpoch = 190_000L,
                actualListenedMs = 180_000L,
                completedRatio = 0.9f,
                wasSkipped = false
            )
        )

        val tracks = database.trackDao().getAll()
        val events = database.listenEventDao().getAll()
        assertEquals(1, tracks.size)
        assertEquals(1, events.size)
        assertEquals("Blinding Lights", tracks[0].normalizedTitle)
        assertEquals("The Weeknd", tracks[0].normalizedArtist)
        assertEquals("Blinding Lights (Official Audio)", tracks[0].rawTitle)
        assertEquals("The Weeknd - Topic", tracks[0].rawArtist)
        assertEquals(tracks[0].id, events[0].trackId)
        assertEquals(180_000L, events[0].actualListenedMs)
    }

    @Test
    fun recordCompletedSession_existingTrackPatchesMissingMetadataAndAppendsEvent() = runTest {
        val repo = ListeningRepositoryImpl(database, CanonicalMetadataNormalizer())

        repo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Get Lucky",
                rawArtist = "Daft Punk",
                album = "",
                durationMs = 0L,
                artworkUri = null,
                startedAtEpoch = 1_000L,
                endedAtEpoch = 101_000L,
                actualListenedMs = 100_000L,
                completedRatio = 0f,
                wasSkipped = false
            )
        )
        repo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "get  lucky!",
                rawArtist = "DAFT PUNK",
                album = "Random Access Memories",
                durationMs = 248_000L,
                artworkUri = "content://art/ram",
                startedAtEpoch = 200_000L,
                endedAtEpoch = 448_000L,
                actualListenedMs = 248_000L,
                completedRatio = 1.0f,
                wasSkipped = false
            )
        )

        val tracks = database.trackDao().getAll()
        val events = database.listenEventDao().getAll()
        assertEquals(1, tracks.size)
        assertEquals(2, events.size)
        assertEquals("Random Access Memories", tracks[0].album)
        assertEquals(248_000L, tracks[0].durationMs)
        assertEquals("content://art/ram", tracks[0].artworkUriOrHash)
        assertEquals(tracks[0].id, events[0].trackId)
        assertEquals(tracks[0].id, events[1].trackId)
    }

    @Test
    fun observeSummary_computesSkipRateSafelyForEmptyAndPopulatedDb() = runTest {
        val repo = ListeningRepositoryImpl(database, CanonicalMetadataNormalizer())

        assertEquals(ListeningSummary(0L, 0, 0, 0f), repo.observeSummary(0L).first())

        val sessions = listOf(
            true to 15_000L,
            false to 180_000L,
            false to 200_000L,
            false to 210_000L
        )
        sessions.forEachIndexed { idx, (skipped, listenedMs) ->
            repo.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Track $idx",
                    rawArtist = "Artist",
                    album = "Album",
                    durationMs = 200_000L,
                    artworkUri = null,
                    startedAtEpoch = 1_000L + idx * 300_000L,
                    endedAtEpoch = 1_000L + idx * 300_000L + listenedMs,
                    actualListenedMs = listenedMs,
                    completedRatio = listenedMs / 200_000f,
                    wasSkipped = skipped
                )
            )
        }

        val summary = repo.observeSummary(0L).first()
        assertEquals(605_000L, summary.totalListenedMs)
        assertEquals(4, summary.playCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(0.25f, summary.skipRate, 0.0001f)
    }

    @Test
    fun renormalizeAllTracks_mergesCollidingTracksSalvagesMetadataAndPreservesAllListenEvents() = runTest {
        var stripNoise = false
        val dynamicNormalizer = object : MetadataNormalizer {
            override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack {
                val title = if (stripNoise) rawTitle.removeSuffix(" (Official Video)").trim() else rawTitle.trim()
                val artist = if (stripNoise) rawArtist.removeSuffix(" - Topic").trim() else rawArtist.trim()
                return NormalizedTrack(
                    normalizedTitle = title,
                    normalizedArtist = artist,
                    dedupKey = CanonicalMetadataNormalizer.buildDedupKey(artist, title)
                )
            }
        }
        val repo = ListeningRepositoryImpl(database, dynamicNormalizer)

        // Track 1 (2 events, missing album/artwork)
        repeat(2) { i ->
            repo.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.google.android.apps.youtube.music",
                    rawTitle = "Starboy (Official Video)",
                    rawArtist = "The Weeknd - Topic",
                    album = "",
                    durationMs = 0L,
                    artworkUri = null,
                    startedAtEpoch = 10_000L + i * 200_000L,
                    endedAtEpoch = 110_000L + i * 200_000L,
                    actualListenedMs = 100_000L,
                    completedRatio = 0.5f,
                    wasSkipped = false
                )
            )
        }
        // Track 2 (3 events, has album/duration/artwork)
        repeat(3) { i ->
            repo.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Starboy",
                    rawArtist = "The Weeknd",
                    album = "Starboy",
                    durationMs = 230_000L,
                    artworkUri = "content://art/starboy",
                    startedAtEpoch = 500_000L + i * 250_000L,
                    endedAtEpoch = 700_000L + i * 250_000L,
                    actualListenedMs = 200_000L,
                    completedRatio = 0.87f,
                    wasSkipped = false
                )
            )
        }

        assertEquals(2, database.trackDao().getAll().size)
        assertEquals(5, database.listenEventDao().getAll().size)

        // Enable improved normalization rules -> both collapse to same dedup_key
        stripNoise = true
        val affected = repo.renormalizeAllTracks()
        assertEquals(2, affected) // 1 merged duplicate + 1 updated survivor

        val survivingTracks = database.trackDao().getAll()
        val allEvents = database.listenEventDao().getAll()
        assertEquals(1, survivingTracks.size)
        assertEquals(5, allEvents.size)

        val survivor = survivingTracks.first()
        assertEquals("Starboy", survivor.normalizedTitle)
        assertEquals("The Weeknd", survivor.normalizedArtist)
        assertEquals("Starboy", survivor.album)
        assertEquals(230_000L, survivor.durationMs)
        assertEquals("content://art/starboy", survivor.artworkUriOrHash)
        assertEquals(true, allEvents.all { it.trackId == survivor.id })

        val topTracks = repo.observeTopTracks(0L).first()
        assertEquals(1, topTracks.size)
        assertEquals(800_000L, topTracks[0].totalListenedMs)
        assertEquals(5, topTracks[0].playCount)

        // Re-running with unchanged rules is idempotent
        assertEquals(0, repo.renormalizeAllTracks())
    }

    @Test
    fun renormalizeAllTracks_handlesDedupKeySwapWithoutUniqueConstraintFailure() = runTest {
        var swapped = false
        val swapNormalizer = object : MetadataNormalizer {
            override fun normalize(rawTitle: String, rawArtist: String): NormalizedTrack {
                val title = if (!swapped) {
                    rawTitle
                } else if (rawTitle == "Alpha") {
                    "Beta"
                } else {
                    "Alpha"
                }
                return NormalizedTrack(
                    normalizedTitle = title,
                    normalizedArtist = rawArtist,
                    dedupKey = CanonicalMetadataNormalizer.buildDedupKey(rawArtist, title)
                )
            }
        }
        val repo = ListeningRepositoryImpl(database, swapNormalizer)

        repo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Alpha",
                rawArtist = "Artist",
                album = "",
                durationMs = 100_000L,
                artworkUri = null,
                startedAtEpoch = 1_000L,
                endedAtEpoch = 51_000L,
                actualListenedMs = 50_000L,
                completedRatio = 0.5f,
                wasSkipped = false
            )
        )
        repo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.spotify.music",
                rawTitle = "Beta",
                rawArtist = "Artist",
                album = "",
                durationMs = 100_000L,
                artworkUri = null,
                startedAtEpoch = 60_000L,
                endedAtEpoch = 110_000L,
                actualListenedMs = 50_000L,
                completedRatio = 0.5f,
                wasSkipped = false
            )
        )

        swapped = true
        val affected = repo.renormalizeAllTracks()
        assertEquals(2, affected)

        val tracks = database.trackDao().getAll()
        assertEquals(2, tracks.size)
        assertEquals("Beta", tracks[0].normalizedTitle)
        assertEquals("Alpha", tracks[1].normalizedTitle)
    }

    @Test
    fun renormalizeAllTracks_withMusicMetadataNormalizer_mergesLegacyVariantsAndPreservesRawFields() = runTest {
        val legacyRepo = ListeningRepositoryImpl(database, CanonicalMetadataNormalizer())

        // Track A (YT Music noisy, 2 events, missing album/duration/artwork)
        repeat(2) { i ->
            legacyRepo.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.google.android.apps.youtube.music",
                    rawTitle = "Blinding Lights (Official Audio) [Lyrics]",
                    rawArtist = "The Weeknd - Topic",
                    album = "",
                    durationMs = 0L,
                    artworkUri = null,
                    startedAtEpoch = 10_000L + i * 200_000L,
                    endedAtEpoch = 160_000L + i * 200_000L,
                    actualListenedMs = 150_000L,
                    completedRatio = 0f,
                    wasSkipped = false
                )
            )
        }

        // Track B (YT Music unpacked generic artist, 1 event)
        legacyRepo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.google.android.apps.youtube.music",
                rawTitle = "The Weeknd - Blinding Lights | Official Video",
                rawArtist = "YouTube Music",
                album = "",
                durationMs = 200_000L,
                artworkUri = null,
                startedAtEpoch = 500_000L,
                endedAtEpoch = 680_000L,
                actualListenedMs = 180_000L,
                completedRatio = 0.9f,
                wasSkipped = false
            )
        )

        // Track C (Spotify clean, 2 events, full metadata)
        repeat(2) { i ->
            legacyRepo.recordCompletedSession(
                CompletedListenSession(
                    sourcePackage = "com.spotify.music",
                    rawTitle = "Blinding Lights",
                    rawArtist = "The Weeknd",
                    album = "After Hours",
                    durationMs = 200_000L,
                    artworkUri = "content://art/after_hours",
                    startedAtEpoch = 800_000L + i * 250_000L,
                    endedAtEpoch = 1_000_000L + i * 250_000L,
                    actualListenedMs = 200_000L,
                    completedRatio = 1.0f,
                    wasSkipped = false
                )
            )
        }

        // Track D (Valid artist with spaced dash in title, 1 event)
        legacyRepo.recordCompletedSession(
            CompletedListenSession(
                sourcePackage = "com.google.android.apps.youtube.music",
                rawTitle = "One More Time - Radio Edit (Official Video)",
                rawArtist = "Daft Punk",
                album = "Discovery",
                durationMs = 235_000L,
                artworkUri = null,
                startedAtEpoch = 1_500_000L,
                endedAtEpoch = 1_700_000L,
                actualListenedMs = 200_000L,
                completedRatio = 0.85f,
                wasSkipped = false
            )
        )

        assertEquals(4, database.trackDao().getAll().size)
        assertEquals(6, database.listenEventDao().getAll().size)

        val upgradedRepo = ListeningRepositoryImpl(database, MusicMetadataNormalizer())
        val affected = upgradedRepo.renormalizeAllTracks()
        assertEquals(4, affected) // 2 merged duplicates + 2 updated survivors

        val survivingTracks = database.trackDao().getAll()
        val allEvents = database.listenEventDao().getAll()
        assertEquals(2, survivingTracks.size)
        assertEquals(6, allEvents.size)

        val blindingSurvivor = survivingTracks.first { it.dedupKey == "the weeknd::blinding lights" }
        assertEquals("Blinding Lights", blindingSurvivor.normalizedTitle)
        assertEquals("The Weeknd", blindingSurvivor.normalizedArtist)
        assertEquals("Blinding Lights (Official Audio) [Lyrics]", blindingSurvivor.rawTitle)
        assertEquals("The Weeknd - Topic", blindingSurvivor.rawArtist)
        assertEquals("After Hours", blindingSurvivor.album)
        assertEquals(200_000L, blindingSurvivor.durationMs)
        assertEquals("content://art/after_hours", blindingSurvivor.artworkUriOrHash)
        assertEquals(5, allEvents.count { it.trackId == blindingSurvivor.id })

        val daftPunkSurvivor = survivingTracks.first { it.dedupKey == "daft punk::one more time radio edit" }
        assertEquals("One More Time - Radio Edit", daftPunkSurvivor.normalizedTitle)
        assertEquals("Daft Punk", daftPunkSurvivor.normalizedArtist)
        assertEquals("One More Time - Radio Edit (Official Video)", daftPunkSurvivor.rawTitle)
        assertEquals(1, allEvents.count { it.trackId == daftPunkSurvivor.id })

        val topTracks = upgradedRepo.observeTopTracks(0L).first()
        assertEquals(2, topTracks.size)
        assertEquals(880_000L, topTracks[0].totalListenedMs)
        assertEquals(5, topTracks[0].playCount)

        // Second run must be idempotent
        assertEquals(0, upgradedRepo.renormalizeAllTracks())
    }
}
