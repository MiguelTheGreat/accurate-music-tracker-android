package com.musictracker.app.domain.tracking

import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.TrackIdentity
import com.musictracker.app.domain.normalization.AdAndPlaceholderFilter
import com.musictracker.app.domain.normalization.MetadataNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaySessionStateMachine(
    private val clock: TrackerClock,
    private val normalizer: MetadataNormalizer,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val idlePauseTimeoutMs: Long = DEFAULT_IDLE_PAUSE_TIMEOUT_MS
) : PlaySessionEngine {

    private enum class Phase {
        PENDING,
        ACTIVE
    }

    private data class SessionRecord(
        val identity: TrackIdentity,
        val dedupKey: String,
        var normalizedTitle: String,
        var normalizedArtist: String,
        var album: String,
        var durationMs: Long,
        var artworkUri: String?,
        val startedAtEpoch: Long,
        var phase: Phase,
        var accumulatedMs: Long = 0L,
        var segmentStartElapsedMs: Long? = null,
        var pausedSinceElapsedMs: Long? = null,
        var lastPlayingPositionMs: Long = 0L,
        var lastPositionUpdateElapsedMs: Long = 0L,
        var lastRealProgressElapsedMs: Long = 0L,
        var lastPlaybackSpeed: Float = 1.0f
    ) {
        fun toNowTrackingState(): NowTrackingState = NowTrackingState(
            sourcePackage = identity.sourcePackage,
            rawTitle = identity.rawTitle,
            rawArtist = identity.rawArtist,
            normalizedTitle = normalizedTitle,
            normalizedArtist = normalizedArtist,
            album = album,
            durationMs = durationMs,
            artworkUri = artworkUri,
            isPlaying = segmentStartElapsedMs != null,
            accumulatedMs = accumulatedMs,
            segmentStartElapsedMs = segmentStartElapsedMs,
            isDebouncing = phase == Phase.PENDING
        )
    }

    private val lock = Any()
    private val _nowTracking = MutableStateFlow<NowTrackingState?>(null)
    override val nowTracking: StateFlow<NowTrackingState?> = _nowTracking.asStateFlow()

    private var currentSession: SessionRecord? = null
    private val latestMetadataByPackage = mutableMapOf<String, MediaMetadataSnapshot>()
    private val latestStateByPackage = mutableMapOf<String, PlaybackStateSnapshot>()

    override fun onPlaybackEvent(event: PlaybackEvent): List<CompletedListenSession> =
        synchronized(lock) {
            val now = clock.elapsedRealtimeMs()

            if (event is PlaybackEvent.StateChanged) {
                val pkg = event.sourcePackage.trim()
                val session = currentSession
                if (
                    session != null &&
                    session.identity.sourcePackage == pkg &&
                    event.state.status == PlaybackStatus.PLAYING &&
                    event.state.playbackSpeed.isFinite() &&
                    event.state.playbackSpeed > 0f &&
                    session.phase == Phase.ACTIVE &&
                    session.segmentStartElapsedMs != null &&
                    _nowTracking.value != null &&
                    !isStalled(session, event.state) &&
                    !isTrackOverrun(session, event.state, now) &&
                    !isSingleTrackLoop(session, newPos = event.state.positionMs, now = now)
                ) {
                    latestStateByPackage[pkg] = event.state
                    session.pausedSinceElapsedMs = null
                    updatePlayingTelemetry(session, event.state, now)
                    return emptyList()
                }
            }

            val emitted = mutableListOf<CompletedListenSession>()
            val prevSession = currentSession
            val prevPhase = prevSession?.phase
            val prevSegmentStart = prevSession?.segmentStartElapsedMs
            var metadataPatched = false

            checkIdlePauseTimeout(now, emitted)

            when (event) {
                is PlaybackEvent.Tick -> {
                    currentSession?.let { active ->
                        if (isTrackOverrun(active, state = null, now = now)) {
                            handleTrackOverrun(active, state = null, now = now, emitted = emitted)
                        } else {
                            promoteIfEligible(active, now)
                        }
                    }
                }

                is PlaybackEvent.MetadataChanged -> {
                    metadataPatched = handleMetadataChanged(
                        event.sourcePackage.trim(),
                        event.metadata,
                        now,
                        emitted
                    )
                }

                is PlaybackEvent.StateChanged -> {
                    handleStateChanged(event.sourcePackage.trim(), event.state, now, emitted)
                }

                is PlaybackEvent.SessionSnapshot -> {
                    metadataPatched = handleSessionSnapshot(
                        pkg = event.sourcePackage.trim(),
                        metadata = event.metadata,
                        state = event.state,
                        now = now,
                        emitted = emitted
                    )
                }
            }

            val unchangedActiveOrPaused =
                currentSession === prevSession &&
                    prevSession != null &&
                    prevSession.phase == prevPhase &&
                    prevSession.segmentStartElapsedMs == prevSegmentStart &&
                    !metadataPatched &&
                    emitted.isEmpty() &&
                    _nowTracking.value != null
            if (!unchangedActiveOrPaused) {
                publishNowTracking()
            }
            emitted
        }

    override fun onSessionEnded(sourcePackage: String): List<CompletedListenSession> =
        synchronized(lock) {
            val pkg = sourcePackage.trim()
            val now = clock.elapsedRealtimeMs()
            val emitted = mutableListOf<CompletedListenSession>()

            latestMetadataByPackage.remove(pkg)
            latestStateByPackage.remove(pkg)

            if (currentSession?.identity?.sourcePackage == pkg) {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
            }

            publishNowTracking()
            emitted
        }

    override fun flushActiveSession(): List<CompletedListenSession> =
        synchronized(lock) {
            val now = clock.elapsedRealtimeMs()
            val emitted = mutableListOf<CompletedListenSession>()

            latestMetadataByPackage.clear()
            latestStateByPackage.clear()
            finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)

            publishNowTracking()
            emitted
        }

    private fun handleMetadataChanged(
        pkg: String,
        metadata: MediaMetadataSnapshot,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ): Boolean {
        if (metadata.title.isBlank()) {
            return false
        }

        if (AdAndPlaceholderFilter.isAdOrPlaceholder(metadata)) {
            if (currentSession?.identity?.sourcePackage == pkg) {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
            }
            latestMetadataByPackage.remove(pkg)
            return false
        }

        latestMetadataByPackage[pkg] = metadata
        val incomingIdentity = TrackIdentity(
            sourcePackage = pkg,
            rawTitle = metadata.title.trim(),
            rawArtist = metadata.artist.trim()
        )
        val incomingNorm = normalizer.normalize(incomingIdentity.rawTitle, incomingIdentity.rawArtist)
        val session = currentSession

        return when {
            session != null && isSameTrack(session, pkg, incomingIdentity, incomingNorm.dedupKey) -> {
                val patched = patchMetadataInPlace(session, metadata)
                promoteIfEligible(session, now)
                patched
            }

            session != null && session.identity.sourcePackage == pkg -> {
                val latestState = latestStateByPackage[pkg]
                val wasActivelyPlaying = session.segmentStartElapsedMs != null
                val wasPlaying = wasActivelyPlaying ||
                    (latestState?.status == PlaybackStatus.PLAYING &&
                        latestState.playbackSpeed.isFinite() &&
                        latestState.playbackSpeed > 0f)
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                if (
                    wasActivelyPlaying ||
                    (wasPlaying && !isStalePreExistingPlayingState(latestState, metadata.durationMs, now))
                ) {
                    startNewSession(
                        pkg = pkg,
                        metadata = metadata,
                        initialPosMs = 0L,
                        now = now,
                        state = if (wasActivelyPlaying) null else latestState
                    )
                }
                false
            }

            session == null -> {
                val latestState = latestStateByPackage[pkg]
                if (
                    latestState?.status == PlaybackStatus.PLAYING &&
                    !isStalePreExistingPlayingState(latestState, metadata.durationMs, now)
                ) {
                    startNewSession(
                        pkg = pkg,
                        metadata = metadata,
                        initialPosMs = latestState.positionMs,
                        now = now,
                        state = latestState
                    )
                }
                false
            }

            else -> {
                val latestState = latestStateByPackage[pkg]
                if (
                    latestState?.status == PlaybackStatus.PLAYING &&
                    !isStalePreExistingPlayingState(latestState, metadata.durationMs, now)
                ) {
                    finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                    startNewSession(
                        pkg = pkg,
                        metadata = metadata,
                        initialPosMs = latestState.positionMs,
                        now = now,
                        state = latestState
                    )
                }
                false
            }
        }
    }

    private fun handleStateChanged(
        pkg: String,
        state: PlaybackStateSnapshot,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ) {
        latestStateByPackage[pkg] = state
        val session = currentSession

        when {
            session != null && session.identity.sourcePackage != pkg -> {
                if (state.status == PlaybackStatus.PLAYING) {
                    val meta = latestMetadataByPackage[pkg]
                    if (
                        meta != null &&
                        !AdAndPlaceholderFilter.isAdOrPlaceholder(meta) &&
                        !isStalePreExistingPlayingState(state, meta.durationMs, now)
                    ) {
                        finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                        startNewSession(
                            pkg = pkg,
                            metadata = meta,
                            initialPosMs = state.positionMs,
                            now = now,
                            state = state
                        )
                    }
                }
            }

            session == null -> {
                if (state.status == PlaybackStatus.PLAYING) {
                    val meta = latestMetadataByPackage[pkg]
                    if (
                        meta != null &&
                        !AdAndPlaceholderFilter.isAdOrPlaceholder(meta) &&
                        !isStalePreExistingPlayingState(state, meta.durationMs, now)
                    ) {
                        startNewSession(
                            pkg = pkg,
                            metadata = meta,
                            initialPosMs = state.positionMs,
                            now = now,
                            state = state
                        )
                    }
                }
            }

            else -> {
                applyStateToCurrentSession(session, state, now, emitted)
            }
        }
    }

    private fun handleSessionSnapshot(
        pkg: String,
        metadata: MediaMetadataSnapshot?,
        state: PlaybackStateSnapshot?,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ): Boolean {
        if (metadata != null && metadata.title.isNotBlank() && AdAndPlaceholderFilter.isAdOrPlaceholder(metadata)) {
            if (currentSession?.identity?.sourcePackage == pkg) {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted, state = state)
            }
            latestMetadataByPackage.remove(pkg)
            if (state != null) {
                latestStateByPackage[pkg] = state
            }
            return false
        }

        val validMeta = metadata?.takeIf { it.title.isNotBlank() }
        if (validMeta != null) {
            latestMetadataByPackage[pkg] = validMeta
        }
        if (state != null) {
            latestStateByPackage[pkg] = state
        }

        var metadataPatched = false
        val session = currentSession
        if (session != null && session.identity.sourcePackage == pkg) {
            val incomingIdentity = validMeta?.let {
                TrackIdentity(
                    sourcePackage = pkg,
                    rawTitle = it.title.trim(),
                    rawArtist = it.artist.trim()
                )
            }
            val incomingDedupKey = incomingIdentity?.let {
                normalizer.normalize(it.rawTitle, it.rawArtist).dedupKey
            }
            if (
                incomingIdentity != null &&
                incomingDedupKey != null &&
                !isSameTrack(session, pkg, incomingIdentity, incomingDedupKey)
            ) {
                val effectiveState = state ?: latestStateByPackage[pkg]
                val wasActivelyPlaying = session.segmentStartElapsedMs != null
                val shouldStartPlaying = if (state != null) {
                    state.status == PlaybackStatus.PLAYING &&
                        !isStalePreExistingPlayingState(state, validMeta.durationMs, now)
                } else {
                    wasActivelyPlaying ||
                        (latestStateByPackage[pkg]?.status == PlaybackStatus.PLAYING &&
                            !isStalePreExistingPlayingState(effectiveState, validMeta.durationMs, now))
                }
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                if (shouldStartPlaying) {
                    startNewSession(
                        pkg = pkg,
                        metadata = validMeta,
                        initialPosMs = state?.positionMs ?: 0L,
                        now = now,
                        state = if (state == null && wasActivelyPlaying) null else effectiveState
                    )
                }
            } else {
                if (validMeta != null) {
                    metadataPatched = patchMetadataInPlace(session, validMeta)
                }
                if (state != null) {
                    applyStateToCurrentSession(session, state, now, emitted)
                } else {
                    promoteIfEligible(session, now)
                }
            }
        } else {
            if (state?.status == PlaybackStatus.PLAYING) {
                val effectiveMeta = validMeta ?: latestMetadataByPackage[pkg]
                if (
                    effectiveMeta != null &&
                    !AdAndPlaceholderFilter.isAdOrPlaceholder(effectiveMeta) &&
                    !isStalePreExistingPlayingState(state, effectiveMeta.durationMs, now)
                ) {
                    if (session != null) {
                        finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                    }
                    startNewSession(
                        pkg = pkg,
                        metadata = effectiveMeta,
                        initialPosMs = state.positionMs,
                        now = now,
                        state = state
                    )
                }
            }
        }
        return metadataPatched
    }

    private fun applyStateToCurrentSession(
        session: SessionRecord,
        state: PlaybackStateSnapshot,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ) {
        when {
            state.status == PlaybackStatus.PLAYING && (!state.playbackSpeed.isFinite() || state.playbackSpeed <= 0f) -> {
                val effectiveEnd = resolveEffectiveEnd(session, now, state)
                closePlayingSegment(session, effectiveEnd)
                promoteIfEligible(session, effectiveEnd)
                if (session.pausedSinceElapsedMs == null) {
                    session.pausedSinceElapsedMs = effectiveEnd
                }
                checkIdlePauseTimeout(now, emitted)
            }

            state.status == PlaybackStatus.PLAYING && isStalled(session, state) -> {
                val stallEnd = session.lastRealProgressElapsedMs.coerceAtMost(now)
                closePlayingSegment(session, stallEnd)
                promoteIfEligible(session, stallEnd)
                if (session.pausedSinceElapsedMs == null) {
                    session.pausedSinceElapsedMs = now
                }
                checkIdlePauseTimeout(now, emitted)
            }

            state.status == PlaybackStatus.PLAYING && isTrackOverrun(session, state, now) -> {
                handleTrackOverrun(session, state, now, emitted)
            }

            state.status == PlaybackStatus.PLAYING -> {
                promoteIfEligible(session, now)
                if (isSingleTrackLoop(session, newPos = state.positionMs, now = now)) {
                    val repeatIdentity = session.identity
                    val repeatDedupKey = session.dedupKey
                    val repeatNormalizedTitle = session.normalizedTitle
                    val repeatNormalizedArtist = session.normalizedArtist
                    val repeatAlbum = session.album
                    val repeatDurationMs = session.durationMs
                    val repeatArtworkUri = session.artworkUri

                    finalizeOrDiscardCurrent(now, isLoopRestart = true, emitted = emitted)
                    currentSession = SessionRecord(
                        identity = repeatIdentity,
                        dedupKey = repeatDedupKey,
                        normalizedTitle = repeatNormalizedTitle,
                        normalizedArtist = repeatNormalizedArtist,
                        album = repeatAlbum,
                        durationMs = repeatDurationMs,
                        artworkUri = repeatArtworkUri,
                        startedAtEpoch = clock.currentTimeMillis(),
                        phase = Phase.PENDING,
                        accumulatedMs = 0L,
                        segmentStartElapsedMs = now,
                        pausedSinceElapsedMs = null,
                        lastPlayingPositionMs = state.positionMs.coerceAtLeast(0L),
                        lastPositionUpdateElapsedMs = state.lastPositionUpdateTimeMs.coerceAtLeast(0L),
                        lastRealProgressElapsedMs = now,
                        lastPlaybackSpeed = state.playbackSpeed.takeIf { it > 0.05f && it.isFinite() } ?: 1.0f
                    )
                } else {
                    if (session.segmentStartElapsedMs == null) {
                        session.segmentStartElapsedMs = now
                        session.lastRealProgressElapsedMs = now
                    }
                    session.pausedSinceElapsedMs = null
                    updatePlayingTelemetry(session, state, now)
                    promoteIfEligible(session, now)
                }
            }

            state.status.isTerminal() -> {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted, state = state)
            }

            else -> {
                val effectiveEnd = resolveEffectiveEnd(session, now, state)
                closePlayingSegment(session, effectiveEnd)
                promoteIfEligible(session, effectiveEnd)
                if (session.pausedSinceElapsedMs == null) {
                    session.pausedSinceElapsedMs = effectiveEnd
                }
                checkIdlePauseTimeout(now, emitted)
            }
        }
    }

    private fun isSameTrack(
        session: SessionRecord,
        pkg: String,
        incomingIdentity: TrackIdentity,
        incomingDedupKey: String
    ): Boolean = session.identity.sourcePackage == pkg &&
        (session.identity == incomingIdentity ||
            (session.dedupKey.isNotEmpty() && session.dedupKey == incomingDedupKey))

    private fun isStalled(
        session: SessionRecord,
        state: PlaybackStateSnapshot
    ): Boolean {
        if (state.lastPositionUpdateTimeMs <= 0L || session.lastPositionUpdateElapsedMs <= 0L) {
            return false
        }
        if (state.positionMs < 0L) return false
        return state.lastPositionUpdateTimeMs > session.lastPositionUpdateElapsedMs &&
            state.positionMs == session.lastPlayingPositionMs &&
            (state.lastPositionUpdateTimeMs - session.lastRealProgressElapsedMs) >= STALL_THRESHOLD_MS
    }

    private fun projectedTrackEndMs(
        session: SessionRecord,
        state: PlaybackStateSnapshot? = null
    ): Long? {
        if (session.durationMs <= 0L) return null
        val useFreshState = state != null &&
            state.lastPositionUpdateTimeMs > 0L &&
            state.lastPositionUpdateTimeMs > session.lastPositionUpdateElapsedMs
        val updateElapsedMs = if (useFreshState) {
            state!!.lastPositionUpdateTimeMs
        } else {
            session.lastPositionUpdateElapsedMs
        }
        if (updateElapsedMs <= 0L) return null
        val positionMs = if (useFreshState) {
            state!!.positionMs.coerceAtLeast(0L)
        } else {
            session.lastPlayingPositionMs.coerceAtLeast(0L)
        }
        val rawSpeed = if (useFreshState) state!!.playbackSpeed else session.lastPlaybackSpeed
        val speed = rawSpeed.takeIf { it > 0.05f && it.isFinite() } ?: 1.0f
        val remainingMs = ((session.durationMs - positionMs).coerceAtLeast(0L) / speed).toLong()
        return updateElapsedMs + remainingMs + TRACK_OVERRUN_GRACE_MS
    }

    private fun isTrackOverrun(
        session: SessionRecord,
        state: PlaybackStateSnapshot?,
        now: Long
    ): Boolean {
        if (session.segmentStartElapsedMs == null) return false
        val projectedEnd = projectedTrackEndMs(session, state) ?: return false
        return now > projectedEnd
    }

    private fun handleTrackOverrun(
        session: SessionRecord,
        state: PlaybackStateSnapshot?,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ) {
        val projectedEnd = (projectedTrackEndMs(session, state) ?: now).coerceAtMost(now)
        closePlayingSegment(session, projectedEnd)
        promoteIfEligible(session, projectedEnd)
        if (session.pausedSinceElapsedMs == null) {
            session.pausedSinceElapsedMs = projectedEnd
        }
        checkIdlePauseTimeout(now, emitted)
    }

    private fun isStalePreExistingPlayingState(
        state: PlaybackStateSnapshot?,
        durationMs: Long,
        now: Long
    ): Boolean {
        if (state == null || state.status != PlaybackStatus.PLAYING) return false
        if (!state.playbackSpeed.isFinite() || state.playbackSpeed <= 0f) return true
        if (state.lastPositionUpdateTimeMs > 0L && durationMs > 0L) {
            val elapsedSinceUpdate = (now - state.lastPositionUpdateTimeMs).coerceAtLeast(0L)
            val speed = state.playbackSpeed.takeIf { it > 0.05f && it.isFinite() } ?: 1.0f
            val projectedPos = state.positionMs.coerceAtLeast(0L) + (elapsedSinceUpdate * speed).toLong()
            if (projectedPos > durationMs + STALE_ATTACH_GRACE_MS) {
                return true
            }
        }
        return false
    }

    private fun updatePlayingTelemetry(
        session: SessionRecord,
        state: PlaybackStateSnapshot,
        now: Long
    ) {
        if (state.positionMs >= 0L) {
            if (state.positionMs != session.lastPlayingPositionMs) {
                session.lastRealProgressElapsedMs =
                    if (state.lastPositionUpdateTimeMs > 0L) state.lastPositionUpdateTimeMs else now
            }
            session.lastPlayingPositionMs = state.positionMs
        }
        if (state.lastPositionUpdateTimeMs > 0L) {
            if (session.lastPositionUpdateElapsedMs == 0L) {
                session.lastRealProgressElapsedMs = state.lastPositionUpdateTimeMs
            }
            session.lastPositionUpdateElapsedMs = state.lastPositionUpdateTimeMs
        }
        if (state.playbackSpeed > 0.05f && state.playbackSpeed.isFinite()) {
            session.lastPlaybackSpeed = state.playbackSpeed
        }
    }

    private fun resolveEffectiveEnd(
        session: SessionRecord,
        now: Long,
        state: PlaybackStateSnapshot? = null
    ): Long {
        if (state != null && state.lastPositionUpdateTimeMs > 0L) {
            val start = session.segmentStartElapsedMs
            if (start != null && state.lastPositionUpdateTimeMs in start..now) {
                return state.lastPositionUpdateTimeMs
            }
        }

        val projectedEnd = projectedTrackEndMs(session)
        if (projectedEnd != null && now > projectedEnd) {
            return projectedEnd
        }

        return now
    }

    private fun isSingleTrackLoop(session: SessionRecord, newPos: Long, now: Long): Boolean {
        if (session.phase != Phase.ACTIVE) return false
        val dur = session.durationMs
        if (dur <= 0L) return false
        if (newPos < 0L) return false
        if (newPos > LOOP_START_WINDOW_MS) return false

        val prevPos = session.lastPlayingPositionMs
        val nearEnd = (dur > LOOP_END_WINDOW_MS && prevPos >= dur - LOOP_END_WINDOW_MS) ||
            prevPos.toDouble() / dur.toDouble() >= LOOP_END_RATIO
        if (!nearEnd) return false

        val minDrop = minOf(LOOP_MIN_DROP_MS, dur / 2L)
        if (newPos >= prevPos - minDrop) return false

        val minListened = minOf(LOOP_MIN_LISTENED_MS, (dur * 0.5).toLong())
        return effectiveListenedMs(session, now) >= minListened
    }

    private fun effectiveListenedMs(session: SessionRecord, now: Long): Long {
        val liveDelta = session.segmentStartElapsedMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        return session.accumulatedMs + liveDelta
    }

    private fun closePlayingSegment(session: SessionRecord, effectiveEnd: Long) {
        val start = session.segmentStartElapsedMs ?: return
        val rawDelta = (effectiveEnd - start).coerceAtLeast(0L)
        val maxSingleSegmentMs = if (session.durationMs > 0L) {
            maxOf(session.durationMs * 2L, session.durationMs + 120_000L)
        } else {
            MAX_UNKNOWN_DURATION_SEGMENT_MS
        }
        session.accumulatedMs += rawDelta.coerceAtMost(maxSingleSegmentMs)
        session.segmentStartElapsedMs = null
    }

    private fun promoteIfEligible(session: SessionRecord, now: Long) {
        if (session.phase == Phase.PENDING && effectiveListenedMs(session, now) >= debounceMs) {
            session.phase = Phase.ACTIVE
        }
    }

    private fun checkIdlePauseTimeout(now: Long, emitted: MutableList<CompletedListenSession>) {
        val session = currentSession ?: return
        if (session.segmentStartElapsedMs != null) return
        val pausedSince = session.pausedSinceElapsedMs ?: return
        if ((now - pausedSince).coerceAtLeast(0L) >= idlePauseTimeoutMs) {
            finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
        }
    }

    private fun finalizeOrDiscardCurrent(
        now: Long,
        isLoopRestart: Boolean,
        emitted: MutableList<CompletedListenSession>,
        state: PlaybackStateSnapshot? = null
    ) {
        val session = currentSession ?: return
        val effectiveEnd = resolveEffectiveEnd(session, now, state)
        closePlayingSegment(session, effectiveEnd)
        promoteIfEligible(session, effectiveEnd)
        currentSession = null

        if (session.phase == Phase.ACTIVE && session.accumulatedMs > 0L) {
            val actualMs = session.accumulatedMs
            val completedRatio = if (session.durationMs > 0L) {
                actualMs.toFloat() / session.durationMs.toFloat()
            } else {
                0f
            }
            val wasSkipped = when {
                isLoopRestart -> false
                session.durationMs > 0L -> completedRatio < SKIP_COMPLETION_THRESHOLD
                else -> actualMs < SKIP_UNKNOWN_DURATION_MS
            }
            val endedAtEpoch = clock.currentTimeMillis().coerceAtLeast(session.startedAtEpoch)
            emitted.add(
                CompletedListenSession(
                    sourcePackage = session.identity.sourcePackage,
                    rawTitle = session.identity.rawTitle,
                    rawArtist = session.identity.rawArtist,
                    album = session.album,
                    durationMs = session.durationMs,
                    artworkUri = session.artworkUri,
                    startedAtEpoch = session.startedAtEpoch,
                    endedAtEpoch = endedAtEpoch,
                    actualListenedMs = actualMs,
                    completedRatio = completedRatio,
                    wasSkipped = wasSkipped
                )
            )
        }
    }

    private fun startNewSession(
        pkg: String,
        metadata: MediaMetadataSnapshot,
        initialPosMs: Long,
        now: Long,
        state: PlaybackStateSnapshot? = null
    ) {
        val identity = TrackIdentity(
            sourcePackage = pkg,
            rawTitle = metadata.title.trim(),
            rawArtist = metadata.artist.trim()
        )
        val normalized = normalizer.normalize(identity.rawTitle, identity.rawArtist)
        currentSession = SessionRecord(
            identity = identity,
            dedupKey = normalized.dedupKey,
            normalizedTitle = normalized.normalizedTitle,
            normalizedArtist = normalized.normalizedArtist,
            album = metadata.album.trim(),
            durationMs = metadata.durationMs.coerceAtLeast(0L),
            artworkUri = metadata.artworkUri,
            startedAtEpoch = clock.currentTimeMillis(),
            phase = Phase.PENDING,
            accumulatedMs = 0L,
            segmentStartElapsedMs = now,
            pausedSinceElapsedMs = null,
            lastPlayingPositionMs = initialPosMs.coerceAtLeast(0L),
            lastPositionUpdateElapsedMs = state?.lastPositionUpdateTimeMs?.coerceAtLeast(0L) ?: 0L,
            lastRealProgressElapsedMs = now,
            lastPlaybackSpeed = state?.playbackSpeed?.takeIf { it > 0.05f && it.isFinite() } ?: 1.0f
        )
    }

    private fun patchMetadataInPlace(session: SessionRecord, metadata: MediaMetadataSnapshot): Boolean {
        var changed = false
        if (metadata.durationMs > 0L && session.durationMs != metadata.durationMs) {
            session.durationMs = metadata.durationMs
            changed = true
        }
        val trimmedAlbum = metadata.album.trim()
        if (trimmedAlbum.isNotBlank() && session.album != trimmedAlbum) {
            session.album = trimmedAlbum
            changed = true
        }
        if (metadata.artworkUri != null && session.artworkUri != metadata.artworkUri) {
            session.artworkUri = metadata.artworkUri
            changed = true
        }
        return changed
    }

    private fun publishNowTracking() {
        _nowTracking.value = currentSession?.toNowTrackingState()
    }

    private fun PlaybackStatus.isTerminal(): Boolean = when (this) {
        PlaybackStatus.STOPPED,
        PlaybackStatus.NONE,
        PlaybackStatus.ERROR -> true

        else -> false
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_500L
        const val DEFAULT_IDLE_PAUSE_TIMEOUT_MS = 3L * 60L * 1_000L
        const val LOOP_END_WINDOW_MS = 5_000L
        const val LOOP_END_RATIO = 0.90
        const val LOOP_START_WINDOW_MS = 3_000L
        const val LOOP_MIN_DROP_MS = 3_000L
        const val LOOP_MIN_LISTENED_MS = 10_000L
        const val SKIP_COMPLETION_THRESHOLD = 0.50f
        const val SKIP_UNKNOWN_DURATION_MS = 30_000L
        const val TRACK_OVERRUN_GRACE_MS = 15_000L
        const val STALL_THRESHOLD_MS = 20_000L
        const val STALE_ATTACH_GRACE_MS = 60_000L
        const val MAX_UNKNOWN_DURATION_SEGMENT_MS = 2L * 60L * 60L * 1_000L
    }
}
