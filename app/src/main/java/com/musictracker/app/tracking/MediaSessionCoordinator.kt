package com.musictracker.app.tracking

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.musictracker.app.data.preferences.AllowlistRepository
import com.musictracker.app.data.preferences.DefaultAllowlist
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.CompletedListenSession
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.PlaybackEvent
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.domain.tracking.PlaySessionStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TrackedMediaController {
    val tokenId: Any
    val packageName: String
    val currentMetadata: MediaMetadataSnapshot?
    val currentPlaybackState: PlaybackStateSnapshot?
    fun registerCallback(listener: Listener)
    fun unregisterCallback()

    interface Listener {
        fun onMetadataChanged(metadata: MediaMetadataSnapshot?)
        fun onPlaybackStateChanged(state: PlaybackStateSnapshot?)
        fun onSessionDestroyed()
    }
}

class AndroidMediaControllerAdapter(
    private val controller: MediaController,
    private val callbackHandler: Handler? = Handler(Looper.getMainLooper())
) : TrackedMediaController {

    private var registeredCallback: MediaController.Callback? = null

    override val tokenId: Any
        get() = controller.sessionToken

    override val packageName: String
        get() = controller.packageName.orEmpty()

    override val currentMetadata: MediaMetadataSnapshot?
        get() = controller.metadata?.toDomainSnapshot()

    override val currentPlaybackState: PlaybackStateSnapshot?
        get() = controller.playbackState?.toDomainSnapshot()

    override fun registerCallback(listener: TrackedMediaController.Listener) {
        unregisterCallback()
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                listener.onMetadataChanged(metadata?.toDomainSnapshot())
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                listener.onPlaybackStateChanged(state?.toDomainSnapshot())
            }

            override fun onSessionDestroyed() {
                listener.onSessionDestroyed()
            }
        }
        registeredCallback = callback
        if (callbackHandler != null) {
            controller.registerCallback(callback, callbackHandler)
        } else {
            controller.registerCallback(callback)
        }
    }

    override fun unregisterCallback() {
        val callback = registeredCallback ?: return
        registeredCallback = null
        runCatching {
            controller.unregisterCallback(callback)
        }
    }
}

fun MediaMetadata.toDomainSnapshot(): MediaMetadataSnapshot {
    val title = firstNonBlank(
        getString(MediaMetadata.METADATA_KEY_TITLE),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    ).orEmpty()

    val artist = firstNonBlank(
        getString(MediaMetadata.METADATA_KEY_ARTIST),
        getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    ).orEmpty()

    val album = firstNonBlank(
        getString(MediaMetadata.METADATA_KEY_ALBUM),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
    ).orEmpty()

    val durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)

    val artworkUri = firstNonBlank(
        getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
        getString(MediaMetadata.METADATA_KEY_ART_URI),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
    )

    return MediaMetadataSnapshot(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUri = artworkUri
    )
}

fun PlaybackState.toDomainSnapshot(): PlaybackStateSnapshot {
    val mappedStatus = when (state) {
        PlaybackState.STATE_STOPPED -> PlaybackStatus.STOPPED
        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
        PlaybackState.STATE_PLAYING -> PlaybackStatus.PLAYING
        PlaybackState.STATE_FAST_FORWARDING -> PlaybackStatus.FAST_FORWARDING
        PlaybackState.STATE_REWINDING -> PlaybackStatus.REWINDING
        PlaybackState.STATE_BUFFERING -> PlaybackStatus.BUFFERING
        PlaybackState.STATE_ERROR -> PlaybackStatus.ERROR
        PlaybackState.STATE_CONNECTING -> PlaybackStatus.CONNECTING
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> PlaybackStatus.SKIPPING_TO_PREVIOUS
        PlaybackState.STATE_SKIPPING_TO_NEXT -> PlaybackStatus.SKIPPING_TO_NEXT
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.SKIPPING_TO_QUEUE_ITEM
        else -> PlaybackStatus.NONE
    }

    return PlaybackStateSnapshot(
        status = mappedStatus,
        // Preserve PlaybackState.PLAYBACK_POSITION_UNKNOWN (-1L) verbatim so loop detection does not misfire.
        positionMs = position,
        playbackSpeed = playbackSpeed,
        lastPositionUpdateTimeMs = lastPositionUpdateTime
    )
}

