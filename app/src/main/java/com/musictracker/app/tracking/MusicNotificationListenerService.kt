package com.musictracker.app.tracking

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    internal var callbackHandlerOverride: Handler? = null

    private val handlerLock = Any()
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    internal val activeCallbackThread: HandlerThread?
        get() = synchronized(handlerLock) { callbackThread }

    private val componentName: ComponentName by lazy {
        ComponentName(this, MusicNotificationListenerService::class.java)
    }

    private var activeGateway: MediaSessionGateway? = null
    private var isSessionListenerRegistered: Boolean = false
    private var isListenerActive: Boolean = false
    private var isDestroyed: Boolean = false

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            synchronized(handlerLock) {
                if (isDestroyed || !isListenerActive) return@OnActiveSessionsChangedListener
                val handler = callbackHandlerOverride ?: callbackHandler ?: return@OnActiveSessionsChangedListener
                resolveCoordinator()?.onAndroidSessionsChanged(controllers, handler)
            }
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                migrateNotificationFilter(0, emptyList())
            }
        }
        val coordinator = resolveCoordinator()
        val gateway = resolveMediaSessionGateway()
        if (gateway == null) {
            synchronized(handlerLock) {
                isListenerActive = false
            }
            coordinator?.setListenerConnected(false)
            return
        }

        synchronized(handlerLock) {
            if (isDestroyed) return
            val handler = ensureCallbackHandler() ?: return
            try {
                unregisterSessionListenerLocked()

                gateway.addOnActiveSessionsChangedListener(
                    activeSessionsChangedListener,
                    componentName,
                    handler
                )
                activeGateway = gateway
                isSessionListenerRegistered = true
                isListenerActive = true
                activeInstance = this

                val activeSessions = gateway.getActiveSessions(componentName)
                coordinator?.setListenerConnected(true)
                coordinator?.start()
                coordinator?.onAndroidSessionsChanged(activeSessions, handler)
            } catch (e: SecurityException) {
                isListenerActive = false
                if (activeInstance === this) activeInstance = null
                unregisterSessionListenerLocked()
                coordinator?.setListenerConnected(false)
                coordinator?.flushAndStop()
                Log.w(TAG, "Notification listener access denied while registering MediaSessionManager", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return
        val coordinator = resolveCoordinator() ?: return
        if (!coordinator.isPackageTracked(pkg)) return
        syncActiveSessionsInternal()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        synchronized(handlerLock) {
            isListenerActive = false
            if (activeInstance === this) activeInstance = null
            unregisterSessionListenerLocked()
            resolveCoordinator()?.let { coordinator ->
                coordinator.setListenerConnected(false)
                coordinator.flushAndStop()
            }
        }
        try {
            requestRebind(componentName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request NotificationListenerService rebind", e)
        }
    }

    override fun onDestroy() {
        val threadToQuit = synchronized(handlerLock) {
            isDestroyed = true
            isListenerActive = false
            if (activeInstance === this) activeInstance = null
            unregisterSessionListenerLocked()
            resolveCoordinator()?.let { coordinator ->
                coordinator.setListenerConnected(false)
                coordinator.flushAndStop()
            }
            val current = callbackThread
            callbackThread = null
            callbackHandler = null
            current
        }
        if (threadToQuit != null) {
            runCatching { threadToQuit.quitSafely() }
        }
        super.onDestroy()
    }

    private fun syncActiveSessionsInternal() {
        synchronized(handlerLock) {
            if (isDestroyed || !isListenerActive) return
            val gateway = activeGateway ?: return
            val handler = callbackHandlerOverride ?: callbackHandler ?: return
            val coordinator = resolveCoordinator() ?: return
            handler.post {
                val sessions = synchronized(handlerLock) {
                    if (isDestroyed || !isListenerActive) return@post
                    runCatching { gateway.getActiveSessions(componentName) }.getOrNull()
                } ?: return@post
                coordinator.onAndroidSessionsChanged(sessions, handler)
            }
        }
    }

    private fun ensureCallbackHandler(): Handler? {
        synchronized(handlerLock) {
            if (isDestroyed) return null
            callbackHandlerOverride?.let { return it }
            val existingThread = callbackThread
            val existingHandler = callbackHandler
            if (existingThread != null && existingThread.isAlive && existingHandler != null) {
                return existingHandler
            }
            val thread = HandlerThread(CALLBACK_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).apply {
                start()
            }
            val handler = Handler(thread.looper)
            callbackThread = thread
            callbackHandler = handler
            return handler
        }
    }

    private fun unregisterSessionListenerLocked() {
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
        private const val CALLBACK_THREAD_NAME = "CollectoMediaCallbacks"

        @Volatile
        private var activeInstance: MusicNotificationListenerService? = null

        fun requestActiveSessionSync() {
            activeInstance?.syncActiveSessionsInternal()
        }
    }
}
