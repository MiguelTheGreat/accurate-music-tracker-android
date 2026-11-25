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

class AccessibilityPreferencesStore(
    private val dataStore: DataStore<Preferences>
) {
    val reduceTransparencyEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs[KEY_REDUCE_TRANSPARENCY] ?: false }
        .distinctUntilChanged()

    suspend fun setReduceTransparencyEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_REDUCE_TRANSPARENCY] = enabled
        }
    }

    companion object {
        internal val KEY_REDUCE_TRANSPARENCY = booleanPreferencesKey("reduce_transparency_enabled")
    }
}
