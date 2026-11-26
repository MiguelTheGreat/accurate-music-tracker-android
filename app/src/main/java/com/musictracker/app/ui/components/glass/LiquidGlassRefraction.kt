package com.musictracker.app.ui.components.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musictracker.app.ui.theme.LiquidGlassColorPalette
import com.musictracker.app.ui.theme.LiquidGlassMetrics

internal val RefractionBlurRadiusRegular = 12.dp
internal val RefractionBlurRadiusClear = 7.dp
internal const val RefractionSaturationRegular = 1.80f
internal const val RefractionSaturationClear = 1.90f

/**
 * Result of constructing the Liquid Glass backdrop [ComposeRenderEffect].
 * [hasActiveRefraction] is true only when the API 33+ AGSL [RuntimeShader] compiled and chained
 * cleanly, allowing [LiquidGlassSurface] to dial fill opacity down for unmistakable glass clarity
 * while falling back safely on API 31–32, quirky OEM drivers, or Robolectric JVM tests.
 */
internal data class LiquidGlassEffectResult(
    val renderEffect: ComposeRenderEffect?,
    val hasActiveRefraction: Boolean
)

/**
 * AGSL (Android Graphics Shading Language, API 33+) convex optical refraction shader:
 * - Evaluates an exact rounded-rectangle signed distance field (`sdRoundedBox`) and analytic
 *   outward surface normal (`sdRoundedBoxNormal`).
 * - Applies smoothstepped convex edge-bending refraction within the curved glass bezel band.
 * - Adds subtle R/G/B chromatic dispersion near the rim, clamping every sample coordinate to
 *   `[0.5, resolution - 0.5]` so border pixels never bleed or glitch.
 * - Synthesizes directional top-left Fresnel specular glint and bottom-right inner caustic bounce
 *   tuned for both Dark and Light modes via `isDark`.
 */
