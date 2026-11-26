package com.musictracker.app.domain.stats

import com.musictracker.app.domain.model.CollectorMilestone
import com.musictracker.app.domain.model.HourlyListeningBucket
import com.musictracker.app.domain.model.ListenEventInsightSample
import com.musictracker.app.domain.model.ListeningInsights
import com.musictracker.app.domain.model.ListeningRhythm
import com.musictracker.app.domain.model.ListeningStreak
import com.musictracker.app.domain.model.MilestoneType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure-Kotlin calculator for timezone-safe daily listening streaks, unlockable collector
 * milestones, and 24-hour listening rhythm ("Peak Hours").
 *
 * Strictly zero `android.*` dependencies so all logic can be unit-tested on the JVM.
 */
object ListeningInsightsCalculator {

    const val ACTIVE_DAY_MIN_LISTENED_MS: Long = 30_000L
    const val WEEKLY_10H_TARGET_MS: Long = 10L * 3_600_000L
    private const val ROLLING_WEEK_DAYS_SPAN: Long = 6L // inclusive 7-day window [d, d + 6]

    fun computeAll(
        allEvents: List<ListenEventInsightSample>,
        timeframeSinceEpochMs: Long,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ListeningInsights {
        val streak = computeStreak(
            events = allEvents,
            nowEpochMs = nowEpochMs,
            zoneId = zoneId
        )
        val milestones = computeMilestones(
            events = allEvents,
            streak = streak,
            zoneId = zoneId
        )
        val rhythm = computeListeningRhythm(
            events = allEvents,
            sinceEpochMs = timeframeSinceEpochMs,
            zoneId = zoneId
        )
        return ListeningInsights(
            streak = streak,
            milestones = milestones,
            rhythm = rhythm
        )
    }

    /**
     * Computes daily listening streaks in [zoneId].
     *
     * A calendar day is considered active when total listened duration on that day is
     * `>= 30_000L` ms OR there is at least 1 non-skipped play.
     *
     * `currentStreakDays` counts consecutive backward days from `today` if `today` is active,
     * or from `yesterday` if `today` is not yet active (so an ongoing streak does not falsely
     * reset before the user listens today).
     */
    fun computeStreak(
        events: List<ListenEventInsightSample>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ListeningStreak {
        if (events.isEmpty()) {
            return ListeningStreak()
        }

        val dailyStats = HashMap<LocalDate, DailyActivity>()
        for (event in events) {
            val date = Instant.ofEpochMilli(event.startedAtEpoch)
                .atZone(zoneId)
                .toLocalDate()
            val stats = dailyStats.getOrPut(date) { DailyActivity() }
            stats.totalListenedMs += event.actualListenedMs.coerceAtLeast(0L)
            if (!event.wasSkipped) {
                stats.nonSkippedPlays += 1
            }
        }

        val activeDates = dailyStats.entries
            .asSequence()
            .filter { (_, stats) ->
                stats.totalListenedMs >= ACTIVE_DAY_MIN_LISTENED_MS || stats.nonSkippedPlays >= 1
            }
            .map { it.key }
            .toHashSet()

        if (activeDates.isEmpty()) {
            return ListeningStreak()
        }

        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val isTodayActive = today in activeDates
        val yesterday = today.minusDays(1)

        val currentStreak = when {
            isTodayActive -> countConsecutiveBackward(today, activeDates)
            yesterday in activeDates -> countConsecutiveBackward(yesterday, activeDates)
            else -> 0
        }

        val sortedDates = activeDates.sorted()
        var longestStreak = 1
        var runLength = 1
        for (i in 1 until sortedDates.size) {
            if (sortedDates[i] == sortedDates[i - 1].plusDays(1)) {
                runLength += 1
                if (runLength > longestStreak) {
                    longestStreak = runLength
                }
            } else {
                runLength = 1
            }
        }

        return ListeningStreak(
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            activeDaysCount = activeDates.size,
            isTodayActive = isTodayActive
        )
    }

    /**
     * Evaluates all unlockable collector milestones across all-time listening history.
     */
    fun computeMilestones(
        events: List<ListenEventInsightSample>,
        streak: ListeningStreak,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<CollectorMilestone> {
        val totalPlays = events.size.toLong()
        val uniqueTrackCount = events.asSequence().map { it.trackId }.toHashSet().size.toLong()
        val maxArtistPlays = events
            .asSequence()
            .map { it.artistKey.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "unknown artist" }
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull()
            ?.toLong()
            ?: 0L
        val max7DayListenedMs = computeMaxRolling7DayListenedMs(events, zoneId)
        val bestStreakDays = maxOf(streak.currentStreakDays, streak.longestStreakDays).toLong()

        return listOf(
            milestone(MilestoneType.FIRST_SPIN, totalPlays),
            milestone(MilestoneType.STREAK_3, bestStreakDays),
            milestone(MilestoneType.STREAK_7, bestStreakDays),
            milestone(MilestoneType.STREAK_30, bestStreakDays),
            milestone(MilestoneType.WEEKLY_10H, max7DayListenedMs),
            milestone(MilestoneType.ARTIST_100, maxArtistPlays),
            milestone(MilestoneType.DISCOVERY_50, uniqueTrackCount),
            milestone(MilestoneType.CENTURION_500, totalPlays)
        )
    }

    /**
     * Aggregates listening duration and play count into 24 hourly buckets (`0..23`)
     * for events occurring at or after [sinceEpochMs] in [zoneId].
     */
    fun computeListeningRhythm(
        events: List<ListenEventInsightSample>,
        sinceEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ListeningRhythm {
        val msPerHour = LongArray(24)
        val playsPerHour = IntArray(24)

        for (event in events) {
            if (event.startedAtEpoch < sinceEpochMs) continue
            val hour = Instant.ofEpochMilli(event.startedAtEpoch)
                .atZone(zoneId)
                .hour
            msPerHour[hour] += event.actualListenedMs.coerceAtLeast(0L)
            playsPerHour[hour] += 1
        }

        var peakHour: Int? = null
        var peakMs = 0L
        val buckets = ArrayList<HourlyListeningBucket>(24)
        for (hour in 0 until 24) {
            val ms = msPerHour[hour]
            buckets += HourlyListeningBucket(
                hour = hour,
                totalListenedMs = ms,
                playCount = playsPerHour[hour]
            )
            if (ms > peakMs) {
                peakMs = ms
                peakHour = hour
            }
        }

        return ListeningRhythm(
            buckets = buckets,
            peakHour = peakHour,
            peakListenedMs = peakMs
        )
    }

    private fun countConsecutiveBackward(startDate: LocalDate, activeDates: Set<LocalDate>): Int {
        var count = 0
        var cursor = startDate
        while (cursor in activeDates) {
            count += 1
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private fun computeMaxRolling7DayListenedMs(
        events: List<ListenEventInsightSample>,
        zoneId: ZoneId
    ): Long {
        if (events.isEmpty()) return 0L

        val msByDate = HashMap<LocalDate, Long>()
        for (event in events) {
            val date = Instant.ofEpochMilli(event.startedAtEpoch)
                .atZone(zoneId)
                .toLocalDate()
            val listened = event.actualListenedMs.coerceAtLeast(0L)
            msByDate[date] = (msByDate[date] ?: 0L) + listened
        }

        val sortedEntries = msByDate.entries.sortedBy { it.key }
        var left = 0
        var windowSum = 0L
        var maxSum = 0L

        for (right in sortedEntries.indices) {
            val rightDate = sortedEntries[right].key
            windowSum += sortedEntries[right].value
            while (left <= right && ChronoUnit.DAYS.between(sortedEntries[left].key, rightDate) > ROLLING_WEEK_DAYS_SPAN) {
                windowSum -= sortedEntries[left].value
                left += 1
            }
            if (windowSum > maxSum) {
                maxSum = windowSum
            }
        }

        return maxSum
    }

    private fun milestone(type: MilestoneType, rawValue: Long): CollectorMilestone {
        val clamped = rawValue.coerceAtLeast(0L)
        return CollectorMilestone(
            type = type,
            currentValue = clamped,
            targetValue = type.targetValue,
            unlocked = clamped >= type.targetValue
        )
    }

    private class DailyActivity(
        var totalListenedMs: Long = 0L,
        var nonSkippedPlays: Int = 0
    )
}
