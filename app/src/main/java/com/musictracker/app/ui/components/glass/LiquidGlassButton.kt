package com.musictracker.app.ui.components.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Capsule Liquid Glass button supporting [LiquidGlassVariant.Prominent] (`glassProminent` CTA)
 * and [LiquidGlassVariant.Regular] styles with gel bounce and touch-point illumination.
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: LiquidGlassVariant = LiquidGlassVariant.Prominent,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val foregroundColor = when (variant) {
        LiquidGlassVariant.Prominent -> colors.onProminent
        LiquidGlassVariant.Regular, LiquidGlassVariant.Clear -> colors.labelPrimary
    }

    LiquidGlassSurface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.48f)
            .liquidBounce(pressed = isPressed && enabled, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        variant = variant,
        shape = LiquidGlassShapes.Capsule,
        interactive = enabled,
        contentPadding = contentPadding
    ) {
        CompositionLocalProvider(LocalContentColor provides foregroundColor) {
            Row(
                modifier = Modifier.defaultMinSize(minHeight = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}
