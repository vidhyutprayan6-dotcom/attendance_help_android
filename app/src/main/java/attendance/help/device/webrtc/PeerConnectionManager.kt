package attendance.help.device.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import attendance.help.device.BuildConfig
import attendance.help.device.domain.model.TurnServerConfig
import attendance.help.device.domain.model.WebRtcTransportDiagnostics
import attendance.help.device.utils.DeviceHints
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
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
    /** Remote screen VideoTrack (from Remote MediaProjection). */
    val onRemoteVideoTrack: (VideoTrack) -> Unit = {},
    /** Control front-camera VideoTrack received on Remote. */
    val onRemoteCameraTrack: (VideoTrack) -> Unit = {},
    val onDiagnosticsChanged: (WebRtcTransportDiagnostics) -> Unit = {}
)

/**
 * WebRTC peer for screen-share remote control:
 * - Remote (offerer) publishes screen video and receives control via data channel.
 * - Control (answerer) receives screen and sends touch commands via data channel.
 */
@Singleton
class PeerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var observerGeneration: Int = 0
    private var peerGeneration: Int = 0
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraCapturer: VideoCapturer? = null
    private var cameraSurfaceHelper: SurfaceTextureHelper? = null
    private var cameraSource: VideoSource? = null
    private var localCameraTrack: VideoTrack? = null
    private val cameraRunning = AtomicBoolean(false)
    /** REMOTE local physical camera — never published to PeerConnection. */
    private var remoteLocalCameraCapturer: VideoCapturer? = null
    private var remoteLocalCameraHelper: SurfaceTextureHelper? = null
    private var remoteLocalCameraSource: VideoSource? = null
    private val remoteLocalCameraRunning = AtomicBoolean(false)
    private var controlFirstFrameSink: VideoSink? = null
    private var dataChannel: DataChannel? = null
    private var listeners: WebRtcListeners? = null
    private val mediaRunning = AtomicBoolean(false)
    private var sharingScreen = false
    private var turnConfig: TurnServerConfig = TurnServerConfig()
    private var remoteDescriptionSet = false
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var statsPolling = false
    /** True when this device is CONTROL (answerer). Used to route inbound tracks. */
    private var isControlRole: Boolean = false

    val diagnostics = WebRtcDiagnostics()

    var captureWidth: Int = 0
        private set
    var captureHeight: Int = 0
        private set

    @Synchronized
    fun hasPeerConnection(): Boolean = peerConnection != null

    fun currentPeerGeneration(): Int = peerGeneration

    fun diagnosticsSnapshot(): WebRtcTransportDiagnostics = diagnostics.snapshot()

    fun setTurnConfig(config: TurnServerConfig) {
        turnConfig = config
        val hubHasTurn = config.urls.any { it.isNotBlank() }
        diagnostics.turnConfigured = hubHasTurn || BuildConfig.DEBUG
        diagnostics.log(
            "TURN_CONFIG_FROM_HUB urls=${config.urls.size} userBlank=${config.username.isBlank()} " +
                "willUseFallback=${!hubHasTurn}"
        )
    }

    fun updateDiagnosticsContext(
        localDeviceId: String,
        remoteDeviceId: String,
        sessionId: String,
        role: String
    ) {
        diagnostics.localDeviceId = localDeviceId
        diagnostics.remoteDeviceId = remoteDeviceId
        diagnostics.sessionId = sessionId
        diagnostics.role = role
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
        diagnostics.log("PeerConnectionFactory ready emulator=$isEmulator")
    }

    fun initRenderer(renderer: SurfaceViewRenderer, mirror: Boolean = false) {
        ensureInitialized()
        renderer.init(ensureEglBase().eglBaseContext, null)
        renderer.setMirror(mirror)
        renderer.setEnableHardwareScaler(true)
    }

    /**
     * Creates exactly one [PeerConnection] per session generation.
     * @param isControlDevice true for Control (answerer), false for Remote (offerer).
     */
    @Synchronized
    fun createPeerConnection(
        isControlDevice: Boolean,
        listeners: WebRtcListeners,
        force: Boolean = false
    ): Boolean {
        ensureInitialized()
        if (peerConnection != null && !force) {
            this.listeners = listeners
            notifyDiagnostics()
            return true
        }
        disposeControlCameraSenderFully()
        stopRemoteLocalCameraOnly()
        closePeerOnly()
        peerGeneration++
        observerGeneration = peerGeneration
        diagnostics.resetForNewPeer(peerGeneration)
        diagnostics.forceRelayOnly = BuildConfig.FORCE_RELAY_ONLY
        this.listeners = listeners

        val iceServers = buildIceServers()
        val isEmulator = DeviceHints.isProbablyEmulator()
        val forceRelayOnly = BuildConfig.FORCE_RELAY_ONLY
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (forceRelayOnly) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            if (isEmulator) {
                iceCandidatePoolSize = 4
            }
        }

        val genAtCreate = observerGeneration
        val pc = factory!!.createPeerConnection(rtcConfig, createObserver(genAtCreate, isEmulator))
        if (pc == null) {
            diagnostics.log("WEBRTC createPeerConnection returned null control=$isControlDevice")
            peerConnection = null
            return false
        }
        peerConnection = pc
        this.isControlRole = isControlDevice
        remoteDescriptionSet = false
        pendingRemoteIceCandidates.clear()
        diagnostics.log("WEBRTC createPeerConnection generation=$peerGeneration control=$isControlDevice relayOnly=$forceRelayOnly", pc)

        if (isControlDevice) {
            // m-line #1 slot: receive REMOTE screen
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiverInit(RtpTransceiverDirection.RECV_ONLY)
            )
        } else {
            dataChannel = pc.createDataChannel(
                "control",
                DataChannel.Init().apply {
                    ordered = true
                    negotiated = false
                }
            )
            dataChannel?.let { attachDataChannel(it) }
            diagnostics.log("DATACHANNEL_CREATED label=control", pc)
            // m-line #2 slot prepared later before offer: receive CONTROL camera
        }
        notifyDiagnostics()
        return true
    }

    private fun createObserver(generation: Int, isEmulator: Boolean): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            private fun alive(): Boolean = generation == observerGeneration && peerConnection != null
            private fun pc(): PeerConnection? = if (alive()) peerConnection else null

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                if (!alive()) return
                diagnostics.signalingState = newState
                diagnostics.log("SIGNALING_CHANGE state=$newState", pc())
                notifyDiagnostics()
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (!alive()) return
                diagnostics.iceConnectionState = newState
                diagnostics.log("ICE_CONNECTION_CHANGE state=$newState emulator=$isEmulator", pc())
                mainHandler.post { listeners?.onIceConnectionChange(newState) }
                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    diagnostics.logIceFailureReport(pc())
                }
                notifyDiagnostics()
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (!alive()) return
                diagnostics.log("STD_ICE_CONNECTION_CHANGE state=$newState", pc())
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (!alive()) return
                diagnostics.iceGatheringState = newState
                diagnostics.log("ICE_GATHERING_CHANGE state=$newState", pc())
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    diagnostics.logLocalIceSummary(pc())
                }
                notifyDiagnostics()
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                if (!alive()) return
                diagnostics.recordLocalCandidate(candidate, pc())
                listeners?.onIceCandidate(candidate)
                notifyDiagnostics()
            }

            override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                if (!alive()) return
                // STUN 701 timeouts are common and non-fatal when ICE already connected via host/srflx.
                val connected = diagnostics.connectionState ==
                    PeerConnection.PeerConnectionState.CONNECTED ||
                    diagnostics.iceConnectionState == PeerConnection.IceConnectionState.CONNECTED ||
                    diagnostics.iceConnectionState == PeerConnection.IceConnectionState.COMPLETED
                if (connected && event.errorCode == 701) {
                    Timber.tag("WEBRTC_DIAG").d(
                        "Ignoring non-fatal STUN timeout while connected: %s",
                        event.url
                    )
                    return
                }
                diagnostics.logIceCandidateError(event, pc())
                notifyDiagnostics()
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(dc: DataChannel) {
                if (!alive()) return
                diagnostics.log("DATACHANNEL_RECEIVED label=${dc.label()}", pc())
                attachDataChannel(dc)
                notifyDiagnostics()
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                if (!alive()) return
                val track = receiver.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    val id = track.id().orEmpty()
                    val streamIds = mediaStreams.mapNotNull { it.id }.joinToString(",")
                    // Role-based routing (stable): Control only receives screen; Remote only receives camera.
                    if (isControlRole) {
                        diagnostics.remoteVideoReceived = true
                        diagnostics.log("REMOTE_SCREEN_TRACK_RECEIVED id=$id streams=$streamIds", pc())
                        mainHandler.post { listeners?.onRemoteVideoTrack(track) }
                    } else {
                        diagnostics.log("CONTROL_CAMERA_TRACK_RECEIVED id=$id streams=$streamIds", pc())
                        Timber.tag("CAMERA_WEBRTC").i("REMOTE_CONTROL_CAMERA_TRACK_RECEIVED id=%s", id)
                        mainHandler.post { listeners?.onRemoteCameraTrack(track) }
                    }
                    notifyDiagnostics()
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (!alive()) return
                diagnostics.connectionState = newState
                diagnostics.log("CONNECTION_CHANGE state=$newState", pc())
                mainHandler.post { listeners?.onConnectionChange(newState) }
                if (newState == PeerConnection.PeerConnectionState.CONNECTED && BuildConfig.DEBUG) {
                    scheduleStatsSample(pc())
                }
                notifyDiagnostics()
            }
        }
    }

    @Synchronized
    fun prepareRemoteScreenShare(
        listeners: WebRtcListeners,
        permissionResultData: Intent,
        forceRecreatePeer: Boolean
    ): Result<Unit> {
        val peerReady = if (peerConnection == null || forceRecreatePeer) {
            createPeerConnection(
                isControlDevice = false,
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

    /**
     * Tear down peer for ICE reconnect without stopping screen capture.
     * Caller recreates peer and calls [reattachScreenTrackToPeer].
     */
    @Synchronized
    fun teardownPeerForReconnect() {
        disposeControlCameraSenderFully()
        stopRemoteLocalCameraOnly()
        closePeerOnly()
        remoteDescriptionSet = false
        pendingRemoteIceCandidates.clear()
        diagnostics.queuedRemoteCandidates = 0
    }

    @Synchronized
    fun reattachScreenTrackToPeer(): Boolean {
        val pc = peerConnection ?: return false
        val track = localVideoTrack ?: return false
        if (!mediaRunning.get()) return false
        return runCatching {
            pc.addTrack(track, listOf("AH_STREAM"))
            diagnostics.log("SCREEN_TRACK_REATTACHED", pc)
            true
        }.getOrElse {
            diagnostics.log("SCREEN_TRACK_REATTACH_FAILED error=${it.message}", pc)
            false
        }
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        var turnFromHub = false
        turnConfig.urls.filter { it.isNotBlank() }.forEach { url ->
            val builder = PeerConnection.IceServer.builder(url)
            if (turnConfig.username.isNotBlank()) {
                builder.setUsername(turnConfig.username)
                builder.setPassword(turnConfig.credential)
            }
            servers.add(builder.createIceServer())
            turnFromHub = true
        }
        // Always fall back to OpenRelay when hub did not provide TURN.
        // LDPlayer often fails STUN (error 701) and needs relay candidates.
        // Debug builds always ensure TURN; release builds also fall back if hub TURN is empty
        // so two devices behind NAT/emulator can still connect during early testing.
        if (!turnFromHub) {
            diagnostics.log(
                "Using public TURN fallback (hubTurnEmpty=true emulator=${DeviceHints.isProbablyEmulator()})"
            )
            listOf(
                "turn:openrelay.metered.ca:80",
                "turn:openrelay.metered.ca:443",
                "turn:openrelay.metered.ca:443?transport=tcp"
            ).forEach { url ->
                servers.add(
                    PeerConnection.IceServer.builder(url)
                        .setUsername("openrelayproject")
                        .setPassword("openrelayproject")
                        .createIceServer()
                )
            }
            servers.add(
                PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer()
            )
        }
        diagnostics.turnConfigured = servers.any { s ->
            s.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
        }
        diagnostics.log(
            "ICE_SERVERS count=${servers.size} turnConfigured=${diagnostics.turnConfigured} turnFromHub=$turnFromHub"
        )
        return servers
    }

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
                diagnostics.captureActive = true
                diagnostics.log("CAPTURE_ACTIVE ${w}x${h}@${fps}fps", pc)
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
        diagnostics.captureActive = false
        Timber.tag("SCREEN_CAPTURE").i("Screen share stopped")
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("No peer connection")
        diagnostics.log("WEBRTC createOffer", pc)
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        diagnostics.localDescriptionSet = true
                        diagnostics.log("WEBRTC setLocalOffer success", pc)
                        onSuccess(sdp)
                    }
                    override fun onCreateFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for offer")
                    override fun onSetFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for offer")
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = onError(error ?: "create offer failed")
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("No peer connection")
        diagnostics.log("WEBRTC createAnswer", pc)
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        diagnostics.localDescriptionSet = true
                        diagnostics.log("WEBRTC setLocalAnswer success", pc)
                        onSuccess(sdp)
                    }
                    override fun onCreateFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for answer")
                    override fun onSetFailure(error: String?) =
                        onError(error ?: "setLocalDescription failed for answer")
                }, sdp)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = onError(error ?: "create answer failed")
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
    }

    fun signalingState(): PeerConnection.SignalingState? = peerConnection?.signalingState()

    fun applyRemoteOffer(
        sdp: SessionDescription,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (peerConnection == null) {
            onError("No peer connection")
            return
        }
        diagnostics.log("WEBRTC receiveOffer", peerConnection)
        setRemoteDescription(sdp, onDone, onError)
    }

    fun applyRemoteAnswer(
        sdp: SessionDescription,
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        val pc = peerConnection ?: run {
            onError("No peer connection")
            return false
        }
        return when (pc.signalingState()) {
            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> {
                diagnostics.log("WEBRTC receiveAnswer", pc)
                setRemoteDescription(sdp, onDone, onError)
                true
            }
            PeerConnection.SignalingState.STABLE -> {
                diagnostics.log("Ignoring unexpected/duplicate answer state=${pc.signalingState()}", pc)
                false
            }
            else -> {
                val msg = "Cannot apply answer in signaling state ${pc.signalingState()}"
                diagnostics.log(msg, pc)
                onError(msg)
                false
            }
        }
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
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                diagnostics.remoteDescriptionSet = true
                val label = if (sdp.type == SessionDescription.Type.OFFER) {
                    "WEBRTC setRemoteOffer success"
                } else {
                    "WEBRTC setRemoteAnswer success"
                }
                diagnostics.log(label, pc)
                flushPendingRemoteIceCandidates()
                onDone()
            }
            override fun onCreateFailure(error: String?) =
                onError(error ?: "setRemoteDescription create failed")
            override fun onSetFailure(error: String?) =
                onError(error ?: "setRemoteDescription set failed")
        }, sdp)
    }

    /**
     * Adds a remote ICE candidate, queueing until remote description is applied.
     */
    fun addRemoteIceCandidate(candidate: IceCandidate): Boolean {
        val pc = peerConnection ?: return false
        diagnostics.recordRemoteCandidate(candidate, pc)
        if (!remoteDescriptionSet) {
            pendingRemoteIceCandidates.add(candidate)
            diagnostics.queuedRemoteCandidates = pendingRemoteIceCandidates.size
            diagnostics.log("REMOTE_CANDIDATE_QUEUED queued=${pendingRemoteIceCandidates.size}", pc)
            notifyDiagnostics()
            return false
        }
        return applyRemoteIceCandidate(candidate)
    }

    private fun flushPendingRemoteIceCandidates() {
        if (pendingRemoteIceCandidates.isEmpty()) return
        val copy = pendingRemoteIceCandidates.toList()
        pendingRemoteIceCandidates.clear()
        diagnostics.queuedRemoteCandidates = 0
        copy.forEach { applyRemoteIceCandidate(it) }
        diagnostics.logRemoteIceSummary(peerConnection)
        notifyDiagnostics()
    }

    private fun applyRemoteIceCandidate(candidate: IceCandidate): Boolean {
        val pc = peerConnection ?: return false
        return runCatching {
            val ok = pc.addIceCandidate(candidate)
            if (ok) {
                diagnostics.appliedRemoteCandidates++
                diagnostics.log("REMOTE_CANDIDATE_ADD_SUCCESS applied=${diagnostics.appliedRemoteCandidates}", pc)
            } else {
                diagnostics.failedAddCandidateCalls++
                diagnostics.log("REMOTE_CANDIDATE_ADD_FAILURE applied=${diagnostics.appliedRemoteCandidates}", pc)
            }
            notifyDiagnostics()
            ok
        }.getOrElse {
            diagnostics.failedAddCandidateCalls++
            diagnostics.log("REMOTE_CANDIDATE_ADD_FAILURE error=${it.message}", pc)
            notifyDiagnostics()
            false
        }
    }

    /** @deprecated use [addRemoteIceCandidate] */
    fun addIceCandidate(candidate: IceCandidate): Boolean = addRemoteIceCandidate(candidate)

    fun sendData(message: String) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false)
        channel.send(buffer)
    }

    fun isScreenSharing(): Boolean = sharingScreen && mediaRunning.get()
    fun isLocalMediaPublishing(): Boolean = mediaRunning.get()
    fun isDataChannelOpen(): Boolean = dataChannel?.state() == DataChannel.State.OPEN
    fun isCameraRunning(): Boolean = cameraRunning.get()
    fun hasControlCameraSenderTrack(): Boolean = localCameraTrack != null
    fun isRemoteLocalCameraOpen(): Boolean = remoteLocalCameraRunning.get()

    /**
     * REMOTE: add RECV_ONLY video transceiver for CONTROL camera before createOffer.
     * Negotiates camera m-line in the initial SDP — no later renegotiation.
     */
    @Synchronized
    fun ensureRemoteCameraReceiverSlot() {
        val pc = peerConnection ?: return
        if (isControlRole) return
        val videoCount = pc.transceivers.count {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }
        if (videoCount < 2) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiverInit(RtpTransceiverDirection.RECV_ONLY)
            )
            diagnostics.log("REMOTE_CAMERA_RECV_TRANSCEIVER_ADDED count=${pc.transceivers.size}", pc)
            Timber.tag("CAMERA_WEBRTC").i("REMOTE camera RECV transceiver prepared videoCount=%d", videoCount + 1)
        }
    }

    /**
     * CONTROL: create AH_CAMERA VideoTrack (no capture yet) and addTrack before createAnswer
     * so the answer includes the camera send m-line.
     */
    @Synchronized
    fun ensureControlCameraSenderReady(): Boolean {
        val pc = peerConnection ?: return false
        if (!isControlRole) return false
        if (localCameraTrack != null) return true
        return try {
            ensureInitialized()
            if (cameraSource == null) {
                cameraSource = factory!!.createVideoSource(false)
            }
            localCameraTrack = factory!!.createVideoTrack("AH_CAMERA", cameraSource).apply {
                setEnabled(true)
            }
            pc.addTrack(localCameraTrack, listOf("AH_CAMERA_STREAM"))
            diagnostics.log("CONTROL_CAMERA_SENDER_TRACK_READY", pc)
            Timber.tag("CAMERA_WEBRTC").i("CONTROL_CAMERA_TRACK_SENT placeholder capturer=off")
            true
        } catch (error: Throwable) {
            Timber.tag("CAMERA_CAPTURE").e(error, "ensureControlCameraSenderReady failed")
            false
        }
    }

    /**
     * Starts physical camera capture into the already-negotiated CONTROL camera track.
     * Does NOT renegotiate SDP.
     */
    @Synchronized
    fun startControlCameraCapture(localPreview: VideoSink? = null): Result<Unit> {
        return try {
            ensureInitialized()
            if (!ensureControlCameraSenderReady()) {
                return Result.failure(
                    IllegalStateException(
                        "Camera sender track missing — reconnect session so CONTROL camera m-line is negotiated"
                    )
                )
            }
            if (cameraRunning.get()) {
                localPreview?.let { localCameraTrack?.addSink(it) }
                return Result.success(Unit)
            }
            val (enumerator, deviceName) = selectCameraDevice()
                ?: return Result.failure(
                    IllegalStateException(
                        if (DeviceHints.isProbablyEmulator()) {
                            "EMULATOR_CAMERA_NOT_AVAILABLE — enable webcam in LDPlayer settings"
                        } else {
                            "CAMERA_UNAVAILABLE"
                        }
                    )
                )
            Timber.tag("CAMERA_CAPTURE").i("Using camera device=%s", deviceName)
            val capturer = enumerator.createCapturer(deviceName, null)
                ?: return Result.failure(IllegalStateException("CAMERA_UNAVAILABLE"))
            cameraCapturer = capturer
            if (cameraSurfaceHelper == null) {
                cameraSurfaceHelper = SurfaceTextureHelper.create(
                    "CameraCaptureThread",
                    ensureEglBase().eglBaseContext
                )
            }
            val source = cameraSource
                ?: return Result.failure(IllegalStateException("Camera VideoSource missing"))
            capturer.initialize(cameraSurfaceHelper, context, source.capturerObserver)
            val (w, h, fps) = if (DeviceHints.isProbablyEmulator()) {
                Triple(640, 480, 15)
            } else {
                Triple(1280, 720, 24)
            }
            capturer.startCapture(w, h, fps)
            localPreview?.let { sink -> localCameraTrack?.addSink(sink) }
            attachControlFirstFrameProbe()
            cameraRunning.set(true)
            Timber.tag("CAMERA_CAPTURE").i("CONTROL_CAMERA_CAPTURER_START %dx%d@%d", w, h, fps)
            diagnostics.log("CONTROL_CAMERA_CAPTURER_START", peerConnection)
            Result.success(Unit)
        } catch (error: Throwable) {
            Timber.tag("CAMERA_CAPTURE").e(error, "startControlCameraCapture failed")
            runCatching { stopControlCameraCapture() }
            Result.failure(error)
        }
    }

    /** Stops capture but keeps negotiated AH_CAMERA track (no SDP change). */
    @Synchronized
    fun stopControlCameraCapture() {
        controlFirstFrameSink?.let { sink ->
            runCatching { localCameraTrack?.removeSink(sink) }
        }
        controlFirstFrameSink = null
        if (!cameraRunning.getAndSet(false) && cameraCapturer == null) return
        runCatching { (cameraCapturer as? CameraVideoCapturer)?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        cameraCapturer = null
        Timber.tag("CAMERA_CAPTURE").i("CONTROL_CAMERA_CAPTURER_STOP")
        diagnostics.log("CONTROL_CAMERA_CAPTURER_STOP", peerConnection)
    }

    @Synchronized
    fun disposeControlCameraSenderFully() {
        stopControlCameraCapture()
        localCameraTrack?.dispose()
        localCameraTrack = null
        cameraSource?.dispose()
        cameraSource = null
        cameraSurfaceHelper?.dispose()
        cameraSurfaceHelper = null
    }

    /**
     * REMOTE only: open local physical camera without publishing.
     * Does NOT addTrack to PeerConnection.
     */
    @Synchronized
    fun startRemoteLocalCameraOnly(): Result<Unit> {
        if (isControlRole) return Result.success(Unit)
        return try {
            ensureInitialized()
            if (remoteLocalCameraRunning.get()) return Result.success(Unit)
            val (enumerator, deviceName) = selectCameraDevice()
                ?: return Result.failure(IllegalStateException("EMULATOR_CAMERA_NOT_AVAILABLE or CAMERA_UNAVAILABLE"))
            val capturer = enumerator.createCapturer(deviceName, null)
                ?: return Result.failure(IllegalStateException("CAMERA_UNAVAILABLE"))
            remoteLocalCameraCapturer = capturer
            remoteLocalCameraHelper = SurfaceTextureHelper.create(
                "RemoteLocalCamera",
                ensureEglBase().eglBaseContext
            )
            remoteLocalCameraSource = factory!!.createVideoSource(false)
            capturer.initialize(
                remoteLocalCameraHelper,
                context,
                remoteLocalCameraSource!!.capturerObserver
            )
            capturer.startCapture(640, 480, 15)
            remoteLocalCameraRunning.set(true)
            Timber.tag("CAMERA_CAPTURE").i("REMOTE_CAMERA_OPENED_LOCAL_ONLY device=%s", deviceName)
            Result.success(Unit)
        } catch (error: Throwable) {
            Timber.tag("CAMERA_CAPTURE").e(error, "startRemoteLocalCameraOnly failed")
            runCatching { stopRemoteLocalCameraOnly() }
            Result.failure(error)
        }
    }

    @Synchronized
    fun stopRemoteLocalCameraOnly() {
        if (!remoteLocalCameraRunning.getAndSet(false) && remoteLocalCameraCapturer == null) return
        runCatching { (remoteLocalCameraCapturer as? CameraVideoCapturer)?.stopCapture() }
        runCatching { remoteLocalCameraCapturer?.dispose() }
        remoteLocalCameraCapturer = null
        remoteLocalCameraSource?.dispose()
        remoteLocalCameraSource = null
        remoteLocalCameraHelper?.dispose()
        remoteLocalCameraHelper = null
        Timber.tag("CAMERA_CAPTURE").i("REMOTE_CAMERA_LOCAL_ONLY_STOPPED")
    }

    private fun attachControlFirstFrameProbe() {
        controlFirstFrameSink?.let { runCatching { localCameraTrack?.removeSink(it) } }
        val probe = object : VideoSink {
            private val seen = AtomicBoolean(false)
            override fun onFrame(frame: VideoFrame) {
                if (seen.compareAndSet(false, true)) {
                    Timber.tag("CAMERA_CAPTURE").i("CONTROL_CAMERA_FIRST_FRAME")
                    diagnostics.log("CONTROL_CAMERA_FIRST_FRAME", peerConnection)
                }
            }
        }
        controlFirstFrameSink = probe
        localCameraTrack?.addSink(probe)
    }

    private fun selectCameraDevice(): Pair<CameraEnumerator, String>? {
        val camera2 = Camera2Enumerator(context)
        val names2 = camera2.deviceNames.toList()
        Timber.tag("CAMERA_CAPTURE").i(
            "Camera2 devices=%s front=%s",
            names2,
            names2.map { "$it front=${camera2.isFrontFacing(it)} back=${camera2.isBackFacing(it)}" }
        )
        pickPreferredCameraName(camera2, names2)?.let { return camera2 to it }

        val camera1 = Camera1Enumerator(true)
        val names1 = camera1.deviceNames.toList()
        Timber.tag("CAMERA_CAPTURE").i("Camera1 fallback devices=%s", names1)
        pickPreferredCameraName(camera1, names1)?.let { return camera1 to it }
        return null
    }

    private fun pickPreferredCameraName(enumerator: CameraEnumerator, names: List<String>): String? {
        if (names.isEmpty()) return null
        return names.firstOrNull { name ->
            enumerator.isFrontFacing(name) &&
                !name.contains("virtual", ignoreCase = true) &&
                !name.contains("scene", ignoreCase = true)
        } ?: names.firstOrNull { enumerator.isFrontFacing(it) }
            ?: names.firstOrNull {
                !it.contains("virtual", ignoreCase = true) &&
                    !it.contains("scene", ignoreCase = true)
            }
            ?: names.firstOrNull()
    }

    @Synchronized
    fun attachLocalCameraPreview(sink: VideoSink) {
        localCameraTrack?.addSink(sink)
        Timber.tag("CAMERA_CAPTURE").i("CONTROL_CAMERA_LOCAL_RENDER sinkAttached=true")
    }

    @Synchronized
    fun detachLocalCameraPreview(sink: VideoSink) {
        runCatching { localCameraTrack?.removeSink(sink) }
    }

    /** Compatibility wrapper — CONTROL publishes; REMOTE opens local-only. */
    fun startFrontCamera(publishToPeer: Boolean, localPreview: VideoSink? = null): Result<Unit> {
        return if (publishToPeer || isControlRole) {
            startControlCameraCapture(localPreview)
        } else {
            startRemoteLocalCameraOnly()
        }
    }

    fun stopFrontCamera() {
        stopControlCameraCapture()
        stopRemoteLocalCameraOnly()
    }

    @Synchronized
    fun release() {
        disposeControlCameraSenderFully()
        stopRemoteLocalCameraOnly()
        stopScreenShare()
        closePeerOnly()
        peerGeneration++
        observerGeneration = peerGeneration
    }

    private fun closePeerOnly() {
        statsPolling = false
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        remoteDescriptionSet = false
        pendingRemoteIceCandidates.clear()
        diagnostics.queuedRemoteCandidates = 0
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                val state = channel.state()
                diagnostics.dataChannelState = state.name
                diagnostics.log("DATACHANNEL_${state.name}", peerConnection)
                notifyDiagnostics()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val text = String(data, Charsets.UTF_8)
                mainHandler.post { listeners?.onDataMessage(text) }
            }
        })
        diagnostics.dataChannelState = channel.state().name
    }

    private fun notifyDiagnostics() {
        mainHandler.post {
            listeners?.onDiagnosticsChanged(diagnostics.snapshot())
        }
    }

    private fun scheduleStatsSample(pc: PeerConnection?) {
        if (!BuildConfig.DEBUG || pc == null || statsPolling) return
        statsPolling = true
        pc.getStats(object : RTCStatsCollectorCallback {
            override fun onStatsDelivered(report: RTCStatsReport?) {
                statsPolling = false
                if (report == null || observerGeneration != peerGeneration) return
                val pair = report.statsMap.entries.firstOrNull {
                    it.value.type == "candidate-pair" &&
                        (it.value.members["selected"] as? Boolean) == true
                }
                if (pair != null) {
                    diagnostics.log(
                        "GETSTATS selectedPair=${pair.key} bytesSent=${pair.value.members["bytesSent"]} bytesReceived=${pair.value.members["bytesReceived"]}",
                        pc
                    )
                }
            }
        })
    }
}
