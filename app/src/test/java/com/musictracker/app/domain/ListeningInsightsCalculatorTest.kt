package com.musictracker.app.domain

import com.musictracker.app.domain.model.ListenEventInsightSample
import com.musictracker.app.domain.model.MilestoneType
import com.musictracker.app.domain.stats.ListeningInsightsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ListeningInsightsCalculatorTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun computeStreak_consecutiveDaysEndingToday_reportsCurrentAndLongest() {
        val today = LocalDate.of(2026, 6, 15)
        val events = (0L..3L).map { offset ->
            eventOn(today.minusDays(offset), hour = 14, listenedMs = 180_000L)
        }

        val streak = ListeningInsightsCalculator.computeStreak(
            events = events,
            nowEpochMs = epochAt(today, 20, utc),
            zoneId = utc
        )

        assertEquals(4, streak.currentStreakDays)
        assertEquals(4, streak.longestStreakDays)
        assertEquals(4, streak.activeDaysCount)
        assertTrue(streak.isTodayActive)
    }

    @Test
    fun computeStreak_yesterdayGrace_preservesOngoingStreakBeforeTodayActivity() {
        val today = LocalDate.of(2026, 6, 15)
        val events = (1L..3L).map { offset ->
            eventOn(today.minusDays(offset), hour = 18, listenedMs = 120_000L)
        }

        val streak = ListeningInsightsCalculator.computeStreak(
            events = events,
            nowEpochMs = epochAt(today, 9, utc),
            zoneId = utc
        )

        assertEquals(3, streak.currentStreakDays)
        assertEquals(3, streak.longestStreakDays)
        assertEquals(3, streak.activeDaysCount)
        assertFalse(streak.isTodayActive)
    }

    @Test
    fun computeStreak_gapResetsCurrentStreak_butPreservesLongest() {
        val today = LocalDate.of(2026, 6, 15)
        val earlierRun = (6L..10L).map { offset ->
            eventOn(today.minusDays(offset), hour = 12, listenedMs = 180_000L)
        }
        val todayEvent = eventOn(today, hour = 10, listenedMs = 180_000L)

        val streak = ListeningInsightsCalculator.computeStreak(
            events = earlierRun + todayEvent,
            nowEpochMs = epochAt(today, 12, utc),
            zoneId = utc
        )

        assertEquals(1, streak.currentStreakDays)
        assertEquals(5, streak.longestStreakDays)
        assertEquals(6, streak.activeDaysCount)
        assertTrue(streak.isTodayActive)
    }

    @Test
    fun computeStreak_isTimezoneSafeAcrossMidnightBoundary() {
        // 2026-06-15T01:30Z is June 15 in UTC, but June 14 18:30 in America/Los_Angeles (UTC-7).
        val laZone = ZoneId.of("America/Los_Angeles")
        val utcEventMs = LocalDate.of(2026, 6, 15).atTime(1, 30).atZone(utc).toInstant().toEpochMilli()
        val nowMs = LocalDate.of(2026, 6, 15).atTime(16, 0).atZone(utc).toInstant().toEpochMilli()
        val events = listOf(
            ListenEventInsightSample(
                startedAtEpoch = utcEventMs,
                actualListenedMs = 120_000L,
                trackId = 1L,
                artistKey = "daft punk",
                wasSkipped = false
            )
        )

        val utcStreak = ListeningInsightsCalculator.computeStreak(events, nowMs, utc)
        val laStreak = ListeningInsightsCalculator.computeStreak(events, nowMs, laZone)

        // In UTC, event was on June 15 (today). In LA, nowMs is 09:00 June 15, event was June 14 (yesterday).
        assertTrue(utcStreak.isTodayActive)
        assertEquals(1, utcStreak.currentStreakDays)

        assertFalse(laStreak.isTodayActive)
        assertEquals(1, laStreak.currentStreakDays)
    }

    @Test
    fun computeStreak_ignoresDaysWithOnlyShortSkippedPlays() {
        val today = LocalDate.of(2026, 6, 15)
        val events = listOf(
            eventOn(today, hour = 11, listenedMs = 5_000L, wasSkipped = true)
        )

        val streak = ListeningInsightsCalculator.computeStreak(
            events = events,
            nowEpochMs = epochAt(today, 12, utc),
            zoneId = utc
        )

        assertEquals(0, streak.currentStreakDays)
        assertEquals(0, streak.longestStreakDays)
        assertEquals(0, streak.activeDaysCount)
        assertFalse(streak.isTodayActive)
    }

    @Test
    fun computeMilestones_unlocksExactThresholds() {
        val baseDate = LocalDate.of(2026, 6, 15)
        // 7 consecutive days, 100 plays of "artist_a" across 50 unique tracks, totaling 10h+ within 7 days.
        val events = (0 until 100).map { index ->
            val dayOffset = (index % 7).toLong()
            ListenEventInsightSample(
                startedAtEpoch = epochAt(baseDate.minusDays(dayOffset), hour = 10 + (index % 10), zoneId = utc),
                actualListenedMs = 360_000L, // 6 min each -> 100 * 6m = 10 hours in 7-day rolling window
                trackId = (index % 50).toLong() + 1L,
                artistKey = "artist_a",
                wasSkipped = false
            )
        }

        val streak = ListeningInsightsCalculator.computeStreak(events, epochAt(baseDate, 23, utc), utc)
        val milestones = ListeningInsightsCalculator.computeMilestones(events, streak, utc)
            .associateBy { it.type }

        assertTrue(milestones.getValue(MilestoneType.FIRST_SPIN).unlocked)
        assertTrue(milestones.getValue(MilestoneType.STREAK_3).unlocked)
        assertTrue(milestones.getValue(MilestoneType.STREAK_7).unlocked)
        assertFalse(milestones.getValue(MilestoneType.STREAK_30).unlocked)
        assertTrue(milestones.getValue(MilestoneType.WEEKLY_10H).unlocked)
        assertTrue(milestones.getValue(MilestoneType.ARTIST_100).unlocked)
        assertTrue(milestones.getValue(MilestoneType.DISCOVERY_50).unlocked)
        assertFalse(milestones.getValue(MilestoneType.CENTURION_500).unlocked)
    }

    @Test
    fun computeListeningRhythm_buckets24HoursAndExcludesEventsBeforeCutoff() {
        val day = LocalDate.of(2026, 6, 15)
        val cutoffMs = epochAt(day, 0, utc)
        val events = listOf(
            // Excluded (before cutoff)
            eventOn(day.minusDays(1), hour = 8, listenedMs = 900_000L),
            // Included
            eventOn(day, hour = 9, listenedMs = 120_000L),
            eventOn(day, hour = 21, listenedMs = 240_000L),
            eventOn(day, hour = 21, listenedMs = 300_000L)
        )

        val rhythm = ListeningInsightsCalculator.computeListeningRhythm(
            events = events,
            sinceEpochMs = cutoffMs,
            zoneId = utc
        )

        assertEquals(24, rhythm.buckets.size)
        assertEquals(21, rhythm.peakHour)
        assertEquals(540_000L, rhythm.peakListenedMs)
        assertEquals(0L, rhythm.buckets[8].totalListenedMs)
        assertEquals(120_000L, rhythm.buckets[9].totalListenedMs)
        assertEquals(1, rhythm.buckets[9].playCount)
        assertEquals(540_000L, rhythm.buckets[21].totalListenedMs)
        assertEquals(2, rhythm.buckets[21].playCount)
    }

    @Test
    fun computeListeningRhythm_emptyEventsReturnsNullPeakHour() {
        val rhythm = ListeningInsightsCalculator.computeListeningRhythm(
            events = emptyList(),
            sinceEpochMs = 0L,
            zoneId = utc
        )

        assertEquals(24, rhythm.buckets.size)
        assertNull(rhythm.peakHour)
        assertEquals(0L, rhythm.peakListenedMs)
        assertFalse(rhythm.hasData)
    }

    private fun eventOn(
        date: LocalDate,
        hour: Int,
        listenedMs: Long,
        wasSkipped: Boolean = false
    ): ListenEventInsightSample = ListenEventInsightSample(
        startedAtEpoch = epochAt(date, hour, utc),
        actualListenedMs = listenedMs,
        trackId = 1L,
        artistKey = "daft punk",
        wasSkipped = wasSkipped
    )

    private fun epochAt(date: LocalDate, hour: Int, zoneId: ZoneId): Long =
        date.atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()
}
