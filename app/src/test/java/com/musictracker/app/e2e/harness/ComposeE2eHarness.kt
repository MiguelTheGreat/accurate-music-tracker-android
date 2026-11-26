package com.musictracker.app.e2e.harness

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import com.musictracker.app.ui.theme.LiquidGlassAccessibilityState
import com.musictracker.app.ui.theme.MusicTrackerTheme

/**
 * Fake [ActivityResultRegistryOwner] for deterministic Storage Access Framework (SAF)
 * `CreateDocument` tests in Robolectric Compose UI tests.
 */
class FakeActivityResultRegistryOwner(
    private val onCreateDocument: (suggestedFilename: String) -> Uri?
) : ActivityResultRegistryOwner {

    val launchedFilenames = mutableListOf<String>()

    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            val filename = input?.toString().orEmpty()
            launchedFilenames.add(filename)
            val resultUri = onCreateDocument(filename)
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, resultUri as O)
        }
    }
}

/**
 * Renders [content] inside [MusicTrackerTheme] with optional [LiquidGlassAccessibilityState]
 * and optional [ActivityResultRegistryOwner] for SAF launcher testing.
 */
fun ComposeContentTestRule.setLiquidGlassContent(
    darkTheme: Boolean = true,
    reduceTransparency: Boolean = false,
    accessibilityOverride: LiquidGlassAccessibilityState? = null,
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    content: @Composable () -> Unit
) {
    setContent {
        if (activityResultRegistryOwner != null) {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner
            ) {
                MusicTrackerTheme(
                    darkTheme = darkTheme,
                    reduceTransparency = reduceTransparency,
                    accessibilityOverride = accessibilityOverride,
                    content = content
                )
            }
        } else {
            MusicTrackerTheme(
                darkTheme = darkTheme,
                reduceTransparency = reduceTransparency,
                accessibilityOverride = accessibilityOverride,
                content = content
            )
        }
    }
}

/**
 * Scrolls the first scrollable container (e.g., LazyColumn) until a node matching [tag] is composed and visible.
 */
fun ComposeContentTestRule.scrollToTag(tag: String) {
    onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        .performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
}

/**
 * Scrolls the first scrollable container (e.g., LazyColumn) until a node matching [text] is composed and visible.
 */
fun ComposeContentTestRule.scrollToText(text: String) {
    onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        .performScrollToNode(androidx.compose.ui.test.hasText(text))
}

/**
 * Polls [condition] while pumping the Android Main Looper so Room/DataStore Flow emissions
 * dispatched to Dispatchers.Main are processed deterministically under Robolectric.
 */
fun ComposeContentTestRule.waitUntilIdle(
    timeoutMillis: Long = 15_000L,
    condition: () -> Boolean
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    waitForIdle()
}


