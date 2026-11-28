package com.musictracker.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

class AllowlistStore(
    private val dataStore: DataStore<Preferences>,
    private val defaultPackages: Set<String> = DefaultAllowlist.packages
) : AllowlistRepository {

    override val allowedPackages: Flow<Set<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            if (prefs[KEY_ALLOWLIST_INITIALIZED] == true) {
                prefs[KEY_ALLOWED_PACKAGES].orEmpty()
            } else {
                defaultPackages
            }
        }
        .distinctUntilChanged()

    override val dismissedSuggestionPackages: Flow<Set<String>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            prefs[KEY_DISMISSED_SUGGESTIONS].orEmpty()
        }
        .distinctUntilChanged()

    override suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
        if (packageName.isBlank()) return
        dataStore.edit { prefs ->
            val current = if (prefs[KEY_ALLOWLIST_INITIALIZED] == true) {
                prefs[KEY_ALLOWED_PACKAGES].orEmpty()
            } else {
                defaultPackages
            }
            prefs[KEY_ALLOWED_PACKAGES] = if (allowed) {
                current + packageName
            } else {
                current - packageName
            }
            if (allowed) {
                val dismissed = prefs[KEY_DISMISSED_SUGGESTIONS].orEmpty()
                if (packageName in dismissed) {
                    prefs[KEY_DISMISSED_SUGGESTIONS] = dismissed - packageName
                }
            }
            prefs[KEY_ALLOWLIST_INITIALIZED] = true
        }
    }

    override suspend fun dismissSuggestion(packageName: String) {
        if (packageName.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_DISMISSED_SUGGESTIONS].orEmpty()
            prefs[KEY_DISMISSED_SUGGESTIONS] = current + packageName
        }
    }

    override suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            prefs[KEY_ALLOWED_PACKAGES] = defaultPackages
            prefs[KEY_ALLOWLIST_INITIALIZED] = true
        }
    }

    companion object {
        internal val KEY_ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        internal val KEY_ALLOWLIST_INITIALIZED = booleanPreferencesKey("allowlist_initialized")
        internal val KEY_DISMISSED_SUGGESTIONS = stringSetPreferencesKey("dismissed_suggestion_packages")
    }
}
