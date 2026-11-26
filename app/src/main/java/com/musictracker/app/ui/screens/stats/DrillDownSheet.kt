package com.musictracker.app.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.domain.model.ArtistDetailStats
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.domain.model.TrackDetailStats
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.ArtistAvatar
import com.musictracker.app.ui.components.content.ArtworkThumbnail
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import com.musictracker.app.ui.theme.withTabularNumerals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface DrillDownTarget {
    data class Track(val track: TopTrackStat) : DrillDownTarget
    data class Artist(val artist: TopArtistStat) : DrillDownTarget
}

private val DrillDownDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillDownSheet(
    target: DrillDownTarget,
    timeframe: StatsTimeframe,
    trackDetail: TrackDetailStats?,
    artistDetail: ArtistDetailStats?,
    artistTopTracks: List<TopTrackStat>,
    onShareTrack: (TopTrackStat) -> Unit,
    onShareArtist: (TopArtistStat, List<TopTrackStat>) -> Unit,
    onDismiss: () -> Unit,
    artistArtworkPaths: Map<String, String> = emptyMap(),
    trackArtworkPaths: Map<Long, String> = emptyMap()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (target) {
                    is DrillDownTarget.Track -> TrackDrillDownBody(
                        fallbackTrack = target.track,
                        detail = trackDetail,
                        resolvedArtworkPath = trackArtworkPaths[target.track.trackId],
                        timeframe = timeframe,
                        onShare = { onShareTrack(target.track) },
                        onDismiss = onDismiss
                    )

                    is DrillDownTarget.Artist -> ArtistDrillDownBody(
                        fallbackArtist = target.artist,
                        detail = artistDetail,
                        topTracks = artistTopTracks,
                        resolvedArtistArtworkPath = artistArtworkPaths[artistDetail?.artist]
                            ?: artistArtworkPaths[target.artist.artist],
                        trackArtworkPaths = trackArtworkPaths,
                        timeframe = timeframe,
                        onShare = { onShareArtist(target.artist, artistTopTracks) },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackDrillDownBody(
    fallbackTrack: TopTrackStat,
    detail: TrackDetailStats?,
    resolvedArtworkPath: String?,
    timeframe: StatsTimeframe,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val title = detail?.title ?: fallbackTrack.title
    val artist = detail?.artist ?: fallbackTrack.artist
    val album = detail?.album ?: fallbackTrack.album
    val artworkUri = resolvedArtworkPath ?: detail?.artworkUri ?: fallbackTrack.artworkUri
    val playCount = detail?.playCount ?: fallbackTrack.playCount
    val totalMs = detail?.totalListenedMs ?: fallbackTrack.totalListenedMs
    val skipRate = detail?.skipRate ?: 0f

    // Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ArtworkThumbnail(
            artworkUri = artworkUri,
            fallbackSeed = "$title|$artist",
            contentDescription = title,
            size = 64.dp,
            cornerRadius = 14.dp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (album.isNotBlank()) {
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close track details",
                tint = colors.labelSecondary
            )
        }
    }

    TimeframeCaptionPill(timeframe = timeframe)

    // 2x2 stats grid
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrillDownMetricTile(
                label = "Time Listened",
                value = formatSummaryDuration(totalMs),
                modifier = Modifier.weight(1f)
            )
            DrillDownMetricTile(
                label = if (playCount == 1) "Play" else "Plays",
                value = playCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrillDownMetricTile(
                label = "Skip Rate",
                value = formatSkipRate(playCount, skipRate),
                modifier = Modifier.weight(1f)
            )
            val avgMs = if (playCount > 0) totalMs / playCount else 0L
            DrillDownMetricTile(
                label = "Avg / Play",
                value = formatSummaryDuration(avgMs),
                modifier = Modifier.weight(1f)
            )
        }
    }

    HeardDatesRow(
        firstHeardEpoch = detail?.firstHeardEpoch,
        lastHeardEpoch = detail?.lastHeardEpoch
    )

    LiquidGlassButton(
        onClick = onShare,
        variant = LiquidGlassVariant.Prominent,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Share Track Card",
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ArtistDrillDownBody(
    fallbackArtist: TopArtistStat,
    detail: ArtistDetailStats?,
    topTracks: List<TopTrackStat>,
    resolvedArtistArtworkPath: String?,
    trackArtworkPaths: Map<Long, String>,
    timeframe: StatsTimeframe,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val artistName = detail?.artist ?: fallbackArtist.artist
    val playCount = detail?.playCount ?: fallbackArtist.playCount
    val totalMs = detail?.totalListenedMs ?: fallbackArtist.totalListenedMs
    val uniqueTracks = detail?.uniqueTrackCount ?: fallbackArtist.uniqueTrackCount
    val skipRate = detail?.skipRate ?: 0f

    // Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ArtistAvatar(
            artworkPath = resolvedArtistArtworkPath,
            artistName = artistName,
            size = 62.dp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = artistName,
                style = MaterialTheme.typography.titleLarge,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (uniqueTracks == 1) "1 tracked song" else "$uniqueTracks tracked songs",
                style = MaterialTheme.typography.bodySmall,
                color = colors.labelSecondary
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close artist details",
                tint = colors.labelSecondary
            )
        }
    }

    TimeframeCaptionPill(timeframe = timeframe)

    // 2x2 stats grid
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrillDownMetricTile(
                label = "Time Listened",
                value = formatSummaryDuration(totalMs),
                modifier = Modifier.weight(1f)
            )
            DrillDownMetricTile(
                label = if (playCount == 1) "Play" else "Plays",
                value = playCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrillDownMetricTile(
                label = "Skip Rate",
                value = formatSkipRate(playCount, skipRate),
                modifier = Modifier.weight(1f)
            )
            DrillDownMetricTile(
                label = "Unique Tracks",
                value = uniqueTracks.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }

    HeardDatesRow(
        firstHeardEpoch = detail?.firstHeardEpoch,
        lastHeardEpoch = detail?.lastHeardEpoch
    )

    if (topTracks.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Top Tracks",
                style = MaterialTheme.typography.titleMedium,
                color = colors.labelPrimary
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.standardCardFill)
                    .border(1.dp, colors.standardCardBorder, RoundedCornerShape(16.dp))
                    .padding(vertical = 4.dp)
            ) {
                topTracks.forEachIndexed { idx, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "#${idx + 1}",
                            style = MaterialTheme.typography.labelMedium.withTabularNumerals(),
                            color = colors.labelTertiary
                        )
                        ArtworkThumbnail(
                            artworkUri = trackArtworkPaths[track.trackId] ?: track.artworkUri,
                            fallbackSeed = "${track.title}|${track.artist}",
                            contentDescription = track.title,
                            size = 36.dp,
                            cornerRadius = 8.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (track.playCount == 1) "1 play" else "${track.playCount} plays",
                                style = MaterialTheme.typography.labelSmall.withTabularNumerals(),
                                color = colors.labelTertiary
                            )
                        }
                        Text(
                            text = formatSummaryDuration(track.totalListenedMs),
                            style = MaterialTheme.typography.labelLarge.withTabularNumerals(),
                            color = colors.labelPrimary
                        )
                    }
                    if (idx < topTracks.lastIndex) {
                        HorizontalDivider(
                            color = colors.standardCardBorder,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }

    LiquidGlassButton(
        onClick = onShare,
        variant = LiquidGlassVariant.Prominent,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Share Artist Card",
            style = MaterialTheme.typography.labelLarge
        )
    }

    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun TimeframeCaptionPill(timeframe: StatsTimeframe) {
    val colors = LiquidGlassTheme.colors
    val label = when (timeframe) {
        StatsTimeframe.TODAY -> "Showing stats for Today"
        StatsTimeframe.SEVEN_DAYS -> "Showing stats for Last 7 Days"
        StatsTimeframe.THIRTY_DAYS -> "Showing stats for Last 30 Days"
        StatsTimeframe.ALL_TIME -> "Showing All-Time stats"
    }
    Box(
        modifier = Modifier
            .clip(LiquidGlassShapes.Capsule)
            .background(colors.controlTrackFill)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.labelSecondary
        )
    }
}

@Composable
private fun DrillDownMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.standardCardFill)
            .border(1.dp, colors.standardCardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.withTabularNumerals(),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.labelSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun HeardDatesRow(
    firstHeardEpoch: Long?,
    lastHeardEpoch: Long?
) {
    val colors = LiquidGlassTheme.colors
    val firstText = remember(firstHeardEpoch) { formatEpochDate(firstHeardEpoch) }
    val lastText = remember(lastHeardEpoch) { formatEpochDate(lastHeardEpoch) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.standardCardFill)
            .border(1.dp, colors.standardCardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "First Heard",
                style = MaterialTheme.typography.labelSmall,
                color = colors.labelTertiary
            )
            Text(
                text = firstText,
                style = MaterialTheme.typography.bodyMedium.withTabularNumerals(),
                color = colors.labelPrimary
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Last Heard",
                style = MaterialTheme.typography.labelSmall,
                color = colors.labelTertiary
            )
            Text(
                text = lastText,
                style = MaterialTheme.typography.bodyMedium.withTabularNumerals(),
                color = colors.labelPrimary
            )
        }
    }
}

private fun formatEpochDate(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return "—"
    return runCatching {
        DrillDownDateFormatter.format(
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        )
    }.getOrDefault("—")
}
