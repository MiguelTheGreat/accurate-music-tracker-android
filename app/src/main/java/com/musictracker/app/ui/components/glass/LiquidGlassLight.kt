package com.musictracker.app.ui.components.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.sqrt

/** Default static light direction (toward the top-left), normalized. */
internal val DefaultLightDirection = Offset(-0.62f, -0.78f)

/**
 * Process-wide, ref-counted tilt light source driving the Liquid Glass specular angle.
 *
 * - One `TYPE_GAME_ROTATION_VECTOR` listener shared by every visible glass surface.
 * - Registered only while at least one surface is composed AND its lifecycle is STARTED;
 *   unregistered as soon as the app backgrounds or the last surface leaves composition.
 * - Throttled to ~15 Hz and only publishes when the direction moves noticeably, so the
 *   render effect is rebuilt rarely and idle surfaces never redraw.
 */
internal object LiquidGlassTiltLight : SensorEventListener {
    private const val MinIntervalMs = 66L
    private const val MinDelta = 0.03f

    private val _direction = mutableStateOf(DefaultLightDirection)
    val direction: State<Offset> get() = _direction

    private var refCount = 0
    private var sensorManager: SensorManager? = null
    private var lastPublishMs = 0L
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun acquire(context: Context) {
        refCount++
        if (refCount == 1) {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sm != null && sensor != null) {
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                sensorManager = sm
            }
        }
    }

    fun release() {
        if (refCount == 0) return
        refCount--
        if (refCount == 0) {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            _direction.value = DefaultLightDirection
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishMs < MinIntervalMs) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val pitch = orientation[1] // tilt forward/back
        val roll = orientation[2]  // tilt left/right
        // Light stays "above" the device in world space: tilting shifts it across the glass.
        var x = DefaultLightDirection.x - roll.coerceIn(-0.8f, 0.8f) * 0.9f
        var y = DefaultLightDirection.y - (pitch + 0.6f).coerceIn(-0.8f, 0.8f) * 0.6f
        val len = sqrt(x * x + y * y).coerceAtLeast(1e-3f)
        x /= len; y /= len
        val prev = _direction.value
        if (abs(prev.x - x) + abs(prev.y - y) < MinDelta) return
        lastPublishMs = now
        _direction.value = Offset(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Returns the current specular light direction. When [enabled] (not Reduced Motion, full
 * refraction tier) this subscribes to [LiquidGlassTiltLight] for as long as the caller is
 * composed and the host lifecycle is STARTED. Read the returned state only inside draw /
 * graphicsLayer lambdas to avoid recomposition.
 */
@Composable
internal fun rememberLiquidGlassLightDirection(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose { }
        var acquired = false
        fun sync(started: Boolean) {
            if (started && !acquired) { LiquidGlassTiltLight.acquire(context); acquired = true }
            if (!started && acquired) { LiquidGlassTiltLight.release(); acquired = false }
        }
        val observer = LifecycleEventObserver { owner, _ ->
            sync(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sync(false)
        }
    }
    return if (enabled) LiquidGlassTiltLight.direction else StaticLight
}

private val StaticLight: State<Offset> = mutableStateOf(DefaultLightDirection)
