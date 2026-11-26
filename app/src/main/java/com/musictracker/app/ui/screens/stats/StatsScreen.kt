package com.musictracker.app.ui.screens.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.ArtworkThumbnail
import com.musictracker.app.ui.components.content.EmptyStateView
import com.musictracker.app.ui.components.content.StandardMaterialCard
import com.musictracker.app.ui.components.content.deterministicArtworkColors
import com.musictracker.app.ui.components.glass.LiquidGlassSegmentedControl
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import com.musictracker.app.ui.theme.withTabularNumerals

@Composable
fun StatsRoute(
    viewModel: StatsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StatsScreenContent(
        uiState = uiState,
        onSelectTimeframe = viewModel::selectTimeframe,
        modifier = modifier
    )
}

@Composable
fun StatsScreenContent(
    uiState: StatsUiState,
    onSelectTimeframe: (StatsTimeframe) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val timeframeOptions = remember {
        listOf(
            StatsTimeframe.TODAY,
            StatsTimeframe.SEVEN_DAYS,
            StatsTimeframe.THIRTY_DAYS,
            StatsTimeframe.ALL_TIME
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.contentBackground),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBarTop + 16.dp,
            bottom = navBarBottom + 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "stats_header") {
            Text(
                text = "Listening Insights",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.labelPrimary
            )
        }

        item(key = "stats_timeframe") {
            LiquidGlassSegmentedControl(
                options = timeframeOptions,
                selectedOption = uiState.selectedTimeframe,
                onOptionSelected = onSelectTimeframe,
                labelProvider = ::timeframeLabel,
                testTagProvider = TestTags::statsTimeframe,
                modifier = Modifier.testTag(TestTags.STATS_TIMEFRAME_CONTROL)
            )
        }

        item(key = "stats_overview") {
            StatsOverviewStrip(summary = uiState.summary)
        }

        val isEmpty = !uiState.isLoading && uiState.topTracks.isEmpty() && uiState.topArtists.isEmpty()
        if (isEmpty) {
            item(key = "stats_empty") {
                EmptyStateView(
                    title = "No Data for ${timeframeEmptyTitle(uiState.selectedTimeframe)}",
                    subtitle = "Listen to music in an allowed player to build your ranked listening insights."
                )
            }
        } else {
            if (uiState.topTracks.isNotEmpty()) {
                item(key = "stats_top_tracks_section") {
                    Text(
                        text = "Top Tracks",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.labelPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TopTracksCard(
                        tracks = uiState.topTracks,
                        modifier = Modifier.testTag(TestTags.STATS_TOP_TRACKS_LIST)
                    )
                }
            }

            if (uiState.topArtists.isNotEmpty()) {
                item(key = "stats_top_artists_section") {
                    Text(
                        text = "Top Artists",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.labelPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TopArtistsCard(
                        artists = uiState.topArtists,
                        modifier = Modifier.testTag(TestTags.STATS_TOP_ARTISTS_LIST)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsOverviewStrip(
    summary: ListeningSummary,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    StandardMaterialCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverviewMetric(
                label = "Actual Time",
                value = formatSummaryDuration(summary.totalListenedMs)
            )
            OverviewMetric(
                label = "Total Plays",
                value = summary.playCount.toString()
            )
            OverviewMetric(
                label = "Skip Rate",
                value = formatSkipRate(summary.playCount, summary.skipRate)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Ranked strictly by actual time listened",
            style = MaterialTheme.typography.bodySmall,
            color = colors.labelTertiary
        )
    }
}

@Composable
private fun OverviewMetric(
    label: String,
    value: String
) {
    val colors = LiquidGlassTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.withTabularNumerals(),
            color = colors.labelPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.labelSecondary
        )
    }
}

@Composable
private fun TopTracksCard(
    tracks: List<TopTrackStat>,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val maxListenedMs = remember(tracks) {
        tracks.maxOfOrNull { it.totalListenedMs }?.coerceAtLeast(1L) ?: 1L
    }

    StandardMaterialCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        tracks.forEachIndexed { index, track ->
            val ratio = (track.totalListenedMs.toFloat() / maxListenedMs.toFloat())
                .coerceIn(0.04f, 1f)
            TopTrackRow(
                rank = index + 1,
                track = track,
                relativeRatio = ratio,
                modifier = Modifier.testTag(TestTags.statsTrackItem(track.trackId))
            )
            if (index < tracks.lastIndex) {
                HorizontalDivider(
                    color = colors.standardCardBorder,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun TopTrackRow(
    rank: Int,
    track: TopTrackStat,
    relativeRatio: Float,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val animatedRatio by animateFloatAsState(
        targetValue = relativeRatio,
        animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
        label = "track_bar_ratio"
    )
    val playLabel = if (track.playCount == 1) "1 play" else "${track.playCount} plays"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelLarge.withTabularNumerals(),
                color = colors.labelTertiary,
                modifier = Modifier.width(28.dp)
            )
            ArtworkThumbnail(
                artworkUri = track.artworkUri,
                fallbackSeed = "${track.title}|${track.artist}",
                contentDescription = track.title,
                size = 44.dp,
                cornerRadius = LiquidGlassShapes.ContentGroupInnerRadius
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSummaryDuration(track.totalListenedMs),
                    style = MaterialTheme.typography.titleSmall.withTabularNumerals(),
                    color = colors.labelPrimary
                )
                Text(
                    text = playLabel,
                    style = MaterialTheme.typography.bodySmall.withTabularNumerals(),
                    color = colors.labelTertiary
                )
            }
        }

        ProportionalBar(
            ratio = animatedRatio,
            accentColor = colors.accentBlue
        )
    }
}

@Composable
private fun TopArtistsCard(
    artists: List<TopArtistStat>,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val maxListenedMs = remember(artists) {
        artists.maxOfOrNull { it.totalListenedMs }?.coerceAtLeast(1L) ?: 1L
    }

    StandardMaterialCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        artists.forEachIndexed { index, artist ->
            val ratio = (artist.totalListenedMs.toFloat() / maxListenedMs.toFloat())
                .coerceIn(0.04f, 1f)
            TopArtistRow(
                rank = index + 1,
                artist = artist,
                relativeRatio = ratio,
                modifier = Modifier.testTag(TestTags.statsArtistItem(index))
            )
            if (index < artists.lastIndex) {
                HorizontalDivider(
                    color = colors.standardCardBorder,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun TopArtistRow(
    rank: Int,
    artist: TopArtistStat,
    relativeRatio: Float,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val animatedRatio by animateFloatAsState(
        targetValue = relativeRatio,
        animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
        label = "artist_bar_ratio"
    )
    val avatarColors = remember(artist.artist) { deterministicArtworkColors(artist.artist) }
    val monogram = remember(artist.artist) {
        artist.artist.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
    val playLabel = if (artist.playCount == 1) "1 play" else "${artist.playCount} plays"
    val trackLabel = if (artist.uniqueTrackCount == 1) "1 track" else "${artist.uniqueTrackCount} tracks"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelLarge.withTabularNumerals(),
                color = colors.labelTertiary,
                modifier = Modifier.width(28.dp)
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(avatarColors.first, avatarColors.second))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = monogram,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = artist.artist,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$playLabel · $trackLabel",
                    style = MaterialTheme.typography.bodySmall.withTabularNumerals(),
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatSummaryDuration(artist.totalListenedMs),
                style = MaterialTheme.typography.titleSmall.withTabularNumerals(),
                color = colors.labelPrimary
            )
        }

        ProportionalBar(
            ratio = animatedRatio,
            accentColor = colors.accentGreen
        )
    }
}

@Composable
private fun ProportionalBar(
    ratio: Float,
    accentColor: Color
) {
    val colors = LiquidGlassTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(colors.controlTrackFill)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0.04f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(accentColor)
        )
    }
}

private fun timeframeLabel(timeframe: StatsTimeframe): String = when (timeframe) {
    StatsTimeframe.TODAY -> "Today"
    StatsTimeframe.SEVEN_DAYS -> "7 Days"
    StatsTimeframe.THIRTY_DAYS -> "30 Days"
    StatsTimeframe.ALL_TIME -> "All Time"
}

private fun timeframeEmptyTitle(timeframe: StatsTimeframe): String = when (timeframe) {
    StatsTimeframe.TODAY -> "Today"
    StatsTimeframe.SEVEN_DAYS -> "Last 7 Days"
    StatsTimeframe.THIRTY_DAYS -> "Last 30 Days"
    StatsTimeframe.ALL_TIME -> "All Time"
}
