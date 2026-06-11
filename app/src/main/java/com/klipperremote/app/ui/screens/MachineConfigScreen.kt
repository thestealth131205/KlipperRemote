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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.viewmodel.MainViewModel

// ── Syntax-Highlighting ────────────────────────────────────────────────────────

private val CfgSectionColor = Color(0xFFE8FF00) // Neon-Gelb für [section]
private val CfgKeyColor     = Color(0xFF87CEEB) // Hellblau  für key
private val CfgCommentColor = Color(0xFF888888) // Grau      für # Kommentar
private val CfgEqualColor   = Color(0xFFAAAAAA) // Grau      für : / =
private val CfgValueColor   = Color(0xFFEEEEEE) // Fast-Weiß für Werte

private fun buildHighlighted(text: String): AnnotatedString = buildAnnotatedString {
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
}

private object CfgSyntaxTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(buildHighlighted(text.text), OffsetMapping.Identity)
}

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isEditing = uiState.editingConfigPath != null

    LaunchedEffect(Unit) {
        if (uiState.configFiles.isEmpty()) viewModel.loadConfigFiles()
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditing) uiState.editingConfigPath!!.substringAfterLast('/')
                        else "Maschine",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing) viewModel.closeConfigEditor() else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (isEditing) {
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
                    } else {
                        IconButton(onClick = { viewModel.loadConfigFiles() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Neu laden")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        }
    ) { padding ->
        if (isEditing) {
            ConfigEditor(
                content = uiState.editingConfigContent,
                error = uiState.editingConfigError,
                onContentChange = { viewModel.updateEditingConfigContent(it) },
                modifier = Modifier.padding(padding)
            )
        } else {
            ConfigFileList(
                files = uiState.configFiles,
                isLoading = uiState.configFilesLoading,
                onFileClick = { viewModel.openConfigFile(it.path) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

// ── Dateiliste ────────────────────────────────────────────────────────────────

@Composable
private fun ConfigFileList(
    files: List<ConfigFile>,
    isLoading: Boolean,
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
                            ConfigFileItem(file = file, onClick = { onFileClick(file) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigFileItem(file: ConfigFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
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
        Icon(
            Icons.Default.Edit,
            contentDescription = null,
            tint = Color(0xFF555555),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

// ── Editor ────────────────────────────────────────────────────────────────────

@Composable
private fun ConfigEditor(
    content: String,
    error: String?,
    onContentChange: (String) -> Unit,
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
                    visualTransformation = CfgSyntaxTransformation
                )
            }
        }
    }
}
