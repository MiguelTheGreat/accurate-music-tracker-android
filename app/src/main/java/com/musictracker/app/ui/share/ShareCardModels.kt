package com.musictracker.app.ui.share

import android.graphics.Bitmap
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat

enum class ShareCardMode(val label: String) {
    TOP_ARTIST("Top Artists"),
    TOP_MUSIC("Top Music"),
    LISTENING_SUMMARY("Summary")
}

enum class ShareAspectRatio(
    val label: String,
    val widthPx: Int,
    val heightPx: Int
) {
    STORY_9_16("9:16 Story", 1080, 1920),
    POST_4_5("4:5 Post", 1080, 1350)
}

data class ShareCardData(
    val mode: ShareCardMode,
    val aspectRatio: ShareAspectRatio = ShareAspectRatio.STORY_9_16,
    val timeframe: StatsTimeframe = StatsTimeframe.SEVEN_DAYS,
    val summary: ListeningSummary = ListeningSummary(),
    val featuredArtist: TopArtistStat? = null,
    val featuredTrack: TopTrackStat? = null,
    val topTracks: List<TopTrackStat> = emptyList(),
    val topArtists: List<TopArtistStat> = emptyList(),
    val streakDays: Int = 0,
    val peakHour: Int? = null,
    val primaryArtwork: Bitmap? = null,
    val accentArtworks: List<Bitmap?> = emptyList()
)

sealed interface ShareExportResult {
    data class Success(val message: String) : ShareExportResult
    data class Error(val message: String) : ShareExportResult
}

internal fun shareTimeframeLabel(timeframe: StatsTimeframe): String = when (timeframe) {
    StatsTimeframe.TODAY -> "Today"
    StatsTimeframe.SEVEN_DAYS -> "7 Days"
    StatsTimeframe.THIRTY_DAYS -> "30 Days"
    StatsTimeframe.ALL_TIME -> "All Time"
}

internal fun formatPeakHourLabel(hour: Int): String {
    val normalized = Math.floorMod(hour, 24)
    return when {
        normalized == 0 -> "12 AM"
        normalized < 12 -> "$normalized AM"
        normalized == 12 -> "12 PM"
        else -> "${normalized - 12} PM"
    }
}
