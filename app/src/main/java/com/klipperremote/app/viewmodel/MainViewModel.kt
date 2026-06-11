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
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.WebcamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.klipperremote.app.data.repository.KlipperRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val crownestAutoDetectedCam: CrownestCam? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: KlipperRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
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
        startPolling()
        loadFiles()
        loadMacros()
        autoDetectWebcamIfNeeded()
    }

    private fun startPolling() {
        viewModelScope.launch {
            fetchPowerDevices()
            while (true) {
                fetchTemperatures()
                fetchPrinterStatus()
                fetchPosition()
                fetchPrintProgress()
                delay(3000L)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(10000L)
                fetchPowerDevices()
            }
        }
    }

    fun fetchTemperatures() {
        viewModelScope.launch {
            repository.getTemperatures()
                .onSuccess { temps ->
                    _uiState.update { it.copy(temperatures = temps, isLoading = false, connectionFailed = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(connectionFailed = true) }
                }
        }
    }

    private fun fetchPrinterStatus() {
        viewModelScope.launch {
            repository.getPrinterStatus()
                .onSuccess { status ->
                    _uiState.update { it.copy(printerState = status.state) }
                }
        }
    }

    private fun fetchPrintProgress() {
        viewModelScope.launch {
            repository.getPrintProgress()
                .onSuccess { progress ->
                    _uiState.update { it.copy(printProgress = progress) }
                }
        }
    }

    private fun fetchPosition() {
        viewModelScope.launch {
            repository.getPosition()
                .onSuccess { pos ->
                    _uiState.update { it.copy(position = pos) }
                }
        }
    }

    fun setTemperature(heaterName: String, target: Float) {
        viewModelScope.launch {
            repository.setTemperature(heaterName, target)
                .onSuccess {
                    _uiState.update { it.copy(setTempSuccess = "Temperatur gesetzt: $target°C") }
                    delay(2000L)
                    _uiState.update { it.copy(setTempSuccess = null) }
                    fetchTemperatures()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Fehler: ${e.message}") }
                }
        }
    }

    fun jogMove(axis: String, distance: Float) {
        viewModelScope.launch {
            repository.jogMove(axis, distance)
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Bewegung fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun homeAxes(axes: String = "") {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            repository.sendGcode(gcode)
                .onSuccess { showGcodeResult("GCode gesendet: $gcode") }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "GCode-Fehler: ${e.message}") }
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
        viewModelScope.launch {
            repository.getFiles()
                .onSuccess { files ->
                    _uiState.update { it.copy(files = files) }
                }
        }
    }

    fun startPrint(filename: String) {
        viewModelScope.launch {
            repository.startPrint(filename)
                .onSuccess {
                    showGcodeResult("Druck gestartet: $filename")
                    fetchPrinterStatus()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Druck-Start fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun loadMacros() {
        viewModelScope.launch {
            repository.getMacros()
                .onSuccess { macros ->
                    _uiState.update { it.copy(macros = macros) }
                }
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

    private fun fetchPowerDevices() {
        viewModelScope.launch {
            repository.getPowerDevices()
                .onSuccess { devices ->
                    _uiState.update { it.copy(powerDevices = devices, connectionFailed = false) }
                    if (devices.isNotEmpty()) repository.saveCachedPowerDevices(devices)
                }
                .onFailure {
                    _uiState.update { it.copy(connectionFailed = true) }
                }
        }
    }

    fun togglePowerDevice(device: String, on: Boolean) {
        viewModelScope.launch {
            repository.togglePowerDevice(device, on)
                .onSuccess {
                    fetchPowerDevices()
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
        viewModelScope.launch {
            _uiState.update { it.copy(gcodePreviewLoading = true, gcodePreviewMetadata = null, gcodePreviewThumbnail = null) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(gcodeViewerLoading = true, gcodeViewerError = null, gcodeViewerLayers = emptyList()) }
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
        var currentZ = -1f
        var currentX = 0f
        var currentY = 0f
        val currentSegments = mutableListOf<GCodeSegment>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith(";")) continue
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

            // Neue Schicht bei Z-Änderung
            if (newZ != currentZ && newZ >= 0f) {
                if (currentSegments.isNotEmpty() && currentZ >= 0f) {
                    layers.add(GCodeLayer(currentZ, currentSegments.toList()))
                    currentSegments.clear()
                }
                currentZ = newZ
            }

            if (currentZ >= 0f && (newX != currentX || newY != currentY)) {
                val isTravel = cmd == "G0" || !hasE
                currentSegments.add(GCodeSegment(currentX, currentY, newX, newY, isTravel))
            }

            currentX = newX; currentY = newY
        }

        if (currentSegments.isNotEmpty() && currentZ >= 0f) {
            layers.add(GCodeLayer(currentZ, currentSegments.toList()))
        }
        return layers
    }

    // ── Maschine / Konfigurationsdateien ────────────────────────────────────────

    fun loadConfigFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(configFilesLoading = true) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(editingConfigPath = path, editingConfigContent = "", editingConfigError = null, editingConfigSaved = false) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(editingConfigSaving = true, editingConfigSaved = false, editingConfigError = null) }
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
        viewModelScope.launch {
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
            if (_uiState.value.webcamConfig.customUrl.isNotBlank()) return@launch

            repository.detectCrownestCams()
                .onSuccess { cams ->
                    val cam = cams.firstOrNull() ?: return@onSuccess
                    val currentConfig = _uiState.value.webcamConfig
                    if (currentConfig.customUrl.isNotBlank()) return@onSuccess
                    repository.saveWebcamConfig(
                        currentConfig.copy(
                            name = cam.name,
                            customUrl = "http://$host:${cam.port}/?action=stream",
                            snapshotUrl = "http://$host:${cam.port}/?action=snapshot"
                        )
                    )
                }
        }
    }

    fun detectCrownest() {
        viewModelScope.launch {
            _uiState.update { it.copy(crownestDetecting = true, crownestCams = emptyList()) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(crownestDetecting = true, crownestAutoDetectedCam = null) }
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
