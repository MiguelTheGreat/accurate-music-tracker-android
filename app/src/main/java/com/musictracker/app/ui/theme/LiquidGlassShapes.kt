package com.musictracker.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Computes concentric nested corner radius following Apple's ConcentricRectangle principle:
 * `innerRadius = max(0, outerRadius - padding)`.
 */
fun concentricCornerRadius(outerRadius: Dp, padding: Dp): Dp =
    (outerRadius - padding).coerceAtLeast(0.dp)

fun concentricRoundedShape(outerRadius: Dp, padding: Dp): RoundedCornerShape =
    RoundedCornerShape(concentricCornerRadius(outerRadius, padding))

object LiquidGlassShapes {
    val Capsule = CircleShape

    val HeroOuterRadius: Dp = 28.dp
    val HeroPadding: Dp = 12.dp
    val HeroInnerRadius: Dp = concentricCornerRadius(HeroOuterRadius, HeroPadding) // 16.dp
    val HeroCard = RoundedCornerShape(HeroOuterRadius)
    val HeroInner = RoundedCornerShape(HeroInnerRadius)

    val BannerRadius: Dp = 24.dp
    val Banner = RoundedCornerShape(BannerRadius)

    val SheetInset = RoundedCornerShape(28.dp)
    val SheetExpanded = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    val ContentGroupOuterRadius: Dp = 24.dp
    val ContentGroupPadding: Dp = 12.dp
    val ContentGroupInnerRadius: Dp =
        concentricCornerRadius(ContentGroupOuterRadius, ContentGroupPadding) // 12.dp
    val ContentGroup = RoundedCornerShape(ContentGroupOuterRadius)
    val ContentInner = RoundedCornerShape(ContentGroupInnerRadius)

    val ControlTrackRadius: Dp = 14.dp
    val ControlTrackPadding: Dp = 3.dp
    val ControlThumbRadius: Dp =
        concentricCornerRadius(ControlTrackRadius, ControlTrackPadding) // 11.dp
    val ControlTrack = RoundedCornerShape(ControlTrackRadius)
    val ControlThumb = RoundedCornerShape(ControlThumbRadius)
}
