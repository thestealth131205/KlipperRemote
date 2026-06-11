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
    val icePassword: String = ""
) {
    fun resolveSnapshotUrl(host: String, port: Int = 7125, apiKey: String = ""): String {
        val snap = snapshotUrl.ifBlank { return "" }
        return if (snap.startsWith("/")) {
            val sep = if (snap.contains("?")) "&" else "?"
            val keyp = if (apiKey.isNotBlank()) "${sep}apikey=$apiKey" else ""
            "http://$host:$port$snap$keyp"
        } else {
            if (apiKey.isNotBlank()) "$snap&token=$apiKey" else snap
        }
    }

    fun resolveStreamUrl(host: String, port: Int = 7125, apiKey: String = ""): String {
        val keyParam = if (apiKey.isNotBlank()) "?apikey=$apiKey" else ""
        if (customUrl.isNotBlank()) {
            return if (customUrl.startsWith("/")) {
                // relative path → geht durch Moonraker → API-Key anhängen
                // Trennzeichen: & wenn URL bereits ? enthält, sonst ?
                val sep = if (customUrl.contains("?")) "&" else "?"
                val relKeyParam = if (apiKey.isNotBlank()) "${sep}apikey=$apiKey" else ""
                "http://$host:$port$customUrl$relKeyParam"
            } else {
                // absolute URL (z.B. direkt auf Port 8080) → API-Key anhängen falls gesetzt
                if (apiKey.isNotBlank()) "$customUrl?apikey=$apiKey" else customUrl
            }
        }
        if (host.isBlank()) return ""
        return when (streamType) {
            WebcamStreamType.MJPEG -> "http://$host:$port/webcam/stream$keyParam"
            WebcamStreamType.WEBRTC -> "http://$host:$port/webcam/webrtc$keyParam"
            WebcamStreamType.HLS -> "http://$host:$port/webcam/hls/stream.m3u8$keyParam"
            WebcamStreamType.CAMERA_STREAMER -> "http://$host:$port/webcam/stream$keyParam"
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

data class GCodeSegment(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val isTravel: Boolean
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
