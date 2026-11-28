package com.musictracker.app.domain.model

/**
 * Lightweight projection of a listen event used for pure-Kotlin streak, milestone,
 * and hourly listening rhythm calculations without loading full track metadata.
 */
data class ListenEventInsightSample(
    val startedAtEpoch: Long,
    val actualListenedMs: Long,
    val trackId: Long,
    val artistKey: String,
    val wasSkipped: Boolean
)

/**
 * Aggregated drill-down statistics for a single track within a timeframe.
 */
data class TrackDetailStats(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val playCount: Int,
    val totalListenedMs: Long,
    val skippedCount: Int,
    val firstHeardEpoch: Long?,
    val lastHeardEpoch: Long?
) {
    val skipRate: Float
        get() = if (playCount <= 0) 0f else skippedCount.toFloat() / playCount.toFloat()
}

/**
 * Aggregated drill-down statistics for a canonical artist group within a timeframe.
 */
data class ArtistDetailStats(
    val artist: String,
    val playCount: Int,
    val totalListenedMs: Long,
    val skippedCount: Int,
    val uniqueTrackCount: Int,
    val firstHeardEpoch: Long?,
    val lastHeardEpoch: Long?
) {
    val skipRate: Float
        get() = if (playCount <= 0) 0f else skippedCount.toFloat() / playCount.toFloat()
}

/**
 * Daily listening streak state computed in the user's local timezone.
 */
data class ListeningStreak(
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeDaysCount: Int = 0,
    val isTodayActive: Boolean = false
)

/**
 * Unlockable collector milestone definitions.
 */
enum class MilestoneType(
    val title: String,
    val description: String,
    val targetValue: Long
) {
    FIRST_SPIN(
        title = "First Spin",
        description = "Track your very first play",
        targetValue = 1L
    ),
    STREAK_3(
        title = "3-Day Groove",
        description = "Listen for 3 days in a row",
        targetValue = 3L
    ),
    STREAK_7(
        title = "Week-Long Rhythm",
        description = "Maintain a 7-day listening streak",
        targetValue = 7L
    ),
    STREAK_30(
        title = "Monthly Devotion",
        description = "Maintain a 30-day listening streak",
        targetValue = 30L
    ),
    WEEKLY_10H(
        title = "10-Hour Week",
        description = "Listen for 10+ hours in a 7-day window",
        targetValue = 10L * 3_600_000L
    ),
    ARTIST_100(
        title = "True Fan",
        description = "Play a single artist 100+ times",
        targetValue = 100L
    ),
    DISCOVERY_50(
        title = "Explorer 50",
        description = "Discover 50+ unique tracks",
        targetValue = 50L
    ),
    CENTURION_500(
        title = "500 Club",
        description = "Reach 500 total tracked plays",
        targetValue = 500L
    )
}

/**
 * Progress and unlock state for a single collector milestone.
 */
data class CollectorMilestone(
    val type: MilestoneType,
    val currentValue: Long,
    val targetValue: Long = type.targetValue,
    val unlocked: Boolean = currentValue >= targetValue
) {
    val progressFraction: Float
        get() = if (targetValue <= 0L) {
            1f
        } else {
            (currentValue.coerceAtLeast(0L).toFloat() / targetValue.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * Single hour-of-day bucket (`0..23`) for Listening Rhythm ("Peak Hours").
 */
data class HourlyListeningBucket(
    val hour: Int,
    val totalListenedMs: Long,
    val playCount: Int
)

/**
 * 24-hour listening distribution for the selected timeframe.
 */
data class ListeningRhythm(
    val buckets: List<HourlyListeningBucket>,
    val peakHour: Int?,
    val peakListenedMs: Long
) {
    val hasData: Boolean
        get() = peakHour != null && peakListenedMs > 0L

    companion object {
        fun empty(): ListeningRhythm = ListeningRhythm(
            buckets = (0..23).map { hour ->
                HourlyListeningBucket(hour = hour, totalListenedMs = 0L, playCount = 0)
            },
            peakHour = null,
            peakListenedMs = 0L
        )
    }
}

/**
 * Combined pure-Kotlin insights payload for the Stats screen.
 */
data class ListeningInsights(
    val streak: ListeningStreak = ListeningStreak(),
    val milestones: List<CollectorMilestone> = emptyList(),
    val rhythm: ListeningRhythm = ListeningRhythm.empty()
)

/**
 * Period-over-period delta for the Stats summary strip.
 * Null fields indicate no valid comparison baseline (e.g., previous period was 0 or ALL_TIME).
 */
data class SummaryDelta(
    val listenedMsPercentChange: Int? = null,
    val playCountPercentChange: Int? = null,
    val skipRatePointChange: Int? = null
)

/**
 * Period-over-period rank movement for a track or artist in the Top 5 list.
 */
sealed interface RankDelta {
    data class Up(val places: Int) : RankDelta
    data class Down(val places: Int) : RankDelta
    data object Same : RankDelta
    data object New : RankDelta
}

/**
 * Discovery classification tag shown in the Track / Artist drill-down sheet.
 */
enum class DiscoveryTag(val label: String) {
    OBSESSION("Obsession"),
    LOYALIST("Loyalist"),
    NEW_DISCOVERY("New Find"),
    REDISCOVERED("Rediscovered")
}

