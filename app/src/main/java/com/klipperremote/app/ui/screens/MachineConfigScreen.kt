package com.klipperremote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.BackupConfigFile
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.viewmodel.MainViewModel

// ── Syntax-Highlighting ────────────────────────────────────────────────────────

private val CfgSectionColor = Color(0xFFE8FF00) // Neon-Gelb für [section]
private val CfgKeyColor     = Color(0xFF87CEEB) // Hellblau  für key
private val CfgCommentColor = Color(0xFF888888) // Grau      für # Kommentar
private val CfgEqualColor   = Color(0xFFAAAAAA) // Grau      für : / =
private val CfgValueColor   = Color(0xFFEEEEEE) // Fast-Weiß für Werte

private fun buildHighlighted(text: String, searchQuery: String = ""): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { idx, line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith('#') -> {
                pushStyle(SpanStyle(color = CfgCommentColor))
                append(line)
                pop()
            }
            trimmed.startsWith('[') && trimmed.contains(']') -> {
                pushStyle(SpanStyle(color = CfgSectionColor, fontWeight = FontWeight.Bold))
                append(line)
                pop()
            }
            trimmed.contains(':') || trimmed.contains('=') -> {
                val sepChar = if (trimmed.indexOf(':') != -1 &&
                    (trimmed.indexOf('=') == -1 || trimmed.indexOf(':') < trimmed.indexOf('='))) ':' else '='
                val sepIdx = line.indexOf(sepChar)
                if (sepIdx > 0) {
                    pushStyle(SpanStyle(color = CfgKeyColor))
                    append(line.substring(0, sepIdx))
                    pop()
                    pushStyle(SpanStyle(color = CfgEqualColor))
                    append(sepChar.toString())
                    pop()
                    pushStyle(SpanStyle(color = CfgValueColor))
                    append(line.substring(sepIdx + 1))
                    pop()
                } else {
                    append(line)
                }
            }
            else -> append(line)
        }
        if (idx < lines.lastIndex) append('\n')
    }
    // Search match highlighting
    if (searchQuery.isNotBlank()) {
        val lowerText = text.lowercase()
        val lowerQuery = searchQuery.lowercase()
        var startIdx = 0
        while (true) {
            val found = lowerText.indexOf(lowerQuery, startIdx)
            if (found == -1) break
            addStyle(SpanStyle(background = Color(0xFFE8FF00).copy(alpha = 0.5f), color = Color.Black), found, found + searchQuery.length)
            startIdx = found + searchQuery.length
        }
    }
}

