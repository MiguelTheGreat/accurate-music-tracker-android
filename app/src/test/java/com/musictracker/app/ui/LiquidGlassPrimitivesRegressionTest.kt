package com.musictracker.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.ui.components.glass.LiquidGlassBackdropHost
import com.musictracker.app.ui.components.glass.LiquidGlassBackdropState
import com.musictracker.app.ui.components.glass.LiquidGlassSegmentedControl
import com.musictracker.app.ui.components.glass.LiquidGlassSurface
import com.musictracker.app.ui.components.glass.LocalLiquidGlassBackdrop
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.home.HomeScreenContent
import com.musictracker.app.ui.screens.home.HomeUiState
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.MusicTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiquidGlassPrimitivesRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun backdropHostProvidesNullInsideContentAndNonNullInsideOverlay() {
        var contentBackdrop: LiquidGlassBackdropState? = null
        var contentObserved = false
        var overlayBackdrop: LiquidGlassBackdropState? = null
        var overlayObserved = false
        var tick by mutableIntStateOf(0)

        composeTestRule.setContent {
            MusicTrackerTheme(
                accessibilityOverride = LiquidGlassAccessibilityState(
                    reduceTransparency = false,
                    highContrast = false,
                    reduceMotion = false
                )
            ) {
                LiquidGlassBackdropHost(
                    overlay = {
                        overlayBackdrop = LocalLiquidGlassBackdrop.current
                        overlayObserved = true
                        LiquidGlassSurface(
                            modifier = Modifier.testTag("overlay_glass")
                        ) {
                            Text("Overlay")
                        }
                    }
                ) {
                    contentBackdrop = LocalLiquidGlassBackdrop.current
                    contentObserved = true
                    Box(modifier = Modifier.fillMaxSize()) {
                        LiquidGlassSurface(
                            modifier = Modifier.testTag("content_glass")
                        ) {
                            Text("Content tick $tick")
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        assertTrue("Expected content() to compose", contentObserved)
        assertTrue("Expected overlay() to compose", overlayObserved)
        assertNull("content() must never receive LocalLiquidGlassBackdrop", contentBackdrop)
        assertNotNull("overlay() must receive LocalLiquidGlassBackdrop", overlayBackdrop)

        // Trigger a second frame/recomposition to ensure content still sees null and never self-records
        composeTestRule.runOnIdle { tick = 1 }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Content tick 1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overlay_glass").assertIsDisplayed()
        assertNull("content() must still see null backdrop on subsequent frames", contentBackdrop)
        assertNotNull("overlay() must still see non-null backdrop on subsequent frames", overlayBackdrop)
    }

    @Test
    fun segmentedControlHoldNonSelectedSegmentDoesNotLoopAndSwitchesOnClick() {
        val options = listOf("Today", "7 Days", "30 Days", "All Time")
        var selectedOption by mutableStateOf(options.first())
        var compositionCount = 0

        composeTestRule.setContent {
            MusicTrackerTheme {
                Box(modifier = Modifier.padding(16.dp)) {
                    SideEffect { compositionCount++ }
                    LiquidGlassSegmentedControl(
                        options = options,
                        selectedOption = selectedOption,
                        onOptionSelected = { selectedOption = it },
                        labelProvider = { it },
                        testTagProvider = { "seg_$it" }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("seg_Today").assertIsSelected()

        val compositionsBeforeHold = compositionCount

        // Press and hold a non-selected segment ("30 Days") without releasing
        composeTestRule.onNodeWithTag("seg_30 Days").performTouchInput {
            down(center)
        }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()

        val compositionsDuringHold = compositionCount - compositionsBeforeHold
        assertTrue(
            "Holding a non-selected segment must not trigger an infinite recomposition loop (delta=$compositionsDuringHold)",
            compositionsDuringHold < 15
        )
        // Still on Today until released
        assertEquals("Today", selectedOption)

        // Release completes click and switches selection cleanly to "30 Days"
        composeTestRule.onNodeWithTag("seg_30 Days").performTouchInput {
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals("30 Days", selectedOption)
        composeTestRule.onNodeWithTag("seg_30 Days").assertIsSelected()

        // Subsequent click switches cleanly to "7 Days"
        composeTestRule.onNodeWithTag("seg_7 Days").performClick()
        composeTestRule.waitForIdle()
        assertEquals("7 Days", selectedOption)
        composeTestRule.onNodeWithTag("seg_7 Days").assertIsSelected()
    }

    @Test
    fun nowTrackingCardRendersInLightThemeWithReducedTransparency() {
        val clock = FakeTrackerClock(elapsedMs = 20_000L)
        val playingTrack = NowTrackingState(
            sourcePackage = "com.google.android.apps.youtube.music",
            rawTitle = "Nights",
            rawArtist = "Frank Ocean",
            normalizedTitle = "Nights",
            normalizedArtist = "Frank Ocean",
            album = "Blonde",
            durationMs = 307_000L,
            artworkUri = null,
            isPlaying = true,
            accumulatedMs = 45_000L,
            segmentStartElapsedMs = 15_000L,
            isDebouncing = false
        )

        composeTestRule.setContent {
            MusicTrackerTheme(
                darkTheme = false,
                accessibilityOverride = LiquidGlassAccessibilityState(
                    reduceTransparency = true,
                    highContrast = false,
                    reduceMotion = false
                )
            ) {
                HomeScreenContent(
                    uiState = HomeUiState(
                        isNotificationAccessGranted = true,
                        nowTracking = playingTrack,
                        isLoading = false
                    ),
                    clock = clock,
                    onEnableNotificationAccess = {},
                    onDismissPermissionBanner = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(TestTags.HOME_NOW_TRACKING_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Nights").assertIsDisplayed()
        composeTestRule.onNodeWithText("Playing").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_LIVE_COUNTER).assertIsDisplayed()
    }
}
