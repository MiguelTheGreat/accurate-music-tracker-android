package com.musictracker.app.domain.tracking

import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.model.TrackIdentity
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
        var lastPlayingPositionMs: Long = 0L
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
            val emitted = mutableListOf<CompletedListenSession>()

            checkIdlePauseTimeout(now, emitted)

            when (event) {
                is PlaybackEvent.Tick -> {
                    currentSession?.let { promoteIfEligible(it, now) }
                }

                is PlaybackEvent.MetadataChanged -> {
                    handleMetadataChanged(event.sourcePackage.trim(), event.metadata, now, emitted)
                }

                is PlaybackEvent.StateChanged -> {
                    handleStateChanged(event.sourcePackage.trim(), event.state, now, emitted)
                }

                is PlaybackEvent.SessionSnapshot -> {
                    handleSessionSnapshot(
                        pkg = event.sourcePackage.trim(),
                        metadata = event.metadata,
                        state = event.state,
                        now = now,
                        emitted = emitted
                    )
                }
            }

            publishNowTracking()
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
    ) {
        if (metadata.title.isBlank()) {
            return
        }

        latestMetadataByPackage[pkg] = metadata
        val incomingIdentity = TrackIdentity(
            sourcePackage = pkg,
            rawTitle = metadata.title.trim(),
            rawArtist = metadata.artist.trim()
        )
        val session = currentSession

        when {
            session != null && session.identity == incomingIdentity -> {
                patchMetadataInPlace(session, metadata)
                promoteIfEligible(session, now)
            }

            session != null && session.identity.sourcePackage == pkg -> {
                val wasPlaying = session.segmentStartElapsedMs != null ||
                    latestStateByPackage[pkg]?.status == PlaybackStatus.PLAYING
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                if (wasPlaying) {
                    startNewSession(pkg, metadata, initialPosMs = 0L, now = now)
                }
            }

            session == null -> {
                val latestState = latestStateByPackage[pkg]
                if (latestState?.status == PlaybackStatus.PLAYING) {
                    startNewSession(pkg, metadata, initialPosMs = latestState.positionMs, now = now)
                }
            }

            else -> {
                val latestState = latestStateByPackage[pkg]
                if (latestState?.status == PlaybackStatus.PLAYING) {
                    finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                    startNewSession(pkg, metadata, initialPosMs = latestState.positionMs, now = now)
                }
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
                    if (meta != null) {
                        finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                        startNewSession(pkg, meta, initialPosMs = state.positionMs, now = now)
                    }
                }
            }

            session == null -> {
                if (state.status == PlaybackStatus.PLAYING) {
                    val meta = latestMetadataByPackage[pkg]
                    if (meta != null) {
                        startNewSession(pkg, meta, initialPosMs = state.positionMs, now = now)
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
    ) {
        val validMeta = metadata?.takeIf { it.title.isNotBlank() }
        if (validMeta != null) {
            latestMetadataByPackage[pkg] = validMeta
        }
        if (state != null) {
            latestStateByPackage[pkg] = state
        }

        val session = currentSession
        if (session != null && session.identity.sourcePackage == pkg) {
            val incomingIdentity = validMeta?.let {
                TrackIdentity(
                    sourcePackage = pkg,
                    rawTitle = it.title.trim(),
                    rawArtist = it.artist.trim()
                )
            }
            if (incomingIdentity != null && incomingIdentity != session.identity) {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                if (state?.status == PlaybackStatus.PLAYING) {
                    startNewSession(pkg, validMeta, initialPosMs = state.positionMs, now = now)
                }
            } else {
                if (validMeta != null) {
                    patchMetadataInPlace(session, validMeta)
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
                if (effectiveMeta != null) {
                    if (session != null) {
                        finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
                    }
                    startNewSession(pkg, effectiveMeta, initialPosMs = state.positionMs, now = now)
                }
            }
        }
    }

    private fun applyStateToCurrentSession(
        session: SessionRecord,
        state: PlaybackStateSnapshot,
        now: Long,
        emitted: MutableList<CompletedListenSession>
    ) {
        when {
            state.status == PlaybackStatus.PLAYING -> {
                promoteIfEligible(session, now)
                if (isSingleTrackLoop(session, newPos = state.positionMs, now = now)) {
                    val repeatIdentity = session.identity
                    val repeatNormalizedTitle = session.normalizedTitle
                    val repeatNormalizedArtist = session.normalizedArtist
                    val repeatAlbum = session.album
                    val repeatDurationMs = session.durationMs
                    val repeatArtworkUri = session.artworkUri

                    finalizeOrDiscardCurrent(now, isLoopRestart = true, emitted = emitted)
                    currentSession = SessionRecord(
                        identity = repeatIdentity,
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
                        lastPlayingPositionMs = state.positionMs.coerceAtLeast(0L)
                    )
                } else {
                    if (session.segmentStartElapsedMs == null) {
                        session.segmentStartElapsedMs = now
                    }
                    session.pausedSinceElapsedMs = null
                    if (state.positionMs >= 0L) {
                        session.lastPlayingPositionMs = state.positionMs
                    }
                    promoteIfEligible(session, now)
                }
            }

            state.status.isTerminal() -> {
                finalizeOrDiscardCurrent(now, isLoopRestart = false, emitted = emitted)
            }

            else -> {
                closePlayingSegment(session, now)
                promoteIfEligible(session, now)
                if (session.pausedSinceElapsedMs == null) {
                    session.pausedSinceElapsedMs = now
                }
            }
        }
    }

    private fun isSingleTrackLoop(session: SessionRecord, newPos: Long, now: Long): Boolean {
        if (session.phase != Phase.ACTIVE) return false
        val dur = session.durationMs
        if (dur <= 0L) return false
        if (newPos < 0L) return false
        if (newPos > LOOP_START_WINDOW_MS) return false

        val prevPos = session.lastPlayingPositionMs
        val nearEnd = prevPos >= (dur - LOOP_END_WINDOW_MS).coerceAtLeast(0L) ||
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

    private fun closePlayingSegment(session: SessionRecord, now: Long) {
        val start = session.segmentStartElapsedMs ?: return
        session.accumulatedMs += (now - start).coerceAtLeast(0L)
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
        emitted: MutableList<CompletedListenSession>
    ) {
        val session = currentSession ?: return
        closePlayingSegment(session, now)
        promoteIfEligible(session, now)
        currentSession = null

        if (session.phase == Phase.ACTIVE) {
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
        now: Long
    ) {
        val identity = TrackIdentity(
            sourcePackage = pkg,
            rawTitle = metadata.title.trim(),
            rawArtist = metadata.artist.trim()
        )
        val normalized = normalizer.normalize(identity.rawTitle, identity.rawArtist)
        currentSession = SessionRecord(
            identity = identity,
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
            lastPlayingPositionMs = initialPosMs.coerceAtLeast(0L)
        )
    }

    private fun patchMetadataInPlace(session: SessionRecord, metadata: MediaMetadataSnapshot) {
        if (metadata.durationMs > 0L) {
            session.durationMs = metadata.durationMs
        }
        if (metadata.album.isNotBlank()) {
            session.album = metadata.album.trim()
        }
        if (metadata.artworkUri != null) {
            session.artworkUri = metadata.artworkUri
        }
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
        const val DEFAULT_IDLE_PAUSE_TIMEOUT_MS = 30L * 60L * 1_000L
        const val LOOP_END_WINDOW_MS = 5_000L
        const val LOOP_END_RATIO = 0.90
        const val LOOP_START_WINDOW_MS = 3_000L
        const val LOOP_MIN_DROP_MS = 3_000L
        const val LOOP_MIN_LISTENED_MS = 10_000L
        const val SKIP_COMPLETION_THRESHOLD = 0.50f
        const val SKIP_UNKNOWN_DURATION_MS = 30_000L
    }
}
