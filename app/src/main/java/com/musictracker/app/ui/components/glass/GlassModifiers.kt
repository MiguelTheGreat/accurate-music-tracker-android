package com.musictracker.app.ui.components.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Observes pointer coordinates in [PointerEventPass.Initial] WITHOUT consuming events so
 * buttons, switches, and tabs continue to receive clicks normally, while rendering a soft
 * radial light bloom tracking the touch point. Disabled automatically in Reduced Motion.
 */
fun Modifier.touchPointIllumination(
    enabled: Boolean = true,
    bloomRadius: Dp = LiquidGlassMetrics.TouchBloomRadius
): Modifier = composed {
    val accessibility = LiquidGlassTheme.accessibility
    val colors = LiquidGlassTheme.colors
    if (!enabled || accessibility.reduceMotion) return@composed this

    var touchOffset by remember { mutableStateOf<Offset?>(null) }
    var isTouching by remember { mutableStateOf(false) }

    val bloomAlpha by animateFloatAsState(
        targetValue = if (isTouching) 1f else 0f,
        animationSpec = LiquidGlassMotion.ReducedMotionSpec,
        label = "touch_bloom_alpha"
    )

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val first = event.changes.firstOrNull()
                    if (first != null && first.pressed) {
                        touchOffset = first.position
                        isTouching = true
                    } else {
                        isTouching = false
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()
            val center = touchOffset
            if (center != null && bloomAlpha > 0.01f) {
                val radiusPx = bloomRadius.toPx().coerceAtLeast(1f)
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to colors.touchBloomCore.copy(alpha = colors.touchBloomCore.alpha * bloomAlpha),
                            0.45f to colors.touchBloomMid.copy(alpha = colors.touchBloomMid.alpha * bloomAlpha),
                            0.75f to Color.Transparent
                        ),
                        center = center,
                        radius = radiusPx
                    )
                )
            }
        }
}

/**
 * Gel-like interactive flex & spring rebound on press (`scale(0.96, 1.02)`).
 * Disabled automatically when Reduced Motion is active.
 */
fun Modifier.liquidBounce(
    pressed: Boolean,
    enabled: Boolean = true
): Modifier = composed {
    val accessibility = LiquidGlassTheme.accessibility
    if (!enabled || accessibility.reduceMotion) return@composed this

    val scaleX by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = LiquidGlassMotion.SpringBounce,
        label = "liquid_bounce_x"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (pressed) 1.02f else 1f,
        animationSpec = LiquidGlassMotion.SpringBounce,
        label = "liquid_bounce_y"
    )

    this.graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }
}

/**
 * Materialization entrance animation (`scale 0.92 -> 1.0` + alpha ramp) instead of plain fade.
 * In Reduced Motion mode, performs a subtle 150ms opacity-only transition with no scale transform.
 */
fun Modifier.liquidMaterialize(visible: Boolean = true): Modifier = composed {
    val accessibility = LiquidGlassTheme.accessibility
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        appeared = visible
    }

    val targetAlpha = if (appeared) 1f else 0f
    val targetScale = if (appeared || accessibility.reduceMotion) 1f else 0.92f

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
        label = "liquid_materialize_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "liquid_materialize_scale"
    )

    this.graphicsLayer {
        this.alpha = alpha
        this.scaleX = scale
        this.scaleY = scale
    }
}

/**
 * Estimates relative WCAG luminance `[0f..1f]` of a color (used to trigger the official
 * 35% dark dimming scrim behind `Clear` Liquid Glass over bright media).
 */
fun estimateColorLuminance(color: Color): Float = color.luminance()
