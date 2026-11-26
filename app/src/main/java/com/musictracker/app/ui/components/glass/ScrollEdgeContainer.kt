package com.musictracker.app.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassTheme

enum class ScrollEdgeStyle {
    /** Gradual fade behind floating chrome (default). */
    Soft,

    /** Uniform higher-contrast band for dense scrolling content. */
    Hard
}

/**
 * Renders pointer-transparent top and bottom Scroll Edge Effects behind floating chrome bars
 * so foreground navigation and status indicators stay legible as content scrolls beneath them.
 */
@Composable
fun ScrollEdgeContainer(
    modifier: Modifier = Modifier,
    topEdgeHeight: Dp = 64.dp,
    bottomEdgeHeight: Dp = 104.dp,
    style: ScrollEdgeStyle = ScrollEdgeStyle.Soft,
    showTopEdge: Boolean = true,
    showBottomEdge: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val edgeColor = colors.scrollEdgeTint

    Box(modifier = modifier.fillMaxSize()) {
        if (showTopEdge) {
            val topBrush = when (style) {
                ScrollEdgeStyle.Soft -> Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to edgeColor,
                        0.55f to edgeColor.copy(alpha = edgeColor.alpha * 0.45f),
                        1.0f to Color.Transparent
                    )
                )

                ScrollEdgeStyle.Hard -> Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to edgeColor.copy(alpha = (edgeColor.alpha * 1.25f).coerceAtMost(0.92f)),
                        0.8f to edgeColor.copy(alpha = edgeColor.alpha * 0.85f),
                        1.0f to Color.Transparent
                    )
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topEdgeHeight)
                    .background(topBrush)
            )
        }

        if (showBottomEdge) {
            val bottomBrush = when (style) {
                ScrollEdgeStyle.Soft -> Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.45f to edgeColor.copy(alpha = edgeColor.alpha * 0.50f),
                        1.0f to edgeColor.copy(alpha = (edgeColor.alpha * 1.15f).coerceAtMost(0.92f))
                    )
                )

                ScrollEdgeStyle.Hard -> Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.2f to edgeColor.copy(alpha = edgeColor.alpha * 0.85f),
                        1.0f to edgeColor.copy(alpha = (edgeColor.alpha * 1.3f).coerceAtMost(0.95f))
                    )
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomEdgeHeight)
                    .background(bottomBrush)
            )
        }

        content()
    }
}
