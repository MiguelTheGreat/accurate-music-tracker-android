package com.musictracker.app.ui.components.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

@Immutable
data class LiquidGlassNavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val testTag: String
)

/**
 * Detached floating Liquid Glass capsule navigation bar.
 *
 * Condenses labels and height smoothly when [isMinimized] is true (e.g. scrolling down),
 * disabled automatically when Reduced Motion is active.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidGlassNavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    isMinimized: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val effectiveMinimized = isMinimized && !accessibility.reduceMotion

    val barHeight by animateDpAsState(
        targetValue = if (effectiveMinimized) {
            LiquidGlassMetrics.NavBarMinimizedHeight
        } else {
            LiquidGlassMetrics.NavBarHeight
        },
        label = "nav_bar_height"
    )

    LiquidGlassSurface(
        modifier = modifier.height(barHeight),
        variant = LiquidGlassVariant.Regular,
        shape = LiquidGlassShapes.Capsule,
        interactive = true,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item.id == selectedId
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                val activePillColor = if (selected) {
                    colors.accentBlue.copy(alpha = if (colors.isDark) 0.24f else 0.16f)
                } else {
                    Color.Transparent
                }
                val contentTint = if (selected) colors.accentBlue else colors.labelSecondary

                Box(
                    modifier = Modifier
                        .testTag(item.testTag)
                        .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        .clip(LiquidGlassShapes.Capsule)
                        .background(activePillColor)
                        .liquidBounce(pressed = isPressed)
                        .selectable(
                            selected = selected,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(item.id) }
                        )
                        .padding(horizontal = if (effectiveMinimized) 14.dp else 18.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
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
