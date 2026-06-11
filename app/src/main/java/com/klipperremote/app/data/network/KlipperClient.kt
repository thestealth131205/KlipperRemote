package com.klipperremote.app.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.klipperremote.app.data.model.GcodeMetadata
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrinterStatusInfo
import com.klipperremote.app.data.model.TemperatureInfo
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

    private val baseUrl: String
        get() = "http://${config.host}:${config.port}"

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
    suspend fun getPrinterStatus(): PrinterStatusInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/printer/info").get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext PrinterStatusInfo()
            val json = JSONObject(body)
            val result = json.optJSONObject("result") ?: return@withContext PrinterStatusInfo()
            PrinterStatusInfo(
                state = result.optString("state", "offline"),
                message = result.optString("state_message", "")
            )
        } catch (e: Exception) {
            PrinterStatusInfo("offline")
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
        val resp = client.newCall(req).execute()
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
                files.add(
                    PrintFile(
                        filename = obj.optString("filename", ""),
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
                    thumbnailUrl = "$baseUrl/server/files/gcodes/$relPath"
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
            val state = status.optJSONObject("print_stats")?.optString("state", "") ?: ""
            if (state != "printing" && state != "paused") return@withContext null
            status.optJSONObject("virtual_sdcard")?.optDouble("progress", -1.0)
                ?.takeIf { it >= 0.0 }?.toFloat()
        } catch (e: Exception) {
            null
        }
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
