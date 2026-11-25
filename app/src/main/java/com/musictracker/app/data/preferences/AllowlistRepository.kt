package com.musictracker.app.data.preferences

import kotlinx.coroutines.flow.Flow

interface AllowlistRepository {
    val allowedPackages: Flow<Set<String>>
    suspend fun setPackageAllowed(packageName: String, allowed: Boolean)
    suspend fun resetToDefaults()
}
