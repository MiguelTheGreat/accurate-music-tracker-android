package com.musictracker.app.ui.screens.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.RankDelta
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.model.SummaryDelta
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.blend.BlendSheet
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.ArtistAvatar
import com.musictracker.app.ui.components.content.ArtworkThumbnail
import com.musictracker.app.ui.components.content.EmptyStateView
import com.musictracker.app.ui.components.content.StandardMaterialCard
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassSegmentedControl
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.share.ShareCardMode
import com.musictracker.app.ui.share.ShareCardSheet
import com.musictracker.app.ui.share.ShareSheetInitialConfig
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import com.musictracker.app.ui.theme.withTabularNumerals
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StatsRoute(
    viewModel: StatsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var shareSheetConfig by remember { mutableStateOf<ShareSheetInitialConfig?>(null) }
    var showShareActionSheet by remember { mutableStateOf(false) }
    var showBlendSheet by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    StatsScreenContent(
        uiState = uiState,
        onSelectTimeframe = viewModel::selectTimeframe,
        onTrackClick = viewModel::openTrackDetail,
        onArtistClick = viewModel::openArtistDetail,
        onShareClick = { showShareActionSheet = true },
        modifier = modifier
    )

    if (showShareActionSheet) {
        ShareActionSheet(
            onShareStatsCard = {
                showShareActionSheet = false
                shareSheetConfig = ShareSheetInitialConfig(
                    mode = ShareCardMode.LISTENING_SUMMARY
                )
            },
            onBlendWithFriend = {
                showShareActionSheet = false
                showBlendSheet = true
            },
            onDismiss = { showShareActionSheet = false }
        )
    }

    if (showBlendSheet) {
        BlendSheet(
            topArtists = uiState.blendTopArtists,
            topTracks = uiState.blendTopTracks,
            onDismiss = { showBlendSheet = false }
        )
    }

    val drillDownTarget = uiState.drillDownTarget
    if (drillDownTarget != null) {
        DrillDownSheet(
            target = drillDownTarget,
            timeframe = uiState.selectedTimeframe,
            trackDetail = uiState.selectedTrackDetail,
            artistDetail = uiState.selectedArtistDetail,
            artistTopTracks = uiState.selectedArtistTopTracks,
            artistArtworkPaths = uiState.artistArtworkPaths,
            trackArtworkPaths = uiState.trackArtworkPaths,
            firstListenEpoch = uiState.selectedDrillDownFirstListenEpoch,
            discoveryTag = uiState.selectedDrillDownDiscoveryTag,
            onShareTrack = { track ->
                viewModel.dismissDrillDown()
                shareSheetConfig = ShareSheetInitialConfig(
                    mode = ShareCardMode.TOP_MUSIC,
                    featuredTrack = track
                )
            },
            onShareArtist = { artist, artistTracks ->
                viewModel.dismissDrillDown()
                shareSheetConfig = ShareSheetInitialConfig(
                    mode = ShareCardMode.TOP_ARTIST,
                    featuredArtist = artist,
                    artistTopTracks = artistTracks
                )
            },
            onDismiss = viewModel::dismissDrillDown
        )
    }

    val activeShareConfig = shareSheetConfig
    if (activeShareConfig != null) {
        ShareCardSheet(
            initialConfig = activeShareConfig,
            timeframe = uiState.selectedTimeframe,
            summary = uiState.summary,
            topTracks = uiState.topTracks,
            topArtists = uiState.topArtists,
            streakDays = uiState.insights.streak.currentStreakDays,
            peakHour = uiState.insights.rhythm.peakHour,
            onSelectTimeframe = viewModel::selectTimeframe,
            onDismiss = { shareSheetConfig = null },
            artworkResolver = viewModel.artworkResolver
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareActionSheet(
    onShareStatsCard: () -> Unit,
    onBlendWithFriend: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LiquidGlassTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        LiquidGlassSheet(
            isExpanded = sheetState.currentValue == SheetValue.Expanded,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Share & Social",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary
                )
                LiquidGlassButton(
                    onClick = onShareStatsCard,
                    variant = LiquidGlassVariant.Regular,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share Stats Card",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                LiquidGlassButton(
                    onClick = onBlendWithFriend,
                    variant = LiquidGlassVariant.Prominent,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Blend with a Friend",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun StatsScreenContent(
    uiState: StatsUiState,
    onSelectTimeframe: (StatsTimeframe) -> Unit,
    modifier: Modifier = Modifier,
    onTrackClick: (TopTrackStat) -> Unit = {},
    onArtistClick: (TopArtistStat) -> Unit = {},
    onShareClick: () -> Unit = {}
) {
    val colors = LiquidGlassTheme.colors
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    val timeframeOptions = remember {
        listOf(
            StatsTimeframe.TODAY,
            StatsTimeframe.SEVEN_DAYS,
            StatsTimeframe.THIRTY_DAYS,
            StatsTimeframe.ALL_TIME
        )
    }

    val topFiveTracks = remember(uiState.topTracks) { uiState.topTracks.take(5) }
    val topFiveArtists = remember(uiState.topArtists) { uiState.topArtists.take(5) }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Listening Insights",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.labelPrimary
                )
                LiquidGlassButton(
                    onClick = onShareClick,
                    variant = LiquidGlassVariant.Regular,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share listening insights",
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
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
            StatsOverviewStrip(
                summary = uiState.summary,
                summaryDelta = uiState.summaryDelta
            )
        }

        val isEmpty = !uiState.isLoading && topFiveTracks.isEmpty() && topFiveArtists.isEmpty()
        if (isEmpty) {
            item(key = "stats_empty") {
                EmptyStateView(
                    title = "No Data for ${timeframeEmptyTitle(uiState.selectedTimeframe)}",
                    subtitle = "Listen to music in an allowed player to build your ranked listening insights."
                )
            }
        } else {
            item(key = "stats_ranked_pager") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tracksColor by animateColorAsState(
                                targetValue = if (pagerState.currentPage == 0) colors.labelPrimary else colors.labelTertiary,
                                label = "stats_tab_tracks_color"
                            )
                            val artistsColor by animateColorAsState(
                                targetValue = if (pagerState.currentPage == 1) colors.labelPrimary else colors.labelTertiary,
                                label = "stats_tab_artists_color"
                            )
                            Text(
                                text = "Top 5 Tracks",
                                style = MaterialTheme.typography.titleLarge,
                                color = tracksColor,
                                modifier = Modifier
                                    .clip(LiquidGlassShapes.Capsule)
                                    .clickable {
                                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                    }
                                    .padding(vertical = 2.dp)
                            )
                            Text(
                                text = "Top 5 Artists",
                                style = MaterialTheme.typography.titleLarge,
                                color = artistsColor,
                                modifier = Modifier
                                    .clip(LiquidGlassShapes.Capsule)
                                    .clickable {
                                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }

                        // Page indicator dots
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(2) { page ->
                                val isCurrent = pagerState.currentPage == page
                                val dotWidth by animateDpAsState(
                                    targetValue = if (isCurrent) 18.dp else 6.dp,
                                    label = "stats_dot_width_$page"
                                )
                                Box(
                                    modifier = Modifier
                                        .height(6.dp)
                                        .width(dotWidth)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrent) colors.accentBlue else colors.labelTertiary.copy(alpha = 0.4f)
                                        )
                                )
                            }
                        }
                    }

                    Text(
                        text = if (pagerState.currentPage == 0) {
                            "Swipe for Top 5 Artists"
                        } else {
                            "Swipe back for Top 5 Tracks"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.labelTertiary
                    )

                    HorizontalPager(
                        state = pagerState,
                        pageSpacing = 12.dp,
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        when (page) {
                            0 -> {
                                if (topFiveTracks.isNotEmpty()) {
                                    TopTracksCard(
                                        tracks = topFiveTracks,
                                        trackArtworkPaths = uiState.trackArtworkPaths,
                                        rankDeltas = uiState.trackRankDeltas,
                                        onTrackClick = onTrackClick,
                                        modifier = Modifier.testTag(TestTags.STATS_TOP_TRACKS_LIST)
                                    )
                                } else {
                                    EmptyStateView(
                                        title = "No Top Tracks Yet",
                                        subtitle = "Keep listening to populate your Top 5 Tracks."
                                    )
                                }
                            }
                            else -> {
                                if (topFiveArtists.isNotEmpty()) {
                                    TopArtistsCard(
                                        artists = topFiveArtists,
                                        artistArtworkPaths = uiState.artistArtworkPaths,
                                        rankDeltas = uiState.artistRankDeltas,
                                        onArtistClick = onArtistClick,
                                        modifier = Modifier.testTag(TestTags.STATS_TOP_ARTISTS_LIST)
                                    )
                                } else {
                                    EmptyStateView(
                                        title = "No Top Artists Yet",
                                        subtitle = "Keep listening to populate your Top 5 Artists."
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "stats_listening_rhythm") {
                ListeningRhythmCard(rhythm = uiState.insights.rhythm)
            }
        }

        if (uiState.insights.milestones.isNotEmpty() || uiState.insights.streak.activeDaysCount > 0) {
            item(key = "stats_streaks_milestones") {
                StreaksAndMilestonesSection(
                    streak = uiState.insights.streak,
                    milestones = uiState.insights.milestones
                )
            }
        }
    }
}

@Composable
private fun StatsOverviewStrip(
    summary: ListeningSummary,
    summaryDelta: SummaryDelta? = null,
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
            verticalAlignment = Alignment.Top
        ) {
            OverviewMetric(
                label = "Actual Time",
                value = formatSummaryDuration(summary.totalListenedMs),
                deltaText = summaryDelta?.listenedMsPercentChange?.let(::formatSignedPercent),
                deltaColor = deltaColorForPositiveGood(summaryDelta?.listenedMsPercentChange)
            )
            OverviewMetric(
                label = "Total Plays",
                value = summary.playCount.toString(),
                deltaText = summaryDelta?.playCountPercentChange?.let(::formatSignedPercent),
                deltaColor = deltaColorForPositiveGood(summaryDelta?.playCountPercentChange)
            )
            OverviewMetric(
                label = "Skip Rate",
                value = formatSkipRate(summary.playCount, summary.skipRate),
                deltaText = summaryDelta?.skipRatePointChange?.let(::formatSignedPoints),
                deltaColor = deltaColorForSkipRate(summaryDelta?.skipRatePointChange)
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
    value: String,
    deltaText: String? = null,
    deltaColor: Color = LiquidGlassTheme.colors.labelTertiary
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
        if (deltaText != null) {
            Text(
                text = deltaText,
                style = MaterialTheme.typography.labelSmall.withTabularNumerals()
                    .copy(fontWeight = FontWeight.SemiBold),
                color = deltaColor
            )
        }
    }
}

private fun formatSignedPercent(delta: Int): String =
    if (delta > 0) "+$delta%" else "$delta%"

private fun formatSignedPoints(delta: Int): String =
    if (delta > 0) "+${delta}pt" else "${delta}pt"

@Composable
private fun deltaColorForPositiveGood(delta: Int?): Color {
    val colors = LiquidGlassTheme.colors
    return when {
        delta == null || delta == 0 -> colors.labelTertiary
        delta > 0 -> colors.accentGreen
        else -> colors.accentRed
    }
}

@Composable
private fun deltaColorForSkipRate(delta: Int?): Color {
    val colors = LiquidGlassTheme.colors
    return when {
        delta == null || delta == 0 -> colors.labelTertiary
        delta < 0 -> colors.accentGreen
        else -> colors.accentAmber
    }
}

@Composable
private fun TopTracksCard(
    tracks: List<TopTrackStat>,
    trackArtworkPaths: Map<Long, String> = emptyMap(),
    rankDeltas: Map<Long, RankDelta> = emptyMap(),
    onTrackClick: (TopTrackStat) -> Unit = {},
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
                rankDelta = rankDeltas[track.trackId],
                track = track,
                resolvedArtworkPath = trackArtworkPaths[track.trackId],
                relativeRatio = ratio,
                onClick = { onTrackClick(track) },
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
    rankDelta: RankDelta?,
    track: TopTrackStat,
    resolvedArtworkPath: String?,
    relativeRatio: Float,
    onClick: () -> Unit,
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RankColumn(rank = rank, rankDelta = rankDelta)
            ArtworkThumbnail(
                artworkUri = resolvedArtworkPath ?: track.artworkUri,
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
    artistArtworkPaths: Map<String, String> = emptyMap(),
    rankDeltas: Map<String, RankDelta> = emptyMap(),
    onArtistClick: (TopArtistStat) -> Unit = {},
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
            val normalizedKey = artist.artist.trim().lowercase(Locale.ROOT)
            TopArtistRow(
                rank = index + 1,
                rankDelta = rankDeltas[normalizedKey],
                artist = artist,
                resolvedArtworkPath = artistArtworkPaths[artist.artist],
                relativeRatio = ratio,
                onClick = { onArtistClick(artist) },
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
    rankDelta: RankDelta?,
    artist: TopArtistStat,
    resolvedArtworkPath: String?,
    relativeRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val animatedRatio by animateFloatAsState(
        targetValue = relativeRatio,
        animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
        label = "artist_bar_ratio"
    )
    val playLabel = if (artist.playCount == 1) "1 play" else "${artist.playCount} plays"
    val trackLabel = if (artist.uniqueTrackCount == 1) "1 track" else "${artist.uniqueTrackCount} tracks"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RankColumn(rank = rank, rankDelta = rankDelta)
            ArtistAvatar(
                artworkPath = resolvedArtworkPath,
                artistName = artist.artist,
                size = 42.dp
            )
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
private fun RankColumn(
    rank: Int,
    rankDelta: RankDelta?
) {
    val colors = LiquidGlassTheme.colors
    Column(
        modifier = Modifier.width(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.labelLarge.withTabularNumerals(),
            color = colors.labelTertiary
        )
        if (rankDelta != null) {
            when (rankDelta) {
                is RankDelta.Up -> Text(
                    text = "▲${rankDelta.places}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumerals()
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentGreen,
                    maxLines = 1
                )
                is RankDelta.Down -> Text(
                    text = "▼${rankDelta.places}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumerals()
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentRed,
                    maxLines = 1
                )
                RankDelta.Same -> Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.labelTertiary,
                    maxLines = 1
                )
                RankDelta.New -> Box(
                    modifier = Modifier
                        .clip(LiquidGlassShapes.Capsule)
                        .background(colors.accentBlue.copy(alpha = 0.16f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "NEW",
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontWeight = FontWeight.Bold),
                        color = colors.accentBlue,
                        maxLines = 1
                    )
                }
            }
        }
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
