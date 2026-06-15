package com.klipperremote.app.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.klipperremote.app.data.model.AppConfig
import com.klipperremote.app.data.model.BackupConfigFile
import com.klipperremote.app.data.model.BLOCK_DELAY
import com.klipperremote.app.data.model.BLOCK_GOTO
import com.klipperremote.app.data.model.BLOCK_HOME
import com.klipperremote.app.data.model.BLOCK_MACRO
import com.klipperremote.app.data.model.BLOCK_POWER
import com.klipperremote.app.data.model.BLOCK_ZTILT
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.data.model.ConsoleEntry
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.DriverSettings
import com.klipperremote.app.data.model.GCodeLayer
import com.klipperremote.app.data.model.GCodeSegment
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.PrinterProfile
import com.klipperremote.app.data.model.RoutineData
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.klipperremote.app.data.network.ApiRequestQueue
import com.klipperremote.app.data.repository.KlipperRepository
import com.klipperremote.app.PrintMonitor
import com.klipperremote.app.PrintMonitorService
import com.klipperremote.app.PrintNotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    val appConfig: AppConfig = AppConfig(),
    val webcamConfig: WebcamConfig = WebcamConfig(),
    // Alle Webcams des aktiven Druckers (Wischen zum Wechseln, wenn >1).
    val webcams: List<WebcamConfig> = emptyList(),
    // Alle gespeicherten Drucker + ID des aktiven (Drei-Striche-Menü).
    val printers: List<PrinterProfile> = emptyList(),
    val selectedPrinterId: String = "",
    val setTempSuccess: String? = null,
    val printerState: String = "offline",
    // Unveränderter Klipper-print_stats.state (für Abschluss-/Abbrucherkennung).
    val rawPrintState: String = "",
    val position: KlipperPosition = KlipperPosition(),
    val files: List<PrintFile> = emptyList(),
    val macros: List<String> = emptyList(),
    val favoriteMacros: List<String> = emptyList(),
    val pinnedGcodes: List<String> = listOf("G28", "SAVE_CONFIG", "PROBE_CALIBRATE"),
    val gcodeResult: String? = null,
    val powerDevices: List<PowerDevice> = emptyList(),
    val connectionFailed: Boolean = false,
    // Wird true nach 3 aufeinanderfolgenden Verbindungsfehlern → Dialog anzeigen
    val showConnectionFailedDialog: Boolean = false,
    // true, solange nach "Abbrechen" keine Verbindungsversuche/Datenabfragen erfolgen dürfen
    val connectionPaused: Boolean = false,
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
    // Konfig-Backup: Inhalte werden geladen, dann lokal speichern/teilen
    val configBackupLoading: Boolean = false,
    val pendingBackupFiles: List<BackupConfigFile>? = null,
    // Konfig-Upload: vom Nutzer ausgewählte Dateien werden zum Drucker hochgeladen
    val configUploadLoading: Boolean = false,
    val configUploadResult: String? = null,
    // Crownest-Kameraerkennung
    val crownestCams: List<CrownestCam> = emptyList(),
    val crownestDetecting: Boolean = false,
    val crownestAutoDetectedCam: CrownestCam? = null,
    // Druckstatistiken (null = kein aktiver Druck)
    val printStats: PrintStats? = null,
    // Tuning-Daten
    val tuningData: TuningData = TuningData(),
    // Konsole (Moonraker gcode_store)
    val consoleEntries: List<ConsoleEntry> = emptyList(),
    val consoleLoading: Boolean = false,
    // Treiber-Einstellungen (X/Y/Z)
    val driverSettings: DriverSettings = DriverSettings(),
    val driverSettingsLoading: Boolean = false,
    val driverSettingsSaving: Boolean = false,
    val driverSettingsSaved: Boolean = false,
    val driverSettingsError: String? = null,
    // Druckergebnis je Dateiname: true = erfolgreich, false = abgebrochen/Fehler.
    // Steuert das Symbol neben Vorschau-/Play-Button in der Dateiliste.
    val printResults: Map<String, Boolean> = emptyMap(),
    // Temperaturverlauf der letzten ~10 Min. (max. 120 Punkte pro Heizer).
    // Key = Heizer-Name, Value = Liste von (timestampMs, temperaturCelsius).
    val temperatureHistory: Map<String, List<Pair<Long, Float>>> = emptyMap(),
    // Gespeicherte Routinen (max. 4)
    val routines: List<RoutineData> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: KlipperRepository,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()


    /** Zähler aufeinanderfolgender Verbindungsfehler (für den Hinweisdialog). */
    private var consecutiveFailures = 0

    /**
     * Wenn true, pausieren alle Polling-Schleifen – es werden keine Verbindungs-
     * versuche bzw. Datenabfragen mehr ausgelöst. Wird nach "Abbrechen" gesetzt
     * und erst beim Neustart der App oder beim Speichern von Verbindungs-
     * einstellungen wieder aufgehoben.
     */
    @Volatile
    private var connectionPaused = false

    /**
     * Ob die App im Vordergrund ist. Im Hintergrund werden nur noch die für die
     * Benachrichtigung nötigen Daten und das nur alle 10 Sekunden abgefragt, um
     * den Klipper-Rechner nicht dauerhaft zu belasten. Wird vom Activity-
     * Lifecycle über [setAppForeground] gesetzt.
     */
    @Volatile
    private var appInForeground = true

    // ── Zentrale App-Konfiguration (Parallelität + Polling-Intervalle) ──────────
    // Werden aus der gespeicherten AppConfig befüllt und von Queue/Polling gelesen.
    @Volatile
    private var maxConcurrentConnections = 1
    @Volatile
    private var tempIntervalMs = 2000L
    @Volatile
    private var backgroundIntervalMs = 4000L
    @Volatile
    private var powerIntervalMs = 15000L
    @Volatile
    private var notifyIntervalMs = 10000L

    /** Priorisierte Warteschlange für alle API-Anfragen (Parallelität via AppConfig). */
    private val queue = ApiRequestQueue(viewModelScope) { maxConcurrentConnections }

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
        viewModelScope.launch {
            repository.webcamsFlow.collect { webcams ->
                _uiState.update { it.copy(webcams = webcams) }
            }
        }
        viewModelScope.launch {
            repository.profilesFlow.collect { printers ->
                _uiState.update { it.copy(printers = printers) }
            }
        }
        viewModelScope.launch {
            repository.selectedPrinterIdFlow.collect { id ->
                _uiState.update { it.copy(selectedPrinterId = id) }
            }
        }
        // App-Konfiguration (Parallelität + Intervalle) übernehmen.
        viewModelScope.launch {
            repository.appConfigFlow.collect { appConfig ->
                applyAppConfig(appConfig)
                _uiState.update { it.copy(appConfig = appConfig) }
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
        // Druckergebnisse (Symbole in der Dateiliste) aus DataStore laden
        viewModelScope.launch {
            val results = repository.loadPrintResults()
            if (results.isNotEmpty()) {
                _uiState.update { it.copy(printResults = results) }
            }
        }
        // Routinen von Disk laden
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadRoutinesFromDisk()
            if (loaded.isNotEmpty()) _uiState.update { it.copy(routines = loaded) }
        }
        startPolling()
        collectWsSnapshot()
        loadFiles()
        autoDetectWebcamIfNeeded()
    }

    // ── Routinen ──────────────────────────────────────────────────────────────

    private val gson = com.google.gson.Gson()
    private val routinesFile get() = java.io.File(appContext.filesDir, "routines.json")

    private fun loadRoutinesFromDisk(): List<RoutineData> = runCatching {
        gson.fromJson(routinesFile.readText(), Array<RoutineData>::class.java).toList()
    }.getOrDefault(emptyList())

    private fun saveRoutinesToDisk(routines: List<RoutineData>) {
        runCatching { routinesFile.writeText(gson.toJson(routines)) }
    }

    fun saveRoutine(routine: RoutineData) {
        val current = _uiState.value.routines.toMutableList()
        val idx = current.indexOfFirst { it.id == routine.id }
        if (idx >= 0) current[idx] = routine else if (current.size < 4) current.add(routine)
        _uiState.update { it.copy(routines = current) }
        viewModelScope.launch(Dispatchers.IO) { saveRoutinesToDisk(current) }
    }

    fun deleteRoutine(id: String) {
        val current = _uiState.value.routines.filter { it.id != id }
        _uiState.update { it.copy(routines = current) }
        viewModelScope.launch(Dispatchers.IO) { saveRoutinesToDisk(current) }
    }

    fun executeRoutine(routine: RoutineData) {
        viewModelScope.launch {
            routine.blocks.forEach { block ->
                when (block.type) {
                    BLOCK_POWER -> block.deviceName?.let {
                        withContext(Dispatchers.IO) { repository.togglePowerDevice(it, block.turnOn) }
                    }
                    BLOCK_DELAY -> delay((block.seconds * 1000L).toLong())
                    BLOCK_HOME -> {
                        withContext(Dispatchers.IO) { repository.homeAxes(block.axes) }
                        // Wait for Klipper to enter "printing" state (moving)
                        withTimeoutOrNull(5_000L) {
                            while (_uiState.value.printerState != "printing") { delay(250L) }
                        }
                        // Wait until homing completes (leaves "printing")
                        withTimeoutOrNull(180_000L) {
                            while (_uiState.value.printerState == "printing") { delay(500L) }
                        }
                    }
                    BLOCK_ZTILT -> {
                        withContext(Dispatchers.IO) { repository.sendGcode("Z_TILT_ADJUST") }
                        withTimeoutOrNull(5_000L) {
                            while (_uiState.value.printerState != "printing") { delay(250L) }
                        }
                        withTimeoutOrNull(300_000L) {
                            while (_uiState.value.printerState == "printing") { delay(500L) }
                        }
                    }
                    BLOCK_GOTO  -> moveToXyz(block.x, block.y, block.z, block.feedrate)
                    BLOCK_MACRO -> block.command?.let {
                        withContext(Dispatchers.IO) { repository.sendGcode(it) }
                    }
                }
            }
        }
    }

    /** true, solange ein Druck läuft oder pausiert ist (Steuerung wird dann gesperrt). */
    private fun isPrintActive(): Boolean {
        val s = _uiState.value.printerState
        return s == "printing" || s == "paused"
    }

    private fun startPolling() {
        // Status-Schleife (Vordergrund) – HTTP-Fallback, wenn WebSocket nicht verbunden.
        // Ist der WS verbunden, liefert [collectWsSnapshot] bereits Push-Updates für alle
        // Statuswerte; HTTP-Abfragen wären dann redundant und belasten den Drucker unnötig.
        viewModelScope.launch {
            while (true) {
                if (!connectionPaused && appInForeground && !repository.wsConnected.value) {
                    if (isPrintActive()) queue.enqueueHigh { fetchPrinterSnapshotInternal() }
                    else queue.enqueueHigh { fetchTemperaturesInternal() }
                }
                delay(tempIntervalMs)
            }
        }
        // Hintergrunddaten (Vordergrund, NUR im Leerlauf, NUR ohne WS) – Intervall aus AppConfig.
        viewModelScope.launch {
            if (!connectionPaused && appInForeground && !isPrintActive() && !repository.wsConnected.value) {
                queue.enqueueNormal { fetchPowerDevicesInternal() }
            }
            while (true) {
                delay(backgroundIntervalMs)
                if (connectionPaused || !appInForeground || isPrintActive() || repository.wsConnected.value) continue
                queue.enqueueNormal { fetchPrinterStatusInternal() }
                queue.enqueueNormal { fetchPositionInternal() }
            }
        }
        // Power-Geräte seltener aktualisieren (Vordergrund) – Während des Drucks und bei
        // aktiver WS-Verbindung werden Energiegeräte nicht per HTTP abgefragt.
        viewModelScope.launch {
            while (true) {
                delay(powerIntervalMs)
                if (!connectionPaused && appInForeground && !isPrintActive() && !repository.wsConnected.value) {
                    queue.enqueueNormal { fetchPowerDevicesInternal() }
                }
            }
        }
        // Hintergrund-Modus: HTTP-Snapshot nur wenn WS nicht verbunden.
        // Bei aktiver WS-Verbindung pusht Moonraker Änderungen automatisch auch im Hintergrund.
        viewModelScope.launch {
            while (true) {
                delay(notifyIntervalMs)
                if (connectionPaused || appInForeground || repository.wsConnected.value) continue
                queue.enqueueNormal { fetchPrinterSnapshotInternal() }
            }
        }
    }

    /**
     * Abonniert den via WebSocket gepushten Drucker-Status und aktualisiert den UI-State
     * direkt bei jedem Update. HTTP-Polling läuft parallel als Fallback und wird in
     * [startPolling] automatisch pausiert, sobald der WS-Client verbunden ist.
     */
    private fun collectWsSnapshot() {
        viewModelScope.launch {
            repository.wsSnapshot.collect { snap ->
                if (snap != null) {
                    _uiState.update {
                        it.copy(
                            temperatures       = snap.temperatures,
                            isLoading          = false,
                            printerState       = snap.printerState,
                            rawPrintState      = snap.rawState,
                            position           = snap.position,
                            printProgress      = snap.printProgress,
                            printSpeedMmPerSec = snap.printSpeedMmPerSec,
                            printStats         = snap.printStats,
                            tuningData         = snap.tuningData
                        )
                    }
                    reportConnectionResult(success = true)
                    processPrintLifecycle(snap)
                }
            }
        }
    }

    /** Übernimmt die App-Konfiguration in die Laufzeit-Felder (Queue + Polling). */
    private fun applyAppConfig(appConfig: AppConfig) {
        maxConcurrentConnections = appConfig.maxConcurrentConnections.coerceAtLeast(1)
        tempIntervalMs = appConfig.tempIntervalSec.coerceAtLeast(1) * 1000L
        backgroundIntervalMs = appConfig.backgroundIntervalSec.coerceAtLeast(1) * 1000L
        powerIntervalMs = appConfig.powerIntervalSec.coerceAtLeast(1) * 1000L
        notifyIntervalMs = appConfig.notifyIntervalSec.coerceAtLeast(5) * 1000L
    }

    /**
     * Wird vom Activity-Lifecycle aufgerufen. Im Hintergrund werden alle
     * Vordergrund-Polling-Schleifen pausiert; es läuft nur noch die 10-Sekunden-
     * Benachrichtigungsabfrage. Beim Zurückkehren in den Vordergrund werden
     * sofort die wichtigsten Daten aktualisiert.
     */
    fun setAppForeground(inForeground: Boolean) {
        val wasInForeground = appInForeground
        appInForeground = inForeground
        if (inForeground && !wasInForeground && !connectionPaused) {
            fetchTemperatures()
        }
    }

    /**
     * Zentrale Auswertung des Verbindungsergebnisses. Erfolg setzt den Fehler-
     * zähler zurück; nach 3 aufeinanderfolgenden Fehlern wird der Hinweisdialog
     * angezeigt und das Polling pausiert, bis der Nutzer reagiert.
     */
    private fun reportConnectionResult(success: Boolean) {
        if (success) {
            consecutiveFailures = 0
            _uiState.update { it.copy(connectionFailed = false) }
        } else {
            consecutiveFailures++
            _uiState.update { it.copy(connectionFailed = true) }
            if (consecutiveFailures >= 3 && !connectionPaused && !_uiState.value.showConnectionFailedDialog) {
                // Polling anhalten, bis der Nutzer im Dialog entscheidet.
                connectionPaused = true
                _uiState.update { it.copy(showConnectionFailedDialog = true, connectionPaused = true) }
            }
        }
    }

    /** "Erneut versuchen": Zähler zurücksetzen, Polling fortsetzen. */
    fun retryConnection() {
        consecutiveFailures = 0
        connectionPaused = false
        _uiState.update { it.copy(showConnectionFailedDialog = false, connectionPaused = false) }
        fetchTemperatures()
    }

    /**
     * "Abbrechen": Dialog schließen und Verbindungsversuche/Datenabfragen
     * unterbinden, bis die App neu gestartet oder die Verbindungseinstellungen
     * gespeichert werden.
     */
    fun cancelConnection() {
        connectionPaused = true
        _uiState.update { it.copy(showConnectionFailedDialog = false, connectionPaused = true) }
    }

    fun fetchTemperatures() {
        queue.enqueueHigh { fetchTemperaturesInternal() }
    }

    private fun updateTempHistory(temps: List<TemperatureInfo>) {
        val now = System.currentTimeMillis()
        val old = _uiState.value.temperatureHistory
        val updated = old.toMutableMap()
        temps.forEach { t ->
            // Alle Heizer und Temperatursensoren tracken (kein Namensfilter)
            val history = (updated[t.name] ?: emptyList()).toMutableList()
            history.add(Pair(now, t.current))
            if (history.size > 120) history.subList(0, history.size - 120).clear()
            updated[t.name] = history
        }
        _uiState.update { it.copy(temperatureHistory = updated) }
    }

    private suspend fun fetchTemperaturesInternal() {
        repository.getTemperatures()
            .onSuccess { temps ->
                _uiState.update { it.copy(temperatures = temps, isLoading = false) }
                updateTempHistory(temps)
                reportConnectionResult(success = true)
            }
            .onFailure {
                reportConnectionResult(success = false)
            }
    }

    /**
     * Holt mit EINER einzigen API-Anfrage alle Statuswerte (Temperaturen, Position,
     * Fortschritt, Druckstatistik, Tuning + Druckerzustand) und aktualisiert den
     * gesamten UI-State auf einmal. Wird während des Drucks im Status-Intervall sowie
     * im Hintergrund-Modus für die Benachrichtigung verwendet.
     */
    private suspend fun fetchPrinterSnapshotInternal() {
        repository.getPrinterSnapshot()
            .onSuccess { snap ->
                _uiState.update {
                    it.copy(
                        temperatures = snap.temperatures,
                        isLoading = false,
                        printerState = snap.printerState,
                        rawPrintState = snap.rawState,
                        position = snap.position,
                        printProgress = snap.printProgress,
                        printSpeedMmPerSec = snap.printSpeedMmPerSec,
                        printStats = snap.printStats,
                        tuningData = snap.tuningData
                    )
                }
                updateTempHistory(snap.temperatures)
                reportConnectionResult(success = true)
                processPrintLifecycle(snap)
            }
            .onFailure {
                reportConnectionResult(success = false)
            }
    }

    private suspend fun fetchPrinterStatusInternal() {
        repository.getPrinterStatus()
            .onSuccess { status ->
                _uiState.update { it.copy(printerState = status.state) }
            }
    }

    /**
     * Wertet ein Status-Update über [PrintMonitor] aus: zeigt/aktualisiert die laufende
     * Druck-Benachrichtigung, sammelt Min/Max-Statistiken und löst bei Druckende die
     * Abschluss-/Fehlerbenachrichtigung aus. Bei einem beendeten Druck wird zusätzlich
     * der [PrintMonitorService] für die Vordergrund-Überwachung gestartet bzw. gestoppt
     * und das Ergebnis-Symbol persistiert.
     */
    private fun processPrintLifecycle(snap: com.klipperremote.app.data.model.PrinterSnapshot) {
        val printing = snap.printerState == "printing" || snap.printerState == "paused"
        // Hintergrund-Überwachung an den Druckstatus koppeln (zuverlässige Updates im Hintergrund).
        if (printing) PrintMonitorService.start(appContext)
        val result = PrintMonitor.onSnapshot(appContext, snap)
        result?.let {
            recordPrintResult(it.filename, it.success)
            PrintMonitorService.stop(appContext)
        }
    }

    /** Speichert das Druckergebnis (Symbol in der Dateiliste) und persistiert es. */
    private fun recordPrintResult(filename: String, success: Boolean) {
        val updated = _uiState.value.printResults + (filename to success)
        _uiState.update { it.copy(printResults = updated) }
        viewModelScope.launch { repository.savePrintResults(updated) }
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
        val loc = java.util.Locale.US
        val parts = mutableListOf<String>()
        x?.let { parts.add("X%.3f".format(loc, it)) }
        y?.let { parts.add("Y%.3f".format(loc, it)) }
        z?.let { parts.add("Z%.3f".format(loc, it)) }
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
        // Vorheriges Ergebnis-Symbol dieser Datei entfernen – wird beim Druckende neu gesetzt.
        if (_uiState.value.printResults.containsKey(filename)) {
            val updated = _uiState.value.printResults - filename
            _uiState.update { it.copy(printResults = updated) }
            viewModelScope.launch { repository.savePrintResults(updated) }
        }
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
        // Verbindungseinstellungen geändert → Pause aufheben und erneut versuchen.
        consecutiveFailures = 0
        connectionPaused = false
        _uiState.update { it.copy(showConnectionFailedDialog = false, connectionPaused = false) }
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    fun saveAppConfig(appConfig: AppConfig) {
        // Sofort anwenden, damit Parallelität/Intervalle nicht erst nach dem
        // nächsten Flow-Emit greifen, dann persistieren.
        applyAppConfig(appConfig)
        viewModelScope.launch {
            repository.saveAppConfig(appConfig)
        }
    }

    fun saveWebcamConfig(config: WebcamConfig) {
        viewModelScope.launch {
            repository.saveWebcamConfig(config)
        }
    }

    /** Speichert die komplette Webcam-Liste des aktiven Druckers (Multi-Webcam). */
    fun saveWebcams(webcams: List<WebcamConfig>) {
        viewModelScope.launch {
            repository.saveWebcams(webcams)
        }
    }

    // ── Mehrere Drucker verwalten (Drei-Striche-Menü) ────────────────────────

    fun addPrinter(name: String, host: String, port: Int, apiKey: String) {
        consecutiveFailures = 0
        connectionPaused = false
        _uiState.update { it.copy(showConnectionFailedDialog = false, connectionPaused = false) }
        viewModelScope.launch {
            repository.addPrinter(name = name, host = host, port = port, apiKey = apiKey)
        }
    }

    fun updatePrinter(profile: PrinterProfile) {
        viewModelScope.launch {
            repository.updatePrinter(profile)
        }
    }

    fun deletePrinter(id: String) {
        viewModelScope.launch {
            repository.deletePrinter(id)
        }
    }

    fun selectPrinter(id: String) {
        consecutiveFailures = 0
        connectionPaused = false
        _uiState.update { it.copy(showConnectionFailedDialog = false, connectionPaused = false) }
        viewModelScope.launch {
            repository.selectPrinter(id)
        }
    }

    private suspend fun fetchPowerDevicesInternal() {
        repository.getPowerDevices()
            .onSuccess { devices ->
                _uiState.update { it.copy(powerDevices = devices) }
                reportConnectionResult(success = true)
                if (devices.isNotEmpty()) repository.saveCachedPowerDevices(devices)
            }
            .onFailure {
                reportConnectionResult(success = false)
            }
    }

    /** Lädt die Energiegeräte einmalig nach (z. B. beim Öffnen des Power-Dialogs während des Drucks). */
    fun refreshPowerDevices() {
        if (connectionPaused) return
        queue.enqueueHigh { fetchPowerDevicesInternal() }
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
            // G-Code-Datei zeilenweise streamen und parsen – kein vollständiger RAM-Puffer,
            // damit Moonraker auf dem RPi nicht einfriert (große Dateien können 100+ MB sein).
            repository.streamGcodeFile(filename) { reader -> parseGCode(reader) }
                .onSuccess { layers ->
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

    private fun parseGCode(reader: java.io.BufferedReader): List<GCodeLayer> {
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

        // Segmente pro Schicht begrenzen: Zeichnen aller Segmente kostet CPU pro Frame.
        // Bei sehr komplexen Schichten (>8000 Segmente) wird jedes N-te übernommen.
        val MAX_SEGS = 8_000

        fun flushLayer() {
            if (currentSegments.isNotEmpty()) {
                val segs = if (currentSegments.size > MAX_SEGS) {
                    val stride = currentSegments.size / MAX_SEGS
                    currentSegments.filterIndexed { i, _ -> i % stride == 0 }
                } else {
                    currentSegments.toList()
                }
                layers.add(GCodeLayer(if (layerZ.isNaN()) currentZ else layerZ, segs))
            }
            currentSegments.clear()
            layerZ = Float.NaN
        }

        for (rawLine in reader.lineSequence()) {
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

    // Lädt die Inhalte der ausgewählten Konfig-Dateien für ein Backup.
    // Ergebnis landet in pendingBackupFiles → UI zeigt dann Speichern/Teilen-Dialog.
    fun fetchConfigsForBackup(paths: List<String>) {
        if (paths.isEmpty()) return
        _uiState.update { it.copy(configBackupLoading = true, editingConfigError = null) }
        queue.enqueueNormal {
            val results = mutableListOf<BackupConfigFile>()
            var firstError: String? = null
            for (path in paths) {
                repository.readConfigFile(path)
                    .onSuccess { content ->
                        results.add(BackupConfigFile(path.substringAfterLast('/'), content))
                    }
                    .onFailure { e -> if (firstError == null) firstError = e.message }
            }
            if (results.isEmpty()) {
                _uiState.update {
                    it.copy(configBackupLoading = false, editingConfigError = firstError ?: "Backup fehlgeschlagen")
                }
            } else {
                _uiState.update { it.copy(configBackupLoading = false, pendingBackupFiles = results) }
            }
        }
    }

    fun clearPendingBackup() {
        _uiState.update { it.copy(pendingBackupFiles = null) }
    }

    // Lädt vom Nutzer (per System-Dateiauswahl) gewählte Dateien als
    // Konfigurationsdateien zum Drucker hoch (Moonraker root=config) und
    // aktualisiert anschließend die Dateiliste.
    fun uploadConfigFiles(files: List<BackupConfigFile>) {
        if (files.isEmpty()) return
        _uiState.update { it.copy(configUploadLoading = true, editingConfigError = null) }
        queue.enqueueHigh {
            var uploaded = 0
            var firstError: String? = null
            for (f in files) {
                repository.saveConfigFile(f.name, f.content)
                    .onSuccess { uploaded++ }
                    .onFailure { e -> if (firstError == null) firstError = e.message }
            }
            val msg = if (uploaded > 0) "$uploaded Datei(en) hochgeladen"
                      else "Hochladen fehlgeschlagen: ${firstError ?: "unbekannter Fehler"}"
            _uiState.update { it.copy(configUploadLoading = false, configUploadResult = msg) }
            if (uploaded > 0) {
                repository.listConfigFiles().onSuccess { list ->
                    _uiState.update { it.copy(configFiles = list) }
                }
            }
        }
    }

    fun clearConfigUploadResult() {
        _uiState.update { it.copy(configUploadResult = null) }
    }

    fun firmwareRestart() {
        queue.enqueueHigh {
            repository.firmwareRestart()
                .onSuccess {
                    _uiState.update { it.copy(gcodeResult = "Firmware-Neustart ausgelöst…") }
                    delay(3000L)
                    _uiState.update { it.copy(gcodeResult = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Firmware-Neustart fehlgeschlagen: ${e.message}") }
                }
        }
    }

    fun loadConsole() {
        _uiState.update { it.copy(consoleLoading = true) }
        queue.enqueueHigh {
            repository.getGcodeStore()
                .onSuccess { entries ->
                    _uiState.update { it.copy(consoleEntries = entries, consoleLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(consoleLoading = false, error = "Konsole: ${e.message}") }
                }
        }
    }

    // ── Treiber-Einstellungen (X/Y/Z) ───────────────────────────────────────────

    fun loadDriverSettings() {
        _uiState.update { it.copy(driverSettingsLoading = true, driverSettingsError = null) }
        queue.enqueueNormal {
            repository.getDriverSettings()
                .onSuccess { settings ->
                    _uiState.update { it.copy(driverSettings = settings, driverSettingsLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(driverSettingsLoading = false, driverSettingsError = e.message) }
                }
        }
    }

    // Lauf-/Halte-Strom je Achse live anwenden (SET_TMC_CURRENT).
    // edits: stepperName → Paar(runCurrent, holdCurrent?)
    fun saveDriverSettings(edits: Map<String, Pair<Float, Float?>>) {
        if (edits.isEmpty()) return
        _uiState.update { it.copy(driverSettingsSaving = true, driverSettingsSaved = false, driverSettingsError = null) }
        queue.enqueueHigh {
            var firstError: String? = null
            for ((stepper, currents) in edits) {
                val res = repository.setDriverCurrent(stepper, currents.first, currents.second)
                if (res.isFailure && firstError == null) {
                    firstError = res.exceptionOrNull()?.message
                }
            }
            if (firstError == null) {
                _uiState.update { it.copy(driverSettingsSaving = false, driverSettingsSaved = true) }
                delay(2000L)
                _uiState.update { it.copy(driverSettingsSaved = false) }
            } else {
                _uiState.update { it.copy(driverSettingsSaving = false, driverSettingsError = firstError) }
            }
        }
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
