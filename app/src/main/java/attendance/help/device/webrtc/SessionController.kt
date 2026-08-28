package attendance.help.device.webrtc

import android.content.Context
import android.content.Intent
import attendance.help.device.BuildConfig
import attendance.help.device.camera.LocalCameraSource
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.device.command.CloseCameraCommand
import attendance.help.device.device.command.CommandParser
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.device.command.KeyCommand
import attendance.help.device.device.command.OpenCameraCommand
import attendance.help.device.device.command.PingCommand
import attendance.help.device.device.command.PongCommand
import attendance.help.device.device.command.TouchCommand
import attendance.help.device.device.control.RemoteInputAccessibilityService
import attendance.help.device.device.control.ScreenCapturePermissionActivity
import attendance.help.device.device.control.ScreenShareCoordinator
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.DualCameraSessionState
import attendance.help.device.domain.model.HubDevice
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.network.hub.HubClient
import attendance.help.device.network.hub.HubMessage
import attendance.help.device.service.LinkStatusService
import attendance.help.device.service.ScreenShareService
import attendance.help.device.utils.ServerAddressParser
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
import kotlin.math.hypot

/**
 * Server-first link orchestrator with full remote control:
 * Control sees Remote screen + injects touches; Remote sees Control camera.
 */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val peerConnectionManager: PeerConnectionManager,
    private val localCameraSource: LocalCameraSource,
    private val screenShareCoordinator: ScreenShareCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localDeviceId by lazy { deviceIdentityProvider.getOrCreateDeviceId() }

    private var hubClient: HubClient? = null
    private var peerCreated = false
    private var boundPeerId: String? = null

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localPipRenderer: SurfaceViewRenderer? = null
    private var inboundVideoTrack: VideoTrack? = null

    private var pendingOfferFromId: String? = null
    private var pendingOfferSdp: String? = null
    private var touchDownX: Float? = null
    private var touchDownY: Float? = null

    private val _ui = MutableStateFlow(AppLinkSnapshot(localDeviceId = localDeviceId))
    val uiState: StateFlow<AppLinkSnapshot> = _ui.asStateFlow()

    init {
        scope.launch {
            screenShareCoordinator.permissionResults.collect { intent ->
                onScreenShareGranted(intent)
            }
        }
        scope.launch {
            screenShareCoordinator.denied.collect {
                update {
                    copy(
                        needsScreenSharePermission = true,
                        statusMessage = "Screen share denied — Control cannot see this phone"
                    )
                }
            }
        }
    }

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

    fun requestScreenSharePermission() {
        context.startActivity(ScreenCapturePermissionActivity.intent(context))
    }

    fun refreshAccessibilityState() {
        update { copy(accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()) }
    }

    fun sendTouch(action: String, x: Float, y: Float) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        peerConnectionManager.sendData(TouchCommand(action, x, y).toPayload())
    }

    fun sendRemoteKey(type: String) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        peerConnectionManager.sendData(KeyCommand(type).toPayload())
    }

    /** Connect to PC/virtual hub only (phones never host the server). */
    suspend fun connectToServer(hostInput: String, displayName: String) {
        try {
            sessionRepository.setDisplayName(displayName.ifBlank { "Phone" })
            sessionRepository.setHostingHubLocally(false)
            sessionRepository.setServerLinkState(ServerLinkState.CONNECTING)
            update {
                copy(
                    hostingHubLocally = false,
                    serverLinkState = ServerLinkState.CONNECTING,
                    statusMessage = "Connecting to server…",
                    lastError = null
                )
            }

            val endpoint = ServerAddressParser.parse(hostInput, BuildConfig.SIGNALING_PORT)
                .getOrElse { err ->
                    failServer(err.message ?: "Invalid server address")
                    return
                }

            sessionRepository.setServerHost(endpoint.host)
            update {
                copy(
                    serverHost = endpoint.host,
                    serverPort = endpoint.port,
                    statusMessage = "Connecting to ${endpoint.host}:${endpoint.port}…"
                )
            }
            openHubClient(ServerAddressParser.toWsUrl(endpoint))
        } catch (t: Throwable) {
            Timber.e(t, "connectToServer crashed")
            failServer(t.message ?: "Connection failed")
        }
    }

    suspend fun setMode(mode: DeviceMode) {
        if (_ui.value.serverLinkState != ServerLinkState.CONNECTED) {
            failServer("Connect to the server first.")
            return
        }
        if (mode == DeviceMode.NONE) {
            clearModeToNothing()
            return
        }
        // Changing role must free any current Control↔Remote bind.
        unbindPeerIfNeeded("mode change")
        stopMediaFully()
        boundPeerId = null
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
                    DeviceMode.REMOTE -> "Remote mode — fully controllable by any Control phone"
                    DeviceMode.CONTROL -> "Control mode — select a Remote phone"
                    else -> ""
                },
                boundPeer = null,
                dualCamera = DualCameraSessionState(),
                needsScreenSharePermission = false,
                screenShareActive = false,
                accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
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
        // Release Control↔Remote first so the peer is not left busy.
        unbindPeerIfNeeded("mode cleared")
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
                needsScreenSharePermission = false,
                screenShareActive = false,
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
        if (!remote.available) {
            update { copy(statusMessage = "That remote is busy with another control phone") }
            return
        }
        update { copy(sessionLinkState = SessionLinkState.BINDING, statusMessage = "Connecting to ${remote.displayName}…") }
        hubClient?.send(
            HubMessage.SelectRemote(
                controlDeviceId = localDeviceId,
                remoteDeviceId = remote.deviceId
            )
        )
    }

    /** Control starts camera + offer after bind; Remote answers with screen share. */
    private suspend fun beginAutoDualCamera() {
        val peerId = boundPeerId ?: return
        if (_ui.value.mode != DeviceMode.CONTROL) return
        ensurePeer(isOfferer = true)
        startControlMedia()
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

    fun openDualCamera() {
        scope.launch { beginAutoDualCamera() }
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

    /** End control of the remote; remote becomes available again for other controls. */
    fun releaseRemoteControl() {
        scope.launch {
            val peerId = boundPeerId
            if (peerId != null) {
                hubClient?.send(
                    HubMessage.SessionUnbind(
                        fromId = localDeviceId,
                        peerId = peerId
                    )
                )
            }
            applySessionReleased("Disconnected from remote")
        }
    }

    private suspend fun applySessionReleased(message: String) {
        stopBothCamerasLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundVideoTrack = null
        boundPeerId = null
        pendingOfferSdp = null
        pendingOfferFromId = null
        sessionRepository.setSessionLinkState(
            if (_ui.value.mode == DeviceMode.REMOTE) {
                SessionLinkState.WAITING_FOR_CONTROL
            } else {
                SessionLinkState.SELECTING_REMOTE
            }
        )
        update {
            copy(
                boundPeer = null,
                sessionLinkState = if (mode == DeviceMode.REMOTE) {
                    SessionLinkState.WAITING_FOR_CONTROL
                } else {
                    SessionLinkState.SELECTING_REMOTE
                },
                dualCamera = DualCameraSessionState(),
                needsScreenSharePermission = false,
                screenShareActive = false,
                statusMessage = message,
                webrtcState = "CLOSED"
            )
        }
        refreshStatusNotification(
            detail = when (_ui.value.mode) {
                DeviceMode.REMOTE -> "Available — controllable by any Control phone"
                DeviceMode.CONTROL -> "Select a remote to control"
                else -> ""
            }
        )
        if (_ui.value.mode == DeviceMode.CONTROL) {
            hubClient?.send(HubMessage.RequestRemotes(localDeviceId))
        }
    }

    fun sendPing() {
        peerConnectionManager.sendData(PingCommand(localDeviceId).toPayload())
    }

    suspend fun disconnectServer() {
        try {
            unbindPeerIfNeeded("server disconnect")
            runCatching { hubClient?.send(HubMessage.Unregister(localDeviceId)) }
            stopMediaFully()
            boundPeerId = null
            hubClient?.disconnect()
            hubClient = null
            sessionRepository.clearModeSettings()
            sessionRepository.setServerLinkState(ServerLinkState.DISCONNECTED)
            sessionRepository.setHostingHubLocally(false)
            LinkStatusService.stop(context)
            update {
                copy(
                    serverLinkState = ServerLinkState.DISCONNECTED,
                    mode = DeviceMode.NONE,
                    sessionLinkState = SessionLinkState.IDLE,
                    boundPeer = null,
                    availableRemotes = emptyList(),
                    dualCamera = DualCameraSessionState(),
                    hostingHubLocally = false,
                    needsScreenSharePermission = false,
                    screenShareActive = false,
                    statusMessage = "Disconnected from server",
                    lastError = null,
                    webrtcState = "CLOSED"
                )
            }
        } catch (t: Throwable) {
            Timber.e(t, "disconnectServer failed")
            update {
                copy(
                    serverLinkState = ServerLinkState.DISCONNECTED,
                    statusMessage = "Disconnected",
                    lastError = t.message
                )
            }
        }
    }

    private fun openHubClient(wsUrl: String) {
        hubClient?.disconnect()
        val client = HubClient(
            onMessage = { handleHub(it) },
            onOpen = {
                scope.launch {
                    sessionRepository.setServerLinkState(ServerLinkState.CONNECTED)
                    update {
                        copy(
                            serverLinkState = ServerLinkState.CONNECTED,
                            statusMessage = "Connected to server ${serverHost}:${serverPort}"
                        )
                    }
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
                    LinkStatusService.stop(context)
                }
            },
            onFailure = { t ->
                scope.launch {
                    failServer(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "Cannot reach server. Check IP, Wi‑Fi, and that the hub is running."
                    )
                }
            }
        )
        hubClient = client
        client.connect(wsUrl)
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
                            statusMessage = if (iAmControl) {
                                "Connected — starting full remote control…"
                            } else {
                                "Control connected — allow screen share to be controlled"
                            },
                            needsScreenSharePermission = !iAmControl,
                            accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
                        )
                    }
                    refreshStatusNotification(
                        detail = if (iAmControl) "Controlling ${peer.displayName}" else "Being controlled"
                    )
                    ensurePeer(isOfferer = iAmControl)
                    if (iAmControl) {
                        kotlinx.coroutines.delay(900)
                        beginAutoDualCamera()
                    } else {
                        // Prompt Remote to share screen ASAP so answer can include screen track.
                        requestScreenSharePermission()
                    }
                }
                is HubMessage.SessionUnbind -> {
                    applySessionReleased("Remote released")
                }
                is HubMessage.SessionUnbound -> {
                    applySessionReleased(message.reason.ifBlank { "Remote released" })
                }
                is HubMessage.RelayOffer -> {
                    ensurePeer(isOfferer = false)
                    pendingOfferFromId = message.fromId
                    pendingOfferSdp = message.sdp
                    if (_ui.value.mode == DeviceMode.REMOTE && !peerConnectionManager.isCameraRunning()) {
                        update { copy(needsScreenSharePermission = true) }
                        if (!_ui.value.screenShareActive) {
                            requestScreenSharePermission()
                        }
                    }
                    tryAnswerPendingOffer()
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
                    if (_ui.value.mode == DeviceMode.REMOTE) {
                        update { copy(needsScreenSharePermission = !peerConnectionManager.isScreenSharing()) }
                        if (!peerConnectionManager.isScreenSharing()) {
                            requestScreenSharePermission()
                        }
                    } else {
                        startControlMedia()
                    }
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

    private suspend fun onScreenShareGranted(resultData: Intent) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        ensurePeer(isOfferer = false)
        withContext(Dispatchers.Main) {
            ScreenShareService.start(context)
            peerConnectionManager.startScreenShare(resultData)
            localPipRenderer?.let { peerConnectionManager.attachLocalVideoTo(it) }
            update {
                copy(
                    needsScreenSharePermission = false,
                    screenShareActive = true,
                    dualCamera = DualCameraSessionState(
                        isActive = true,
                        bothCamerasOn = true,
                        controlShowsRemoteFeed = true,
                        remoteShowsControlFeed = true
                    ),
                    statusMessage = "Cameras ON — Control feed on both; screen shared for full control",
                    accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
                )
            }
        }
        tryAnswerPendingOffer()
    }

    private fun tryAnswerPendingOffer() {
        val sdp = pendingOfferSdp ?: return
        val fromId = pendingOfferFromId ?: return
        if (!peerConnectionManager.isCameraRunning()) return
        pendingOfferSdp = null
        pendingOfferFromId = null
        peerConnectionManager.setRemoteDescription(
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        ) {
            peerConnectionManager.createAnswer(
                onSuccess = { answer ->
                    hubClient?.send(
                        HubMessage.RelayAnswer(
                            fromId = localDeviceId,
                            toId = fromId,
                            sdp = answer.description
                        )
                    )
                },
                onError = { Timber.e(it) }
            )
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
                CommandTypes.OPEN_CAMERA -> {
                    if (_ui.value.mode == DeviceMode.REMOTE) {
                        update { copy(needsScreenSharePermission = !peerConnectionManager.isScreenSharing()) }
                        if (!peerConnectionManager.isScreenSharing()) {
                            requestScreenSharePermission()
                        }
                    } else {
                        startControlMedia()
                    }
                }
                CommandTypes.CLOSE_CAMERA -> stopBothCamerasLocally()
                CommandTypes.PING ->
                    peerConnectionManager.sendData(PongCommand(localDeviceId).toPayload())
                CommandTypes.PONG -> update { copy(statusMessage = "Peer alive (PONG)") }
                CommandTypes.TOUCH -> handleIncomingTouch(payload)
                CommandTypes.KEY_BACK,
                CommandTypes.KEY_HOME,
                CommandTypes.KEY_RECENTS -> handleIncomingKey(CommandParser.typeOf(payload)!!)
            }
        }
    }

    private fun handleIncomingTouch(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val touch = CommandParser.parseTouch(payload) ?: return
        val a11y = RemoteInputAccessibilityService.instance
        if (a11y == null) {
            update {
                copy(
                    accessibilityEnabled = false,
                    statusMessage = "Enable Accessibility for Attendance Help to allow touch control"
                )
            }
            return
        }
        when (touch.action) {
            "down" -> {
                touchDownX = touch.x
                touchDownY = touch.y
            }
            "move" -> {
                // Keep latest; applied on up as swipe if needed.
                if (touchDownX == null) {
                    touchDownX = touch.x
                    touchDownY = touch.y
                }
            }
            "up" -> {
                val sx = touchDownX ?: touch.x
                val sy = touchDownY ?: touch.y
                val dist = hypot((touch.x - sx).toDouble(), (touch.y - sy).toDouble())
                if (dist < 0.02) {
                    a11y.tapNormalized(touch.x, touch.y)
                } else {
                    a11y.swipeNormalized(sx, sy, touch.x, touch.y)
                }
                touchDownX = null
                touchDownY = null
            }
        }
    }

    private fun handleIncomingKey(type: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val a11y = RemoteInputAccessibilityService.instance ?: return
        when (type) {
            CommandTypes.KEY_BACK -> a11y.pressBack()
            CommandTypes.KEY_HOME -> a11y.pressHome()
            CommandTypes.KEY_RECENTS -> a11y.pressRecents()
        }
    }

    private fun unbindPeerIfNeeded(reason: String) {
        val peerId = boundPeerId ?: return
        hubClient?.send(
            HubMessage.SessionUnbind(
                fromId = localDeviceId,
                peerId = peerId
            )
        )
        Timber.i("Unbind before %s (peer=%s)", reason, peerId)
    }

    private suspend fun startControlMedia() {
        withContext(Dispatchers.Main) {
            localCameraSource.start()
            localPipRenderer?.let { peerConnectionManager.attachLocalVideoTo(it) }
            update {
                copy(
                    dualCamera = DualCameraSessionState(
                        isActive = true,
                        bothCamerasOn = true,
                        controlShowsRemoteFeed = true,
                        remoteShowsControlFeed = true
                    ),
                    sessionLinkState = SessionLinkState.STREAMING,
                    statusMessage = "Connected — Control feed on both phones; touch Remote screen to operate"
                )
            }
        }
    }

    private suspend fun startBothCamerasLocally() {
        // Kept for call sites; role decides camera vs screen.
        if (_ui.value.mode == DeviceMode.CONTROL) {
            startControlMedia()
        } else if (_ui.value.mode == DeviceMode.REMOTE) {
            update {
                copy(
                    needsScreenSharePermission = !peerConnectionManager.isScreenSharing(),
                    dualCamera = dualCamera.copy(isActive = true, bothCamerasOn = true)
                )
            }
            if (!peerConnectionManager.isScreenSharing()) {
                requestScreenSharePermission()
            }
        }
    }

    private suspend fun stopBothCamerasLocally() {
        withContext(Dispatchers.Main) {
            localPipRenderer?.let { peerConnectionManager.detachLocalVideoFrom(it) }
            localCameraSource.stop()
            ScreenShareService.stop(context)
            update {
                copy(
                    dualCamera = DualCameraSessionState(),
                    screenShareActive = false,
                    needsScreenSharePermission = false,
                    statusMessage = "Media stopped"
                )
            }
        }
    }

    private suspend fun stopMediaFully() {
        stopBothCamerasLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundVideoTrack = null
        pendingOfferSdp = null
        pendingOfferFromId = null
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
            if (link == ServerLinkState.CONNECTED) {
                update {
                    copy(
                        mode = mode,
                        serverHost = host,
                        serverLinkState = link,
                        accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
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
