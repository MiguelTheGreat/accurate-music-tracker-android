package com.musictracker.app.ui.components.content

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.musictracker.app.ui.theme.LiquidGlassShapes
import java.io.File

private val ArtworkGradientPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFF1D4ED8) to Color(0xFF7C3AED),
    Color(0xFF0F766E) to Color(0xFF0284C7),
    Color(0xFFBE185D) to Color(0xFFF97316),
    Color(0xFF4338CA) to Color(0xFFEC4899),
    Color(0xFF047857) to Color(0xFF10B981),
    Color(0xFFB45309) to Color(0xFFEAB308),
    Color(0xFF6D28D9) to Color(0xFF2563EB),
    Color(0xFF9F1239) to Color(0xFFE11D48)
)

/**
 * Deterministic 2-color gradient derived from track/artist metadata seed.
 * Also used by the "Now Tracking" card for ambient backdrop & luminance estimation.
 */
fun deterministicArtworkColors(seed: String): Pair<Color, Color> {
    val index = Math.floorMod(seed.hashCode(), ArtworkGradientPalettes.size)
    return ArtworkGradientPalettes[index]
}

private fun normalizeArtworkModel(uri: String?): Any? {
    val trimmed = uri?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("/") -> File(trimmed)
        trimmed.startsWith("file://", ignoreCase = true) -> {
            val path = runCatching { Uri.parse(trimmed).path }.getOrNull()
                ?: trimmed.removePrefix("file://")
            if (path.isNotBlank()) File(path) else trimmed
        }
        else -> trimmed
    }
}

@Composable
fun ArtworkThumbnail(
    artworkUri: String?,
    fallbackSeed: String,
    contentDescription: String?,
    size: Dp = 44.dp,
    cornerRadius: Dp = LiquidGlassShapes.ContentGroupInnerRadius,
    modifier: Modifier = Modifier,
    shape: Shape? = null
) {
    val resolvedShape = shape ?: remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val gradientColors = remember(fallbackSeed) { deterministicArtworkColors(fallbackSeed) }
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val normalizedModel = remember(artworkUri) { normalizeArtworkModel(artworkUri) }
    val imageRequest = remember(context, normalizedModel, sizePx) {
        normalizedModel?.let { data ->
            ImageRequest.Builder(context)
                .data(data)
                .size(sizePx, sizePx)
                .crossfade(true)
                .build()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(resolvedShape)
            .background(Brush.linearGradient(listOf(gradientColors.first, gradientColors.second))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = if (imageRequest == null) contentDescription else null,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(size * 0.48f)
        )
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ArtistAvatar(
    artworkPath: String?,
    artistName: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val gradientColors = remember(artistName) { deterministicArtworkColors(artistName) }
    val monogram = remember(artistName) {
        artistName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val normalizedModel = remember(artworkPath) { normalizeArtworkModel(artworkPath) }
    val imageRequest = remember(context, normalizedModel, sizePx) {
        normalizedModel?.let { data ->
            ImageRequest.Builder(context)
                .data(data)
                .size(sizePx, sizePx)
                .crossfade(true)
                .build()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(gradientColors.first, gradientColors.second))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = artistName.ifBlank { "Artist photo" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
