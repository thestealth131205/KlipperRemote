package com.klipperremote.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import com.klipperremote.app.data.model.AppConfig
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.data.model.ConsoleEntry
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.DriverSettings
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.PrinterProfile
import com.klipperremote.app.data.model.PrinterSnapshot
import com.klipperremote.app.data.model.PrinterStatusInfo
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.Timelapse
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import com.klipperremote.app.data.model.WebcamStreamType
import com.klipperremote.app.data.network.KlipperClient
import com.klipperremote.app.data.network.MoonrakerWsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KlipperRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // Eigener Scope für den WebSocket-Client (Singleton-Lebensdauer).
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    companion object {
        val KEY_HOST = stringPreferencesKey("klipper_host")
        val KEY_PORT = intPreferencesKey("klipper_port")
        val KEY_USERNAME = stringPreferencesKey("klipper_username")
        val KEY_PASSWORD = stringPreferencesKey("klipper_password")
        val KEY_API_KEY = stringPreferencesKey("klipper_api_key")
        // Mehrere Drucker-Profile (JSON-Array) + ID des aktuell ausgewählten Druckers.
        val KEY_PRINTERS = stringPreferencesKey("printers_json")
        val KEY_SELECTED_PRINTER = stringPreferencesKey("selected_printer_id")
        val KEY_CACHED_POWER_DEVICES = stringPreferencesKey("cached_power_devices")
        val KEY_PRINT_RESULTS = stringPreferencesKey("print_results")
        val KEY_FAVORITE_MACROS = stringPreferencesKey("favorite_macros")

        val KEY_WEBCAM_NAME = stringPreferencesKey("webcam_name")
        val KEY_WEBCAM_URL = stringPreferencesKey("webcam_custom_url")
        val KEY_WEBCAM_SNAPSHOT_URL = stringPreferencesKey("webcam_snapshot_url")
        val KEY_WEBCAM_STREAM_TYPE = stringPreferencesKey("webcam_stream_type")
        val KEY_WEBCAM_FPS = intPreferencesKey("webcam_fps")
        val KEY_WEBCAM_ROTATE = intPreferencesKey("webcam_rotate")
        val KEY_WEBCAM_FLIP_H = stringPreferencesKey("webcam_flip_h")
        val KEY_WEBCAM_FLIP_V = stringPreferencesKey("webcam_flip_v")
        val KEY_WEBCAM_STUN = stringPreferencesKey("webcam_stun_server")
        val KEY_WEBCAM_ICE_USER = stringPreferencesKey("webcam_ice_username")
        val KEY_WEBCAM_ICE_PASS = stringPreferencesKey("webcam_ice_password")
        val KEY_WEBCAM_PORT = intPreferencesKey("webcam_port")

        val KEY_APP_MAX_CONNECTIONS = intPreferencesKey("app_max_connections")
        val KEY_APP_TEMP_INTERVAL = intPreferencesKey("app_temp_interval_sec")
        val KEY_APP_BG_INTERVAL = intPreferencesKey("app_bg_interval_sec")
        val KEY_APP_POWER_INTERVAL = intPreferencesKey("app_power_interval_sec")
        val KEY_APP_NOTIFY_INTERVAL = intPreferencesKey("app_notify_interval_sec")
        val KEY_APP_TEMP_GRAPH_MIN = intPreferencesKey("app_temp_graph_min")
        val KEY_APP_TEMP_GRAPH_MAX = intPreferencesKey("app_temp_graph_max")
    }

    // Einmalig erkannte dynamische Objektnamen für die gebündelte HTTP-Snapshot-Abfrage.
    // Werden bei Konfigurationsänderung (saveConfig) zurückgesetzt.
    @Volatile private var cachedHeaterKeys: List<String>? = null
    @Volatile private var cachedFanGenericKeys: List<String>? = null
    @Volatile private var cachedHasFan: Boolean = false

    // ── WebSocket (Moonraker async push) ─────────────────────────────────────
    @Volatile private var wsClient: MoonrakerWsClient? = null
    private var wsSnapshotJob: Job? = null
    private var wsConnectedJob: Job? = null

    private val _wsSnapshot  = MutableStateFlow<PrinterSnapshot?>(null)
    private val _wsConnected = MutableStateFlow(false)

    /** Letzter via WebSocket-Push empfangener Drucker-Status (null = noch kein Update). */
    val wsSnapshot:  StateFlow<PrinterSnapshot?> = _wsSnapshot.asStateFlow()

    /** true, solange die WebSocket-Verbindung besteht und Daten empfangen werden. */
    val wsConnected: StateFlow<Boolean>          = _wsConnected.asStateFlow()

    init {
        // Automatisch (re-)verbinden, wenn sich Host/Port/API-Key ändert (z. B.
        // Druckerwechsel). distinctUntilChanged verhindert unnötige Reconnects bei
        // anderen DataStore-Änderungen (Webcams, Favoriten, Ergebnisse …).
        repoScope.launch {
            configFlow.distinctUntilChanged().collect { config -> reinitWebSocket(config) }
        }
    }

    private fun reinitWebSocket(config: KlipperConfig) {
        wsSnapshotJob?.cancel()
        wsConnectedJob?.cancel()
        wsClient?.disconnect()
        wsClient = null
        _wsConnected.value = false

        if (config.host.isBlank()) return

        val client = MoonrakerWsClient(config, repoScope)
        wsClient = client
        wsSnapshotJob  = repoScope.launch { client.snapshot.collect  { _wsSnapshot.value  = it } }
        wsConnectedJob = repoScope.launch { client.connected.collect { _wsConnected.value = it } }
        client.connect()
    }

    // ── Drucker-Profile (Multi-Drucker) ──────────────────────────────────────

    /** Alle gespeicherten Drucker (inkl. Legacy-Migration bei erstem Zugriff). */
    val profilesFlow: Flow<List<PrinterProfile>> = dataStore.data.map { decodeProfiles(it) }

    /** ID des aktuell ausgewählten Druckers (fällt auf den ersten zurück). */
    val selectedPrinterIdFlow: Flow<String> = dataStore.data.map { prefs ->
        selectedProfile(prefs)?.id ?: ""
    }

    val configFlow: Flow<KlipperConfig> = dataStore.data.map { prefs ->
        selectedProfile(prefs)?.toKlipperConfig() ?: KlipperConfig()
    }

    /** Alle Webcams des ausgewählten Druckers (für Multi-Webcam-Wischen). */
    val webcamsFlow: Flow<List<WebcamConfig>> = dataStore.data.map { prefs ->
        selectedProfile(prefs)?.webcams ?: emptyList()
    }

    /** Erste/einzelne Webcam des ausgewählten Druckers (Abwärtskompatibilität). */
    val webcamConfigFlow: Flow<WebcamConfig> = dataStore.data.map { prefs ->
        selectedProfile(prefs)?.webcams?.firstOrNull() ?: WebcamConfig()
    }

    val appConfigFlow: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            maxConcurrentConnections = (prefs[KEY_APP_MAX_CONNECTIONS] ?: 1).coerceIn(1, 8),
            tempIntervalSec = (prefs[KEY_APP_TEMP_INTERVAL] ?: 2).coerceIn(1, 60),
            backgroundIntervalSec = (prefs[KEY_APP_BG_INTERVAL] ?: 4).coerceIn(1, 120),
            powerIntervalSec = (prefs[KEY_APP_POWER_INTERVAL] ?: 15).coerceIn(1, 300),
            notifyIntervalSec = (prefs[KEY_APP_NOTIFY_INTERVAL] ?: 10).coerceIn(5, 600),
            tempGraphMinCelsius = (prefs[KEY_APP_TEMP_GRAPH_MIN] ?: 10).coerceIn(0, 250),
            tempGraphMaxCelsius = (prefs[KEY_APP_TEMP_GRAPH_MAX] ?: 300).coerceIn(50, 500)
        )
    }

    suspend fun saveAppConfig(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_MAX_CONNECTIONS] = config.maxConcurrentConnections.coerceIn(1, 8)
            prefs[KEY_APP_TEMP_INTERVAL] = config.tempIntervalSec.coerceIn(1, 60)
            prefs[KEY_APP_BG_INTERVAL] = config.backgroundIntervalSec.coerceIn(1, 120)
            prefs[KEY_APP_POWER_INTERVAL] = config.powerIntervalSec.coerceIn(1, 300)
            prefs[KEY_APP_NOTIFY_INTERVAL] = config.notifyIntervalSec.coerceIn(5, 600)
            prefs[KEY_APP_TEMP_GRAPH_MIN] = config.tempGraphMinCelsius.coerceIn(0, 250)
            prefs[KEY_APP_TEMP_GRAPH_MAX] = config.tempGraphMaxCelsius.coerceIn(50, 500)
        }
    }

    suspend fun saveConfig(config: KlipperConfig) {
        // Drucker/Verbindung geändert → erkannte Objektnamen neu ermitteln
        cachedHeaterKeys = null
        cachedFanGenericKeys = null
        cachedHasFan = false
        dataStore.edit { prefs ->
            val list = decodeProfiles(prefs).toMutableList()
            val selId = prefs[KEY_SELECTED_PRINTER]
            val idx = list.indexOfFirst { it.id == selId }.let { if (it >= 0) it else if (list.isNotEmpty()) 0 else -1 }
            if (idx >= 0) {
                list[idx] = list[idx].copy(
                    host = config.host, port = config.port, username = config.username,
                    password = config.password, apiKey = config.apiKey
                )
                if (prefs[KEY_SELECTED_PRINTER] == null) prefs[KEY_SELECTED_PRINTER] = list[idx].id
            } else {
                val newP = PrinterProfile(
                    id = java.util.UUID.randomUUID().toString(),
                    host = config.host, port = config.port, username = config.username,
                    password = config.password, apiKey = config.apiKey
                )
                list.add(newP)
                prefs[KEY_SELECTED_PRINTER] = newP.id
            }
            prefs[KEY_PRINTERS] = encodeProfiles(list)
        }
    }

    /** Schreibt die (einzelne) Webcam in den ausgewählten Drucker. */
    suspend fun saveWebcamConfig(config: WebcamConfig) {
        dataStore.edit { prefs ->
            updateSelectedProfile(prefs) { p ->
                val cams = p.webcams.toMutableList()
                if (cams.isEmpty()) cams.add(config) else cams[0] = config
                p.copy(webcams = cams)
            }
        }
    }

    /** Ersetzt die komplette Webcam-Liste des ausgewählten Druckers (Multi-Webcam). */
    suspend fun saveWebcams(webcams: List<WebcamConfig>) {
        dataStore.edit { prefs ->
            updateSelectedProfile(prefs) { it.copy(webcams = webcams) }
        }
    }

    /** Legt einen neuen Drucker an und wählt ihn aus. Gibt dessen ID zurück. */
    suspend fun addPrinter(
        name: String, host: String, port: Int, apiKey: String,
        username: String = "", password: String = ""
    ): String {
        cachedHeaterKeys = null; cachedFanGenericKeys = null; cachedHasFan = false
        val id = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            val list = decodeProfiles(prefs).toMutableList()
            list.add(PrinterProfile(id = id, name = name, host = host, port = port,
                username = username, password = password, apiKey = apiKey))
            prefs[KEY_PRINTERS] = encodeProfiles(list)
            prefs[KEY_SELECTED_PRINTER] = id
        }
        return id
    }

    /** Aktualisiert ein bestehendes Drucker-Profil (Name/Verbindung/Webcams). */
    suspend fun updatePrinter(profile: PrinterProfile) {
        cachedHeaterKeys = null; cachedFanGenericKeys = null; cachedHasFan = false
        dataStore.edit { prefs ->
            val list = decodeProfiles(prefs).toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                list[idx] = profile
                prefs[KEY_PRINTERS] = encodeProfiles(list)
            }
        }
    }

    /** Entfernt einen Drucker; wählt ggf. einen anderen aus. */
    suspend fun deletePrinter(id: String) {
        cachedHeaterKeys = null; cachedFanGenericKeys = null; cachedHasFan = false
        dataStore.edit { prefs ->
            val list = decodeProfiles(prefs).toMutableList()
            list.removeAll { it.id == id }
            prefs[KEY_PRINTERS] = encodeProfiles(list)
            if (prefs[KEY_SELECTED_PRINTER] == id) {
                prefs[KEY_SELECTED_PRINTER] = list.firstOrNull()?.id ?: ""
            }
        }
    }

    /** Wechselt den aktiven Drucker (löst WebSocket-Reconnect aus). */
    suspend fun selectPrinter(id: String) {
        cachedHeaterKeys = null; cachedFanGenericKeys = null; cachedHasFan = false
        dataStore.edit { prefs -> prefs[KEY_SELECTED_PRINTER] = id }
    }

    // ── Profil-(De-)Serialisierung & Helfer ──────────────────────────────────

    /** Liest die Profil-Liste oder migriert die Legacy-Einzelkonfiguration. */
    private fun decodeProfiles(prefs: Preferences): List<PrinterProfile> {
        val raw = prefs[KEY_PRINTERS]
        if (!raw.isNullOrBlank()) {
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { profileFromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        // Migration: vorhandene Legacy-Einzelkonfiguration → ein Profil.
        val legacyHost = prefs[KEY_HOST] ?: ""
        if (legacyHost.isBlank()) return emptyList()
        val legacyWebcam = WebcamConfig(
            name = prefs[KEY_WEBCAM_NAME] ?: "cam 1",
            customUrl = prefs[KEY_WEBCAM_URL] ?: "",
            snapshotUrl = prefs[KEY_WEBCAM_SNAPSHOT_URL] ?: "",
            streamType = prefs[KEY_WEBCAM_STREAM_TYPE]?.let {
                runCatching { WebcamStreamType.valueOf(it) }.getOrNull()
            } ?: WebcamStreamType.MJPEG,
            fps = prefs[KEY_WEBCAM_FPS] ?: 15,
            rotate = prefs[KEY_WEBCAM_ROTATE] ?: 0,
            flipH = prefs[KEY_WEBCAM_FLIP_H] == "true",
            flipV = prefs[KEY_WEBCAM_FLIP_V] == "true",
            stunServer = prefs[KEY_WEBCAM_STUN] ?: "stun:stun.l.google.com:19302",
            iceUsername = prefs[KEY_WEBCAM_ICE_USER] ?: "",
            icePassword = prefs[KEY_WEBCAM_ICE_PASS] ?: "",
            webcamPort = prefs[KEY_WEBCAM_PORT] ?: 0
        )
        return listOf(PrinterProfile(
            id = "legacy",
            name = "Drucker",
            host = legacyHost,
            port = prefs[KEY_PORT] ?: 7125,
            username = prefs[KEY_USERNAME] ?: "",
            password = prefs[KEY_PASSWORD] ?: "",
            apiKey = prefs[KEY_API_KEY] ?: "",
            webcams = if (legacyWebcam.customUrl.isNotBlank() || legacyWebcam.snapshotUrl.isNotBlank())
                listOf(legacyWebcam) else emptyList()
        ))
    }

    /** Das aktuell ausgewählte Profil (oder das erste, oder null). */
    private fun selectedProfile(prefs: Preferences): PrinterProfile? {
        val list = decodeProfiles(prefs)
        val sel = prefs[KEY_SELECTED_PRINTER]
        return list.firstOrNull { it.id == sel } ?: list.firstOrNull()
    }

    /** Wendet eine Transformation auf das ausgewählte Profil an (innerhalb edit{}). */
    private fun updateSelectedProfile(prefs: MutablePreferences, transform: (PrinterProfile) -> PrinterProfile) {
        val list = decodeProfiles(prefs).toMutableList()
        if (list.isEmpty()) return
        val selId = prefs[KEY_SELECTED_PRINTER]
        val idx = list.indexOfFirst { it.id == selId }.let { if (it >= 0) it else 0 }
        list[idx] = transform(list[idx])
        prefs[KEY_PRINTERS] = encodeProfiles(list)
    }

    private fun encodeProfiles(list: List<PrinterProfile>): String =
        JSONArray().apply { list.forEach { put(profileToJson(it)) } }.toString()

    private fun profileToJson(p: PrinterProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("password", p.password)
        put("apiKey", p.apiKey)
        put("webcams", JSONArray().apply { p.webcams.forEach { put(webcamToJson(it)) } })
    }

    private fun profileFromJson(o: JSONObject): PrinterProfile {
        val webcams = mutableListOf<WebcamConfig>()
        o.optJSONArray("webcams")?.let { arr ->
            for (i in 0 until arr.length()) webcams.add(webcamFromJson(arr.getJSONObject(i)))
        }
        return PrinterProfile(
            id = o.optString("id"),
            name = o.optString("name", "Drucker"),
            host = o.optString("host", ""),
            port = o.optInt("port", 7125),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            apiKey = o.optString("apiKey", ""),
            webcams = webcams
        )
    }

    private fun webcamToJson(w: WebcamConfig): JSONObject = JSONObject().apply {
        put("name", w.name)
        put("customUrl", w.customUrl)
        put("snapshotUrl", w.snapshotUrl)
        put("streamType", w.streamType.name)
        put("fps", w.fps)
        put("rotate", w.rotate)
        put("flipH", w.flipH)
        put("flipV", w.flipV)
        put("stunServer", w.stunServer)
        put("iceUsername", w.iceUsername)
        put("icePassword", w.icePassword)
        put("webcamPort", w.webcamPort)
    }

    private fun webcamFromJson(o: JSONObject): WebcamConfig = WebcamConfig(
        name = o.optString("name", "cam 1"),
        customUrl = o.optString("customUrl", ""),
        snapshotUrl = o.optString("snapshotUrl", ""),
        streamType = runCatching { WebcamStreamType.valueOf(o.optString("streamType", "MJPEG")) }
            .getOrDefault(WebcamStreamType.MJPEG),
        fps = o.optInt("fps", 15),
        rotate = o.optInt("rotate", 0),
        flipH = o.optBoolean("flipH", false),
        flipV = o.optBoolean("flipV", false),
        stunServer = o.optString("stunServer", "stun:stun.l.google.com:19302"),
        iceUsername = o.optString("iceUsername", ""),
        icePassword = o.optString("icePassword", ""),
        webcamPort = o.optInt("webcamPort", 0)
    )

    suspend fun saveFavoriteMacros(favorites: List<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_FAVORITE_MACROS] = favorites.joinToString(",")
        }
    }

    suspend fun loadFavoriteMacros(): List<String> {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_FAVORITE_MACROS] ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    suspend fun getTemperatures(): Result<List<TemperatureInfo>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getTemperatures() }
    }

    suspend fun getPrinterStatus(): Result<PrinterStatusInfo> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getPrinterStatus() }
    }

    /**
     * Holt alle für die Statusanzeige nötigen Werte mit EINER einzigen
     * printer.objects.query-Anfrage. Die dynamischen Objektnamen werden nur beim
     * ersten Aufruf (oder nach Konfigurationswechsel) per /objects/list ermittelt
     * und danach zwischengespeichert → im Normalfall genau eine Anfrage pro Abruf.
     */
    suspend fun getPrinterSnapshot(): Result<PrinterSnapshot> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching {
            val client = KlipperClient(config)
            if (cachedHeaterKeys == null) {
                val objs = client.getObjectList()
                if (objs.isNotEmpty()) {
                    cachedHeaterKeys = objs.filter {
                        it.startsWith("extruder") || it.startsWith("heater_bed") ||
                            it.startsWith("heater_generic") || it.startsWith("temperature_sensor")
                    }
                    cachedFanGenericKeys = objs.filter { it.startsWith("fan_generic ") }
                    cachedHasFan = objs.contains("fan")
                }
            }
            val heaterKeys = cachedHeaterKeys ?: error("Objektliste nicht verfügbar")
            client.getPrinterSnapshot(heaterKeys, cachedFanGenericKeys ?: emptyList(), cachedHasFan)
        }
    }

    suspend fun setTemperature(heaterName: String, target: Float): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setTemperature(heaterName, target)
    }

    suspend fun jogMove(axis: String, distance: Float): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).jogMove(axis, distance)
    }

    suspend fun homeAxes(axes: String = ""): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).homeAxes(axes)
    }

    suspend fun getKlippyState(): String {
        val config = configFlow.first()
        if (config.host.isBlank()) return "offline"
        return KlipperClient(config).getKlippyState()
    }

    suspend fun extrude(amount: Float): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).extrude(amount)
    }

    suspend fun sendGcode(gcode: String): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).sendGcode(gcode)
    }

    suspend fun getFiles(): Result<List<PrintFile>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getFiles() }
    }

    suspend fun startPrint(filename: String): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).startPrint(filename)
    }

    // ── Zeitraffer ────────────────────────────────────────────────────────────
    suspend fun getTimelapses(): Result<List<Timelapse>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getTimelapses() }
    }

    suspend fun deleteTimelapse(path: String): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).deleteTimelapse(path)
    }

    suspend fun streamTimelapse(path: String, out: java.io.OutputStream): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).streamTimelapse(path, out)
    }

    // URL + Auth-Header zum direkten Streamen im Player
    suspend fun timelapsePlayback(path: String): Pair<String, Map<String, String>>? {
        val config = configFlow.first()
        if (config.host.isBlank()) return null
        val client = KlipperClient(config)
        return client.timelapseUrl(path) to client.authHeaders()
    }

    suspend fun getMacros(): Result<List<String>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getMacros() }
    }

    suspend fun getPosition(): Result<KlipperPosition> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getPosition() }
    }

    suspend fun getPowerDevices(): Result<List<PowerDevice>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getPowerDevices() }
    }

    suspend fun togglePowerDevice(device: String, on: Boolean): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).togglePowerDevice(device, on)
    }

    suspend fun getGcodeFileContent(filename: String): Result<String> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getGcodeFileContent(filename) }
    }

    suspend fun <T> streamGcodeFile(filename: String, block: (java.io.BufferedReader) -> T): Result<T> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).withGcodeStream(filename, block) }
    }

    suspend fun getBedSize(): Result<Pair<Float, Float>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.success(Pair(235f, 235f))
        return runCatching { KlipperClient(config).getBedSize() }
    }

    suspend fun getGcodeMetadata(filename: String): Result<GcodeMetadata> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getGcodeMetadata(filename) }
    }

    suspend fun getPrintProgress(): Result<Float?> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.success(null)
        return runCatching { KlipperClient(config).getPrintProgress() }
    }

    suspend fun fetchThumbnail(url: String): Bitmap? {
        val config = configFlow.first()
        if (config.host.isBlank()) return null
        return try { KlipperClient(config).fetchImageBitmap(url) } catch (e: Exception) { null }
    }

    suspend fun listConfigFiles(): Result<List<ConfigFile>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).listConfigFiles() }
    }

    suspend fun readConfigFile(path: String): Result<String> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).readConfigFile(path) }
    }

    suspend fun saveConfigFile(path: String, content: String): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).saveConfigFile(path, content)
    }

    suspend fun firmwareRestart(): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).firmwareRestart()
    }

    suspend fun getGcodeStore(): Result<List<ConsoleEntry>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getGcodeStore() }
    }

    suspend fun restartHost(): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).restartHost()
    }

    suspend fun detectCrownestCams(): Result<List<CrownestCam>> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).detectCrownestCams() }
    }

    suspend fun pausePrint(): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).pausePrint()
    }

    suspend fun resumePrint(): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).resumePrint()
    }

    suspend fun cancelPrint(): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).cancelPrint()
    }

    suspend fun getPrintSpeed(): Result<Float?> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.success(null)
        return runCatching { KlipperClient(config).getPrintSpeed() }
    }

    suspend fun getPrintStats(): Result<PrintStats?> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.success(null)
        return runCatching { KlipperClient(config).getPrintStats() }
    }

    suspend fun getDriverSettings(): Result<DriverSettings> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).getDriverSettings() }
    }

    suspend fun setDriverCurrent(stepperName: String, runCurrent: Float, holdCurrent: Float?): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setDriverCurrent(stepperName, runCurrent, holdCurrent)
    }

    suspend fun getTuningData(): Result<TuningData> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.success(TuningData())
        return runCatching { KlipperClient(config).getTuningData() }
    }

    suspend fun setSpeedFactor(percent: Int): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setSpeedFactor(percent)
    }

    suspend fun setExtrudeFactor(percent: Int): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setExtrudeFactor(percent)
    }

    suspend fun setPartCoolingFan(percent: Int): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setPartCoolingFan(percent)
    }

    suspend fun setGenericFanSpeed(fanKeyName: String, percent: Int): Result<Unit> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return KlipperClient(config).setGenericFanSpeed(fanKeyName, percent)
    }

    suspend fun downloadSnapshot(snapshotUrl: String): Result<ByteArray> {
        val config = configFlow.first()
        if (config.host.isBlank()) return Result.failure(IllegalStateException("Kein Host konfiguriert"))
        return runCatching { KlipperClient(config).downloadSnapshot(snapshotUrl) }
    }

    // Power-Gerät-Cache: Format "name:status|name:status"
    suspend fun saveCachedPowerDevices(devices: List<PowerDevice>) {
        val encoded = devices.joinToString("|") { "${it.name}:${it.status}" }
        dataStore.edit { prefs -> prefs[KEY_CACHED_POWER_DEVICES] = encoded }
    }

    suspend fun loadCachedPowerDevices(): List<PowerDevice> {
        val encoded = dataStore.data.first()[KEY_CACHED_POWER_DEVICES] ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return encoded.split("|").mapNotNull { part ->
            val idx = part.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            PowerDevice(name = part.substring(0, idx), status = part.substring(idx + 1))
        }
    }

    // Druckergebnisse je Dateiname (true=erfolg, false=abgebrochen), als JSON persistiert.
    suspend fun savePrintResults(results: Map<String, Boolean>) {
        val obj = JSONObject()
        results.forEach { (k, v) -> obj.put(k, v) }
        dataStore.edit { prefs -> prefs[KEY_PRINT_RESULTS] = obj.toString() }
    }

    suspend fun loadPrintResults(): Map<String, Boolean> {
        val encoded = dataStore.data.first()[KEY_PRINT_RESULTS] ?: return emptyMap()
        if (encoded.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(encoded)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optBoolean(key)) }
            }
        }.getOrDefault(emptyMap())
    }
}
