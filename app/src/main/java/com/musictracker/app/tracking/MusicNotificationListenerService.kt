package com.musictracker.app.tracking

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.musictracker.app.MusicTrackerApplication

internal interface MediaSessionGateway {
    fun addOnActiveSessionsChangedListener(
        listener: MediaSessionManager.OnActiveSessionsChangedListener,
        notificationListener: ComponentName,
        handler: Handler
    )

    fun removeOnActiveSessionsChangedListener(
        listener: MediaSessionManager.OnActiveSessionsChangedListener
    )

    fun getActiveSessions(notificationListener: ComponentName): List<MediaController>
}

private class AndroidMediaSessionGateway(
    private val manager: MediaSessionManager
) : MediaSessionGateway {
    override fun addOnActiveSessionsChangedListener(
        listener: MediaSessionManager.OnActiveSessionsChangedListener,
        notificationListener: ComponentName,
        handler: Handler
    ) {
        manager.addOnActiveSessionsChangedListener(listener, notificationListener, handler)
    }

    override fun removeOnActiveSessionsChangedListener(
        listener: MediaSessionManager.OnActiveSessionsChangedListener
    ) {
        manager.removeOnActiveSessionsChangedListener(listener)
    }

    override fun getActiveSessions(notificationListener: ComponentName): List<MediaController> =
        manager.getActiveSessions(notificationListener)
}

class MusicNotificationListenerService : NotificationListenerService() {

    internal var coordinatorOverride: MediaSessionCoordinator? = null
    internal var mediaSessionGatewayOverride: MediaSessionGateway? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val componentName: ComponentName by lazy {
        ComponentName(this, MusicNotificationListenerService::class.java)
    }

    private var activeGateway: MediaSessionGateway? = null
    private var isSessionListenerRegistered: Boolean = false

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            resolveCoordinator()?.onAndroidSessionsChanged(controllers, mainHandler)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val coordinator = resolveCoordinator()
        val gateway = resolveMediaSessionGateway()
        if (gateway == null) {
            coordinator?.setListenerConnected(false)
            return
        }

        try {
            unregisterSessionListener()

            gateway.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                componentName,
                mainHandler
            )
            activeGateway = gateway
            isSessionListenerRegistered = true

            val activeSessions = gateway.getActiveSessions(componentName)
            coordinator?.setListenerConnected(true)
            coordinator?.start()
            coordinator?.onAndroidSessionsChanged(activeSessions, mainHandler)
        } catch (e: SecurityException) {
            unregisterSessionListener()
            coordinator?.setListenerConnected(false)
            coordinator?.flushAndStop()
            Log.w(TAG, "Notification listener access denied while registering MediaSessionManager", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterSessionListener()
        resolveCoordinator()?.let { coordinator ->
            coordinator.setListenerConnected(false)
            coordinator.flushAndStop()
        }
        try {
            requestRebind(componentName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request NotificationListenerService rebind", e)
        }
    }

    override fun onDestroy() {
        unregisterSessionListener()
        resolveCoordinator()?.let { coordinator ->
            coordinator.setListenerConnected(false)
            coordinator.flushAndStop()
        }
        super.onDestroy()
    }

    private fun unregisterSessionListener() {
        if (isSessionListenerRegistered) {
            runCatching {
                activeGateway?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            }
            isSessionListenerRegistered = false
        }
        activeGateway = null
    }

    private fun resolveCoordinator(): MediaSessionCoordinator? =
        coordinatorOverride
            ?: (applicationContext as? MusicTrackerApplication)?.appContainer?.mediaSessionCoordinator

    private fun resolveMediaSessionGateway(): MediaSessionGateway? {
        mediaSessionGatewayOverride?.let { return it }
        val manager = getSystemService(MediaSessionManager::class.java) ?: return null
        return AndroidMediaSessionGateway(manager)
    }

    companion object {
        private const val TAG = "MusicNotifListenerSvc"
    }
}
