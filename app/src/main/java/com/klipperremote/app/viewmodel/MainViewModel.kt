package com.klipperremote.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.WebcamConfig
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
    val powerDevices: List<PowerDevice> = emptyList()
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
        startPolling()
        loadFiles()
        loadMacros()
    }

    private fun startPolling() {
        viewModelScope.launch {
            fetchPowerDevices()
            while (true) {
                fetchTemperatures()
                fetchPrinterStatus()
                fetchPosition()
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
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getTemperatures()
                .onSuccess { temps ->
                    _uiState.update { it.copy(temperatures = temps, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
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
                    _uiState.update { it.copy(powerDevices = devices) }
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

    private fun showGcodeResult(msg: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(gcodeResult = msg) }
            delay(2000L)
            _uiState.update { it.copy(gcodeResult = null) }
        }
    }
}
