package com.musictracker.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.theme.MusicTrackerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersStatusCardsSearchableAllowlistReduceTransparencyAndRealSafExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = FakeSystemPermissionHelper(
            notificationAccessGranted = true,
            ignoringBatteryOptimizations = false,
            installedApps = listOf(
                InstalledAppInfo("com.google.android.apps.youtube.music", "YouTube Music", true),
                InstalledAppInfo("com.spotify.music", "Spotify", true),
                InstalledAppInfo("com.android.chrome", "Chrome", false)
            )
        )
        val allowlist = FakeAllowlistRepository(setOf("com.google.android.apps.youtube.music"))
        val reduceTransparency = MutableStateFlow(false)
        val exporter = FakeDataExporter()

        val viewModel = SettingsViewModel(
            systemPermissionHelper = permissions,
            allowlistRepository = allowlist,
            reduceTransparencyFlow = reduceTransparency,
            setReduceTransparency = { reduceTransparency.value = it },
            dataExporter = exporter,
            ioDispatcher = Dispatchers.Unconfined
        )

        val jsonUri = Uri.parse("content://com.musictracker.test/export.json")
        val sqliteUri = Uri.parse("content://com.musictracker.test/backup.db")
        val jsonOut = ByteArrayOutputStream()
        val sqliteOut = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(jsonUri, jsonOut)
        shadowOf(context.contentResolver).registerOutputStream(sqliteUri, sqliteOut)

        var nextSafUri: Uri? = jsonUri
        val fakeRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    dispatchResult(requestCode, nextSafUri)
                }
            }
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                MusicTrackerTheme {
                    SettingsRoute(
                        viewModel = viewModel,
                        systemPermissionHelper = permissions
                    )
                }
            }
        }

        // Verify permission status cards & deep-link clicks
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_STATUS).assertTextContains("Active")
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_NOTIF_BTN).performClick()
        assertEquals(1, permissions.openNotificationSettingsCalls)

        composeTestRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_STATUS)
            .assertTextContains("Optimized", substring = true)
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_BATTERY_BTN).performClick()
        assertEquals(1, permissions.requestBatteryExemptionCalls)

        // Verify SAF JSON export writes payload and shows feedback
        nextSafUri = jsonUri
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_EXPORT_JSON_BTN))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, exporter.jsonExportCalls)
        assertTrue(jsonOut.toString().contains("schema_version"))
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_EXPORT_FEEDBACK))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertIsDisplayed()

        // Verify SAF SQLite backup writes payload
        nextSafUri = sqliteUri
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_SQLITE_BTN).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, exporter.sqliteExportCalls)
        assertTrue(sqliteOut.size() > 0)

        // Verify Reduce Transparency switch toggles state
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH)
            .assertIsOff()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_REDUCE_TRANSPARENCY_SWITCH).assertIsOn()
        assertTrue(reduceTransparency.value)

        // Verify searchable App Allowlist filters and toggles
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_ALLOWLIST_SEARCH))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_ALLOWLIST_SEARCH)
            .performTextInput("Spotify")
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.settingsAllowlistSwitch("com.spotify.music")))
        composeTestRule.onNodeWithTag(TestTags.settingsAllowlistSwitch("com.spotify.music"))
            .assertIsOff()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.settingsAllowlistSwitch("com.spotify.music")).assertIsOn()
        assertEquals(listOf("com.spotify.music" to true), allowlist.toggleHistory)
    }

    @Test
    fun safCancelProducesNoErrorFeedbackBanner() {
        val permissions = FakeSystemPermissionHelper()
        val viewModel = SettingsViewModel(
            systemPermissionHelper = permissions,
            allowlistRepository = FakeAllowlistRepository(),
            reduceTransparencyFlow = MutableStateFlow(false),
            setReduceTransparency = {},
            dataExporter = FakeDataExporter(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val cancelRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    dispatchResult(requestCode, null)
                }
            }
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides cancelRegistryOwner) {
                MusicTrackerTheme {
                    SettingsRoute(
                        viewModel = viewModel,
                        systemPermissionHelper = permissions
                    )
                }
            }
        }

        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(TestTags.SETTINGS_EXPORT_JSON_BTN))
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_JSON_BTN).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.SETTINGS_EXPORT_FEEDBACK).assertDoesNotExist()
    }
}
