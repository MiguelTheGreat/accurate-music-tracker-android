package com.musictracker.app.ui.components.glass

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val NavBarHorizontalPadding = 8.dp
private val NavBarVerticalPadding = 6.dp
private val NavBarTabGap = 4.dp

@Immutable
data class LiquidGlassNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val testTag: String
)

/**
 * Pure helper mapping a horizontal pointer coordinate [pointerX] (in nav bar coordinates) to a
 * continuous logical tab position in `[0f, (itemCount - 1).toFloat()]`, accounting for horizontal
 * padding, tab width, inter-tab gap, and RTL layout direction.
 */
internal fun calculateNavBarDragPosition(
    pointerX: Float,
    tabWidthPx: Float,
    tabGapPx: Float,
    horizontalPaddingPx: Float,
    itemCount: Int,
    isRtl: Boolean
): Float {
    if (itemCount <= 1 || tabWidthPx <= 0f) return 0f
    val safeGapPx = tabGapPx.coerceAtLeast(0f)
    val stridePx = tabWidthPx + safeGapPx
    if (stridePx <= 0f) return 0f
    val contentWidthPx = itemCount * tabWidthPx + (itemCount - 1) * safeGapPx
    val contentX = if (isRtl) {
        (horizontalPaddingPx + contentWidthPx) - pointerX
    } else {
        pointerX - horizontalPaddingPx
    }
    return ((contentX - tabWidthPx / 2f) / stridePx).coerceIn(0f, (itemCount - 1).toFloat())
}

/**
 * Pure helper mapping a horizontal pointer coordinate [pointerX] (in nav bar coordinates) to the
 * nearest discrete tab index in `0 until itemCount`, accounting for RTL layout direction.
 */
internal fun calculateNavBarDragIndex(
    pointerX: Float,
    tabWidthPx: Float,
    tabGapPx: Float,
    horizontalPaddingPx: Float,
    itemCount: Int,
    isRtl: Boolean
): Int {
    if (itemCount <= 1 || tabWidthPx <= 0f) return 0
    return calculateNavBarDragPosition(
        pointerX = pointerX,
        tabWidthPx = tabWidthPx,
        tabGapPx = tabGapPx,
        horizontalPaddingPx = horizontalPaddingPx,
        itemCount = itemCount,
        isRtl = isRtl
    ).roundToInt().coerceIn(0, itemCount - 1)
}

