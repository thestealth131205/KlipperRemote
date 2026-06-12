package com.klipperremote.app.ui.screens

import android.content.res.Configuration
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.FanInfo
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import com.klipperremote.app.data.model.WebcamStreamType
import com.klipperremote.app.ui.theme.*
import com.klipperremote.app.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToMachine: () -> Unit = {},
    onOpenGCodeViewer: (String) -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var setTempTarget by remember { mutableStateOf<TemperatureInfo?>(null) }
    var showWebcamSettings by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var showGcodeFileBrowser by remember { mutableStateOf(false) }
    var gcodeConfirmFile by remember { mutableStateOf<String?>(null) }
    var showTuningDialog by remember { mutableStateOf(false) }
    var showPauseConfirm by remember { mutableStateOf(false) }

    val powerDevices = uiState.powerDevices
    val isPowerOn = powerDevices.any { it.status == "on" }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            BottomControlBar(
                printerState = uiState.printerState,
                onNavigateToAppConfig = onNavigateToSettings,
                onOpenWebcamConfig = { showWebcamSettings = true },
                onNavigateToMachine = onNavigateToMachine,
                onStartPrint = { showGcodeFileBrowser = true },
                onPausePrint = { showPauseConfirm = true },
                onCancelPrint = { viewModel.cancelPrint() },
                onNavigateToCrashLog = onNavigateToCrashLog,
                onCoolDown = { viewModel.coolDown() }
            )
        }
    ) { padding ->
        when {
            uiState.config.host.isBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = OnSurfaceDim
                        )
                        Text(
                            "Kein Klipper konfiguriert",
                            color = OnSurfaceDim,
                            fontSize = 16.sp
                        )
                        Button(
                            onClick = onNavigateToSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
                        ) {
                            Text("Einstellungen öffnen", color = Color.Black)
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Error banner
                    uiState.error?.let { err ->
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        err,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.clearError() },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Druckinfos (nur während aktivem Druck)
                    val isPrinting = uiState.printerState == "printing" || uiState.printerState == "paused"
                    uiState.printStats?.let { stats ->
                        if (isPrinting) {
                            item {
                                PrintInfoPanel(
                                    stats = stats,
                                    currentSpeed = uiState.printSpeedMmPerSec,
                                    zHeight = uiState.position.z
                                )
                            }
                        }
                    }

                    // Temperaturen header with power button (always visible)
                    item {
                        SectionHeader(title = "Temperaturen") {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isPowerOn) AccentYellow.copy(alpha = 0.15f)
                                        else Color.Transparent
                                    )
                                    .clickable { showPowerDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    contentDescription = "Drucker-Power",
                                    tint = if (isPowerOn) AccentYellow else OnSurfaceDim.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Temperature grid
                    item {
                        if (uiState.connectionFailed && uiState.temperatures.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1C1C1C)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.WifiOff,
                                        contentDescription = null,
                                        tint = OnSurfaceDim,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            "Keine Verbindung",
                                            color = OnSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "Drucker nicht erreichbar – Einstellungen prüfen",
                                            color = OnSurfaceDim,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        } else if (uiState.isLoading && uiState.temperatures.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = AccentYellow,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        } else {
                            TemperatureGrid(
                                temps = uiState.temperatures,
                                onSetTemp = { setTempTarget = it }
                            )
                        }
                    }

                    // Webcam section header
                    item {
                        SectionHeader(title = "Webcam") {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showWebcamSettings = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = AccentYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Tuning-Leiste (nur während aktivem Druck)
                    if (isPrinting) {
                        item {
                            TuningBar(
                                tuningData = uiState.tuningData,
                                onOpenTuning = { showTuningDialog = true }
                            )
                        }
                    }

                    // Webcam card
                    item {
                        val ctx = LocalContext.current
                        WebcamCard(
                            host = uiState.config.host,
                            port = uiState.config.port,
                            webcamConfig = uiState.webcamConfig,
                            apiKey = uiState.config.apiKey,
                            printProgress = uiState.printProgress,
                            printSpeedMmPerSec = uiState.printSpeedMmPerSec,
                            temperatures = uiState.temperatures,
                            position = uiState.position,
                            printerState = uiState.printerState,
                            onPausePrint = { showPauseConfirm = true },
                            onResumePrint = { viewModel.resumePrint() },
                            onSaveSnapshot = { viewModel.saveWebcamSnapshot(ctx) }
                        )
                    }

                    // Bewegungsbereich header
                    item {
                        SectionHeader(title = "Bewegen") {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2A2A2A))
                                    .clickable { viewModel.motorsOff() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Motor Abschalten",
                                    color = OnSurfaceDim,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Bewegungsbereich
                    item {
                        BewegungsSection(
                            position = uiState.position,
                            onJog = { axis, dist -> viewModel.jogMove(axis, dist) },
                            onHome = { axes -> viewModel.homeAxes(axes) },
                            pinnedGcodes = uiState.pinnedGcodes,
                            macros = uiState.macros,
                            favoriteMacros = uiState.favoriteMacros,
                            onSendGcode = { viewModel.sendGcode(it) },
                            onMoveToXyz = { x, y, z, feed -> viewModel.moveToXyz(x, y, z, feed) },
                            onToggleFavorite = { viewModel.toggleMacroFavorite(it) }
                        )
                    }

                    // Druckdateien
                    if (uiState.files.isNotEmpty()) {
                        item {
                            SectionHeader(title = "Druckdateien") {
                                IconButton(
                                    onClick = { viewModel.loadFiles() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Aktualisieren",
                                        tint = AccentYellow,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        items(uiState.files, key = { "${it.filename}_${it.modified}" }) { file ->
                            PrintFileRow(
                                file = file,
                                onPrint = { viewModel.startPrint(file.filename) },
                                onViewGCode = { onOpenGCodeViewer(file.filename) }
                            )
                        }
                    }

                    // Bottom spacing
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // Set temperature dialog
    setTempTarget?.let { temp ->
        SetTempDialog(
            temp = temp,
            onConfirm = { target ->
                viewModel.setTemperature(temp.name, target)
                setTempTarget = null
            },
            onDismiss = { setTempTarget = null }
        )
    }

    // Webcam settings dialog
    if (showWebcamSettings) {
        WebcamSettingsDialog(
            current = uiState.webcamConfig,
            onSave = { config ->
                viewModel.saveWebcamConfig(config)
                showWebcamSettings = false
            },
            onDismiss = { showWebcamSettings = false }
        )
    }

    // Power dialog
    if (showPowerDialog) {
        PowerDialog(
            devices = powerDevices,
            onToggle = { name, on -> viewModel.togglePowerDevice(name, on) },
            onDismiss = { showPowerDialog = false }
        )
    }

    // Pause-Bestätigungs-Dialog
    if (showPauseConfirm) {
        AlertDialog(
            onDismissRequest = { showPauseConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            icon = {
                Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFFF9800))
            },
            title = {
                Text("Druck pausieren?", color = OnSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Möchtest du den laufenden Druck wirklich pausieren?",
                    color = OnSurfaceDim
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPauseConfirm = false
                        viewModel.pausePrint()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Pausieren", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPauseConfirm = false }) {
                    Text("Abbrechen", color = OnSurfaceDim)
                }
            }
        )
    }

    // Tuning-Dialog
    if (showTuningDialog) {
        TuningDialog(
            tuningData = uiState.tuningData,
            onSetSpeed    = { viewModel.setSpeedFactor(it) },
            onSetFlow     = { viewModel.setExtrudeFactor(it) },
            onSetPartFan  = { viewModel.setPartCoolingFan(it) },
            onSetFan      = { name, pct -> viewModel.setGenericFanSpeed(name, pct) },
            onDismiss     = { showTuningDialog = false }
        )
    }

    // Success Snackbar overlay
    uiState.setTempSuccess?.let { msg ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A3A1A),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(msg, color = OnSurface, fontSize = 14.sp)
                }
            }
        }
    }

    // G-Code Datei-Browser
    if (showGcodeFileBrowser) {
        GcodeFileBrowserDialog(
            files = uiState.files,
            onSelectFile = { filename ->
                showGcodeFileBrowser = false
                gcodeConfirmFile = filename
                viewModel.loadFiles()
                viewModel.loadGcodePreview(filename)
            },
            onPreviewFile = { filename ->
                showGcodeFileBrowser = false
                onOpenGCodeViewer(filename)
            },
            onDismiss = { showGcodeFileBrowser = false }
        )
    }

    // Druck-Bestätigungsdialog
    gcodeConfirmFile?.let { filename ->
        GcodePrintConfirmDialog(
            filename = filename,
            metadata = uiState.gcodePreviewMetadata,
            thumbnail = uiState.gcodePreviewThumbnail,
            isLoading = uiState.gcodePreviewLoading,
            onConfirm = {
                viewModel.startPrint(filename)
                gcodeConfirmFile = null
                viewModel.clearGcodePreview()
            },
            onDismiss = {
                gcodeConfirmFile = null
                viewModel.clearGcodePreview()
            }
        )
    }

    // GCode result overlay
    uiState.gcodeResult?.let { msg ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A2A3A),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(msg, color = OnSurface, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Section Header ─────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = OnSurface
        )
        trailingContent?.invoke()
    }
}

// ── Temperature Grid ───────────────────────────────────────────────────────────

@Composable
fun TemperatureGrid(
    temps: List<TemperatureInfo>,
    onSetTemp: (TemperatureInfo) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        temps.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { temp ->
                    TempCard(
                        temp = temp,
                        modifier = Modifier.weight(1f),
                        onSetTemp = { onSetTemp(temp) }
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun TempCard(
    temp: TemperatureInfo,
    modifier: Modifier = Modifier,
    onSetTemp: () -> Unit
) {
    val displayName = when {
        temp.name == "extruder" -> "Extruder"
        temp.name.startsWith("extruder") -> "Extruder ${temp.name.removePrefix("extruder")}"
        temp.name == "heater_bed" -> "Heater Bed"
        temp.name.startsWith("heater_generic ") -> temp.name.removePrefix("heater_generic ")
        temp.name.startsWith("temperature_sensor ") -> temp.name.removePrefix("temperature_sensor ")
        else -> temp.name
    }
    val targetText = if (temp.target == 0f) "Aus" else "→ %.0f°C".format(temp.target)
    val isActive = temp.target > 0f

    val (icon, activeColor) = when {
        temp.name == "extruder" || temp.name.startsWith("extruder") ->
            Icons.Default.LocalFireDepartment to Color(0xFFFF6B00)
        temp.name == "heater_bed" ->
            Icons.Default.Thermostat to Color(0xFFFF8A00)
        temp.name.startsWith("heater_generic") ->
            Icons.Default.LocalFireDepartment to Color(0xFFE64A19)
        else ->
            Icons.Default.DeviceThermostat to Color(0xFF4CAF50)
    }
    val iconTint = if (isActive) activeColor else OnSurfaceDim.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1C1C1C))
            .clickable { onSetTemp() }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        color = OnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.1f°C".format(temp.current),
                        color = OnSurface,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        targetText,
                        color = if (isActive) activeColor.copy(alpha = 0.85f) else OnSurfaceDim,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(32.dp)
                )
            }
            if (temp.power > 0f) {
                LinearProgressIndicator(
                    progress = { temp.power },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = activeColor,
                    trackColor = Color.White.copy(alpha = 0.06f)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentYellow.copy(alpha = 0.12f))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Setzen",
                    color = AccentYellow,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Webcam Card ────────────────────────────────────────────────────────────────

@Composable
fun WebcamCard(
    host: String,
    port: Int = 7125,
    webcamConfig: WebcamConfig,
    apiKey: String = "",
    printProgress: Float? = null,
    printSpeedMmPerSec: Float? = null,
    temperatures: List<TemperatureInfo> = emptyList(),
    position: com.klipperremote.app.data.model.KlipperPosition = com.klipperremote.app.data.model.KlipperPosition(),
    printerState: String = "offline",
    onPausePrint: () -> Unit = {},
    onResumePrint: () -> Unit = {},
    onSaveSnapshot: () -> Unit = {}
) {
    var showFullscreen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111111))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(webcamConfig.name, color = OnSurfaceDim, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFF1744).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("● Live", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (host.isNotBlank()) {
                val streamUrl = remember(host, port, webcamConfig.customUrl, webcamConfig.streamType, apiKey) {
                    webcamConfig.resolveStreamUrl(host, port, apiKey)
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
                            Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        webViewRef?.onPause()
                    }
                }

                Box {
                    key(streamUrl) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    webViewClient = WebViewClient()
                                    loadUrl(streamUrl)
                                }.also { webViewRef = it }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    }
                    // Zoom-Button unten rechts
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xAA222222))
                            .clickable { showFullscreen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Vollbild",
                            tint = Color(0xFFAAAAAA),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color(0xFF333333),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            "Kein Host konfiguriert",
                            color = Color(0xFF333333),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showFullscreen && host.isNotBlank()) {
        val streamUrl = remember(host, port, webcamConfig.customUrl, webcamConfig.streamType, apiKey) {
            webcamConfig.resolveStreamUrl(host, port, apiKey)
        }
        WebcamFullscreenDialog(
            streamUrl = streamUrl,
            printProgress = printProgress,
            printSpeedMmPerSec = printSpeedMmPerSec,
            temperatures = temperatures,
            position = position,
            printerState = printerState,
            onPausePrint = onPausePrint,
            onResumePrint = onResumePrint,
            onSaveSnapshot = onSaveSnapshot,
            onDismiss = { showFullscreen = false }
        )
    }
}

