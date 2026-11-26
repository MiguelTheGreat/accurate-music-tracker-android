package com.musictracker.app.ui.components.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import com.musictracker.app.ui.theme.LiquidGlassOptics
import kotlin.math.ceil

/**
 * Lens geometry in the coordinate space of the modified content (pre-scale rect + scale
 * around its center). [magnificationBoost] multiplies the optics' base magnification
 * (e.g. swelling when pressed).
 */
internal class LiquidLensGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val magnificationBoost: Float = 1f
)

/**
 * Renders the modified content normally everywhere except inside a moving capsule lens,
 * where the same pixels are redrawn through the AGSL Liquid Glass lens shader (magnified
 * center, bent rim, chromatic dispersion, specular glint). Used by the nav-bar sliding pill
 * and the segmented-control thumb so the icons/labels *beneath* the lens are magnified.
 *
 * API 33+ only; below that (or when [enabled] is false) the content draws untouched.
 * No extra redraws: everything happens in the content's own draw pass, which only runs
 * when the lens position/scale or content actually changes.
 */
@Composable
internal fun Modifier.liquidLensContent(
    enabled: Boolean,
    optics: LiquidGlassOptics,
    isDark: Boolean,
    lightDirection: State<Offset>,
    geometry: DrawScope.() -> LiquidLensGeometry?
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val density = LocalDensity.current
    val contentLayer = rememberGraphicsLayer()
    val lensLayer = rememberGraphicsLayer()
    val builder = remember { LiquidGlassEffectBuilder() }
    val refractionPx = with(density) { optics.refraction.toPx() }
    val edgePx = with(density) { optics.edgeThickness.toPx() }
    val dispersionPx = with(density) { optics.dispersion.toPx() }

    return this.drawWithContent {
        val g = geometry()
        if (g == null || g.width < 4f || g.height < 4f) {
            drawContent()
            return@drawWithContent
        }
        contentLayer.record { this@drawWithContent.drawContent() }

        val w = g.width * g.scaleX
        val h = g.height * g.scaleY
        val l = g.left + (g.width - w) / 2f
        val t = g.top + (g.height - h) / 2f
        val radius = minOf(w, h) / 2f
        val lensPath = Path().apply {
            addRoundRect(RoundRect(l, t, l + w, t + h, CornerRadius(radius, radius)))
        }

        clipPath(lensPath, ClipOp.Difference) { drawLayer(contentLayer) }

        val light = lightDirection.value
        lensLayer.renderEffect = builder.buildRefraction(
            LiquidGlassShaderParams(
                widthPx = w,
                heightPx = h,
                cornerRadiusPx = radius,
                blurPx = 0f,
                refractionPx = refractionPx.coerceAtMost(minOf(w, h) * 0.3f),
                edgePx = edgePx.coerceAtMost(minOf(w, h) * 0.5f),
                dispersionPx = dispersionPx,
                magnification = optics.magnification * g.magnificationBoost,
                specular = optics.specularIntensity,
                lightX = light.x,
                lightY = light.y,
                luminanceAdaptation = optics.luminanceAdaptation,
                saturation = optics.saturation,
                isDark = isDark
            )
        )
        lensLayer.record(size = IntSize(ceil(w).toInt(), ceil(h).toInt())) {
            translate(left = -l, top = -t) { drawLayer(contentLayer) }
        }
        clipPath(lensPath) {
            translate(left = l, top = t) { drawLayer(lensLayer) }
        }
    }
}
