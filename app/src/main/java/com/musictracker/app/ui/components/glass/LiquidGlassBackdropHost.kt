package com.musictracker.app.ui.components.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.musictracker.app.ui.theme.LiquidGlassTheme

/**
 * Holds the recorded content-layer [GraphicsLayer] so floating Liquid Glass chrome overlays
 * can sample and blur pixels behind them without blurring their own foreground text/icons.
 *
 * Fields [hostPositionInRoot] and [hasRecordedContent] are intentionally plain mutable fields
 * (not Compose snapshot state) because they are updated during layout/draw phases.
 */
@Stable
class LiquidGlassBackdropState(
    val graphicsLayer: GraphicsLayer
) {
    var hostPositionInRoot: Offset = Offset.Zero
    var hasRecordedContent: Boolean = false
}

val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdropState?> { null }

/**
 * Two-layer backdrop host for Apple HIG-faithful Liquid Glass in Jetpack Compose:
 * - [content] is the scrollable content layer recorded into a [GraphicsLayer] on API 31+
 *   (when Reduced Transparency is off).
 * - [overlay] hosts floating chrome (navigation bar, scroll edge effects, banners) as a sibling
 *   above [content], guaranteeing zero self-blur recursion.
 */
@Composable
fun LiquidGlassBackdropHost(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val accessibility = LiquidGlassTheme.accessibility
    val graphicsLayer = rememberGraphicsLayer()
    val backdropState = remember(graphicsLayer) { LiquidGlassBackdropState(graphicsLayer) }
    val shouldRecord = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !accessibility.reduceTransparency

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                backdropState.hostPositionInRoot = coordinates.positionInRoot()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (shouldRecord && size.width >= 1f && size.height >= 1f) {
                        backdropState.hasRecordedContent = false
                        backdropState.graphicsLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        backdropState.hasRecordedContent = true
                        drawLayer(backdropState.graphicsLayer)
                    } else {
                        backdropState.hasRecordedContent = false
                        drawContent()
                    }
                }
        ) {
            CompositionLocalProvider(LocalLiquidGlassBackdrop provides null) {
                content()
            }
        }

        CompositionLocalProvider(LocalLiquidGlassBackdrop provides backdropState) {
            overlay()
        }
    }
}
