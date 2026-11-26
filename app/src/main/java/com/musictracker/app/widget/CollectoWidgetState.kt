package com.musictracker.app.widget

import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.ui.common.formatSourceAppName
import com.musictracker.app.ui.common.formatSummaryDuration

/**
 * Immutable render state for the Collecto home screen Glance widget.
 */
sealed interface CollectoWidgetState {
    data class NowTracking(
        val title: String,
        val artist: String,
        val sourceApp: String,
        val artworkPath: String?
    ) : CollectoWidgetState

    data class WeeklyTopArtist(
        val artistName: String,
        val weeklyDurationFormatted: String,
        val weeklyPlayCount: Int,
        val artworkPath: String?
    ) : CollectoWidgetState

    data object Empty : CollectoWidgetState
}

/**
 * Pure-Kotlin state mapper for [CollectoGlanceWidget] so state transitions can be
 * exhaustively unit-tested on the JVM without Android framework dependencies.
 */
object CollectoWidgetStateMapper {

    fun map(
        nowTracking: NowTrackingState?,
        topArtist: TopArtistStat?,
        weeklySummary: ListeningSummary,
        artworkPath: String?
    ): CollectoWidgetState {
        if (nowTracking != null && nowTracking.isPlaying && !nowTracking.isDebouncing) {
            val title = nowTracking.normalizedTitle.ifBlank { nowTracking.rawTitle }.ifBlank { "Unknown Track" }
            val artist = nowTracking.normalizedArtist.ifBlank { nowTracking.rawArtist }.ifBlank { "Unknown Artist" }
            return CollectoWidgetState.NowTracking(
                title = title,
                artist = artist,
                sourceApp = formatSourceAppName(nowTracking.sourcePackage),
                artworkPath = artworkPath
            )
        }

        if (topArtist != null && topArtist.artist.isNotBlank() && weeklySummary.totalListenedMs > 0L) {
            return CollectoWidgetState.WeeklyTopArtist(
                artistName = topArtist.artist,
                weeklyDurationFormatted = formatSummaryDuration(weeklySummary.totalListenedMs),
                weeklyPlayCount = weeklySummary.playCount,
                artworkPath = artworkPath
            )
        }

        return CollectoWidgetState.Empty
    }
}
