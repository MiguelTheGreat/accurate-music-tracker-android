package com.musictracker.app.ui.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ShareSheetInitialConfig(
    val mode: ShareCardMode = ShareCardMode.LISTENING_SUMMARY,
    val featuredArtist: TopArtistStat? = null,
    val featuredTrack: TopTrackStat? = null,
    val artistTopTracks: List<TopTrackStat> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardSheet(
    initialConfig: ShareSheetInitialConfig,
    timeframe: StatsTimeframe,
    summary: ListeningSummary,
    topTracks: List<TopTrackStat>,
    topArtists: List<TopArtistStat>,
    streakDays: Int,
    peakHour: Int?,
    onSelectTimeframe: (StatsTimeframe) -> Unit,
    onDismiss: () -> Unit,
    artworkResolver: suspend (key: String, isArtist: Boolean) -> File? = { _, _ -> null }
) {
    val context = LocalContext.current
    val colors = LiquidGlassTheme.colors
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedMode by remember(initialConfig) { mutableStateOf(initialConfig.mode) }
    var selectedAspect by remember { mutableStateOf(ShareAspectRatio.STORY_9_16) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var statusFeedback by remember { mutableStateOf<ShareExportResult?>(null) }

    val instagramAvailable = remember(context) {
        ShareCardExporter.isInstagramStoriesAvailable(context)
    }

    val effectiveTracks = remember(selectedMode, initialConfig, topTracks) {
        if (selectedMode == ShareCardMode.TOP_ARTIST && initialConfig.artistTopTracks.isNotEmpty()) {
            initialConfig.artistTopTracks
        } else {
            topTracks
        }
    }

    val effectiveArtist = remember(selectedMode, initialConfig) {
        if (selectedMode == ShareCardMode.TOP_ARTIST) {
            initialConfig.featuredArtist
        } else {
            null
        }
    }

    val effectiveTrack = remember(selectedMode, initialConfig) {
        if (selectedMode == ShareCardMode.TOP_MUSIC) {
            initialConfig.featuredTrack
        } else {
            null
        }
    }

    LaunchedEffect(
        selectedMode,
        selectedAspect,
        timeframe,
        summary,
        effectiveArtist,
        effectiveTrack,
        effectiveTracks,
        topArtists,
        streakDays,
        peakHour
    ) {
        isRendering = true
        val bitmap = withContext(Dispatchers.Default) {
            val primaryArt = resolvePrimaryBitmap(
                mode = selectedMode,
                artist = effectiveArtist ?: topArtists.firstOrNull(),
                track = effectiveTrack ?: effectiveTracks.firstOrNull(),
                artworkResolver = artworkResolver
            )
            val accentArt = if (selectedMode == ShareCardMode.TOP_ARTIST && effectiveArtist == null) {
                resolveArtistAccentBitmaps(
                    artists = topArtists.take(5),
                    artworkResolver = artworkResolver
                )
            } else {
                resolveAccentBitmaps(
                    tracks = effectiveTracks.take(5),
                    artworkResolver = artworkResolver
                )
            }
            ShareCardRenderer.render(
                ShareCardData(
                    mode = selectedMode,
                    aspectRatio = selectedAspect,
                    timeframe = timeframe,
                    summary = summary,
                    featuredArtist = effectiveArtist,
                    featuredTrack = effectiveTrack,
                    topTracks = effectiveTracks,
                    topArtists = topArtists,
                    streakDays = streakDays,
                    peakHour = peakHour,
                    primaryArtwork = primaryArt,
                    accentArtworks = accentArt
                )
            )
        }
        renderedBitmap = bitmap
        isRendering = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        LiquidGlassSheet(
            isExpanded = sheetState.currentValue == SheetValue.Expanded
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Share Collecto Card",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.labelPrimary
                        )
                        Text(
                            text = "Customize and export your listening snapshot",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.labelSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close share sheet",
                            tint = colors.labelSecondary
                        )
                    }
                }

                // Mode selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShareCardMode.entries.forEach { mode ->
                        ShareOptionPill(
                            label = mode.label,
                            selected = selectedMode == mode,
                            onClick = {
                                selectedMode = mode
                                statusFeedback = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Timeframe selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val timeframes = remember {
                        listOf(
                            StatsTimeframe.TODAY to "Today",
                            StatsTimeframe.SEVEN_DAYS to "7D",
                            StatsTimeframe.THIRTY_DAYS to "30D",
                            StatsTimeframe.ALL_TIME to "All"
                        )
                    }
                    timeframes.forEach { (tf, shortLabel) ->
                        ShareOptionPill(
                            label = shortLabel,
                            selected = timeframe == tf,
                            onClick = {
                                onSelectTimeframe(tf)
                                statusFeedback = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Aspect ratio toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShareAspectRatio.entries.forEach { ratio ->
                        ShareOptionPill(
                            label = ratio.label,
                            selected = selectedAspect == ratio,
                            onClick = {
                                selectedAspect = ratio
                                statusFeedback = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Card preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val previewAspect = selectedAspect.widthPx.toFloat() / selectedAspect.heightPx.toFloat()
                    val previewWidthFraction = if (selectedAspect == ShareAspectRatio.STORY_9_16) 0.56f else 0.72f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(previewWidthFraction)
                            .aspectRatio(previewAspect)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.standardCardFill)
                            .border(
                                width = 1.dp,
                                color = colors.rimBorder,
                                shape = RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = renderedBitmap
                        if (bmp != null) {
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Share card preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (isRendering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = colors.accentBlue
                            )
                        }
                    }
                }

                // Feedback pill
                statusFeedback?.let { result ->
                    val (bgColor, textColor, msg) = when (result) {
                        is ShareExportResult.Success -> Triple(
                            colors.accentGreen.copy(alpha = 0.16f),
                            colors.accentGreen,
                            result.message
                        )
                        is ShareExportResult.Error -> Triple(
                            colors.accentRed.copy(alpha = 0.16f),
                            colors.accentRed,
                            result.message
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LiquidGlassShapes.Capsule)
                            .background(bgColor)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Export / Share action buttons
                val canAct = renderedBitmap != null && !isRendering && !isExporting

                if (instagramAvailable) {
                    LiquidGlassButton(
                        onClick = {
                            val bmp = renderedBitmap ?: return@LiquidGlassButton
                            coroutineScope.launch {
                                isExporting = true
                                statusFeedback = ShareCardExporter.shareToInstagramStories(context, bmp)
                                isExporting = false
                            }
                        },
                        enabled = canAct,
                        variant = LiquidGlassVariant.Prominent,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Share to Stories",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiquidGlassButton(
                        onClick = {
                            val bmp = renderedBitmap ?: return@LiquidGlassButton
                            coroutineScope.launch {
                                isExporting = true
                                statusFeedback = ShareCardExporter.saveToGallery(context, bmp, selectedMode)
                                isExporting = false
                            }
                        },
                        enabled = canAct,
                        variant = LiquidGlassVariant.Regular,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Save Image",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    LiquidGlassButton(
                        onClick = {
                            val bmp = renderedBitmap ?: return@LiquidGlassButton
                            coroutineScope.launch {
                                isExporting = true
                                statusFeedback = ShareCardExporter.shareStandard(context, bmp)
                                isExporting = false
                            }
                        },
                        enabled = canAct,
                        variant = if (instagramAvailable) LiquidGlassVariant.Regular else LiquidGlassVariant.Prominent,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Share",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ShareOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val bgColor = if (selected) colors.accentBlue else colors.controlTrackFill
    val textColor = if (selected) colors.onProminent else colors.labelSecondary

    Box(
        modifier = modifier
            .clip(LiquidGlassShapes.Capsule)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private suspend fun resolvePrimaryBitmap(
    mode: ShareCardMode,
    artist: TopArtistStat?,
    track: TopTrackStat?,
    artworkResolver: suspend (key: String, isArtist: Boolean) -> File?
): Bitmap? {
    val resolvedFile = when (mode) {
        ShareCardMode.TOP_ARTIST, ShareCardMode.LISTENING_SUMMARY -> {
            val artistKey = artist?.artist
            if (!artistKey.isNullOrBlank()) {
                runCatching { artworkResolver(artistKey, true) }.getOrNull()
            } else {
                null
            }
        }
        ShareCardMode.TOP_MUSIC -> {
            val trackKey = track?.let { "${it.artist}::${it.title}" }
            if (!trackKey.isNullOrBlank()) {
                runCatching { artworkResolver(trackKey, false) }.getOrNull()
            } else {
                null
            }
        }
    }
    if (resolvedFile != null) {
        decodeFileSafely(resolvedFile)?.let { return it }
    }
    val localUriFile = track?.artworkUri?.let(::localFileFromUri)
    return localUriFile?.let(::decodeFileSafely)
}

private suspend fun resolveAccentBitmaps(
    tracks: List<TopTrackStat>,
    artworkResolver: suspend (key: String, isArtist: Boolean) -> File?
): List<Bitmap?> = tracks.map { track ->
    val resolved = runCatching { artworkResolver("${track.artist}::${track.title}", false) }.getOrNull()
    val file = resolved ?: track.artworkUri?.let(::localFileFromUri)
    file?.let(::decodeFileSafely)
}

private suspend fun resolveArtistAccentBitmaps(
    artists: List<TopArtistStat>,
    artworkResolver: suspend (key: String, isArtist: Boolean) -> File?
): List<Bitmap?> = artists.map { artist ->
    val resolved = runCatching { artworkResolver(artist.artist, true) }.getOrNull()
    resolved?.let(::decodeFileSafely)
}

private fun localFileFromUri(uri: String): File? {
    val path = when {
        uri.startsWith("file://") -> uri.removePrefix("file://")
        uri.startsWith("/") -> uri
        else -> return null
    }
    return File(path).takeIf { it.isFile && it.canRead() }
}

private fun decodeFileSafely(file: File): Bitmap? = runCatching {
    if (!file.isFile || !file.canRead()) return@runCatching null
    BitmapFactory.decodeFile(file.absolutePath)
}.getOrNull()
