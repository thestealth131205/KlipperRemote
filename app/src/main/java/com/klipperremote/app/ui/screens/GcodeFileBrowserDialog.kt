package com.klipperremote.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ── G-Code Datei-Browser Dialog ──────────────────────────────────────────────

@Composable
fun GcodeFileBrowserDialog(
    files: List<PrintFile>,
    onSelectFile: (String) -> Unit,
    onPreviewFile: (String) -> Unit = {},
    onDismiss: () -> Unit,
    thumbnails: Map<String, Bitmap> = emptyMap(),
    onRequestThumbnail: (String) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.filename.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1C),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Datei auswählen",
                        color = OnSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen", tint = OnSurfaceDim)
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = OnSurfaceDim,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = OnSurface, fontSize = 14.sp),
                        cursorBrush = SolidColor(AccentYellow),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text("Dateiname suchen…", color = OnSurfaceDim, fontSize = 14.sp)
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Löschen",
                                tint = OnSurfaceDim,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                if (filteredFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "Keine Treffer für '$searchQuery'"
                            else "Keine Dateien gefunden",
                            color = OnSurfaceDim,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        items(filteredFiles, key = { "${it.filename}_${it.modified}" }) { file ->
                            GcodeFileItem(
                                file = file,
                                thumbnail = thumbnails[file.filename],
                                onRequestThumbnail = { onRequestThumbnail(file.filename) },
                                onClick = { onSelectFile(file.filename) },
                                onPreview = { onPreviewFile(file.filename) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GcodeFileItem(
    file: PrintFile,
    thumbnail: Bitmap? = null,
    onRequestThumbnail: () -> Unit = {},
    onClick: () -> Unit,
    onPreview: () -> Unit = {}
) {
    LaunchedEffect(file.filename) {
        if (thumbnail == null) onRequestThumbnail()
    }

    val sizeText = when {
        file.size >= 1_048_576 -> "%.1f MB".format(file.size / 1_048_576f)
        file.size >= 1_024 -> "%.0f KB".format(file.size / 1_024f)
        else -> "${file.size} B"
    }
    val dateText = if (file.modified > 0L) {
        SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(file.modified))
    } else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Color(0xFF252525))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF111111))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = AccentYellow.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.filename.substringAfterLast('/'),
                color = OnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (dateText.isNotEmpty() || sizeText.isNotEmpty()) {
                Text(
                    "$dateText  $sizeText".trim(),
                    color = OnSurfaceDim,
                    fontSize = 11.sp
                )
            }
        }
        IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Vorschau",
                tint = AccentYellow.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Druck-Bestätigungs-Dialog ─────────────────────────────────────────────────

@Composable
fun GcodePrintConfirmDialog(
    filename: String,
    metadata: GcodeMetadata?,
    thumbnail: Bitmap?,
    isLoading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1C),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Vorschaubild oder Lade-Indikator
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AccentYellow, modifier = Modifier.size(36.dp))
                    }
                } else if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Vorschau",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF111111))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF111111)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Kein Vorschaubild", color = OnSurfaceDim, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Dateiname
                Text(
                    filename.substringAfterLast('/'),
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Druckzeit
                val timeText = metadata?.estimatedTime?.let { formatPrintTime(it) }
                if (timeText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Druckzeit: $timeText",
                        color = AccentYellow,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceDim)
                    ) {
                        Text("Abbrechen", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentYellow,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Druck starten", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun formatPrintTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}min"
        h > 0 -> "${h}h"
        m > 0 -> "${m}min"
        else -> "${seconds}s"
    }
}
