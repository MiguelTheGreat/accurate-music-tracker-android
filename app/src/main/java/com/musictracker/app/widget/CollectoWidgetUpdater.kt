package com.musictracker.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Event-driven, coalesced widget refresh coordinator for [CollectoGlanceWidget].
 *
 * - Zero periodic polling timers.
 * - Coalesces rapid playback/DB state changes with a short debounce (~300ms).
 * - Checks whether any Collecto widget instance is actually pinned before calling `updateAll`
 *   so there is zero widget work when no widget is placed on the home screen.
 * - Wrapped in `runCatching` so Robolectric/JVM environments without AppWidgetManager never fail.
 */
class CollectoWidgetUpdater(
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var pendingJob: Job? = null

    fun requestUpdate() {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = scope.launch(ioDispatcher) {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                runCatching {
                    val manager = GlanceAppWidgetManager(appContext)
                    val ids = manager.getGlanceIds(CollectoGlanceWidget::class.java)
                    if (ids.isNotEmpty()) {
                        CollectoGlanceWidget().updateAll(appContext)
                    }
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
    }
}
