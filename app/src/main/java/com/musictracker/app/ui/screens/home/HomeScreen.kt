package com.musictracker.app.ui.screens.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.tracking.SystemPermissionHelper
import com.musictracker.app.ui.common.computeLiveListenedMs
import com.musictracker.app.ui.common.formatEventDuration
import com.musictracker.app.ui.common.formatLiveCounter
import com.musictracker.app.ui.common.formatRelativeTime
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSourceAppName
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.ArtworkThumbnail
import com.musictracker.app.ui.components.content.EmptyStateView
import com.musictracker.app.ui.components.content.StandardMaterialCard
import com.musictracker.app.ui.components.content.deterministicArtworkColors
import com.musictracker.app.ui.components.glass.LiquidGlassBanner
import com.musictracker.app.ui.components.glass.LiquidGlassSurface
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.components.glass.estimateColorLuminance
import com.musictracker.app.ui.navigation.TestTags
import com.musictracker.app.ui.theme.LiquidGlassMetrics
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import com.musictracker.app.ui.theme.withTabularNumerals
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    clock: TrackerClock,
    systemPermissionHelper: SystemPermissionHelper,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissionAndDay()
    }

    HomeScreenContent(
        uiState = uiState,
        clock = clock,
        onEnableNotificationAccess = {
            systemPermissionHelper.openNotificationListenerSettings(context)
        },
        onDismissPermissionBanner = viewModel::dismissPermissionBanner,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    clock: TrackerClock,
    onEnableNotificationAccess: () -> Unit,
    onDismissPermissionBanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val nowEpochMs = clock.currentTimeMillis()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.contentBackground)
            .testTag(TestTags.HOME_RECENT_LIST),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBarTop + 16.dp,
            bottom = navBarBottom + 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "home_header") {
            Text(
                text = "Music Tracker",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.labelPrimary
            )
        }

        if (uiState.showPermissionBanner) {
            item(key = "home_permission_banner") {
                LiquidGlassBanner(
                    title = "Notification Access Needed",
                    subtitle = "Enable local media session access to track true listening time.",
                    actionLabel = "Enable",
                    onActionClick = onEnableNotificationAccess,
                    onDismiss = onDismissPermissionBanner,
                    actionTestTag = TestTags.HOME_PERMISSION_ENABLE_BTN,
                    dismissContentDescription = "Dismiss notification access banner",
                    modifier = Modifier.testTag(TestTags.HOME_PERMISSION_BANNER)
                )
            }
        }

        item(key = "home_now_tracking") {
            SectionTitle(text = "Now Tracking")
            Spacer(modifier = Modifier.height(8.dp))
            NowTrackingGlassCard(
                nowTracking = uiState.nowTracking,
                clock = clock,
                modifier = Modifier.testTag(TestTags.HOME_NOW_TRACKING_CARD)
            )
        }

        item(key = "home_today_summary") {
            SectionTitle(text = "Today's Summary")
            Spacer(modifier = Modifier.height(8.dp))
            TodaySummaryBento(summary = uiState.todaySummary)
        }

        item(key = "home_recent_header") {
            SectionTitle(text = "Recent Listening")
        }

        if (uiState.recentHistory.isEmpty()) {
            item(key = "home_recent_empty") {
                EmptyStateView(
                    title = "No Listening History Yet",
                    subtitle = "Play music in YouTube Music or any allowed player to start logging accurate listening time."
                )
            }
        } else {
            items(
                items = uiState.recentHistory,
                key = { it.eventId }
            ) { item ->
                RecentListenRow(
                    item = item,
                    nowEpochMs = nowEpochMs,
                    modifier = Modifier.testTag(TestTags.homeRecentItem(item.eventId))
                )
            }
        }
    }
}

