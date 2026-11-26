package com.musictracker.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Mandatory accessibility accommodations for Liquid Glass:
 * 1. [reduceTransparency] — Disables backdrop blur and increases fill opacity (~0.94) for legibility.
 * 2. [highContrast] — Elevates border width (1.5dp) and contrast (0.85 alpha) and sharpens foregrounds.
 * 3. [reduceMotion] — Disables elastic spring bounce, touch illumination, and tab bar minimization.
 */
@Immutable
data class LiquidGlassAccessibilityState(
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false
)

val LocalLiquidGlassAccessibility = compositionLocalOf { LiquidGlassAccessibilityState() }

@Composable
fun rememberLiquidGlassAccessibilityState(
    reduceTransparency: Boolean = false,
    highContrastOverride: Boolean? = null,
    reduceMotionOverride: Boolean? = null
): LiquidGlassAccessibilityState {
    val context = LocalContext.current
    var systemHighContrast by remember(context) { mutableStateOf(isSystemHighContrast(context)) }
    var systemReduceMotion by remember(context) { mutableStateOf(isSystemReduceMotion(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemHighContrast = isSystemHighContrast(context)
        systemReduceMotion = isSystemReduceMotion(context)
    }

    return LiquidGlassAccessibilityState(
        reduceTransparency = reduceTransparency,
        highContrast = highContrastOverride ?: systemHighContrast,
        reduceMotion = reduceMotionOverride ?: systemReduceMotion
    )
}

internal fun isSystemHighContrast(context: Context): Boolean {
    val uiModeHighContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching {
            val uiModeManager = context.getSystemService(UiModeManager::class.java)
            (uiModeManager?.contrast ?: 0f) >= 0.5f
        }.getOrDefault(false)
    } else {
        false
    }

    val secureHighTextContrast = runCatching {
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)

    return uiModeHighContrast || secureHighTextContrast
}

internal fun isSystemReduceMotion(context: Context): Boolean {
    val animatorScale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)

    val transitionScale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
    }.getOrDefault(1f)

    return animatorScale == 0f || transitionScale == 0f
}
