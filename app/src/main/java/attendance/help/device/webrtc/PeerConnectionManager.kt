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
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
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
import attendance.help.device.domain.model.TurnServerConfig
import attendance.help.device.utils.DeviceHints
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class WebRtcListeners(
    val onIceCandidate: (IceCandidate) -> Unit,
    val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
    val onIceConnectionChange: (PeerConnection.IceConnectionState) -> Unit = {},
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var eglBase: EglBase? = null
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
    private var turnConfig: TurnServerConfig = TurnServerConfig()

    var captureWidth: Int = 0
        private set
    var captureHeight: Int = 0
        private set

    @Synchronized
    fun hasPeerConnection(): Boolean = peerConnection != null

    fun setTurnConfig(config: TurnServerConfig) {
        turnConfig = config
    }

    @Synchronized
    private fun ensureEglBase(): EglBase {
        eglBase?.let { return it }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            var initError: Throwable? = null
            mainHandler.post {
                try {
                    eglBase = EglBase.create()
                } catch (error: Throwable) {
                    initError = error
                } finally {
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
            initError?.let { throw it }
        } else {
            eglBase = EglBase.create()
        }
        return eglBase ?: throw IllegalStateException("EGL context creation failed")
    }

    @Synchronized
    fun ensureInitialized() {
        if (factory != null) return
        val egl = ensureEglBase()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val isEmulator = DeviceHints.isProbablyEmulator()
        val encoder = DefaultVideoEncoderFactory(egl.eglBaseContext, !isEmulator, !isEmulator)
        val decoder = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        Timber.i("PeerConnectionFactory ready (emulator=%s)", isEmulator)
    }

    fun initRenderer(renderer: SurfaceViewRenderer) {
        ensureInitialized()
        renderer.init(ensureEglBase().eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
    }

    @Synchronized
    fun createPeerConnection(
        isController: Boolean,
        listeners: WebRtcListeners,
        force: Boolean = false
    ): Boolean {
        ensureInitialized()
        if (peerConnection != null && !force) {
            this.listeners = listeners
            return true
        }
        closePeerOnly()
        this.listeners = listeners

        val iceServers = buildIceServers()
        val isEmulator = DeviceHints.isProbablyEmulator()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            if (isEmulator) {
                iceCandidatePoolSize = 4
            }
        }

        val pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Timber.i("ICE connection state -> %s (emulator=%s)", newState, isEmulator)
                mainHandler.post { listeners.onIceConnectionChange(newState) }
            }
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
        if (pc == null) {
            Timber.e(
                "WEBRTC createPeerConnection returned null (controller=%s emulator=%s)",
                isController,
                DeviceHints.isProbablyEmulator()
            )
            peerConnection = null
            return false
        }
        peerConnection = pc

        if (isController) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiverInit(RtpTransceiverDirection.RECV_ONLY)
            )
            dataChannel = pc.createDataChannel(
                "control",
                DataChannel.Init().apply { ordered = true }
            )
            dataChannel?.let { attachDataChannel(it) }
        }
        return true
    }

    /** Recreates the peer while keeping an active screen-share track attached (renegotiation). */
    @Synchronized
    fun recreatePeerForRenegotiation(
        isController: Boolean,
        listeners: WebRtcListeners
    ): Boolean {
        val hadMedia = mediaRunning.get()
        val track = localVideoTrack
        if (!createPeerConnection(isController, listeners, force = true)) return false
        if (hadMedia && track != null) {
            val pc = peerConnection ?: return false
            pc.addTrack(track, listOf("AH_STREAM"))
        }
        return true
    }

    /**
     * Creates the peer (if needed) and starts screen capture in one synchronized step
     * so another thread cannot clear the peer between the two operations.
     */
    @Synchronized
    fun prepareRemoteScreenShare(
        listeners: WebRtcListeners,
        permissionResultData: Intent,
        forceRecreatePeer: Boolean
    ): Result<Unit> {
        val peerReady = if (peerConnection == null || forceRecreatePeer) {
            createPeerConnection(
                isController = false,
                listeners = listeners,
                force = forceRecreatePeer || peerConnection != null
            )
        } else {
            this.listeners = listeners
            true
        }
        if (!peerReady || peerConnection == null) {
            return Result.failure(
                IllegalStateException("WebRTC peer could not be created on this device")
            )
        }
        return startScreenShareSafely(permissionResultData)
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        turnConfig.urls.filter { it.isNotBlank() }.forEach { url ->
            val builder = PeerConnection.IceServer.builder(url)
            if (turnConfig.username.isNotBlank()) {
                builder.setUsername(turnConfig.username)
                builder.setPassword(turnConfig.credential)
            }
            servers.add(builder.createIceServer())
        }
        if (DeviceHints.isProbablyEmulator() && turnConfig.urls.none { it.isNotBlank() }) {
            Timber.i("Using public TURN fallback for emulator/LDPlayer testing")
            listOf(
                "turn:openrelay.metered.ca:80?transport=tcp",
                "turn:openrelay.metered.ca:443?transport=tcp"
            ).forEach { url ->
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername("openrelayproject")
                        .setPassword("openrelayproject")
                        .createIceServer()
                )
            }
        }
        return servers
    }

    /**
     * Starts screen capture safely. Returns failure instead of crashing the process.
     * Requires an active peer connection so the video track can be published.
     */
    @Synchronized
    fun startScreenShareSafely(permissionResultData: Intent): Result<Unit> {
        return try {
            ensureInitialized()
            if (mediaRunning.get()) {
                Result.success(Unit)
            } else {
                val pc = peerConnection
                    ?: throw IllegalStateException("WebRTC peer connection is not ready")
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
                Timber.i(
                    "Screen share started %dx%d@%dfps emulator=%s",
                    w, h, fps, DeviceHints.isProbablyEmulator()
                )
                Result.success(Unit)
            }
        } catch (error: Throwable) {
            Timber.e(error, "startScreenShareSafely failed")
            runCatching { stopScreenShare() }
            Result.failure(error)
        }
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
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", ensureEglBase().eglBaseContext)
        videoSource = factory!!.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer.startCapture(width, height, fps)
        localVideoTrack = factory!!.createVideoTrack("AH_SCREEN", videoSource).apply {
            setEnabled(true)
        }
        pc.addTrack(localVideoTrack, listOf("AH_STREAM"))
        captureWidth = width
        captureHeight = height
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
        captureWidth = 0
        captureHeight = 0
        Timber.tag("SCREEN_CAPTURE").i("Screen share stopped")
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("No peer connection")
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() = onSuccess(sdp)
                    override fun onCreateFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for offer")
                    override fun onSetFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for offer")
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = onError(error ?: "create offer failed")
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("No peer connection")
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() = onSuccess(sdp)
                    override fun onCreateFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for answer")
                    override fun onSetFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for answer")
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = onError(error ?: "create answer failed")
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

    fun setRemoteDescription(
        sdp: SessionDescription,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val pc = peerConnection ?: run {
            onError("No peer connection")
            return
        }
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() = onDone()
            override fun onCreateFailure(error: String?) =
                onError(error ?: "setRemoteDescription create failed")
            override fun onSetFailure(error: String?) =
                onError(error ?: "setRemoteDescription set failed")
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

    fun restartIce(onComplete: () -> Unit = {}) {
        val pc = peerConnection ?: return
        runCatching { pc.restartIce() }
        onComplete()
    }

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
}
