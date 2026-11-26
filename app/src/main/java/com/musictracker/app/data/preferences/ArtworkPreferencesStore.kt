package com.musictracker.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

class ArtworkPreferencesStore(
    private val dataStore: DataStore<Preferences>
) {
    val onlineArtworkEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs[KEY_ONLINE_ARTWORK_ENABLED] ?: true }
        .distinctUntilChanged()

    suspend fun setOnlineArtworkEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONLINE_ARTWORK_ENABLED] = enabled
        }
    }

    companion object {
        internal val KEY_ONLINE_ARTWORK_ENABLED = booleanPreferencesKey("online_artwork_enabled")
    }
}
