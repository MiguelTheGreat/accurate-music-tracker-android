package com.musictracker.app.ui.blend

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musictracker.app.domain.model.TopArtistStat
import com.musictracker.app.domain.model.TopTrackStat
import com.musictracker.app.ui.components.glass.LiquidGlassButton
import com.musictracker.app.ui.components.glass.LiquidGlassSheet
import com.musictracker.app.ui.components.glass.LiquidGlassVariant
import com.musictracker.app.ui.share.ShareAspectRatio
import com.musictracker.app.ui.share.ShareCardExporter
import com.musictracker.app.ui.share.ShareExportResult
import com.musictracker.app.ui.theme.LiquidGlassShapes
import com.musictracker.app.ui.theme.LiquidGlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BlendSheetTab(val label: String) {
    MY_CODE("My Code"),
    COMPARE("Compare")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlendSheet(
    topArtists: List<TopArtistStat>,
    topTracks: List<TopTrackStat>,
    initialFriendCode: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LiquidGlassTheme.colors
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedTab by remember {
        mutableStateOf(
            if (initialFriendCode.isNullOrBlank()) BlendSheetTab.MY_CODE else BlendSheetTab.COMPARE
        )
    }
    var copiedFeedback by remember { mutableStateOf(false) }

    val myPayload = remember(topArtists, topTracks) {
        BlendCalculator.fromStats(topArtists = topArtists, topTracks = topTracks)
    }
    val myCode = remember(myPayload) {
        BlendCalculator.encode(myPayload)
    }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(myCode) {
        qrBitmap = withContext(Dispatchers.Default) {
            runCatching { BlendCalculator.generateQrBitmap(myCode, sizePx = 512) }.getOrNull()
        }
    }

    // Compare tab state
    var friendCodeInput by remember { mutableStateOf(initialFriendCode.orEmpty()) }
    var decodeError by remember { mutableStateOf<String?>(null) }
    var comparisonResult by remember { mutableStateOf<BlendComparisonResult?>(null) }
    var selectedAspect by remember { mutableStateOf(ShareAspectRatio.STORY_9_16) }
    var renderedCardBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRenderingCard by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportFeedback by remember { mutableStateOf<ShareExportResult?>(null) }

    LaunchedEffect(initialFriendCode, myPayload) {
        val code = initialFriendCode?.trim()
        if (!code.isNullOrEmpty()) {
            selectedTab = BlendSheetTab.COMPARE
            friendCodeInput = code
            BlendCalculator.decode(code)
                .onSuccess { friendPayload ->
                    decodeError = null
                    comparisonResult = BlendCalculator.compare(myPayload, friendPayload)
                }
                .onFailure { err ->
                    comparisonResult = null
                    decodeError = err.message ?: "Invalid Collecto Blend code"
                }
        }
    }

    val instagramAvailable = remember(context) {
        ShareCardExporter.isInstagramStoriesAvailable(context)
    }

    LaunchedEffect(comparisonResult, selectedAspect) {
        val result = comparisonResult
        if (result == null) {
            renderedCardBitmap = null
            isRenderingCard = false
            return@LaunchedEffect
        }
        isRenderingCard = true
        renderedCardBitmap = withContext(Dispatchers.Default) {
            BlendCardRenderer.render(result = result, aspectRatio = selectedAspect)
        }
        isRenderingCard = false
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
                            text = "Collecto Blend",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.labelPrimary
                        )
                        Text(
                            text = "Compare music taste offline via QR or share code",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.labelSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close blend sheet",
                            tint = colors.labelSecondary
                        )
                    }
                }

                // Tab selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BlendSheetTab.entries.forEach { tab ->
                        BlendOptionPill(
                            label = tab.label,
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                when (selectedTab) {
                    BlendSheetTab.MY_CODE -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val bmp = qrBitmap
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Your Collecto Blend QR code",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                }
                            }

                            Text(
                                text = "Includes your top ${myPayload.artists.size} artists & ${myPayload.tracks.size} tracks. Everything stays on-device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.labelSecondary,
                                textAlign = TextAlign.Center
                            )

                            LiquidGlassButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("Collecto Blend Code", myCode)
                                    )
                                    copiedFeedback = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                variant = LiquidGlassVariant.Regular
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (copiedFeedback) "Blend Code Copied!" else "Copy Blend Code",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }

                    BlendSheetTab.COMPARE -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = friendCodeInput,
                                onValueChange = {
                                    friendCodeInput = it
                                    decodeError = null
                                },
                                label = { Text("Friend's COLLECTO1: code") },
                                placeholder = { Text("Paste COLLECTO1:... code here") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as? ClipboardManager
                                            val pasted = clipboard?.primaryClip
                                                ?.getItemAt(0)
                                                ?.coerceToText(context)
                                                ?.toString()
                                                ?.trim()
                                            if (!pasted.isNullOrBlank()) {
                                                friendCodeInput = pasted
                                                decodeError = null
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentPaste,
                                            contentDescription = "Paste code from clipboard"
                                        )
                                    }
                                }
                            )

                            decodeError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            LiquidGlassButton(
                                onClick = {
                                    val decoded = BlendCalculator.decode(friendCodeInput)
                                    decoded.onSuccess { friendPayload ->
                                        decodeError = null
                                        exportFeedback = null
                                        comparisonResult = BlendCalculator.compare(myPayload, friendPayload)
                                    }.onFailure { error ->
                                        comparisonResult = null
                                        decodeError = error.message ?: "Could not decode blend code"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = friendCodeInput.isNotBlank(),
                                variant = LiquidGlassVariant.Regular
                            ) {
                                Text(
                                    text = "Calculate Taste Match",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            val result = comparisonResult
                            if (result != null) {
                                // Aspect ratio selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ShareAspectRatio.entries.forEach { aspect ->
                                        BlendOptionPill(
                                            label = aspect.label,
                                            selected = selectedAspect == aspect,
                                            onClick = {
                                                selectedAspect = aspect
                                                exportFeedback = null
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                // Card preview
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(colors.controlTrackFill)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val preview = renderedCardBitmap
                                    if (isRenderingCard || preview == null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(220.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    } else {
                                        Image(
                                            bitmap = preview.asImageBitmap(),
                                            contentDescription = "Blend card preview",
                                            modifier = Modifier
                                                .fillMaxWidth(0.72f)
                                                .aspectRatio(
                                                    selectedAspect.widthPx.toFloat() /
                                                        selectedAspect.heightPx.toFloat()
                                                )
                                                .clip(RoundedCornerShape(16.dp)),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }

                                exportFeedback?.let { feedback ->
                                    val message = when (feedback) {
                                        is ShareExportResult.Success -> feedback.message
                                        is ShareExportResult.Error -> feedback.message
                                    }
                                    val feedbackColor = when (feedback) {
                                        is ShareExportResult.Success -> colors.accentBlue
                                        is ShareExportResult.Error -> MaterialTheme.colorScheme.error
                                    }
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = feedbackColor
                                    )
                                }

                                // Export buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LiquidGlassButton(
                                        onClick = {
                                            val bmp = renderedCardBitmap ?: return@LiquidGlassButton
                                            coroutineScope.launch {
                                                isExporting = true
                                                exportFeedback = ShareCardExporter.saveToGallery(
                                                    context = context,
                                                    bitmap = bmp,
                                                    fileSlug = "blend"
                                                )
                                                isExporting = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isExporting && renderedCardBitmap != null,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Save", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

                                    LiquidGlassButton(
                                        onClick = {
                                            val bmp = renderedCardBitmap ?: return@LiquidGlassButton
                                            coroutineScope.launch {
                                                isExporting = true
                                                exportFeedback = ShareCardExporter.shareStandard(
                                                    context = context,
                                                    bitmap = bmp
                                                )
                                                isExporting = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isExporting && renderedCardBitmap != null,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Share,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Share", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                if (instagramAvailable) {
                                    LiquidGlassButton(
                                        onClick = {
                                            val bmp = renderedCardBitmap ?: return@LiquidGlassButton
                                            coroutineScope.launch {
                                                isExporting = true
                                                exportFeedback = ShareCardExporter.shareToInstagramStories(
                                                    context = context,
                                                    bitmap = bmp
                                                )
                                                isExporting = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isExporting && renderedCardBitmap != null
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Share to Instagram Stories",
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BlendOptionPill(
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
