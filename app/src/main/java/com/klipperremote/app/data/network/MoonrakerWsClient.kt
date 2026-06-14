package com.klipperremote.app.data.network

import com.klipperremote.app.data.model.FanInfo
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.PrinterSnapshot
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.TuningData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchroner Moonraker-WebSocket-Client (JSON-RPC 2.0).
 *
 * Verbindet sich mit dem Moonraker-WebSocket-Endpunkt und abonniert via
 * `printer.objects.subscribe` alle relevanten Druckerobjekte. Moonraker pusht
 * bei jeder Änderung ein Delta, das hier mit dem letzten Vollbild zusammengeführt
 * und direkt als [PrinterSnapshot] in [snapshot] emittiert wird – ohne weiteren
 * HTTP-Request.
 *
 * Gleichzeitig können beliebige JSON-RPC-Anfragen asynchron gesendet werden:
 * [sendRpc] schickt die Anfrage und wartet nicht-blockierend per ID-Matching
 * auf die passende Antwort (mehrere Anfragen können gleichzeitig in-flight sein).
 *
 * Reconnect-Logik: bei Verbindungsabbruch wird automatisch nach [RECONNECT_DELAY_MS]
 * neu verbunden.
 */
class MoonrakerWsClient(
    private val config: KlipperConfig,
    private val scope: CoroutineScope
) {
    companion object {
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)      // kein Lese-Timeout für dauerhafte WS-Verbindung
        .pingInterval(30, TimeUnit.SECONDS)    // Keepalive
        .build()

    // Eindeutige Anfrage-IDs; Antworten werden per ID zugeordnet.
    private val idCounter = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    // Zusammengeführter Drucker-Status (initiales Subscribe-Vollbild + Delta-Merges).
    private val statusMutex = Mutex()
    private var mergedStatus = JSONObject()

    // Einmalig erkannte dynamische Objektnamen (Heizer, Lüfter).
    private var heaterKeys: List<String> = emptyList()
    private var fanGenericKeys: List<String> = emptyList()
    private var hasFan = false

    /** Letzter geparster Drucker-Status. null solange noch keine Daten empfangen. */
    private val _snapshot = MutableStateFlow<PrinterSnapshot?>(null)
    val snapshot: StateFlow<PrinterSnapshot?> = _snapshot.asStateFlow()

    /** true, solange die WebSocket-Verbindung besteht und die Subscribe-Antwort verarbeitet wurde. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var reconnectJob: Job? = null

    // ── WebSocket-Listener ────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch { onConnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connected.value = false
            failAllPending()
            if (!closed) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            failAllPending()
            if (!closed) scheduleReconnect()
        }
    }

    // ── Öffentliche API ───────────────────────────────────────────────────────

    /** Baut die WebSocket-Verbindung auf. Konfigurationspflicht: [KlipperConfig.host] darf nicht leer sein. */
    fun connect() {
        if (config.host.isBlank()) return
        closed = false
        val urlBuilder = StringBuilder("ws://")
            .append(config.host).append(":").append(config.port)
            .append("/websocket")
        if (config.apiKey.isNotBlank()) urlBuilder.append("?token=").append(config.apiKey)
        ws = okHttp.newWebSocket(Request.Builder().url(urlBuilder.toString()).build(), listener)
    }

    /** Trennt die Verbindung dauerhaft (kein automatischer Reconnect). */
    fun disconnect() {
        closed = true
        reconnectJob?.cancel()
        ws?.close(1000, "Verbindung getrennt")
        ws = null
        _connected.value = false
        failAllPending()
    }

    /**
     * Sendet eine JSON-RPC-2.0-Anfrage und wartet asynchron auf die Antwort.
     * Mehrere Aufrufe können gleichzeitig ausstehend sein – die Zuordnung
     * erfolgt über die eindeutige Request-ID im JSON-Körper.
     *
     * @throws IllegalStateException wenn die WebSocket-Verbindung nicht besteht.
     */
    suspend fun sendRpc(method: String, params: JSONObject? = null): JSONObject {
        val id = idCounter.getAndIncrement()
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
            put("id", id)
        }
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val sent = ws?.send(request.toString()) ?: false
        if (!sent) {
            pending.remove(id)
            deferred.completeExceptionally(IllegalStateException("WebSocket nicht verbunden"))
        }
        return deferred.await()
    }

    // ── Interne Logik ─────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || closed) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!closed) connect()
        }
    }

    private fun failAllPending() {
        val ex = IllegalStateException("WebSocket-Verbindung unterbrochen")
        pending.values.forEach { it.completeExceptionally(ex) }
        pending.clear()
    }

    /** Nach erfolgreicher Verbindung: Objektliste holen + Subscribe absenden. */
    private suspend fun onConnected() {
        try {
            // Objektliste einmalig abfragen (dynamische Heizer-/Lüfternamen erkennen).
            if (heaterKeys.isEmpty()) {
                val listResult = sendRpc("printer.objects.list")
                val arr = listResult.optJSONObject("result")?.optJSONArray("objects")
                val objs = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
                heaterKeys = objs.filter {
                    it.startsWith("extruder") || it.startsWith("heater_bed") ||
                        it.startsWith("heater_generic") || it.startsWith("temperature_sensor")
                }
                fanGenericKeys = objs.filter { it.startsWith("fan_generic ") }
                hasFan = objs.contains("fan")
            }
            subscribeObjects()
        } catch (_: Exception) {
            // Verbindung schlägt fehl → Reconnect übernimmt
        }
    }

    /** Abonniert alle relevanten Klipper-Objekte. Speichert den initialen Vollbild-Status. */
    private suspend fun subscribeObjects() {
        val objects = JSONObject()
        listOf("print_stats", "toolhead", "virtual_sdcard", "gcode_move", "motion_report")
            .forEach { objects.put(it, JSONObject.NULL) }
        if (hasFan) objects.put("fan", JSONObject.NULL)
        heaterKeys.forEach { objects.put(it, JSONObject.NULL) }
        fanGenericKeys.forEach { objects.put(it, JSONObject.NULL) }

        val result = sendRpc("printer.objects.subscribe", JSONObject().put("objects", objects))
        val status = result.optJSONObject("result")?.optJSONObject("status") ?: return

        val snap = statusMutex.withLock {
            mergedStatus = status
            parseSnapshot()
        }
        _snapshot.value = snap
        _connected.value = true
    }

    /** Verarbeitet eine eingehende WebSocket-Nachricht. */
    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            // Antwort auf eine unserer Anfragen (hat "id")
            val hasId = json.has("id") && !json.isNull("id")
            if (hasId) {
                pending.remove(json.getInt("id"))?.complete(json)
                return
            }
            // Push-Benachrichtigung von Moonraker
            if (json.optString("method") == "notify_status_update") {
                val delta = json.optJSONArray("params")?.optJSONObject(0) ?: return
                val snap = statusMutex.withLock {
                    mergeInto(mergedStatus, delta)
                    parseSnapshot()
                }
                _snapshot.value = snap
            }
        } catch (_: Exception) { }
    }

    /**
     * Führt die Felder aus [delta] rekursiv in [target] zusammen.
     * Moonraker sendet bei `notify_status_update` nur geänderte Felder;
     * nicht erwähnte Felder bleiben im zusammengeführten Status unverändert.
     */
    private fun mergeInto(target: JSONObject, delta: JSONObject) {
        delta.keys().forEach { key ->
            val existing = target.optJSONObject(key)
            val incoming = delta.optJSONObject(key)
            if (existing != null && incoming != null) {
                mergeInto(existing, incoming)
            } else {
                target.put(key, delta.get(key))
            }
        }
    }

    /**
     * Parst den zusammengeführten Drucker-Status in ein [PrinterSnapshot]-Objekt.
     * Muss unter [statusMutex] aufgerufen werden.
     */
    private fun parseSnapshot(): PrinterSnapshot {
        val status = mergedStatus

        // Temperaturen (Heizer + Temperatursensoren)
        val temps = heaterKeys.mapNotNull { key ->
            val obj = status.optJSONObject(key) ?: return@mapNotNull null
            val current = obj.optDouble("temperature", -1.0).toFloat()
            if (current < 0) return@mapNotNull null
            TemperatureInfo(
                name = key,
                current = current,
                target = obj.optDouble("target", 0.0).toFloat(),
                power = obj.optDouble("power", 0.0).toFloat()
            )
        }

        // Druckerzustand
        val ps = status.optJSONObject("print_stats")
        val rawState = ps?.optString("state", "") ?: ""
        val mapped = when (rawState) {
            "printing" -> "printing"
            "paused"   -> "paused"
            "error"    -> "error"
            else       -> "ready"
        }

        // Werkzeugkopf-Position
        val toolhead = status.optJSONObject("toolhead")
        val posArr = toolhead?.optJSONArray("position")
        val position = if (posArr != null) {
            KlipperPosition(
                x = posArr.optDouble(0, Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                y = posArr.optDouble(1, Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                z = posArr.optDouble(2, Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            )
        } else KlipperPosition()

        val gcodeMove = status.optJSONObject("gcode_move")
        val motionRep = status.optJSONObject("motion_report")
        val vSdcard   = status.optJSONObject("virtual_sdcard")

        // Fortschritt + Druckstatistik
        val printing = rawState == "printing" || rawState == "paused"
        var progress: Float? = null
        var speedMmS: Float? = null
        var stats: PrintStats? = null
        if (ps != null) {
            if (printing) {
                // Fortschritt: byte-basiert aus virtual_sdcard (kein async Metadaten-Aufruf nötig)
                progress = vSdcard?.optDouble("progress", 0.0)?.toFloat()?.coerceIn(0f, 1f)
                val speedMmMin = gcodeMove?.optDouble("speed", -1.0) ?: -1.0
                speedMmS = if (speedMmMin > 0) (speedMmMin / 60.0).toFloat() else null
            }

            val info = ps.optJSONObject("info")
            val currentLayer = info?.optInt("current_layer", -1)?.takeIf { it > 0 }
            val totalLayers  = info?.optInt("total_layer",   -1)?.takeIf { it > 0 }

            val liveExtVel = motionRep?.optDouble("live_extruder_velocity", 0.0)?.toFloat() ?: 0f
            val volumetricFlow = if (printing && liveExtVel > 0.001f) {
                val r = 1.75f / 2f
                liveExtVel * Math.PI.toFloat() * r * r
            } else null

            // Druckstatistik immer parsen, damit nach Abschluss/Abbruch die finalen Werte
            // (Filamentverbrauch, Druckdauer, Statusmeldung) für die Benachrichtigung bereitstehen.
            stats = PrintStats(
                filename      = ps.optString("filename", ""),
                printDuration = ps.optDouble("print_duration", 0.0).toFloat(),
                progress      = progress ?: 0f,
                filamentUsed  = ps.optDouble("filament_used", 0.0).toFloat(),
                currentLayer  = currentLayer,
                totalLayers   = totalLayers,
                maxVelocity   = toolhead?.optDouble("max_velocity", 0.0)?.toFloat()?.takeIf { it > 0 },
                volumetricFlow = volumetricFlow,
                speedFactor   = gcodeMove?.optDouble("speed_factor", 1.0)?.toFloat() ?: 1f,
                extrudeFactor = gcodeMove?.optDouble("extrude_factor", 1.0)?.toFloat() ?: 1f,
                message       = ps.optString("message", "")
            )
        }

        // Tuning-Werte (Geschwindigkeit, Extrusion, Lüfter)
        val speedFactor   = ((gcodeMove?.optDouble("speed_factor",   1.0) ?: 1.0) * 100).toInt()
        val extrudeFactor = ((gcodeMove?.optDouble("extrude_factor", 1.0) ?: 1.0) * 100).toInt()
        val partFan       = ((status.optJSONObject("fan")?.optDouble("speed", 0.0) ?: 0.0) * 100).toInt()
        val fans = fanGenericKeys.mapNotNull { key ->
            val speed = status.optJSONObject(key)?.optDouble("speed", 0.0) ?: return@mapNotNull null
            val keyName = key.removePrefix("fan_generic ")
            val displayName = keyName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            FanInfo(keyName = keyName, displayName = displayName, speedPercent = (speed * 100).toInt())
        }

        return PrinterSnapshot(
            temperatures      = temps.sortedBy { it.name },
            printerState      = mapped,
            rawState          = rawState,
            position          = position,
            printProgress     = progress,
            printSpeedMmPerSec = speedMmS,
            printStats        = stats,
            tuningData        = TuningData(
                speedFactor     = speedFactor,
                extrudeFactor   = extrudeFactor,
                partCoolingFan  = partFan,
                fans            = fans
            )
        )
    }
}
