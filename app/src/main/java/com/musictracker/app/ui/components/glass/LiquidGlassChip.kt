package com.musictracker.app.ui.components.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Chrome-layer Liquid Glass pill chip (use strictly in floating chrome toolbars, never inside
 * dense content lists or nested inside another Liquid Glass container).
 */
@Composable
fun LiquidGlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val colors = LiquidGlassTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val variant = if (selected) LiquidGlassVariant.Prominent else LiquidGlassVariant.Regular
    val contentTint = if (selected) colors.onProminent else colors.labelPrimary

    LiquidGlassSurface(
        modifier = modifier
            .liquidBounce(pressed = isPressed)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        variant = variant,
        shape = LiquidGlassShapes.Capsule,
        interactive = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentTint,
                maxLines = 1
            )
        }
    }
}
