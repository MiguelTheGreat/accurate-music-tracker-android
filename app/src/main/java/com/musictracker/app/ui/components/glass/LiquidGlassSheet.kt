package com.musictracker.app.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Floating Apple iOS 26 Liquid Glass sheet:
 * - Always renders as a true [LiquidGlassSurface] (AGSL rim refraction, hairline Fresnel border,
 *   light blur, and subtle sheen) with consistent inset geometry (`12.dp` horizontal margin,
 *   `28.dp` corners) — never snapping or settling to an opaque solid box when expanded.
 */
@Composable
fun LiquidGlassSheet(
    isExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            variant = LiquidGlassVariant.Regular,
            shape = LiquidGlassShapes.SheetInset,
            interactive = true,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SheetGrabber()
                content()
            }
        }
    }
}

@Composable
private fun SheetGrabber() {
    val colors = LiquidGlassTheme.colors
    Box(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .width(36.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.labelTertiary.copy(alpha = 0.55f))
    )
}
