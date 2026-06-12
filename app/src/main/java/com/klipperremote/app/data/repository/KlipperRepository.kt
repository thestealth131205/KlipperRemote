package com.klipperremote.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import android.graphics.Bitmap
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
import com.klipperremote.app.data.model.PrinterStatusInfo
import com.klipperremote.app.data.model.TemperatureInfo
import com.klipperremote.app.data.model.TuningData
import com.klipperremote.app.data.model.WebcamConfig
import com.klipperremote.app.data.model.WebcamStreamType
import com.klipperremote.app.data.network.KlipperClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KlipperRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val KEY_HOST = stringPreferencesKey("klipper_host")
        val KEY_PORT = intPreferencesKey("klipper_port")
        val KEY_USERNAME = stringPreferencesKey("klipper_username")
        val KEY_PASSWORD = stringPreferencesKey("klipper_password")
        val KEY_API_KEY = stringPreferencesKey("klipper_api_key")
        val KEY_CACHED_POWER_DEVICES = stringPreferencesKey("cached_power_devices")
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
    }

    val configFlow: Flow<KlipperConfig> = dataStore.data.map { prefs ->
        KlipperConfig(
            host = prefs[KEY_HOST] ?: "",
            port = prefs[KEY_PORT] ?: 7125,
            username = prefs[KEY_USERNAME] ?: "",
            password = prefs[KEY_PASSWORD] ?: "",
            apiKey = prefs[KEY_API_KEY] ?: ""
        )
    }

    val webcamConfigFlow: Flow<WebcamConfig> = dataStore.data.map { prefs ->
        WebcamConfig(
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
    }

    val appConfigFlow: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            maxConcurrentConnections = (prefs[KEY_APP_MAX_CONNECTIONS] ?: 1).coerceIn(1, 8),
            tempIntervalSec = (prefs[KEY_APP_TEMP_INTERVAL] ?: 2).coerceIn(1, 60),
            backgroundIntervalSec = (prefs[KEY_APP_BG_INTERVAL] ?: 4).coerceIn(1, 120),
            powerIntervalSec = (prefs[KEY_APP_POWER_INTERVAL] ?: 15).coerceIn(1, 300),
            notifyIntervalSec = (prefs[KEY_APP_NOTIFY_INTERVAL] ?: 10).coerceIn(5, 600)
        )
    }

    suspend fun saveAppConfig(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_MAX_CONNECTIONS] = config.maxConcurrentConnections.coerceIn(1, 8)
            prefs[KEY_APP_TEMP_INTERVAL] = config.tempIntervalSec.coerceIn(1, 60)
            prefs[KEY_APP_BG_INTERVAL] = config.backgroundIntervalSec.coerceIn(1, 120)
            prefs[KEY_APP_POWER_INTERVAL] = config.powerIntervalSec.coerceIn(1, 300)
            prefs[KEY_APP_NOTIFY_INTERVAL] = config.notifyIntervalSec.coerceIn(5, 600)
        }
    }

    suspend fun saveConfig(config: KlipperConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_HOST] = config.host
            prefs[KEY_PORT] = config.port
            prefs[KEY_USERNAME] = config.username
            prefs[KEY_PASSWORD] = config.password
            prefs[KEY_API_KEY] = config.apiKey
        }
    }

    suspend fun saveWebcamConfig(config: WebcamConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_WEBCAM_NAME] = config.name
            prefs[KEY_WEBCAM_URL] = config.customUrl
            prefs[KEY_WEBCAM_SNAPSHOT_URL] = config.snapshotUrl
            prefs[KEY_WEBCAM_STREAM_TYPE] = config.streamType.name
            prefs[KEY_WEBCAM_FPS] = config.fps
            prefs[KEY_WEBCAM_ROTATE] = config.rotate
            prefs[KEY_WEBCAM_FLIP_H] = config.flipH.toString()
            prefs[KEY_WEBCAM_FLIP_V] = config.flipV.toString()
            prefs[KEY_WEBCAM_STUN] = config.stunServer
            prefs[KEY_WEBCAM_ICE_USER] = config.iceUsername
            prefs[KEY_WEBCAM_ICE_PASS] = config.icePassword
            prefs[KEY_WEBCAM_PORT] = config.webcamPort
        }
    }

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
}