@Composable
fun WebcamFullscreenDialog(
    streamUrl: String,
    printProgress: Float?,
    printSpeedMmPerSec: Float?,
    temperatures: List<TemperatureInfo>,
    position: com.klipperremote.app.data.model.KlipperPosition,
    printerState: String,
    onPausePrint: () -> Unit,
    onResumePrint: () -> Unit,
    onSaveSnapshot: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val fsLifecycleOwner = LocalLifecycleOwner.current
        var fsWebViewRef by remember { mutableStateOf<WebView?>(null) }

        DisposableEffect(fsLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> fsWebViewRef?.onPause()
                    Lifecycle.Event.ON_RESUME -> fsWebViewRef?.onResume()
                    else -> {}
                }
            }
            fsLifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                fsLifecycleOwner.lifecycle.removeObserver(observer)
                fsWebViewRef?.onPause()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            key(streamUrl) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            loadUrl(streamUrl)
                        }.also { fsWebViewRef = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Schließen-Button oben rechts
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xAA000000))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            if (isLandscape) {
                val hotend = temperatures.firstOrNull { it.name.startsWith("extruder") }
                val bed = temperatures.firstOrNull { it.name == "heater_bed" }

                // Stats-Overlay unten links
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color(0xBB000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    printProgress?.let {
                        Text("Fortschritt: ${(it * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    printSpeedMmPerSec?.let {
                        Text("Geschw.: ${it.toInt()} mm/s", color = Color.White, fontSize = 12.sp)
                    }
                    hotend?.let {
                        Text("Hotend: ${it.current.toInt()}°C / ${it.target.toInt()}°C", color = Color(0xFFFF8A65), fontSize = 12.sp)
                    }
                    bed?.let {
                        Text("Bett: ${it.current.toInt()}°C / ${it.target.toInt()}°C", color = Color(0xFF90CAF9), fontSize = 12.sp)
                    }
                    position.z?.let {
                        Text("Z-Höhe: ${"%.2f".format(it)} mm", color = Color(0xFFA5D6A7), fontSize = 12.sp)
                    }
                }

                // Steuerung rechts
                val isPaused = printerState == "paused"
                val isPrinting = printerState == "printing"
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isPrinting || isPaused) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA000000))
                                .clickable { if (isPaused) onResumePrint() else onPausePrint() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Fortsetzen" else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xAA000000))
                            .clickable { onSaveSnapshot() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Snapshot",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Bewegungsbereich ───────────────────────────────────────────────────────────

@Composable
fun BewegungsSection(
    position: KlipperPosition,
    onJog: (axis: String, dist: Float) -> Unit,
    onHome: (axes: String) -> Unit,
    pinnedGcodes: List<String> = emptyList(),
    macros: List<String> = emptyList(),
    favoriteMacros: List<String> = emptyList(),
    onSendGcode: (String) -> Unit = {},
    onMoveToXyz: (x: Float?, y: Float?, z: Float?, feedrate: Int) -> Unit = { _, _, _, _ -> },
    onToggleFavorite: (String) -> Unit = {}
) {
    var stepMm by remember { mutableStateOf(10f) }
    val stepOptions = listOf(0.1f, 1f, 10f, 50f)

    // Alle Schnellbefehle: pinnedGcodes zuerst, dann Makros (ohne Duplikate)
    val allCommands = remember(pinnedGcodes, macros) {
        (pinnedGcodes + macros).distinct()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            // XY Pad
            XyPadCard(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
                stepMm = stepMm,
                onJog = onJog,
                onHome = { onHome("XY") }
            )
            // Z Pad
            ZPadCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                stepMm = stepMm,
                onJog = onJog,
                onHome = { onHome("Z") }
            )
            // Step selector
            StepSelectorCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                steps = stepOptions,
                selected = stepMm,
                onSelect = { stepMm = it }
            )
        }
        // Position strip
        PositionStrip(
            position = position,
            onHomeAll = { onHome("") },
            onMoveToXyz = onMoveToXyz
        )
        // Makros als Pill-Grid (min. 2 nebeneinander, Favoriten oben)
        if (allCommands.isNotEmpty()) {
            MacroPillGrid(
                commands = allCommands,
                favorites = favoriteMacros,
                onSend = onSendGcode,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
fun MacroPillGrid(
    commands: List<String>,
    favorites: List<String>,
    onSend: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val favoriteSet = favorites.toSet()
    // Favoriten oben, dann Rest – jeweils alphabetisch
    val ordered = (commands.filter { it in favoriteSet }.sorted() +
            commands.filter { it !in favoriteSet }.sorted())
    val chunks = ordered.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { cmd ->
                    MacroPill(
                        modifier = Modifier.weight(1f),
                        cmd = cmd,
                        isFavorite = cmd in favoriteSet,
                        canAddFavorite = favorites.size < 3,
                        onSend = { onSend(cmd) },
                        onToggleFavorite = { onToggleFavorite(cmd) }
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MacroPill(
    modifier: Modifier = Modifier,
    cmd: String,
    isFavorite: Boolean,
    canAddFavorite: Boolean,
    onSend: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = if (isFavorite) Color(0xFF1C2816) else Color(0xFF1C1C1C),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSend() },
                        onLongPress = { showMenu = true }
                    )
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isFavorite) {
                    Text(
                        text = "\u2605 ",
                        color = Color(0xFFFFD700),
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = cmd,
                    color = AccentYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isFavorite) "\u2605" else "\u2606",
                            color = Color(0xFFFFD700),
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isFavorite) "Favorit entfernen" else "Favorisieren",
                            fontSize = 13.sp
                        )
                    }
                },
                onClick = {
                    if (isFavorite || canAddFavorite) onToggleFavorite()
                    showMenu = false
                },
                enabled = isFavorite || canAddFavorite
            )
        }
    }
}

