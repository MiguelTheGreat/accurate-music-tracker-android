package com.musictracker.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.musictracker.app.data.preferences.AccessibilityPreferencesStore
import com.musictracker.app.data.preferences.AllowlistStore
import com.musictracker.app.data.preferences.DefaultAllowlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PreferencesStoresTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun newPrefsFile(): File =
        File(tempFolder.root, "prefs_${UUID.randomUUID()}.preferences_pb")

    @Test
    fun allowlistStore_initialStateSeedsDefaultMusicPlayersAndExcludesNonMusicApps() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { newPrefsFile() }
        )
        val store = AllowlistStore(dataStore)

        val initial = store.allowedPackages.first()
        assertEquals(DefaultAllowlist.packages, initial)
        assertTrue(initial.contains("com.google.android.apps.youtube.music"))
        assertTrue(initial.contains("com.spotify.music"))
        assertFalse(initial.contains("com.google.android.youtube"))
        assertFalse(initial.contains("com.android.chrome"))
    }

    @Test
    fun allowlistStore_setPackageAllowed_addsAndRemovesReactively() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { newPrefsFile() }
        )
        val store = AllowlistStore(dataStore)

        store.allowedPackages.test {
            val seeded = awaitItem()
            assertTrue(seeded.contains("com.spotify.music"))

            store.setPackageAllowed("com.example.player", allowed = true)
            val afterAdd = awaitItem()
            assertTrue(afterAdd.contains("com.example.player"))

            store.setPackageAllowed("com.spotify.music", allowed = false)
            val afterRemove = awaitItem()
            assertFalse(afterRemove.contains("com.spotify.music"))
            assertTrue(afterRemove.contains("com.example.player"))

            // Blank package name is a no-op
            store.setPackageAllowed("   ", allowed = true)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun allowlistStore_disablingAllDefaults_persistsEmptySetAcrossFreshStoreInstance() = runTest {
        val file = newPrefsFile()

        // Instance 1: disable every default package and shut down its scope
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore1 = PreferenceDataStoreFactory.create(
            scope = scope1,
            produceFile = { file }
        )
        val store1 = AllowlistStore(dataStore1)
        for (pkg in DefaultAllowlist.packages) {
            store1.setPackageAllowed(pkg, allowed = false)
        }
        assertTrue(store1.allowedPackages.first().isEmpty())
        scope1.cancel()
        scope1.coroutineContext[Job]?.join()

        // Instance 2: reopen the exact same file and confirm defaults are NOT re-seeded
        val dataStore2 = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        val store2 = AllowlistStore(dataStore2)
        assertEquals(emptySet<String>(), store2.allowedPackages.first())
    }

    @Test
    fun allowlistStore_resetToDefaults_restoresDefaultPackages() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { newPrefsFile() }
        )
        val store = AllowlistStore(dataStore)

        for (pkg in DefaultAllowlist.packages) {
            store.setPackageAllowed(pkg, allowed = false)
        }
        store.setPackageAllowed("com.custom.player", allowed = true)
        assertEquals(setOf("com.custom.player"), store.allowedPackages.first())

        store.resetToDefaults()
        assertEquals(DefaultAllowlist.packages, store.allowedPackages.first())
    }

    @Test
    fun accessibilityPreferencesStore_defaultsToFalseAndPersistsToggle() = runTest {
        val file = newPrefsFile()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore1 = PreferenceDataStoreFactory.create(
            scope = scope1,
            produceFile = { file }
        )
        val store1 = AccessibilityPreferencesStore(dataStore1)
        assertFalse(store1.reduceTransparencyEnabled.first())

        store1.setReduceTransparencyEnabled(true)
        assertTrue(store1.reduceTransparencyEnabled.first())
        scope1.cancel()
        scope1.coroutineContext[Job]?.join()

        // Reopen file to confirm persistence
        val dataStore2 = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        val store2 = AccessibilityPreferencesStore(dataStore2)
        assertTrue(store2.reduceTransparencyEnabled.first())

        store2.setReduceTransparencyEnabled(false)
        assertFalse(store2.reduceTransparencyEnabled.first())
    }
}
