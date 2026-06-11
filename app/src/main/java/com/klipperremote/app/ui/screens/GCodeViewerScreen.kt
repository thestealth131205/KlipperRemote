package com.klipperremote.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.klipperremote.app.data.model.GCodeLayer
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

    // Datei beim Öffnen laden
    LaunchedEffect(filename) {
        viewModel.loadGCodeViewer(filename)
    }

    // Sicherstellen dass layerIndex im Bereich bleibt
    LaunchedEffect(layers.size) {
        if (layers.isNotEmpty() && layerIndex >= layers.size) {
            layerIndex = layers.size - 1
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
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundDark)
                        .padding(padding)
                ) {
                    // Canvas-Bereich
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LayerCanvas(
                            layer = currentLayer,
                            bedWidth = bedSize.first,
                            bedHeight = bedSize.second,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Schicht-Info unten links
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Z: ${"%.3f".format(currentLayer.zHeight)} mm", color = OnSurfaceDim, fontSize = 11.sp)
                            Text("${currentLayer.segments.size} Segmente", color = OnSurfaceDim, fontSize = 11.sp)
                        }
                    }

                    // Vertikaler Slider rechts
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
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

                        // Slider um 270° gedreht → vertikal, oben = hohe Schicht
                        Slider(
                            value = layerIndex.toFloat(),
                            onValueChange = { layerIndex = it.toInt() },
                            valueRange = 0f..(layers.size - 1).toFloat(),
                            steps = if (layers.size > 2) layers.size - 2 else 0,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentYellow,
                                activeTrackColor = AccentYellow,
                                inactiveTrackColor = SurfaceDark
                            ),
                            modifier = Modifier
                                .graphicsLayer { rotationZ = 270f }
                                .fillMaxHeight()
                                .width(200.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerCanvas(
    layer: GCodeLayer,
    bedWidth: Float,
    bedHeight: Float,
    modifier: Modifier = Modifier
) {
    val travelColor = Color(0xFF444444)
    val extrudeColor = AccentYellow

    Canvas(modifier = modifier.background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))) {
        val canvasW = size.width
        val canvasH = size.height

        // Bett-Aspect-Ratio beibehalten, mit Padding
        val padding = 24f
        val availW = canvasW - padding * 2
        val availH = canvasH - padding * 2
        val scaleX = availW / bedWidth
        val scaleY = availH / bedHeight
        val scale = minOf(scaleX, scaleY)
        val offsetX = padding + (availW - bedWidth * scale) / 2f
        val offsetY = padding + (availH - bedHeight * scale) / 2f

        fun toScreen(x: Float, y: Float): Offset {
            return Offset(
                x = offsetX + x * scale,
                y = offsetY + (bedHeight - y) * scale  // Y invertieren: 0 = unten
            )
        }

        // Bett-Umriss zeichnen
        val bedColor = Color(0xFF1A1A2E)
        drawRect(
            color = bedColor,
            topLeft = Offset(offsetX, offsetY),
            size = Size(bedWidth * scale, bedHeight * scale)
        )
        // Bett-Rand
        drawRect(
            color = Color(0xFF2A2A3E),
            topLeft = Offset(offsetX, offsetY),
            size = Size(bedWidth * scale, bedHeight * scale),
            style = Stroke(strokeWidth = 1f)
        )

        // Segmente zeichnen
        for (seg in layer.segments) {
            val start = toScreen(seg.x1, seg.y1)
            val end = toScreen(seg.x2, seg.y2)
            drawLine(
                color = if (seg.isTravel) travelColor else extrudeColor,
                start = start,
                end = end,
                strokeWidth = if (seg.isTravel) 0.8f else 1.5f
            )
        }
    }
}