@Composable
fun XyPadCard(
    modifier: Modifier = Modifier,
    stepMm: Float,
    onJog: (axis: String, dist: Float) -> Unit,
    onHome: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Y label
                Text("Y", color = OnSurfaceDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                // Up
                JogArrowButton(icon = Icons.Default.KeyboardArrowUp) { onJog("Y", stepMm) }
                // Middle row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("X", color = OnSurfaceDim, fontSize = 10.sp, modifier = Modifier.width(16.dp))
                    JogArrowButton(icon = Icons.Default.KeyboardArrowLeft) { onJog("X", -stepMm) }
                    // Home button center
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2A))
                            .clickable { onHome() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = "Home XY",
                            tint = AccentYellow,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    JogArrowButton(icon = Icons.Default.KeyboardArrowRight) { onJog("X", stepMm) }
                    Spacer(Modifier.width(16.dp))
                }
                // Down
                JogArrowButton(icon = Icons.Default.KeyboardArrowDown) { onJog("Y", -stepMm) }
            }
        }
    }
}

@Composable
fun ZPadCard(
    modifier: Modifier = Modifier,
    stepMm: Float,
    onJog: (axis: String, dist: Float) -> Unit,
    onHome: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("Z", color = OnSurfaceDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            JogArrowButton(icon = Icons.Default.KeyboardArrowUp) { onJog("Z", stepMm) }
            // Home Z
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable { onHome() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home Z",
                    tint = AccentYellow,
                    modifier = Modifier.size(18.dp)
                )
            }
            JogArrowButton(icon = Icons.Default.KeyboardArrowDown) { onJog("Z", -stepMm) }
        }
    }
}

