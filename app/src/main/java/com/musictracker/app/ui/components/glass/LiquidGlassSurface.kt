package com.musictracker.app.ui.components.glass

import android.os.Build
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassOptics
import com.musictracker.app.ui.theme.LiquidGlassOpticsTokens
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
 * - API 33+: Samples the sibling [LiquidGlassBackdropState] through light blur → AGSL lens
 *   and optically refracted via an AGSL `RuntimeShader` (`RenderEffect.createRuntimeShaderEffect`
 *   Parameters come from [LiquidGlassOpticsTokens]; falls back to API 31–32 blur+saturation.
 * - API 31–32: Samples the sibling [LiquidGlassBackdropState] blurred with `RenderEffect`
 *   (guarded by `blurRadiusPx > 0.5f` to avoid Android's `BlurEffect(0f, 0f)` runtime crash).
 * - API 26–30: Uses a graceful frosted translucent fallback with multi-layer caustic & Fresnel
 *   rim highlights.
 * - Accessibility: Honors Reduced Transparency (no blur/refraction/caustics, ~0.94 fill),
 *   High Contrast (1.5dp 0.85 alpha border), and Reduced Motion (disables touch bloom).
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    variant: LiquidGlassVariant = LiquidGlassVariant.Regular,
    shape: Shape = LiquidGlassShapes.Capsule,
    isBrightMediaBehind: Boolean = false,
    interactive: Boolean = false,
    blurRadiusOverride: Dp? = null,
    optics: LiquidGlassOptics? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val backdrop = LocalLiquidGlassBackdrop.current
    val isNestedGlass = LocalInsideLiquidGlass.current
    val density = LocalDensity.current

    val surfacePositionInRoot = remember { mutableStateOf(Offset.Zero) }
    var surfaceSizePx by remember { mutableStateOf(IntSize.Zero) }

    val resolvedOptics = optics ?: LiquidGlassOpticsTokens.forVariant(variant == LiquidGlassVariant.Clear)
    val isRefractionTier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val blurRadiusPx = with(density) { (blurRadiusOverride ?: resolvedOptics.blurRadius).toPx() }
    val legacyBlurRadiusPx = with(density) { (blurRadiusOverride ?: resolvedOptics.legacyBlurRadius).toPx() }

    // Tiers: API 33+ refraction shader | API 31–32 light blur | API 26–30 / Reduced Transparency opaque.
    val canUseBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !accessibility.reduceTransparency &&
        !isNestedGlass &&
        backdrop != null &&
        (isRefractionTier || legacyBlurRadiusPx > LiquidGlassMetrics.MinSafeBlurRadiusPx)

    val lightDirection = rememberLiquidGlassLightDirection(
        enabled = canUseBackdropBlur && isRefractionTier && !accessibility.reduceMotion
    )
    val effectBuilder = remember { LiquidGlassEffectBuilder() }
    val legacyEffect = remember(canUseBackdropBlur, legacyBlurRadiusPx, resolvedOptics.legacySaturation) {
        if (canUseBackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createLegacyBlurEffect(legacyBlurRadiusPx, resolvedOptics.legacySaturation)
        } else {
            null
        }
    }

    val cornerRadiusPx = remember(shape, surfaceSizePx, density) {
        if (surfaceSizePx.width > 0 && surfaceSizePx.height > 0) {
            resolveCornerRadiusPx(
                shape = shape,
                size = Size(surfaceSizePx.width.toFloat(), surfaceSizePx.height.toFloat()),
                density = density
            )
        } else {
            0f
        }
    }

    val borderWidth = if (accessibility.highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast
    } else {
        LiquidGlassMetrics.RimWidthStandard
    }
    val fallbackBorderBrush = remember(accessibility.highContrast, colors) {
        if (accessibility.highContrast) {
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
    }

    val fillBrush: Brush = remember(
        accessibility.reduceTransparency,
        isNestedGlass,
        variant,
        canUseBackdropBlur,
        resolvedOptics.tintAlphaScale,
        colors
    ) {
        when {
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
                // Light tint so the refracted backdrop stays clearly visible.
                val baseFill = colors.regularFill.copy(
                    alpha = colors.regularFill.alpha * resolvedOptics.tintAlphaScale
                )
                Brush.verticalGradient(
                    colors = listOf(
                        baseFill,
                        baseFill.copy(alpha = (baseFill.alpha * 0.85f).coerceAtLeast(0f))
                    )
                )
            }

            else -> {
                // API 26-30 frosted translucent fallback (unchanged)
                Brush.verticalGradient(
                    colors = listOf(
                        colors.regularFillFallback,
                        colors.regularFillFallback.copy(alpha = (colors.regularFillFallback.alpha * 0.94f).coerceAtLeast(0f))
                    )
                )
            }
        }
    }

    val elevationDp = if (isNestedGlass) 0.dp else 10.dp
    val shadowAmbientAlpha = if (colors.isDark) 0.22f else 0.10f
    val shadowSpotAlpha = if (colors.isDark) 0.28f else 0.14f
    val showOpticalOverlay = !accessibility.reduceTransparency && !isNestedGlass

    val refractionPx = with(density) { resolvedOptics.refraction.toPx() }
    val edgePx = with(density) { resolvedOptics.edgeThickness.toPx() }
    val dispersionPx = with(density) { resolvedOptics.dispersion.toPx() }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevationDp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = shadowAmbientAlpha),
                spotColor = Color.Black.copy(alpha = shadowSpotAlpha),
                clip = false
            )
            .clip(shape)
            .then(
                if (canUseBackdropBlur) {
                    Modifier
                        .onSizeChanged { newSize ->
                            if (surfaceSizePx != newSize) {
                                surfaceSizePx = newSize
                            }
                        }
                        .onGloballyPositioned { coordinates ->
                            surfacePositionInRoot.value = coordinates.positionInRoot()
                        }
                } else {
                    Modifier
                }
            )
            .touchPointIllumination(enabled = interactive && !isNestedGlass)
    ) {
        // Layer 1: backdrop → light blur → AGSL lens refraction → saturation (background only)
        if (canUseBackdropBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        val minSide = minOf(size.width, size.height)
                        val light = lightDirection.value
                        renderEffect = effectBuilder.buildRefraction(
                            LiquidGlassShaderParams(
                                widthPx = size.width,
                                heightPx = size.height,
                                cornerRadiusPx = cornerRadiusPx,
                                blurPx = blurRadiusPx,
                                refractionPx = refractionPx.coerceAtMost(minSide * 0.3f),
                                edgePx = edgePx.coerceAtMost(minSide * 0.5f),
                                dispersionPx = dispersionPx,
                                magnification = resolvedOptics.magnification,
                                specular = resolvedOptics.specularIntensity,
                                lightX = light.x,
                                lightY = light.y,
                                luminanceAdaptation = resolvedOptics.luminanceAdaptation,
                                saturation = resolvedOptics.saturation,
                                isDark = colors.isDark
                            )
                        ) ?: legacyEffect
                    }
                    .drawBehind {
                        if (backdrop.hasRecordedContent) {
                            val offset = surfacePositionInRoot.value - backdrop.hostPositionInRoot
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

        // Optical glass tint + multi-layer caustic & Fresnel rim overlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(fillBrush)
                .then(
                    if (showOpticalOverlay) {
                        Modifier.drawBehind {
                            drawLiquidGlassOptics(
                                shape = shape,
                                colors = colors,
                                highContrast = accessibility.highContrast
                            )
                        }
                    } else {
                        Modifier.border(
                            width = borderWidth,
                            brush = fallbackBorderBrush,
                            shape = shape
                        )
                    }
                )
        )

        // Layer 2: Razor-sharp foreground content
        CompositionLocalProvider(LocalInsideLiquidGlass provides true) {
            Box(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}
