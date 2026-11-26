package com.klipperremote.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.GCodeLayer
import com.klipperremote.app.data.model.MoveType
import com.klipperremote.app.ui.theme.*
import com.klipperremote.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GCodeViewerScreen(
    filename: String,
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val layers = uiState.gcodeViewerLayers
    val bedSize = uiState.gcodeViewerBedSize
    var layerIndex by remember { mutableIntStateOf(0) }
    // Merkt sich, ob nach dem Laden bereits auf "alle Schichten" (volles Modell) gesprungen wurde.
    var hasSetInitialLayer by remember(filename) { mutableStateOf(false) }

    // Datei beim Öffnen laden
    LaunchedEffect(filename) {
        viewModel.loadGCodeViewer(filename)
    }

    // Nach dem Laden: Standardmäßig alle Schichten übereinander anzeigen (volles 3D-Modell).
    // Danach nur noch sicherstellen dass layerIndex im gültigen Bereich bleibt.
    LaunchedEffect(layers.size) {
        if (layers.isNotEmpty()) {
            if (!hasSetInitialLayer) {
                layerIndex = layers.size - 1
                hasSetInitialLayer = true
            } else if (layerIndex >= layers.size) {
                layerIndex = layers.size - 1
            }
        }
    }

    // Beim Verlassen State leeren
    DisposableEffect(Unit) {
        onDispose { viewModel.clearGCodeViewer() }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        filename.substringAfterLast('/'),
                        color = OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück", tint = OnSurface)
                    }
                },
                actions = {
                    if (layers.isNotEmpty()) {
                        Text(
                            "Schicht ${layerIndex + 1} / ${layers.size}",
                            color = AccentYellow,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            uiState.gcodeViewerLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = AccentYellow, modifier = Modifier.size(48.dp))
                        Text("G-Code wird geladen…", color = OnSurfaceDim, fontSize = 14.sp)
                    }
                }
            }
            uiState.gcodeViewerError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            "Fehler: ${uiState.gcodeViewerError}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            layers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Keine Schichten gefunden", color = OnSurfaceDim, fontSize = 14.sp)
                }
            }
            else -> {
                val currentLayer = layers[layerIndex]
                // Zoom + Verschiebung (Pinch-to-Zoom / Pan)
                var zoom by remember { mutableFloatStateOf(1f) }
                var panX by remember { mutableFloatStateOf(0f) }
                var panY by remember { mutableFloatStateOf(0f) }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding)
                ) {
                    // Canvas-Bereich – nimmt möglichst viel Fläche ein
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        StackedModelCanvas(
                            layers = layers,
                            layerIndex = layerIndex,
                            bedWidth = bedSize.first,
                            bedHeight = bedSize.second,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                        zoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                                        if (zoom > 1f) {
                                            panX += pan.x
                                            panY += pan.y
                                        } else {
                                            panX = 0f; panY = 0f
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = panX
                                    translationY = panY
                                }
                        )
                        // Schicht-Info unten links
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Z: ${"%.3f".format(currentLayer.zHeight)} mm", color = OnSurfaceDim, fontSize = 11.sp)
                            Text("${layerIndex + 1} Schichten sichtbar", color = OnSurfaceDim, fontSize = 11.sp)
                        }
                        // Legende oben rechts
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            LegendItem(color = Color(0xFFFFFF00), label = "Druck")
                            LegendItem(color = Color(0xFF4CAF50), label = "Infill")
                            LegendItem(color = Color(0xFFFF9800), label = "Stütze")
                        }
                    }

                    // Vertikaler Slider rechts
                    BoxWithConstraints(
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val sliderLength = maxHeight
                        // Schichtnummern oben/unten
                        Text(
                            "${layers.size}",
                            color = OnSurfaceDim,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        Text(
                            "1",
                            color = OnSurfaceDim,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )

                        // Slider um 270° gedreht → vertikal, oben = hohe Schicht.
                        // requiredWidth(sliderLength) erzwingt die volle Bildschirmhöhe – sonst
                        // würde .width() auf die schmale 56dp-Spaltenbreite gestaucht.
                        Slider(
                            value = layerIndex.toFloat(),
                            onValueChange = { layerIndex = it.toInt() },
                            valueRange = 0f..(layers.size - 1).coerceAtLeast(0).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = AccentYellow,
                                activeTrackColor = AccentYellow,
                                inactiveTrackColor = SurfaceDark
                            ),
                            modifier = Modifier
                                .graphicsLayer { rotationZ = 270f }
                                .requiredWidth(sliderLength)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(width = 14.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, color = OnSurfaceDim, fontSize = 10.sp)
    }
}

