package com.klipperremote.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.BLOCK_DELAY
import com.klipperremote.app.data.model.BLOCK_GOTO
import com.klipperremote.app.data.model.BLOCK_HOME
import com.klipperremote.app.data.model.BLOCK_MACRO
import com.klipperremote.app.data.model.BLOCK_POWER
import com.klipperremote.app.data.model.BLOCK_ZTILT
import com.klipperremote.app.data.model.RoutineBlockData
import com.klipperremote.app.data.model.RoutineData
import com.klipperremote.app.ui.theme.AccentYellow
import com.klipperremote.app.ui.theme.BackgroundDark
import com.klipperremote.app.ui.theme.OnSurface
import com.klipperremote.app.ui.theme.OnSurfaceDim
import com.klipperremote.app.viewmodel.MainViewModel
import kotlin.math.roundToInt

// ── Block helpers ──────────────────────────────────────────────────────────────

private fun RoutineBlockData.displayLabel(): String = when (type) {
    BLOCK_POWER  -> if (turnOn) "EIN: $deviceName" else "AUS: $deviceName"
    BLOCK_DELAY  -> "Warten ${if (seconds == seconds.toInt().toFloat()) seconds.toInt().toString() else seconds.toString()}s"
    BLOCK_HOME   -> "Homen $axes"
    BLOCK_ZTILT  -> "Z-Tilt"
    BLOCK_GOTO   -> buildString {
        append("Fahre nach")
        x?.let { append(" X${it.toInt()}") }
        y?.let { append(" Y${it.toInt()}") }
        z?.let { append(" Z${it.toInt()}") }
    }
    BLOCK_MACRO  -> command ?: "Makro"
    else         -> type
}

private fun RoutineBlockData.blockColor(): Color = when (type) {
    BLOCK_POWER  -> if (turnOn) Color(0xFF4CAF50) else Color(0xFFEF5350)
    BLOCK_DELAY  -> Color(0xFF9E9E9E)
    BLOCK_HOME   -> Color(0xFF42A5F5)
    BLOCK_ZTILT  -> Color(0xFF7E57C2)
    BLOCK_GOTO   -> Color(0xFF26C6DA)
    BLOCK_MACRO  -> Color(0xFFE8FF00)
    else         -> Color(0xFF888888)
}