private fun firstNonBlank(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim()
        if (!trimmed.isNullOrEmpty()) {
            return trimmed
        }
    }
    return null
}

class MediaSessionCoordinator(
    private val playSessionEngine: PlaySessionEngine,
    private val allowlistRepository: AllowlistRepository,
    private val listeningRepository: ListeningRepository,
    private val clock: TrackerClock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val schedulerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = PlaySessionStateMachine.DEFAULT_DEBOUNCE_MS,
    private val idlePauseTimeoutMs: Long = PlaySessionStateMachine.DEFAULT_IDLE_PAUSE_TIMEOUT_MS
) {

    private data class AttachedEntry(
        val controller: TrackedMediaController,
        var cachedMetadata: MediaMetadataSnapshot?,
        var cachedState: PlaybackStateSnapshot?
    )

    private val stateLock = Any()
    private val persistenceMutex = Mutex()

    private val _isListenerConnected = MutableStateFlow(false)
    val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

    val nowTracking: StateFlow<NowTrackingState?>
        get() = playSessionEngine.nowTracking

    private var lastKnownControllers: List<TrackedMediaController> = emptyList()
    private var allowedPackages: Set<String> = DefaultAllowlist.packages
    private val attachedControllers = LinkedHashMap<Any, AttachedEntry>()
    private var focusedTokenId: Any? = null

    private var allowlistJob: Job? = null
    private var debounceJob: Job? = null
    private var idleTimeoutJob: Job? = null

    fun setListenerConnected(connected: Boolean) {
        _isListenerConnected.value = connected
    }

    fun start() {
        synchronized(stateLock) {
            if (allowlistJob?.isActive == true) return
            allowlistJob = scope.launch(schedulerDispatcher) {
                allowlistRepository.allowedPackages.collect { updatedAllowlist ->
                    val emitted = synchronized(stateLock) {
                        allowedPackages = updatedAllowlist
                        reconcileSessionsLocked(lastKnownControllers)
                    }
                    persistSessionsAsync(emitted)
                }
            }
        }
    }

    fun onAndroidSessionsChanged(
        controllers: List<MediaController>?,
        callbackHandler: Handler? = Handler(Looper.getMainLooper())
    ) {
        val adapted = controllers.orEmpty().map { controller ->
            AndroidMediaControllerAdapter(
                controller = controller,
                callbackHandler = callbackHandler
            )
        }
        onActiveSessionsChanged(adapted)
    }

    fun onActiveSessionsChanged(controllers: List<TrackedMediaController>) {
        val emitted = synchronized(stateLock) {
            lastKnownControllers = controllers.distinctBy { it.tokenId }
            reconcileSessionsLocked(lastKnownControllers)
        }
        persistSessionsAsync(emitted)
    }

    fun flushAndStop(): Job {
        val emitted = synchronized(stateLock) {
            allowlistJob?.cancel()
            allowlistJob = null
            debounceJob?.cancel()
            debounceJob = null
            idleTimeoutJob?.cancel()
            idleTimeoutJob = null

            attachedControllers.values.forEach { entry ->
                runCatching { entry.controller.unregisterCallback() }
            }
            attachedControllers.clear()
            lastKnownControllers = emptyList()
            focusedTokenId = null

            playSessionEngine.flushActiveSession()
        }

        return scope.launch(ioDispatcher) {
            withContext(NonCancellable) {
                persistSessions(emitted)
            }
        }
    }

    fun onServiceStopped(): Job = flushAndStop()

    private fun reconcileSessionsLocked(
        controllers: List<TrackedMediaController>
    ): List<CompletedListenSession> {
        val emitted = mutableListOf<CompletedListenSession>()
        val eligible = controllers.filter { it.packageName in allowedPackages }
        val eligibleByToken = LinkedHashMap<Any, TrackedMediaController>(eligible.size)
        for (controller in eligible) {
            eligibleByToken.putIfAbsent(controller.tokenId, controller)
        }

        // Detach controllers that are no longer active or no longer allowlisted.
        val removedTokens = attachedControllers.keys.filter { it !in eligibleByToken }
        for (tokenId in removedTokens) {
            val removed = attachedControllers.remove(tokenId) ?: continue
            runCatching { removed.controller.unregisterCallback() }
            if (focusedTokenId == tokenId) {
                focusedTokenId = null
                emitted += playSessionEngine.onSessionEnded(removed.controller.packageName)
            }
        }

        // Rebuild attachedControllers in incoming priority order, reusing already-attached entries.
        val reordered = LinkedHashMap<Any, AttachedEntry>(eligibleByToken.size)
        for ((tokenId, controller) in eligibleByToken) {
            val existing = attachedControllers[tokenId]
            if (existing != null) {
                reordered[tokenId] = existing
            } else {
                val entry = AttachedEntry(
                    controller = controller,
                    cachedMetadata = controller.currentMetadata,
                    cachedState = controller.currentPlaybackState
                )
                reordered[tokenId] = entry
                controller.registerCallback(createListener(tokenId))
            }
        }
        attachedControllers.clear()
        attachedControllers.putAll(reordered)

        // Focus selection: keep valid PLAYING focus, or pick the highest-priority PLAYING controller.
        if (focusedTokenId == null || focusedTokenId !in attachedControllers) {
            focusedTokenId = null
            selectFallbackFocusLocked(emitted)
        } else if (attachedControllers[focusedTokenId]?.cachedState?.status != PlaybackStatus.PLAYING) {
            val candidate = attachedControllers.entries.firstOrNull { (_, entry) ->
                entry.cachedState?.status == PlaybackStatus.PLAYING
            }
            if (candidate != null) {
                val previousFocused = attachedControllers[focusedTokenId]
                focusedTokenId = candidate.key
                if (
                    candidate.value.cachedMetadata?.title.isNullOrBlank() &&
                    previousFocused != null
                ) {
                    emitted += playSessionEngine.onSessionEnded(previousFocused.controller.packageName)
                }
                emitted += playSessionEngine.onPlaybackEvent(
                    PlaybackEvent.SessionSnapshot(
                        sourcePackage = candidate.value.controller.packageName,
                        metadata = candidate.value.cachedMetadata,
                        state = candidate.value.cachedState
                    )
                )
            }
        }

        updateSchedulersLocked()
        return emitted
    }

    private fun createListener(tokenId: Any): TrackedMediaController.Listener =
        object : TrackedMediaController.Listener {
            override fun onMetadataChanged(metadata: MediaMetadataSnapshot?) {
                handleControllerMetadataChanged(tokenId, metadata)
            }

            override fun onPlaybackStateChanged(state: PlaybackStateSnapshot?) {
                handleControllerStateChanged(tokenId, state)
            }

            override fun onSessionDestroyed() {
                handleControllerDestroyed(tokenId)
            }
        }

    private fun handleControllerMetadataChanged(
        tokenId: Any,
        metadata: MediaMetadataSnapshot?
    ) {
        val emitted = synchronized(stateLock) {
            val entry = attachedControllers[tokenId] ?: return
            entry.cachedMetadata = metadata
            val results = if (focusedTokenId == tokenId && metadata != null) {
                playSessionEngine.onPlaybackEvent(
                    PlaybackEvent.MetadataChanged(
                        sourcePackage = entry.controller.packageName,
                        metadata = metadata
                    )
                )
            } else {
                emptyList()
            }
            updateSchedulersLocked()
            results
        }
        persistSessionsAsync(emitted)
    }

    private fun handleControllerStateChanged(
        tokenId: Any,
        state: PlaybackStateSnapshot?
    ) {
        if (state == null) return
        val emitted = synchronized(stateLock) {
            val entry = attachedControllers[tokenId] ?: return
            entry.cachedState = state
            val results = mutableListOf<CompletedListenSession>()

            if (focusedTokenId == tokenId) {
                results += playSessionEngine.onPlaybackEvent(
                    PlaybackEvent.StateChanged(
                        sourcePackage = entry.controller.packageName,
                        state = state
                    )
                )
                if (state.status.isTerminal()) {
                    focusedTokenId = null
                    selectFallbackFocusLocked(results)
                }
            } else if (state.status == PlaybackStatus.PLAYING) {
                val previousFocused = focusedTokenId?.let { attachedControllers[it] }
                focusedTokenId = tokenId
                if (
                    entry.cachedMetadata?.title.isNullOrBlank() &&
                    previousFocused != null
                ) {
                    results += playSessionEngine.onSessionEnded(previousFocused.controller.packageName)
                }
                results += playSessionEngine.onPlaybackEvent(
                    PlaybackEvent.SessionSnapshot(
                        sourcePackage = entry.controller.packageName,
                        metadata = entry.cachedMetadata,
                        state = state
                    )
                )
            }
            // Non-focused PAUSED / STOPPED / BUFFERING updates are cached only and ignored.

            updateSchedulersLocked()
            results
        }
        persistSessionsAsync(emitted)
    }

    private fun handleControllerDestroyed(tokenId: Any) {
        val emitted = synchronized(stateLock) {
            val removed = attachedControllers.remove(tokenId)
            lastKnownControllers = lastKnownControllers.filterNot { it.tokenId == tokenId }
            if (removed == null) return

            runCatching { removed.controller.unregisterCallback() }
            val results = mutableListOf<CompletedListenSession>()
            if (focusedTokenId == tokenId) {
                focusedTokenId = null
                results += playSessionEngine.onSessionEnded(removed.controller.packageName)
                selectFallbackFocusLocked(results)
            }
            updateSchedulersLocked()
            results
        }
        persistSessionsAsync(emitted)
    }

    private fun selectFallbackFocusLocked(emitted: MutableList<CompletedListenSession>) {
        val candidate = attachedControllers.entries.firstOrNull { (_, entry) ->
            entry.cachedState?.status == PlaybackStatus.PLAYING
        } ?: return

        focusedTokenId = candidate.key
        emitted += playSessionEngine.onPlaybackEvent(
            PlaybackEvent.SessionSnapshot(
                sourcePackage = candidate.value.controller.packageName,
                metadata = candidate.value.cachedMetadata,
                state = candidate.value.cachedState
            )
        )
    }

    private fun updateSchedulersLocked() {
        val tracking = playSessionEngine.nowTracking.value

        if (tracking != null && tracking.isDebouncing && tracking.isPlaying) {
            if (debounceJob?.isActive != true) {
                val liveDelta = tracking.segmentStartElapsedMs
                    ?.let { (clock.elapsedRealtimeMs() - it).coerceAtLeast(0L) }
                    ?: 0L
                val remainingMs = (debounceMs - (tracking.accumulatedMs + liveDelta))
                    .coerceAtLeast(1L)
                debounceJob = scope.launch(schedulerDispatcher) {
                    val currentJob = coroutineContext[Job]
                    delay(remainingMs)
                    handleScheduledTick(isDebounce = true, scheduledJob = currentJob)
                }
            }
        } else {
            debounceJob?.cancel()
            debounceJob = null
        }

        if (tracking != null && !tracking.isPlaying) {
            if (idleTimeoutJob?.isActive != true) {
                idleTimeoutJob = scope.launch(schedulerDispatcher) {
                    val currentJob = coroutineContext[Job]
                    delay(idlePauseTimeoutMs)
                    handleScheduledTick(isDebounce = false, scheduledJob = currentJob)
                }
            }
        } else {
            idleTimeoutJob?.cancel()
            idleTimeoutJob = null
        }
    }

    private fun handleScheduledTick(isDebounce: Boolean, scheduledJob: Job?) {
        val emitted = synchronized(stateLock) {
            if (isDebounce) {
                if (debounceJob === scheduledJob) {
                    debounceJob = null
                }
            } else {
                if (idleTimeoutJob === scheduledJob) {
                    idleTimeoutJob = null
                }
            }
            val results = playSessionEngine.onTick()
            updateSchedulersLocked()
            results
        }
        persistSessionsAsync(emitted)
    }

    private fun persistSessionsAsync(sessions: List<CompletedListenSession>) {
        if (sessions.isEmpty()) return
        scope.launch(ioDispatcher) {
            persistSessions(sessions)
        }
    }

    private suspend fun persistSessions(sessions: List<CompletedListenSession>) {
        if (sessions.isEmpty()) return
        persistenceMutex.withLock {
            for (session in sessions) {
                try {
                    listeningRepository.recordCompletedSession(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    runCatching {
                        Log.e(TAG, "Failed to persist completed listen session", t)
                    }
                }
            }
        }
    }

    private fun PlaybackStatus.isTerminal(): Boolean = when (this) {
        PlaybackStatus.STOPPED,
        PlaybackStatus.NONE,
        PlaybackStatus.ERROR -> true

        else -> false
    }

    companion object {
        private const val TAG = "MediaSessionCoordinator"
    }
}