private class CfgSyntaxTransformation(private val searchQuery: String = "") : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(buildHighlighted(text.text, searchQuery), OffsetMapping.Identity)
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isEditing = uiState.editingConfigPath != null
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showFirmwareRestartConfirm by remember { mutableStateOf(false) }
    // Backup-Modus: Konfig-Dateien auswählen, um sie zu sichern
    var backupMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }
    // Upload-Dialog: Dateien per System-Auswahl hochladen
    var showUploadDialog by remember { mutableStateOf(false) }
    // Config-Suche
    var configSearchMode by remember { mutableStateOf(false) }
    var configSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(isEditing) {
        if (!isEditing) { configSearchMode = false; configSearchQuery = "" }
    }
    val configSearchMatchCount = remember(uiState.editingConfigContent, configSearchQuery) {
        if (configSearchQuery.isBlank()) 0
        else {
            var count = 0; var idx = 0
            val lower = uiState.editingConfigContent.lowercase()
            val lowerQ = configSearchQuery.lowercase()
            while (true) { val f = lower.indexOf(lowerQ, idx); if (f == -1) break; count++; idx = f + lowerQ.length }
            count
        }
    }

    fun exitBackupMode() {
        backupMode = false
        selectedPaths.clear()
    }

    // System-Dateiauswahl für den Upload (mehrere Dateien möglich).
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = readPickedFiles(context, uris)
            if (files.isNotEmpty()) viewModel.uploadConfigFiles(files)
        }
    }

    // Ergebnis des Uploads als Toast anzeigen und danach zurücksetzen.
    LaunchedEffect(uiState.configUploadResult) {
        uiState.configUploadResult?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearConfigUploadResult()
        }
    }

    LaunchedEffect(Unit) {
        if (uiState.configFiles.isEmpty()) viewModel.loadConfigFiles()
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isEditing -> uiState.editingConfigPath!!.substringAfterLast('/')
                            backupMode -> if (selectedPaths.isEmpty()) "Backup – auswählen"
                                          else "${selectedPaths.size} ausgewählt"
                            else -> "Maschine"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            isEditing -> viewModel.closeConfigEditor()
                            backupMode -> exitBackupMode()
                            else -> onNavigateBack()
                        }
                    }) {
                        Icon(
                            if (backupMode && !isEditing) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = {
                            configSearchMode = !configSearchMode
                            if (!configSearchMode) configSearchQuery = ""
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "In Konfiguration suchen",
                                tint = if (configSearchMode) Color(0xFFE8FF00) else Color(0xFFAAAAAA)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.saveCurrentConfigFile(uiState.editingConfigContent) },
                            enabled = !uiState.editingConfigSaving
                        ) {
                            when {
                                uiState.editingConfigSaved ->
                                    Icon(Icons.Default.Check, contentDescription = "Gespeichert", tint = Color(0xFF4CAF50))
                                uiState.editingConfigSaving ->
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFE8FF00))
                                else ->
                                    Icon(Icons.Default.Save, contentDescription = "Speichern", tint = Color(0xFFE8FF00))
                            }
                        }
                    } else if (backupMode) {
                        // Haken erscheint, sobald mindestens eine Datei markiert ist
                        if (uiState.configBackupLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFE8FF00)
                            )
                        } else if (selectedPaths.isNotEmpty()) {
                            IconButton(onClick = { viewModel.fetchConfigsForBackup(selectedPaths.toList()) }) {
                                Icon(Icons.Default.Check, contentDescription = "Backup erstellen", tint = Color(0xFF4CAF50))
                            }
                        }
                    } else {
                        IconButton(onClick = { showUploadDialog = true }) {
                            if (uiState.configUploadLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFE8FF00)
                                )
                            } else {
                                Icon(Icons.Default.Add, contentDescription = "Datei hochladen", tint = Color(0xFFE8FF00))
                            }
                        }
                        IconButton(onClick = { backupMode = true }) {
                            Icon(Icons.Default.Save, contentDescription = "Konfiguration sichern", tint = Color(0xFFE8FF00))
                        }
                        IconButton(onClick = { viewModel.loadConfigFiles() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Neu laden")
                        }
                        IconButton(onClick = { showFirmwareRestartConfirm = true }) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Firmware neu starten",
                                tint = Color(0xFFFF9800)
                            )
                        }
                        IconButton(onClick = { showRestartConfirm = true }) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Host neu starten",
                                tint = Color(0xFFEF5350)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        }
    ) { padding ->
        if (showFirmwareRestartConfirm) {
            AlertDialog(
                onDismissRequest = { showFirmwareRestartConfirm = false },
                containerColor = Color(0xFF1E1E1E),
                icon = {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color(0xFFFF9800))
                },
                title = {
                    Text("Firmware neu starten?", color = Color(0xFFEEEEEE), fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Nur die Klipper-Firmware wird neu gestartet. Laufende Drucke werden abgebrochen.",
                        color = Color(0xFFAAAAAA)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showFirmwareRestartConfirm = false
                            viewModel.firmwareRestart()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Neu starten", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFirmwareRestartConfirm = false }) {
                        Text("Abbrechen", color = Color(0xFF888888))
                    }
                }
            )
        }

        if (showRestartConfirm) {
            AlertDialog(
                onDismissRequest = { showRestartConfirm = false },
                containerColor = Color(0xFF1E1E1E),
                icon = {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color(0xFFEF5350))
                },
                title = {
                    Text("Host neu starten?", color = Color(0xFFEEEEEE), fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Klipper und Moonraker werden neu gestartet. Laufende Drucke werden abgebrochen.",
                        color = Color(0xFFAAAAAA)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRestartConfirm = false
                            viewModel.restartHost()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                    ) {
                        Text("Neu starten")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestartConfirm = false }) {
                        Text("Abbrechen", color = Color(0xFF888888))
                    }
                }
            )
        }

        if (showUploadDialog) {
            UploadFileDialog(
                onDismiss = { showUploadDialog = false },
                onPickFiles = {
                    showUploadDialog = false
                    uploadLauncher.launch("*/*")
                }
            )
        }

        val backupFiles = uiState.pendingBackupFiles
        if (backupFiles != null) {
            BackupActionDialog(
                count = backupFiles.size,
                onDismiss = { viewModel.clearPendingBackup() },
                onSaveLocal = {
                    val saved = saveConfigsToDownloads(context, backupFiles)
                    android.widget.Toast.makeText(
                        context,
                        if (saved > 0) "$saved Datei(en) unter Downloads/KlipperRemote gespeichert"
                        else "Speichern fehlgeschlagen",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    viewModel.clearPendingBackup()
                    exitBackupMode()
                },
                onShare = {
                    shareConfigs(context, backupFiles)
                    viewModel.clearPendingBackup()
                    exitBackupMode()
                }
            )
        }

        if (isEditing) {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (configSearchMode) {
                    ConfigSearchBar(
                        query = configSearchQuery,
                        matchCount = configSearchMatchCount,
                        onQueryChange = { configSearchQuery = it },
                        onClose = { configSearchMode = false; configSearchQuery = "" }
                    )
                }
                ConfigEditor(
                    content = uiState.editingConfigContent,
                    error = uiState.editingConfigError,
                    onContentChange = { viewModel.updateEditingConfigContent(it) },
                    searchQuery = configSearchQuery,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            ConfigFileList(
                files = uiState.configFiles,
                isLoading = uiState.configFilesLoading,
                backupMode = backupMode,
                selectedPaths = selectedPaths,
                onFileClick = { file ->
                    if (backupMode) {
                        if (selectedPaths.contains(file.path)) selectedPaths.remove(file.path)
                        else selectedPaths.add(file.path)
                    } else {
                        viewModel.openConfigFile(file.path)
                    }
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

// ── Upload: Datei-Auswahl-Dialog ────────────────────────────────────────────────

@Composable
private fun UploadFileDialog(
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        icon = { Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color(0xFFE8FF00)) },
        title = {
            Text("Datei hochladen", color = Color(0xFFEEEEEE), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Wähle eine oder mehrere Dateien aus, um sie als Konfigurationsdatei zum Drucker hochzuladen. " +
                        "Eine bestehende Datei mit gleichem Namen wird überschrieben.",
                    color = Color(0xFFAAAAAA)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPickFiles,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8FF00))
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Dateien auswählen", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = Color(0xFF888888))
            }
        }
    )
}

// Liest die per System-Auswahl gewählten Dateien (Inhalt + Anzeigename) ein.
private fun readPickedFiles(
    context: android.content.Context,
    uris: List<android.net.Uri>
): List<BackupConfigFile> {
    val result = mutableListOf<BackupConfigFile>()
    for (uri in uris) {
        runCatching {
            val name = queryDisplayName(context, uri) ?: "upload.cfg"
            val content = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@runCatching
            result.add(BackupConfigFile(name, content))
        }
    }
    return result
}

// Ermittelt den Anzeigenamen einer Content-Uri (DISPLAY_NAME), sonst den letzten Pfadteil.
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

// ── Backup: Speichern/Teilen-Dialog ─────────────────────────────────────────────

@Composable
private fun BackupActionDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSaveLocal: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        icon = { Icon(Icons.Default.Save, contentDescription = null, tint = Color(0xFFE8FF00)) },
        title = {
            Text("Backup ($count)", color = Color(0xFFEEEEEE), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Möchtest du die ausgewählten Konfigurationsdateien lokal speichern oder teilen?",
                    color = Color(0xFFAAAAAA)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onSaveLocal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8FF00))
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lokal speichern", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFFE8FF00), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Teilen…", color = Color(0xFFE8FF00))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = Color(0xFF888888))
            }
        }
    )
}

