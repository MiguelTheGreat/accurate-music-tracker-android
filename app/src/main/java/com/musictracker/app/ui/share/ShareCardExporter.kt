package com.musictracker.app.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Handles saving rendered share cards to the device gallery (`Pictures/Collecto`) and
 * launching native Android share sheets / Instagram Stories intents via [FileProvider].
 */
object ShareCardExporter {

    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val INSTAGRAM_STORY_ACTION = "com.instagram.share.ADD_TO_STORY"
    private const val SHARED_CARDS_SUBDIR = "shared_cards"
    private const val SHARED_CARD_FILENAME = "collecto_card.png"
    private const val GALLERY_FOLDER = "Collecto"

    private val fileTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun isInstagramStoriesAvailable(context: Context): Boolean {
        val probeIntent = Intent(INSTAGRAM_STORY_ACTION).apply {
            type = "image/png"
        }
        return runCatching {
            context.packageManager.resolveActivity(probeIntent, 0) != null
        }.getOrDefault(false)
    }

    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        mode: ShareCardMode
    ): ShareExportResult = saveToGallery(
        context = context,
        bitmap = bitmap,
        fileSlug = mode.name.lowercase()
    )

    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        fileSlug: String
    ): ShareExportResult = withContext(Dispatchers.IO) {
        val timestamp = fileTimestampFormatter.format(
            Instant.now().atZone(ZoneId.systemDefault())
        )
        val sanitizedSlug = fileSlug
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "card" }
        val displayName = "Collecto_${sanitizedSlug}_$timestamp.png"

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, displayName)
            } else {
                saveLegacyPictures(context, bitmap, displayName)
            }
            ShareExportResult.Success("Saved to Pictures/$GALLERY_FOLDER")
        }.getOrElse { error ->
            ShareExportResult.Error(error.message ?: "Failed to save image")
        }
    }

    suspend fun shareToInstagramStories(
        context: Context,
        bitmap: Bitmap
    ): ShareExportResult {
        val uri = withContext(Dispatchers.IO) {
            runCatching { writeShareablePng(context, bitmap) }.getOrNull()
        } ?: return ShareExportResult.Error("Could not prepare card image")

        return runCatching {
            val intent = Intent(INSTAGRAM_STORY_ACTION).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.grantUriPermission(
                INSTAGRAM_PACKAGE,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.startActivity(intent)
            ShareExportResult.Success("Opening Instagram Stories…")
        }.getOrElse { error ->
            ShareExportResult.Error(error.message ?: "Instagram Stories unavailable")
        }
    }

    suspend fun shareStandard(
        context: Context,
        bitmap: Bitmap
    ): ShareExportResult {
        val uri = withContext(Dispatchers.IO) {
            runCatching { writeShareablePng(context, bitmap) }.getOrNull()
        } ?: return ShareExportResult.Error("Could not prepare card image")

        return runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Collecto Card", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share Collecto Card").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            ShareExportResult.Success("Share sheet opened")
        }.getOrElse { error ->
            ShareExportResult.Error(error.message ?: "Unable to open share sheet")
        }
    }

    internal fun writeShareablePng(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, SHARED_CARDS_SUBDIR).apply { mkdirs() }
        val file = File(dir, SHARED_CARD_FILENAME)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Bitmap PNG compression failed")
            }
            out.flush()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, displayName: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Bitmap PNG compression failed")
                }
                out.flush()
            } ?: throw IOException("Could not open MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveLegacyPictures(context: Context, bitmap: Bitmap, displayName: String) {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val targetDir = File(baseDir, GALLERY_FOLDER).apply { mkdirs() }
        val targetFile = File(targetDir, displayName)
        FileOutputStream(targetFile).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Bitmap PNG compression failed")
            }
            out.flush()
        }
    }
}
