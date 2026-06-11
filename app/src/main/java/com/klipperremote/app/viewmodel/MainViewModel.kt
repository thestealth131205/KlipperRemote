package com.klipperremote.app.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.GCodeLayer
import com.klipperremote.app.data.model.GCodeSegment
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.klipperremote.app.data.network.ApiRequestQueue
import com.klipperremote.app.data.repository.KlipperRepository
import com.klipperremote.app.PrintNotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val temperatures: List<TemperatureInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val config: KlipperConfig = KlipperConfig(),
    val webcamConfig: WebcamConfig = WebcamConfig(),
    val setTempSuccess: String? = null,
    val printerState: String = "offline",
    val position: KlipperPosition = KlipperPosition(),
    val files: List<PrintFile> = emptyList(),
    val macros: List<String> = emptyList(),
    val favoriteMacros: List<String> = emptyList(),
    val pinnedGcodes: List<String> = listOf("G28", "SAVE_CONFIG", "PROBE_CALIBRATE"),
    val gcodeResult: String? = null,
    val powerDevices: List<PowerDevice> = emptyList(),
    val connectionFailed: Boolean = false,
    // G-Code Viewer
    val gcodeViewerLayers: List<GCodeLayer> = emptyList(),
    val gcodeViewerLoading: Boolean = false,
    val gcodeViewerError: String? = null,
    val gcodeViewerBedSize: Pair<Float, Float> = Pair(235f, 235f),
    // G-Code Datei-Vorschau (für Druck-Bestätigungsdialog)
    val gcodePreviewMetadata: GcodeMetadata? = null,
    val gcodePreviewThumbnail: Bitmap? = null,
    val gcodePreviewLoading: Boolean = false,
    // Druckfortschritt (null = kein aktiver Druck, 0.0–1.0 = aktiv)
    val printProgress: Float? = null,
    // Aktuelle Druckgeschwindigkeit in mm/s (null = nicht verfügbar)
    val printSpeedMmPerSec: Float? = null,
    // Maschine / Konfigurationsdateien
    val configFiles: List<ConfigFile> = emptyList(),
    val configFilesLoading: Boolean = false,
    val editingConfigPath: String? = null,
    val editingConfigContent: String = "",
    val editingConfigSaving: Boolean = false,
    val editingConfigSaved: Boolean = false,
    val editingConfigError: String? = null,
    // Crownest-Kameraerkennung
    val crownestCams: List<CrownestCam> = emptyList(),
    val crownestDetecting: Boolean = false,
    val crownestAutoDetectedCam: CrownestCam? = null,
    // Druckstatistiken (null = kein aktiver Druck)
    val printStats: PrintStats? = null,
    // Tuning-Daten
    val tuningData: TuningData = TuningData()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: KlipperRepository,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Letzter bekannter Druckzustand – um den Start eines Drucks zu erkennen. */
    private var lastPrintingActive = false

    /** Serialisierte Warteschlange für alle API-Anfragen. */
    private val queue = ApiRequestQueue(viewModelScope)

    init {
        viewModelScope.launch {
            var lastHost = ""
            repository.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
                // Makros laden sobald ein Host bekannt ist (DataStore lädt async → nicht in init direkt)
                if (config.host.isNotBlank() && config.host != lastHost) {
                    lastHost = config.host
                    loadMacros()
                }
            }
        }
        viewModelScope.launch {
            repository.webcamConfigFlow.collect { webcamConfig ->
                _uiState.update { it.copy(webcamConfig = webcamConfig) }
            }
        }
        // Gecachte Power-Geräte sofort aus DataStore laden (ohne Netzwerk)
        viewModelScope.launch {
            val cached = repository.loadCachedPowerDevices()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(powerDevices = cached) }
            }
        }
        // Favoriten-Makros aus DataStore laden
        viewModelScope.launch {
            val favorites = repository.loadFavoriteMacros()
            _uiState.update { it.copy(favoriteMacros = favorites) }
        }
        startPolling()
        loadFiles()
        autoDetectWebcamIfNeeded()
    }

    private fun startPolling() {
        // Temperatur: alle 2 Sekunden, HIGH-Priorität
        viewModelScope.launch {
            while (true) {
                queue.enqueueHigh { fetchTemperaturesInternal() }
                delay(2000L)
            }
        }
        // Hintergrunddaten: alle 4 Sekunden, NORMAL-Priorität (sequenziell via Queue)
        viewModelScope.launch {
            queue.enqueueNormal { fetchPowerDevicesInternal() }
            var bgCounter = 0
            while (true) {
                delay(4000L)
                bgCounter++
                queue.enqueueNormal { fetchPrinterStatusInternal() }
                queue.enqueueNormal { fetchPositionInternal() }
                queue.enqueueNormal { fetchPrintProgressInternal() }
                queue.enqueueNormal { fetchPrintSpeedInternal() }
                queue.enqueueNormal { fetchPrintStatsInternal() }
                queue.enqueueNormal { syncPrintNotification() }
                if (bgCounter % 2 == 0) {
                    queue.enqueueNormal { fetchTuningDataInternal() }
                }
            }
        }
        // Power-Geräte seltener aktualisieren
        viewModelScope.launch {
            while (true) {
                delay(15000L)
                queue.enqueueNormal { fetchPowerDevicesInternal() }
            }
        }
    }

    fun fetchTemperatures() {
        queue.enqueueHigh { fetchTemperaturesInternal() }
    }

    private suspend fun fetchTemperaturesInternal() {
        repository.getTemperatures()
            .onSuccess { temps ->
                _uiState.update { it.copy(temperatures = temps, isLoading = false, connectionFailed = false) }
            }
            .onFailure {
                _uiState.update { it.copy(connectionFailed = true) }
            }
    }

    private suspend fun fetchPrinterStatusInternal() {
        repository.getPrinterStatus()
            .onSuccess { status ->
                _uiState.update { it.copy(printerState = status.state) }
            }
    }

    private suspend fun fetchPrintProgressInternal() {
        repository.getPrintProgress()
            .onSuccess { progress ->
                _uiState.update { it.copy(printProgress = progress) }
            }
    }

    private suspend fun fetchPrintSpeedInternal() {
        repository.getPrintSpeed()
            .onSuccess { speed ->
                _uiState.update { it.copy(printSpeedMmPerSec = speed) }
            }
    }

    private suspend fun fetchPrintStatsInternal() {
        repository.getPrintStats()
            .onSuccess { stats ->
                _uiState.update { it.copy(printStats = stats) }
            }
    }

    /**
     * Erkennt anhand des Druckerzustands, ob ein Druck läuft, und zeigt bzw. entfernt
     * die fortlaufende Druck-Benachrichtigung. Wird bei Druckstart erstmals ausgelöst.
     */
    private fun syncPrintNotification() {
        val state = _uiState.value
        val printing = state.printerState.equals("printing", ignoreCase = true)
        if (printing) {
            val stats = state.printStats
            val progress = stats?.progress ?: state.printProgress ?: 0f
            val filename = stats?.filename ?: ""
            val etaText = stats?.let { s ->
                if (s.progress > 0.01f) {
                    val remainingSecs = (s.printDuration / s.progress * (1f - s.progress)).toLong()
                    val etaMillis = System.currentTimeMillis() + remainingSecs * 1000L
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = etaMillis }
                    "%02d:%02d".format(
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE)
                    )
                } else null
            }
            PrintNotificationHelper.showPrintProgress(appContext, filename, progress, etaText)
            lastPrintingActive = true
        } else if (lastPrintingActive) {
            PrintNotificationHelper.clearPrintProgress(appContext)
            lastPrintingActive = false
        }
    }

    private suspend fun fetchTuningDataInternal() {
        repository.getTuningData()
            .onSuccess { data ->
                _uiState.update { it.copy(tuningData = data) }
            }
    }

    fun setSpeedFactor(percent: Int) {
        queue.enqueueHigh {
            repository.setSpeedFactor(percent.coerceIn(10, 500))
                .onFailure { e -> _uiState.update { it.copy(error = "Geschwindigkeit: ${e.message}") } }
            fetchTuningDataInternal()
        }
    }

    fun setExtrudeFactor(percent: Int) {
        queue.enqueueHigh {
            repository.setExtrudeFactor(percent.coerceIn(10, 200))
                .onFailure { e -> _uiState.update { it.copy(error = "Flussrate: ${e.message}") } }
            fetchTuningDataInternal()
        }
    }

    fun setPartCoolingFan(percent: Int) {
        queue.enqueueHigh {
            repository.setPartCoolingFan(percent.coerceIn(0, 100))
                .onFailure { e -> _uiState.update { it.copy(error = "Lüfter: ${e.message}") } }
            fetchTuningDataInternal()
        }
    }

    fun setGenericFanSpeed(fanKeyName: String, percent: Int) {
        queue.enqueueHigh {
            repository.setGenericFanSpeed(fanKeyName, percent.coerceIn(0, 100))
                .onFailure { e -> _uiState.update { it.copy(error = "Lüfter: ${e.message}") } }
            fetchTuningDataInternal()
        }
    }

    private suspend fun fetchPositionInternal() {
        repository.getPosition()
            .onSuccess { pos ->
                _uiState.update { it.copy(position = pos) }
            }
    }

    fun setTemperature(heaterName: String, target: Float) {
        queue.enqueueHigh {
            repository.setTemperature(heaterName, target)
                .onSuccess {
                    _uiState.update { it.copy(setTempSuccess = "Temperatur gesetzt: $target°C") }
                    delay(2000L)
                    _uiState.update { it.copy(setTempSuccess = null) }
                    fetchTemperaturesInternal()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Fehler: ${e.message}") }
                }
        }
    }

    fun jogMove(axis: String, distance: Float) {
        queue.enqueueHigh {
            repository.jogMove(axis, distance)
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Bewegung fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun homeAxes(axes: String = "") {
        queue.enqueueHigh {
            repository.homeAxes(axes)
                .onSuccess {
                    showGcodeResult(if (axes.isBlank()) "Alle Achsen gehomed" else "$axes gehomed")
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Homing fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun extrude(amount: Float) {
        queue.enqueueHigh {
            repository.extrude(amount)
                .onSuccess {
                    val label = if (amount > 0) "+${amount.toInt()} mm extrudiert" else "${amount.toInt()} mm retrahiert"
                    showGcodeResult(label)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Extrusion fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun sendGcode(gcode: String) {
        queue.enqueueHigh {
            repository.sendGcode(gcode)
                .onSuccess { showGcodeResult("GCode gesendet: $gcode") }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "GCode-Fehler: ${e.message}") }
                }
        }
    }

    fun motorsOff() {
        queue.enqueueHigh {
            repository.sendGcode("M84")
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Motor-Fehler: ${e.message}") }
                }
        }
    }

    fun coolDown() {
        queue.enqueueHigh {
            repository.sendGcode("TURN_OFF_HEATERS")
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Kühl-Fehler: ${e.message}") }
                }
        }
    }

    fun pausePrint() {
        viewModelScope.launch {
            repository.pausePrint().onFailure { e ->
                _uiState.update { it.copy(error = "Pause fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun resumePrint() {
        viewModelScope.launch {
            repository.resumePrint().onFailure { e ->
                _uiState.update { it.copy(error = "Fortsetzen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun cancelPrint() {
        viewModelScope.launch {
            repository.cancelPrint().onFailure { e ->
                _uiState.update { it.copy(error = "Abbrechen fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun saveWebcamSnapshot(context: android.content.Context) {
        val cfg = _uiState.value.config
        val snapshotUrl = _uiState.value.webcamConfig.resolveSnapshotUrl(cfg.host, cfg.port, cfg.apiKey)
        if (snapshotUrl.isBlank()) {
            _uiState.update { it.copy(error = "Kein Snapshot-URL konfiguriert") }
            return
        }
        viewModelScope.launch {
            repository.downloadSnapshot(snapshotUrl)
                .onSuccess { bytes -> saveImageToGallery(context, bytes) }
                .onFailure { e -> _uiState.update { it.copy(error = "Snapshot: ${e.message}") } }
        }
    }

    private suspend fun saveImageToGallery(context: android.content.Context, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val filename = "klipper_snapshot_${System.currentTimeMillis()}.jpg"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KlipperRemote")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                val sub = java.io.File(dir, "KlipperRemote")
                sub.mkdirs()
                java.io.File(sub, filename).writeBytes(bytes)
            }
        }
    }

    fun moveToXyz(x: Float?, y: Float?, z: Float?, feedrate: Int) {
        val parts = mutableListOf<String>()
        x?.let { parts.add("X%.3f".format(it)) }
        y?.let { parts.add("Y%.3f".format(it)) }
        z?.let { parts.add("Z%.3f".format(it)) }
        if (parts.isEmpty()) return
        parts.add("F$feedrate")
        sendGcode("G0 ${parts.joinToString(" ")}")
    }

    fun loadFiles() {
        queue.enqueueNormal {
            repository.getFiles()
                .onSuccess { files ->
                    _uiState.update { it.copy(files = files) }
                }
        }
    }

    fun startPrint(filename: String) {
        queue.enqueueHigh {
            repository.startPrint(filename)
                .onSuccess {
                    showGcodeResult("Druck gestartet: $filename")
                    fetchPrinterStatusInternal()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Druck-Start fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun loadMacros() {
        queue.enqueueNormal {
            repository.getMacros()
                .onSuccess { macros ->
                    _uiState.update { it.copy(macros = macros) }
                }
        }
    }

    fun toggleMacroFavorite(macro: String) {
        val current = _uiState.value.favoriteMacros
        val updated = if (macro in current) {
            current - macro
        } else {
            if (current.size >= 3) current else current + macro
        }
        _uiState.update { it.copy(favoriteMacros = updated) }
        viewModelScope.launch {
            repository.saveFavoriteMacros(updated)
        }
    }

    fun saveConfig(config: KlipperConfig) {
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    fun saveWebcamConfig(config: WebcamConfig) {
        viewModelScope.launch {
            repository.saveWebcamConfig(config)
        }
    }

    private suspend fun fetchPowerDevicesInternal() {
        repository.getPowerDevices()
            .onSuccess { devices ->
                _uiState.update { it.copy(powerDevices = devices, connectionFailed = false) }
                if (devices.isNotEmpty()) repository.saveCachedPowerDevices(devices)
            }
            .onFailure {
                _uiState.update { it.copy(connectionFailed = true) }
            }
    }

    fun togglePowerDevice(device: String, on: Boolean) {
        queue.enqueueHigh {
            repository.togglePowerDevice(device, on)
                .onSuccess {
                    fetchPowerDevicesInternal()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Power-Fehler: ${e.message}") }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun loadGcodePreview(filename: String) {
        _uiState.update { it.copy(gcodePreviewLoading = true, gcodePreviewMetadata = null, gcodePreviewThumbnail = null) }
        queue.enqueueNormal {
            repository.getGcodeMetadata(filename)
                .onSuccess { meta ->
                    _uiState.update { it.copy(gcodePreviewMetadata = meta, gcodePreviewLoading = false) }
                    if (meta.thumbnailUrl != null) {
                        val bmp = repository.fetchThumbnail(meta.thumbnailUrl)
                        _uiState.update { it.copy(gcodePreviewThumbnail = bmp) }
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(gcodePreviewLoading = false) }
                }
        }
    }

    fun clearGcodePreview() {
        _uiState.update { it.copy(gcodePreviewMetadata = null, gcodePreviewThumbnail = null, gcodePreviewLoading = false) }
    }

    fun loadGCodeViewer(filename: String) {
        _uiState.update { it.copy(gcodeViewerLoading = true, gcodeViewerError = null, gcodeViewerLayers = emptyList()) }
        queue.enqueueNormal {
            // Bettgröße laden
            repository.getBedSize().onSuccess { bed ->
                _uiState.update { it.copy(gcodeViewerBedSize = bed) }
            }
            // G-Code-Datei laden und parsen
            repository.getGcodeFileContent(filename)
                .onSuccess { content ->
                    val layers = withContext(Dispatchers.Default) { parseGCode(content) }
                    _uiState.update { it.copy(gcodeViewerLayers = layers, gcodeViewerLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(gcodeViewerLoading = false, gcodeViewerError = e.message) }
                }
        }
    }

    fun clearGCodeViewer() {
        _uiState.update { it.copy(gcodeViewerLayers = emptyList(), gcodeViewerError = null, gcodeViewerLoading = false) }
    }

    private fun parseGCode(content: String): List<GCodeLayer> {
        val layers = mutableListOf<GCodeLayer>()
        var currentX = 0f
        var currentY = 0f
        var currentZ = 0f
        // Z-Höhe der aktuellen Schicht – nur fürs Label (Slider-Anzeige).
        // Schichtwechsel werden AUSSCHLIESSLICH über Slicer-Marker (;LAYER_CHANGE etc.)
        // ausgelöst, NICHT über Z-Änderungen (Z-Hops würden sonst falsch zählen).
        var layerZ = Float.NaN
        val currentSegments = mutableListOf<GCodeSegment>()
        var currentTypeComment = ""   // letzter ;TYPE: Kommentar

        fun flushLayer() {
            if (currentSegments.isNotEmpty()) {
                layers.add(GCodeLayer(if (layerZ.isNaN()) currentZ else layerZ, currentSegments.toList()))
            }
            currentSegments.clear()
            layerZ = Float.NaN
        }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            // Kommentare: ;TYPE: und explizite Layer-Wechsel-Marker auswerten
            if (line.startsWith(";")) {
                val c = line.drop(1).trim().lowercase()
                when {
                    c.startsWith("type:") -> currentTypeComment = c.substringAfter(':').trim()
                    // PrusaSlicer/OrcaSlicer: ;LAYER_CHANGE  ·  Cura: ;LAYER:n
                    // Bambu/Generisch: ;CHANGE_LAYER / ; layer num
                    // Schichtwechsel NUR hier: vor jeder Schicht steht ein solcher Marker.
                    c.startsWith("layer_change") || c.startsWith("change_layer") ||
                        c == "before_layer_change" || c.startsWith("layer:") ||
                        c.startsWith("layer ") -> flushLayer()
                }
                continue
            }

            val cmdLine = line.substringBefore(';').trim().uppercase()
            val parts = cmdLine.split("\\s+".toRegex())
            val cmd = parts.firstOrNull() ?: continue
            if (cmd != "G0" && cmd != "G1") continue

            var newX = currentX
            var newY = currentY
            var newZ = currentZ
            var hasE = false

            for (i in 1 until parts.size) {
                val p = parts[i]
                when {
                    p.startsWith("X") -> newX = p.drop(1).toFloatOrNull() ?: currentX
                    p.startsWith("Y") -> newY = p.drop(1).toFloatOrNull() ?: currentY
                    p.startsWith("Z") -> newZ = p.drop(1).toFloatOrNull() ?: currentZ
                    p.startsWith("E") -> hasE = true
                }
            }

            val moved = newX != currentX || newY != currentY
            val isExtrude = cmd == "G1" && hasE && moved

            // layerZ dient nur als Höhen-Label der Schicht – auf erste Extrusion gesetzt.
            // Schichtwechsel passieren ausschließlich über die Marker oben (flushLayer()).
            if (isExtrude && layerZ.isNaN()) {
                layerZ = newZ
            }

            // Segmente erst ab der ersten Extrusion der Schicht sammeln – so landen
            // Start-G-Code-/Purge-Fahrten nicht als Phantom-Schicht.
            if (!layerZ.isNaN() && moved) {
                val isTravel = cmd == "G0" || !hasE
                val moveType = when {
                    isTravel -> com.klipperremote.app.data.model.MoveType.TRAVEL
                    currentTypeComment.contains("support") -> com.klipperremote.app.data.model.MoveType.SUPPORT
                    currentTypeComment.contains("infill") || currentTypeComment == "fill" ||
                        currentTypeComment == "skin" || currentTypeComment.contains("sparse") ||
                        currentTypeComment.contains("solid infill") -> com.klipperremote.app.data.model.MoveType.INFILL
                    else -> com.klipperremote.app.data.model.MoveType.PRINT
                }
                currentSegments.add(GCodeSegment(currentX, currentY, newX, newY, moveType))
            }

            currentX = newX; currentY = newY; currentZ = newZ
        }

        flushLayer()
        return layers
    }

    // ── Maschine / Konfigurationsdateien ────────────────────────────────────────

    fun loadConfigFiles() {
        _uiState.update { it.copy(configFilesLoading = true) }
        queue.enqueueNormal {
            repository.listConfigFiles()
                .onSuccess { files ->
                    _uiState.update { it.copy(configFiles = files, configFilesLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(configFilesLoading = false, editingConfigError = e.message) }
                }
        }
    }

    fun openConfigFile(path: String) {
        _uiState.update { it.copy(editingConfigPath = path, editingConfigContent = "", editingConfigError = null, editingConfigSaved = false) }
        queue.enqueueNormal {
            repository.readConfigFile(path)
                .onSuccess { content ->
                    _uiState.update { it.copy(editingConfigContent = content) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(editingConfigError = e.message) }
                }
        }
    }

    fun saveCurrentConfigFile(content: String) {
        val path = _uiState.value.editingConfigPath ?: return
        _uiState.update { it.copy(editingConfigSaving = true, editingConfigSaved = false, editingConfigError = null) }
        queue.enqueueHigh {
            repository.saveConfigFile(path, content)
                .onSuccess {
                    _uiState.update { it.copy(editingConfigSaving = false, editingConfigSaved = true) }
                    delay(2000L)
                    _uiState.update { it.copy(editingConfigSaved = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(editingConfigSaving = false, editingConfigError = e.message) }
                }
        }
    }

    fun updateEditingConfigContent(content: String) {
        _uiState.update { it.copy(editingConfigContent = content) }
    }

    fun closeConfigEditor() {
        _uiState.update { it.copy(editingConfigPath = null, editingConfigContent = "", editingConfigError = null, editingConfigSaved = false) }
    }

    fun restartHost() {
        queue.enqueueHigh {
            repository.restartHost()
                .onSuccess {
                    _uiState.update { it.copy(gcodeResult = "Host-Neustart ausgelöst…") }
                    delay(3000L)
                    _uiState.update { it.copy(gcodeResult = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Neustart fehlgeschlagen: ${e.message}") }
                }
        }
    }

    private fun autoDetectWebcamIfNeeded() {
        viewModelScope.launch {
            // DataStore braucht kurz zum Laden
            delay(1000L)
            val host = _uiState.value.config.host
            if (host.isBlank()) return@launch
            // Generischen Platzhalterwert auch überschreiben (kein echter Nutzer-Eintrag)
            val savedUrl = _uiState.value.webcamConfig.customUrl
            val isPlaceholder = savedUrl == "/webcam/?action=stream" || savedUrl.isBlank()
            if (savedUrl.isNotBlank() && !isPlaceholder) return@launch

            queue.enqueueNormal {
                repository.detectCrownestCams()
                    .onSuccess { cams ->
                        val cam = cams.firstOrNull() ?: return@onSuccess
                        val currentConfig = _uiState.value.webcamConfig
                        val currentUrl = currentConfig.customUrl
                        if (currentUrl.isNotBlank() && currentUrl != "/webcam/?action=stream") return@onSuccess
                        repository.saveWebcamConfig(
                            currentConfig.copy(
                                name = cam.name,
                                customUrl = "/?action=stream",
                                snapshotUrl = "/?action=snapshot",
                                webcamPort = cam.port
                            )
                        )
                    }
            }
        }
    }

    fun detectCrownest() {
        _uiState.update { it.copy(crownestDetecting = true, crownestCams = emptyList()) }
        queue.enqueueNormal {
            repository.detectCrownestCams()
                .onSuccess { cams ->
                    _uiState.update { it.copy(crownestCams = cams, crownestDetecting = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(crownestDetecting = false) }
                }
        }
    }

    fun clearCrownest() {
        _uiState.update { it.copy(crownestCams = emptyList()) }
    }

    fun autoDetectFirstCam() {
        _uiState.update { it.copy(crownestDetecting = true, crownestAutoDetectedCam = null) }
        queue.enqueueNormal {
            repository.detectCrownestCams()
                .onSuccess { cams ->
                    _uiState.update { it.copy(crownestDetecting = false, crownestAutoDetectedCam = cams.firstOrNull()) }
                }
                .onFailure {
                    _uiState.update { it.copy(crownestDetecting = false) }
                }
        }
    }

    fun clearCrownestAutoDetected() {
        _uiState.update { it.copy(crownestAutoDetectedCam = null) }
    }

    // ── G-Code Viewer ────────────────────────────────────────────────────────────

    private fun showGcodeResult(msg: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(gcodeResult = msg) }
            delay(2000L)
            _uiState.update { it.copy(gcodeResult = null) }
        }
    }
}
