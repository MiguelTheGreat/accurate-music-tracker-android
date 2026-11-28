package com.musictracker.app.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface AllowlistRepository {
    val allowedPackages: Flow<Set<String>>
    val dismissedSuggestionPackages: Flow<Set<String>>
        get() = flowOf(emptySet())
    suspend fun setPackageAllowed(packageName: String, allowed: Boolean)
    suspend fun dismissSuggestion(packageName: String) {}
    suspend fun resetToDefaults()
}
