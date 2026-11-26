package com.musictracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

object LiquidGlassTheme {
    val colors: LiquidGlassColorPalette
        @Composable get() = LocalLiquidGlassColors.current

    val accessibility: LiquidGlassAccessibilityState
        @Composable get() = LocalLiquidGlassAccessibility.current
}

@Composable
fun MusicTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceTransparency: Boolean = false,
    accessibilityOverride: LiquidGlassAccessibilityState? = null,
    content: @Composable () -> Unit
) {
    val accessibilityState = accessibilityOverride
        ?: rememberLiquidGlassAccessibilityState(reduceTransparency = reduceTransparency)

    val basePalette = if (darkTheme) DarkLiquidGlassPalette else LightLiquidGlassPalette
    val palette = if (accessibilityState.highContrast) {
        basePalette.copy(
            labelPrimary = if (darkTheme) Color.White else Color.Black,
            labelSecondary = if (darkTheme) Color(0xEBFFFFFF) else Color(0xEB000000),
            labelTertiary = if (darkTheme) Color(0xCCFFFFFF) else Color(0xCC000000),
            rimBorder = basePalette.rimBorderHighContrast,
            standardCardBorder = basePalette.standardCardBorderHighContrast
        )
    } else {
        basePalette
    }

    val materialColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accentBlue,
            onPrimary = palette.onProminent,
            secondary = palette.accentGreen,
            tertiary = palette.accentAmber,
            background = palette.contentBackground,
            onBackground = palette.labelPrimary,
            surface = palette.standardCardFill,
            onSurface = palette.labelPrimary,
            surfaceVariant = palette.standardCardElevatedFill,
            onSurfaceVariant = palette.labelSecondary,
            outline = palette.standardCardBorder,
            error = palette.accentRed,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = palette.accentBlue,
            onPrimary = palette.onProminent,
            secondary = palette.accentGreen,
            tertiary = palette.accentAmber,
            background = palette.contentBackground,
            onBackground = palette.labelPrimary,
            surface = palette.standardCardFill,
            onSurface = palette.labelPrimary,
            surfaceVariant = palette.standardCardElevatedFill,
            onSurfaceVariant = palette.labelSecondary,
            outline = palette.standardCardBorder,
            error = palette.accentRed,
            onError = Color.White
        )
    }

    CompositionLocalProvider(
        LocalLiquidGlassColors provides palette,
        LocalLiquidGlassAccessibility provides accessibilityState
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = MusicTrackerTypography,
            content = content
        )
    }
}
