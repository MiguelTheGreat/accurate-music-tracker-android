package com.musictracker.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.musictracker.app.MainActivity
import com.musictracker.app.MusicTrackerApplication
import com.musictracker.app.R
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.StatsTimeframe
import com.musictracker.app.domain.normalization.MusicMetadataNormalizer
import com.musictracker.app.ui.common.computeTimeframeSinceEpochMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Lightweight Liquid Glass-styled Jetpack Glance home screen widget for Collecto.
 *
 * Displays:
 * - "Now Tracking" when an allowlisted music session is actively playing (non-debouncing)
 * - Current weekly #1 artist & 7-day listening summary when idle
 * - Friendly empty prompt when no listening history exists yet
 *
 * Strictly zero network I/O on widget render: only reads local cached WebP artwork downscaled
 * to <= 128x128 px to stay well under RemoteViews Binder transaction limits.
 */
class CollectoGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadSnapshot(context.applicationContext)
        provideContent {
            CollectoWidgetContent(
                state = snapshot.state,
                artworkBitmap = snapshot.artworkBitmap
            )
        }
    }

    private data class WidgetSnapshot(
        val state: CollectoWidgetState,
        val artworkBitmap: Bitmap?
    )

    private suspend fun loadSnapshot(appContext: Context): WidgetSnapshot {
        val appContainer = (appContext as? MusicTrackerApplication)?.appContainer
            ?: return WidgetSnapshot(CollectoWidgetState.Empty, null)

        return withContext(appContainer.ioDispatcher) {
            runCatching {
                val live = appContainer.playSessionEngine.nowTracking.value
                if (live != null && live.isPlaying && !live.isDebouncing) {
                    val dedupKey = MusicMetadataNormalizer.buildDedupKey(
                        normalizedArtist = live.normalizedArtist.ifBlank { live.rawArtist },
                        normalizedTitle = live.normalizedTitle.ifBlank { live.rawTitle }
                    )
                    val artFile = appContainer.artworkRepository.getCachedTrackArtworkFile(dedupKey)
                    val state = CollectoWidgetStateMapper.map(
                        nowTracking = live,
                        topArtist = null,
                        weeklySummary = ListeningSummary(),
                        artworkPath = artFile?.absolutePath
                    )
                    return@runCatching WidgetSnapshot(
                        state = state,
                        artworkBitmap = decodeWidgetThumbnail(artFile)
                    )
                }

                val nowMs = appContainer.clock.currentTimeMillis()
                val cutoffMs = computeTimeframeSinceEpochMs(StatsTimeframe.SEVEN_DAYS, nowMs)
                val topArtist = appContainer.listeningRepository
                    .observeTopArtists(sinceEpochMs = cutoffMs, limit = 1)
                    .first()
                    .firstOrNull()
                val weeklySummary = appContainer.listeningRepository
                    .observeSummary(sinceEpochMs = cutoffMs)
                    .first()

                val artFile = topArtist?.artist
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { artistKey ->
                        appContainer.artworkRepository.getCachedArtistArtworkFile(artistKey)
                    }

                val state = CollectoWidgetStateMapper.map(
                    nowTracking = live,
                    topArtist = topArtist,
                    weeklySummary = weeklySummary,
                    artworkPath = artFile?.absolutePath
                )
                WidgetSnapshot(
                    state = state,
                    artworkBitmap = decodeWidgetThumbnail(artFile)
                )
            }.getOrElse {
                WidgetSnapshot(CollectoWidgetState.Empty, null)
            }
        }
    }

    private fun decodeWidgetThumbnail(file: File?): Bitmap? {
        if (file == null || !file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@runCatching null

            var sampleSize = 1
            while ((width / sampleSize) > MAX_ARTWORK_PX || (height / sampleSize) > MAX_ARTWORK_PX) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        }.getOrNull()
    }

    companion object {
        private const val MAX_ARTWORK_PX = 128
    }
}

private val WidgetPrimaryTextColor = ColorProvider(
    day = Color(0xFF0F172A),
    night = Color(0xFFF8FAFC)
)

private val WidgetSecondaryTextColor = ColorProvider(
    day = Color(0xFF475569),
    night = Color(0xFFCBD5E1)
)

private val WidgetAccentColor = ColorProvider(
    day = Color(0xFF0066CC),
    night = Color(0xFF60A5FA)
)

@Composable
private fun CollectoWidgetContent(
    state: CollectoWidgetState,
    artworkBitmap: Bitmap?
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_liquid_glass_bg))
            .cornerRadius(24.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.CenterStart
    ) {
        when (state) {
            is CollectoWidgetState.NowTracking -> NowTrackingContent(state, artworkBitmap)
            is CollectoWidgetState.WeeklyTopArtist -> WeeklyTopArtistContent(state, artworkBitmap)
            CollectoWidgetState.Empty -> EmptyWidgetContent()
        }
    }
}

@Composable
private fun NowTrackingContent(
    state: CollectoWidgetState.NowTracking,
    artworkBitmap: Bitmap?
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            Image(
                provider = ImageProvider(artworkBitmap),
                contentDescription = state.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier
                    .size(48.dp)
                    .cornerRadius(12.dp)
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "NOW TRACKING • ${state.sourceApp.uppercase(Locale.US)}",
                style = TextStyle(
                    color = WidgetAccentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = state.title,
                style = TextStyle(
                    color = WidgetPrimaryTextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(1.dp))
            Text(
                text = state.artist,
                style = TextStyle(
                    color = WidgetSecondaryTextColor,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WeeklyTopArtistContent(
    state: CollectoWidgetState.WeeklyTopArtist,
    artworkBitmap: Bitmap?
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            Image(
                provider = ImageProvider(artworkBitmap),
                contentDescription = state.artistName,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier
                    .size(48.dp)
                    .cornerRadius(24.dp)
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "COLLECTO • THIS WEEK",
                style = TextStyle(
                    color = WidgetAccentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = "#1 ${state.artistName}",
                style = TextStyle(
                    color = WidgetPrimaryTextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(1.dp))
            val playsLabel = if (state.weeklyPlayCount == 1) "1 play" else "${state.weeklyPlayCount} plays"
            Text(
                text = "${state.weeklyDurationFormatted} listened • $playsLabel",
                style = TextStyle(
                    color = WidgetSecondaryTextColor,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EmptyWidgetContent() {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = "COLLECTO",
            style = TextStyle(
                color = WidgetAccentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = "Ready to track",
            style = TextStyle(
                color = WidgetPrimaryTextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
        Spacer(modifier = GlanceModifier.height(1.dp))
        Text(
            text = "Play music to see your live session & weekly #1 artist",
            style = TextStyle(
                color = WidgetSecondaryTextColor,
                fontSize = 12.sp
            ),
            maxLines = 2
        )
    }
}
