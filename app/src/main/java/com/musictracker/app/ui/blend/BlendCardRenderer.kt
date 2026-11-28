package com.musictracker.app.ui.blend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.musictracker.app.ui.components.content.deterministicArtworkColors
import com.musictracker.app.ui.share.ShareAspectRatio
import kotlin.math.min

/**
 * Pure Android [Canvas] + [Bitmap] renderer for Collecto Blend Cards.
 *
 * Supports both [ShareAspectRatio.STORY_9_16] (`1080x1920`) and
 * [ShareAspectRatio.POST_4_5] (`1080x1350`) with a dual-gradient glass aesthetic.
 */
object BlendCardRenderer {

    fun render(
        result: BlendComparisonResult,
        aspectRatio: ShareAspectRatio = ShareAspectRatio.STORY_9_16
    ): Bitmap {
        val width = aspectRatio.widthPx
        val height = aspectRatio.heightPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val leftSeed = result.myTopArtist ?: "Collecto Listener"
        val rightSeed = result.friendTopArtist ?: "Collecto Friend"
        val leftColors = deterministicArtworkColors(leftSeed)
        val rightColors = deterministicArtworkColors(rightSeed)
        val leftAccent = leftColors.first.toArgb()
        val rightAccent = rightColors.first.toArgb()

        drawDualBackdrop(canvas, width, height, leftAccent, rightAccent)

        val isStory = aspectRatio == ShareAspectRatio.STORY_9_16
        val outerPadH = 72f
        val outerPadTop = if (isStory) 140f else 84f
        val outerPadBottom = if (isStory) 150f else 88f
        val cardRect = RectF(outerPadH, outerPadTop, width - outerPadH, height - outerPadBottom)

        drawGlassCard(canvas, cardRect, cornerRadius = 56f)

        val contentLeft = cardRect.left + 60f
        val contentRight = cardRect.right - 60f
        val centerX = cardRect.centerX()

        // Header
        val headerY = cardRect.top + 88f
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.14f
        }
        canvas.drawText("COLLECTO BLEND", contentLeft, headerY, brandPaint)

        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 226, 232, 240)
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("TASTE MATCH", contentRight, headerY, tagPaint)

        // Match % hero
        val heroY = if (isStory) cardRect.top + 380f else cardRect.top + 300f
        val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = if (isStory) 210f else 176f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            shader = LinearGradient(
                centerX - 220f,
                heroY - 160f,
                centerX + 220f,
                heroY,
                intArrayOf(lightenColor(leftAccent, 0.45f), Color.WHITE, lightenColor(rightAccent, 0.45f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawText("${result.matchPercent}%", centerX, heroY, percentPaint)

        // Vibe label pill
        val vibeY = heroY + 76f
        val vibePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val vibeTextWidth = vibePaint.measureText(result.vibeLabel)
        val pillRect = RectF(
            centerX - vibeTextWidth / 2f - 44f,
            vibeY - 52f,
            centerX + vibeTextWidth / 2f + 44f,
            vibeY + 22f
        )
        val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 255, 255, 255)
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(110, 255, 255, 255)
        }
        canvas.drawRoundRect(pillRect, 36f, 36f, pillFill)
        canvas.drawRoundRect(pillRect, 36f, 36f, pillStroke)
        canvas.drawText(result.vibeLabel, centerX, vibeY, vibePaint)

        // Top artists comparison row
        val compareTop = vibeY + if (isStory) 92f else 62f
        val halfGap = 16f
        val boxHeight = 148f
        val leftBox = RectF(contentLeft, compareTop, centerX - halfGap, compareTop + boxHeight)
        val rightBox = RectF(centerX + halfGap, compareTop, contentRight, compareTop + boxHeight)
        drawMiniInfoBox(
            canvas = canvas,
            rect = leftBox,
            label = "YOUR #1 ARTIST",
            value = result.myTopArtist ?: "—",
            accentColor = leftAccent
        )
        drawMiniInfoBox(
            canvas = canvas,
            rect = rightBox,
            label = "FRIEND'S #1 ARTIST",
            value = result.friendTopArtist ?: "—",
            accentColor = rightAccent
        )

        // Shared artists section
        val sharedTop = compareTop + boxHeight + if (isStory) 72f else 48f
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 226, 232, 240)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val sharedHeader = if (result.sharedArtists.isNotEmpty()) {
            "SHARED ARTISTS (${result.sharedArtists.size})"
        } else {
            "SHARED ARTISTS"
        }
        canvas.drawText(sharedHeader, contentLeft, sharedTop, sectionTitlePaint)

        val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 203, 213, 225)
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val maxArtists = if (isStory) 5 else 3
        val maxTextWidth = contentRight - contentLeft - 24f
        var cursorY = sharedTop + 62f
        if (result.sharedArtists.isEmpty()) {
            canvas.drawText(
                "No overlapping top artists yet — time to trade recs!",
                contentLeft,
                cursorY,
                mutedPaint
            )
            cursorY += 56f
        } else {
            result.sharedArtists.take(maxArtists).forEachIndexed { index, artist ->
                val line = "${index + 1}.  ${ellipsize(artist, itemPaint, maxTextWidth - 64f)}"
                canvas.drawText(line, contentLeft, cursorY, itemPaint)
                cursorY += if (isStory) 68f else 56f
            }
        }

        if (result.sharedTracks.isNotEmpty()) {
            val tracksSummaryY = min(cursorY + 24f, cardRect.bottom - 120f)
            val trackSummary = "${result.sharedTracks.size} shared top track" +
                if (result.sharedTracks.size == 1) "" else "s"
            canvas.drawText(trackSummary, contentLeft, tracksSummaryY, mutedPaint)
        }

        // Footer
        val footerY = cardRect.bottom - 48f
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 226, 232, 240)
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Generated locally on device with Collecto",
            centerX,
            footerY,
            footerPaint
        )

        return bitmap
    }

    private fun drawDualBackdrop(
        canvas: Canvas,
        width: Int,
        height: Int,
        leftAccent: Int,
        rightAccent: Int
    ) {
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    darkenColor(leftAccent, 0.35f),
                    Color.rgb(10, 12, 18),
                    darkenColor(rightAccent, 0.35f)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)

        val leftOrb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.22f,
                height * 0.24f,
                width * 0.65f,
                intArrayOf(withAlpha(leftAccent, 130), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), leftOrb)

        val rightOrb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.80f,
                height * 0.72f,
                width * 0.65f,
                intArrayOf(withAlpha(rightAccent, 130), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), rightOrb)
    }

    private fun drawGlassCard(canvas: Canvas, rect: RectF, cornerRadius: Float) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(148, 16, 20, 30)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(
                    Color.argb(115, 255, 255, 255),
                    Color.argb(36, 255, 255, 255),
                    Color.argb(80, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun drawMiniInfoBox(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        accentColor: Int
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 255, 255, 255)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = withAlpha(lightenColor(accentColor, 0.35f), 120)
        }
        canvas.drawRoundRect(rect, 30f, 30f, bgPaint)
        canvas.drawRoundRect(rect, 30f, 30f, borderPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 226, 232, 240)
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val maxW = rect.width() - 48f
        canvas.drawText(label, rect.left + 24f, rect.top + 52f, labelPaint)
        canvas.drawText(
            ellipsize(value, valuePaint, maxW),
            rect.left + 24f,
            rect.top + 108f,
            valuePaint
        )
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun darkenColor(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun lightenColor(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
    )
}