@Composable
fun StepSelectorCard(
    modifier: Modifier = Modifier,
    steps: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Selected step display
            Text(
                text = if (selected < 1f) "%.1f".format(selected) else "%.0f".format(selected),
                color = AccentYellow,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
            Text("mm", color = OnSurfaceDim, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            // Step circles
            steps.forEach { step ->
                val isSelected = step == selected
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 22.dp else 14.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) AccentYellow
                            else Color(0xFF3A3A3A)
                        )
                        .clickable { onSelect(step) }
                )
                if (step != steps.last()) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun JogArrowButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF252525))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentYellow,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun PositionStrip(
    position: KlipperPosition,
    onHomeAll: () -> Unit,
    onMoveToXyz: (x: Float?, y: Float?, z: Float?, feedrate: Int) -> Unit
) {
    var showMoveDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PositionLabel("X", position.x)
            Spacer(Modifier.width(16.dp))
            PositionLabel("Y", position.y)
            Spacer(Modifier.width(16.dp))
            PositionLabel("Z", position.z)
            Spacer(Modifier.weight(1f))
            // Zu XYZ fahren Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable { showMoveDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = "Zu Koordinaten fahren",
                    tint = AccentYellow,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            // Home-Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable { onHomeAll() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Alle homen",
                    tint = AccentYellow,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    if (showMoveDialog) {
        MoveToXyzDialog(
            currentPosition = position,
            onDismiss = { showMoveDialog = false },
            onConfirm = { x, y, z, feed ->
                onMoveToXyz(x, y, z, feed)
                showMoveDialog = false
            }
        )
    }
}

