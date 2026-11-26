package com.musictracker.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Optical, material, vibrancy, and semantic color tokens for the Liquid Glass Design System.
 *
 * Follows Apple HIG Liquid Glass principles:
 * - Liquid Glass tokens are reserved for floating chrome (nav bar, banners, hero media card,
 *   prominent CTAs, sheets) and Temporary Lift thumbs during active interaction.
 * - Standard Material tokens are used for dense content cards, lists, and page backgrounds.
 */
@Immutable
data class LiquidGlassColorPalette(
    val isDark: Boolean,
    // Regular Liquid Glass fills
    val regularFill: Color,
    val regularFillFallback: Color,
    val regularFillReducedTransparency: Color,
    // Clear Liquid Glass (media-only) & official 35% dimming rule
    val clearFill: Color,
    val clearBrightMediaDimScrim: Color,
    // Prominent tinted CTA (glassProminent)
    val prominentGradientStart: Color,
    val prominentGradientEnd: Color,
    val onProminent: Color,
    // Specular rims & highlights
    val rimBorder: Color,
    val rimBorderHighContrast: Color,
    val specularTopHighlight: Color,
    val specularBottomHighlight: Color,
    // Touch-point radial illumination
    val touchBloomCore: Color,
    val touchBloomMid: Color,
    // Scroll Edge Effects
    val scrollEdgeTint: Color,
    // Content layer (Standard Material — strictly NOT Liquid Glass)
    val contentBackground: Color,
    val standardCardFill: Color,
    val standardCardElevatedFill: Color,
    val standardCardBorder: Color,
    val standardCardBorderHighContrast: Color,
    val controlTrackFill: Color,
    val controlThumbSolid: Color,
    // Vibrancy text hierarchy
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    // Semantic accents
    val accentBlue: Color,
    val accentGreen: Color,
    val accentAmber: Color,
    val accentRed: Color
)

val DarkLiquidGlassPalette = LiquidGlassColorPalette(
    isDark = true,
    regularFill = Color(0x7A161A24),
    regularFillFallback = Color(0xC7161A24),
    regularFillReducedTransparency = Color(0xF0181C26),
    clearFill = Color(0x1FFFFFFF),
    clearBrightMediaDimScrim = Color(0x59000000),
    prominentGradientStart = Color(0xC70A84FF),
    prominentGradientEnd = Color(0x940A84FF),
    onProminent = Color(0xFFFFFFFF),
    rimBorder = Color(0x2EFFFFFF),
    rimBorderHighContrast = Color(0xD9FFFFFF),
    specularTopHighlight = Color(0x8CFFFFFF),
    specularBottomHighlight = Color(0x26FFFFFF),
    touchBloomCore = Color(0x52FFFFFF),
    touchBloomMid = Color(0x0FFFFFFF),
    scrollEdgeTint = Color(0x8C0A0C12),
    contentBackground = Color(0xFF0A0C12),
    standardCardFill = Color(0xFF151924),
    standardCardElevatedFill = Color(0xFF1D2231),
    standardCardBorder = Color(0x1AFFFFFF),
    standardCardBorderHighContrast = Color(0xCCFFFFFF),
    controlTrackFill = Color(0xFF23293A),
    controlThumbSolid = Color(0xFFFFFFFF),
    labelPrimary = Color(0xFFF5F7FA),
    labelSecondary = Color(0xB8E5EAF5),
    labelTertiary = Color(0x85C7D0E2),
    accentBlue = Color(0xFF0A84FF),
    accentGreen = Color(0xFF30D158),
    accentAmber = Color(0xFFFF9F0A),
    accentRed = Color(0xFFFF453A)
)

val LightLiquidGlassPalette = LiquidGlassColorPalette(
    isDark = false,
    regularFill = Color(0x78FFFFFF),
    regularFillFallback = Color(0xDBFFFFFF),
    regularFillReducedTransparency = Color(0xF0F5F7FA),
    clearFill = Color(0x1FFFFFFF),
    clearBrightMediaDimScrim = Color(0x59000000),
    prominentGradientStart = Color(0xC7007AFF),
    prominentGradientEnd = Color(0x94007AFF),
    onProminent = Color(0xFFFFFFFF),
    rimBorder = Color(0x2E0F172A),
    rimBorderHighContrast = Color(0xD9000000),
    specularTopHighlight = Color(0xE6FFFFFF),
    specularBottomHighlight = Color(0x240F172A),
    touchBloomCore = Color(0x38007AFF),
    touchBloomMid = Color(0x12007AFF),
    scrollEdgeTint = Color(0x8CF2F4F8),
    contentBackground = Color(0xFFF2F4F8),
    standardCardFill = Color(0xFFFFFFFF),
    standardCardElevatedFill = Color(0xFFF7F9FC),
    standardCardBorder = Color(0x1F0F172A),
    standardCardBorderHighContrast = Color(0xCC000000),
    controlTrackFill = Color(0xFFE1E6F0),
    controlThumbSolid = Color(0xFFFFFFFF),
    labelPrimary = Color(0xFF0E1320),
    labelSecondary = Color(0xB81E273B),
    labelTertiary = Color(0xB33B465E),
    accentBlue = Color(0xFF007AFF),
    accentGreen = Color(0xFF1A8F34),
    accentAmber = Color(0xFFC26400),
    accentRed = Color(0xFFFF3B30)
)

object LiquidGlassMetrics {
    val BlurRadiusRegular: Dp = 20.dp
    val BlurRadiusClear: Dp = 10.dp
    val BlurRadiusThumbLift: Dp = 8.dp
    const val SaturationRegular: Float = 1.85f
    const val SaturationClear: Float = 1.90f
    const val BrightMediaLuminanceThreshold: Float = 0.55f
    val TouchBloomRadius: Dp = 140.dp
    val RimWidthStandard: Dp = 1.dp
    val RimWidthHighContrast: Dp = 1.5.dp
    val MinRowHeight: Dp = 68.dp
    val NavBarHeight: Dp = 64.dp
    val NavBarMinimizedHeight: Dp = 52.dp
    const val MinSafeBlurRadiusPx: Float = 0.5f
}

object LiquidGlassMotion {
    val SpringFluid = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)
    val SpringBounce = spring<Float>(dampingRatio = 0.58f, stiffness = 420f)
    val ReducedMotionSpec = tween<Float>(durationMillis = 150, easing = LinearEasing)

    fun fluidSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) ReducedMotionSpec else SpringFluid

    fun bounceSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) ReducedMotionSpec else SpringBounce
}

val LocalLiquidGlassColors = staticCompositionLocalOf { DarkLiquidGlassPalette }
