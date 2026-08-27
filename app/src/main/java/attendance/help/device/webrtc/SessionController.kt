package attendance.help.device.webrtc

import attendance.help.device.BuildConfig
import attendance.help.device.camera.LocalCameraSource
import attendance.help.device.camera.RemoteVideoSource
import attendance.help.device.data.local.PeerDao
import attendance.help.device.data.local.PeerEntity
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.device.command.CloseCameraCommand
import attendance.help.device.device.command.CommandParser
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.device.command.OpenCameraCommand
import attendance.help.device.device.command.PingCommand
import attendance.help.device.device.command.PongCommand
import attendance.help.device.device.command.StatusCommand
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.DualCameraSessionState
import attendance.help.device.domain.model.PeerDevice
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.network.NetworkManager
import attendance.help.device.network.signaling.SignalingClient
import attendance.help.device.network.signaling.SignalingCodec
import attendance.help.device.network.signaling.SignalingMessage
import attendance.help.device.network.signaling.SignalingServer
import attendance.help.device.utils.PairingCodeGenerator
import attendance.help.device.utils.TailscaleIpFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class LiveSessionUi(
    val localIp: String? = null,
    val pairingCode: String? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_PAIRED,
    val peer: PeerDevice? = null,
    val dualCamera: DualCameraSessionState = DualCameraSessionState(),
    val statusMessage: String = "",
    val lastError: String? = null,
    val webrtcState: String = "NEW"
)

