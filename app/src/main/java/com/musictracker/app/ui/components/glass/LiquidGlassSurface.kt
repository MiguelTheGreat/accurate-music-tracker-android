package com.musictracker.app.ui.components.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme

enum class LiquidGlassVariant {
    /** Default (~95% of chrome): blurs and adjusts luminosity for legibility over scrolling content. */
    Regular,

    /** Media-only: highly translucent with mandatory 35% dark dimming scrim when over bright media. */
    Clear,

    /** Tinted CTA emphasis (`glassProminent`). */
    Prominent
}

/**
 * Prevents accidental glass-on-glass nesting: if a [LiquidGlassSurface] is placed inside another
 * [LiquidGlassSurface], it demotes to a non-blurred opaque/tinted fill.
 */
internal val LocalInsideLiquidGlass = compositionLocalOf { false }

/**
 * Core Liquid Glass chrome primitive.
 *
 * - API 31+: Samples the sibling [LiquidGlassBackdropState] into a background-only layer blurred
 *   with `RenderEffect` (guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` and
 *   `blurRadiusPx > 0.5f` to avoid Android's `BlurEffect(0f, 0f)` runtime crash).
 * - API 26-30: Uses a graceful frosted translucent fallback with specular rim highlights.
 * - Accessibility: Honors Reduced Transparency (no blur, ~0.94 fill), High Contrast (1.5dp 0.85
 *   alpha border), and Reduced Motion (disables touch bloom).
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    variant: LiquidGlassVariant = LiquidGlassVariant.Regular,
    shape: Shape = LiquidGlassShapes.Capsule,
    isBrightMediaBehind: Boolean = false,
    interactive: Boolean = false,
    blurRadiusOverride: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val backdrop = LocalLiquidGlassBackdrop.current
    val isNestedGlass = LocalInsideLiquidGlass.current
    val density = LocalDensity.current

    var surfacePositionInRoot by remember { mutableStateOf(Offset.Zero) }

    val baseBlurDp = blurRadiusOverride ?: when (variant) {
        LiquidGlassVariant.Regular, LiquidGlassVariant.Prominent -> LiquidGlassMetrics.BlurRadiusRegular
        LiquidGlassVariant.Clear -> LiquidGlassMetrics.BlurRadiusClear
    }
    val blurRadiusPx = with(density) { baseBlurDp.toPx() }
    val saturation = when (variant) {
        LiquidGlassVariant.Clear -> LiquidGlassMetrics.SaturationClear
        LiquidGlassVariant.Regular, LiquidGlassVariant.Prominent -> LiquidGlassMetrics.SaturationRegular
    }

    val canUseBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !accessibility.reduceTransparency &&
        !isNestedGlass &&
        backdrop != null &&
        blurRadiusPx > LiquidGlassMetrics.MinSafeBlurRadiusPx

    val borderWidth = if (accessibility.highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast
    } else {
        LiquidGlassMetrics.RimWidthStandard
    }
    val borderBrush = if (accessibility.highContrast) {
        Brush.verticalGradient(listOf(colors.rimBorderHighContrast, colors.rimBorderHighContrast))
    } else {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to colors.specularTopHighlight,
                0.35f to colors.rimBorder,
                1.0f to colors.specularBottomHighlight
            )
        )
    }

    val fillBrush: Brush = when {
        accessibility.reduceTransparency || isNestedGlass -> {
            val solid = when (variant) {
                LiquidGlassVariant.Prominent -> colors.accentBlue
                LiquidGlassVariant.Regular, LiquidGlassVariant.Clear -> colors.regularFillReducedTransparency
            }
            Brush.verticalGradient(listOf(solid, solid))
        }

        variant == LiquidGlassVariant.Prominent -> {
            Brush.linearGradient(
                colors = listOf(colors.prominentGradientStart, colors.prominentGradientEnd)
            )
        }

        variant == LiquidGlassVariant.Clear -> {
            Brush.verticalGradient(
                colors = listOf(
                    colors.clearFill.copy(alpha = (colors.clearFill.alpha * 1.15f).coerceAtMost(1f)),
                    colors.clearFill
                )
            )
        }

        canUseBackdropBlur -> {
            Brush.verticalGradient(
                colors = listOf(
                    colors.regularFill,
                    colors.regularFill.copy(alpha = (colors.regularFill.alpha * 0.92f).coerceAtLeast(0f))
                )
            )
        }

        else -> {
            // API 26-30 frosted translucent fallback
            Brush.verticalGradient(
                colors = listOf(
                    colors.regularFillFallback,
                    colors.regularFillFallback.copy(alpha = (colors.regularFillFallback.alpha * 0.94f).coerceAtLeast(0f))
                )
            )
        }
    }

    val elevationDp = if (isNestedGlass) 0.dp else 10.dp

    Box(
        modifier = modifier
            .shadow(
                elevation = elevationDp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.24f),
                spotColor = Color.Black.copy(alpha = 0.28f),
                clip = false
            )
            .clip(shape)
            .onGloballyPositioned { coordinates ->
                surfacePositionInRoot = coordinates.positionInRoot()
            }
            .touchPointIllumination(enabled = interactive && !isNestedGlass)
    ) {
        // Layer 1: Background-only blurred backdrop (sibling behind foreground content)
        if (canUseBackdropBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            blurRadiusPx > LiquidGlassMetrics.MinSafeBlurRadiusPx
                        ) {
                            renderEffect = createLiquidGlassRenderEffect(
                                radiusPx = blurRadiusPx,
                                saturation = saturation
                            )?.asComposeRenderEffect()
                        }
                    }
                    .drawBehind {
                        if (backdrop.hasRecordedContent) {
                            val offset = surfacePositionInRoot - backdrop.hostPositionInRoot
                            translate(left = -offset.x, top = -offset.y) {
                                drawLayer(backdrop.graphicsLayer)
                            }
                        }
                    }
            )
        }

        // Official 35% Dimming Rule for Clear Liquid Glass over bright media
        if (variant == LiquidGlassVariant.Clear && isBrightMediaBehind && !accessibility.reduceTransparency) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.clearBrightMediaDimScrim)
            )
        }

        // Optical glass tint + specular rim border
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(fillBrush)
                .border(width = borderWidth, brush = borderBrush, shape = shape)
        )

        // Layer 2: Razor-sharp foreground content
        CompositionLocalProvider(LocalInsideLiquidGlass provides true) {
            Box(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun createLiquidGlassRenderEffect(
    radiusPx: Float,
    saturation: Float
): RenderEffect? {
    if (radiusPx <= LiquidGlassMetrics.MinSafeBlurRadiusPx) return null
    val blurEffect = RenderEffect.createBlurEffect(
        radiusPx,
        radiusPx,
        Shader.TileMode.CLAMP
    )
    val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
    val colorFilterEffect = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(saturationMatrix)
    )
    return RenderEffect.createChainEffect(colorFilterEffect, blurEffect)
}
