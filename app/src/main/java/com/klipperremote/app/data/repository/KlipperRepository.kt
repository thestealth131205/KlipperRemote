package com.klipperremote.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.klipperremote.app.data.model.KlipperConfig
import com.klipperremote.app.data.model.KlipperPosition
import com.klipperremote.app.data.model.PowerDevice
import com.klipperremote.app.data.model.PrintFile
import com.klipperremote.app.data.model.PrinterStatusInfo
import com.klipperremote.app.data.model.TemperatureInfo
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
            icePassword = prefs[KEY_WEBCAM_ICE_PASS] ?: ""
        )
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
        }
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
}
