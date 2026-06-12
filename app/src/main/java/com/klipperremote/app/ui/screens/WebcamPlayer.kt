package com.klipperremote.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.klipperremote.app.data.model.WebcamStreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Einheitlicher Webcam-Player. Zeigt IMMER einen schwarzen Hintergrund bis das
 * eigentliche Stream-Bild läuft – statt einer HTML-Seite im WebView.
 * Je nach Stream-Typ wird ein echter nativer Player verwendet:
 *  - MJPEG / Camera-Streamer → nativer MJPEG-Decoder (Frame-für-Frame Bitmap)
 *  - HLS                     → Media3/ExoPlayer
 *  - WebRTC                  → WebRTC-PeerConnection mit SurfaceViewRenderer (WHEP)
 */
@Composable
fun WebcamPlayer(
    streamUrl: String,
    streamType: WebcamStreamType,
    flipH: Boolean = false,
    flipV: Boolean = false,
    rotate: Int = 0,
    stunServer: String = "",
    iceUsername: String = "",
    icePassword: String = "",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (streamType) {
            WebcamStreamType.WEBRTC -> WebRtcPlayer(
                url = streamUrl,
                stunServer = stunServer,
                iceUsername = iceUsername,
                icePassword = icePassword,
                flipH = flipH,
                flipV = flipV,
                rotate = rotate,
                modifier = Modifier.fillMaxSize()
            )
            WebcamStreamType.HLS -> HlsPlayer(
                url = streamUrl,
                flipH = flipH,
                flipV = flipV,
                rotate = rotate,
                modifier = Modifier.fillMaxSize()
            )
            else -> MjpegPlayer(
                url = streamUrl,
                flipH = flipH,
                flipV = flipV,
                rotate = rotate,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── MJPEG ────────────────────────────────────────────────────────────────────

private val mjpegClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // Endlos-Stream
        .build()
}

@Composable
private fun MjpegPlayer(
    url: String,
    flipH: Boolean,
    flipV: Boolean,
    rotate: Int,
    modifier: Modifier
) {
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(url, lifecycleOwner) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var job: Job? = null
        var currentCall: Call? = null

        fun stopStream() {
            job?.cancel()
            job = null
            currentCall?.cancel()
            currentCall = null
        }

        fun startStream() {
            if (job?.isActive == true) return
            job = scope.launch {
                while (isActive) {
                    try {
                        streamMjpeg(url, onCall = { currentCall = it }) { bmp -> frame = bmp }
                    } catch (_: Exception) {
                        // Verbindung verloren / abgebrochen → kurz warten, dann erneut versuchen
                    }
                    if (!isActive) break
                    delay(1500)
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> startStream()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> stopStream()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        startStream()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopStream()
            scope.cancel()
        }
    }

    val current = frame
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Webcam",
            contentScale = ContentScale.Fit,
            modifier = modifier.graphicsLayer(
                scaleX = if (flipH) -1f else 1f,
                scaleY = if (flipV) -1f else 1f,
                rotationZ = rotate.toFloat()
            )
        )
    } else {
        CircularProgressIndicator(color = Color(0xFF555555))
    }
}

/**
 * Liest einen Motion-JPEG (multipart) Stream und liefert jeden vollständigen
 * JPEG-Frame als Bitmap. Sucht robust nach den JPEG-Markern SOI (FFD8) / EOI (FFD9),
 * unabhängig von den multipart-Boundary-Headern.
 */
private fun streamMjpeg(url: String, onCall: (Call) -> Unit, onFrame: (Bitmap) -> Unit) {
    val request = Request.Builder().url(url).build()
    val call = mjpegClient.newCall(request)
    onCall(call)
    call.execute().use { resp ->
        val body = resp.body ?: return
        val input = BufferedInputStream(body.byteStream(), 16 * 1024)
        val buffer = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        var inFrame = false
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                }
            } else {
                buffer.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    val bytes = buffer.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(onFrame)
                    inFrame = false
                    buffer.reset()
                }
            }
            prev = b
        }
    }
}

// ── HLS (Media3) ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
private fun HlsPlayer(
    url: String,
    flipH: Boolean,
    flipV: Boolean,
    rotate: Int,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            val source = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier.graphicsLayer(
            scaleX = if (flipH) -1f else 1f,
            scaleY = if (flipV) -1f else 1f,
            rotationZ = rotate.toFloat()
        )
    )
}

// ── WebRTC ───────────────────────────────────────────────────────────────────

@Composable
private fun WebRtcPlayer(
    url: String,
    stunServer: String,
    iceUsername: String,
    icePassword: String,
    flipH: Boolean,
    flipV: Boolean,
    rotate: Int,
    modifier: Modifier
) {
    val context = LocalContext.current
    val renderer = remember(url) { SurfaceViewRenderer(context) }

    val session = remember(url) {
        val iceServers = buildList {
            if (stunServer.isNotBlank()) {
                val b = PeerConnection.IceServer.builder(stunServer)
                if (iceUsername.isNotBlank()) {
                    b.setUsername(iceUsername)
                    b.setPassword(icePassword)
                }
                add(b.createIceServer())
            }
        }
        WebRtcSession(context.applicationContext, url, iceServers)
    }

    DisposableEffect(url) {
        renderer.init(session.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(flipH)
        renderer.rotation = rotate.toFloat()
        renderer.scaleY = if (flipV) -1f else 1f
        session.onVideoTrack = { track -> track.addSink(renderer) }
        session.start()
        onDispose {
            session.dispose()
            renderer.release()
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}

/** Kapselt eine WebRTC-Empfangs-Session (recvonly) gegen einen WHEP-Endpunkt. */
private class WebRtcSession(
    private val context: Context,
    private val url: String,
    private val iceServers: List<PeerConnection.IceServer>
) {
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var offerSent = false

    var onVideoTrack: ((VideoTrack) -> Unit)? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        ensureFactoryInitialized(context)
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        peerConnection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    scope.launch { sendOfferAndApplyAnswer() }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { onVideoTrack?.invoke(it) }
            }
        })

        // Nur Empfang (recvonly) für Video + Audio
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) peerConnection?.setLocalDescription(emptySdpObserver(), sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun sendOfferAndApplyAnswer() {
        if (offerSent) return
        offerSent = true
        val offer = peerConnection?.localDescription ?: return
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/sdp")
                .post(offer.description.toRequestBody("application/sdp".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                val payload = resp.body?.string()?.trim().orEmpty()
                if (payload.isEmpty()) return
                // Antwort kann reines SDP oder JSON ({"type":"answer","sdp":...}) sein
                val answerSdp = if (payload.startsWith("v=0")) {
                    payload
                } else {
                    runCatching { JSONObject(payload).getString("sdp") }.getOrNull() ?: return
                }
                val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                peerConnection?.setRemoteDescription(emptySdpObserver(), answer)
            }
        } catch (_: Exception) {
            // Signaling fehlgeschlagen → Renderer bleibt schwarz
        }
    }

    fun dispose() {
        scope.cancel()
        runCatching { peerConnection?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { eglBase.release() }
        peerConnection = null
        factory = null
    }

    private fun emptySdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        @Volatile
        private var factoryInitialized = false

        @Synchronized
        fun ensureFactoryInitialized(context: Context) {
            if (factoryInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
    }
}
