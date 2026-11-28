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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassOpticsTokens
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Floating Apple iOS 26 Liquid Glass sheet:
 * - Connects to [LocalLiquidGlassSheetBackdrop] so `ModalBottomSheet` (which renders in a
 *   separate Dialog window) runs the exact same real AGSL blur + refraction pipeline as the
 *   bottom dock without self-blur recursion or OEM `FLAG_BLUR_BEHIND` quirks.
 */
@Composable
fun LiquidGlassSheet(
    isExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetBackdrop = LocalLiquidGlassBackdrop.current ?: LocalLiquidGlassSheetBackdrop.current

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides sheetBackdrop) {
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
                optics = LiquidGlassOpticsTokens.Sheet,
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
