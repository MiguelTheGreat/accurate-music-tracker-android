package com.musictracker.app.e2e.harness

import com.musictracker.app.domain.clock.TrackerClock

/**
 * Deterministic, controllable [TrackerClock] for opaque-box E2E tests.
 * Allows advancing monotonic elapsed time and wall-clock epoch in lockstep
 * or independently (e.g., to test backward monotonic clock jumps or multi-day timeframes).
 */
class FakeTrackerClock(
    initialElapsedMs: Long = DEFAULT_INITIAL_ELAPSED_MS,
    initialEpochMs: Long = DEFAULT_INITIAL_EPOCH_MS
) : TrackerClock {

    private var elapsedMs: Long = initialElapsedMs
    private var epochMs: Long = initialEpochMs

    override fun elapsedRealtimeMs(): Long = elapsedMs

    override fun currentTimeMillis(): Long = epochMs

    fun advanceBy(deltaMs: Long) {
        elapsedMs += deltaMs
        epochMs += deltaMs
    }

    fun advanceElapsedOnly(deltaMs: Long) {
        elapsedMs += deltaMs
    }

    fun setElapsedRealtimeMs(newElapsedMs: Long) {
        elapsedMs = newElapsedMs
    }

    fun setCurrentTimeMillis(newEpochMs: Long) {
        epochMs = newEpochMs
    }

    companion object {
        const val DEFAULT_INITIAL_ELAPSED_MS: Long = 10_000L
        // 2025-06-15T12:00:00Z — midday UTC so "today" boundaries are clean across timezones
        const val DEFAULT_INITIAL_EPOCH_MS: Long = 1_749_988_800_000L
    }
}
