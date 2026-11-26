package com.musictracker.app.ui.components.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlin.math.roundToInt

/**
 * Content-layer segmented control implementing Apple HIG's **Temporary Lift** exception:
 * - Track sits in the content layer using Standard Material fill.
 * - Selected thumb rests solid/opaque with concentric corners (`ControlThumb = 11.dp` inside
 *   `ControlTrack = 14.dp` with `3.dp` padding) and transforms into a translucent refractive
 *   Liquid Glass droplet (`scale(1.06, 1.04)` + specular rim) only while actively pressed.
 */
@Composable
fun <T> LiquidGlassSegmentedControl(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    testTagProvider: ((T) -> String)? = null,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return

    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val selectedIndex = options.indexOf(selectedOption).coerceAtLeast(0)
    val interactionSources = remember(options.size) {
        List(options.size) { MutableInteractionSource() }
    }
    val pressedStates = interactionSources.map { it.collectIsPressedAsState() }
    val anySegmentPressed = pressedStates.any { it.value }

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
        label = "segmented_thumb_index"
    )

    val isLifted = anySegmentPressed && !accessibility.reduceMotion && !accessibility.reduceTransparency
    val thumbScaleX by animateFloatAsState(
        targetValue = if (isLifted) 1.06f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "segmented_thumb_scale_x"
    )
    val thumbScaleY by animateFloatAsState(
        targetValue = if (isLifted) 1.04f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "segmented_thumb_scale_y"
    )

    val trackBorderColor = if (accessibility.highContrast) {
        colors.standardCardBorderHighContrast
    } else {
        colors.standardCardBorder
    }
    val trackBorderWidth = if (accessibility.highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast
    } else {
        LiquidGlassMetrics.RimWidthStandard
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(LiquidGlassShapes.ControlTrack)
            .background(colors.controlTrackFill)
            .border(trackBorderWidth, trackBorderColor, LiquidGlassShapes.ControlTrack)
            .padding(LiquidGlassShapes.ControlTrackPadding)
    ) {
        val segmentWidth = maxWidth / options.size

        // Sliding Temporary Lift thumb
        val thumbFill = when {
            isLifted -> if (colors.isDark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.72f)
            colors.isDark -> colors.standardCardElevatedFill
            else -> colors.controlThumbSolid
        }
        val thumbBorderBrush = when {
            accessibility.highContrast -> Brush.verticalGradient(
                listOf(colors.rimBorderHighContrast, colors.rimBorderHighContrast)
            )
            isLifted -> Brush.verticalGradient(
                listOf(colors.specularTopHighlight, colors.rimBorder, colors.specularBottomHighlight)
            )
            else -> Brush.verticalGradient(listOf(colors.standardCardBorder, colors.standardCardBorder))
        }

        Box(
            modifier = Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .offset {
                    IntOffset(
                        x = (animatedIndex * segmentWidth.toPx()).roundToInt(),
                        y = 0
                    )
                }
                .graphicsLayer {
                    scaleX = thumbScaleX
                    scaleY = thumbScaleY
                }
                .shadow(
                    elevation = if (isLifted) 8.dp else 2.dp,
                    shape = LiquidGlassShapes.ControlThumb,
                    clip = false
                )
                .clip(LiquidGlassShapes.ControlThumb)
                .background(thumbFill)
                .border(
                    width = if (isLifted || accessibility.highContrast) 1.25.dp else 0.75.dp,
                    brush = thumbBorderBrush,
                    shape = LiquidGlassShapes.ControlThumb
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selectedOption
                val interactionSource = interactionSources[index]

                val tagModifier = testTagProvider?.invoke(option)?.let { Modifier.testTag(it) } ?: Modifier

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(tagModifier)
                        .clip(LiquidGlassShapes.ControlThumb)
                        .selectable(
                            selected = isSelected,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onOptionSelected(option) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(option),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSelected) colors.labelPrimary else colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
