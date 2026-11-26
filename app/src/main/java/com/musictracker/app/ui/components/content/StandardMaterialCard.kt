package com.musictracker.app.ui.components.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Content-layer Standard Material card (strictly NOT Liquid Glass).
 *
 * Used for dense content surfaces: Today's Summary bento cards, Recent Listening rows,
 * Top Tracks / Top Artists groups, and Settings sections so underlying content stays crisp
 * and readable while scrolling beneath floating Liquid Glass chrome.
 */
@Composable
fun StandardMaterialCard(
    modifier: Modifier = Modifier,
    shape: Shape = LiquidGlassShapes.ContentGroup,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val borderWidth = if (accessibility.highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast
    } else {
        LiquidGlassMetrics.RimWidthStandard
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.standardCardFill)
            .border(width = borderWidth, color = colors.standardCardBorder, shape = shape)
            .padding(contentPadding),
        content = content
    )
}