/**
 * Zeichnet alle Schichten von 0 bis [layerIndex] übereinandergestapelt in isometrischer
 * Projektion – dadurch entsteht ein 3D-Eindruck des gedruckten Modells (wie im Web-Betrachter),
 * statt nur der aktuell einzelnen Schicht von oben.
 */
@Composable
private fun StackedModelCanvas(
    layers: List<GCodeLayer>,
    layerIndex: Int,
    bedWidth: Float,
    bedHeight: Float,
    modifier: Modifier = Modifier
) {
    val colorPrint   = Color(0xFFFFFF00)  // gelb  – Druckfahrten
    val colorInfill  = Color(0xFF4CAF50)  // grün  – Infill
    val colorSupport = Color(0xFFFF9800)  // orange – Stützen

    val visibleLayers = remember(layers, layerIndex) {
        layers.subList(0, (layerIndex + 1).coerceIn(0, layers.size))
    }
    val maxZ = visibleLayers.lastOrNull()?.zHeight?.coerceAtLeast(0.01f) ?: 1f

    Canvas(modifier = modifier.background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))) {
        val canvasW = size.width
        val canvasH = size.height

        // Isometrische Projektion (30°): X nach rechts-unten, Y nach rechts-oben, Z nach oben.
        val angleRad = Math.toRadians(30.0)
        val cosA = kotlin.math.cos(angleRad).toFloat()
        val sinA = kotlin.math.sin(angleRad).toFloat()

        fun isoProject(x: Float, y: Float, z: Float): Offset {
            val isoX = (x - y) * cosA
            val isoY = (x + y) * sinA + z
            return Offset(isoX, -isoY)  // Bildschirm-Y invertieren: höher = weiter oben
        }

        // Bounding Box aus den Bett-Ecken (unten und auf Höhe maxZ) für die Skalierung ermitteln.
        val corners = listOf(
            isoProject(0f, 0f, 0f), isoProject(bedWidth, 0f, 0f),
            isoProject(0f, bedHeight, 0f), isoProject(bedWidth, bedHeight, 0f),
            isoProject(0f, 0f, maxZ), isoProject(bedWidth, 0f, maxZ),
            isoProject(0f, bedHeight, maxZ), isoProject(bedWidth, bedHeight, maxZ)
        )
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }

        val padding = 24f
        val availW = canvasW - padding * 2
        val availH = canvasH - padding * 2
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf(availW / spanX, availH / spanY)

        val offsetX = padding + (availW - spanX * scale) / 2f - minX * scale
        val offsetY = padding + (availH - spanY * scale) / 2f - minY * scale

        fun toScreen(x: Float, y: Float, z: Float): Offset {
            val p = isoProject(x, y, z)
            return Offset(offsetX + p.x * scale, offsetY + p.y * scale)
        }

        // Bett-Umriss als Raute (Draufsicht in isometrischer Projektion) zeichnen
        val bedPath = Path().apply {
            val p0 = toScreen(0f, 0f, 0f)
            val p1 = toScreen(bedWidth, 0f, 0f)
            val p2 = toScreen(bedWidth, bedHeight, 0f)
            val p3 = toScreen(0f, bedHeight, 0f)
            moveTo(p0.x, p0.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
        }
        drawPath(bedPath, color = Color(0xFF1A1A2E))
        drawPath(bedPath, color = Color(0xFF2A2A3E), style = Stroke(width = 1f))

        // Alle sichtbaren Schichten übereinander zeichnen – Leerfahrten werden ausgeblendet,
        // damit ein sauberes, geschlossenes Modell statt eines Fahrwege-Gewirrs entsteht.
        for (layer in visibleLayers) {
            val z = layer.zHeight
            for (pass in listOf(MoveType.SUPPORT, MoveType.INFILL, MoveType.PRINT)) {
                for (seg in layer.segments) {
                    if (seg.moveType != pass) continue
                    val start = toScreen(seg.x1, seg.y1, z)
                    val end = toScreen(seg.x2, seg.y2, z)
                    val (color, stroke) = when (pass) {
                        MoveType.SUPPORT -> colorSupport to 1f
                        MoveType.INFILL  -> colorInfill  to 1f
                        else             -> colorPrint   to 1.2f
                    }
                    drawLine(color = color, start = start, end = end, strokeWidth = stroke)
                }
            }
        }
    }
}