@Composable
private fun NowTrackingGlassCard(
    nowTracking: NowTrackingState?,
    clock: TrackerClock,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility

    if (nowTracking == null) {
        LiquidGlassSurface(
            modifier = modifier.fillMaxWidth(),
            variant = LiquidGlassVariant.Regular,
            shape = LiquidGlassShapes.HeroCard,
            contentPadding = PaddingValues(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(LiquidGlassShapes.HeroInner)
                        .background(colors.controlTrackFill),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Nothing Playing",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.labelPrimary
                    )
                    Text(
                        text = "Play music in YouTube Music or any allowed app to begin tracking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.labelSecondary
                    )
                }
            }
        }
        return
    }

    var liveListenedMs by remember(
        nowTracking.sourcePackage,
        nowTracking.normalizedTitle,
        nowTracking.normalizedArtist,
        nowTracking.accumulatedMs,
        nowTracking.isPlaying,
        nowTracking.segmentStartElapsedMs
    ) {
        mutableLongStateOf(computeLiveListenedMs(nowTracking, clock.elapsedRealtimeMs()))
    }

    LaunchedEffect(
        nowTracking.sourcePackage,
        nowTracking.normalizedTitle,
        nowTracking.normalizedArtist,
        nowTracking.accumulatedMs,
        nowTracking.isPlaying,
        nowTracking.segmentStartElapsedMs
    ) {
        liveListenedMs = computeLiveListenedMs(nowTracking, clock.elapsedRealtimeMs())
        if (nowTracking.isPlaying && nowTracking.segmentStartElapsedMs != null) {
            while (isActive) {
                delay(1_000L)
                liveListenedMs = computeLiveListenedMs(nowTracking, clock.elapsedRealtimeMs())
            }
        }
    }

    val artworkSeed = "${nowTracking.normalizedTitle}|${nowTracking.normalizedArtist}"
    val ambientColors = remember(artworkSeed) { deterministicArtworkColors(artworkSeed) }
    val avgLuminance = remember(ambientColors) {
        (estimateColorLuminance(ambientColors.first) + estimateColorLuminance(ambientColors.second)) / 2f
    }
    val isBrightMedia = avgLuminance > LiquidGlassMetrics.BrightMediaLuminanceThreshold
    val sourceAppName = remember(nowTracking.sourcePackage) {
        formatSourceAppName(nowTracking.sourcePackage)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(LiquidGlassShapes.HeroCard)
    ) {
        // Rich media ambient backdrop behind Clear Liquid Glass
        val backdropModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !accessibility.reduceTransparency) {
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(ambientColors.first, ambientColors.second)))
                .blur(18.dp)
        } else {
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(ambientColors.first, ambientColors.second)))
        }
        Box(modifier = backdropModifier)

        // Ensure dark contrast base over ambient artwork so Clear glass stays crisp
        if (!isBrightMedia && !accessibility.reduceTransparency) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.24f))
            )
        }

        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            variant = LiquidGlassVariant.Clear,
            shape = LiquidGlassShapes.HeroCard,
            isBrightMediaBehind = isBrightMedia,
            contentPadding = PaddingValues(LiquidGlassShapes.HeroPadding + 4.dp)
        ) {
            val foregroundPrimary = if (accessibility.reduceTransparency && !colors.isDark) {
                colors.labelPrimary
            } else {
                Color.White
            }
            val foregroundSecondary = if (accessibility.reduceTransparency && !colors.isDark) {
                colors.labelSecondary
            } else {
                Color.White.copy(alpha = 0.84f)
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ArtworkThumbnail(
                        artworkUri = nowTracking.artworkUri,
                        fallbackSeed = artworkSeed,
                        contentDescription = nowTracking.normalizedTitle,
                        size = 56.dp,
                        cornerRadius = LiquidGlassShapes.HeroInnerRadius
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = nowTracking.normalizedTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = foregroundPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = nowTracking.normalizedArtist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = foregroundSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Solid tinted source app pill (strictly NOT nested Liquid Glass)
                            Box(
                                modifier = Modifier
                                    .clip(LiquidGlassShapes.Capsule)
                                    .background(Color.Black.copy(alpha = 0.34f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = sourceAppName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }

                            // Solid Play/Pause status pill
                            val statusColor = if (nowTracking.isPlaying) colors.accentGreen else colors.accentAmber
                            val isLightReducedTransparency = accessibility.reduceTransparency && !colors.isDark
                            val statusPillBackground = if (isLightReducedTransparency) {
                                statusColor.copy(alpha = 0.16f)
                            } else {
                                statusColor.copy(alpha = 0.24f)
                            }
                            val statusPillTextColor = if (isLightReducedTransparency) {
                                colors.labelPrimary
                            } else {
                                Color.White
                            }
                            Row(
                                modifier = Modifier
                                    .clip(LiquidGlassShapes.Capsule)
                                    .background(statusPillBackground)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (nowTracking.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (nowTracking.isPlaying) "Playing" else "Paused",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusPillTextColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatLiveCounter(liveListenedMs),
                            style = MaterialTheme.typography.headlineSmall.withTabularNumerals(),
                            color = foregroundPrimary,
                            modifier = Modifier.testTag(TestTags.HOME_LIVE_COUNTER)
                        )
                        Text(
                            text = "Listened",
                            style = MaterialTheme.typography.labelSmall,
                            color = foregroundSecondary
                        )
                    }
                }

                if (nowTracking.durationMs > 0L) {
                    val progress = (liveListenedMs.toFloat() / nowTracking.durationMs.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = foregroundPrimary,
                        trackColor = foregroundPrimary.copy(alpha = 0.24f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TodaySummaryBento(
    summary: ListeningSummary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryStatTile(
            label = "Time Listened",
            value = formatSummaryDuration(summary.totalListenedMs),
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.HOME_STAT_TIME)
        )
        SummaryStatTile(
            label = "Tracks Played",
            value = summary.playCount.toString(),
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.HOME_STAT_PLAYS)
        )
        SummaryStatTile(
            label = "Skip Rate",
            value = formatSkipRate(summary.playCount, summary.skipRate),
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.HOME_STAT_SKIP_RATE)
        )
    }
}

@Composable
private fun SummaryStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    StandardMaterialCard(
        modifier = modifier.semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.withTabularNumerals(),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecentListenRow(
    item: RecentListenItem,
    nowEpochMs: Long,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val sourceApp = remember(item.sourcePackage) { formatSourceAppName(item.sourcePackage) }

    StandardMaterialCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = LiquidGlassMetrics.MinRowHeight - 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ArtworkThumbnail(
                artworkUri = item.artworkUri,
                fallbackSeed = "${item.title}|${item.artist}",
                contentDescription = item.title,
                size = 44.dp,
                cornerRadius = LiquidGlassShapes.ContentGroupInnerRadius
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.artist} · $sourceApp",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = formatEventDuration(item.actualListenedMs),
                    style = MaterialTheme.typography.labelLarge.withTabularNumerals(),
                    color = colors.labelPrimary,
                    maxLines = 1
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.wasSkipped) {
                        Box(
                            modifier = Modifier
                                .clip(LiquidGlassShapes.Capsule)
                                .background(colors.accentAmber.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Skipped",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accentAmber
                            )
                        }
                    }
                    Text(
                        text = formatRelativeTime(item.endedAtEpoch, nowEpochMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.labelTertiary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = LiquidGlassTheme.colors.labelPrimary,
        modifier = modifier
    )
}
