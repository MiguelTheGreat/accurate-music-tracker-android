package com.musictracker.app.ui.components.glass

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Liquid Glass sheet following Apple HIG geometry:
 * - Half-sheet is inset (`12.dp` horizontal margin) with `28.dp` corner radius so underlying
 *   content visibly peeks around it.
 * - When [isExpanded] is true, transitions to edge-to-edge higher-opacity appearance for focus.
 */
@Composable
fun LiquidGlassSheet(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val horizontalInset by animateDpAsState(
        targetValue = if (isExpanded) 0.dp else 12.dp,
        label = "sheet_horizontal_inset"
    )
    val sheetShape = if (isExpanded) LiquidGlassShapes.SheetExpanded else LiquidGlassShapes.SheetInset

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset)
    ) {
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(sheetShape)
                    .background(colors.regularFillReducedTransparency)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SheetGrabber()
                content()
            }
        } else {
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidMaterialize(),
                variant = LiquidGlassVariant.Regular,
                shape = sheetShape,
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
