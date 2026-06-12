package com.klipperremote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.AxisDriver
import com.klipperremote.app.viewmodel.MainViewModel

private val Accent = Color(0xFFE8FF00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (uiState.driverSettings.axes.isEmpty()) viewModel.loadDriverSettings()
    }

    // Lokale Bearbeitungs-Zustände je Achse, initialisiert aus den geladenen Werten
    val runFields = remember { mutableStateMapOf<String, String>() }
    val holdFields = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(uiState.driverSettings) {
        uiState.driverSettings.axes.forEach { axis ->
            runFields[axis.stepperName] = axis.runCurrent?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
            holdFields[axis.stepperName] = axis.holdCurrent?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
        }
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = { Text("Driver Einstellung", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDriverSettings() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Neu laden")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                uiState.driverSettingsLoading && uiState.driverSettings.axes.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                uiState.driverSettings.axes.isEmpty() ->
                    Text(
                        "Keine Treiber-Einstellungen gefunden",
                        color = Color(0xFF888888),
                        modifier = Modifier.align(Alignment.Center)
                    )
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "Lauf- und Halte-Strom werden sofort angewendet (SET_TMC_CURRENT). " +
                                    "Microsteps und Rotation-Distance sind nur zur Info – Änderungen " +
                                    "erfordern eine Konfigurationsbearbeitung und Firmware-Neustart.",
                                color = Color(0xFF888888),
                                fontSize = 11.sp
                            )
                        }
                        items(uiState.driverSettings.axes, key = { it.stepperName }) { axis ->
                            AxisDriverCard(
                                axis = axis,
                                runValue = runFields[axis.stepperName] ?: "",
                                holdValue = holdFields[axis.stepperName] ?: "",
                                onRunChange = { runFields[axis.stepperName] = it },
                                onHoldChange = { holdFields[axis.stepperName] = it }
                            )
                        }
                        item {
                            uiState.driverSettingsError?.let {
                                Text(it, color = Color(0xFFEF5350), fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    val edits = buildMap<String, Pair<Float, Float?>> {
                                        uiState.driverSettings.axes.forEach { axis ->
                                            val run = runFields[axis.stepperName]
                                                ?.replace(',', '.')?.trim()?.toFloatOrNull()
                                            if (run != null) {
                                                val hold = holdFields[axis.stepperName]
                                                    ?.replace(',', '.')?.trim()?.toFloatOrNull()
                                                put(axis.stepperName, run to hold)
                                            }
                                        }
                                    }
                                    viewModel.saveDriverSettings(edits)
                                },
                                enabled = !uiState.driverSettingsSaving,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                when {
                                    uiState.driverSettingsSaving ->
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                                    uiState.driverSettingsSaved ->
                                        Text("Gespeichert ✓", color = Color.Black, fontWeight = FontWeight.Bold)
                                    else ->
                                        Text("Speichern", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisDriverCard(
    axis: AxisDriver,
    runValue: String,
    holdValue: String,
    onRunChange: (String) -> Unit,
    onHoldChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Achse ${axis.axis}",
                color = Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (axis.driverType.isNotBlank()) axis.driverType.uppercase() else axis.stepperName,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CurrentField(
                label = "Lauf-Strom (A)",
                value = runValue,
                enabled = axis.driverType.isNotBlank(),
                onChange = onRunChange,
                modifier = Modifier.weight(1f)
            )
            CurrentField(
                label = "Halte-Strom (A)",
                value = holdValue,
                enabled = axis.driverType.isNotBlank(),
                onChange = onHoldChange,
                modifier = Modifier.weight(1f)
            )
        }

        if (axis.driverType.isBlank()) {
            Text(
                "Kein TMC-Treiber erkannt – Strom nicht setzbar.",
                color = Color(0xFFFF9800),
                fontSize = 11.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            InfoValue("Microsteps", axis.microsteps?.toString() ?: "–")
            InfoValue("Rotation-Distance", axis.rotationDistance?.let { String.format(java.util.Locale.US, "%.3f mm", it) } ?: "–")
        }
    }
}

@Composable
private fun CurrentField(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            focusedLabelColor = Accent,
            cursorColor = Accent,
            unfocusedBorderColor = Color(0xFF444444),
            unfocusedLabelColor = Color(0xFF888888),
            focusedTextColor = Color(0xFFEEEEEE),
            unfocusedTextColor = Color(0xFFEEEEEE),
            disabledTextColor = Color(0xFF666666),
            disabledBorderColor = Color(0xFF333333),
            disabledLabelColor = Color(0xFF666666)
        )
    )
}

@Composable
private fun InfoValue(label: String, value: String) {
    Column {
        Text(label, color = Color(0xFF888888), fontSize = 10.sp)
        Text(value, color = Color(0xFFCCCCCC), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