@Composable
fun MoveToXyzDialog(
    currentPosition: KlipperPosition,
    onDismiss: () -> Unit,
    onConfirm: (x: Float?, y: Float?, z: Float?, feedrate: Int) -> Unit
) {
    var xInput by remember { mutableStateOf(currentPosition.x?.let { "%.1f".format(it) } ?: "") }
    var yInput by remember { mutableStateOf(currentPosition.y?.let { "%.1f".format(it) } ?: "") }
    var zInput by remember { mutableStateOf(currentPosition.z?.let { "%.1f".format(it) } ?: "") }
    var feedInput by remember { mutableStateOf("3000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Zu Koordinaten fahren",
                    color = OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("X (mm)", xInput, { v: String -> xInput = v }),
                    Triple("Y (mm)", yInput, { v: String -> yInput = v }),
                    Triple("Z (mm)", zInput, { v: String -> zInput = v }),
                    Triple("Vorschub (mm/min)", feedInput, { v: String -> feedInput = v })
                ).forEach { (label, value, onValue) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValue,
                        label = { Text(label, color = OnSurfaceDim, fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface,
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    xInput.toFloatOrNull(),
                    yInput.toFloatOrNull(),
                    zInput.toFloatOrNull(),
                    feedInput.toIntOrNull() ?: 3000
                )
            }) {
                Text("Fahren", color = AccentYellow, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = OnSurfaceDim)
            }
        }
    )
}

@Composable
fun PositionLabel(axis: String, value: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$axis ",
            color = OnSurfaceDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value?.let { "%.1f".format(it) } ?: "???",
            color = if (value != null) OnSurface else OnSurfaceDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Power Dialog ───────────────────────────────────────────────────────────────

@Composable
fun PowerDialog(
    devices: List<PowerDevice>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Drucker-Power",
                    color = OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    val isOn = device.status == "on"
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF2A2A2A)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    device.name,
                                    color = OnSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (isOn) "Ein" else if (device.status == "error") "Fehler" else "Aus",
                                    color = when {
                                        isOn -> AccentYellow
                                        device.status == "error" -> ErrorRed
                                        else -> OnSurfaceDim
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = isOn,
                                onCheckedChange = { on -> onToggle(device.name, on) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = AccentYellow,
                                    uncheckedThumbColor = OnSurfaceDim,
                                    uncheckedTrackColor = Color(0xFF3A3A3A)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen", color = AccentYellow)
            }
        }
    )
}

// ── Bottom Control Bar ─────────────────────────────────────────────────────────

@Composable
fun BottomControlBar(
    printerState: String,
    onNavigateToAppConfig: () -> Unit,
    onOpenWebcamConfig: () -> Unit,
    onNavigateToMachine: () -> Unit,
    onStartPrint: () -> Unit = {},
    onPausePrint: () -> Unit = {},
    onCancelPrint: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onCoolDown: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val statusText = when (printerState) {
        "ready" -> "Leerlauf"
        "printing" -> "Druckt"
        "paused" -> "Pausiert"
        "error" -> "Fehler"
        "standby" -> "Standby"
        else -> "Offline"
    }
    val statusColor = when (printerState) {
        "ready" -> OnSurface
        "printing" -> AccentYellow
        "paused" -> Color(0xFFFF9800)
        "error" -> ErrorRed
        else -> OnSurfaceDim
    }

    Surface(
        color = SurfaceDark,
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val printLabel = when (printerState) {
                "printing" -> "Druck pausieren"
                "paused" -> "Druck abbrechen"
                else -> "Druck starten"
            }
            val printIcon = when (printerState) {
                "printing" -> Icons.Default.Pause
                "paused" -> Icons.Default.Stop
                else -> Icons.Default.PlayArrow
            }
            val printAction = when (printerState) {
                "printing" -> onPausePrint
                "paused" -> onCancelPrint
                else -> onStartPrint
            }
            val printColor = when (printerState) {
                "printing" -> Color(0xFFFF9800)
                "paused" -> ErrorRed
                else -> AccentYellow
            }
            Button(
                onClick = printAction,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = printColor,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    printIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    printLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Text(
                "❄️",
                fontSize = 22.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onCoolDown() }
                    .padding(2.dp)
            )

            Text(
                statusText,
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(0.05f))

            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariant)
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Menü",
                        tint = OnSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1E1E1E))
                ) {
                    Text(
                        "Konfiguration",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    DropdownMenuItem(
                        text = { Text("App Konfiguration", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onNavigateToAppConfig() }
                    )
                    DropdownMenuItem(
                        text = { Text("Webcam Konfiguration", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onOpenWebcamConfig() }
                    )
                    DropdownMenuItem(
                        text = { Text("Maschine", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Build, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onNavigateToMachine() }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    DropdownMenuItem(
                        text = { Text("Crash Log", color = Color(0xFFFF5555)) },
                        leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFFFF5555), modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onNavigateToCrashLog() }
                    )
                }
            }
        }
    }
}

