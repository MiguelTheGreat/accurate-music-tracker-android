package com.musictracker.app.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Floating chrome-layer Regular Liquid Glass banner for non-intrusive status prompts and
 * export feedback. Inline CTA uses a solid vibrant capsule (strictly avoiding glass-on-glass).
 */
@Composable
fun LiquidGlassBanner(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    actionTestTag: String? = null,
    dismissContentDescription: String = "Dismiss",
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val iconTint = if (isError) colors.accentRed else colors.accentBlue
    val icon = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.NotificationsActive

    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .liquidMaterialize(),
        variant = LiquidGlassVariant.Regular,
        shape = LiquidGlassShapes.Banner,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (actionLabel != null && onActionClick != null) {
                val tagModifier = actionTestTag?.let { Modifier.testTag(it) } ?: Modifier
                Box(
                    modifier = tagModifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(LiquidGlassShapes.Capsule)
                        .background(if (isError) colors.accentRed else colors.accentBlue)
                        .clickable(role = Role.Button, onClick = onActionClick)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onProminent,
                        maxLines = 1
                    )
                }
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = dismissContentDescription,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
