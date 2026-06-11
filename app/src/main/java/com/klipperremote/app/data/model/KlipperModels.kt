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
    fun resolveStreamUrl(host: String): String {
        if (customUrl.isNotBlank()) {
            // relative path (e.g. "/webcam/?action=stream") → prepend host
            return if (customUrl.startsWith("/")) "http://$host$customUrl" else customUrl
        }
        if (host.isBlank()) return ""
        return when (streamType) {
            WebcamStreamType.MJPEG -> "http://$host/webcam/stream"
            WebcamStreamType.WEBRTC -> "http://$host/webcam/webrtc"
            WebcamStreamType.HLS -> "http://$host/webcam/hls/stream.m3u8"
            WebcamStreamType.CAMERA_STREAMER -> "http://$host/webcam/stream"
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
