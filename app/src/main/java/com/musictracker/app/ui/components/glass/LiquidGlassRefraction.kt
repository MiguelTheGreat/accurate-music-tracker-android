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
import kotlin.math.abs

/**
 * Parameters for one evaluation of the Liquid Glass lens pipeline. All lengths are in pixels.
 * [lightX]/[lightY] is the normalized 2D light direction (points *toward* the light; default
 * top-left). Instances are compared by value so an unchanged frame reuses the cached effect.
 */
internal data class LiquidGlassShaderParams(
    val widthPx: Float,
    val heightPx: Float,
    val cornerRadiusPx: Float,
    val blurPx: Float,
    val refractionPx: Float,
    val edgePx: Float,
    val dispersionPx: Float,
    val magnification: Float,
    val specular: Float,
    val lightX: Float,
    val lightY: Float,
    val luminanceAdaptation: Float,
    val saturation: Float,
    val isDark: Boolean
)

/**
 * AGSL (API 33+) single-pass Liquid Glass lens. No loops, 3 texture samples total
 * (G = base sample, plus R and B for dispersion).
 *
 * - Exact rounded-rect / capsule SDF; analytic outward normal.
 * - Height field = circular bevel across a rim band of width `edge`: displacement
 *   `bend = 1 - sqrt(1 - rim²)` is ~0 in the center and steepens toward the edge, giving
 *   Apple's "thick glass rim" — the middle stays nearly undistorted while content bends
 *   and magnifies toward the curved edge.
 * - `magnification` scales the whole sample field around the center (lens pills).
 * - Chromatic dispersion: R and B are offset ±`dispersion` along the normal at the rim.
 * - Light luminance adaptation pulls backdrop luma toward a legible mid tone.
 * - Specular: bright glint on the rim facing `lightDir`, softer bounce on the opposite rim.
 */
internal const val LIQUID_GLASS_LENS_AGSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float cornerRadius;
    uniform float refraction;
    uniform float edge;
    uniform float dispersion;
    uniform float magnification;
    uniform float2 lightDir;
    uniform float specular;
    uniform float lumAdapt;
    uniform float isDark;

    half4 main(float2 fc) {
        float2 hs = resolution * 0.5;
        float r = clamp(cornerRadius, 0.5, min(hs.x, hs.y));
        float2 p = fc - hs;
        float2 q = abs(p) - hs + float2(r);
        float d = min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;

        float2 g = (q.x > 0.0 && q.y > 0.0) ? normalize(q)
                 : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
        float2 n = float2(p.x < 0.0 ? -g.x : g.x, p.y < 0.0 ? -g.y : g.y);

        float rim = 1.0 - clamp(-d / max(edge, 1.0), 0.0, 1.0);
        float bend = 1.0 - sqrt(max(1.0 - rim * rim, 0.0));

        float2 lo = float2(0.5);
        float2 hi = max(resolution - float2(0.5), lo);
        float2 base = hs + p / max(magnification, 0.01) - n * (bend * refraction);
        float2 dOff = n * (bend * dispersion);

        float4 cG = float4(content.eval(clamp(base, lo, hi)));
        float cR = float(content.eval(clamp(base + dOff, lo, hi)).r);
        float cB = float(content.eval(clamp(base - dOff, lo, hi)).b);
        float3 col = float3(cR, cG.g, cB);

        float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
        float target = (isDark > 0.5 ? 0.30 : 0.74) * cG.a;
        col += float3((target - luma) * lumAdapt);

        float rimLight = rim * rim * rim;
        float s1 = max(dot(n, lightDir), 0.0);
        float s2 = max(dot(n, -lightDir), 0.0);
        float spec = (s1 * s1 + 0.45 * s2 * s2 * s2) * rimLight * specular;
        col += float3(spec) * max(cG.a, 0.35);

        float a = max(cG.a, clamp(spec, 0.0, 1.0));
        return half4(half3(clamp(col, float3(0.0), float3(a))), half(a));
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

/**
 * Caches one [RuntimeShader] per surface and rebuilds the (cheap, immutable) [RenderEffect]
 * wrapper only when [LiquidGlassShaderParams] actually change. Safe to call from a
 * `graphicsLayer {}` block every frame: identical params return the cached effect, so idle
 * surfaces never allocate or re-render.
 */
internal class LiquidGlassEffectBuilder {
    private var shader: Any? = null
    private var shaderFailed = false
    private var lastParams: LiquidGlassShaderParams? = null
    private var lastEffect: ComposeRenderEffect? = null

    /** API 33+ full tier: light blur → lens shader → saturation. Null if unavailable. */
    fun buildRefraction(params: LiquidGlassShaderParams): ComposeRenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) return null
        if (params.widthPx < 4f || params.heightPx < 4f) return null
        if (params == lastParams) return lastEffect
        val effect = runCatching { buildApi33(params) }.getOrElse {
            shaderFailed = true
            null
        }
        lastParams = params
        lastEffect = effect
        return effect
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildApi33(p: LiquidGlassShaderParams): ComposeRenderEffect {
        val rs = (shader as? RuntimeShader) ?: RuntimeShader(LIQUID_GLASS_LENS_AGSL).also { shader = it }
        rs.setFloatUniform("resolution", p.widthPx, p.heightPx)
        rs.setFloatUniform("cornerRadius", p.cornerRadiusPx.coerceAtLeast(0.5f))
        rs.setFloatUniform("refraction", p.refractionPx)
        rs.setFloatUniform("edge", p.edgePx)
        rs.setFloatUniform("dispersion", p.dispersionPx)
        rs.setFloatUniform("magnification", p.magnification)
        rs.setFloatUniform("lightDir", p.lightX, p.lightY)
        rs.setFloatUniform("specular", p.specular)
        rs.setFloatUniform("lumAdapt", p.luminanceAdaptation)
        rs.setFloatUniform("isDark", if (p.isDark) 1f else 0f)

        var effect = RenderEffect.createRuntimeShaderEffect(rs, "content")
        if (p.blurPx > LiquidGlassMetrics.MinSafeBlurRadiusPx) {
            val blur = RenderEffect.createBlurEffect(p.blurPx, p.blurPx, Shader.TileMode.CLAMP)
            effect = RenderEffect.createChainEffect(effect, blur)
        }
        if (abs(p.saturation - 1f) > 0.01f) {
            val sat = RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(p.saturation) })
            )
            effect = RenderEffect.createChainEffect(sat, effect)
        }
        return effect.asComposeRenderEffect()
    }
}

/** API 31–32 tier: blur + saturation only (lighter tuning than before). */
@RequiresApi(Build.VERSION_CODES.S)
internal fun createLegacyBlurEffect(radiusPx: Float, saturation: Float): ComposeRenderEffect? {
    if (radiusPx <= LiquidGlassMetrics.MinSafeBlurRadiusPx) return null
    val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val sat = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
    )
    return RenderEffect.createChainEffect(sat, blur).asComposeRenderEffect()
}


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
