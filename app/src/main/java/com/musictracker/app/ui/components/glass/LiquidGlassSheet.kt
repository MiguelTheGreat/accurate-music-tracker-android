package com.musictracker.app.ui.components.glass

import android.os.Build
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.musictracker.app.ui.theme.LiquidGlassOpticsTokens
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Floating Apple iOS 26 Liquid Glass sheet:
 * - Always renders as a true [LiquidGlassSurface] with [LiquidGlassOpticsTokens.Sheet]
 *   (deeper blur, balanced translucent tint, hairline Fresnel rim) without snapping to opaque.
 * - On API 31+, also enables native `FLAG_BLUR_BEHIND` (`blurBehindRadius = 28.dp`) on the
 *   hosting [DialogWindowProvider] window so content behind `ModalBottomSheet` blurs cleanly
 *   even across the separate dialog window boundary.
 */
@Composable
fun LiquidGlassSheet(
    isExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val accessibility = LiquidGlassTheme.accessibility
    val view = LocalView.current
    val density = LocalDensity.current
    val blurBehindPx = with(density) { 28.dp.roundToPx() }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DisposableEffect(view, accessibility.reduceTransparency, blurBehindPx) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                if (!accessibility.reduceTransparency) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = blurBehindPx
                    }
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 0
                    }
                }
            }
            onDispose {
                val w = (view.parent as? DialogWindowProvider)?.window
                if (w != null) {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply {
                        blurBehindRadius = 0
                    }
                }
            }
        }
    }

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
