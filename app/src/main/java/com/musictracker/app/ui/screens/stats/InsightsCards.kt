package com.musictracker.app.ui.screens.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.domain.model.CollectorMilestone
import com.musictracker.app.domain.model.ListeningRhythm
import com.musictracker.app.domain.model.ListeningStreak
import com.musictracker.app.domain.model.MilestoneType
import com.musictracker.app.ui.components.content.StandardMaterialCard
import com.musictracker.app.ui.share.formatPeakHourLabel
import com.musictracker.app.ui.theme.LiquidGlassMotion
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import com.musictracker.app.ui.theme.withTabularNumerals
import java.util.Locale

/**
 * Compact 24-hour Listening Rhythm ("Peak Hours") bar chart card for the Stats screen.
 */
@Composable
fun ListeningRhythmCard(
    rhythm: ListeningRhythm,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accessibility = LiquidGlassTheme.accessibility
    val maxMs = remember(rhythm) {
        rhythm.peakListenedMs.coerceAtLeast(1L)
    }
    val peakBadgeText = remember(rhythm.peakHour) {
        rhythm.peakHour?.let { "Peak: ${formatPeakHourLabel(it)}" } ?: "No data"
    }

    StandardMaterialCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Listening Rhythm",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.labelPrimary
                )
                Text(
                    text = "Peak hours of the day",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.labelTertiary
                )
            }
            Box(
                modifier = Modifier
                    .clip(LiquidGlassShapes.Capsule)
                    .background(
                        if (rhythm.hasData) colors.accentBlue.copy(alpha = 0.16f) else colors.controlTrackFill
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = peakBadgeText,
                    style = MaterialTheme.typography.labelMedium.withTabularNumerals(),
                    color = if (rhythm.hasData) colors.accentBlue else colors.labelTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 24-bar chart
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            rhythm.buckets.forEach { bucket ->
                val targetFraction = if (rhythm.hasData && bucket.totalListenedMs > 0L) {
                    (bucket.totalListenedMs.toFloat() / maxMs.toFloat()).coerceIn(0.10f, 1f)
                } else {
                    0.06f
                }
                val animatedFraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = LiquidGlassMotion.fluidSpec(accessibility.reduceMotion),
                    label = "rhythm_bar_${bucket.hour}"
                )
                val isPeak = rhythm.peakHour == bucket.hour && bucket.totalListenedMs > 0L
                val barColor = when {
                    isPeak -> colors.accentBlue
                    bucket.totalListenedMs > 0L -> colors.accentBlue.copy(alpha = 0.42f)
                    else -> colors.controlTrackFill
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(animatedFraction)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(barColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Minimal x-axis labels (12a, 6a, 12p, 6p)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("12a", "6a", "12p", "6p").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.withTabularNumerals(),
                    color = colors.labelTertiary
                )
            }
        }
    }
}

/**
 * Daily Listening Streaks & Unlockable Collector Milestones section for the Stats screen.
 */
