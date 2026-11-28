package com.musictracker.app.ui.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendCalculatorTest {

    @Test
    fun encodeAndDecode_roundTripPreservesPayload() {
        val original = BlendPayload(
            v = 1,
            name = "Miguel",
            artists = listOf(
                BlendArtistEntry(name = "Daft Punk", plays = 42),
                BlendArtistEntry(name = "The Weeknd", plays = 28)
            ),
            tracks = listOf(
                BlendTrackEntry(title = "Instant Crush", artist = "Daft Punk", plays = 19),
                BlendTrackEntry(title = "Blinding Lights", artist = "The Weeknd", plays = 14)
            )
        )

        val encoded = BlendCalculator.encode(original)
        assertTrue(encoded.startsWith(BlendCalculator.PREFIX))

        val decoded = BlendCalculator.decode(encoded)
        assertTrue(decoded.isSuccess)
        assertEquals(original, decoded.getOrThrow())
    }

    @Test
    fun decode_invalidPrefixOrCorruptedPayload_returnsFailure() {
        assertTrue(BlendCalculator.decode("INVALID:abc123").isFailure)
        assertTrue(BlendCalculator.decode("").isFailure)
        assertTrue(BlendCalculator.decode(BlendCalculator.PREFIX).isFailure)
        assertTrue(BlendCalculator.decode("${BlendCalculator.PREFIX}!!!not-base64!!!").isFailure)
        // Valid URL-safe Base64, but corrupted zlib stream
        assertTrue(BlendCalculator.decode("${BlendCalculator.PREFIX}YWJjZGVmZ2hpamtsbW5vcA").isFailure)
    }

    @Test
    fun compare_identicalPayloads_returns100PercentAndAllShared() {
        val payload = BlendPayload(
            name = "User A",
            artists = listOf(
                BlendArtistEntry("Daft Punk", 30),
                BlendArtistEntry("Justice", 15)
            ),
            tracks = listOf(
                BlendTrackEntry("One More Time", "Daft Punk", 12)
            )
        )

        val result = BlendCalculator.compare(payload, payload)

        assertEquals(100, result.matchPercent)
        assertEquals(listOf("Daft Punk", "Justice"), result.sharedArtists)
        assertEquals(listOf("One More Time — Daft Punk"), result.sharedTracks)
    }

    @Test
    fun compare_disjointPayloads_returns0PercentAndNoShared() {
        val mine = BlendPayload(
            name = "User A",
            artists = listOf(BlendArtistEntry("Daft Punk", 25)),
            tracks = listOf(BlendTrackEntry("Aerodynamic", "Daft Punk", 10))
        )
        val friend = BlendPayload(
            name = "User B",
            artists = listOf(BlendArtistEntry("Taylor Swift", 25)),
            tracks = listOf(BlendTrackEntry("Cruel Summer", "Taylor Swift", 10))
        )

        val result = BlendCalculator.compare(mine, friend)

        assertEquals(0, result.matchPercent)
        assertTrue(result.sharedArtists.isEmpty())
        assertTrue(result.sharedTracks.isEmpty())
    }

    @Test
    fun compare_partialOverlap_returnsBoundedMatchAndExpectedSharedItems() {
        val mine = BlendPayload(
            name = "User A",
            artists = listOf(
                BlendArtistEntry("Daft Punk", 40),
                BlendArtistEntry("Justice", 20)
            ),
            tracks = listOf(
                BlendTrackEntry("One More Time", "Daft Punk", 15),
                BlendTrackEntry("D.A.N.C.E.", "Justice", 10)
            )
        )
        val friend = BlendPayload(
            name = "User B",
            artists = listOf(
                BlendArtistEntry("daft punk", 30),
                BlendArtistEntry("Kavinsky", 25)
            ),
            tracks = listOf(
                BlendTrackEntry("One More Time", "Daft Punk", 12),
                BlendTrackEntry("Nightcall", "Kavinsky", 18)
            )
        )

        val result = BlendCalculator.compare(mine, friend)

        assertTrue(result.matchPercent in 1..99)
        assertEquals(listOf("Daft Punk"), result.sharedArtists)
        assertEquals(listOf("One More Time — Daft Punk"), result.sharedTracks)
        assertEquals("Daft Punk", result.myTopArtist)
        assertEquals("daft punk", result.friendTopArtist)
    }
}