/**
 * Detached floating Liquid Glass capsule navigation bar with Apple iOS 26-style sliding
 * Liquid Glass lens:
 * - Supports both per-tab taps and bar-level horizontal press-and-drag gestures that smoothly
 *   glide the swollen Liquid Glass lens (`scale(1.12, 1.10)` + dynamic stretch + luminous specular
 *   rim) across tab icons and switch to the tab under the finger on release.
 * - Fires subtle tactile ticks when crossing into a new tab and when snapping into place on
 *   release, strictly skipped when Reduced Motion is enabled.
 * - Preserves per-tab `Role.Tab` semantics, `TestTags`, scroll minimization, and Reduced Motion /
 *   Reduced Transparency accessibility modes.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidGlassNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    isMinimized: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val effectiveMinimized = isMinimized && !accessibility.reduceMotion

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val interactionSources = remember(items.size) {
        List(items.size) { MutableInteractionSource() }
    }
    val pressedStates = interactionSources.map { it.collectIsPressedAsState() }
    val pressedIndex = pressedStates.indexOfFirst { it.value }
    val isAnyPressed = pressedIndex != -1

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragHoveredIndex by remember { mutableIntStateOf(selectedIndex) }

    val barHeight by animateDpAsState(
        targetValue = if (effectiveMinimized) {
            LiquidGlassMetrics.NavBarMinimizedHeight
        } else {
            LiquidGlassMetrics.NavBarHeight
        },
        label = "nav_bar_height"
    )

    val tabWidth by animateDpAsState(
        targetValue = if (effectiveMinimized) 64.dp else 88.dp,
        label = "nav_tab_width"
    )
    val tabGap = NavBarTabGap

    // Keep latest values accessible inside long-lived pointerInput without restarting mid-gesture.
    val currentItems by rememberUpdatedState(items)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentReduceMotion by rememberUpdatedState(accessibility.reduceMotion)
    val currentTabWidth by rememberUpdatedState(tabWidth)

    // While dragging or holding a tab, the liquid glass lens tracks the finger / held tab and swells.
    val targetLensFloat = when {
        isDragging && !accessibility.reduceMotion -> dragPosition
        isDragging -> dragHoveredIndex.toFloat()
        isAnyPressed && !accessibility.reduceMotion -> pressedIndex.toFloat()
        else -> selectedIndex.toFloat()
    }

    val animatedLensIndexState = animateFloatAsState(
        targetValue = targetLensFloat,
        animationSpec = if (accessibility.reduceMotion) {
            LiquidGlassMotion.ReducedMotionSpec
        } else {
            spring(dampingRatio = 0.72f, stiffness = 460f)
        },
        label = "nav_lens_index"
    )

    val isLensSwollen = (isAnyPressed || isDragging) && !accessibility.reduceMotion
    val lensScaleXState = animateFloatAsState(
        targetValue = if (isLensSwollen) 1.12f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "nav_lens_scale_x"
    )
    val lensScaleYState = animateFloatAsState(
        targetValue = if (isLensSwollen) 1.10f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "nav_lens_scale_y"
    )
    val lensGlowAlphaState = animateFloatAsState(
        targetValue = if (isLensSwollen) 1f else 0f,
        animationSpec = LiquidGlassMotion.ReducedMotionSpec,
        label = "nav_lens_glow_alpha"
    )

    val barDragModifier = Modifier.pointerInput(items.size, layoutDirection) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            val horizontalPaddingPx = NavBarHorizontalPadding.toPx()
            val tabGapPx = tabGap.toPx()

            var dragStarted = false
            var lastHoveredIndex = calculateNavBarDragIndex(
                pointerX = down.position.x,
                tabWidthPx = currentTabWidth.toPx(),
                tabGapPx = tabGapPx,
                horizontalPaddingPx = horizontalPaddingPx,
                itemCount = currentItems.size,
                isRtl = isRtl
            )

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                    if (!change.pressed) {
                        if (dragStarted) {
                            change.consume()
                            val finalIndex = calculateNavBarDragIndex(
                                pointerX = change.position.x,
                                tabWidthPx = currentTabWidth.toPx(),
                                tabGapPx = tabGapPx,
                                horizontalPaddingPx = horizontalPaddingPx,
                                itemCount = currentItems.size,
                                isRtl = isRtl
                            )
                            if (!currentReduceMotion) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            currentItems.getOrNull(finalIndex)?.let { targetItem ->
                                currentOnSelect(targetItem.id)
                            }
                        }
                        break
                    }

                    val deltaX = abs(change.position.x - down.position.x)
                    if (!dragStarted && deltaX > touchSlop) {
                        dragStarted = true
                        isDragging = true
                    }

                    if (dragStarted) {
                        change.consume()
                        val tabWidthPx = currentTabWidth.toPx()
                        val newPosition = calculateNavBarDragPosition(
                            pointerX = change.position.x,
                            tabWidthPx = tabWidthPx,
                            tabGapPx = tabGapPx,
                            horizontalPaddingPx = horizontalPaddingPx,
                            itemCount = currentItems.size,
                            isRtl = isRtl
                        )
                        val newIndex = calculateNavBarDragIndex(
                            pointerX = change.position.x,
                            tabWidthPx = tabWidthPx,
                            tabGapPx = tabGapPx,
                            horizontalPaddingPx = horizontalPaddingPx,
                            itemCount = currentItems.size,
                            isRtl = isRtl
                        )
                        dragPosition = newPosition
                        dragHoveredIndex = newIndex
                        if (newIndex != lastHoveredIndex) {
                            lastHoveredIndex = newIndex
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
            .height(barHeight)
            .then(barDragModifier),
        variant = LiquidGlassVariant.Regular,
        shape = LiquidGlassShapes.Capsule,
        interactive = true,
        contentPadding = PaddingValues(
            horizontal = NavBarHorizontalPadding,
            vertical = NavBarVerticalPadding
        )
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            // Sliding, swelling Apple-style Liquid Glass Lens pill.
            // Reads animated states strictly in layout/layer/draw phases to avoid per-frame recomposition.
            Box(
                modifier = Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .offset {
                        val stridePx = (tabWidth + tabGap).toPx()
                        IntOffset(
                            x = (animatedLensIndexState.value * stridePx).roundToInt(),
                            y = 0
                        )
                    }
                    .graphicsLayer {
                        val travelStretch = if (accessibility.reduceMotion) {
                            0f
                        } else {
                            abs(animatedLensIndexState.value - targetLensFloat)
                                .coerceIn(0f, 1f) * 0.14f
                        }
                        scaleX = lensScaleXState.value + travelStretch
                        scaleY = lensScaleYState.value - (travelStretch * 0.25f)
                    }
                    .shadow(
                        elevation = if (isLensSwollen) 10.dp else 3.dp,
                        shape = LiquidGlassShapes.Capsule,
                        ambientColor = colors.accentBlue.copy(alpha = if (colors.isDark) 0.35f else 0.20f),
                        spotColor = colors.accentBlue.copy(alpha = if (colors.isDark) 0.45f else 0.26f),
                        clip = false
                    )
                    .clip(LiquidGlassShapes.Capsule)
                    .drawBehind {
                        val glowAlpha = lensGlowAlphaState.value
                        val radius = size.height / 2f
                        val cornerRadius = CornerRadius(radius, radius)

                        val lensFillBrush = if (accessibility.reduceTransparency) {
                            Brush.verticalGradient(
                                listOf(
                                    colors.accentBlue.copy(alpha = if (colors.isDark) 0.34f else 0.22f),
                                    colors.accentBlue.copy(alpha = if (colors.isDark) 0.28f else 0.18f)
                                )
                            )
                        } else if (!colors.isDark) {
                            // Light Mode: cool-white top caustic -> soft accentBlue glass tint -> bottom reflection
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFFF8FBFF).copy(
                                        alpha = (0.85f + 0.12f * glowAlpha).coerceAtMost(0.98f)
                                    ),
                                    0.46f to colors.accentBlue.copy(
                                        alpha = (0.18f + 0.10f * glowAlpha).coerceAtMost(1f)
                                    ),
                                    1.0f to colors.accentBlue.copy(
                                        alpha = (0.10f + 0.08f * glowAlpha).coerceAtMost(1f)
                                    )
                                )
                            )
                        } else {
                            // Dark Mode: luminous top catchlight -> vibrant glass tint -> bottom bounce
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(
                                        alpha = (0.28f + 0.20f * glowAlpha).coerceAtMost(1f)
                                    ),
                                    0.45f to colors.accentBlue.copy(
                                        alpha = (0.24f + 0.12f * glowAlpha).coerceAtMost(1f)
                                    ),
                                    1.0f to colors.accentBlue.copy(
                                        alpha = (0.14f + 0.10f * glowAlpha).coerceAtMost(1f)
                                    )
                                )
                            )
                        }

                        drawRoundRect(
                            brush = lensFillBrush,
                            cornerRadius = cornerRadius
                        )

                        // Inner caustic crescent / arc for convex refractive lens depth
                        if (!accessibility.reduceTransparency && !accessibility.highContrast) {
                            val innerInsetPx = 1.5.dp.toPx()
                            val innerStrokePx = 1.2.dp.toPx()
                            val innerWidth = size.width - (innerInsetPx * 2f)
                            val innerHeight = size.height - (innerInsetPx * 2f)
                            if (innerWidth > 2f && innerHeight > 2f) {
                                val innerRadius = (radius - innerInsetPx).coerceAtLeast(0f)
                                val innerCausticBrush = if (!colors.isDark) {
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.White.copy(alpha = 0.92f),
                                            0.38f to Color.White.copy(alpha = 0.18f),
                                            0.72f to Color.Transparent,
                                            1.0f to colors.accentBlue.copy(
                                                alpha = (0.20f + 0.14f * glowAlpha).coerceAtMost(1f)
                                            )
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.White.copy(
                                                alpha = (0.44f + 0.30f * glowAlpha).coerceAtMost(1f)
                                            ),
                                            0.34f to Color(0xFF9CD4FF).copy(
                                                alpha = (0.12f + 0.12f * glowAlpha).coerceAtMost(1f)
                                            ),
                                            0.72f to Color.Transparent,
                                            1.0f to colors.accentBlue.copy(
                                                alpha = (0.22f + 0.24f * glowAlpha).coerceAtMost(1f)
                                            )
                                        )
                                    )
                                }
                                drawRoundRect(
                                    brush = innerCausticBrush,
                                    topLeft = Offset(innerInsetPx, innerInsetPx),
                                    size = Size(innerWidth, innerHeight),
                                    cornerRadius = CornerRadius(innerRadius, innerRadius),
                                    style = Stroke(width = innerStrokePx)
                                )
                            }
                        }

                        val strokePx = if (accessibility.highContrast) {
                            LiquidGlassMetrics.RimWidthHighContrast.toPx()
                        } else {
                            (if (isLensSwollen) 1.35.dp else 0.95.dp).toPx()
                        }
                        val halfStroke = strokePx / 2f
                        val insetRadius = (radius - halfStroke).coerceAtLeast(0f)
                        val lensBorderBrush = when {
                            accessibility.highContrast -> {
                                Brush.verticalGradient(
                                    listOf(colors.rimBorderHighContrast, colors.rimBorderHighContrast)
                                )
                            }

                            !colors.isDark -> {
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.White.copy(alpha = 0.95f),
                                        0.45f to Color(0xFF1E3A5F).copy(
                                            alpha = (0.24f + 0.14f * glowAlpha).coerceAtMost(1f)
                                        ),
                                        1.0f to colors.accentBlue.copy(
                                            alpha = (0.28f + 0.16f * glowAlpha).coerceAtMost(1f)
                                        )
                                    )
                                )
                            }

                            else -> {
                                // Subtle chromatic top/bottom rim glint when swollen/dragging in Dark Mode
                                val topRim = lerp(
                                    colors.specularTopHighlight,
                                    Color(0xFFA8E6FF),
                                    0.35f * glowAlpha
                                ).copy(alpha = (0.62f + 0.34f * glowAlpha).coerceAtMost(1f))
                                val bottomRim = lerp(
                                    colors.specularBottomHighlight,
                                    Color(0xFFC4B5FD),
                                    0.40f * glowAlpha
                                ).copy(alpha = (0.44f + 0.36f * glowAlpha).coerceAtMost(1f))
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to topRim,
                                        0.5f to colors.rimBorder.copy(
                                            alpha = (0.38f + 0.28f * glowAlpha).coerceAtMost(1f)
                                        ),
                                        1.0f to bottomRim
                                    )
                                )
                            }
                        }

                        drawRoundRect(
                            brush = lensBorderBrush,
                            topLeft = Offset(halfStroke, halfStroke),
                            size = Size(
                                width = (size.width - strokePx).coerceAtLeast(0f),
                                height = (size.height - strokePx).coerceAtLeast(0f)
                            ),
                            cornerRadius = CornerRadius(insetRadius, insetRadius),
                            style = Stroke(width = strokePx)
                        )
                    }
            )

            // Foreground tab items
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(tabGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected = item.id == selectedId
                    val interactionSource = interactionSources[index]
                    val isPressed = pressedStates[index].value
                    val isHoveredByDrag = isDragging && index == dragHoveredIndex
                    val isHighlighted = if (isDragging) isHoveredByDrag else (selected || isPressed)
                    val isElevated = isPressed || isHoveredByDrag

                    val contentTint by animateColorAsState(
                        targetValue = if (isHighlighted) colors.accentBlue else colors.labelSecondary,
                        animationSpec = tween(durationMillis = 150),
                        label = "nav_item_tint_${item.id}"
                    )
                    val itemScale by animateFloatAsState(
                        targetValue = if (isElevated && !accessibility.reduceMotion) 1.07f else 1f,
                        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
                        label = "nav_item_scale_${item.id}"
                    )

                    Box(
                        modifier = Modifier
                            .testTag(item.testTag)
                            .width(tabWidth)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(LiquidGlassShapes.Capsule)
                            .selectable(
                                selected = selected,
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(item.id) }
                            )
                            .graphicsLayer {
                                scaleX = itemScale
                                scaleY = itemScale
                            }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isHighlighted) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                                tint = contentTint,
                                modifier = Modifier.size(22.dp)
                            )
                            AnimatedVisibility(
                                visible = !effectiveMinimized,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared [NestedScrollConnection] that minimizes the floating navigation capsule on downward
 * scrolls and expands it back on upward scrolls.
 */
@Composable
fun rememberNavBarScrollConnection(
    onMinimizedChange: (Boolean) -> Unit
): NestedScrollConnection = remember(onMinimizedChange) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -12f) {
                onMinimizedChange(true)
            } else if (available.y > 12f) {
                onMinimizedChange(false)
            }
            return Offset.Zero
        }
    }
}