private fun RoutineBlockData.blockIcon(): ImageVector = when (type) {
    BLOCK_POWER  -> Icons.Default.PowerSettingsNew
    BLOCK_DELAY  -> Icons.Default.Timer
    BLOCK_HOME   -> Icons.Default.Home
    BLOCK_ZTILT  -> Icons.Default.Straighten
    BLOCK_GOTO   -> Icons.Default.NearMe
    BLOCK_MACRO  -> Icons.Default.Terminal
    else         -> Icons.Default.Extension
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    routineId: String?,
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Load existing routine or start fresh.
    // routineId ist stabil (kommt aus NavArgs), uiState.routines kann sich verzögert füllen
    // (Disk-Load läuft async auf IO-Thread → evtl. erst nach erster Komposition fertig).
    val initialExisting = remember(routineId) {
        routineId?.let { id -> uiState.routines.find { it.id == id } }
    }
    var routineName by remember { mutableStateOf(initialExisting?.name ?: "Neue Routine") }
    val blocks = remember { mutableStateListOf<RoutineBlockData>().also { list ->
        initialExisting?.blocks?.let { list.addAll(it) }
    }}

    // Falls der Disk-Load erst NACH der ersten Komposition fertig wurde (Race-Condition),
    // Routine nachladen sobald sie in uiState erscheint.
    var initialized by remember { mutableStateOf(initialExisting != null || routineId == null) }
    LaunchedEffect(uiState.routines) {
        if (!initialized && routineId != null) {
            val found = uiState.routines.find { it.id == routineId }
            if (found != null) {
                routineName = found.name
                blocks.clear()
                blocks.addAll(found.blocks)
                initialized = true
            }
        }
    }

    // existing für Delete-Button und ID-Ermittlung beim Speichern
    val existing = if (routineId != null) uiState.routines.find { it.id == routineId } else null

    // Drag state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(80f) }

    // Dialogs
    var showGotoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Routine bearbeiten", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Routine löschen",
                                tint = Color(0xFFEF5350)
                            )
                        }
                    }
                    IconButton(onClick = {
                        val routine = RoutineData(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            name = routineName.ifBlank { "Routine" },
                            blocks = blocks.toList()
                        )
                        viewModel.saveRoutine(routine)
                        showSaveSuccess = true
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Speichern", tint = AccentYellow)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Name field ───────────────────────────────────────────────────────
            OutlinedTextField(
                value = routineName,
                onValueChange = { routineName = it },
                label = { Text("Name der Routine", color = OnSurfaceDim, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    focusedBorderColor = AccentYellow,
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            // ── Block sequence ───────────────────────────────────────────────────
            Text(
                "Ablauf",
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (blocks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Noch keine Bausteine → unten auswählen",
                        color = OnSurfaceDim,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(blocks, key = { idx, _ -> idx }) { index, block ->
                        val isDragging = draggingIndex == index
                        val elevation by animateDpAsState(
                            if (isDragging) 12.dp else 0.dp,
                            label = "blockElevation"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset {
                                    if (isDragging) IntOffset(0, dragOffsetY.roundToInt())
                                    else IntOffset.Zero
                                }
                                .shadow(elevation, RoundedCornerShape(12.dp))
                                .onGloballyPositioned { coords ->
                                    if (index == 0) itemHeightPx = coords.size.height.toFloat() + 8f
                                }
                                .pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { _, dragAmount ->
                                            dragOffsetY += dragAmount.y
                                            if (blocks.size > 1) {
                                                val newIndex = (index + (dragOffsetY / itemHeightPx).roundToInt())
                                                    .coerceIn(0, blocks.size - 1)
                                                if (newIndex != index && draggingIndex != null) {
                                                    blocks.add(newIndex, blocks.removeAt(index))
                                                    draggingIndex = newIndex
                                                    dragOffsetY -= (newIndex - index) * itemHeightPx
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        }
                                    )
                                }
                        ) {
                            BlockSequenceItem(
                                block = block,
                                isDragging = isDragging,
                                onMoveUp = { if (index > 0) { blocks.add(index - 1, blocks.removeAt(index)) } },
                                onMoveDown = { if (index < blocks.size - 1) { blocks.add(index + 1, blocks.removeAt(index)) } },
                                onDelete = { blocks.removeAt(index) }
                            )
                        }
                    }
                }
            }

            // ── Block palette ─────────────────────────────────────────────────────
            Divider(color = Color(0xFF2A2A2A), thickness = 1.dp)
            Text(
                "Bausteine",
                color = OnSurfaceDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            // Build flat list of chip specs: label / color / icon / onClick
            data class Chip(val label: String, val color: Color, val icon: ImageVector, val action: () -> Unit)
            val chips: List<Chip> = buildList {
                uiState.powerDevices.forEach { d ->
                    add(Chip("EIN\n${d.name}", Color(0xFF4CAF50), Icons.Default.PowerSettingsNew) {
                        blocks.add(RoutineBlockData(type = BLOCK_POWER, deviceName = d.name, turnOn = true))
                    })
                    add(Chip("AUS\n${d.name}", Color(0xFFEF5350), Icons.Default.PowerSettingsNew) {
                        blocks.add(RoutineBlockData(type = BLOCK_POWER, deviceName = d.name, turnOn = false))
                    })
                }
                listOf(0.5f, 1f, 2f, 4f, 6f, 8f).forEach { sec ->
                    val lbl = "${if (sec == sec.toInt().toFloat()) sec.toInt().toString() else sec.toString()}s\nWarten"
                    add(Chip(lbl, Color(0xFF9E9E9E), Icons.Default.Timer) {
                        blocks.add(RoutineBlockData(type = BLOCK_DELAY, seconds = sec))
                    })
                }
                add(Chip("Homen\nXYZ", Color(0xFF42A5F5), Icons.Default.Home) {
                    blocks.add(RoutineBlockData(type = BLOCK_HOME, axes = "XYZ"))
                })
                listOf("X", "Y", "Z").forEach { axis ->
                    add(Chip("Homen\n$axis", Color(0xFF42A5F5), Icons.Default.Home) {
                        blocks.add(RoutineBlockData(type = BLOCK_HOME, axes = axis))
                    })
                }
                add(Chip("Z-Tilt\nAdjust", Color(0xFF7E57C2), Icons.Default.Straighten) {
                    blocks.add(RoutineBlockData(type = BLOCK_ZTILT))
                })
                add(Chip("Fahre\nnach…", Color(0xFF26C6DA), Icons.Default.NearMe) { showGotoDialog = true })
                uiState.macros.forEach { macro ->
                    add(Chip(macro, Color(0xFFE8FF00), Icons.Default.Terminal) {
                        blocks.add(RoutineBlockData(type = BLOCK_MACRO, command = macro))
                    })
                }
            }
            // Render as grid of 4 chips per row
            Column(
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.chunked(4).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { chip ->
                            PaletteChip(
                                label = chip.label,
                                color = chip.color,
                                icon = chip.icon,
                                modifier = Modifier.weight(1f),
                                onClick = chip.action
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // GoTo dialog
        if (showGotoDialog) {
            GotoBlockDialog(
                onConfirm = { x, y, z, feed ->
                    blocks.add(RoutineBlockData(type = BLOCK_GOTO, x = x, y = y, z = z, feedrate = feed))
                    showGotoDialog = false
                },
                onDismiss = { showGotoDialog = false }
            )
        }

        // Delete confirm dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = Color(0xFF1E1E1E),
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350)) },
                title = { Text("Routine löschen?", color = OnSurface, fontWeight = FontWeight.Bold) },
                text = { Text("\"$routineName\" wird dauerhaft gelöscht.", color = OnSurfaceDim) },
                confirmButton = {
                    Button(
                        onClick = {
                            existing?.id?.let { viewModel.deleteRoutine(it) }
                            showDeleteConfirm = false
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                    ) { Text("Löschen") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Abbrechen", color = OnSurfaceDim)
                    }
                }
            )
        }

        // Save success snackbar
        if (showSaveSuccess) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                showSaveSuccess = false
                onNavigateBack()
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
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
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Text("Routine gespeichert", color = OnSurface, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ── Block sequence item ─────────────────────────────────────────────────────────

@Composable
private fun BlockSequenceItem(
    block: RoutineBlockData,
    isDragging: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (isDragging) Color(0xFF2A2A2A) else Color(0xFF1C1C1C)
    val accent = block.blockColor()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Drag handle
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Verschieben",
                tint = Color(0xFF444444),
                modifier = Modifier.size(20.dp)
            )

            // Block color accent
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    block.blockIcon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Label
            Text(
                block.displayLabel(),
                color = OnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Move buttons
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable { onMoveUp() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Hoch", tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable { onMoveDown() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Runter", tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
                }
            }

            // Delete
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Entfernen", tint = Color(0xFF555555), modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── Palette chip ───────────────────────────────────────────────────────────────

@Composable
private fun PaletteChip(
    label: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1C1C1C),
        modifier = modifier
            .clickable { onClick() }
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                label,
                color = OnSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

// ── GoTo dialog ────────────────────────────────────────────────────────────────

@Composable
private fun GotoBlockDialog(
    onConfirm: (x: Float?, y: Float?, z: Float?, feedrate: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var xInput by remember { mutableStateOf("") }
    var yInput by remember { mutableStateOf("") }
    var zInput by remember { mutableStateOf("") }
    var feedInput by remember { mutableStateOf("3000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        icon = { Icon(Icons.Default.NearMe, contentDescription = null, tint = Color(0xFF26C6DA)) },
        title = { Text("Koordinaten", color = OnSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leer lassen = Achse nicht anfahren", color = OnSurfaceDim, fontSize = 12.sp)
                listOf(
                    Triple("X (mm)", xInput) { v: String -> xInput = v },
                    Triple("Y (mm)", yInput) { v: String -> yInput = v },
                    Triple("Z (mm)", zInput) { v: String -> zInput = v },
                    Triple("Vorschub (mm/min)", feedInput) { v: String -> feedInput = v }
                ).forEach { (label, value, onValue) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValue,
                        label = { Text(label, color = OnSurfaceDim, fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface,
                            focusedBorderColor = Color(0xFF26C6DA),
                            unfocusedBorderColor = Color(0xFF444444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        xInput.replace(',', '.').toFloatOrNull(),
                        yInput.replace(',', '.').toFloatOrNull(),
                        zInput.replace(',', '.').toFloatOrNull(),
                        feedInput.toIntOrNull() ?: 3000
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26C6DA))
            ) { Text("Hinzufügen", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = OnSurfaceDim) }
        }
    )
}
