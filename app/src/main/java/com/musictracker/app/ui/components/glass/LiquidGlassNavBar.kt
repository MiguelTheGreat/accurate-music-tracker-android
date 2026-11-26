package com.musictracker.app.ui.components.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class LiquidGlassNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val testTag: String
)

/**
 * Detached floating Liquid Glass capsule navigation bar with Apple iOS 26-style sliding
 * Liquid Glass lens:
 * - Glides fluidly with liquid stretch between tabs when switching.
 * - Swells/enlarges (`scale(1.12, 1.10)`) with a luminous specular glass rim while any tab
 *   is pressed and held, automatically respecting Reduced Motion and Reduced Transparency.
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
    val effectiveMinimized = isMinimized && !accessibility.reduceMotion

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val interactionSources = remember(items.size) {
        List(items.size) { MutableInteractionSource() }
    }
    val pressedStates = interactionSources.map { it.collectIsPressedAsState() }
    val pressedIndex = pressedStates.indexOfFirst { it.value }
    val isAnyPressed = pressedIndex != -1

    // While holding a tab, the liquid glass lens gravitates to the held tab and swells.
    val targetLensIndex = if (isAnyPressed && !accessibility.reduceMotion) pressedIndex else selectedIndex

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
    val tabGap = 4.dp

    val animatedLensIndex by animateFloatAsState(
        targetValue = targetLensIndex.toFloat(),
        animationSpec = if (accessibility.reduceMotion) {
            LiquidGlassMotion.ReducedMotionSpec
        } else {
            spring(dampingRatio = 0.72f, stiffness = 460f)
        },
        label = "nav_lens_index"
    )

    // Subtle horizontal liquid stretch while the lens travels between tabs.
    val travelStretch = if (accessibility.reduceMotion) {
        0f
    } else {
        abs(animatedLensIndex - targetLensIndex).coerceIn(0f, 1f) * 0.14f
    }

    val isLensSwollen = isAnyPressed && !accessibility.reduceMotion
    val lensScaleX by animateFloatAsState(
        targetValue = if (isLensSwollen) 1.12f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "nav_lens_scale_x"
    )
    val lensScaleY by animateFloatAsState(
        targetValue = if (isLensSwollen) 1.10f else 1f,
        animationSpec = LiquidGlassMotion.bounceSpec(accessibility.reduceMotion),
        label = "nav_lens_scale_y"
    )
    val lensGlowAlpha by animateFloatAsState(
        targetValue = if (isLensSwollen) 1f else 0f,
        animationSpec = LiquidGlassMotion.ReducedMotionSpec,
        label = "nav_lens_glow_alpha"
    )

    LiquidGlassSurface(
        modifier = modifier.height(barHeight),
        variant = LiquidGlassVariant.Regular,
        shape = LiquidGlassShapes.Capsule,
        interactive = true,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            // Sliding, swelling Apple-style Liquid Glass Lens pill
            val lensFillBrush = if (accessibility.reduceTransparency) {
                Brush.verticalGradient(
                    listOf(
                        colors.accentBlue.copy(alpha = if (colors.isDark) 0.34f else 0.22f),
                        colors.accentBlue.copy(alpha = if (colors.isDark) 0.28f else 0.18f)
                    )
                )
            } else {
                val topAlpha = (if (colors.isDark) 0.26f else 0.58f) + (0.18f * lensGlowAlpha)
                val bottomAlpha = (if (colors.isDark) 0.12f else 0.28f) + (0.10f * lensGlowAlpha)
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = topAlpha),
                        0.45f to colors.accentBlue.copy(
                            alpha = (if (colors.isDark) 0.22f else 0.15f) + (0.10f * lensGlowAlpha)
                        ),
                        1.0f to colors.accentBlue.copy(alpha = bottomAlpha)
                    )
                )
            }

            val lensBorderBrush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to colors.specularTopHighlight.copy(alpha = 0.55f + (0.40f * lensGlowAlpha)),
                    0.5f to colors.rimBorder.copy(alpha = 0.35f + (0.30f * lensGlowAlpha)),
                    1.0f to colors.specularBottomHighlight.copy(alpha = 0.40f + (0.35f * lensGlowAlpha))
                )
            )

            Box(
                modifier = Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .offset {
                        val stridePx = (tabWidth + tabGap).toPx()
                        IntOffset(
                            x = (animatedLensIndex * stridePx).roundToInt(),
                            y = 0
                        )
                    }
                    .graphicsLayer {
                        scaleX = lensScaleX + travelStretch
                        scaleY = lensScaleY - (travelStretch * 0.25f)
                    }
                    .shadow(
                        elevation = if (isLensSwollen) 10.dp else 3.dp,
                        shape = LiquidGlassShapes.Capsule,
                        ambientColor = colors.accentBlue.copy(alpha = 0.35f),
                        spotColor = colors.accentBlue.copy(alpha = 0.45f),
                        clip = false
                    )
                    .clip(LiquidGlassShapes.Capsule)
                    .background(lensFillBrush)
                    .border(
                        width = if (isLensSwollen) 1.35.dp else 0.85.dp,
                        brush = lensBorderBrush,
                        shape = LiquidGlassShapes.Capsule
                    )
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
                    val isHighlighted = selected || isPressed

                    val contentTint by animateColorAsState(
                        targetValue = if (isHighlighted) colors.accentBlue else colors.labelSecondary,
                        animationSpec = tween(durationMillis = 150),
                        label = "nav_item_tint_${item.id}"
                    )
                    val itemScale by animateFloatAsState(
                        targetValue = if (isPressed && !accessibility.reduceMotion) 1.07f else 1f,
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
