package com.klipperremote.app.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.ConsoleEntry
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.FanInfo
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.PrinterProfile
import com.klipperremote.app.data.model.RoutineData
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.Timelapse
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import com.klipperremote.app.data.model.WebcamStreamType
import com.klipperremote.app.ui.theme.*
import com.klipperremote.app.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToMachine: () -> Unit = {},
    onNavigateToDriverSettings: () -> Unit = {},
    onOpenGCodeViewer: (String) -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToSlicer: () -> Unit = {},
    onNavigateToRoutineEditor: (String?) -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val ctx0 = LocalContext.current
    var setTempTarget by remember { mutableStateOf<TemperatureInfo?>(null) }
    var showWebcamSettings by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var showGcodeFileBrowser by remember { mutableStateOf(false) }
    var gcodeConfirmFile by remember { mutableStateOf<String?>(null) }
    var showTuningDialog by remember { mutableStateOf(false) }
    var showPauseConfirm by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var showTimelapseBrowser by remember { mutableStateOf(false) }
    var showPrinterManager by remember { mutableStateOf(false) }

    val powerDevices = uiState.powerDevices
    val isPowerOn = powerDevices.any { it.status == "on" }
    val isPrinting = uiState.printerState == "printing" || uiState.printerState == "paused"
    val isLandscapeTop = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            if (uiState.config.host.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundDark)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(
                                horizontal = 16.dp,
                                vertical = if (isLandscapeTop) 4.dp else 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Dashboard",
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isLandscapeTop) 15.sp else 18.sp,
                            color = OnSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(if (isLandscapeTop) 26.dp else 32.dp)
                                .clip(CircleShape)
                                .background(if (isPowerOn) AccentYellow.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { showPowerDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = "Drucker-Power",
                                tint = if (isPowerOn) AccentYellow else OnSurfaceDim.copy(alpha = 0.5f),
                                modifier = Modifier.size(if (isLandscapeTop) 17.dp else 20.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            BottomControlBar(
                printerState = uiState.printerState,
                printers = uiState.printers,
                selectedPrinterId = uiState.selectedPrinterId,
                onNavigateToAppConfig = onNavigateToSettings,
                onOpenWebcamConfig = { showWebcamSettings = true },
                onOpenPrinterManager = { showPrinterManager = true },
                onSelectPrinter = { viewModel.selectPrinter(it) },
                onNavigateToMachine = onNavigateToMachine,
                onNavigateToDriverSettings = onNavigateToDriverSettings,
                onStartPrint = { showGcodeFileBrowser = true },
                onPausePrint = { showPauseConfirm = true },
                onCancelPrint = { viewModel.cancelPrint() },
                onNavigateToCrashLog = onNavigateToCrashLog,
                onNavigateToSlicer = onNavigateToSlicer,
                onOpenTimelapse = { viewModel.loadTimelapses(); showTimelapseBrowser = true },
                onCoolDown = { viewModel.coolDown() },
                onOpenConsole = { viewModel.loadConsole(); showConsole = true }
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
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val ctx = LocalContext.current

                // ── Shared content blocks (composable lambdas) ───────────────────────

                val ErrorBannerBlock: @Composable () -> Unit = {
                    uiState.error?.let { err ->
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
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                val PrintInfoBlock: @Composable () -> Unit = {
                    if (isPrinting) {
                        uiState.printStats?.let { stats ->
                            PrintInfoPanel(
                                stats = stats,
                                currentSpeed = uiState.printSpeedMmPerSec,
                                zHeight = uiState.position.z
                            )
                        }
                    }
                }

                val TempGridBlock: @Composable () -> Unit = {
                    when {
                        uiState.connectionFailed && uiState.temperatures.isEmpty() ->
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFF1C1C1C)) {
                                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(20.dp))
                                    Column {
                                        Text("Keine Verbindung", color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text("Drucker nicht erreichbar – Einstellungen prüfen", color = OnSurfaceDim, fontSize = 12.sp)
                                    }
                                }
                            }
                        uiState.isLoading && uiState.temperatures.isEmpty() ->
                            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AccentYellow, modifier = Modifier.size(36.dp))
                            }
                        else ->
                            TemperatureGrid(
                                temps = uiState.temperatures,
                                enabled = true,
                                onSetTemp = { setTempTarget = it },
                                temperatureHistory = uiState.temperatureHistory,
                                tempGraphMinCelsius = uiState.appConfig.tempGraphMinCelsius,
                                tempGraphMaxCelsius = uiState.appConfig.tempGraphMaxCelsius
                            )
                    }
                }

                val WebcamHeaderBlock: @Composable () -> Unit = {
                    SectionHeader(title = "Webcam") {
                        Box(modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable { showWebcamSettings = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                val TuningContentBlock: @Composable () -> Unit = {
                    if (isPrinting) TuningBar(tuningData = uiState.tuningData, onOpenTuning = { showTuningDialog = true })
                }

                val WebcamOnlyBlock: @Composable () -> Unit = {
                    WebcamCard(
                        host = uiState.config.host, port = uiState.config.port,
                        webcams = uiState.webcams.ifEmpty { listOf(uiState.webcamConfig) },
                        apiKey = uiState.config.apiKey, printProgress = uiState.printProgress,
                        printSpeedMmPerSec = uiState.printSpeedMmPerSec, temperatures = uiState.temperatures,
                        position = uiState.position, printerState = uiState.printerState,
                        onPausePrint = { showPauseConfirm = true }, onResumePrint = { viewModel.resumePrint() },
                        onSaveSnapshot = { viewModel.saveWebcamSnapshot(ctx) }
                    )
                }

                val WebcamCardBlock: @Composable () -> Unit = {
                    TuningContentBlock()
                    WebcamOnlyBlock()
                }

                val BewegenHeaderBlock: @Composable () -> Unit = {
                    SectionHeader(title = "Bewegen") {
                        Box(
                            modifier = Modifier.alpha(if (isPrinting) 0.4f else 1f)
                                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
                                .clickable(enabled = !isPrinting) { viewModel.motorsOff() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Motor Abschalten", color = OnSurfaceDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                val BewegenBlock: @Composable () -> Unit = {
                    BewegungsSection(
                        position = uiState.position, enabled = !isPrinting,
                        onJog = { axis, dist -> viewModel.jogMove(axis, dist) },
                        onHome = { axes -> viewModel.homeAxes(axes) },
                        pinnedGcodes = uiState.pinnedGcodes, macros = uiState.macros,
                        favoriteMacros = uiState.favoriteMacros,
                        onSendGcode = { viewModel.sendGcode(it) },
                        onMoveToXyz = { x, y, z, feed -> viewModel.moveToXyz(x, y, z, feed) },
                        onToggleFavorite = { viewModel.toggleMacroFavorite(it) },
                        routines = uiState.routines,
                        onRunRoutine = { viewModel.executeRoutine(it) },
                        onEditRoutine = { onNavigateToRoutineEditor(it) },
                        onCreateRoutine = { onNavigateToRoutineEditor(null) }
                    )
                }

                val FilesBlock: @Composable () -> Unit = {
                    if (uiState.files.isNotEmpty()) {
                        SectionHeader(title = "Druckdateien") {
                            IconButton(onClick = { viewModel.loadFiles() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = AccentYellow, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.files.forEach { file ->
                                PrintFileRow(
                                    file = file, printResult = uiState.printResults[file.filename],
                                    onPrint = { viewModel.startPrint(file.filename) },
                                    onViewGCode = { onOpenGCodeViewer(file.filename) }
                                )
                            }
                        }
                    }
                }

                // Dateien-Inhalt ohne Header (für Landscape-Label-Layout)
                val FilesContentBlock: @Composable () -> Unit = {
                    if (uiState.files.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.files.forEach { file ->
                                PrintFileRow(
                                    file = file, printResult = uiState.printResults[file.filename],
                                    onPrint = { viewModel.startPrint(file.filename) },
                                    onViewGCode = { onOpenGCodeViewer(file.filename) }
                                )
                            }
                        }
                    }
                }

                // ── Layout ───────────────────────────────────────────────────────────

                if (isLandscape) {
                    // Landscape: vertikale Abschnitts-Labels links, Inhalt scrollbar rechts
                    val screenW = LocalConfiguration.current.screenWidthDp.dp
                    val colW = maxOf(screenW * 0.88f, 320.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BackgroundDark)
                            .padding(padding)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        // Spalte 1: Status / Temperaturen
                        Row(modifier = Modifier.width(colW).fillMaxHeight()) {
                            LandscapeLabel(title = "Status")
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (uiState.error != null) ErrorBannerBlock()
                                PrintInfoBlock()
                                TempGridBlock()
                            }
                        }
                        // Spalte 2: Tuning (nur bei aktivem Druck)
                        if (isPrinting) {
                            Row(modifier = Modifier.width(colW).fillMaxHeight()) {
                                LandscapeLabel(title = "Tuning") {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showTuningDialog = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = "Tuning öffnen", tint = AccentYellow, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TuningBar(tuningData = uiState.tuningData, onOpenTuning = { showTuningDialog = true })
                                }
                            }
                        }
                        // Spalte 3: Webcam
                        Row(modifier = Modifier.width(colW).fillMaxHeight()) {
                            LandscapeLabel(title = "Webcam") {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showWebcamSettings = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = "Webcam Einstellungen", tint = AccentYellow, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WebcamOnlyBlock()
                            }
                        }
                        // Spalte 4: Bewegen
                        Row(modifier = Modifier.width(colW).fillMaxHeight()) {
                            LandscapeLabel(title = "Bewegen") {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .alpha(if (isPrinting) 0.4f else 1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF2A2A2A))
                                        .clickable(enabled = !isPrinting) { viewModel.motorsOff() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Motor Abschalten", tint = OnSurfaceDim, modifier = Modifier.size(14.dp))
                                }
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                BewegenBlock()
                            }
                        }
                        // Spalte 5: Druckdateien
                        Row(modifier = Modifier.width(colW).fillMaxHeight()) {
                            LandscapeLabel(title = "Dateien") {
                                IconButton(onClick = { viewModel.loadFiles() }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = AccentYellow, modifier = Modifier.size(14.dp))
                                }
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2A2A)))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilesContentBlock()
                            }
                        }
                    }
                } else {
                    // Portrait: LazyColumn
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().background(BackgroundDark).padding(padding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (uiState.error != null) item { ErrorBannerBlock() }
                        item { PrintInfoBlock() }
                        item { TempGridBlock() }
                        item { WebcamHeaderBlock() }
                        item { WebcamCardBlock() }
                        item { BewegenHeaderBlock() }
                        item { BewegenBlock() }
                        if (uiState.files.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Druckdateien") {
                                    IconButton(onClick = { viewModel.loadFiles() }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = AccentYellow, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            items(uiState.files, key = { "${it.filename}_${it.modified}" }) { file ->
                                PrintFileRow(
                                    file = file, printResult = uiState.printResults[file.filename],
                                    onPrint = { viewModel.startPrint(file.filename) },
                                    onViewGCode = { onOpenGCodeViewer(file.filename) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
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
        // Energiegeräte beim Öffnen aktualisieren (während des Drucks werden sie sonst nicht gepollt)
        LaunchedEffect(Unit) { viewModel.refreshPowerDevices() }
        PowerDialog(
            devices = powerDevices,
            isPrinting = uiState.printerState == "printing" || uiState.printerState == "paused",
            onToggle = { name, on -> viewModel.togglePowerDevice(name, on) },
            onDismiss = { showPowerDialog = false }
        )
    }

    // Drucker-Manager-Dialog
    if (showPrinterManager) {
        PrinterManagerDialog(
            printers = uiState.printers,
            selectedId = uiState.selectedPrinterId,
            onSelect = { viewModel.selectPrinter(it); showPrinterManager = false },
            onAdd = { name, host, port, key -> viewModel.addPrinter(name, host, port, key) },
            onDelete = { viewModel.deletePrinter(it) },
            onDismiss = { showPrinterManager = false }
        )
    }

    // Verbindungsfehler-Dialog (nach 3 aufeinanderfolgenden Fehlversuchen)
    if (uiState.showConnectionFailedDialog) {
        AlertDialog(
            onDismissRequest = { /* nur über die Buttons schließbar */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            containerColor = Color(0xFF1E1E1E),
            icon = {
                Icon(Icons.Default.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text("Keine Verbindung", color = OnSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Verbindung konnte nicht hergestellt werden.",
                    color = OnSurfaceDim
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.retryConnection() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
                ) {
                    Text("Erneut versuchen", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelConnection() }) {
                    Text("Abbrechen", color = OnSurfaceDim)
                }
            }
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

    // Zeitraffer-Browser
    if (showTimelapseBrowser) {
        TimelapseBrowserDialog(
            timelapses = uiState.timelapses,
            isLoading = uiState.timelapsesLoading,
            error = uiState.timelapseError,
            downloading = uiState.timelapseDownloading,
            onRefresh = { viewModel.loadTimelapses() },
            onDownload = { viewModel.downloadTimelapse(ctx0, it) },
            onDelete = { viewModel.deleteTimelapse(it.path) },
            getPlayback = { viewModel.getTimelapsePlayback(it.path) },
            onDismiss = { showTimelapseBrowser = false }
        )
    }
    uiState.timelapseDownloadResult?.let { msg ->
        LaunchedEffect(msg) {
            android.widget.Toast.makeText(ctx0, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearTimelapseDownloadResult()
        }
    }

    // Konsolen-Dialog
    if (showConsole) {
        ConsoleDialog(
            entries = uiState.consoleEntries,
            isLoading = uiState.consoleLoading,
            onRefresh = { viewModel.loadConsole() },
            onSendGcode = { viewModel.sendGcode(it) },
            onDismiss = { showConsole = false }
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
            onDismiss = { showGcodeFileBrowser = false },
            thumbnails = uiState.fileThumbnails,
            onRequestThumbnail = { viewModel.loadFileThumbnail(it) }
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

// ── Landscape Section Label ─────────────────────────────────────────────────────
// Zeigt den Abschnittstitel vertikal gestapelt (ein Buchstabe pro Zeile, oben→unten)
// und einen optionalen Action-Button ganz unten im Streifen.

@Composable
fun LandscapeLabel(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(30.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            title.forEach { ch ->
                Text(
                    text = ch.toString(),
                    color = OnSurfaceDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 12.sp
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}

// ── Temperature Grid ───────────────────────────────────────────────────────────

@Composable
fun TemperatureGrid(
    temps: List<TemperatureInfo>,
    enabled: Boolean = true,
    onSetTemp: (TemperatureInfo) -> Unit,
    temperatureHistory: Map<String, List<Pair<Long, Float>>> = emptyMap(),
    tempGraphMinCelsius: Int = 10,
    tempGraphMaxCelsius: Int = 300
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val chunks = temps.chunked(2)
        chunks.forEachIndexed { chunkIdx, pair ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { temp ->
                    TempCard(
                        temp = temp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = enabled,
                        onSetTemp = { onSetTemp(temp) }
                    )
                }
                // Last row with only one card → show temp history graph in 4th slot
                if (pair.size == 1 && chunkIdx == chunks.lastIndex && temperatureHistory.isNotEmpty()) {
                    TempHistoryCard(
                        history = temperatureHistory,
                        minCelsius = tempGraphMinCelsius,
                        maxCelsius = tempGraphMaxCelsius,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                } else if (pair.size == 1) {
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
    enabled: Boolean = true,
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
                    .alpha(if (enabled) 1f else 0.4f)
                    .clickable(enabled = enabled) { onSetTemp() }
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

// ── Temperature History Graph ───────────────────────────────────────────────────

@Composable
fun TempHistoryCard(
    history: Map<String, List<Pair<Long, Float>>>,
    minCelsius: Int = 10,
    maxCelsius: Int = 300,
    modifier: Modifier = Modifier
) {
    val rangeMin = minCelsius.toFloat()
    val rangeMax = maxCelsius.toFloat().coerceAtLeast(rangeMin + 10f)
    val rangeSpan = rangeMax - rangeMin

    // Feste Farben pro Heizer-Typ; Rest nach Index
    val baseColors = listOf(
        Color(0xFFFF6B00),  // extruder – orange
        Color(0xFF64B5F6),  // heater_bed – blau
        Color(0xFF81C784),  // 3. – grün
        Color(0xFFCE93D8),  // 4. – lila
        Color(0xFFFFCC80),  // 5. – amber
        Color(0xFF80DEEA),  // 6. – cyan
    )

    // Reihenfolge: extruder* zuerst, heater_bed zweite, Rest alphabetisch
    val sortedKeys = remember(history) {
        buildList {
            addAll(history.keys.filter { it.startsWith("extruder") }.sorted())
            if (history.containsKey("heater_bed")) add("heater_bed")
            addAll(history.keys.filter { !it.startsWith("extruder") && it != "heater_bed" }.sorted())
        }
    }
    val colorMap: Map<String, Color> = remember(sortedKeys) {
        sortedKeys.mapIndexed { i, name ->
            name to baseColors.getOrElse(i) { Color.White.copy(alpha = 0.5f) }
        }.toMap()
    }

    val yStep = when {
        rangeSpan > 250f -> 50f
        rangeSpan > 100f -> 25f
        else -> 10f
    }

    val hasEnoughData = sortedKeys.any { (history[it]?.size ?: 0) >= 2 }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1C1C1C))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                "Verlauf",
                color = OnSurfaceDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (!hasEnoughData) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Verlauf\nwird geladen…",
                        color = OnSurfaceDim,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Spacer(Modifier.height(2.dp))

                // Legende: Farblinie + Kurzname pro Heizer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sortedKeys.forEach { name ->
                        val color = colorMap[name] ?: Color.White
                        val shortName = when {
                            name == "extruder" -> "Extr."
                            name.startsWith("extruder") -> "E${name.removePrefix("extruder").trim()}"
                            name == "heater_bed" -> "Bed"
                            name.startsWith("heater_generic ") -> name.removePrefix("heater_generic ").take(8)
                            name.startsWith("temperature_sensor ") -> name.removePrefix("temperature_sensor ").take(8)
                            else -> name.take(8)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.size(10.dp, 6.dp)) {
                                drawLine(
                                    color = color,
                                    start = Offset(0f, size.height / 2f),
                                    end = Offset(size.width, size.height / 2f),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                            }
                            Spacer(Modifier.width(2.dp))
                            Text(shortName, color = color.copy(alpha = 0.85f), fontSize = 7.sp)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))

                // Graph: Y-Achse links + Zeichenfläche rechts
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Y-Achsen-Beschriftung per nativeCanvas
                    Canvas(modifier = Modifier.width(22.dp).fillMaxHeight()) {
                        val h = size.height
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(130, 200, 200, 200)
                            textSize = 7.dp.toPx()
                            textAlign = android.graphics.Paint.Align.RIGHT
                            isAntiAlias = true
                        }
                        var t = (rangeMin / yStep).toInt() * yStep
                        while (t <= rangeMax + 0.1f) {
                            if (t >= rangeMin - 0.1f) {
                                val yFrac = (t - rangeMin) / rangeSpan
                                val y = h - yFrac * h
                                drawContext.canvas.nativeCanvas.drawText(
                                    "${t.toInt()}",
                                    size.width - 1.dp.toPx(),
                                    y + 3.dp.toPx(),
                                    paint
                                )
                            }
                            t += yStep
                        }
                    }

                    Spacer(Modifier.width(3.dp))

                    // Graph-Canvas mit Gitterlinien + Temperaturkurven
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val w = size.width
                        val h = size.height
                        val now = System.currentTimeMillis()
                        val windowMs = 10L * 60 * 1000  // 10 Minuten

                        // Gitterlinien
                        var gridT = (rangeMin / yStep).toInt() * yStep
                        while (gridT <= rangeMax + 0.1f) {
                            if (gridT >= rangeMin - 0.1f) {
                                val yFrac = (gridT - rangeMin) / rangeSpan
                                val y = h - yFrac * h
                                drawLine(
                                    color = Color.White.copy(alpha = 0.08f),
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1f
                                )
                            }
                            gridT += yStep
                        }

                        // Temperaturkurven
                        sortedKeys.forEach { name ->
                            val points = history[name]?.filter { it.second >= 0f } ?: return@forEach
                            if (points.size < 2) return@forEach
                            val color = colorMap[name] ?: Color.White
                            val path = Path()
                            var moved = false
                            points.forEach { (timeMs, temp) ->
                                val xFrac = ((timeMs - (now - windowMs)).toFloat() / windowMs).coerceIn(0f, 1f)
                                val yFrac = ((temp - rangeMin) / rangeSpan).coerceIn(0f, 1f)
                                val x = xFrac * w
                                val y = h - yFrac * h
                                if (!moved) { path.moveTo(x, y); moved = true }
                                else path.lineTo(x, y)
                            }
                            drawPath(
                                path,
                                color = color,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Webcam Card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebcamCard(
    host: String,
    port: Int = 7125,
    webcams: List<WebcamConfig>,
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
    val camList = webcams.ifEmpty { listOf(WebcamConfig()) }
    val pagerState = rememberPagerState { camList.size }
    val activeCam = camList.getOrElse(pagerState.currentPage) { WebcamConfig() }
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
                Text(activeCam.name, color = OnSurfaceDim, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFF1744).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("● Live", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (camList.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("${pagerState.currentPage + 1}/${camList.size}", color = OnSurfaceDim.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }

            if (host.isNotBlank()) {
                Box {
                    if (camList.size > 1) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        ) { page ->
                            val cam = camList[page]
                            val url = remember(host, port, cam.customUrl, cam.streamType, apiKey, page) {
                                cam.resolveStreamUrl(host, port, apiKey)
                            }
                            WebcamPlayer(
                                streamUrl = url,
                                streamType = cam.streamType,
                                flipH = cam.flipH,
                                flipV = cam.flipV,
                                rotate = cam.rotate,
                                stunServer = cam.stunServer,
                                iceUsername = cam.iceUsername,
                                icePassword = cam.icePassword,
                                zoomable = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Seitenindikator-Punkte (mittig unten)
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            camList.indices.forEach { idx ->
                                Box(
                                    modifier = Modifier
                                        .size(if (idx == pagerState.currentPage) 7.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (idx == pagerState.currentPage) Color.White
                                            else Color.White.copy(alpha = 0.45f)
                                        )
                                )
                            }
                        }
                    } else {
                        val streamUrl = remember(host, port, activeCam.customUrl, activeCam.streamType, apiKey) {
                            activeCam.resolveStreamUrl(host, port, apiKey)
                        }
                        WebcamPlayer(
                            streamUrl = streamUrl,
                            streamType = activeCam.streamType,
                            flipH = activeCam.flipH,
                            flipV = activeCam.flipV,
                            rotate = activeCam.rotate,
                            stunServer = activeCam.stunServer,
                            iceUsername = activeCam.iceUsername,
                            icePassword = activeCam.icePassword,
                            zoomable = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    }
                    // Vollbild-Button unten rechts
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
        val streamUrl = remember(host, port, activeCam.customUrl, activeCam.streamType, apiKey, pagerState.currentPage) {
            activeCam.resolveStreamUrl(host, port, apiKey)
        }
        WebcamFullscreenDialog(
            streamUrl = streamUrl,
            webcamConfig = activeCam,
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
    webcamConfig: WebcamConfig,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            WebcamPlayer(
                streamUrl = streamUrl,
                streamType = webcamConfig.streamType,
                flipH = webcamConfig.flipH,
                flipV = webcamConfig.flipV,
                rotate = webcamConfig.rotate,
                stunServer = webcamConfig.stunServer,
                iceUsername = webcamConfig.iceUsername,
                icePassword = webcamConfig.icePassword,
                zoomable = true,
                modifier = Modifier.fillMaxSize()
            )

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
    enabled: Boolean = true,
    onJog: (axis: String, dist: Float) -> Unit,
    onHome: (axes: String) -> Unit,
    pinnedGcodes: List<String> = emptyList(),
    macros: List<String> = emptyList(),
    favoriteMacros: List<String> = emptyList(),
    onSendGcode: (String) -> Unit = {},
    onMoveToXyz: (x: Float?, y: Float?, z: Float?, feedrate: Int) -> Unit = { _, _, _, _ -> },
    onToggleFavorite: (String) -> Unit = {},
    routines: List<RoutineData> = emptyList(),
    onRunRoutine: (RoutineData) -> Unit = {},
    onEditRoutine: (String) -> Unit = {},
    onCreateRoutine: () -> Unit = {}
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
                enabled = enabled,
                onJog = onJog,
                onHome = { onHome("XY") }
            )
            // Z Pad
            ZPadCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                stepMm = stepMm,
                enabled = enabled,
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
            enabled = enabled,
            onHomeAll = { onHome("") },
            onMoveToXyz = onMoveToXyz
        )

        // ── Routinen sub-section ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Routinen",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = OnSurface
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AccentYellow.copy(alpha = 0.15f))
                    .clickable(enabled = routines.size < 4) { onCreateRoutine() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Routine erstellen",
                    tint = if (routines.size < 4) AccentYellow else OnSurfaceDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (routines.isEmpty()) {
            Text(
                "Keine Routinen – tippe + zum Erstellen (max. 4)",
                color = OnSurfaceDim,
                fontSize = 12.sp
            )
        } else {
            RoutineChipsGrid(
                routines = routines,
                onRun = onRunRoutine,
                onEdit = onEditRoutine
            )
        }

        // ── Makros als Pill-Grid (min. 2 nebeneinander, Favoriten oben) ────────
        if (allCommands.isNotEmpty()) {
            Text(
                "Makros",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = OnSurface
            )
            MacroPillGrid(
                commands = allCommands,
                favorites = favoriteMacros,
                enabled = enabled,
                onSend = onSendGcode,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

// ── Routine chips grid ─────────────────────────────────────────────────────────

@Composable
fun RoutineChipsGrid(
    routines: List<RoutineData>,
    onRun: (RoutineData) -> Unit,
    onEdit: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        routines.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { routine ->
                    RoutineChip(
                        routine = routine,
                        modifier = Modifier.weight(1f),
                        onRun = { onRun(routine) },
                        onEdit = { onEdit(routine.id) }
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun RoutineChip(
    routine: RoutineData,
    modifier: Modifier = Modifier,
    onRun: () -> Unit,
    onEdit: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = Color(0xFF1A2E1A),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onRun() },
                        onLongPress = { showMenu = true }
                    )
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    routine.name,
                    color = Color(0xFF4CAF50),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Bearbeiten", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { onEdit(); showMenu = false }
            )
        }
    }
}

@Composable
fun MacroPillGrid(
    commands: List<String>,
    favorites: List<String>,
    enabled: Boolean = true,
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
                        enabled = enabled,
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
    enabled: Boolean = true,
    onSend: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier.alpha(if (enabled) 1f else 0.4f)) {
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = if (isFavorite) Color(0xFF1C2816) else Color(0xFF1C1C1C),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(enabled) {
                    detectTapGestures(
                        onTap = { if (enabled) onSend() },
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
                        color = Color(0xFFFFFF00),
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
                            color = Color(0xFFFFFF00),
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
    enabled: Boolean = true,
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
                JogArrowButton(icon = Icons.Default.KeyboardArrowUp, enabled = enabled) { onJog("Y", stepMm) }
                // Middle row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("X", color = OnSurfaceDim, fontSize = 10.sp, modifier = Modifier.width(16.dp))
                    JogArrowButton(icon = Icons.Default.KeyboardArrowLeft, enabled = enabled) { onJog("X", -stepMm) }
                    // Home button center
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .alpha(if (enabled) 1f else 0.4f)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2A))
                            .clickable(enabled = enabled) { onHome() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = "Home XY",
                            tint = AccentYellow,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    JogArrowButton(icon = Icons.Default.KeyboardArrowRight, enabled = enabled) { onJog("X", stepMm) }
                    Spacer(Modifier.width(16.dp))
                }
                // Down
                JogArrowButton(icon = Icons.Default.KeyboardArrowDown, enabled = enabled) { onJog("Y", -stepMm) }
            }
        }
    }
}

@Composable
fun ZPadCard(
    modifier: Modifier = Modifier,
    stepMm: Float,
    enabled: Boolean = true,
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
            JogArrowButton(icon = Icons.Default.KeyboardArrowUp, enabled = enabled) { onJog("Z", stepMm) }
            // Home Z
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable(enabled = enabled) { onHome() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home Z",
                    tint = AccentYellow,
                    modifier = Modifier.size(18.dp)
                )
            }
            JogArrowButton(icon = Icons.Default.KeyboardArrowDown, enabled = enabled) { onJog("Z", -stepMm) }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF252525))
            .clickable(enabled = enabled) { onClick() },
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
    enabled: Boolean = true,
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
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable(enabled = enabled) { showMoveDialog = true },
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
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
                    .clickable(enabled = enabled) { onHomeAll() },
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
                    xInput.replace(',', '.').trim().toFloatOrNull(),
                    yInput.replace(',', '.').trim().toFloatOrNull(),
                    zInput.replace(',', '.').trim().toFloatOrNull(),
                    feedInput.trim().toIntOrNull() ?: 3000
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
    isPrinting: Boolean = false,
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
                    // Während des Drucks darf das Drucker-Netzteil ("printer") NICHT
                    // ausgeschaltet werden – LEDs/andere Geräte bleiben schaltbar.
                    val isPrinterPower = device.name.contains("printer", ignoreCase = true)
                    val lockedOff = isPrinting && isPrinterPower
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
                                    when {
                                        lockedOff -> "Während Druck gesperrt"
                                        isOn -> "Ein"
                                        device.status == "error" -> "Fehler"
                                        else -> "Aus"
                                    },
                                    color = when {
                                        lockedOff -> OnSurfaceDim
                                        isOn -> AccentYellow
                                        device.status == "error" -> ErrorRed
                                        else -> OnSurfaceDim
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = isOn,
                                enabled = !lockedOff,
                                onCheckedChange = { on ->
                                    // Ausschalten des Drucker-Netzteils im Druck blockieren
                                    if (!(lockedOff && !on)) onToggle(device.name, on)
                                },
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

// ── Drucker-Manager-Dialog ─────────────────────────────────────────────────────

@Composable
fun PrinterManagerDialog(
    printers: List<PrinterProfile>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAdd: (name: String, host: String, port: Int, apiKey: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showAddPrinter by remember { mutableStateOf(false) }

    if (showAddPrinter) {
        AddPrinterDialog(
            onAdd = { name, host, port, key ->
                onAdd(name, host, port, key)
                showAddPrinter = false
                onDismiss()
            },
            onDismiss = { showAddPrinter = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Print, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(20.dp))
                Text("Drucker", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                printers.forEach { printer ->
                    val isSelected = printer.id == selectedId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AccentYellow.copy(alpha = 0.1f) else Color(0xFF2A2A2A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(printer.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Print,
                                contentDescription = null,
                                tint = if (isSelected) AccentYellow else OnSurfaceDim,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(printer.name, color = if (isSelected) AccentYellow else OnSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("${printer.host}:${printer.port}", color = OnSurfaceDim, fontSize = 11.sp)
                            }
                            if (printers.size > 1) {
                                IconButton(onClick = { onDelete(printer.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { showAddPrinter = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentYellow, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Drucker hinzufügen", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Schließen", color = AccentYellow) }
        }
    )
}

@Composable
fun AddPrinterDialog(
    onAdd: (name: String, host: String, port: Int, apiKey: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("Drucker") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7125") }
    var apiKey by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentYellow, focusedLabelColor = AccentYellow,
        cursorColor = AccentYellow, focusedTextColor = OnSurface, unfocusedTextColor = OnSurface,
        unfocusedBorderColor = Color(0xFF3A3A3A), unfocusedLabelColor = OnSurfaceDim
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Drucker hinzufügen", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / IP oder Domain") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text("Port (Standard: 7125)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name.trim(), host.trim(), port.toIntOrNull() ?: 7125, apiKey.trim()) },
                enabled = host.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentYellow, contentColor = Color.Black)
            ) { Text("Hinzufügen", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = OnSurfaceDim) }
        }
    )
}

// ── Bottom Control Bar ─────────────────────────────────────────────────────────

@Composable
fun BottomControlBar(
    printerState: String,
    printers: List<PrinterProfile> = emptyList(),
    selectedPrinterId: String = "",
    onNavigateToAppConfig: () -> Unit,
    onOpenWebcamConfig: () -> Unit,
    onOpenPrinterManager: () -> Unit = {},
    onSelectPrinter: (String) -> Unit = {},
    onNavigateToMachine: () -> Unit,
    onNavigateToDriverSettings: () -> Unit = {},
    onStartPrint: () -> Unit = {},
    onPausePrint: () -> Unit = {},
    onCancelPrint: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToSlicer: () -> Unit = {},
    onOpenTimelapse: () -> Unit = {},
    onCoolDown: () -> Unit = {},
    onOpenConsole: () -> Unit = {}
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

            // Abkühlen ist während des Drucks gesperrt (würde den Druck stören)
            val isPrinting = printerState == "printing" || printerState == "paused"
            Text(
                "❄️",
                fontSize = 22.sp,
                modifier = Modifier
                    .alpha(if (isPrinting) 0.4f else 1f)
                    .clip(CircleShape)
                    .clickable(enabled = !isPrinting) { onCoolDown() }
                    .padding(2.dp)
            )

            Text(
                statusText,
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenConsole() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
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
                    // Drucker-Sektion
                    Text(
                        "Drucker",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    if (printers.isNotEmpty()) {
                        printers.forEach { printer ->
                            val isActive = printer.id == selectedPrinterId
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            printer.name,
                                            color = if (isActive) AccentYellow else OnSurface,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(printer.host, color = OnSurfaceDim, fontSize = 11.sp)
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isActive) Icons.Default.CheckCircle else Icons.Default.Print,
                                        contentDescription = null,
                                        tint = if (isActive) AccentYellow else OnSurfaceDim,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = { showMenu = false; if (!isActive) onSelectPrinter(printer.id) }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Drucker verwalten / hinzufügen", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onOpenPrinterManager() }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
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
                    DropdownMenuItem(
                        text = { Text("Driver Einstellung", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onNavigateToDriverSettings() }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    Text(
                        "Werkzeuge",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    DropdownMenuItem(
                        text = { Text("Slicen", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.ViewInAr, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onNavigateToSlicer() }
                    )
                    DropdownMenuItem(
                        text = { Text("Zeitraffer", color = OnSurface) },
                        leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onOpenTimelapse() }
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

// ── Zeitraffer-Browser ───────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) { value /= 1024; idx++ }
    return if (idx == 0) "$bytes B" else "%.1f %s".format(value, units[idx])
}

@Composable
fun TimelapseBrowserDialog(
    timelapses: List<Timelapse>,
    isLoading: Boolean,
    error: String?,
    downloading: Set<String>,
    onRefresh: () -> Unit,
    onDownload: (Timelapse) -> Unit,
    onDelete: (Timelapse) -> Unit,
    getPlayback: suspend (Timelapse) -> Pair<String, Map<String, String>>?,
    onDismiss: () -> Unit
) {
    var deleteConfirm by remember { mutableStateOf<Timelapse?>(null) }
    var playItem by remember { mutableStateOf<Timelapse?>(null) }
    var playback by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    val dateFmt = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }

    // Wiedergabe-Daten (URL + Auth) erst bei Bedarf auflösen
    LaunchedEffect(playItem) {
        playback = playItem?.let { getPlayback(it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121212)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = AccentYellow, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zeitraffer", color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = OnSurface)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen", tint = OnSurface)
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading && timelapses.isEmpty() ->
                            CircularProgressIndicator(color = AccentYellow, modifier = Modifier.align(Alignment.Center))
                        error != null && timelapses.isEmpty() ->
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(36.dp))
                                Text("Fehler: $error", color = OnSurfaceDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                        timelapses.isEmpty() ->
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = OnSurfaceDim, modifier = Modifier.size(40.dp))
                                Text("Keine Zeitraffer vorhanden", color = OnSurfaceDim, fontSize = 14.sp)
                                Text("Sie erscheinen hier, sobald das moonraker-timelapse Plugin Videos gerendert hat.",
                                    color = OnSurfaceDim.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        else ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(timelapses, key = { it.path }) { item ->
                                    val isDownloading = downloading.contains(item.path)
                                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1C1C1C), modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.filename, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    "${dateFmt.format(java.util.Date(item.modified))} · ${formatBytes(item.size)}",
                                                    color = OnSurfaceDim, fontSize = 11.sp
                                                )
                                            }
                                            IconButton(onClick = { playItem = item }, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen", tint = AccentYellow, modifier = Modifier.size(22.dp))
                                            }
                                            if (isDownloading) {
                                                CircularProgressIndicator(color = AccentYellow, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(2.dp))
                                                Spacer(Modifier.width(8.dp))
                                            } else {
                                                IconButton(onClick = { onDownload(item) }, modifier = Modifier.size(36.dp)) {
                                                    Icon(Icons.Default.FileDownload, contentDescription = "Herunterladen", tint = OnSurface, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            IconButton(onClick = { deleteConfirm = item }, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = Color(0xFFFF5555), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    // Lösch-Bestätigung
    deleteConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            containerColor = Color(0xFF1E1E1E),
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5555)) },
            title = { Text("Zeitraffer löschen?", color = OnSurface, fontWeight = FontWeight.Bold) },
            text = { Text("\"${item.filename}\" wird unwiderruflich vom Drucker gelöscht.", color = OnSurfaceDim) },
            confirmButton = {
                Button(
                    onClick = { onDelete(item); deleteConfirm = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5555))
                ) { Text("Löschen", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text("Abbrechen", color = OnSurfaceDim) }
            }
        )
    }

    // Wiedergabe-Overlay
    playItem?.let { item ->
        Dialog(
            onDismissRequest = { playItem = null; playback = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.7f),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.filename, color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { playItem = null; playback = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Schließen", tint = OnSurface)
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val pb = playback
                        if (pb == null) {
                            CircularProgressIndicator(color = AccentYellow)
                        } else {
                            TimelapseVideoPlayer(url = pb.first, headers = pb.second, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TimelapseVideoPlayer(
    url: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(url) {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier
    )
}

// ── Konsolen-Dialog ─────────────────────────────────────────────────────────────

@Composable
fun ConsoleDialog(
    entries: List<ConsoleEntry>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSendGcode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

    // Bei neuen Meldungen ans Ende scrollen
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    // Live-Aktualisierung: jede Sekunde neu laden
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            onRefresh()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121212)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Kopfzeile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = AccentYellow,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Konsole",
                        color = OnSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = OnSurface)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen", tint = OnSurface)
                    }
                }

                // Meldungsliste
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading && entries.isEmpty() -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = AccentYellow
                        )
                        entries.isEmpty() -> Text(
                            "Keine Konsolen-Meldungen",
                            color = OnSurfaceDim,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(entries) { entry ->
                                val isCommand = entry.type == "command"
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    if (entry.time > 0.0) {
                                        Text(
                                            timeFmt.format(java.util.Date((entry.time * 1000).toLong())),
                                            color = Color(0xFF666666),
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        entry.message,
                                        color = if (isCommand) AccentYellow else Color(0xFFCCCCCC),
                                        fontSize = 12.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Eingabezeile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val send = {
                        val cmd = input.trim()
                        if (cmd.isNotEmpty()) {
                            onSendGcode(cmd)
                            input = ""
                            focusManager.clearFocus()
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("G-Code senden…", color = OnSurfaceDim, fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = OnSurface,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentYellow,
                            unfocusedBorderColor = Color(0xFF333333)
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentYellow)
                            .clickable { send() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Senden",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
    printResult: Boolean? = null,
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
            // Druckergebnis-Symbol (grüner Haken = erfolgreich, rotes X = abgebrochen)
            if (printResult != null) {
                Icon(
                    imageVector = if (printResult) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = if (printResult) "Erfolgreich gedruckt" else "Abgebrochen",
                    tint = if (printResult) Color(0xFF4CAF50) else Color(0xFFE53935),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
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
        (stats.printDuration / stats.progress * (1f - stats.progress) / stats.speedFactor.coerceAtLeast(0.1f)).toLong()
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
