package com.klipperremote.app.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.ui.theme.*
import com.klipperremote.app.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var setTempTarget by remember { mutableStateOf<TemperatureInfo?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            BottomControlBar(
                printerState = uiState.printerState,
                onNavigateToSettings = onNavigateToSettings
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
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Einstellungen öffnen")
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

                    // Temperature section header
                    item {
                        SectionHeader(title = "Temperaturen", onGearClick = onNavigateToSettings)
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
                                    color = AccentBlue,
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
                        SectionHeader(
                            title = "Webcam",
                            onGearClick = { /* webcam settings */ }
                        )
                    }

                    // Webcam card
                    item {
                        WebcamCard(host = uiState.config.host)
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
}

@Composable
fun SectionHeader(title: String, onGearClick: () -> Unit) {
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
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onGearClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

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

    val ratio = (temp.current / 280f).coerceIn(0f, 1f)
    val topColor = lerp(Color(0xFF1E1E1E), Color(0xFF3D180A), ratio)
    val bottomColor = lerp(Color(0xFF2D2D2D), Color(0xFF6E2412), ratio)
    val brush = Brush.verticalGradient(colors = listOf(topColor, bottomColor))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1C))
    ) {
        Column {
            // Gradient content area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brush)
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
            ) {
                Text(
                    displayName,
                    color = OnSurface.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "%.1f°C".format(temp.current),
                    color = OnSurface,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    targetText,
                    color = OnSurface.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
                if (temp.power > 0f) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { temp.power },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentBlue,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
            // Setzen button strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AccentBlue)
                    .clickable { onSetTemp() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Setzen",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun WebcamCard(host: String) {
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
                    tint = OnSurfaceDim,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("cam 1", color = OnSurfaceDim, fontSize = 12.sp)
            }

            if (host.isNotBlank()) {
                val streamUrl = remember(host) { "http://$host/webcam/stream" }
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

@Composable
fun BottomControlBar(
    printerState: String,
    onNavigateToSettings: () -> Unit
) {
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
        "printing" -> AccentBlue
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
                onClick = { /* TODO: Druckjob starten */ },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
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
                tint = if (printerState == "printing") AccentBlue else OnSurfaceDim,
                modifier = Modifier.size(22.dp)
            )

            Text(
                statusText,
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(0.05f))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .clickable { onNavigateToSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Menü",
                    tint = OnSurface,
                    modifier = Modifier.size(18.dp)
                )
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
        containerColor = SurfaceDark,
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
                        focusedBorderColor = AccentBlue,
                        focusedLabelColor = AccentBlue,
                        cursorColor = AccentBlue,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    )
                )
                // Quick presets
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
                                selectedContainerColor = AccentBlue,
                                selectedLabelColor = Color.White,
                                containerColor = SurfaceVariant,
                                labelColor = OnSurfaceDim
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = inputValue == preset.toString(),
                                borderColor = SurfaceVariant,
                                selectedBorderColor = AccentBlue
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
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text("Setzen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = OnSurfaceDim)
            }
        }
    )
}
