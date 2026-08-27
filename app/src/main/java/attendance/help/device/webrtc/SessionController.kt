package attendance.help.device.webrtc

import android.content.Context
import attendance.help.device.BuildConfig
import attendance.help.device.camera.LocalCameraSource
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.device.command.CloseCameraCommand
import attendance.help.device.device.command.CommandParser
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.device.command.OpenCameraCommand
import attendance.help.device.device.command.PingCommand
import attendance.help.device.device.command.PongCommand
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.DualCameraSessionState
import attendance.help.device.domain.model.HubDevice
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.network.hub.HubClient
import attendance.help.device.network.hub.HubMessage
import attendance.help.device.network.hub.HubServer
import attendance.help.device.service.LinkStatusService
import attendance.help.device.utils.TailscaleIpFinder
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * Server-first link orchestrator:
 * connect to virtual hub → set mode (Remote/Control/Nothing) →
 * Control selects Remote → bidirectional dual-camera session.
 */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val peerConnectionManager: PeerConnectionManager,
    private val localCameraSource: LocalCameraSource
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localDeviceId by lazy { deviceIdentityProvider.getOrCreateDeviceId() }

    private var hubServer: HubServer? = null
    private var hubClient: HubClient? = null
    private var peerCreated = false
    private var boundPeerId: String? = null

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localPipRenderer: SurfaceViewRenderer? = null
    private var inboundVideoTrack: VideoTrack? = null

    private val _ui = MutableStateFlow(AppLinkSnapshot(localDeviceId = localDeviceId))
    val uiState: StateFlow<AppLinkSnapshot> = _ui.asStateFlow()

    fun bindRenderers(remote: SurfaceViewRenderer?, localPip: SurfaceViewRenderer?) {
        remoteRenderer = remote
        localPipRenderer = localPip
        remote?.let {
            peerConnectionManager.initRemoteRenderer(it)
            inboundVideoTrack?.addSink(it)
        }
        localPip?.let {
            peerConnectionManager.initLocalRenderer(it)
            if (peerConnectionManager.isCameraRunning()) {
                peerConnectionManager.attachLocalVideoTo(it)
            }
        }
    }

    fun unbindRenderers() {
        remoteRenderer?.let { r -> inboundVideoTrack?.removeSink(r) }
        localPipRenderer?.let { peerConnectionManager.detachLocalVideoFrom(it) }
        remoteRenderer = null
        localPipRenderer = null
    }

    /** Connect to virtual server. Optionally host hub on this phone. */
    suspend fun connectToServer(hostInput: String, hostLocally: Boolean, displayName: String) {
        sessionRepository.setDisplayName(displayName.ifBlank { "Phone" })
        sessionRepository.setHostingHubLocally(hostLocally)
        sessionRepository.setServerLinkState(ServerLinkState.CONNECTING)
        update {
            copy(
                hostingHubLocally = hostLocally,
                serverLinkState = ServerLinkState.CONNECTING,
                statusMessage = "Connecting to server…",
                lastError = null
            )
        }

        val advertiseHost: String
        val connectHost: String
        if (hostLocally) {
            startLocalHub()
            advertiseHost = withContext(Dispatchers.IO) {
                TailscaleIpFinder.findPreferredIp() ?: "127.0.0.1"
            }
            // Always loop back locally when this phone hosts the hub.
            connectHost = "127.0.0.1"
        } else {
            advertiseHost = hostInput.trim()
            connectHost = advertiseHost
        }
        if (advertiseHost.isBlank() || connectHost.isBlank()) {
            failServer("Enter a server IP, or host the virtual server on this phone.")
            return
        }
        sessionRepository.setServerHost(advertiseHost)
        update { copy(serverHost = advertiseHost) }
        openHubClient(connectHost)
    }

    suspend fun setMode(mode: DeviceMode) {
        if (_ui.value.serverLinkState != ServerLinkState.CONNECTED &&
            _ui.value.serverLinkState != ServerLinkState.HOSTING_AND_CONNECTED
        ) {
            failServer("Connect to the server first.")
            return
        }
        if (mode == DeviceMode.NONE) {
            clearModeToNothing()
            return
        }
        sessionRepository.setDeviceMode(mode)
        val name = sessionRepository.displayName.first()
        hubClient?.send(
            HubMessage.Register(
                deviceId = localDeviceId,
                displayName = name,
                mode = mode.name
            )
        )
        val sessionState = if (mode == DeviceMode.REMOTE) {
            SessionLinkState.WAITING_FOR_CONTROL
        } else {
            SessionLinkState.SELECTING_REMOTE
        }
        sessionRepository.setSessionLinkState(sessionState)
        update {
            copy(
                mode = mode,
                sessionLinkState = sessionState,
                statusMessage = when (mode) {
                    DeviceMode.REMOTE -> "Remote mode — controllable by any Control phone"
                    DeviceMode.CONTROL -> "Control mode — select a Remote phone"
                    else -> ""
                },
                boundPeer = null
            )
        }
        refreshStatusNotification(
            detail = when (mode) {
                DeviceMode.REMOTE -> "Controllable by any phone"
                DeviceMode.CONTROL -> "Select a remote to control"
                else -> ""
            }
        )
        if (mode == DeviceMode.CONTROL) {
            hubClient?.send(HubMessage.RequestRemotes(localDeviceId))
        }
    }

    /** "Set nothing" — return to mode-unset while keeping server link. */
    suspend fun clearModeToNothing() {
        hubClient?.send(HubMessage.Unregister(localDeviceId))
        stopMediaFully()
        boundPeerId = null
        sessionRepository.clearModeSettings()
        LinkStatusService.stop(context)
        update {
            copy(
                mode = DeviceMode.NONE,
                sessionLinkState = SessionLinkState.IDLE,
                boundPeer = null,
                availableRemotes = emptyList(),
                dualCamera = DualCameraSessionState(),
                statusMessage = "Mode cleared — phone is in initial (nothing) state",
                webrtcState = "CLOSED"
            )
        }
    }

    fun refreshRemoteList() {
        hubClient?.send(HubMessage.RequestRemotes(localDeviceId))
    }

    fun selectRemote(remote: HubDevice) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        update { copy(sessionLinkState = SessionLinkState.BINDING, statusMessage = "Binding to ${remote.displayName}…") }
        hubClient?.send(
            HubMessage.SelectRemote(
                controlDeviceId = localDeviceId,
                remoteDeviceId = remote.deviceId
            )
        )
    }

    fun openDualCamera() {
        val peerId = boundPeerId ?: return
        if (_ui.value.mode != DeviceMode.CONTROL) {
            update { copy(statusMessage = "Only Control phone starts the dual-camera session") }
            return
        }
        scope.launch {
            ensurePeer(isOfferer = true)
            startBothCamerasLocally()
            hubClient?.send(HubMessage.CameraStart(fromId = localDeviceId, toId = peerId))
            peerConnectionManager.sendData(OpenCameraCommand(localDeviceId).toPayload())
            peerConnectionManager.createOffer(
                onSuccess = { sdp ->
                    hubClient?.send(
                        HubMessage.RelayOffer(
                            fromId = localDeviceId,
                            toId = peerId,
                            sdp = sdp.description
                        )
                    )
                },
                onError = { err -> scope.launch { sessionRepository.setLastError(err) } }
            )
        }
    }

    fun closeDualCamera() {
        val peerId = boundPeerId
        scope.launch {
            if (peerId != null) {
                hubClient?.send(HubMessage.CameraStop(fromId = localDeviceId, toId = peerId))
                peerConnectionManager.sendData(CloseCameraCommand(localDeviceId).toPayload())
            }
            stopBothCamerasLocally()
            peerConnectionManager.release()
            peerCreated = false
        }
    }

    fun sendPing() {
        peerConnectionManager.sendData(PingCommand(localDeviceId).toPayload())
    }

    suspend fun disconnectServer() {
        clearModeToNothing()
        hubClient?.disconnect()
        hubClient = null
        runCatching { hubServer?.stop() }
        hubServer = null
        sessionRepository.setServerLinkState(ServerLinkState.DISCONNECTED)
        LinkStatusService.stop(context)
        update {
            copy(
                serverLinkState = ServerLinkState.DISCONNECTED,
                statusMessage = "Disconnected from server"
            )
        }
    }

    private suspend fun startLocalHub() = withContext(Dispatchers.IO) {
        runCatching { hubServer?.stop() }
        val server = HubServer(BuildConfig.SIGNALING_PORT)
        hubServer = server
        // start() blocks; run on dedicated thread.
        Thread(
            {
                runCatching { server.start() }
                    .onFailure { Timber.e(it, "Hub start failed") }
            },
            "ah-hub-server"
        ).start()
        Thread.sleep(500)
    }

    private fun openHubClient(host: String) {
        hubClient?.disconnect()
        val client = HubClient(
            onMessage = { handleHub(it) },
            onOpen = {
                scope.launch {
                    val hosting = sessionRepository.hostingHubLocally.first()
                    val state = if (hosting) {
                        ServerLinkState.HOSTING_AND_CONNECTED
                    } else {
                        ServerLinkState.CONNECTED
                    }
                    sessionRepository.setServerLinkState(state)
                    update {
                        copy(
                            serverLinkState = state,
                            statusMessage = "Connected to server $host — set phone mode next"
                        )
                    }
                    // Re-register if mode already persisted (app relaunch).
                    val mode = sessionRepository.deviceMode.first()
                    if (mode != DeviceMode.NONE) {
                        setMode(mode)
                    } else {
                        refreshStatusNotification(detail = "Choose Remote / Control / Nothing")
                    }
                }
            },
            onClosed = {
                scope.launch {
                    sessionRepository.setServerLinkState(ServerLinkState.DISCONNECTED)
                    update {
                        copy(
                            serverLinkState = ServerLinkState.DISCONNECTED,
                            statusMessage = "Server connection closed"
                        )
                    }
                }
            },
            onFailure = { t ->
                scope.launch { failServer(t.message ?: "Server connection failed") }
            }
        )
        hubClient = client
        client.connect("ws://$host:${BuildConfig.SIGNALING_PORT}")
    }

    private fun handleHub(message: HubMessage) {
        scope.launch {
            when (message) {
                is HubMessage.RegisterAck -> {
                    update { copy(statusMessage = message.message.ifBlank { "Registered" }) }
                }
                is HubMessage.RemotesList -> {
                    update {
                        copy(
                            availableRemotes = message.remotes,
                            statusMessage = "Remotes online: ${message.remotes.size}"
                        )
                    }
                }
                is HubMessage.SessionBound -> {
                    val iAmControl = message.controlDeviceId == localDeviceId
                    val peer = if (iAmControl) {
                        HubDevice(message.remoteDeviceId, message.remoteName, DeviceMode.REMOTE, false)
                    } else {
                        HubDevice(message.controlDeviceId, message.controlName, DeviceMode.CONTROL, false)
                    }
                    boundPeerId = peer.deviceId
                    sessionRepository.setSessionLinkState(SessionLinkState.BOUND)
                    update {
                        copy(
                            boundPeer = peer,
                            sessionLinkState = SessionLinkState.BOUND,
                            statusMessage = "Phones combined — bound with ${peer.displayName}"
                        )
                    }
                    refreshStatusNotification(detail = "Bound with ${peer.displayName}")
                    ensurePeer(isOfferer = iAmControl)
                }
                is HubMessage.RelayOffer -> {
                    ensurePeer(isOfferer = false)
                    if (!peerConnectionManager.isCameraRunning()) {
                        startBothCamerasLocally()
                    }
                    peerConnectionManager.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.OFFER, message.sdp)
                    ) {
                        peerConnectionManager.createAnswer(
                            onSuccess = { answer ->
                                hubClient?.send(
                                    HubMessage.RelayAnswer(
                                        fromId = localDeviceId,
                                        toId = message.fromId,
                                        sdp = answer.description
                                    )
                                )
                            },
                            onError = { Timber.e(it) }
                        )
                    }
                }
                is HubMessage.RelayAnswer -> {
                    peerConnectionManager.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                    )
                }
                is HubMessage.RelayIce -> {
                    peerConnectionManager.addIceCandidate(
                        IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
                    )
                }
                is HubMessage.CameraStart -> {
                    ensurePeer(isOfferer = false)
                    startBothCamerasLocally()
                }
                is HubMessage.CameraStop -> {
                    stopBothCamerasLocally()
                    peerConnectionManager.release()
                    peerCreated = false
                }
                is HubMessage.ErrorMsg -> {
                    sessionRepository.setLastError(message.message)
                    update { copy(lastError = message.message, statusMessage = message.message) }
                }
                else -> Unit
            }
        }
    }

    private fun ensurePeer(isOfferer: Boolean) {
        peerConnectionManager.createPeerConnection(
            isController = isOfferer,
            listeners = WebRtcListeners(
                onIceCandidate = { c ->
                    val peerId = boundPeerId ?: return@WebRtcListeners
                    hubClient?.send(
                        HubMessage.RelayIce(
                            fromId = localDeviceId,
                            toId = peerId,
                            candidate = c.sdp,
                            sdpMid = c.sdpMid,
                            sdpMLineIndex = c.sdpMLineIndex
                        )
                    )
                },
                onConnectionChange = { state ->
                    scope.launch {
                        update { copy(webrtcState = state.name) }
                        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                            sessionRepository.setSessionLinkState(SessionLinkState.STREAMING)
                            update { copy(sessionLinkState = SessionLinkState.STREAMING) }
                        }
                    }
                },
                onDataMessage = { handleData(it) },
                onRemoteVideoTrack = { track ->
                    scope.launch {
                        inboundVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                    }
                }
            )
        )
        peerCreated = true
    }

    private fun handleData(payload: String) {
        scope.launch {
            when (CommandParser.typeOf(payload)) {
                CommandTypes.OPEN_CAMERA -> startBothCamerasLocally()
                CommandTypes.CLOSE_CAMERA -> stopBothCamerasLocally()
                CommandTypes.PING ->
                    peerConnectionManager.sendData(PongCommand(localDeviceId).toPayload())
                CommandTypes.PONG -> update { copy(statusMessage = "Peer alive (PONG)") }
            }
        }
    }

    private suspend fun startBothCamerasLocally() {
        withContext(Dispatchers.Main) {
            localCameraSource.start()
            localPipRenderer?.let { peerConnectionManager.attachLocalVideoTo(it) }
            val dual = DualCameraSessionState(
                isActive = true,
                bothCamerasOn = true,
                controlShowsRemoteFeed = true,
                remoteShowsControlFeed = true
            )
            update {
                copy(
                    dualCamera = dual,
                    sessionLinkState = SessionLinkState.STREAMING,
                    statusMessage = when (mode) {
                        DeviceMode.CONTROL -> "Cameras ON — showing Remote video (your camera also on)"
                        DeviceMode.REMOTE -> "Cameras ON — showing Control video (your camera also on)"
                        else -> "Cameras ON"
                    }
                )
            }
        }
    }

    private suspend fun stopBothCamerasLocally() {
        withContext(Dispatchers.Main) {
            localPipRenderer?.let { peerConnectionManager.detachLocalVideoFrom(it) }
            localCameraSource.stop()
            update {
                copy(
                    dualCamera = DualCameraSessionState(),
                    statusMessage = "Cameras stopped"
                )
            }
        }
    }

    private suspend fun stopMediaFully() {
        stopBothCamerasLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundVideoTrack = null
    }

    private suspend fun failServer(message: String) {
        sessionRepository.setServerLinkState(ServerLinkState.ERROR)
        sessionRepository.setLastError(message)
        update {
            copy(
                serverLinkState = ServerLinkState.ERROR,
                lastError = message,
                statusMessage = message
            )
        }
    }

    private fun refreshStatusNotification(detail: String) {
        val snap = _ui.value
        if (snap.serverLinkState == ServerLinkState.DISCONNECTED) {
            LinkStatusService.stop(context)
            return
        }
        LinkStatusService.update(
            context = context,
            mode = snap.mode,
            server = snap.serverHost.ifBlank { "server" },
            detail = detail
        )
    }

    private fun update(block: AppLinkSnapshot.() -> AppLinkSnapshot) {
        _ui.value = _ui.value.block()
    }

    /** Restore notification / reconnect after process start if needed. */
    fun restoreStatusBarIfNeeded() {
        scope.launch {
            val mode = sessionRepository.deviceMode.first()
            val link = sessionRepository.serverLinkState.first()
            val host = sessionRepository.serverHost.first()
            if (link == ServerLinkState.CONNECTED || link == ServerLinkState.HOSTING_AND_CONNECTED) {
                update {
                    copy(
                        mode = mode,
                        serverHost = host,
                        serverLinkState = link
                    )
                }
                refreshStatusNotification(
                    detail = when (mode) {
                        DeviceMode.REMOTE -> "Controllable by any phone"
                        DeviceMode.CONTROL -> "Control phone"
                        DeviceMode.NONE -> "Connected — set mode"
                    }
                )
            }
        }
    }
}