@Singleton
class SessionController @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val networkManager: NetworkManager,
    private val peerConnectionManager: PeerConnectionManager,
    private val localCameraSource: LocalCameraSource,
    private val remoteVideoSource: RemoteVideoSource,
    private val peerDao: PeerDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val codec = SignalingCodec()

    private var signalingServer: SignalingServer? = null
    private var signalingClient: SignalingClient? = null
    private var role: DeviceRole? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val _ui = MutableStateFlow(LiveSessionUi())
    val uiState: StateFlow<LiveSessionUi> = _ui.asStateFlow()

    private val localDeviceId: String by lazy { deviceIdentityProvider.getOrCreateDeviceId() }

    fun bindRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        local?.let {
            peerConnectionManager.initLocalRenderer(it)
            if (role == DeviceRole.CONTROLLER && peerConnectionManager.isCameraRunning()) {
                peerConnectionManager.attachLocalVideoTo(it)
            }
        }
        remote?.let {
            peerConnectionManager.initRemoteRenderer(it)
            remoteVideoTrack?.addSink(it)
        }
    }

    fun unbindRenderers() {
        localRenderer?.let { peerConnectionManager.detachLocalVideoFrom(it) }
        remoteVideoTrack?.let { track ->
            remoteRenderer?.let { track.removeSink(it) }
        }
        localRenderer = null
        remoteRenderer = null
    }

    suspend fun prepareAsController() {
        role = DeviceRole.CONTROLLER
        val ip = TailscaleIpFinder.findPreferredIp()
        val code = sessionRepository.pairingCode.first() ?: PairingCodeGenerator.generate6Digit()
        sessionRepository.setPairingCode(code)
        sessionRepository.setConnectionState(ConnectionState.WAITING_FOR_PEER)
        _ui.value = _ui.value.copy(
            localIp = ip,
            pairingCode = code,
            connectionState = ConnectionState.WAITING_FOR_PEER,
            statusMessage = "Waiting for Remote to connect…"
        )
        startSignalingServer(code)
    }

    suspend fun connectAsRemote(controllerIp: String, pairingCode: String) {
        role = DeviceRole.REMOTE
        sessionRepository.setConnectionState(ConnectionState.CONNECTING)
        _ui.value = _ui.value.copy(
            connectionState = ConnectionState.CONNECTING,
            statusMessage = "Connecting to $controllerIp…",
            lastError = null
        )
        val client = SignalingClient(
            codec = codec,
            onMessage = { handleSignaling(it) },
            onOpen = {
                scope.launch {
                    sessionRepository.setConnectionState(ConnectionState.PAIRING)
                    clientSend(
                        SignalingMessage.Hello(
                            deviceId = localDeviceId,
                            role = DeviceRole.REMOTE.name,
                            pairingCode = pairingCode,
                            displayName = "Remote"
                        )
                    )
                }
            },
            onClosed = { reason ->
                scope.launch {
                    sessionRepository.setConnectionState(ConnectionState.PAIRED_DISCONNECTED)
                    updateUi {
                        copy(
                            connectionState = ConnectionState.PAIRED_DISCONNECTED,
                            statusMessage = "Disconnected: $reason"
                        )
                    }
                }
            },
            onFailure = { error ->
                scope.launch {
                    sessionRepository.setConnectionState(ConnectionState.ERROR)
                    sessionRepository.setLastError(error.message)
                    updateUi {
                        copy(
                            connectionState = ConnectionState.ERROR,
                            lastError = error.message ?: "Connection failed",
                            statusMessage = "Connection failed"
                        )
                    }
                }
            }
        )
        signalingClient = client
        client.connect(networkManager.signalingWsUrl(controllerIp.trim()))
    }

    fun openDualCamera() {
        scope.launch {
            if (role != DeviceRole.CONTROLLER) {
                updateUi { copy(statusMessage = "Only Controller can start dual camera") }
                return@launch
            }
            ensurePeerForMedia(isController = true)
            startDualCameraLocally()
            serverOrClientSend(SignalingMessage.CameraStart(localDeviceId))
            peerConnectionManager.sendData(OpenCameraCommand(localDeviceId).toPayload())
            peerConnectionManager.createOffer(
                onSuccess = { sdp ->
                    serverOrClientSend(SignalingMessage.Offer(sdp.description))
                },
                onError = { err ->
                    scope.launch {
                        sessionRepository.setLastError(err)
                        updateUi { copy(lastError = err) }
                    }
                }
            )
        }
    }

    fun closeDualCamera() {
        scope.launch {
            serverOrClientSend(SignalingMessage.CameraStop(localDeviceId))
            peerConnectionManager.sendData(CloseCameraCommand(localDeviceId).toPayload())
            stopDualCameraLocally()
            peerConnectionManager.release()
            peerCreated = false
        }
    }

    fun sendPing() {
        peerConnectionManager.sendData(PingCommand(localDeviceId).toPayload())
    }

    suspend fun disconnect() {
        serverOrClientSend(SignalingMessage.Hangup())
        stopDualCameraLocally()
        signalingClient?.disconnect()
        signalingClient = null
        runCatching { signalingServer?.stop() }
        signalingServer = null
        peerConnectionManager.release()
        peerCreated = false
        sessionRepository.setConnectionState(ConnectionState.PAIRED_DISCONNECTED)
        updateUi {
            copy(
                connectionState = ConnectionState.PAIRED_DISCONNECTED,
                dualCamera = DualCameraSessionState(),
                statusMessage = "Disconnected",
                webrtcState = "CLOSED"
            )
        }
    }

    private fun startSignalingServer(expectedCode: String) {
        runCatching { signalingServer?.stop() }
        val server = SignalingServer(
            port = BuildConfig.SIGNALING_PORT,
            codec = codec,
            onMessage = { handleSignaling(it) },
            onClientConnected = {
                scope.launch {
                    updateUi { copy(statusMessage = "Remote socket connected — verifying…") }
                }
            },
            onClientDisconnected = {
                scope.launch {
                    sessionRepository.setConnectionState(ConnectionState.PAIRED_DISCONNECTED)
                    updateUi {
                        copy(
                            connectionState = ConnectionState.PAIRED_DISCONNECTED,
                            statusMessage = "Remote disconnected"
                        )
                    }
                }
            },
            expectedPairingCode = { expectedCode }
        )
        signalingServer = server
        Thread {
            runCatching { server.start() }
                .onFailure { Timber.e(it, "Failed to start signaling server") }
        }.start()
    }

    private fun handleSignaling(message: SignalingMessage) {
        scope.launch {
            when (message) {
                is SignalingMessage.Hello -> {
                    if (role != DeviceRole.CONTROLLER) return@launch
                    val peer = PeerDevice(
                        deviceId = message.deviceId,
                        displayName = message.displayName,
                        tailscaleIp = "remote",
                        lastConnectedAtEpochMs = System.currentTimeMillis()
                    )
                    sessionRepository.setPeerDevice(peer)
                    peerDao.upsert(
                        PeerEntity(
                            deviceId = peer.deviceId,
                            displayName = peer.displayName,
                            tailscaleIp = peer.tailscaleIp,
                            lastConnectedAtEpochMs = peer.lastConnectedAtEpochMs ?: 0L
                        )
                    )
                    serverOrClientSend(
                        SignalingMessage.Welcome(
                            deviceId = localDeviceId,
                            role = DeviceRole.CONTROLLER.name,
                            displayName = "Controller"
                        )
                    )
                    sessionRepository.setConnectionState(ConnectionState.CONNECTED)
                    updateUi {
                        copy(
                            peer = peer,
                            connectionState = ConnectionState.CONNECTED,
                            statusMessage = "Paired with Remote"
                        )
                    }
                    ensurePeerForMedia(isController = true)
                }

                is SignalingMessage.Welcome -> {
                    if (role != DeviceRole.REMOTE) return@launch
                    val peer = PeerDevice(
                        deviceId = message.deviceId,
                        displayName = message.displayName,
                        tailscaleIp = _ui.value.localIp ?: "",
                        lastConnectedAtEpochMs = System.currentTimeMillis()
                    )
                    // Preserve controller IP from connect form via status message / separate field
                    sessionRepository.setPeerDevice(
                        peer.copy(tailscaleIp = peer.tailscaleIp.ifBlank { "controller" })
                    )
                    sessionRepository.setConnectionState(ConnectionState.CONNECTED)
                    updateUi {
                        copy(
                            peer = peer,
                            connectionState = ConnectionState.CONNECTED,
                            statusMessage = "Paired with Controller"
                        )
                    }
                    ensurePeerForMedia(isController = false)
                }

                is SignalingMessage.Reject -> {
                    sessionRepository.setConnectionState(ConnectionState.ERROR)
                    sessionRepository.setLastError(message.reason)
                    updateUi {
                        copy(
                            connectionState = ConnectionState.ERROR,
                            lastError = message.reason,
                            statusMessage = "Pairing rejected"
                        )
                    }
                }

                is SignalingMessage.Offer -> {
                    ensurePeerForMedia(isController = role == DeviceRole.CONTROLLER)
                    // Ensure remote camera is on when offer arrives (both cameras together).
                    if (role == DeviceRole.REMOTE && !peerConnectionManager.isCameraRunning()) {
                        startDualCameraLocally()
                    }
                    peerConnectionManager.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.OFFER, message.sdp)
                    ) {
                        peerConnectionManager.createAnswer(
                            onSuccess = { answer ->
                                serverOrClientSend(SignalingMessage.Answer(answer.description))
                            },
                            onError = { Timber.e(it) }
                        )
                    }
                }

                is SignalingMessage.Answer -> {
                    peerConnectionManager.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                    )
                }

                is SignalingMessage.Ice -> {
                    peerConnectionManager.addIceCandidate(
                        IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                    )
                }

                is SignalingMessage.Hangup -> {
                    stopDualCameraLocally()
                    sessionRepository.setConnectionState(ConnectionState.PAIRED_DISCONNECTED)
                    updateUi {
                        copy(
                            connectionState = ConnectionState.PAIRED_DISCONNECTED,
                            statusMessage = "Peer hung up"
                        )
                    }
                }

                is SignalingMessage.CameraStart -> {
                    ensurePeerForMedia(isController = role == DeviceRole.CONTROLLER)
                    startDualCameraLocally()
                }

                is SignalingMessage.CameraStop -> {
                    stopDualCameraLocally()
                    peerConnectionManager.release()
                    peerCreated = false
                }
            }
        }
    }

    private var peerCreated = false

    private fun ensurePeerForMedia(isController: Boolean) {
        if (peerCreated) {
            // Refresh listeners only.
        }
        peerConnectionManager.createPeerConnection(
            isController = isController,
            listeners = WebRtcListeners(
                onIceCandidate = { candidate ->
                    serverOrClientSend(
                        SignalingMessage.Ice(
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                },
                onConnectionChange = { state ->
                    scope.launch {
                        updateUi { copy(webrtcState = state.name) }
                        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                            sessionRepository.setConnectionState(ConnectionState.CONNECTED)
                        }
                        if (state == PeerConnection.PeerConnectionState.FAILED ||
                            state == PeerConnection.PeerConnectionState.DISCONNECTED
                        ) {
                            sessionRepository.setConnectionState(ConnectionState.RECONNECTING)
                            updateUi {
                                copy(
                                    connectionState = ConnectionState.RECONNECTING,
                                    statusMessage = "WebRTC $state — check Tailscale"
                                )
                            }
                        }
                    }
                },
                onDataMessage = { payload -> handleDataCommand(payload) },
                onRemoteVideoTrack = { track ->
                    scope.launch {
                        remoteVideoTrack = track
                        remoteVideoSource.start()
                        remoteRenderer?.let { track.addSink(it) }
                    }
                }
            )
        )
        peerCreated = true
    }

    private fun handleDataCommand(payload: String) {
        scope.launch {
            when (CommandParser.typeOf(payload)) {
                CommandTypes.OPEN_CAMERA -> {
                    startDualCameraLocally()
                    if (role == DeviceRole.REMOTE) {
                        // Remote answers after starting camera if offer already set; if controller
                        // already sent offer, tracks may renegotiate — send status back.
                        peerConnectionManager.sendData(
                            StatusCommand(localDeviceId, cameraOn = true, role = role!!.name).toPayload()
                        )
                    }
                }
                CommandTypes.CLOSE_CAMERA -> stopDualCameraLocally()
                CommandTypes.PING -> {
                    peerConnectionManager.sendData(PongCommand(localDeviceId).toPayload())
                }
                CommandTypes.PONG -> {
                    updateUi { copy(statusMessage = "Peer alive (PONG)") }
                }
                CommandTypes.DEVICE_STATUS -> {
                    updateUi { copy(statusMessage = "Peer status received") }
                }
            }
        }
    }

    private suspend fun startDualCameraLocally() {
        withContext(Dispatchers.Main) {
            localCameraSource.start()
            if (role == DeviceRole.CONTROLLER) {
                localRenderer?.let { peerConnectionManager.attachLocalVideoTo(it) }
            }
            // Remote also starts camera (product rule: both on), but UI shows controller stream.
            val dual = DualCameraSessionState(
                isActive = true,
                controllerCameraOn = true,
                remoteCameraOn = true,
                controllerShowsLocalPreview = true,
                remoteShowsControllerStream = true
            )
            updateUi {
                copy(
                    dualCamera = dual,
                    statusMessage = if (role == DeviceRole.CONTROLLER) {
                        "Dual camera ON — showing your camera"
                    } else {
                        "Dual camera ON — showing controller camera"
                    }
                )
            }
        }
    }

    private suspend fun stopDualCameraLocally() {
        withContext(Dispatchers.Main) {
            localRenderer?.let { peerConnectionManager.detachLocalVideoFrom(it) }
            localCameraSource.stop()
            remoteVideoSource.stop()
            updateUi {
                copy(
                    dualCamera = DualCameraSessionState(),
                    statusMessage = "Cameras stopped"
                )
            }
        }
    }

    private fun serverOrClientSend(message: SignalingMessage) {
        signalingServer?.send(message)
        signalingClient?.send(message)
    }

    private fun clientSend(message: SignalingMessage) {
        signalingClient?.send(message)
    }

    private fun updateUi(block: LiveSessionUi.() -> LiveSessionUi) {
        _ui.value = _ui.value.block()
    }
}
