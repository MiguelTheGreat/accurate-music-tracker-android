package com.musictracker.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.musictracker.app.data.artwork.ArtworkCacheManager
import com.musictracker.app.data.artwork.ArtworkHttpClient
import com.musictracker.app.data.artwork.ArtworkRepository
import com.musictracker.app.data.artwork.OnlineArtworkFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var currentTimeMs = 1_000_000L
    private lateinit var cacheManager: ArtworkCacheManager
    private lateinit var fakeHttpClient: FakeArtworkHttpClient
    private lateinit var onlineEnabled: MutableStateFlow<Boolean>
    private lateinit var repository: ArtworkRepository

    @Before
    fun setUp() {
        currentTimeMs = 1_000_000L
        cacheManager = ArtworkCacheManager(
            cacheRootDir = tempFolder.newFolder("artwork"),
            negativeCacheTtlMs = 60_000L,
            nowMsProvider = { currentTimeMs }
        )
        fakeHttpClient = FakeArtworkHttpClient()
        onlineEnabled = MutableStateFlow(true)
        repository = ArtworkRepository(
            cacheManager = cacheManager,
            onlineFetcher = OnlineArtworkFetcher(fakeHttpClient),
            onlineArtworkEnabledFlow = onlineEnabled,
            contentResolver = null,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun resolveTrackArtwork_returnsCachedWebpOfflineFirst_withoutHttpCalls() = runTest {
        val dedupKey = "daft punk::instant crush"
        val saved = repository.saveTrackArtworkBitmap(dedupKey, createSampleBitmap(Color.BLUE))
        assertNotNull(saved)
        assertTrue(saved!!.name.endsWith(".webp"))

        val resolved = repository.resolveTrackArtwork(
            dedupKey = dedupKey,
            artist = "Daft Punk",
            title = "Instant Crush",
            rawArtworkUri = null
        )

        assertEquals(saved.absolutePath, resolved?.absolutePath)
        assertTrue("Expected zero HTTP calls on local cache hit", fakeHttpClient.requestedUrls.isEmpty())
    }

    @Test
    fun resolveTrackArtwork_whenOnlineToggleOff_skipsHttpFallback() = runTest {
        onlineEnabled.value = false

        val resolved = repository.resolveTrackArtwork(
            dedupKey = "radiohead::reckoner",
            artist = "Radiohead",
            title = "Reckoner",
            rawArtworkUri = "https://example.com/cover.jpg"
        )

        assertNull(resolved)
        assertTrue("Expected zero HTTP calls when online artwork toggle is OFF", fakeHttpClient.requestedUrls.isEmpty())
    }

    @Test
    fun resolveTrackArtwork_usesDeezerCover_andPersistsLocalWebp() = runTest {
        val imageBytes = createSamplePngBytes(Color.MAGENTA)
        fakeHttpClient.textResponses["api.deezer.com/search?"] = """
            {"data":[{"album":{"cover_xl":"https://cdn.deezer.com/cover_xl.jpg"}}]}
        """.trimIndent()
        fakeHttpClient.byteResponses["https://cdn.deezer.com/cover_xl.jpg"] = imageBytes

        val initialRevision = repository.artworkRevision.value
        val file = repository.resolveTrackArtwork(
            dedupKey = "the weeknd::blinding lights",
            artist = "The Weeknd",
            title = "Blinding Lights",
            rawArtworkUri = null
        )

        assertNotNull(file)
        assertTrue(file!!.exists() && file.length() > 0L)
        assertTrue(file.name.endsWith(".webp"))
        assertEquals(initialRevision + 1, repository.artworkRevision.value)
        assertEquals(file.absolutePath, repository.getCachedTrackArtworkFile("the weeknd::blinding lights")?.absolutePath)
    }

    @Test
    fun resolveTrackArtwork_fallsBackToItunesWhenDeezerEmpty() = runTest {
        val imageBytes = createSamplePngBytes(Color.CYAN)
        fakeHttpClient.textResponses["api.deezer.com/search?"] = """{"data":[]}"""
        fakeHttpClient.textResponses["itunes.apple.com/search?"] = """
            {"results":[{"artworkUrl100":"https://is1-ssl.mzstatic.com/image/100x100bb.jpg"}]}
        """.trimIndent()
        fakeHttpClient.byteResponses["https://is1-ssl.mzstatic.com/image/600x600bb.jpg"] = imageBytes

        val file = repository.resolveTrackArtwork(
            dedupKey = "nujabes::arurian dance",
            artist = "Nujabes",
            title = "Aruarian Dance",
            rawArtworkUri = null
        )

        assertNotNull(file)
        assertTrue(fakeHttpClient.requestedUrls.any { it.contains("itunes.apple.com/search") })
        assertTrue(fakeHttpClient.requestedUrls.any { it == "https://is1-ssl.mzstatic.com/image/600x600bb.jpg" })
    }

    @Test
    fun resolveTrackArtwork_cachesNegativeMissWithinTtl_thenRetriesAfterExpiry() = runTest {
        fakeHttpClient.textResponses["api.deezer.com/search?"] = """{"data":[]}"""
        fakeHttpClient.textResponses["itunes.apple.com/search?"] = """{"results":[]}"""

        val first = repository.resolveTrackArtwork(
            dedupKey = "obscure::unreleased",
            artist = "Obscure",
            title = "Unreleased",
            rawArtworkUri = null
        )
        assertNull(first)
        val callsAfterFirstMiss = fakeHttpClient.requestedUrls.size
        assertEquals(2, callsAfterFirstMiss)

        // Within 60s negative TTL: should not hit HTTP again.
        currentTimeMs += 30_000L
        val second = repository.resolveTrackArtwork(
            dedupKey = "obscure::unreleased",
            artist = "Obscure",
            title = "Unreleased",
            rawArtworkUri = null
        )
        assertNull(second)
        assertEquals(callsAfterFirstMiss, fakeHttpClient.requestedUrls.size)

        // Past 60s negative TTL: should retry HTTP.
        currentTimeMs += 31_000L
        repository.resolveTrackArtwork(
            dedupKey = "obscure::unreleased",
            artist = "Obscure",
            title = "Unreleased",
            rawArtworkUri = null
        )
        assertTrue(fakeHttpClient.requestedUrls.size > callsAfterFirstMiss)
    }

    @Test
    fun resolveArtistArtwork_fetchesDeezerPortrait_andServesSubsequentCallsFromCache() = runTest {
        val imageBytes = createSamplePngBytes(Color.GREEN)
        fakeHttpClient.textResponses["api.deezer.com/search/artist?"] = """
            {"data":[{"picture_xl":"https://cdn.deezer.com/artist_xl.jpg"}]}
        """.trimIndent()
        fakeHttpClient.byteResponses["https://cdn.deezer.com/artist_xl.jpg"] = imageBytes

        val first = repository.resolveArtistArtwork(
            artistKey = "frank ocean",
            artistName = "Frank Ocean"
        )
        assertNotNull(first)
        assertTrue(first!!.name.endsWith(".webp"))
        val callsAfterFetch = fakeHttpClient.requestedUrls.size
        assertEquals(2, callsAfterFetch)

        val second = repository.resolveArtistArtwork(
            artistKey = "frank ocean",
            artistName = "Frank Ocean"
        )
        assertEquals(first.absolutePath, second?.absolutePath)
        assertEquals(callsAfterFetch, fakeHttpClient.requestedUrls.size)
    }

    @Test
    fun resolveArtistArtwork_fallsBackToCachedTrackCover_whenOnlineUnavailable() = runTest {
        onlineEnabled.value = false
        val trackDedupKey = "tame impala::let it happen"
        val cachedTrackFile = repository.saveTrackArtworkBitmap(trackDedupKey, createSampleBitmap(Color.RED))
        assertNotNull(cachedTrackFile)

        val resolved = repository.resolveArtistArtwork(
            artistKey = "tame impala",
            artistName = "Tame Impala",
            fallbackTrackDedupKey = trackDedupKey
        )

        assertEquals(cachedTrackFile!!.absolutePath, resolved?.absolutePath)
        assertTrue(fakeHttpClient.requestedUrls.isEmpty())
    }

    private fun createSampleBitmap(color: Int): Bitmap {
        return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    private fun createSamplePngBytes(color: Int): ByteArray {
        val bitmap = createSampleBitmap(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private class FakeArtworkHttpClient : ArtworkHttpClient {
        val requestedUrls = mutableListOf<String>()
        val textResponses = mutableMapOf<String, String>()
        val byteResponses = mutableMapOf<String, ByteArray>()

        override suspend fun getText(url: String): String? {
            requestedUrls += url
            return textResponses.entries.firstOrNull { (prefix, _) -> url.contains(prefix) }?.value
        }

        override suspend fun getBytes(url: String): ByteArray? {
            requestedUrls += url
            return byteResponses[url]
        }
    }
}
