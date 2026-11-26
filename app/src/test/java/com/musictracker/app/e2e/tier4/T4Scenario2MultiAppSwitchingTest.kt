package com.musictracker.app.e2e.tier4

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.e2e.harness.E2eTestEnvironment
import com.musictracker.app.e2e.harness.FakeMediaControllerDriver
import com.musictracker.app.e2e.harness.FakeSystemPermissionHelper
import com.musictracker.app.e2e.harness.scrollToTag
import com.musictracker.app.e2e.harness.setLiquidGlassContent
import com.musictracker.app.e2e.harness.waitUntilIdle
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier 4 — Scenario 2: Multi-App Switching (YouTube Music + Spotify dedup +
 * ignored main YouTube video + live Settings allowlist toggle for VLC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T4Scenario2MultiAppSwitchingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var env: E2eTestEnvironment
    private lateinit var coordinatorScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start with VLC initially excluded so user explicitly enables VLC via Settings UI
        env = E2eTestEnvironment(
            context = context,
            defaultAllowlistPackages = setOf(
                "com.google.android.apps.youtube.music",
                "com.spotify.music"
            )
        )
        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
        env.close()
    }

    @Test
    fun scenario2_multiAppSwitchingDedupIgnoredYouTubeAndLiveVlcToggle() {
        val coordinator = env.createCoordinator(scope = coordinatorScope)
        coordinator.start()

        val ytMusic = FakeMediaControllerDriver(
            packageName = "com.google.android.apps.youtube.music",
            tokenId = "ytm_token",
            initialMetadata = MediaMetadataSnapshot(
                title = "Blinding Lights (Official Audio)",
                artist = "The Weeknd - Topic",
                durationMs = 200_000L
            ),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PLAYING, positionMs = 0L)
        )
        val spotify = FakeMediaControllerDriver(
            packageName = "com.spotify.music",
            tokenId = "spotify_token",
            initialMetadata = MediaMetadataSnapshot(
                title = "Blinding Lights",
                artist = "The Weeknd",
                durationMs = 200_000L
            ),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )
        val mainYouTube = FakeMediaControllerDriver(
            packageName = "com.google.android.youtube",
            tokenId = "youtube_video_token",
            initialMetadata = MediaMetadataSnapshot(
                title = "5 Minute Documentary",
                artist = "Channel",
                durationMs = 300_000L
            ),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )
        val vlc = FakeMediaControllerDriver(
            packageName = "org.videolan.vlc",
            tokenId = "vlc_token",
            initialMetadata = MediaMetadataSnapshot(
                title = "Local Studio Master.flac",
                artist = "Indie Band",
                durationMs = 240_000L
            ),
            initialPlaybackState = PlaybackStateSnapshot(PlaybackStatus.PAUSED, positionMs = 0L)
        )

        coordinator.onActiveSessionsChanged(listOf(ytMusic, spotify, mainYouTube, vlc))
        val settleDeadline = System.currentTimeMillis() + 3_000L
        while (vlc.isRegistered && System.currentTimeMillis() < settleDeadline) {
            Thread.sleep(20L)
        }
        assertTrue(ytMusic.isRegistered)
        assertTrue(spotify.isRegistered)
        assertFalse("Main YouTube must be ignored", mainYouTube.isRegistered)
        assertFalse("VLC must not be registered until enabled in Settings", vlc.isRegistered)

        // 1. Listen to Blinding Lights on YouTube Music for 90s
        env.clock.advanceBy(90_000L)

        // 2. Switch to Blinding Lights on Spotify for 110s
        ytMusic.pause(90_000L)
        spotify.play(0L)
        env.clock.advanceBy(110_000L)

        // 3. Pause Spotify and watch a 5-minute video on main YouTube (must produce 0 DB rows)
        spotify.pause(110_000L)
        mainYouTube.play(0L)
        env.clock.advanceBy(300_000L)
        mainYouTube.stop(300_000L)

        // 4. Enable VLC in Settings UI allowlist without restarting the service
        val permHelper = FakeSystemPermissionHelper(
            notificationAccessGranted = true,
            installedApps = listOf(
                InstalledAppInfo("org.videolan.vlc", "VLC", isDefaultMusicApp = true),
                InstalledAppInfo("com.spotify.music", "Spotify", isDefaultMusicApp = true)
            )
        )
        val settingsVm = SettingsViewModel(
            systemPermissionHelper = permHelper,
            allowlistRepository = env.allowlistStore,
            accessibilityPreferencesStore = env.accessibilityPreferencesStore,
            dataExporter = env.dataExporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        composeRule.setLiquidGlassContent {
            SettingsRoute(viewModel = settingsVm, systemPermissionHelper = permHelper)
        }
        composeRule.waitUntilIdle { !settingsVm.uiState.value.isLoadingApps }

        val vlcSwitchTag = TestTags.settingsAllowlistSwitch("org.videolan.vlc")
        composeRule.scrollToTag(vlcSwitchTag)
        composeRule.onNodeWithTag(vlcSwitchTag).performClick()

        composeRule.waitUntilIdle { vlc.isRegistered }
        assertTrue("VLC must be dynamically registered without service restart", vlc.isRegistered)

        // 5. Play local FLAC track on VLC for 120s
        vlc.play(0L)
        env.clock.advanceBy(120_000L)

        runBlocking {
            coordinator.flushAndStop().join()
            env.waitForEventCount(3)
        }

        val tracks = runBlocking { env.database.trackDao().getAll() }
        val events = runBlocking { env.database.listenEventDao().getAll() }

        // Assert YouTube video produced 0 DB rows
        assertTrue(events.none { it.sourcePackage == "com.google.android.youtube" })

        // Assert 2 unique tracks: deduplicated Blinding Lights + VLC local FLAC
        assertEquals(2, tracks.size)
        assertEquals(3, events.size)

        val blindingLights = tracks.single { it.normalizedTitle == "Blinding Lights" }
        val blindingEvents = events.filter { it.trackId == blindingLights.id }
        assertEquals(2, blindingEvents.size)
        assertEquals(
            setOf("com.google.android.apps.youtube.music", "com.spotify.music"),
            blindingEvents.map { it.sourcePackage }.toSet()
        )
        assertEquals(200_000L, blindingEvents.sumOf { it.actualListenedMs })

        val vlcEvent = events.single { it.sourcePackage == "org.videolan.vlc" }
        assertEquals(120_000L, vlcEvent.actualListenedMs)
    }
}