internal const val LIQUID_GLASS_REFRACTION_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float cornerRadius;
    uniform float refractionStrength;
    uniform float chromaticAberration;
    uniform float isDark;

    float sdRoundedBox(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + float2(r);
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
    }

    float2 sdRoundedBoxNormal(float2 p, float2 b, float r) {
        float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
        float2 q = abs(p) - b + float2(r);
        float2 grad;
        if (q.x > 0.0 || q.y > 0.0) {
            float2 mq = max(q, 0.0);
            float len = length(mq);
            grad = len > 1e-4 ? (mq / len) : float2(0.0, 1.0);
        } else {
            grad = (q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
        }
        return s * grad;
    }

    half4 main(float2 fragCoord) {
        float2 halfSize = resolution * 0.5;
        float minHalf = max(min(halfSize.x, halfSize.y), 1.0);
        float r = clamp(cornerRadius, 1.0, minHalf);
        float2 p = fragCoord - halfSize;

        float d = sdRoundedBox(p, halfSize, r);
        float bezel = min(minHalf * 0.48, max(r * 0.90, 26.0));
        float edgeFactor = clamp(1.0 + d / max(bezel, 1.0), 0.0, 1.0);
        float lens = edgeFactor * edgeFactor * (3.0 - 2.0 * edgeFactor);

        float2 n = sdRoundedBoxNormal(p, halfSize, r);
        float2 minCoord = float2(0.5);
        float2 maxCoord = max(resolution - float2(0.5), minCoord);

        float2 refractedCoord = fragCoord - n * (lens * refractionStrength);
        float2 chromaOffset = n * (lens * chromaticAberration);

        float2 coordR = clamp(refractedCoord - chromaOffset, minCoord, maxCoord);
        float2 coordG = clamp(refractedCoord, minCoord, maxCoord);
        float2 coordB = clamp(refractedCoord + chromaOffset, minCoord, maxCoord);

        half4 sampleR = content.eval(coordR);
        half4 sampleG = content.eval(coordG);
        half4 sampleB = content.eval(coordB);

        half outAlpha = sampleG.a;
        half3 color = half3(sampleR.r, sampleG.g, sampleB.b);

        float2 lightDir = normalize(float2(-0.62, -0.78));
        float fresnel = pow(edgeFactor, 2.2);
        float topLeftSpec = pow(max(dot(n, lightDir), 0.0), 1.6) * fresnel;
        float bottomRightCaustic = pow(max(dot(n, -lightDir), 0.0), 2.0) * pow(edgeFactor, 1.5);
        float rimGlow = pow(edgeFactor, 3.5);

        if (isDark > 0.5) {
            color += half3(0.95, 0.98, 1.0) * half(topLeftSpec * 0.24) * outAlpha;
            color += half3(0.75, 0.88, 1.0) * half(bottomRightCaustic * 0.14) * outAlpha;
            color += half3(0.85, 0.92, 1.0) * half(rimGlow * 0.08) * outAlpha;
        } else {
            color += half3(1.0, 1.0, 1.0) * half(topLeftSpec * 0.30) * outAlpha;
            color -= half3(0.14, 0.16, 0.22) * half(bottomRightCaustic * 0.14) * outAlpha;
            color += half3(0.92, 0.96, 1.0) * half(rimGlow * 0.06) * outAlpha;
        }

        return half4(clamp(color, half3(0.0), half3(1.0)), outAlpha);
    }
"""

internal fun resolveCornerRadiusPx(
    shape: Shape,
    size: Size,
    density: Density
): Float {
    if (size.width <= 0f || size.height <= 0f) return 0f
    val maxRadius = minOf(size.width, size.height) / 2f
    return when {
        shape == CircleShape -> maxRadius
        shape is CornerBasedShape -> shape.topStart.toPx(size, density).coerceIn(0f, maxRadius)
        else -> 0f
    }
}

@RequiresApi(Build.VERSION_CODES.S)
internal fun createLiquidGlassEffectResult(
    radiusPx: Float,
    saturation: Float,
    surfaceSizePx: IntSize,
    cornerRadiusPx: Float,
    refractionStrengthPx: Float,
    chromaticAberrationPx: Float,
    isDark: Boolean
): LiquidGlassEffectResult {
    if (radiusPx <= LiquidGlassMetrics.MinSafeBlurRadiusPx) {
        return LiquidGlassEffectResult(renderEffect = null, hasActiveRefraction = false)
    }
    val baseEffect = createBlurAndSaturationRenderEffect(
        radiusPx = radiusPx,
        saturation = saturation
    ) ?: return LiquidGlassEffectResult(renderEffect = null, hasActiveRefraction = false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        surfaceSizePx.width >= 4 &&
        surfaceSizePx.height >= 4 &&
        refractionStrengthPx > 0.5f
    ) {
        val refractedEffect = createApi33RefractionRenderEffect(
            innerEffect = baseEffect,
            widthPx = surfaceSizePx.width.toFloat(),
            heightPx = surfaceSizePx.height.toFloat(),
            cornerRadiusPx = cornerRadiusPx,
            refractionStrengthPx = refractionStrengthPx,
            chromaticAberrationPx = chromaticAberrationPx,
            isDark = isDark
        )
        if (refractedEffect != null) {
            return LiquidGlassEffectResult(
                renderEffect = refractedEffect.asComposeRenderEffect(),
                hasActiveRefraction = true
            )
        }
    }

    return LiquidGlassEffectResult(
        renderEffect = baseEffect.asComposeRenderEffect(),
        hasActiveRefraction = false
    )
}

@RequiresApi(Build.VERSION_CODES.S)
private fun createBlurAndSaturationRenderEffect(
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createApi33RefractionRenderEffect(
    innerEffect: RenderEffect,
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
    refractionStrengthPx: Float,
    chromaticAberrationPx: Float,
    isDark: Boolean
): RenderEffect? = runCatching {
    val shader = RuntimeShader(LIQUID_GLASS_REFRACTION_AGSL).apply {
        setFloatUniform("resolution", widthPx, heightPx)
        setFloatUniform("cornerRadius", cornerRadiusPx.coerceAtLeast(1f))
        setFloatUniform("refractionStrength", refractionStrengthPx)
        setFloatUniform("chromaticAberration", chromaticAberrationPx)
        setFloatUniform("isDark", if (isDark) 1f else 0f)
    }
    val refractionEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    RenderEffect.createChainEffect(refractionEffect, innerEffect)
}.getOrNull()

/**
 * Single-pass multi-layer caustic & Fresnel rim overlay for API 26–35 Liquid Glass surfaces
 * (invoked from `drawBehind` only when `!reduceTransparency && !isNestedGlass`):
 * 1. Subtle diagonal refractive gloss sheen (top-left catchlight to bottom-right).
 * 2. Inner caustic top-arc & bottom-bounce bevel stroke (inset 1.5dp) for convex glass depth.
 * 3. Crisp outer Fresnel rim border (1dp standard, or 1.5dp solid [LiquidGlassColorPalette.rimBorderHighContrast]).
 */
internal fun DrawScope.drawLiquidGlassOptics(
    shape: Shape,
    colors: LiquidGlassColorPalette,
    highContrast: Boolean
) {
    if (size.width < 2f || size.height < 2f) return

    val radius = resolveCornerRadiusPx(shape = shape, size = size, density = this)
    val cornerRadius = CornerRadius(radius, radius)

    // 1. Diagonal refractive gloss sheen (top-left catchlight -> bottom-right bounce)
    val glossBrush = if (colors.isDark) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.14f),
                0.38f to Color.White.copy(alpha = 0.03f),
                0.72f to Color.Transparent,
                1.0f to colors.accentBlue.copy(alpha = 0.06f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    } else {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.44f),
                0.36f to Color.White.copy(alpha = 0.12f),
                0.74f to Color.Transparent,
                1.0f to Color(0xFF0F172A).copy(alpha = 0.04f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    }
    drawRoundRect(
        brush = glossBrush,
        cornerRadius = cornerRadius
    )

    // 2. Inner caustic top-arc & bottom-bounce bevel stroke (inset 1.5dp)
    if (!highContrast) {
        val innerInsetPx = 1.5.dp.toPx()
        val innerStrokePx = 1.25.dp.toPx()
        val innerWidth = size.width - (innerInsetPx * 2f)
        val innerHeight = size.height - (innerInsetPx * 2f)
        if (innerWidth > 2f && innerHeight > 2f) {
            val innerRadius = (radius - innerInsetPx).coerceAtLeast(0f)
            val innerCausticBrush = if (colors.isDark) {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = 0.34f),
                        0.26f to Color.White.copy(alpha = 0.04f),
                        0.74f to Color.Transparent,
                        1.0f to colors.accentBlue.copy(alpha = 0.22f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = 0.85f),
                        0.28f to Color.White.copy(alpha = 0.16f),
                        0.74f to Color.Transparent,
                        1.0f to colors.accentBlue.copy(alpha = 0.18f)
                    )
                )
            }
            drawRoundRect(
                brush = innerCausticBrush,
                topLeft = Offset(innerInsetPx, innerInsetPx),
                size = Size(innerWidth, innerHeight),
                cornerRadius = CornerRadius(innerRadius, innerRadius),
                style = Stroke(width = innerStrokePx)
            )
        }
    }

    // 3. Crisp outer Fresnel rim border
    val strokePx = if (highContrast) {
        LiquidGlassMetrics.RimWidthHighContrast.toPx()
    } else {
        LiquidGlassMetrics.RimWidthStandard.toPx()
    }
    val halfStroke = strokePx / 2f
    val outerWidth = (size.width - strokePx).coerceAtLeast(0f)
    val outerHeight = (size.height - strokePx).coerceAtLeast(0f)
    val outerRadius = (radius - halfStroke).coerceAtLeast(0f)

    if (highContrast) {
        drawRoundRect(
            color = colors.rimBorderHighContrast,
            topLeft = Offset(halfStroke, halfStroke),
            size = Size(outerWidth, outerHeight),
            cornerRadius = CornerRadius(outerRadius, outerRadius),
            style = Stroke(width = strokePx)
        )
    } else {
        val outerRimBrush = if (colors.isDark) {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to colors.specularTopHighlight.copy(alpha = 0.78f),
                    0.28f to colors.rimBorder.copy(alpha = 0.36f),
                    0.72f to colors.rimBorder.copy(alpha = 0.20f),
                    1.0f to colors.specularBottomHighlight.copy(alpha = 0.45f)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        } else {
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to Color.White.copy(alpha = 0.92f),
                    0.30f to Color(0xFF0F172A).copy(alpha = 0.16f),
                    0.72f to Color(0xFF0F172A).copy(alpha = 0.12f),
                    1.0f to Color(0xFF0F172A).copy(alpha = 0.22f)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        }
        drawRoundRect(
            brush = outerRimBrush,
            topLeft = Offset(halfStroke, halfStroke),
            size = Size(outerWidth, outerHeight),
            cornerRadius = CornerRadius(outerRadius, outerRadius),
            style = Stroke(width = strokePx)
        )
    }
}
