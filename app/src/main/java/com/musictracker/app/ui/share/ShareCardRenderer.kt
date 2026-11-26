package com.musictracker.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.common.formatSkipRate
import com.musictracker.app.ui.common.formatSummaryDuration
import com.musictracker.app.ui.components.content.deterministicArtworkColors
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic Android [Canvas] + [Bitmap] share card renderer for Collecto.
 *
 * Renders crisp high-resolution cards at 9:16 Story (`1080x1920`) and 4:5 Post (`1080x1350`),
 * extracting dynamic ambient backdrop colors via AndroidX [Palette] with deterministic gradient
 * fallbacks when artwork is unavailable.
 */
object ShareCardRenderer {

    private data class AmbientColors(
        val top: Int,
        val bottom: Int,
        val accent: Int
    )

    fun render(data: ShareCardData): Bitmap {
        val width = data.aspectRatio.widthPx
        val height = data.aspectRatio.heightPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val seed = when (data.mode) {
            ShareCardMode.TOP_ARTIST -> data.featuredArtist?.artist
                ?: data.topArtists.firstOrNull()?.artist
                ?: "Collecto Artist"
            ShareCardMode.TOP_MUSIC -> data.featuredTrack?.let { "${it.title}|${it.artist}" }
                ?: data.topTracks.firstOrNull()?.let { "${it.title}|${it.artist}" }
                ?: "Collecto Music"
            ShareCardMode.LISTENING_SUMMARY -> data.topArtists.firstOrNull()?.artist
                ?: data.topTracks.firstOrNull()?.title
                ?: "Collecto Summary"
        }

        val ambient = extractAmbientColors(data.primaryArtwork, seed)
        drawBackdrop(canvas, width, height, ambient)

        val isStory = data.aspectRatio == ShareAspectRatio.STORY_9_16
        val outerPadH = 72f
        val outerPadTop = if (isStory) 140f else 84f
        val outerPadBottom = if (isStory) 150f else 88f
        val cardRect = RectF(outerPadH, outerPadTop, width - outerPadH, height - outerPadBottom)

        drawGlassCard(canvas, cardRect, cornerRadius = 56f, fillAlpha = 148)

        val headerBottom = drawHeader(canvas, cardRect, shareTimeframeLabel(data.timeframe), ambient)
        val footerTop = cardRect.bottom - 78f
        val contentRect = RectF(
            cardRect.left + 56f,
            headerBottom + 36f,
            cardRect.right - 56f,
            footerTop - 28f
        )

        when (data.mode) {
            ShareCardMode.TOP_ARTIST -> drawTopArtistContent(canvas, contentRect, data, ambient, isStory)
            ShareCardMode.TOP_MUSIC -> drawTopMusicContent(canvas, contentRect, data, ambient, isStory)
            ShareCardMode.LISTENING_SUMMARY -> drawSummaryContent(canvas, contentRect, data, ambient, isStory)
        }

        drawFooter(canvas, cardRect, footerTop)
        return bitmap
    }

    private fun extractAmbientColors(artwork: Bitmap?, seed: String): AmbientColors {
        val fallbackPair = deterministicArtworkColors(seed)
        val fallbackStart = fallbackPair.first.toArgb()
        val fallbackEnd = fallbackPair.second.toArgb()

        if (artwork == null || artwork.isRecycled) {
            return AmbientColors(
                top = darkenColor(fallbackStart, 0.55f),
                bottom = darkenColor(fallbackEnd, 0.25f),
                accent = lightenColor(fallbackStart, 0.25f)
            )
        }

        val palette = runCatching { Palette.from(artwork).generate() }.getOrNull()
        val darkVibrant = palette?.darkVibrantSwatch?.rgb
        val darkMuted = palette?.darkMutedSwatch?.rgb
        val vibrant = palette?.vibrantSwatch?.rgb
        val dominant = palette?.dominantSwatch?.rgb

        val top = darkVibrant ?: darkMuted ?: dominant?.let { darkenColor(it, 0.50f) } ?: darkenColor(fallbackStart, 0.55f)
        val bottom = darkenColor(darkMuted ?: dominant ?: fallbackEnd, 0.28f)
        val accent = vibrant ?: palette?.lightVibrantSwatch?.rgb ?: lightenColor(fallbackStart, 0.28f)

        return AmbientColors(top = top, bottom = bottom, accent = accent)
    }

