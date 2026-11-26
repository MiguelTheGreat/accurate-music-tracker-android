package com.musictracker.app.ui.components.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Content-layer switch implementing Apple HIG's **Temporary Lift** exception:
 * - At rest, the `26.dp` thumb is solid white.
 * - While pressed, the thumb lifts (`scale(1.22, 1.12)`) and transforms into a translucent
 *   refractive Liquid Glass droplet with a bright specular rim.
 * - Exposes [Role.Switch] semantics via `Modifier.toggleable`.
 */
@Composable
fun LiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isLifted = isPressed && enabled && !accessibility.reduceMotion && !accessibility.reduceTransparency

    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accentGreen else colors.controlTrackFill,
        label = "switch_track_color"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        label = "switch_thumb_offset"
    )
    val thumbScaleX by animateFloatAsState(
        targetValue = if (isLifted) 1.22f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "switch_thumb_scale_x"
    )
    val thumbScaleY by animateFloatAsState(
        targetValue = if (isLifted) 1.12f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "switch_thumb_scale_y"
    )

    val trackBorderColor = if (accessibility.highContrast) {
        colors.rimBorderHighContrast
    } else {
        colors.standardCardBorder
    }
    val trackBorderWidth = if (accessibility.highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast
    } else {
        LiquidGlassMetrics.RimWidthStandard
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(trackBorderWidth, trackBorderColor, CircleShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val thumbFill = if (isLifted) Color.White.copy(alpha = 0.28f) else colors.controlThumbSolid
        val thumbRim = if (isLifted) {
            Brush.verticalGradient(listOf(colors.specularTopHighlight, colors.specularBottomHighlight))
        } else {
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.08f)))
        }

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(26.dp)
                .graphicsLayer {
                    scaleX = thumbScaleX
                    scaleY = thumbScaleY
                }
                .shadow(
                    elevation = if (isLifted) 8.dp else 3.dp,
                    shape = CircleShape,
                    clip = false
                )
                .clip(CircleShape)
                .background(thumbFill)
                .border(
                    width = if (isLifted) 1.25.dp else 0.5.dp,
                    brush = thumbRim,
                    shape = CircleShape
                )
        )
    }
}
