package com.musictracker.app.ui.common

import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.StatsTimeframe
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Pure computation of real-time listened milliseconds for the live "Now Tracking" counter.
 */
fun computeLiveListenedMs(state: NowTrackingState, elapsedRealtimeMs: Long): Long {
    val activeDelta = if (state.isPlaying && state.segmentStartElapsedMs != null) {
        (elapsedRealtimeMs - state.segmentStartElapsedMs).coerceAtLeast(0L)
    } else {
        0L
    }
    return (state.accumulatedMs + activeDelta).coerceAtLeast(0L)
}

/**
 * Formats a live ticking counter as `MM:SS` (or `H:MM:SS` if `>= 1h`).
 */
fun formatLiveCounter(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L)) / MILLIS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Formats aggregate listening durations cleanly (e.g. `"2h 14m"`, `"42m"`, `"3m 18s"`, `"0m"`).
 */
fun formatSummaryDuration(durationMs: Long): String {
    val clamped = durationMs.coerceAtLeast(0L)
    val totalSeconds = clamped / MILLIS_PER_SECOND
    if (totalSeconds == 0L) return "0m"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/**
 * Formats a single listen event duration (e.g. `"3m 18s"`, `"45s"`, `"1h 04m"`).
 */
fun formatEventDuration(durationMs: Long): String {
    val clamped = durationMs.coerceAtLeast(0L)
    val totalSeconds = clamped / MILLIS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}

/**
 * Formats skip rate percentage (`"12%"`), or `"—"` when [playCount] is 0 or [skipRate] is NaN.
 */
fun formatSkipRate(playCount: Int, skipRate: Float): String {
    if (playCount <= 0 || skipRate.isNaN()) return "—"
    val percent = (skipRate.coerceIn(0f, 1f) * 100f).roundToInt()
    return "$percent%"
}

/**
 * Formats a relative timestamp (e.g. `"Just now"`, `"12m ago"`, `"3h ago"`, `"2d ago"`).
 */
fun formatRelativeTime(endedAtEpochMs: Long, nowEpochMs: Long): String {
    val diffMs = (nowEpochMs - endedAtEpochMs).coerceAtLeast(0L)
    return when {
        diffMs < MILLIS_PER_MINUTE -> "Just now"
        diffMs < MILLIS_PER_HOUR -> "${diffMs / MILLIS_PER_MINUTE}m ago"
        diffMs < MILLIS_PER_DAY -> "${diffMs / MILLIS_PER_HOUR}h ago"
        else -> "${diffMs / MILLIS_PER_DAY}d ago"
    }
}

/**
 * Computes local start-of-day epoch milliseconds for [nowEpochMs] in [zoneId].
 */
fun startOfTodayEpochMs(nowEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(nowEpochMs)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

/**
 * Computes the `sinceEpochMs` cutoff for a [StatsTimeframe] using [nowEpochMs].
 */
fun computeTimeframeSinceEpochMs(
    timeframe: StatsTimeframe,
    nowEpochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long = when (timeframe) {
    StatsTimeframe.TODAY -> startOfTodayEpochMs(nowEpochMs, zoneId)
    StatsTimeframe.SEVEN_DAYS -> (nowEpochMs - 7L * MILLIS_PER_DAY).coerceAtLeast(0L)
    StatsTimeframe.THIRTY_DAYS -> (nowEpochMs - 30L * MILLIS_PER_DAY).coerceAtLeast(0L)
    StatsTimeframe.ALL_TIME -> 0L
}

private val KnownMusicAppNames = mapOf(
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "com.spotify.music" to "Spotify",
    "com.apple.android.music" to "Apple Music",
    "com.aspiro.tidal" to "TIDAL",
    "com.tidal.wave" to "TIDAL",
    "com.amazon.mp3" to "Amazon Music",
    "com.soundcloud.android" to "SoundCloud",
    "deezer.android.app" to "Deezer",
    "com.bandcamp.android" to "Bandcamp",
    "com.maxmpz.audioplayer" to "Poweramp",
    "in.krosbits.musicolet" to "Musicolet",
    "com.piyush.music" to "Oto Music",
    "org.videolan.vlc" to "VLC",
    "code.name.monkey.retromusic" to "Retro Music",
    "com.rhmsoft.pulsar" to "Pulsar",
    "com.sec.android.app.music" to "Samsung Music",
    "com.foobar2000.foobar2000" to "foobar2000"
)

fun formatSourceAppName(packageName: String): String {
    KnownMusicAppNames[packageName]?.let { return it }
    val segment = packageName.substringAfterLast('.').ifBlank { packageName }
    return segment.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
    }
}
