package com.musictracker.app.e2e.harness

import android.content.Context
import com.musictracker.app.domain.model.MediaMetadataSnapshot
import com.musictracker.app.domain.model.PlaybackStateSnapshot
import com.musictracker.app.domain.model.PlaybackStatus
import com.musictracker.app.tracking.InstalledAppInfo
import com.musictracker.app.tracking.SystemPermissionHelper
import com.musictracker.app.tracking.TrackedMediaController
import java.util.concurrent.atomic.AtomicLong

/**
 * Synthetic [TrackedMediaController] for driving [com.musictracker.app.tracking.MediaSessionCoordinator]
 * deterministically without mocking Android platform internals.
 */
class FakeMediaControllerDriver(
    override val packageName: String,
    override val tokenId: Any = "${packageName}_token_${tokenCounter.incrementAndGet()}",
    initialMetadata: MediaMetadataSnapshot? = null,
    initialPlaybackState: PlaybackStateSnapshot? = null
) : TrackedMediaController {

    companion object {
        private val tokenCounter = AtomicLong(0L)
    }

    private var metadata: MediaMetadataSnapshot? = initialMetadata
    private var playbackState: PlaybackStateSnapshot? = initialPlaybackState
    private var listener: TrackedMediaController.Listener? = null

    var isRegistered: Boolean = false
        private set
    var registerCount: Int = 0
        private set
    var unregisterCount: Int = 0
        private set

    override val currentMetadata: MediaMetadataSnapshot?
        get() = metadata

    override val currentPlaybackState: PlaybackStateSnapshot?
        get() = playbackState

    override fun registerCallback(listener: TrackedMediaController.Listener) {
        this.listener = listener
        isRegistered = true
        registerCount++
    }

    override fun unregisterCallback() {
        this.listener = null
        isRegistered = false
        unregisterCount++
    }

    fun emitMetadata(newMetadata: MediaMetadataSnapshot?) {
        metadata = newMetadata
        listener?.onMetadataChanged(newMetadata)
    }

    fun emitPlaybackState(newState: PlaybackStateSnapshot?) {
        playbackState = newState
        listener?.onPlaybackStateChanged(newState)
    }

    fun emitSessionDestroyed() {
        listener?.onSessionDestroyed()
    }

    fun play(positionMs: Long = 0L, speed: Float = 1.0f) {
        emitPlaybackState(
            PlaybackStateSnapshot(
                status = PlaybackStatus.PLAYING,
                positionMs = positionMs,
                playbackSpeed = speed
            )
        )
    }

    fun pause(positionMs: Long = 0L) {
        emitPlaybackState(
            PlaybackStateSnapshot(
                status = PlaybackStatus.PAUSED,
                positionMs = positionMs,
                playbackSpeed = 0f
            )
        )
    }

    fun stop(positionMs: Long = 0L) {
        emitPlaybackState(
            PlaybackStateSnapshot(
                status = PlaybackStatus.STOPPED,
                positionMs = positionMs,
                playbackSpeed = 0f
            )
        )
    }
}

/**
 * Controllable [SystemPermissionHelper] for UI / cross-feature E2E tests.
 * Area D tests exercise the real [com.musictracker.app.tracking.AndroidSystemPermissionHelper] directly.
 */
class FakeSystemPermissionHelper(
    var notificationAccessGranted: Boolean = false,
    var ignoringBatteryOptimizations: Boolean = false,
    var installedApps: List<InstalledAppInfo> = emptyList()
) : SystemPermissionHelper {

    var openNotificationSettingsCalls: Int = 0
        private set
    var requestBatteryExemptionCalls: Int = 0
        private set
    var openBatterySettingsCalls: Int = 0
        private set

    override fun isNotificationAccessGranted(): Boolean = notificationAccessGranted

    override fun openNotificationListenerSettings(context: Context): Boolean {
        openNotificationSettingsCalls++
        return true
    }

    override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

    override fun requestBatteryOptimizationExemption(context: Context): Boolean {
        requestBatteryExemptionCalls++
        return true
    }

    override fun openBatteryOptimizationSettings(context: Context): Boolean {
        openBatterySettingsCalls++
        return true
    }

    override fun getInstalledLaunchableApps(): List<InstalledAppInfo> = installedApps
}
