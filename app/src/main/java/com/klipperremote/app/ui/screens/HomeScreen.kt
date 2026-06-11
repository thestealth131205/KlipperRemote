package com.klipperremote.app.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.WebcamConfig
import com.klipperremote.app.data.model.WebcamStreamType
import com.klipperremote.app.ui.theme.*
import com.klipperremote.app.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToMachine: () -> Unit = {},
    onOpenGCodeViewer: (String) -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var setTempTarget by remember { mutableStateOf<TemperatureInfo?>(null) }
    var showWebcamSettings by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var showGcodeFileBrowser by remember { mutableStateOf(false) }
    var gcodeConfirmFile by remember { mutableStateOf<String?>(null) }

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
                onStartPrint = { showGcodeFileBrowser = true }
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

                    // Temperaturen header with optional power button
                    item {
                        SectionHeader(title = "Temperaturen") {
                            if (powerDevices.isNotEmpty()) {
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
                    }

                    // Temperature grid
                    item {
                        if (uiState.isLoading && uiState.temperatures.isEmpty()) {
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

                    // Webcam card
                    item {
                        WebcamCard(
                            host = uiState.config.host,
                            webcamConfig = uiState.webcamConfig
                        )
                    }

                    // Bewegungsbereich header
                    item {
                        SectionHeader(title = "Bewegen")
                    }

                    // Bewegungsbereich
                    item {
                        BewegungsSection(
                            position = uiState.position,
                            onJog = { axis, dist -> viewModel.jogMove(axis, dist) },
                            onHome = { axes -> viewModel.homeAxes(axes) }
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
                        items(uiState.files, key = { it.filename }) { file ->
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
fun WebcamCard(host: String, webcamConfig: WebcamConfig) {
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
                val streamUrl = remember(host, webcamConfig.customUrl, webcamConfig.streamType) {
                    webcamConfig.resolveStreamUrl(host)
                }
                key(streamUrl) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = WebViewClient()
                                loadUrl(streamUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
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
}

// ── Bewegungsbereich ───────────────────────────────────────────────────────────

@Composable
fun BewegungsSection(
    position: KlipperPosition,
    onJog: (axis: String, dist: Float) -> Unit,
    onHome: (axes: String) -> Unit
) {
    var stepMm by remember { mutableStateOf(10f) }
    val stepOptions = listOf(0.1f, 1f, 10f, 50f)

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
            onHomeAll = { onHome("") }
        )
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
    onHomeAll: () -> Unit
) {
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
    onStartPrint: () -> Unit = {}
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
            Button(
                onClick = onStartPrint,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    "Druck starten",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = if (printerState == "printing") AccentYellow else OnSurfaceDim,
                modifier = Modifier.size(22.dp)
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
    var showCrownestPicker by remember { mutableStateOf(false) }

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
                                    val host = uiState.config.host
                                    streamUrl  = "http://$host:${cam.port}$streamPath"
                                    snapshotUrl = "http://$host:${cam.port}$snapPath"
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
                // Crownest Auto-Detect
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
                    Text("Aus crowsnest.conf laden", fontSize = 13.sp)
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
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("URL Stream") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors
                )
                OutlinedTextField(
                    value = snapshotUrl,
                    onValueChange = { snapshotUrl = it },
                    label = { Text("URL Snapshot") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors
                )
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
                            flipV = flipV
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