// ── Backup: Dateien lokal speichern / teilen ────────────────────────────────────

// Schreibt die Backup-Dateien in den öffentlichen Downloads/KlipperRemote-Ordner.
// Gibt die Anzahl erfolgreich gespeicherter Dateien zurück.
private fun saveConfigsToDownloads(
    context: android.content.Context,
    files: List<BackupConfigFile>
): Int {
    var saved = 0
    for (file in files) {
        val bytes = file.content.toByteArray(Charsets.UTF_8)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/KlipperRemote")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(it, values, null, null)
                    saved++
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val sub = java.io.File(dir, "KlipperRemote").apply { mkdirs() }
                java.io.File(sub, file.name).writeBytes(bytes)
                saved++
            }
        }
    }
    return saved
}

// Schreibt die Backup-Dateien in den Cache und öffnet das Android-Share-Sheet.
private fun shareConfigs(
    context: android.content.Context,
    files: List<BackupConfigFile>
) {
    runCatching {
        val cacheDir = java.io.File(context.cacheDir, "config_backups").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val uris = ArrayList<android.net.Uri>()
        val authority = "${context.packageName}.fileprovider"
        for (file in files) {
            val out = java.io.File(cacheDir, file.name)
            out.writeBytes(file.content.toByteArray(Charsets.UTF_8))
            uris.add(FileProvider.getUriForFile(context, authority, out))
        }
        val intent = if (uris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = android.content.Intent.createChooser(intent, "Konfiguration teilen")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

// ── Dateiliste ────────────────────────────────────────────────────────────────

@Composable
private fun ConfigFileList(
    files: List<ConfigFile>,
    isLoading: Boolean,
    backupMode: Boolean,
    selectedPaths: List<String>,
    onFileClick: (ConfigFile) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        when {
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFFE8FF00)
            )
            files.isEmpty() -> Text(
                "Keine Konfigurationsdateien gefunden",
                color = Color(0xFF888888),
                modifier = Modifier.align(Alignment.Center)
            )
            else -> {
                val grouped = files.groupBy { it.directory }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    grouped.forEach { (dir, dirFiles) ->
                        if (dir.isNotBlank()) {
                            item {
                                Text(
                                    dir,
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                                )
                            }
                        }
                        items(dirFiles, key = { it.path }) { file ->
                            ConfigFileItem(
                                file = file,
                                backupMode = backupMode,
                                selected = selectedPaths.contains(file.path),
                                onClick = { onFileClick(file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigFileItem(
    file: ConfigFile,
    backupMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (backupMode && selected) Color(0xFF2A2E14) else Color(0xFF1E1E1E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (backupMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFFE8FF00),
                    uncheckedColor = Color(0xFF888888),
                    checkmarkColor = Color.Black
                )
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.filename,
                color = Color(0xFFEEEEEE),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (file.size > 0) {
                Text(
                    formatFileSize(file.size),
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
            }
        }
        if (!backupMode) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

// ── Config-Suchleiste ─────────────────────────────────────────────────────────

@Composable
private fun ConfigSearchBar(
    query: String,
    matchCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = Color(0xFF888888),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                color = Color(0xFFEEEEEE),
                fontSize = 14.sp,
                fontFamily = FontFamily.Default
            ),
            cursorBrush = SolidColor(Color(0xFFE8FF00)),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text("In Konfiguration suchen…", color = Color(0xFF555555), fontSize = 14.sp)
                    }
                    innerTextField()
                }
            }
        )
        if (query.isNotEmpty()) {
            Text(
                "$matchCount Treffer",
                color = if (matchCount > 0) Color(0xFFE8FF00) else Color(0xFFEF5350),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Suche schließen",
                tint = Color(0xFF888888),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Editor ────────────────────────────────────────────────────────────────────

@Composable
private fun ConfigEditor(
    content: String,
    error: String?,
    onContentChange: (String) -> Unit,
    searchQuery: String = "",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        if (error != null) {
            Text(
                error,
                color = Color(0xFFEF5350),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A1A1A))
                    .padding(8.dp)
            )
        }
        if (content.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE8FF00))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    textStyle = TextStyle(
                        color = Color(0xFFEEEEEE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(Color(0xFFE8FF00)),
                    visualTransformation = remember(searchQuery) { CfgSyntaxTransformation(searchQuery) }
                )
            }
        }
    }
}
