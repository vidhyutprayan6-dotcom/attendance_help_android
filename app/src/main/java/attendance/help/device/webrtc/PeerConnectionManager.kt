package attendance.help.device.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import attendance.help.device.utils.DeviceHints
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class WebRtcListeners(
    val onIceCandidate: (IceCandidate) -> Unit,
    val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
    val onDataMessage: (String) -> Unit,
    val onRemoteVideoTrack: (VideoTrack) -> Unit = {}
)

/**
 * WebRTC peer for screen-share remote control:
 * - Remote publishes screen video (no audio).
 * - Control receives screen and sends touch commands via data channel.
 */
@Singleton
class PeerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val eglBase: EglBase = EglBase.create()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
    private var listeners: WebRtcListeners? = null
    private val mediaRunning = AtomicBoolean(false)
    private var sharingScreen = false

    @Synchronized
    fun ensureInitialized() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val enableH264HighProfile = !DeviceHints.isProbablyEmulator()
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, enableH264HighProfile)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        Timber.i("PeerConnectionFactory ready")
    }

    fun initRenderer(renderer: SurfaceViewRenderer) {
        ensureInitialized()
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
    }

    @Synchronized
    fun createPeerConnection(isController: Boolean, listeners: WebRtcListeners, force: Boolean = false) {
        ensureInitialized()
        if (peerConnection != null && !force) {
            this.listeners = listeners
            return
        }
        closePeerOnly()
        this.listeners = listeners

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                listeners.onIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dc: DataChannel) {
                attachDataChannel(dc)
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    listeners.onRemoteVideoTrack(track)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listeners.onConnectionChange(newState)
            }
        })

        if (isController) {
            dataChannel = peerConnection?.createDataChannel(
                "commands",
                DataChannel.Init().apply { ordered = true }
            )
            dataChannel?.let { attachDataChannel(it) }
        }
    }

    /**
     * Starts screen capture safely. Returns failure instead of crashing the process.
     * Requires an active peer connection so the video track can be published.
     */
    @Synchronized
    fun startScreenShareSafely(permissionResultData: Intent): Result<Unit> {
        return runCatching {
            ensureInitialized()
            if (mediaRunning.get()) return Result.success(Unit)
            val pc = peerConnection
                ?: throw IllegalStateException("Peer connection not ready — stay on session screen and try again")
            val capturer = ScreenCapturerAndroid(
                permissionResultData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post { stopScreenShare() }
                    }
                }
            )
            sharingScreen = true
            val (w, h, fps) = captureDimensions()
            beginScreenCapture(capturer, w, h, fps, pc)
            Timber.i("Screen share started %dx%d@%dfps emulator=%s", w, h, fps, DeviceHints.isProbablyEmulator())
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                Timber.e(error, "startScreenShareSafely failed")
                runCatching { stopScreenShare() }
                Result.failure(error)
            }
        )
    }

    private fun captureDimensions(): Triple<Int, Int, Int> {
        val dm = context.resources.displayMetrics
        return if (DeviceHints.isProbablyEmulator()) {
            Triple(640, 480, 15)
        } else {
            Triple(
                even(min(dm.widthPixels, 1280)),
                even(min(dm.heightPixels, 720)),
                24
            )
        }
    }

    private fun even(value: Int): Int = value and 0xFFFFFFFE.toInt()

    @Deprecated("Use startScreenShareSafely")
    @Synchronized
    fun startScreenShare(permissionResultData: Intent) {
        startScreenShareSafely(permissionResultData).getOrThrow()
    }

    private fun beginScreenCapture(
        capturer: VideoCapturer,
        width: Int,
        height: Int,
        fps: Int,
        pc: PeerConnection
    ) {
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer.startCapture(width, height, fps)
        localVideoTrack = factory!!.createVideoTrack("AH_SCREEN", videoSource).apply {
            setEnabled(true)
        }
        pc.addTrack(localVideoTrack, listOf("AH_STREAM"))
        mediaRunning.set(true)
    }

    @Synchronized
    fun stopScreenShare() {
        if (!mediaRunning.getAndSet(false)) return
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        sharingScreen = false
        Timber.i("Screen share stopped")
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(sdpObserver("offer", onSuccess, onError) { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
        }, constraints) ?: onError("No peer connection")
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createAnswer(sdpObserver("answer", onSuccess, onError) { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
        }, constraints) ?: onError("No peer connection")
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: () -> Unit = {}) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() = onDone()
            override fun onCreateFailure(p0: String?) = Timber.e("setRemote create fail %s", p0)
            override fun onSetFailure(p0: String?) = Timber.e("setRemote set fail %s", p0)
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate): Boolean {
        val pc = peerConnection ?: return false
        return runCatching {
            pc.addIceCandidate(candidate)
            true
        }.getOrElse {
            Timber.w("ICE add failed: %s", it.message)
            false
        }
    }

    fun sendData(message: String) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false)
        channel.send(buffer)
    }

    fun isScreenSharing(): Boolean = sharingScreen && mediaRunning.get()
    fun isLocalMediaPublishing(): Boolean = mediaRunning.get()

    @Synchronized
    fun release() {
        stopScreenShare()
        closePeerOnly()
    }

    private fun closePeerOnly() {
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val text = String(data, Charsets.UTF_8)
                mainHandler.post { listeners?.onDataMessage(text) }
            }
        })
    }

    private fun sdpObserver(
        label: String,
        onSuccess: (SessionDescription) -> Unit,
        onError: (String) -> Unit,
        afterCreate: (SessionDescription) -> Unit
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            afterCreate(sdp)
            onSuccess(sdp)
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = onError(error ?: "create $label failed")
        override fun onSetFailure(error: String?) = onError(error ?: "set $label failed")
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(p0: String?) = Timber.e(p0)
        override fun onSetFailure(p0: String?) = Timber.e(p0)
    }
}
