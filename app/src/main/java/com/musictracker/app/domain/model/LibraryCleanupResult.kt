package com.musictracker.app.domain.model

data class LibraryCleanupResult(
    val removedAdTracks: Int = 0,
    val removedAdEvents: Int = 0,
    val mergedDuplicateTracks: Int = 0,
    val updatedTracks: Int = 0
) {
    val hasChanges: Boolean
        get() = removedAdTracks > 0 ||
            removedAdEvents > 0 ||
            mergedDuplicateTracks > 0 ||
            updatedTracks > 0
}
