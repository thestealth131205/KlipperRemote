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
import com.klipperremote.app.data.model.DriverEdit
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
    val microstepFields = remember { mutableStateMapOf<String, String>() }
    val rotationFields = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(uiState.driverSettings) {
        uiState.driverSettings.axes.forEach { axis ->
            runFields[axis.stepperName] = axis.runCurrent?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
            holdFields[axis.stepperName] = axis.holdCurrent?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
            microstepFields[axis.stepperName] = axis.microsteps?.toString() ?: ""
            rotationFields[axis.stepperName] = axis.rotationDistance?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: ""
        }
    }

    var showRestartConfirm by remember { mutableStateOf(false) }

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
                                "Lauf- und Halte-Strom werden sofort angewendet (SET_TMC_CURRENT) und " +
                                    "in die Config geschrieben. Microsteps und Rotation-Distance werden " +
                                    "ebenfalls in die Config geschrieben; ihre Änderung erfordert einen " +
                                    "FIRMWARE_RESTART, der dann automatisch ausgelöst wird.",
                                color = Color(0xFF888888),
                                fontSize = 11.sp
                            )
                        }
                        items(uiState.driverSettings.axes, key = { it.stepperName }) { axis ->
                            AxisDriverCard(
                                axis = axis,
                                runValue = runFields[axis.stepperName] ?: "",
                                holdValue = holdFields[axis.stepperName] ?: "",
                                microstepValue = microstepFields[axis.stepperName] ?: "",
                                rotationValue = rotationFields[axis.stepperName] ?: "",
                                onRunChange = { runFields[axis.stepperName] = it },
                                onHoldChange = { holdFields[axis.stepperName] = it },
                                onMicrostepChange = { microstepFields[axis.stepperName] = it },
                                onRotationChange = { rotationFields[axis.stepperName] = it }
                            )
                        }
                        item {
                            uiState.driverSettingsError?.let {
                                Text(it, color = Color(0xFFEF5350), fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    val edits = buildDriverEdits(
                                        uiState.driverSettings.axes,
                                        runFields, holdFields, microstepFields, rotationFields
                                    )
                                    if (edits.any { it.needsRestart }) showRestartConfirm = true
                                    else viewModel.saveDriverSettings(edits)
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

            if (showRestartConfirm) {
                AlertDialog(
                    onDismissRequest = { showRestartConfirm = false },
                    containerColor = Color(0xFF1E1E1E),
                    title = { Text("Firmware-Neustart nötig", color = Color(0xFFEEEEEE)) },
                    text = {
                        Text(
                            "Änderungen an Microsteps oder Rotation-Distance greifen erst nach einem " +
                                "FIRMWARE_RESTART. Dieser wird nach dem Speichern automatisch ausgelöst und " +
                                "unterbricht einen laufenden Druck. Fortfahren?",
                            color = Color(0xFFBBBBBB),
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestartConfirm = false
                            viewModel.saveDriverSettings(
                                buildDriverEdits(
                                    uiState.driverSettings.axes,
                                    runFields, holdFields, microstepFields, rotationFields
                                )
                            )
                        }) { Text("Speichern & Neustart", color = Accent) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestartConfirm = false }) {
                            Text("Abbrechen", color = Color(0xFF888888))
                        }
                    }
                )
            }
        }
    }
}

// Baut die Liste der tatsächlich geänderten Achsen-Werte (nur abweichende Felder werden gesetzt).
private fun buildDriverEdits(
    axes: List<AxisDriver>,
    runFields: Map<String, String>,
    holdFields: Map<String, String>,
    microstepFields: Map<String, String>,
    rotationFields: Map<String, String>
): List<DriverEdit> = axes.mapNotNull { axis ->
    val name = axis.stepperName
    val run = runFields[name]?.replace(',', '.')?.trim()?.toFloatOrNull()
        ?.takeIf { axis.runCurrent == null || kotlin.math.abs(it - axis.runCurrent) > 0.0001f }
    val hold = holdFields[name]?.replace(',', '.')?.trim()?.toFloatOrNull()
        ?.takeIf { axis.holdCurrent == null || kotlin.math.abs(it - axis.holdCurrent) > 0.0001f }
    val micro = microstepFields[name]?.trim()?.toIntOrNull()
        ?.takeIf { it > 0 && it != axis.microsteps }
    val rot = rotationFields[name]?.replace(',', '.')?.trim()?.toFloatOrNull()
        ?.takeIf { it > 0f && (axis.rotationDistance == null || kotlin.math.abs(it - axis.rotationDistance) > 0.0001f) }

    if (run == null && hold == null && micro == null && rot == null) null
    else DriverEdit(
        stepperName = name,
        driverType = axis.driverType,
        runCurrent = run,
        holdCurrent = hold,
        microsteps = micro,
        rotationDistance = rot
    )
}

@Composable
private fun AxisDriverCard(
    axis: AxisDriver,
    runValue: String,
    holdValue: String,
    microstepValue: String,
    rotationValue: String,
    onRunChange: (String) -> Unit,
    onHoldChange: (String) -> Unit,
    onMicrostepChange: (String) -> Unit,
    onRotationChange: (String) -> Unit
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CurrentField(
                label = "Microsteps",
                value = microstepValue,
                enabled = true,
                decimal = false,
                onChange = onMicrostepChange,
                modifier = Modifier.weight(1f)
            )
            CurrentField(
                label = "Rotation-Distance (mm)",
                value = rotationValue,
                enabled = true,
                onChange = onRotationChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CurrentField(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
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