    private fun drawBackdrop(canvas: Canvas, width: Int, height: Int, ambient: AmbientColors) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(ambient.top, Color.rgb(10, 12, 18), ambient.bottom),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Upper ambient glow orb
        val orb1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.22f,
                height * 0.18f,
                width * 0.72f,
                intArrayOf(withAlpha(ambient.accent, 115), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orb1)

        // Lower ambient glow orb
        val orb2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.82f,
                height * 0.84f,
                width * 0.65f,
                intArrayOf(withAlpha(ambient.top, 130), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orb2)
    }

    private fun drawGlassCard(canvas: Canvas, rect: RectF, cornerRadius: Float, fillAlpha: Int) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.left,
                rect.bottom,
                intArrayOf(
                    Color.argb(fillAlpha, 26, 31, 44),
                    Color.argb((fillAlpha * 1.15f).toInt().coerceAtMost(235), 14, 17, 26)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

        // Top specular sheen
        val sheenRect = RectF(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.top + rect.height() * 0.22f)
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                sheenRect.left,
                sheenRect.top,
                sheenRect.left,
                sheenRect.bottom,
                intArrayOf(Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        val clipPath = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }
        canvas.clipPath(clipPath)
        canvas.drawRect(sheenRect, sheenPaint)
        canvas.restore()

        // Luminous Liquid Glass rim border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    Color.argb(150, 255, 255, 255),
                    Color.argb(55, 255, 255, 255),
                    Color.argb(95, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun drawHeader(
        canvas: Canvas,
        cardRect: RectF,
        timeframeLabel: String,
        ambient: AmbientColors
    ): Float {
        val headerY = cardRect.top + 88f
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        canvas.drawText("Collecto", cardRect.left + 56f, headerY, brandPaint)

        // Timeframe Liquid Glass pill on right
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textWidth = pillTextPaint.measureText(timeframeLabel)
        val pillH = 56f
        val pillW = textWidth + 52f
        val pillRect = RectF(
            cardRect.right - 56f - pillW,
            headerY - 40f,
            cardRect.right - 56f,
            headerY - 40f + pillH
        )
        drawGlassPill(canvas, pillRect, withAlpha(ambient.accent, 95))
        val textBaseline = pillRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(timeframeLabel, pillRect.left + 26f, textBaseline, pillTextPaint)

        return pillRect.bottom
    }

    private fun drawTopArtistContent(
        canvas: Canvas,
        bounds: RectF,
        data: ShareCardData,
        ambient: AmbientColors,
        isStory: Boolean
    ) {
        val artist = data.featuredArtist ?: data.topArtists.firstOrNull()
        val artistName = artist?.artist ?: "No Artist Yet"
        val centerX = bounds.centerX()

        drawEyebrow(canvas, "TOP ARTIST", centerX, bounds.top + 36f, ambient.accent, centered = true)

        val avatarDiameter = if (isStory) 390f else 280f
        val avatarTop = bounds.top + if (isStory) 90f else 62f
        val avatarRect = RectF(
            centerX - avatarDiameter / 2f,
            avatarTop,
            centerX + avatarDiameter / 2f,
            avatarTop + avatarDiameter
        )
        drawCircularArtwork(canvas, avatarRect, data.primaryArtwork, artistName)

        // Artist name
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (isStory) 66f else 54f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val nameY = avatarRect.bottom + if (isStory) 88f else 68f
        canvas.drawText(ellipsize(artistName, namePaint, bounds.width()), centerX, nameY, namePaint)

        // Stats pill row
        val timeText = formatSummaryDuration(artist?.totalListenedMs ?: 0L)
        val playsCount = artist?.playCount ?: 0
        val tracksCount = artist?.uniqueTrackCount ?: 0
        val statsRowTop = nameY + if (isStory) 44f else 30f
        drawThreeStatPills(
            canvas = canvas,
            bounds = RectF(bounds.left, statsRowTop, bounds.right, statsRowTop + 126f),
            items = listOf(
                timeText to "TIME LISTENED",
                playsCount.toString() to if (playsCount == 1) "PLAY" else "PLAYS",
                tracksCount.toString() to if (tracksCount == 1) "TRACK" else "TRACKS"
            )
        )

        // Top album art accent strip (up to 3 tracks)
        val accentTracks = data.topTracks.take(3)
        if (accentTracks.isNotEmpty()) {
            val stripLabelY = statsRowTop + 126f + if (isStory) 76f else 48f
            drawEyebrow(canvas, "TOP TRACKS", bounds.left, stripLabelY, Color.argb(180, 229, 234, 245), centered = false)

            val rowStartY = stripLabelY + 28f
            val rowHeight = if (isStory) 116f else 88f
            val rowGap = if (isStory) 18f else 12f
            accentTracks.forEachIndexed { idx, track ->
                val top = rowStartY + idx * (rowHeight + rowGap)
                if (top + rowHeight <= bounds.bottom) {
                    val rowRect = RectF(bounds.left, top, bounds.right, top + rowHeight)
                    drawCompactTrackRow(
                        canvas = canvas,
                        rect = rowRect,
                        rank = idx + 1,
                        track = track,
                        artwork = data.accentArtworks.getOrNull(idx),
                        accentColor = ambient.accent
                    )
                }
            }
        }
    }

    private fun drawTopMusicContent(
        canvas: Canvas,
        bounds: RectF,
        data: ShareCardData,
        ambient: AmbientColors,
        isStory: Boolean
    ) {
        val featuredTrack = data.featuredTrack
        if (featuredTrack != null) {
            drawHeroTrackContent(canvas, bounds, featuredTrack, data.primaryArtwork, ambient, isStory)
            return
        }

        drawEyebrow(canvas, "TOP MUSIC", bounds.left, bounds.top + 36f, ambient.accent, centered = false)

        val tracks = data.topTracks.take(5)
        if (tracks.isEmpty()) {
            drawEmptyMessage(canvas, bounds, "Keep listening to unlock your Top Music card")
            return
        }

        val maxMs = tracks.maxOf { it.totalListenedMs }.coerceAtLeast(1L)
        val startY = bounds.top + 68f
        val availableH = (bounds.bottom - startY).coerceAtLeast(400f)
        val gap = if (isStory) 24f else 16f
        val rowHeight = ((availableH - gap * (tracks.size - 1)) / tracks.size.coerceAtLeast(1))
            .coerceIn(112f, if (isStory) 210f else 160f)

        tracks.forEachIndexed { idx, track ->
            val top = startY + idx * (rowHeight + gap)
            val rowRect = RectF(bounds.left, top, bounds.right, top + rowHeight)
            drawRankedTrackRow(
                canvas = canvas,
                rect = rowRect,
                rank = idx + 1,
                track = track,
                ratio = (track.totalListenedMs.toFloat() / maxMs.toFloat()).coerceIn(0.08f, 1f),
                artwork = if (idx == 0) data.primaryArtwork ?: data.accentArtworks.getOrNull(0) else data.accentArtworks.getOrNull(idx),
                accentColor = ambient.accent
            )
        }
    }

    private fun drawHeroTrackContent(
        canvas: Canvas,
        bounds: RectF,
        track: TopTrackStat,
        artwork: Bitmap?,
        ambient: AmbientColors,
        isStory: Boolean
    ) {
        val centerX = bounds.centerX()
        drawEyebrow(canvas, "TOP TRACK", centerX, bounds.top + 36f, ambient.accent, centered = true)

        val artSize = if (isStory) 480f else 340f
        val artTop = bounds.top + if (isStory) 96f else 62f
        val artRect = RectF(
            centerX - artSize / 2f,
            artTop,
            centerX + artSize / 2f,
            artTop + artSize
        )
        drawRoundedArtwork(canvas, artRect, cornerRadius = 40f, bitmap = artwork, seed = "${track.title}|${track.artist}")

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (isStory) 58f else 48f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val titleY = artRect.bottom + if (isStory) 88f else 64f
        canvas.drawText(ellipsize(track.title, titlePaint, bounds.width()), centerX, titleY, titlePaint)

        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 229, 234, 245)
            textSize = if (isStory) 38f else 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val artistY = titleY + 52f
        canvas.drawText(ellipsize(track.artist, artistPaint, bounds.width()), centerX, artistY, artistPaint)

        if (track.album.isNotBlank()) {
            val albumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(145, 199, 208, 226)
                textSize = 26f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(ellipsize(track.album, albumPaint, bounds.width()), centerX, artistY + 42f, albumPaint)
        }

        val statsTop = artistY + if (isStory) 96f else 64f
        val playsLabel = if (track.playCount == 1) "PLAY" else "PLAYS"
        drawTwoStatPills(
            canvas = canvas,
            bounds = RectF(bounds.left, statsTop, bounds.right, statsTop + 136f),
            left = formatSummaryDuration(track.totalListenedMs) to "TIME LISTENED",
            right = track.playCount.toString() to playsLabel
        )
    }

    private fun drawSummaryContent(
        canvas: Canvas,
        bounds: RectF,
        data: ShareCardData,
        ambient: AmbientColors,
        isStory: Boolean
    ) {
        val centerX = bounds.centerX()
        drawEyebrow(canvas, "LISTENING SUMMARY", centerX, bounds.top + 36f, ambient.accent, centered = true)

        // Giant hero listening time
        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = if (isStory) 128f else 104f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val heroY = bounds.top + if (isStory) 200f else 160f
        canvas.drawText(formatSummaryDuration(data.summary.totalListenedMs), centerX, heroY, heroPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 229, 234, 245)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val playsText = if (data.summary.playCount == 1) "1 play" else "${data.summary.playCount} plays"
        val skipText = formatSkipRate(data.summary.playCount, data.summary.skipRate)
        canvas.drawText("$playsText  ·  $skipText skip rate", centerX, heroY + 56f, subPaint)

        // Highlights row: Streak + Peak Hour
        val pillsTop = heroY + if (isStory) 110f else 84f
        val streakText = if (data.streakDays == 1) "1 day" else "${data.streakDays} days"
        val peakText = data.peakHour?.let(::formatPeakHourLabel) ?: "—"
        drawTwoStatPills(
            canvas = canvas,
            bounds = RectF(bounds.left, pillsTop, bounds.right, pillsTop + 128f),
            left = streakText to "ACTIVE STREAK",
            right = peakText to "PEAK HOUR"
        )

        // #1 Artist & #1 Track spotlight cards
        val spotlightTop = pillsTop + 128f + if (isStory) 56f else 36f
        val cardHeight = if (isStory) 190f else 148f
        val gap = if (isStory) 28f else 20f

        val topArtist = data.featuredArtist ?: data.topArtists.firstOrNull()
        if (topArtist != null && spotlightTop + cardHeight <= bounds.bottom) {
            drawSpotlightCard(
                canvas = canvas,
                rect = RectF(bounds.left, spotlightTop, bounds.right, spotlightTop + cardHeight),
                eyebrow = "#1 ARTIST",
                title = topArtist.artist,
                subtitle = "${formatSummaryDuration(topArtist.totalListenedMs)} · ${topArtist.playCount} plays",
                artwork = data.primaryArtwork,
                isCircular = true,
                seed = topArtist.artist,
                accentColor = ambient.accent
            )
        }

        val topTrack = data.featuredTrack ?: data.topTracks.firstOrNull()
        val trackCardTop = spotlightTop + cardHeight + gap
        if (topTrack != null && trackCardTop + cardHeight <= bounds.bottom) {
            drawSpotlightCard(
                canvas = canvas,
                rect = RectF(bounds.left, trackCardTop, bounds.right, trackCardTop + cardHeight),
                eyebrow = "#1 TRACK",
                title = topTrack.title,
                subtitle = "${topTrack.artist} · ${formatSummaryDuration(topTrack.totalListenedMs)}",
                artwork = data.accentArtworks.firstOrNull(),
                isCircular = false,
                seed = "${topTrack.title}|${topTrack.artist}",
                accentColor = ambient.accent
            )
        }
    }

    private fun drawSpotlightCard(
        canvas: Canvas,
        rect: RectF,
        eyebrow: String,
        title: String,
        subtitle: String,
        artwork: Bitmap?,
        isCircular: Boolean,
        seed: String,
        accentColor: Int
    ) {
        drawGlassPill(canvas, rect, Color.argb(60, 255, 255, 255), cornerRadius = 32f)

        val artSize = (rect.height() - 44f).coerceAtLeast(80f)
        val artRect = RectF(
            rect.left + 24f,
            rect.centerY() - artSize / 2f,
            rect.left + 24f + artSize,
            rect.centerY() + artSize / 2f
        )
        if (isCircular) {
            drawCircularArtwork(canvas, artRect, artwork, seed)
        } else {
            drawRoundedArtwork(canvas, artRect, cornerRadius = 20f, bitmap = artwork, seed = seed)
        }

        val textLeft = artRect.right + 28f
        val maxTextW = (rect.right - textLeft - 24f).coerceAtLeast(100f)

        val eyebrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 229, 234, 245)
            textSize = 25f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        canvas.drawText(eyebrow, textLeft, rect.top + 48f, eyebrowPaint)
        canvas.drawText(ellipsize(title, titlePaint, maxTextW), textLeft, rect.centerY() + 14f, titlePaint)
        canvas.drawText(ellipsize(subtitle, subtitlePaint, maxTextW), textLeft, rect.bottom - 30f, subtitlePaint)
    }

    private fun drawCompactTrackRow(
        canvas: Canvas,
        rect: RectF,
        rank: Int,
        track: TopTrackStat,
        artwork: Bitmap?,
        accentColor: Int
    ) {
        drawGlassPill(canvas, rect, Color.argb(46, 255, 255, 255), cornerRadius = 24f)

        val artSize = rect.height() - 24f
        val artRect = RectF(
            rect.left + 16f,
            rect.centerY() - artSize / 2f,
            rect.left + 16f + artSize,
            rect.centerY() + artSize / 2f
        )
        drawRoundedArtwork(canvas, artRect, cornerRadius = 16f, bitmap = artwork, seed = "${track.title}|${track.artist}")

        val durationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val durationText = formatSummaryDuration(track.totalListenedMs)
        val durationW = durationPaint.measureText(durationText)

        val textLeft = artRect.right + 20f
        val maxTextW = (rect.right - 24f - durationW - 18f - textLeft).coerceAtLeast(80f)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 29f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 229, 234, 245)
            textSize = 22f
        }

        canvas.drawText(
            ellipsize("#$rank  ${track.title}", titlePaint, maxTextW),
            textLeft,
            rect.centerY() - 4f,
            titlePaint
        )
        val playsLabel = if (track.playCount == 1) "1 play" else "${track.playCount} plays"
        canvas.drawText(
            ellipsize(playsLabel, subPaint, maxTextW),
            textLeft,
            rect.centerY() + 28f,
            subPaint
        )
        canvas.drawText(durationText, rect.right - 24f, rect.centerY() + 9f, durationPaint)
    }

    private fun drawRankedTrackRow(
        canvas: Canvas,
        rect: RectF,
        rank: Int,
        track: TopTrackStat,
        ratio: Float,
        artwork: Bitmap?,
        accentColor: Int
    ) {
        drawGlassPill(canvas, rect, Color.argb(52, 255, 255, 255), cornerRadius = 28f)

        val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 229, 234, 245)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("#$rank", rect.left + 22f, rect.centerY() + 6f, rankPaint)

        val artSize = (rect.height() - 40f).coerceIn(64f, 108f)
        val artRect = RectF(
            rect.left + 84f,
            rect.top + 16f,
            rect.left + 84f + artSize,
            rect.top + 16f + artSize
        )
        drawRoundedArtwork(canvas, artRect, cornerRadius = 18f, bitmap = artwork, seed = "${track.title}|${track.artist}")

        val durationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val durationText = formatSummaryDuration(track.totalListenedMs)
        val durationW = durationPaint.measureText(durationText)

        val textLeft = artRect.right + 22f
        val maxTextW = (rect.right - 24f - durationW - 18f - textLeft).coerceAtLeast(100f)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 229, 234, 245)
            textSize = 24f
        }

        canvas.drawText(ellipsize(track.title, titlePaint, maxTextW), textLeft, artRect.top + 38f, titlePaint)
        canvas.drawText(ellipsize(track.artist, artistPaint, maxTextW), textLeft, artRect.top + 74f, artistPaint)
        canvas.drawText(durationText, rect.right - 24f, artRect.top + 48f, durationPaint)

        // Proportional bar along bottom of row
        val barTop = rect.bottom - 18f
        val barRect = RectF(rect.left + 24f, barTop, rect.right - 24f, barTop + 7f)
        val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 255, 255, 255) }
        canvas.drawRoundRect(barRect, 4f, 4f, trackBgPaint)

        val fillRect = RectF(
            barRect.left,
            barRect.top,
            barRect.left + barRect.width() * ratio.coerceIn(0.06f, 1f),
            barRect.bottom
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
    }

    private fun drawThreeStatPills(
        canvas: Canvas,
        bounds: RectF,
        items: List<Pair<String, String>>
    ) {
        val gap = 18f
        val pillW = (bounds.width() - gap * 2f) / 3f
        items.take(3).forEachIndexed { idx, (value, label) ->
            val left = bounds.left + idx * (pillW + gap)
            drawStatPill(canvas, RectF(left, bounds.top, left + pillW, bounds.bottom), value, label)
        }
    }

    private fun drawTwoStatPills(
        canvas: Canvas,
        bounds: RectF,
        left: Pair<String, String>,
        right: Pair<String, String>
    ) {
        val gap = 20f
        val pillW = (bounds.width() - gap) / 2f
        drawStatPill(canvas, RectF(bounds.left, bounds.top, bounds.left + pillW, bounds.bottom), left.first, left.second)
        drawStatPill(canvas, RectF(bounds.left + pillW + gap, bounds.top, bounds.right, bounds.bottom), right.first, right.second)
    }

    private fun drawStatPill(canvas: Canvas, rect: RectF, value: String, label: String) {
        drawGlassPill(canvas, rect, Color.argb(52, 255, 255, 255), cornerRadius = 26f)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 229, 234, 245)
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ellipsize(value, valuePaint, rect.width() - 20f), rect.centerX(), rect.centerY() + 4f, valuePaint)
        canvas.drawText(label, rect.centerX(), rect.bottom - 22f, labelPaint)
    }

    private fun drawGlassPill(canvas: Canvas, rect: RectF, fillColor: Int, cornerRadius: Float = rect.height() / 2f) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            color = Color.argb(85, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun drawCircularArtwork(canvas: Canvas, rect: RectF, bitmap: Bitmap?, seed: String) {
        canvas.save()
        val path = Path().apply { addOval(rect, Path.Direction.CW) }
        canvas.clipPath(path)

        if (bitmap != null && !bitmap.isRecycled) {
            drawCenterCroppedBitmap(canvas, bitmap, rect)
        } else {
            val pair = deterministicArtworkColors(seed)
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    pair.first.toArgb(),
                    pair.second.toArgb(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(rect, gradPaint)

            val monogram = firstGlyph(seed)
            val monogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = rect.width() * 0.42f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val baseline = rect.centerY() - (monogramPaint.descent() + monogramPaint.ascent()) / 2f
            canvas.drawText(monogram, rect.centerX(), baseline, monogramPaint)
        }
        canvas.restore()

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(140, 255, 255, 255)
        }
        canvas.drawOval(rect, ringPaint)
    }

    private fun drawRoundedArtwork(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        bitmap: Bitmap?,
        seed: String
    ) {
        canvas.save()
        val path = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }
        canvas.clipPath(path)

        if (bitmap != null && !bitmap.isRecycled) {
            drawCenterCroppedBitmap(canvas, bitmap, rect)
        } else {
            val pair = deterministicArtworkColors(seed)
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    pair.first.toArgb(),
                    pair.second.toArgb(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(rect, gradPaint)

            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255)
                textSize = rect.width() * 0.42f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val baseline = rect.centerY() - (notePaint.descent() + notePaint.ascent()) / 2f
            canvas.drawText("♪", rect.centerX(), baseline, notePaint)
        }
        canvas.restore()

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(115, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun drawCenterCroppedBitmap(canvas: Canvas, bitmap: Bitmap, dest: RectF) {
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = max(dest.width() / bw, dest.height() / bh)
        val srcW = dest.width() / scale
        val srcH = dest.height() / scale
        val srcLeft = ((bw - srcW) / 2f).toInt().coerceAtLeast(0)
        val srcTop = ((bh - srcH) / 2f).toInt().coerceAtLeast(0)
        val srcRect = Rect(
            srcLeft,
            srcTop,
            (srcLeft + srcW.toInt()).coerceAtMost(bitmap.width),
            (srcTop + srcH.toInt()).coerceAtMost(bitmap.height)
        )
        canvas.drawBitmap(bitmap, srcRect, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawEyebrow(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        centered: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
            textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        }
        canvas.drawText(text, x, y, paint)
    }

    private fun drawEmptyMessage(canvas: Canvas, bounds: RectF, message: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 229, 234, 245)
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ellipsize(message, paint, bounds.width()), bounds.centerX(), bounds.centerY(), paint)
    }

    private fun drawFooter(canvas: Canvas, cardRect: RectF, footerTop: Float) {
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            strokeWidth = 1.5f
        }
        canvas.drawLine(cardRect.left + 56f, footerTop, cardRect.right - 56f, footerTop, dividerPaint)

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 199, 208, 226)
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Tracked with Collecto",
            cardRect.centerX(),
            footerTop + 46f,
            footerPaint
        )
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end -= 1
        }
        return text.substring(0, end.coerceAtLeast(1)) + ellipsis
    }

    private fun firstGlyph(seed: String): String {
        val trimmed = seed.trim()
        if (trimmed.isEmpty()) return "?"
        val cp = trimmed.codePointAt(0)
        return String(Character.toChars(Character.toUpperCase(cp)))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun darkenColor(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun lightenColor(color: Int, fraction: Float): Int = Color.rgb(
        min(255, (Color.red(color) + (255 - Color.red(color)) * fraction).toInt()),
        min(255, (Color.green(color) + (255 - Color.green(color)) * fraction).toInt()),
        min(255, (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt())
    )
}
