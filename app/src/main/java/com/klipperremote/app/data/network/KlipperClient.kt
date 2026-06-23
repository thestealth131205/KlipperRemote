package com.klipperremote.app.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.klipperremote.app.data.model.FanInfo
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.ConfigFile
import com.klipperremote.app.data.model.ConsoleEntry
import com.klipperremote.app.data.model.CrownestCam
import com.klipperremote.app.data.model.AxisDriver
import com.klipperremote.app.data.model.DriverSettings
import com.klipperremote.app.data.model.DriverEdit
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrintStats
import com.klipperremote.app.data.model.PrinterSnapshot
import com.klipperremote.app.data.model.PrinterStatusInfo
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.Timelapse
import com.klipperremote.app.data.model.TuningData
import okhttp3.MultipartBody
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class KlipperClient(private val config: KlipperConfig) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (config.username.isNotBlank() && config.password.isNotBlank()) {
                addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .build()
                    chain.proceed(req)
                }
            }
            if (config.apiKey.isNotBlank()) {
                addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("X-Api-Key", config.apiKey)
                        .build()
                    chain.proceed(req)
                }
            }
        }
        .build()

    // Moonrakers /printer/gcode/script blockiert bis der G-Code fertig ausgeführt ist.
    // Langläufer wie G28 (Homing), PROBE_CALIBRATE oder Bed-Mesh dauern länger als der
    // normale readTimeout → eigener Client mit großzügigem Read-Timeout.
    private val gcodeClient: OkHttpClient = client.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() {
            // Host bereinigen: Protokoll-Präfix und ggf. eingetippten Port entfernen
            val rawHost = config.host
                .removePrefix("https://")
                .removePrefix("http://")
                .trim()
            // Falls der User bereits "host:port" eingegeben hat, Port nicht doppeln
            return if (rawHost.contains(':')) {
                "http://$rawHost"
            } else {
                "http://$rawHost:${config.port}"
            }
        }

    // Gibt alle verfügbaren Temperaturen zurück
    suspend fun getTemperatures(): List<TemperatureInfo> = withContext(Dispatchers.IO) {
        val objectsUrl = "$baseUrl/printer/objects/list"
        val objectsReq = Request.Builder().url(objectsUrl).get().build()
        val objectsResp = client.newCall(objectsReq).execute()
        val objectsBody = objectsResp.body?.string() ?: return@withContext emptyList()

        val json = JSONObject(objectsBody)
        val objects = json.optJSONObject("result")?.optJSONArray("objects") ?: return@withContext emptyList()

        val heaterKeys = mutableListOf<String>()
        for (i in 0 until objects.length()) {
            val obj = objects.getString(i)
            if (obj.startsWith("extruder") || obj.startsWith("heater_bed") ||
                obj.startsWith("heater_generic") || obj.startsWith("temperature_sensor")) {
                heaterKeys.add(obj)
            }
        }

        if (heaterKeys.isEmpty()) return@withContext emptyList()

        val queryParams = heaterKeys.joinToString("&") { "$it=" }
        val tempUrl = "$baseUrl/printer/objects/query?$queryParams"
        val tempReq = Request.Builder().url(tempUrl).get().build()
        val tempResp = client.newCall(tempReq).execute()
        val tempBody = tempResp.body?.string() ?: return@withContext emptyList()

        val tempJson = JSONObject(tempBody)
        val status = tempJson.optJSONObject("result")?.optJSONObject("status") ?: return@withContext emptyList()

        val result = mutableListOf<TemperatureInfo>()
        for (key in status.keys()) {
            val obj = status.optJSONObject(key) ?: continue
            val current = obj.optDouble("temperature", -1.0).toFloat()
            if (current < 0) continue
            result.add(
                TemperatureInfo(
                    name = key,
                    current = current,
                    target = obj.optDouble("target", 0.0).toFloat(),
                    power = obj.optDouble("power", 0.0).toFloat()
                )
            )
        }
        result.sortedBy { it.name }
    }

    // Druckerstatus abfragen
    // Liefert den echten Druckzustand aus print_stats.state (printing/paused/...),
    // NICHT den Klipper-Host-Status aus /printer/info (der nur ready/startup/shutdown kennt).
    suspend fun getPrinterStatus(): PrinterStatusInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/printer/objects/query?print_stats=state,message")
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext PrinterStatusInfo()
            val status = JSONObject(body)
                .optJSONObject("result")?.optJSONObject("status")
                ?: return@withContext PrinterStatusInfo()
            val ps = status.optJSONObject("print_stats") ?: return@withContext PrinterStatusInfo()
            val rawState = ps.optString("state", "")
            // print_stats.state: standby | printing | paused | complete | cancelled | error
            // standby/complete/cancelled werden als Leerlauf ("ready") behandelt.
            val mapped = when (rawState) {
                "printing" -> "printing"
                "paused"   -> "paused"
                "error"    -> "error"
                "standby", "complete", "cancelled", "" -> "ready"
                else -> "ready"
            }
            PrinterStatusInfo(
                state = mapped,
                message = ps.optString("message", "")
            )
        } catch (e: Exception) {
            PrinterStatusInfo("offline")
        }
    }

    // Klipper-Host-Status aus /printer/info: "ready" | "startup" | "shutdown" | "error".
    // Nach dem Einschalten des Druckers braucht Klipper einige Sekunden bis "ready" –
    // erst dann werden G-Code-Befehle wie G28 oder Z_TILT_ADJUST akzeptiert.
    suspend fun getKlippyState(): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/info").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext "offline"
            JSONObject(body).optJSONObject("result")?.optString("state", "offline") ?: "offline"
        } catch (e: Exception) {
            "offline"
        }
    }

    // Temperatur setzen via GCode
    suspend fun setTemperature(heaterName: String, targetTemp: Float): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val gcode = when {
                heaterName == "extruder" -> "SET_HEATER_TEMPERATURE HEATER=extruder TARGET=$targetTemp"
                heaterName.startsWith("extruder") -> "SET_HEATER_TEMPERATURE HEATER=$heaterName TARGET=$targetTemp"
                heaterName == "heater_bed" -> "SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=$targetTemp"
                heaterName.startsWith("heater_generic ") -> {
                    val genericName = heaterName.removePrefix("heater_generic ")
                    "SET_HEATER_TEMPERATURE HEATER=$genericName TARGET=$targetTemp"
                }
                else -> "SET_HEATER_TEMPERATURE HEATER=$heaterName TARGET=$targetTemp"
            }
            sendGcodeInternal(gcode)
        }
    }

    // Achse bewegen (relativ)
    suspend fun jogMove(axis: String, distance: Float): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val speed = if (axis.uppercase() == "Z") 600 else 3000
            val gcode = "G91\nG1 ${axis.uppercase()}$distance F$speed\nG90"
            sendGcodeInternal(gcode)
        }
    }

    // Achsen homen
    suspend fun homeAxes(axes: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val gcode = if (axes.isBlank()) "G28" else "G28 ${axes.uppercase()}"
            sendGcodeInternal(gcode)
        }
    }

    // Extrudieren / Retract
    suspend fun extrude(amount: Float): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val gcode = "M83\nG1 E$amount F300"
            sendGcodeInternal(gcode)
        }
    }

    // GCode direkt senden
    suspend fun sendGcode(gcode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { sendGcodeInternal(gcode) }
    }

    private fun sendGcodeInternal(gcode: String) {
        val body = JSONObject().put("script", gcode).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/printer/gcode/script")
            .post(body)
            .build()
        val resp = gcodeClient.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
    }

    // Druckdateien auflisten
    suspend fun getFiles(): List<PrintFile> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/server/files/list?root=gcodes").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val result = json.optJSONArray("result") ?: return@withContext emptyList()
            val files = mutableListOf<PrintFile>()
            for (i in 0 until result.length()) {
                val obj = result.optJSONObject(i) ?: continue
                val name = obj.optString("path", obj.optString("filename", ""))
                if (name.isBlank()) continue
                files.add(
                    PrintFile(
                        filename = name,
                        modified = (obj.optDouble("modified", 0.0) * 1000).toLong(),
                        size = obj.optLong("size", 0L)
                    )
                )
            }
            files.sortedByDescending { it.modified }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Druck starten
    suspend fun startPrint(filename: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("filename", filename).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/printer/print/start")
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Verfügbare Makros laden
    suspend fun getMacros(): List<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/objects/list").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val objects = json.optJSONObject("result")?.optJSONArray("objects") ?: return@withContext emptyList()
            val macros = mutableListOf<String>()
            for (i in 0 until objects.length()) {
                val obj = objects.getString(i)
                if (obj.startsWith("gcode_macro ")) {
                    val name = obj.removePrefix("gcode_macro ")
                    // Interne Klipper-Makros (Kleinbuchstaben oder _) überspringen
                    if (!name.startsWith("_")) macros.add(name)
                }
            }
            macros.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Klipper-Firmware neu starten (Moonraker: POST /printer/restart)
    suspend fun firmwareRestart(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/printer/restart")
                .post("".toRequestBody(null))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Konsolen-Verlauf (Moonraker gcode_store) abfragen
    suspend fun getGcodeStore(count: Int = 100): List<ConsoleEntry> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/server/gcode_store?count=$count")
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val arr = JSONObject(body)
                .optJSONObject("result")?.optJSONArray("gcode_store")
                ?: return@withContext emptyList()
            val entries = mutableListOf<ConsoleEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val msg = obj.optString("message", "")
                if (msg.isBlank()) continue
                entries.add(
                    ConsoleEntry(
                        message = msg,
                        time = obj.optDouble("time", 0.0),
                        type = obj.optString("type", "response")
                    )
                )
            }
            entries
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Klipper-Host neu starten (Moonraker: POST /machine/reboot)
    suspend fun restartHost(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/machine/reboot")
                .post("".toRequestBody(null))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Power-Geräte (Moonraker device_power) abfragen
    suspend fun getPowerDevices(): List<PowerDevice> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/machine/device_power/devices").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val devices = json.optJSONObject("result")?.optJSONArray("devices")
                ?: return@withContext emptyList()
            val list = mutableListOf<PowerDevice>()
            for (i in 0 until devices.length()) {
                val d = devices.optJSONObject(i) ?: continue
                list.add(PowerDevice(name = d.optString("device", ""), status = d.optString("status", "off")))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // G-Code-Datei herunterladen (Rohinhalt)
    suspend fun getGcodeFileContent(filename: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url("$baseUrl/server/files/gcodes/$encoded").get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.string() ?: ""
    }

    /** Streamt die GCode-Datei zeilenweise – der volle Dateiinhalt wird nie vollständig
     *  in den Speicher gepuffert. Das verhindert, dass Moonraker auf dem RPi die gesamte
     *  Datei auf einmal in den RAM lädt und den Pi dadurch zum Einfrieren bringt. */
    suspend fun <T> withGcodeStream(filename: String, block: (java.io.BufferedReader) -> T): T = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url("$baseUrl/server/files/gcodes/$encoded").get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body!!.byteStream().bufferedReader().use { block(it) }
    }

    // G-Code Metadaten (Vorschaubild + Druckzeit) via Moonraker laden
    suspend fun getGcodeMetadata(filename: String): GcodeMetadata = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            val req = Request.Builder().url("$baseUrl/server/files/metadata?filename=$encoded").get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext GcodeMetadata()
            val body = resp.body?.string() ?: return@withContext GcodeMetadata()
            val result = JSONObject(body).optJSONObject("result") ?: return@withContext GcodeMetadata()
            val estimatedTime = result.optInt("estimated_time", 0).takeIf { it > 0 }
            val thumbnails = result.optJSONArray("thumbnails")
            var thumbnailUrl: String? = null
            if (thumbnails != null) {
                var maxSize = 0
                var relPath: String? = null
                for (i in 0 until thumbnails.length()) {
                    val t = thumbnails.optJSONObject(i) ?: continue
                    val sz = t.optInt("size", 0)
                    if (sz > maxSize) {
                        maxSize = sz
                        relPath = t.optString("relative_path").takeIf { it.isNotBlank() }
                    }
                }
                if (relPath != null) {
                    val encodedRelPath = relPath.split("/").joinToString("/") { seg ->
                        URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                    }
                    thumbnailUrl = "$baseUrl/server/files/gcodes/$encodedRelPath"
                }
            }
            GcodeMetadata(thumbnailUrl = thumbnailUrl, estimatedTime = estimatedTime)
        } catch (e: Exception) {
            GcodeMetadata()
        }
    }

    // Bild-Bytes von URL laden (für Vorschaubilder)
    suspend fun fetchImageBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            val bytes = resp.body?.bytes() ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // Druckbettgröße aus Klipper-Konfiguration lesen
    suspend fun getBedSize(): Pair<Float, Float> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/objects/query?configfile=").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext Pair(235f, 235f)
            val json = JSONObject(body)
            val settings = json.optJSONObject("result")
                ?.optJSONObject("status")
                ?.optJSONObject("configfile")
                ?.optJSONObject("settings")
            val xMax = settings?.optJSONObject("stepper_x")?.optDouble("position_max", 235.0)?.toFloat() ?: 235f
            val yMax = settings?.optJSONObject("stepper_y")?.optDouble("position_max", 235.0)?.toFloat() ?: 235f
            Pair(xMax, yMax)
        } catch (e: Exception) {
            Pair(235f, 235f)
        }
    }

    // Power-Gerät ein-/ausschalten
    suspend fun togglePowerDevice(device: String, on: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val action = if (on) "on" else "off"
            val req = Request.Builder()
                .url("$baseUrl/machine/device_power/device?device=$device&action=$action")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Slicer-Restzeit-Cache (filename → estimated_time in Sekunden), 1× pro Druck geladen
    @Volatile private var estimateCache: Pair<String, Int?>? = null

    // Slicer-Gesamtzeit für die aktuell gedruckte Datei (gecacht, null = nicht verfügbar)
    private suspend fun estimatedTimeFor(filename: String): Int? {
        if (filename.isBlank()) return null
        estimateCache?.let { if (it.first == filename) return it.second }
        val est = getGcodeMetadata(filename).estimatedTime
        estimateCache = filename to est
        return est
    }

    // Fortschritt slicer-zeit-basiert (wie Mainsail/Fluidd): print_duration / estimated_time.
    // Fallback auf byte-basierten virtual_sdcard.progress, wenn keine Slicer-Schätzung existiert.
    private suspend fun computeProgress(printDuration: Double, filename: String, fileProgress: Float): Float {
        val est = estimatedTimeFor(filename)
        return if (est != null && est > 0) {
            (printDuration / est).toFloat().coerceIn(0f, 1f)
        } else {
            fileProgress.coerceIn(0f, 1f)
        }
    }

    // Druckfortschritt abfragen (0.0–1.0, null = kein aktiver Druck)
    suspend fun getPrintProgress(): Float? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/printer/objects/query?virtual_sdcard=&print_stats=")
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val status = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONObject("status") ?: return@withContext null
            val ps = status.optJSONObject("print_stats") ?: return@withContext null
            val state = ps.optString("state", "")
            if (state != "printing" && state != "paused") return@withContext null
            val fileProgress = status.optJSONObject("virtual_sdcard")
                ?.optDouble("progress", -1.0)?.takeIf { it >= 0.0 }?.toFloat() ?: 0f
            computeProgress(
                printDuration = ps.optDouble("print_duration", 0.0),
                filename = ps.optString("filename", ""),
                fileProgress = fileProgress
            )
        } catch (e: Exception) {
            null
        }
    }

    // Druckpause
    suspend fun pausePrint(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/printer/print/pause")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Druck fortsetzen
    suspend fun resumePrint(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/printer/print/resume")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Druck abbrechen
    suspend fun cancelPrint(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/printer/print/cancel")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Aktuelle Druckgeschwindigkeit aus gcode_move.speed (mm/min → mm/s)
    suspend fun getPrintSpeed(): Float? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/printer/objects/query?gcode_move=speed")
                .get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val speedMmMin = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONObject("status")
                ?.optJSONObject("gcode_move")
                ?.optDouble("speed", -1.0)
                ?.takeIf { it > 0 } ?: return@withContext null
            (speedMmMin / 60.0).toFloat()
        } catch (e: Exception) {
            null
        }
    }

    // Webcam-Snapshot herunterladen
    suspend fun downloadSnapshot(snapshotUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(snapshotUrl).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.bytes() ?: error("Leerer Snapshot")
    }

    // ── Zeitraffer (moonraker-timelapse Plugin) ───────────────────────────────
    // Fertig gerenderte Videos liegen im virtuellen Root "timelapse". Es wird nur
    // bei Bedarf (Öffnen des Browsers) genau EINE Listen-Anfrage gestellt – kein
    // Polling –, um den Klipper-Host zu schonen.
    suspend fun getTimelapses(): List<Timelapse> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/server/files/list?root=timelapse").get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val result = json.optJSONArray("result") ?: return@withContext emptyList()
            val items = mutableListOf<Timelapse>()
            for (i in 0 until result.length()) {
                val obj = result.optJSONObject(i) ?: continue
                val path = obj.optString("path", obj.optString("filename", ""))
                if (path.isBlank()) continue
                // Nur Video-Dateien anzeigen (Plugin legt auch Frame-Ordner an)
                val lower = path.lowercase()
                if (!lower.endsWith(".mp4") && !lower.endsWith(".mkv") &&
                    !lower.endsWith(".avi") && !lower.endsWith(".mov")) continue
                items.add(
                    Timelapse(
                        path = path,
                        modified = (obj.optDouble("modified", 0.0) * 1000).toLong(),
                        size = obj.optLong("size", 0L)
                    )
                )
            }
            items.sortedByDescending { it.modified }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Voll-URL eines Zeitraffer-Videos (zum Abspielen/Streamen)
    fun timelapseUrl(path: String): String {
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        return "$baseUrl/server/files/timelapse/$encoded"
    }

    // Auth-Header (für den ExoPlayer beim Streamen erforderlich)
    fun authHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            headers["Authorization"] = Credentials.basic(config.username, config.password)
        }
        if (config.apiKey.isNotBlank()) {
            headers["X-Api-Key"] = config.apiKey
        }
        return headers
    }

    // Zeitraffer löschen
    suspend fun deleteTimelapse(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
            val req = Request.Builder()
                .url("$baseUrl/server/files/timelapse/$encoded")
                .delete()
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Zeitraffer streamend herunterladen (kein Laden ins RAM → kein OOM bei großen Videos)
    suspend fun streamTimelapse(path: String, out: java.io.OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(timelapseUrl(path)).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input -> input.copyTo(out) } ?: error("Leere Antwort")
            Unit
        }
    }

    // Konfigurationsdateien auflisten (root=config)
    suspend fun listConfigFiles(): List<ConfigFile> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/server/files/list?root=config").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val result = json.optJSONArray("result") ?: return@withContext emptyList()
            val files = mutableListOf<ConfigFile>()
            for (i in 0 until result.length()) {
                val obj = result.optJSONObject(i) ?: continue
                val path = obj.optString("path", "")
                if (path.isBlank()) continue
                files.add(
                    ConfigFile(
                        path = path,
                        modified = (obj.optDouble("modified", 0.0) * 1000).toLong(),
                        size = obj.optLong("size", 0L)
                    )
                )
            }
            files.sortedWith(compareBy({ it.directory }, { it.filename }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Konfigurationsdatei herunterladen
    suspend fun readConfigFile(path: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url("$baseUrl/server/files/config/$encoded").get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.string() ?: ""
    }

    // Konfigurationsdatei speichern (Moonraker-Upload)
    suspend fun saveConfigFile(path: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val filename = path.substringAfterLast('/')
            val directory = path.substringBeforeLast('/', "")
            val fileBody = content.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("root", "config")
                .apply { if (directory.isNotBlank()) addFormDataPart("path", directory) }
                .addFormDataPart("file", filename, fileBody)
                .build()
            val req = Request.Builder().url("$baseUrl/server/files/upload").post(multipart).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
        }
    }

    // Crowsnest-Konfiguration parsen → Liste der konfigurierten Kameras
    suspend fun detectCrownestCams(): List<CrownestCam> = withContext(Dispatchers.IO) {
        try {
            // Typische Dateinamen: crowsnest.conf oder crownest.conf
            val candidates = listOf("crowsnest.conf", "crownest.conf", "crowsnest.cfg", "crownest.cfg")
            val raw = candidates.mapNotNull { name ->
                runCatching {
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val req = Request.Builder().url("$baseUrl/server/files/config/$encoded").get().build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) resp.body?.string() else null
                }.getOrNull()
            }.firstOrNull() ?: return@withContext emptyList()
            // INI-Format parsen
            val cams = mutableListOf<CrownestCam>()
            var currentSection: String? = null
            var currentPort = 0
            var currentMode = "ustreamer"
            for (line in raw.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith('#') || trimmed.isBlank()) continue
                if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                    if (currentSection != null && currentSection!!.startsWith("cam ") && currentPort > 0) {
                        cams.add(CrownestCam(currentSection!!, currentPort, currentMode))
                    }
                    currentSection = trimmed.removeSurrounding("[", "]").trim()
                    currentPort = 0
                    currentMode = "ustreamer"
                } else {
                    val key = trimmed.substringBefore(':').trim().lowercase()
                    val value = trimmed.substringAfter(':', "").trim()
                    when (key) {
                        "port" -> currentPort = value.toIntOrNull() ?: 0
                        "mode" -> currentMode = value.lowercase()
                    }
                }
            }
            if (currentSection != null && currentSection!!.startsWith("cam ") && currentPort > 0) {
                cams.add(CrownestCam(currentSection!!, currentPort, currentMode))
            }
            cams
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Druckstatistiken abfragen (nur während aktivem Druck/Pause)
    suspend fun getPrintStats(): PrintStats? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/printer/objects/query?print_stats=&gcode_move=speed_factor,extrude_factor&toolhead=max_velocity&motion_report=live_extruder_velocity&virtual_sdcard=progress"
            val req = Request.Builder().url(url).get().build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext null
            val status = JSONObject(body)
                .optJSONObject("result")?.optJSONObject("status") ?: return@withContext null

            val ps = status.optJSONObject("print_stats") ?: return@withContext null
            val state = ps.optString("state", "")
            if (state != "printing" && state != "paused") return@withContext null

            val gcodeMove = status.optJSONObject("gcode_move")
            val toolhead  = status.optJSONObject("toolhead")
            val motionRep = status.optJSONObject("motion_report")
            val vSdcard   = status.optJSONObject("virtual_sdcard")

            val info = ps.optJSONObject("info")
            val currentLayer = info?.optInt("current_layer", -1)?.takeIf { it > 0 }
            val totalLayers  = info?.optInt("total_layer",   -1)?.takeIf { it > 0 }

            val liveExtVel = motionRep?.optDouble("live_extruder_velocity", 0.0)?.toFloat() ?: 0f
            val volumetricFlow = if (liveExtVel > 0.001f) {
                val r = 1.75f / 2f
                liveExtVel * Math.PI.toFloat() * r * r
            } else null

            val filename = ps.optString("filename", "")
            val printDuration = ps.optDouble("print_duration", 0.0)
            val fileProgress = vSdcard?.optDouble("progress", 0.0)?.toFloat() ?: 0f

            PrintStats(
                filename      = filename,
                printDuration = printDuration.toFloat(),
                progress      = computeProgress(printDuration, filename, fileProgress),
                filamentUsed  = ps.optDouble("filament_used", 0.0).toFloat(),
                currentLayer  = currentLayer,
                totalLayers   = totalLayers,
                maxVelocity   = toolhead?.optDouble("max_velocity", 0.0)?.toFloat()?.takeIf { it > 0 },
                volumetricFlow = volumetricFlow,
                speedFactor   = gcodeMove?.optDouble("speed_factor", 1.0)?.toFloat() ?: 1f,
                extrudeFactor = gcodeMove?.optDouble("extrude_factor", 1.0)?.toFloat() ?: 1f
            )
        } catch (e: Exception) { null }
    }

    // Tuning-Daten abfragen (Geschwindigkeit-, Fluss- und Lüfterwerte)
    suspend fun getTuningData(): TuningData = withContext(Dispatchers.IO) {
        try {
            // fan_generic Objekte entdecken
            val listBody = client.newCall(Request.Builder().url("$baseUrl/printer/objects/list").get().build())
                .execute().body?.string() ?: return@withContext TuningData()
            val objects = JSONObject(listBody).optJSONObject("result")?.optJSONArray("objects")
            val fanGenerics = mutableListOf<String>()
            if (objects != null) {
                for (i in 0 until objects.length()) {
                    val obj = objects.getString(i)
                    if (obj.startsWith("fan_generic ")) fanGenerics.add(obj)
                }
            }

            // Kombinierte Abfrage
            val sb = StringBuilder("$baseUrl/printer/objects/query?gcode_move=speed_factor,extrude_factor&fan=speed")
            fanGenerics.forEach {
                sb.append("&").append(URLEncoder.encode(it, "UTF-8").replace("+", "%20")).append("=speed")
            }

            val statusJson = client.newCall(Request.Builder().url(sb.toString()).get().build())
                .execute().body?.string() ?: return@withContext TuningData()
            val status = JSONObject(statusJson).optJSONObject("result")?.optJSONObject("status")
                ?: return@withContext TuningData()

            val gm = status.optJSONObject("gcode_move")
            val speedFactor   = ((gm?.optDouble("speed_factor",   1.0) ?: 1.0) * 100).toInt()
            val extrudeFactor = ((gm?.optDouble("extrude_factor", 1.0) ?: 1.0) * 100).toInt()
            val partFan       = ((status.optJSONObject("fan")?.optDouble("speed", 0.0) ?: 0.0) * 100).toInt()

            val fans = fanGenerics.mapNotNull { key ->
                val speed = status.optJSONObject(key)?.optDouble("speed", 0.0) ?: return@mapNotNull null
                val keyName = key.removePrefix("fan_generic ")
                val displayName = keyName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                FanInfo(keyName = keyName, displayName = displayName, speedPercent = (speed * 100).toInt())
            }

            TuningData(speedFactor = speedFactor, extrudeFactor = extrudeFactor, partCoolingFan = partFan, fans = fans)
        } catch (e: Exception) { TuningData() }
    }

    // Druckgeschwindigkeit setzen: M220 S<percent>
    suspend fun setSpeedFactor(percent: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { sendGcodeInternal("M220 S$percent") }
    }

    // Extrusionsrate setzen: M221 S<percent>
    suspend fun setExtrudeFactor(percent: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { sendGcodeInternal("M221 S$percent") }
    }

    // Part-Cooling-Lüfter setzen: M106 S<0-255>
    suspend fun setPartCoolingFan(percent: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val s = (percent / 100.0 * 255).toInt().coerceIn(0, 255)
        runCatching { sendGcodeInternal("M106 S$s") }
    }

    // fan_generic Lüfter setzen: SET_FAN_SPEED FAN=<name> SPEED=<0.0-1.0>
    suspend fun setGenericFanSpeed(fanKeyName: String, percent: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val speed = String.format(java.util.Locale.US, "%.2f", percent / 100.0)
        runCatching { sendGcodeInternal("SET_FAN_SPEED FAN=$fanKeyName SPEED=$speed") }
    }

    // Treiber-Einstellungen (X/Y/Z) aus der Klipper-Konfiguration lesen.
    // run_current/hold_current stammen aus der "tmcXXXX stepper_?"-Sektion,
    // microsteps/rotation_distance aus der "stepper_?"-Sektion.
    suspend fun getDriverSettings(): DriverSettings = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/objects/query?configfile=settings").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext DriverSettings()
            val settings = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONObject("status")
                ?.optJSONObject("configfile")
                ?.optJSONObject("settings")
                ?: return@withContext DriverSettings()

            // Klipper-Settings-Schlüssel sind kleingeschrieben (z. B. "tmc2209 stepper_x")
            val tmcPrefixes = listOf("tmc2209", "tmc2208", "tmc2130", "tmc2240", "tmc5160", "tmc2660")
            var axes = listOf("X" to "stepper_x", "Y" to "stepper_y", "Z" to "stepper_z").map { (axis, stepper) ->
                val stepperObj = settings.optJSONObject(stepper)
                var driverType = ""
                var runCurrent: Float? = null
                var holdCurrent: Float? = null
                for (prefix in tmcPrefixes) {
                    val tmcObj = settings.optJSONObject("$prefix $stepper")
                    if (tmcObj != null) {
                        driverType = prefix
                        runCurrent = tmcObj.optDouble("run_current", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                        holdCurrent = tmcObj.optDouble("hold_current", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                        break
                    }
                }
                AxisDriver(
                    axis = axis,
                    stepperName = stepper,
                    driverType = driverType,
                    runCurrent = runCurrent,
                    holdCurrent = holdCurrent,
                    microsteps = stepperObj?.optInt("microsteps", -1)?.takeIf { it > 0 },
                    rotationDistance = stepperObj?.optDouble("rotation_distance", Double.NaN)
                        ?.takeIf { !it.isNaN() }?.toFloat()
                )
            }

            // Live-Ströme aus den TMC-Status-Objekten lesen. Diese spiegeln SET_TMC_CURRENT
            // wider; die configfile-Settings dagegen sind der beim Start geparste Stand und
            // ändern sich erst nach einem FIRMWARE_RESTART. Ohne diesen Schritt würden gerade
            // live gesetzte Stromwerte beim erneuten Öffnen wieder „zurückspringen".
            val tmcAxes = axes.filter { it.driverType.isNotBlank() }
            if (tmcAxes.isNotEmpty()) {
                val q = StringBuilder("$baseUrl/printer/objects/query?")
                tmcAxes.forEachIndexed { i, a ->
                    if (i > 0) q.append("&")
                    val obj = "${a.driverType} ${a.stepperName}"
                    q.append(URLEncoder.encode(obj, "UTF-8").replace("+", "%20"))
                        .append("=run_current,hold_current")
                }
                val statusBody = runCatching {
                    client.newCall(Request.Builder().url(q.toString()).get().build())
                        .execute().body?.string()
                }.getOrNull()
                val liveStatus = statusBody?.let {
                    runCatching {
                        JSONObject(it).optJSONObject("result")?.optJSONObject("status")
                    }.getOrNull()
                }
                if (liveStatus != null) {
                    axes = axes.map { a ->
                        if (a.driverType.isBlank()) return@map a
                        val o = liveStatus.optJSONObject("${a.driverType} ${a.stepperName}") ?: return@map a
                        a.copy(
                            runCurrent = o.optDouble("run_current", Double.NaN)
                                .takeIf { !it.isNaN() }?.toFloat() ?: a.runCurrent,
                            holdCurrent = o.optDouble("hold_current", Double.NaN)
                                .takeIf { !it.isNaN() }?.toFloat() ?: a.holdCurrent
                        )
                    }
                }
            }

            DriverSettings(axes = axes)
        } catch (e: Exception) {
            DriverSettings()
        }
    }

    // Treiber-Einstellungen anwenden und persistieren.
    //  • run_current/hold_current → sofort live via SET_TMC_CURRENT
    //  • rotation_distance        → sofort live via SET_ROTATION_DISTANCE
    //  • alle Werte               → zusätzlich in die Config-Datei geschrieben (Persistenz)
    //  • microsteps/rotation_distance geändert → FIRMWARE_RESTART, damit sie greifen
    suspend fun applyDriverSettings(edits: List<DriverEdit>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val loc = java.util.Locale.US

                // 1) Live anwenden
                for (e in edits) {
                    if (e.driverType.isNotBlank() && e.runCurrent != null) {
                        val sb = StringBuilder(
                            "SET_TMC_CURRENT STEPPER=${e.stepperName} CURRENT=${String.format(loc, "%.2f", e.runCurrent)}"
                        )
                        if (e.holdCurrent != null) {
                            sb.append(" HOLDCURRENT=${String.format(loc, "%.2f", e.holdCurrent)}")
                        }
                        sendGcodeInternal(sb.toString())
                    }
                    if (e.rotationDistance != null) {
                        sendGcodeInternal(
                            "SET_ROTATION_DISTANCE STEPPER=${e.stepperName} DISTANCE=${String.format(loc, "%.4f", e.rotationDistance)}"
                        )
                    }
                }

                // 2) In Config-Datei(en) persistieren
                val sectionEdits = HashMap<String, MutableMap<String, String>>()
                for (e in edits) {
                    if (e.driverType.isNotBlank() && (e.runCurrent != null || e.holdCurrent != null)) {
                        val m = sectionEdits.getOrPut("${e.driverType} ${e.stepperName}") { HashMap() }
                        if (e.runCurrent != null) m["run_current"] = String.format(loc, "%.3f", e.runCurrent)
                        if (e.holdCurrent != null) m["hold_current"] = String.format(loc, "%.3f", e.holdCurrent)
                    }
                    if (e.microsteps != null || e.rotationDistance != null) {
                        val m = sectionEdits.getOrPut(e.stepperName) { HashMap() }
                        if (e.microsteps != null) m["microsteps"] = e.microsteps.toString()
                        if (e.rotationDistance != null) m["rotation_distance"] = String.format(loc, "%.4f", e.rotationDistance)
                    }
                }
                if (sectionEdits.isNotEmpty()) {
                    val files = listConfigFiles().filter { it.path.endsWith(".cfg", ignoreCase = true) }
                    for (f in files) {
                        val content = runCatching { readConfigFile(f.path) }.getOrNull() ?: continue
                        val relevant = sectionEdits.keys.filter { content.contains("[$it]") }
                        if (relevant.isEmpty()) continue
                        val subset = relevant.associateWith { sectionEdits[it]!! }
                        val (newContent, changed) = applyConfigEdits(content, subset)
                        if (changed) saveConfigFile(f.path, newContent).getOrThrow()
                    }
                }

                // 3) Neustart, falls microsteps/rotation_distance geändert (nur dann nötig –
                // Ströme sind bereits live aktiv).
                if (edits.any { it.needsRestart }) {
                    runCatching {
                        val req = Request.Builder()
                            .url("$baseUrl/printer/firmware_restart")
                            .post("".toRequestBody(null))
                            .build()
                        gcodeClient.newCall(req).execute().close()
                    }
                }
            }
        }

    // Setzt key:value innerhalb der angegebenen Sektionen einer INI-artigen Klipper-Config.
    // Vorhandene Schlüssel werden ersetzt (Einrückung bleibt erhalten), fehlende am
    // Sektionsende ergänzt. Alle übrigen Zeilen bleiben unverändert.
    private fun applyConfigEdits(
        content: String,
        edits: Map<String, Map<String, String>>
    ): Pair<String, Boolean> {
        val lines = content.split("\n")
        val out = ArrayList<String>(lines.size + edits.size)
        var changed = false
        var remaining: MutableMap<String, String>? = null

        fun flushRemaining() {
            remaining?.let { rem ->
                for ((k, v) in rem) {
                    out.add("$k: $v")
                    changed = true
                }
            }
            remaining = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            val header = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                trimmed.removeSurrounding("[", "]").trim()
            } else null

            if (header != null) {
                flushRemaining()
                remaining = edits[header]?.toMutableMap()
                out.add(line)
                continue
            }

            val rem = remaining
            if (rem != null && trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";")) {
                val sep = trimmed.indexOfFirst { it == ':' || it == '=' }
                if (sep > 0) {
                    val key = trimmed.substring(0, sep).trim()
                    val newVal = rem.remove(key)
                    if (newVal != null) {
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        out.add("$indent$key: $newVal")
                        changed = true
                        continue
                    }
                }
            }
            out.add(line)
        }
        flushRemaining()
        return out.joinToString("\n") to changed
    }

    // Rohliste aller Klipper-Objekte (für die einmalige Erkennung dynamischer
    // Objektnamen wie extruder1, heater_generic X, temperature_sensor X, fan_generic X).
    suspend fun getObjectList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/objects/list").get().build()
            val body = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val objects = JSONObject(body).optJSONObject("result")?.optJSONArray("objects")
                ?: return@withContext emptyList()
            (0 until objects.length()).map { objects.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Gebündelter Status für die App-Statusanzeige: EINE einzige printer.objects.query
    // liefert Temperaturen, Druckerzustand, Position, Fortschritt, Druckstatistik und
    // Tuning-Werte. heaterKeys/fanGenericKeys werden vom Repository einmalig erkannt
    // und übergeben, damit hier kein zusätzlicher /objects/list-Aufruf nötig ist.
    suspend fun getPrinterSnapshot(
        heaterKeys: List<String>,
        fanGenericKeys: List<String>,
        includeFan: Boolean
    ): PrinterSnapshot = withContext(Dispatchers.IO) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val sb = StringBuilder(
            "$baseUrl/printer/objects/query?print_stats=&toolhead=&virtual_sdcard=" +
                "&gcode_move=speed,speed_factor,extrude_factor" +
                "&motion_report=live_extruder_velocity"
        )
        // Das [fan]-Objekt (Bauteilkühlung) ist optional – nur abfragen, wenn es laut
        // Objektliste existiert. Sonst lehnt Moonraker die GESAMTE Query mit HTTP 400 ab.
        if (includeFan) sb.append("&fan=speed")
        heaterKeys.forEach { sb.append("&").append(enc(it)).append("=") }
        fanGenericKeys.forEach { sb.append("&").append(enc(it)).append("=speed") }

        val req = Request.Builder().url(sb.toString()).get().build()
        val body = client.newCall(req).execute().body?.string()
            ?: error("Leere Antwort auf printer.objects.query")
        // Bei Fehlern (fehlendes "result"/"status") eine Exception werfen, damit der
        // bisherige Druckerzustand erhalten bleibt und NICHT fälschlich auf "offline"
        // zurückgesetzt wird (würde mitten im Druck einen Abbruch vortäuschen).
        val status = JSONObject(body).optJSONObject("result")?.optJSONObject("status")
            ?: error("Ungültige Antwort auf printer.objects.query")

        // Temperaturen
        val temps = mutableListOf<TemperatureInfo>()
        for (key in heaterKeys) {
            val obj = status.optJSONObject(key) ?: continue
            val current = obj.optDouble("temperature", -1.0).toFloat()
            if (current < 0) continue
            temps.add(
                TemperatureInfo(
                    name = key,
                    current = current,
                    target = obj.optDouble("target", 0.0).toFloat(),
                    power = obj.optDouble("power", 0.0).toFloat()
                )
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

        // Position + max_velocity aus toolhead
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
            val filename = ps.optString("filename", "")
            val printDuration = ps.optDouble("print_duration", 0.0)
            if (printing) {
                val fileProgress = vSdcard?.optDouble("progress", 0.0)?.toFloat() ?: 0f
                progress = computeProgress(printDuration, filename, fileProgress)
                val speedMmMin = gcodeMove?.optDouble("speed", -1.0) ?: -1.0
                speedMmS = if (speedMmMin > 0) (speedMmMin / 60.0).toFloat() else null
            }

            val info = ps.optJSONObject("info")
            val currentLayer = info?.optInt("current_layer", -1)?.takeIf { it > 0 }
            val totalLayers  = info?.optInt("total_layer", -1)?.takeIf { it > 0 }

            val liveExtVel = motionRep?.optDouble("live_extruder_velocity", 0.0)?.toFloat() ?: 0f
            val volumetricFlow = if (printing && liveExtVel > 0.001f) {
                val r = 1.75f / 2f
                liveExtVel * Math.PI.toFloat() * r * r
            } else null

            // Druckstatistik immer parsen, damit nach Abschluss/Abbruch die finalen Werte
            // (Filamentverbrauch, Druckdauer, Statusmeldung) für die Benachrichtigung bereitstehen.
            stats = PrintStats(
                filename      = filename,
                printDuration = printDuration.toFloat(),
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

        // Tuning-Werte
        val speedFactor   = ((gcodeMove?.optDouble("speed_factor", 1.0) ?: 1.0) * 100).toInt()
        val extrudeFactor = ((gcodeMove?.optDouble("extrude_factor", 1.0) ?: 1.0) * 100).toInt()
        val partFan       = ((status.optJSONObject("fan")?.optDouble("speed", 0.0) ?: 0.0) * 100).toInt()
        val fans = fanGenericKeys.mapNotNull { key ->
            val speed = status.optJSONObject(key)?.optDouble("speed", 0.0) ?: return@mapNotNull null
            val keyName = key.removePrefix("fan_generic ")
            val displayName = keyName.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            FanInfo(keyName = keyName, displayName = displayName, speedPercent = (speed * 100).toInt())
        }
        val tuning = TuningData(
            speedFactor = speedFactor,
            extrudeFactor = extrudeFactor,
            partCoolingFan = partFan,
            fans = fans
        )

        PrinterSnapshot(
            temperatures = temps.sortedBy { it.name },
            printerState = mapped,
            rawState = rawState,
            position = position,
            printProgress = progress,
            printSpeedMmPerSec = speedMmS,
            printStats = stats,
            tuningData = tuning
        )
    }

    // Aktuelle Druckkopf-Position abfragen
    suspend fun getPosition(): KlipperPosition = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/objects/query?toolhead=").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext KlipperPosition()
            val json = JSONObject(body)
            val toolhead = json.optJSONObject("result")
                ?.optJSONObject("status")
                ?.optJSONObject("toolhead") ?: return@withContext KlipperPosition()
            val pos = toolhead.optJSONArray("position") ?: return@withContext KlipperPosition()
            KlipperPosition(
                x = pos.optDouble(0, Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                y = pos.optDouble(1, Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                z = pos.optDouble(2, Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            )
        } catch (e: Exception) {
            KlipperPosition()
        }
    }
}