// ── Webcam Settings Dialog ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebcamSettingsDialog(
    current: WebcamConfig,
    onSave: (WebcamConfig) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf(current.name) }
    var streamUrl by remember { mutableStateOf(current.customUrl.ifBlank { "/webcam/?action=stream" }) }
    var snapshotUrl by remember { mutableStateOf(current.snapshotUrl.ifBlank { "/webcam/?action=snapshot" }) }
    var webcamPort by remember { mutableStateOf(current.webcamPort.takeIf { it > 0 }?.toString() ?: "") }
    var showCrownestPicker by remember { mutableStateOf(false) }

    // Auto-Erkennung: ersten Kamera direkt in die Felder übernehmen
    LaunchedEffect(uiState.crownestAutoDetectedCam) {
        val cam = uiState.crownestAutoDetectedCam ?: return@LaunchedEffect
        webcamPort = cam.port.toString()
        streamUrl = "/?action=stream"
        snapshotUrl = "/?action=snapshot"
        name = cam.name
        viewModel.clearCrownestAutoDetected()
    }

    // Wenn Crownest-Erkennung abgeschlossen und Auswahl-Dialog angefragt
    if (showCrownestPicker && uiState.crownestCams.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showCrownestPicker = false; viewModel.clearCrownest() },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Kamera auswählen", color = OnSurface, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.crownestCams.forEach { cam ->
                        val streamPath = if (cam.mode.contains("camera-streamer")) "/?action=stream" else "/?action=stream"
                        val snapPath  = if (cam.mode.contains("camera-streamer")) "/?action=snapshot" else "/?action=snapshot"
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    webcamPort = cam.port.toString()
                                    streamUrl  = streamPath
                                    snapshotUrl = snapPath
                                    name = cam.name
                                    showCrownestPicker = false
                                    viewModel.clearCrownest()
                                },
                            color = Color(0xFF2A2A2A),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(cam.name, color = OnSurface, fontWeight = FontWeight.SemiBold)
                                Text("Port ${cam.port} · ${cam.mode}", color = Color(0xFF888888), fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCrownestPicker = false; viewModel.clearCrownest() }) {
                    Text("Abbrechen", color = OnSurfaceDim)
                }
            }
        )
    }
    var selectedService by remember { mutableStateOf(current.streamType) }
    var fps by remember { mutableStateOf(current.fps.toString()) }
    var selectedRotate by remember { mutableStateOf(current.rotate) }
    var flipH by remember { mutableStateOf(current.flipH) }
    var flipV by remember { mutableStateOf(current.flipV) }
    var serviceExpanded by remember { mutableStateOf(false) }
    var rotateExpanded by remember { mutableStateOf(false) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentYellow,
        focusedLabelColor = AccentYellow,
        cursorColor = AccentYellow,
        focusedTextColor = OnSurface,
        unfocusedTextColor = OnSurface,
        unfocusedBorderColor = Color(0xFF3A3A3A),
        unfocusedLabelColor = OnSurfaceDim
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Text(
                "Webcam konfigurieren",
                color = OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Crownest Auto-Erkennung (direkt erste Kamera übernehmen)
                Button(
                    onClick = { viewModel.autoDetectFirstCam() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = Color(0xFF121212)
                    ),
                    enabled = !uiState.crownestDetecting
                ) {
                    if (uiState.crownestDetecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF121212))
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("URL auto-erkennen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                // Crownest manueller Picker
                Button(
                    onClick = {
                        viewModel.detectCrownest()
                        showCrownestPicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = AccentYellow
                    ),
                    enabled = !uiState.crownestDetecting
                ) {
                    if (uiState.crownestDetecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentYellow)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Aus crowsnest.conf laden (Auswahl)", fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors
                )
                OutlinedTextField(
                    value = webcamPort,
                    onValueChange = { webcamPort = it.filter { c -> c.isDigit() } },
                    label = { Text("Webcam Port (z.B. 8080)") },
                    placeholder = { Text("Leer = Moonraker-Port", color = Color(0xFF666666)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors
                )
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("Pfad Stream (z.B. /webcam/?action=stream)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors
                )
                OutlinedTextField(
                    value = snapshotUrl,
                    onValueChange = { snapshotUrl = it },
                    label = { Text("Pfad Snapshot") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors
                )
                // Vorschau der resultierenden URLs (Stream + Snapshot)
                val resolvedPort = webcamPort.toIntOrNull()?.takeIf { it > 0 } ?: uiState.config.port
                val resolvedHost = uiState.config.host
                val resolvedKey = uiState.config.apiKey
                fun buildPreviewUrl(path: String): String {
                    if (resolvedHost.isBlank() || path.isBlank()) return ""
                    val keyPart = if (resolvedKey.isNotBlank()) "?apikey=$resolvedKey" else ""
                    return if (path.startsWith("/")) "http://$resolvedHost:$resolvedPort$path$keyPart"
                    else if (resolvedKey.isNotBlank()) "$path?apikey=$resolvedKey" else path
                }
                val previewUrl = buildPreviewUrl(streamUrl.trim())
                val previewSnapshotUrl = buildPreviewUrl(snapshotUrl.trim())
                if (previewUrl.isNotBlank() || previewSnapshotUrl.isNotBlank()) {
                    Surface(
                        color = Color(0xFF0D1B2A),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (previewUrl.isNotBlank()) {
                                Text("Stream URL:", color = Color(0xFF888888), fontSize = 11.sp)
                                Text(previewUrl, color = Color(0xFF4FC3F7), fontSize = 11.sp)
                            }
                            if (previewSnapshotUrl.isNotBlank()) {
                                Text("Snapshot URL:", color = Color(0xFF888888), fontSize = 11.sp)
                                Text(previewSnapshotUrl, color = Color(0xFF4FC3F7), fontSize = 11.sp)
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = serviceExpanded,
                    onExpandedChange = { serviceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedService.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Service") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serviceExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = textFieldColors
                    )
                    ExposedDropdownMenu(
                        expanded = serviceExpanded,
                        onDismissRequest = { serviceExpanded = false }
                    ) {
                        WebcamStreamType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label, color = OnSurface) },
                                onClick = { selectedService = type; serviceExpanded = false }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fps,
                        onValueChange = { fps = it.filter { c -> c.isDigit() } },
                        label = { Text("Target FPS") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors
                    )
                    ExposedDropdownMenuBox(
                        expanded = rotateExpanded,
                        onExpandedChange = { rotateExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = "${selectedRotate}°",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Rotate") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rotateExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            colors = textFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = rotateExpanded,
                            onDismissRequest = { rotateExpanded = false }
                        ) {
                            listOf(0, 90, 180, 270).forEach { deg ->
                                DropdownMenuItem(
                                    text = { Text("${deg}°", color = OnSurface) },
                                    onClick = { selectedRotate = deg; rotateExpanded = false }
                                )
                            }
                        }
                    }
                }
                Text("Webcam-Bild spiegeln:", color = OnSurfaceDim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { flipH = !flipH }
                    ) {
                        Checkbox(
                            checked = flipH,
                            onCheckedChange = { flipH = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentYellow,
                                checkmarkColor = Color.Black
                            )
                        )
                        Text("horizontal", color = OnSurface, fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { flipV = !flipV }
                    ) {
                        Checkbox(
                            checked = flipV,
                            onCheckedChange = { flipV = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentYellow,
                                checkmarkColor = Color.Black
                            )
                        )
                        Text("vertikal", color = OnSurface, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        current.copy(
                            name = name.trim(),
                            customUrl = streamUrl.trim(),
                            snapshotUrl = snapshotUrl.trim(),
                            streamType = selectedService,
                            fps = fps.toIntOrNull() ?: 15,
                            rotate = selectedRotate,
                            flipH = flipH,
                            flipV = flipV,
                            webcamPort = webcamPort.toIntOrNull() ?: 0
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                )
            ) {
                Text("Speichern", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = OnSurfaceDim)
            }
        }
    )
}

// ── Print File Row ──────────────────────────────────────────────────────────────

@Composable
fun PrintFileRow(
    file: PrintFile,
    onPrint: () -> Unit,
    onViewGCode: () -> Unit
) {
    val sizeText = when {
        file.size >= 1_048_576 -> "%.1f MB".format(file.size / 1_048_576f)
        file.size >= 1_024 -> "%.0f KB".format(file.size / 1_024f)
        else -> "${file.size} B"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = OnSurfaceDim,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.filename.substringAfterLast('/'),
                    color = OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(sizeText, color = OnSurfaceDim, fontSize = 11.sp)
            }
            // G-Code Viewer Button
            IconButton(onClick = onViewGCode, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription = "G-Code anzeigen",
                    tint = AccentYellow,
                    modifier = Modifier.size(20.dp)
                )
            }
            // Druck starten Button
            IconButton(onClick = onPrint, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Druck starten",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Set Temperature Dialog ─────────────────────────────────────────────────────

// ── Print Info Panel ─────────────────────────────────────────────────────────

@Composable
fun PrintInfoPanel(
    stats: PrintStats,
    currentSpeed: Float?,
    zHeight: Float?
) {
    val remainingSecs = if (stats.progress > 0.01f) {
        (stats.printDuration / stats.progress * (1f - stats.progress)).toLong()
    } else null

    val remainingText = remainingSecs?.let {
        val h = it / 3600L
        val m = (it % 3600L) / 60L
        "%02d:%02d h".format(h, m)
    } ?: "--:-- h"

    val etaText = remainingSecs?.let {
        val etaMillis = System.currentTimeMillis() + it * 1000L
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = etaMillis }
        "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    } ?: "--:--"

    val speedText    = currentSpeed?.let { "${it.toInt()} mm/s" } ?: "-- mm/s"
    val maxVelText   = stats.maxVelocity?.let { "${it.toInt()} mm/s" } ?: "-- mm/s"
    val volumeText   = stats.volumetricFlow?.let { "%.1f mm³/s".format(it) } ?: "-- mm³/s"
    val filamentText = "%.1f mm".format(stats.filamentUsed)
    val layerText = when {
        stats.currentLayer != null && stats.totalLayers != null -> "${stats.currentLayer} / ${stats.totalLayers}"
        stats.currentLayer != null -> "${stats.currentLayer}"
        else -> "Nicht verfügbar"
    }
    val zText = zHeight?.let { "%.2f mm".format(it) } ?: "-- mm"
    val filename = stats.filename.substringAfterLast('/').let {
        if (it.length > 32) it.take(29) + "…" else it
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintInfoCard("Verbleibend", remainingText, Modifier.weight(1f))
            PrintInfoCard("ETA",         etaText,       Modifier.weight(1f))
            PrintInfoCard("Geschw.",     speedText,     Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintInfoCard("Max Geschw.", maxVelText,   Modifier.weight(1f))
            PrintInfoCard("Volumen",     volumeText,   Modifier.weight(1f))
            PrintInfoCard("Filament",    filamentText, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintInfoCard("Ebene",  layerText, Modifier.weight(1f))
            PrintInfoCard("Z Höhe", zText,     Modifier.weight(1f))
        }
        PrintInfoCard("Druckname", filename, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PrintInfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1C))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Text(label, color = OnSurfaceDim, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Tuning Bar ────────────────────────────────────────────────────────────────

@Composable
fun TuningBar(
    tuningData: TuningData,
    onOpenTuning: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1C1C1C)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Tuning", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Air,   contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(15.dp))
            Text("${tuningData.partCoolingFan} %", color = OnSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.Tune,  contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(15.dp))
            Text("${tuningData.extrudeFactor} %",  color = OnSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.Speed, contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(15.dp))
            Text("${tuningData.speedFactor} %",    color = OnSurfaceDim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable { onOpenTuning() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Tuning öffnen",
                    tint = AccentYellow,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ── Tuning Dialog ─────────────────────────────────────────────────────────────

@Composable
fun TuningDialog(
    tuningData: TuningData,
    onSetSpeed:   (Int) -> Unit,
    onSetFlow:    (Int) -> Unit,
    onSetPartFan: (Int) -> Unit,
    onSetFan:     (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Text("Tuning", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TuningAdjustRow("Geschwindigkeit",  tuningData.speedFactor,    "%", 5, 10,  500, onSetSpeed)
                TuningAdjustRow("Durchflussrate",   tuningData.extrudeFactor,  "%", 5, 10,  200, onSetFlow)
                TuningAdjustRow("Lüfter (Kühlung)", tuningData.partCoolingFan, "%", 5,  0,  100, onSetPartFan)
                tuningData.fans.forEach { fan ->
                    TuningAdjustRow(fan.displayName, fan.speedPercent, "%", 5, 0, 100) { pct ->
                        onSetFan(fan.keyName, pct)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen", color = AccentYellow)
            }
        }
    )
}

@Composable
private fun TuningAdjustRow(
    label: String,
    value: Int,
    unit: String,
    step: Int,
    min: Int,
    max: Int,
    onSet: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF2A2A2A),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = AccentYellow, fontSize = 11.sp)
                Text(
                    "$value $unit",
                    color = OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val canDec = value > min
                val canInc = value < max
                // −5
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canDec) Color(0xFF3A3A3A) else Color(0xFF252525))
                        .clickable(enabled = canDec) { onSet((value - step).coerceAtLeast(min)) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "−5",
                        color = if (canDec) OnSurface else OnSurfaceDim.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // −1
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canDec) Color(0xFF3A3A3A) else Color(0xFF252525))
                        .clickable(enabled = canDec) { onSet((value - 1).coerceAtLeast(min)) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "−1",
                        color = if (canDec) OnSurface else OnSurfaceDim.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // +1
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canInc) Color(0xFF3A3A3A) else Color(0xFF252525))
                        .clickable(enabled = canInc) { onSet((value + 1).coerceAtMost(max)) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+1",
                        color = if (canInc) OnSurface else OnSurfaceDim.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // +5
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canInc) Color(0xFF3A3A3A) else Color(0xFF252525))
                        .clickable(enabled = canInc) { onSet((value + step).coerceAtMost(max)) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+5",
                        color = if (canInc) OnSurface else OnSurfaceDim.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetTempDialog(
    temp: TemperatureInfo,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val displayName = when {
        temp.name == "extruder" -> "Extruder"
        temp.name == "heater_bed" -> "Heater Bed"
        temp.name.startsWith("heater_generic ") -> temp.name.removePrefix("heater_generic ")
        temp.name.startsWith("temperature_sensor ") -> temp.name.removePrefix("temperature_sensor ")
        else -> temp.name
    }
    var inputValue by remember(temp.name) {
        mutableStateOf(temp.target.toInt().let { if (it == 0) "" else it.toString() })
    }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF222222),
        title = {
            Text(
                "Zieltemperatur: $displayName",
                color = OnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Aktuell: %.1f°C".format(temp.current),
                    color = OnSurfaceDim,
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it.filter { c -> c.isDigit() } },
                    label = { Text("Zieltemperatur") },
                    suffix = { Text("°C") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        val target = inputValue.toFloatOrNull() ?: return@KeyboardActions
                        onConfirm(target)
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentYellow,
                        focusedLabelColor = AccentYellow,
                        cursorColor = AccentYellow,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(0, 60, 100, 200, 250).forEach { preset ->
                        FilterChip(
                            selected = inputValue == preset.toString(),
                            onClick = { inputValue = preset.toString() },
                            label = {
                                Text(
                                    if (preset == 0) "Aus" else "${preset}°",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentYellow,
                                selectedLabelColor = Color.Black,
                                containerColor = SurfaceVariant,
                                labelColor = OnSurfaceDim
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = inputValue == preset.toString(),
                                borderColor = SurfaceVariant,
                                selectedBorderColor = AccentYellow
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = inputValue.toFloatOrNull() ?: 0f
                    onConfirm(target)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                )
            ) {
                Text("Setzen", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = OnSurfaceDim)
            }
        }
    )
}
