package com.musictracker.app.ui.components.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassTheme

@Composable
fun EmptyStateView(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Rounded.LibraryMusic,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors

    StandardMaterialCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.labelPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
