package com.musictracker.app.ui.components.glass

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassOpticsTokens
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive Liquid Glass segmented control supporting both taps and Apple-style
 * press-and-drag sliding of the highlighted glass thumb across segments.
 *
 * - Tap any option to jump smoothly with spring physics.
 * - Press & drag horizontally anywhere on the control to lift the glass thumb, slide it
 *   continuously across options with subtle haptic ticks, and release to select.
 * - Honors Reduced Motion (no drag stretch or haptics), Reduced Transparency, and High Contrast.
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
    val view = LocalView.current
    val layoutDirection = LocalLayoutDirection.current
    val selectedIndex = options.indexOf(selectedOption).coerceAtLeast(0)
    val interactionSources = remember(options.size) {
        List(options.size) { MutableInteractionSource() }
    }
    val pressedStates = interactionSources.map { it.collectIsPressedAsState() }
    val pressedIndex = pressedStates.indexOfFirst { it.value }
    val anySegmentPressed = pressedIndex != -1

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragHoveredIndex by remember { mutableIntStateOf(selectedIndex) }

    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onOptionSelected)
    val currentReduceMotion by rememberUpdatedState(accessibility.reduceMotion)

    val targetIndexFloat = when {
        isDragging && !accessibility.reduceMotion -> dragPosition
        anySegmentPressed && !accessibility.reduceMotion -> pressedIndex.toFloat()
        else -> selectedIndex.toFloat()
    }

    val animatedIndex by animateFloatAsState(
        targetValue = targetIndexFloat,
        animationSpec = when {
            accessibility.reduceMotion -> tween(durationMillis = 120)
            isDragging -> spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
            else -> LiquidGlassMotion.fluidSpec(false)
        },
        label = "segmented_thumb_index"
    )

    val isLifted = (anySegmentPressed || isDragging) &&
        !accessibility.reduceMotion &&
        !accessibility.reduceTransparency
    val thumbScaleX by animateFloatAsState(
        targetValue = if (isLifted) 1.05f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "segmented_thumb_scale_x"
    )
    val thumbScaleY by animateFloatAsState(
        targetValue = if (isLifted) 1.04f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "segmented_thumb_scale_y"
    )

    val lensLightDirection = rememberLiquidGlassLightDirection(
        enabled = !accessibility.reduceTransparency && !accessibility.reduceMotion
    )

    val dragModifier = Modifier.pointerInput(options.size, layoutDirection) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            val trackPadPx = LiquidGlassShapes.ControlTrackPadding.toPx()

            fun pointerToPosition(pointerX: Float): Float {
                val count = currentOptions.size
                if (count <= 1) return 0f
                val innerWidth = (size.width - trackPadPx * 2f).coerceAtLeast(1f)
                val segWidth = innerWidth / count
                val rawX = (pointerX - trackPadPx).coerceIn(0f, innerWidth)
                val logicalX = if (isRtl) innerWidth - rawX else rawX
                return ((logicalX - segWidth * 0.5f) / segWidth)
                    .coerceIn(0f, (count - 1).toFloat())
            }

            fun pointerToIndex(pointerX: Float): Int =
                pointerToPosition(pointerX).roundToInt().coerceIn(0, (currentOptions.size - 1).coerceAtLeast(0))

            var dragStarted = false
            var lastHoveredIndex = pointerToIndex(down.position.x)

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                    if (!change.pressed) {
                        if (dragStarted) {
                            change.consume()
                            val finalIndex = pointerToIndex(change.position.x)
                            if (!currentReduceMotion) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            currentOptions.getOrNull(finalIndex)?.let(currentOnSelect)
                        }
                        break
                    }

                    val deltaX = abs(change.position.x - down.position.x)
                    val deltaY = abs(change.position.y - down.position.y)
                    if (!dragStarted && deltaX > touchSlop && deltaX >= deltaY) {
                        dragStarted = true
                        isDragging = true
                    }

                    if (dragStarted) {
                        change.consume()
                        val newPos = pointerToPosition(change.position.x)
                        val newIdx = newPos.roundToInt().coerceIn(0, (currentOptions.size - 1).coerceAtLeast(0))
                        dragPosition = newPos
                        dragHoveredIndex = newIdx
                        if (newIdx != lastHoveredIndex) {
                            lastHoveredIndex = newIdx
                            if (!currentReduceMotion) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                }
            } finally {
                isDragging = false
            }
        }
    }

    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(dragModifier),
        variant = LiquidGlassVariant.Regular,
        shape = LiquidGlassShapes.ControlTrack,
        interactive = true
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(LiquidGlassShapes.ControlTrackPadding)
        ) {
            val segmentWidth = maxWidth / options.size

            // Sliding Temporary Lift thumb
            val thumbFill = when {
                isLifted -> if (colors.isDark) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.86f)
                colors.isDark -> Color.White.copy(alpha = 0.13f)
                else -> colors.controlThumbSolid.copy(alpha = 0.86f)
            }
            val thumbBorderBrush = when {
                accessibility.highContrast -> Brush.verticalGradient(
                    listOf(colors.rimBorderHighContrast, colors.rimBorderHighContrast)
                )
                isLifted -> Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (colors.isDark) 0.42f else 0.94f),
                        Color.White.copy(alpha = if (colors.isDark) 0.14f else 0.20f)
                    )
                )
                else -> Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (colors.isDark) 0.26f else 0.90f),
                        Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.16f)
                    )
                )
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
                        val travelStretch = if (accessibility.reduceMotion) {
                            0f
                        } else {
                            abs(animatedIndex - targetIndexFloat).coerceIn(0f, 1f) * 0.10f
                        }
                        scaleX = thumbScaleX + travelStretch
                        scaleY = thumbScaleY - (travelStretch * 0.20f)
                    }
                    .shadow(
                        elevation = if (isLifted) 6.dp else 1.dp,
                        shape = LiquidGlassShapes.ControlThumb,
                        clip = false
                    )
                    .clip(LiquidGlassShapes.ControlThumb)
                    .background(thumbFill)
                    .border(
                        width = if (accessibility.highContrast) 1.5.dp else if (isLifted) 1.0.dp else 0.75.dp,
                        brush = thumbBorderBrush,
                        shape = LiquidGlassShapes.ControlThumb
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .liquidLensContent(
                        enabled = !accessibility.reduceTransparency && !accessibility.reduceMotion,
                        optics = LiquidGlassOpticsTokens.ThumbLens,
                        isDark = colors.isDark,
                        lightDirection = lensLightDirection
                    ) {
                        val travelDelta = abs(animatedIndex - targetIndexFloat)
                        if (!isLifted && travelDelta < 0.015f) return@liquidLensContent null

                        val segW = segmentWidth.toPx()
                        val travelStretch = travelDelta.coerceIn(0f, 1f) * 0.10f
                        LiquidLensGeometry(
                            left = animatedIndex * segW,
                            top = 0f,
                            width = segW,
                            height = size.height,
                            scaleX = thumbScaleX + travelStretch,
                            scaleY = thumbScaleY - (travelStretch * 0.20f),
                            magnificationBoost = if (isLifted) 1.03f else 1f
                        )
                    }
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = option == selectedOption
                    val isHighlighted = if (isDragging) index == dragHoveredIndex else isSelected
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
                                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (isHighlighted) colors.labelPrimary else colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