@Composable
fun StreaksAndMilestonesSection(
    streak: ListeningStreak,
    milestones: List<CollectorMilestone>,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val unlockedCount = remember(milestones) { milestones.count { it.unlocked } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Streak summary card
        StandardMaterialCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (streak.currentStreakDays > 0) {
                                colors.accentAmber.copy(alpha = 0.18f)
                            } else {
                                colors.controlTrackFill
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalFireDepartment,
                        contentDescription = null,
                        tint = if (streak.currentStreakDays > 0) colors.accentAmber else colors.labelTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val streakHeadline = when (streak.currentStreakDays) {
                        0 -> "No active streak"
                        1 -> "1-Day Listening Streak"
                        else -> "${streak.currentStreakDays}-Day Listening Streak"
                    }
                    Text(
                        text = streakHeadline,
                        style = MaterialTheme.typography.titleMedium.withTabularNumerals(),
                        color = colors.labelPrimary
                    )
                    val streakSub = when {
                        streak.isTodayActive -> "Active today · Longest: ${streak.longestStreakDays}d"
                        streak.currentStreakDays > 0 -> "Listen today to extend · Longest: ${streak.longestStreakDays}d"
                        streak.longestStreakDays > 0 -> "Longest streak: ${streak.longestStreakDays}d · ${streak.activeDaysCount} active days"
                        else -> "Listen for 30s+ a day to start your streak"
                    }
                    Text(
                        text = streakSub,
                        style = MaterialTheme.typography.bodySmall.withTabularNumerals(),
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Collector milestones badge strip
        if (milestones.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Collector Milestones",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.labelPrimary
                )
                Text(
                    text = "$unlockedCount / ${milestones.size} unlocked",
                    style = MaterialTheme.typography.labelMedium.withTabularNumerals(),
                    color = colors.labelTertiary
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                items(
                    items = milestones,
                    key = { it.type.name }
                ) { milestone ->
                    MilestoneBadgeCard(milestone = milestone)
                }
            }
        }
    }
}

@Composable
private fun MilestoneBadgeCard(
    milestone: CollectorMilestone,
    modifier: Modifier = Modifier
) {
    val colors = LiquidGlassTheme.colors
    val accentColor = milestoneAccentColor(milestone.type)
    val borderColor = if (milestone.unlocked) accentColor.copy(alpha = 0.55f) else colors.standardCardBorder

    Column(
        modifier = modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.standardCardFill)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (milestone.unlocked) accentColor.copy(alpha = 0.18f) else colors.controlTrackFill
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = milestoneIcon(milestone.type),
                    contentDescription = null,
                    tint = if (milestone.unlocked) accentColor else colors.labelTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (milestone.unlocked) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Unlocked",
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = milestone.type.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (milestone.unlocked) colors.labelPrimary else colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatMilestoneProgress(milestone),
                style = MaterialTheme.typography.labelSmall.withTabularNumerals(),
                color = if (milestone.unlocked) accentColor else colors.labelTertiary,
                maxLines = 1
            )
        }

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.controlTrackFill)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(milestone.progressFraction.coerceIn(0.04f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (milestone.unlocked) accentColor else colors.labelTertiary.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun milestoneAccentColor(type: MilestoneType): Color {
    val colors = LiquidGlassTheme.colors
    return when (type) {
        MilestoneType.FIRST_SPIN -> colors.accentBlue
        MilestoneType.STREAK_3, MilestoneType.STREAK_7, MilestoneType.STREAK_30 -> colors.accentAmber
        MilestoneType.WEEKLY_10H -> colors.accentGreen
        MilestoneType.ARTIST_100 -> colors.accentRed
        MilestoneType.DISCOVERY_50 -> colors.accentBlue
        MilestoneType.CENTURION_500 -> colors.accentGreen
    }
}

private fun milestoneIcon(type: MilestoneType): ImageVector = when (type) {
    MilestoneType.FIRST_SPIN -> Icons.Rounded.MusicNote
    MilestoneType.STREAK_3, MilestoneType.STREAK_7, MilestoneType.STREAK_30 -> Icons.Rounded.LocalFireDepartment
    MilestoneType.WEEKLY_10H -> Icons.Rounded.Schedule
    MilestoneType.ARTIST_100 -> Icons.Rounded.Favorite
    MilestoneType.DISCOVERY_50 -> Icons.Rounded.Explore
    MilestoneType.CENTURION_500 -> Icons.Rounded.EmojiEvents
}

private fun formatMilestoneProgress(milestone: CollectorMilestone): String {
    if (milestone.unlocked) return "Unlocked"
    return when (milestone.type) {
        MilestoneType.WEEKLY_10H -> {
            val hours = milestone.currentValue.toDouble() / 3_600_000.0
            String.format(Locale.US, "%.1f / 10h", hours)
        }
        MilestoneType.STREAK_3, MilestoneType.STREAK_7, MilestoneType.STREAK_30 ->
            "${milestone.currentValue} / ${milestone.targetValue}d"
        else ->
            "${milestone.currentValue} / ${milestone.targetValue}"
    }
}
