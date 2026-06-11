package com.klipperremote.app.data.model

data class TemperatureInfo(
    val name: String,
    val current: Float,
    val target: Float,
    val power: Float = 0f
)

data class KlipperConfig(
    val host: String = "",
    val port: Int = 7125,
    val username: String = "",
    val password: String = "",
    val apiKey: String = ""
)

data class KlipperTemperaturesResponse(
    val result: TemperaturesResult?
)

data class TemperaturesResult(
    val status: Map<String, TemperatureStatus>?
)

data class TemperatureStatus(
    val temperature: Float = 0f,
    val target: Float = 0f,
    val power: Float = 0f
)

data class SetTemperatureRequest(
    val method: String,
    val params: Map<String, Any>,
    val id: Int = 1
)

data class PrinterStatusInfo(
    val state: String = "offline",
    val message: String = ""
)

enum class WebcamStreamType(val label: String) {
    MJPEG("MJPEG"),
    WEBRTC("WebRTC"),
    HLS("HLS"),
    CAMERA_STREAMER("Cam-Streamer")
}

data class WebcamConfig(
    val name: String = "cam 1",
    val customUrl: String = "",
    val snapshotUrl: String = "",
    val streamType: WebcamStreamType = WebcamStreamType.MJPEG,
    val fps: Int = 15,
    val rotate: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val stunServer: String = "stun:stun.l.google.com:19302",
    val iceUsername: String = "",
    val icePassword: String = "",
    // Separater Webcam-Port (0 = Moonraker-Port als Fallback)
    val webcamPort: Int = 0
) {
    /** Effektiver Port: webcamPort wenn gesetzt, sonst Moonraker-Port */
    private fun effectivePort(moonrakerPort: Int) = if (webcamPort > 0) webcamPort else moonrakerPort

    fun resolveSnapshotUrl(host: String, moonrakerPort: Int = 7125, apiKey: String = ""): String {
        val snap = snapshotUrl.ifBlank { return "" }
        val ePort = effectivePort(moonrakerPort)
        return if (snap.startsWith("/")) {
            val keyp = if (apiKey.isNotBlank()) "?apikey=$apiKey" else ""
            "http://$host:$ePort$snap$keyp"
        } else {
            if (apiKey.isNotBlank()) "$snap?apikey=$apiKey" else snap
        }
    }

    fun resolveStreamUrl(host: String, moonrakerPort: Int = 7125, apiKey: String = ""): String {
        val ePort = effectivePort(moonrakerPort)
        val keyParam = if (apiKey.isNotBlank()) "?apikey=$apiKey" else ""
        if (customUrl.isNotBlank()) {
            return if (customUrl.startsWith("/")) {
                // Relativer Pfad → host:effectivePort + Pfad + API-Key
                val relKeyParam = if (apiKey.isNotBlank()) "?apikey=$apiKey" else ""
                "http://$host:$ePort$customUrl$relKeyParam"
            } else {
                // Absolute URL (Rückwärtskompatibilität) → unverändert + API-Key
                if (apiKey.isNotBlank()) "$customUrl?apikey=$apiKey" else customUrl
            }
        }
        if (host.isBlank()) return ""
        return when (streamType) {
            WebcamStreamType.MJPEG -> "http://$host:$ePort/webcam/stream$keyParam"
            WebcamStreamType.WEBRTC -> "http://$host:$ePort/webcam/webrtc$keyParam"
            WebcamStreamType.HLS -> "http://$host:$ePort/webcam/hls/stream.m3u8$keyParam"
            WebcamStreamType.CAMERA_STREAMER -> "http://$host:$ePort/webcam/stream$keyParam"
        }
    }
}

data class PrintFile(
    val filename: String,
    val modified: Long = 0L,
    val size: Long = 0L,
    val printDuration: Float = 0f
)

data class KlipperPosition(
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null
)

data class PowerDevice(
    val name: String,
    val status: String // "on", "off", "error"
)

enum class MoveType { TRAVEL, PRINT, INFILL, SUPPORT }

data class GCodeSegment(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val moveType: MoveType
)

data class GCodeLayer(
    val zHeight: Float,
    val segments: List<GCodeSegment>
)

data class GcodeMetadata(
    val thumbnailUrl: String? = null,
    val estimatedTime: Int? = null  // seconds
)

data class ConfigFile(
    val path: String,          // relativer Pfad inkl. Unterverzeichnisse
    val modified: Long = 0L,
    val size: Long = 0L
) {
    val filename: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")
}

data class CrownestCam(
    val name: String,
    val port: Int,
    val mode: String  // "ustreamer" | "camera-streamer"
)
