package attendance.help.device.webrtc

import android.content.Context
import android.content.Intent
import attendance.help.device.BuildConfig
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.device.command.ControlMessages
import attendance.help.device.device.command.CommandParser
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.device.command.KeyCommand
import attendance.help.device.device.command.PingCommand
import attendance.help.device.device.command.PongCommand
import attendance.help.device.device.command.TouchCommand
import attendance.help.device.device.control.RemoteInputAccessibilityService
import attendance.help.device.device.control.ScreenShareCoordinator
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.CaptureGeometry
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.DualCameraSessionState
import attendance.help.device.domain.model.HubDevice
import attendance.help.device.domain.model.RemoteSessionState
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState
import attendance.help.device.domain.model.TurnServerConfig
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * Server-first link orchestrator: Control sees Remote screen and injects touches.
 */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val peerConnectionManager: PeerConnectionManager,
    private val screenShareCoordinator: ScreenShareCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val gson = Gson()
    private val localDeviceId by lazy { deviceIdentityProvider.getOrCreateDeviceId() }

    private var hubClient: HubClient? = null
    private var peerCreated = false
    private var boundPeerId: String? = null
    private var activeSessionId: String = ""

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var inboundScreenTrack: VideoTrack? = null

    private var pendingOfferFromId: String? = null
    private var pendingOfferSdp: String? = null
    private var remoteScreenReady = false
    private var sessionEpoch = 0
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var touchDownX: Float? = null
    private var touchDownY: Float? = null

    private val _ui = MutableStateFlow(AppLinkSnapshot(localDeviceId = localDeviceId))
    val uiState: StateFlow<AppLinkSnapshot> = _ui.asStateFlow()

    init {
        ScreenShareService.stopCallback = { scope.launch { stopRemoteScreenShare(fromNotification = true) } }
        startPresenceHeartbeat()
        scope.launch {
            screenShareCoordinator.permissionResults.collect { intent ->
                runCatching { onScreenShareGranted(intent) }
                    .onFailure { error ->
                        Timber.tag("REMOTE_SESSION").e(error, "Screen share setup failed")
                        ScreenShareService.stop(context)
                        update {
                            copy(
                                remoteSessionState = RemoteSessionState.ERROR,
                                needsScreenSharePermission = true,
                                screenShareActive = false,
                                statusMessage = context.getString(
                                    attendance.help.device.R.string.screen_share_failed,
                                    error.message ?: "unknown error"
                                )
                            )
                        }
                    }
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

    fun bindRenderer(screenRenderer: SurfaceViewRenderer?) {
        remoteRenderer = screenRenderer
        screenRenderer?.let {
            peerConnectionManager.initRenderer(it)
            inboundScreenTrack?.addSink(it)
        }
    }

    fun unbindRenderer() {
        remoteRenderer?.let { r -> inboundScreenTrack?.removeSink(r) }
        remoteRenderer = null
    }

    fun requestScreenSharePermission() {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        if (_ui.value.boundPeer == null) {
            update { copy(statusMessage = "No Control connected yet") }
            return
        }
        if (!RemoteInputAccessibilityService.isEnabled()) {
            update {
                copy(
                    statusMessage = "Enable Accessibility first, then share screen",
                    accessibilityEnabled = false
                )
            }
            return
        }
        update { copy(remoteSessionState = RemoteSessionState.REQUESTING_SCREEN_PERMISSION) }
        val launched = screenShareCoordinator.requestScreenCapture()
        if (!launched) {
            update {
                copy(
                    remoteSessionState = RemoteSessionState.WAITING,
                    statusMessage = "Return to this app, then tap Share screen again"
                )
            }
        }
    }

    fun stopRemoteScreenShare(fromNotification: Boolean = false) {
        scope.launch {
            sessionMutex.withLock {
                stopScreenLocally()
                remoteScreenReady = false
                update {
                    copy(
                        remoteSessionState = RemoteSessionState.WAITING,
                        screenShareActive = false,
                        needsScreenSharePermission = true,
                        statusMessage = if (fromNotification) {
                            "Screen sharing stopped from notification"
                        } else {
                            "Screen sharing stopped — tap Share screen to start again"
                        }
                    )
                }
            }
        }
    }

    /** Re-register with hub so Remote appears in Control list (fixes stale connections). */
    fun announcePresence() {
        scope.launch {
            if (_ui.value.serverLinkState != ServerLinkState.CONNECTED) return@launch
            val mode = _ui.value.mode
            if (mode == DeviceMode.NONE) return@launch
            val name = sessionRepository.displayName.first()
            hubClient?.send(
                HubMessage.Register(
                    deviceId = localDeviceId,
                    displayName = name,
                    mode = mode.name
                )
            )
            if (mode == DeviceMode.CONTROL) {
                hubClient?.send(HubMessage.RequestRemotes(localDeviceId))
            }
            Timber.i("announcePresence mode=%s", mode)
        }
    }

    fun startPresenceHeartbeat() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(12_000)
                if (_ui.value.serverLinkState == ServerLinkState.CONNECTED &&
                    _ui.value.mode != DeviceMode.NONE
                ) {
                    announcePresence()
                }
            }
        }
    }

    fun refreshAccessibilityState() {
        update { copy(accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()) }
    }

    fun sendTap(normalizedX: Float, normalizedY: Float) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        if (!ControlMessages.isValidNormalized(normalizedX) || !ControlMessages.isValidNormalized(normalizedY)) return
        peerConnectionManager.sendData(ControlMessages.encodeTap(normalizedX, normalizedY))
    }

    fun sendSwipe(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        val mapped = points.mapNotNull { (x, y) ->
            if (ControlMessages.isValidNormalized(x) && ControlMessages.isValidNormalized(y)) {
                ControlMessages.Point(x, y)
            } else null
        }
        if (mapped.size < 2) return
        peerConnectionManager.sendData(
            ControlMessages.encodeSwipe(ControlMessages.downsample(mapped), durationMs)
        )
    }

    fun sendRemoteKey(type: String) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        val action = when (type) {
            CommandTypes.KEY_BACK -> "BACK"
            CommandTypes.KEY_HOME -> "HOME"
            CommandTypes.KEY_RECENTS -> "RECENTS"
            else -> return
        }
        peerConnectionManager.sendData(ControlMessages.encodeGlobalAction(action))
        // Legacy fallback for older builds
        peerConnectionManager.sendData(KeyCommand(type).toPayload())
    }

    /** @deprecated use [sendTap] */
    fun sendTouch(action: String, x: Float, y: Float) {
        if (action == "up") sendTap(x, y)
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

    /** Control sends WebRTC offer after Remote screen is ready. */
    private suspend fun beginScreenControlSession() {
        val peerId = boundPeerId ?: return
        if (_ui.value.mode != DeviceMode.CONTROL) return
        if (!remoteScreenReady) return
        ensurePeer(isOfferer = true, force = false)
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
        stopScreenLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundScreenTrack = null
        boundPeerId = null
        pendingOfferSdp = null
        pendingOfferFromId = null
        remoteScreenReady = false
        clearPendingIce()
        sessionEpoch++
        activeSessionId = ""
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
                remoteSessionState = if (mode == DeviceMode.REMOTE) {
                    RemoteSessionState.WAITING
                } else {
                    RemoteSessionState.DISCONNECTED
                },
                sessionId = "",
                captureGeometry = CaptureGeometry(),
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
        // Re-register Remote so hub list shows it as available again.
        if (_ui.value.mode == DeviceMode.REMOTE) {
            val name = sessionRepository.displayName.first()
            hubClient?.send(
                HubMessage.Register(
                    deviceId = localDeviceId,
                    displayName = name,
                    mode = DeviceMode.REMOTE.name
                )
            )
        }
        if (_ui.value.mode == DeviceMode.CONTROL) {
            kotlinx.coroutines.delay(300)
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
                    announcePresence()
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
                    message.turnConfig?.let { peerConnectionManager.setTurnConfig(it) }
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
                    sessionMutex.withLock {
                        val iAmControl = message.controlDeviceId == localDeviceId
                        val peer = if (iAmControl) {
                            HubDevice(message.remoteDeviceId, message.remoteName, DeviceMode.REMOTE, false)
                        } else {
                            HubDevice(message.controlDeviceId, message.controlName, DeviceMode.CONTROL, false)
                        }
                        activeSessionId = message.sessionId.ifBlank {
                            "${message.controlDeviceId}_${message.remoteDeviceId}"
                        }
                        stopScreenLocally()
                        peerConnectionManager.release()
                        peerCreated = false
                        inboundScreenTrack = null
                        pendingOfferSdp = null
                        pendingOfferFromId = null
                        remoteScreenReady = false
                        clearPendingIce()
                        boundPeerId = null

                        ensurePeer(isOfferer = iAmControl, force = true)
                        if (!peerConnectionManager.hasPeerConnection()) {
                            update {
                                copy(
                                    sessionLinkState = SessionLinkState.ERROR,
                                    remoteSessionState = RemoteSessionState.ERROR,
                                    statusMessage = "WebRTC setup failed — try disconnect and reconnect"
                                )
                            }
                            return@withLock
                        }

                        boundPeerId = peer.deviceId
                        sessionRepository.setSessionLinkState(SessionLinkState.BOUND)
                        update {
                            copy(
                                boundPeer = peer,
                                sessionId = activeSessionId,
                                sessionLinkState = SessionLinkState.BOUND,
                                remoteSessionState = if (iAmControl) {
                                    RemoteSessionState.WAITING
                                } else {
                                    RemoteSessionState.WAITING
                                },
                                statusMessage = if (iAmControl) {
                                    "Connected — waiting for Remote to share screen…"
                                } else {
                                    "Control connected — tap Share screen when ready"
                                },
                                needsScreenSharePermission = !iAmControl,
                                screenShareActive = false,
                                captureGeometry = CaptureGeometry(sessionId = activeSessionId),
                                accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
                            )
                        }
                        refreshStatusNotification(
                            detail = if (iAmControl) {
                                "Controlling ${peer.displayName}"
                            } else {
                                "Being controlled by ${peer.displayName}"
                            }
                        )
                    }
                }
                is HubMessage.SessionUnbind -> Unit // client→server only
                is HubMessage.SessionUnbound -> {
                    if (boundPeerId != null || _ui.value.boundPeer != null) {
                        applySessionReleased(
                            message.reason.ifBlank { "Remote released" }
                        )
                    }
                }
                is HubMessage.ScreenReady -> {
                    if (_ui.value.mode != DeviceMode.CONTROL) return@launch
                    if (message.toId != localDeviceId) return@launch
                    remoteScreenReady = true
                    update {
                        copy(
                            statusMessage = "Remote screen ready — starting video…",
                            screenShareActive = true
                        )
                    }
                    beginScreenControlSession()
                }
                is HubMessage.RelayOffer -> {
                    ensurePeer(isOfferer = false, force = false)
                    pendingOfferFromId = message.fromId
                    pendingOfferSdp = message.sdp
                    tryAnswerPendingOffer()
                }
                is HubMessage.RelayAnswer -> {
                    peerConnectionManager.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                    ) {
                        flushPendingIceCandidates()
                    }
                }
                is HubMessage.RelayIce -> {
                    val candidate = IceCandidate(
                        message.sdpMid,
                        message.sdpMLineIndex ?: 0,
                        message.candidate
                    )
                    if (!peerConnectionManager.addIceCandidate(candidate)) {
                        pendingIceCandidates.add(candidate)
                    }
                }
                is HubMessage.CameraStart,
                is HubMessage.CameraStop -> Unit // screen-only mode; camera deferred
                is HubMessage.ErrorMsg -> {
                    sessionRepository.setLastError(message.message)
                    update { copy(lastError = message.message, statusMessage = message.message) }
                }
                else -> Unit
            }
        }
    }

    private suspend fun onScreenShareGranted(resultData: Intent) {
        sessionMutex.withLock {
            if (_ui.value.mode != DeviceMode.REMOTE) return
            val peerId = boundPeerId ?: return
            if (!peerConnectionManager.hasPeerConnection()) {
                ensurePeer(isOfferer = false, force = true)
            }
            if (!peerConnectionManager.hasPeerConnection()) {
                update {
                    copy(
                        remoteSessionState = RemoteSessionState.ERROR,
                        needsScreenSharePermission = true,
                        statusMessage = "Peer connection not ready — wait a moment and tap Share screen again"
                    )
                }
                return
            }

            update {
                copy(
                    remoteSessionState = RemoteSessionState.SCREEN_PERMISSION_GRANTED,
                    statusMessage = "Starting screen share…"
                )
            }

            ScreenShareService.startAndAwait(context)
            delay(300)

            update { copy(remoteSessionState = RemoteSessionState.STARTING_STREAM) }

            val shareResult = withContext(Dispatchers.Main) {
                peerConnectionManager.startScreenShareSafely(Intent(resultData))
            }

            if (shareResult.isFailure) {
                ScreenShareService.stop(context)
                val message = shareResult.exceptionOrNull()?.message ?: "Screen capture failed"
                update {
                    copy(
                        remoteSessionState = RemoteSessionState.ERROR,
                        needsScreenSharePermission = true,
                        screenShareActive = false,
                        statusMessage = context.getString(
                            attendance.help.device.R.string.screen_share_failed,
                            message
                        )
                    )
                }
                return
            }

            val geom = CaptureGeometry(
                sessionId = activeSessionId,
                captureWidth = peerConnectionManager.captureWidth,
                captureHeight = peerConnectionManager.captureHeight,
                rotation = 0
            )
            RemoteInputAccessibilityService.instance?.updateCaptureGeometry(
                geom.captureWidth,
                geom.captureHeight
            )
            peerConnectionManager.sendData(
                ControlMessages.encodeCaptureGeometry(
                    sessionId = activeSessionId,
                    captureWidth = geom.captureWidth,
                    captureHeight = geom.captureHeight,
                    rotation = geom.rotation
                )
            )

            update {
                copy(
                    remoteSessionState = RemoteSessionState.STREAMING,
                    needsScreenSharePermission = false,
                    screenShareActive = true,
                    captureGeometry = geom,
                    statusMessage = "Screen sharing — Control can see and operate this phone",
                    accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
                )
            }

            hubClient?.send(HubMessage.ScreenReady(fromId = localDeviceId, toId = peerId))
            tryAnswerPendingOffer()
        }
    }

    private fun tryAnswerPendingOffer() {
        val sdp = pendingOfferSdp ?: return
        val fromId = pendingOfferFromId ?: return
        if (!peerConnectionManager.isLocalMediaPublishing()) return
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
                    flushPendingIceCandidates()
                },
                onError = { err ->
                    Timber.e("createAnswer failed: %s", err)
                    scope.launch {
                        update { copy(statusMessage = "Video answer failed: $err") }
                    }
                }
            )
        }
    }

    private fun flushPendingIceCandidates() {
        if (pendingIceCandidates.isEmpty()) return
        val copy = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        copy.forEach { peerConnectionManager.addIceCandidate(it) }
    }

    private fun clearPendingIce() {
        pendingIceCandidates.clear()
    }

    private fun ensurePeer(isOfferer: Boolean, force: Boolean = false) {
        peerConnectionManager.createPeerConnection(
            isController = isOfferer,
            force = force || !peerCreated,
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
                        if (_ui.value.mode != DeviceMode.CONTROL) return@launch
                        remoteRenderer?.let { r -> inboundScreenTrack?.removeSink(r) }
                        inboundScreenTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        update {
                            copy(
                                statusMessage = "Remote screen connected — touch to control",
                                screenShareActive = true,
                                sessionLinkState = SessionLinkState.STREAMING
                            )
                        }
                    }
                }
            )
        )
        peerCreated = true
    }

    private fun handleData(payload: String) {
        scope.launch {
            if (_ui.value.sessionLinkState != SessionLinkState.STREAMING &&
                _ui.value.sessionLinkState != SessionLinkState.BOUND
            ) {
                return@launch
            }
            when (ControlMessages.parseType(payload)) {
                ControlMessages.TAP -> handleControlTap(payload)
                ControlMessages.SWIPE -> handleControlSwipe(payload)
                ControlMessages.LONG_PRESS -> handleControlLongPress(payload)
                ControlMessages.GLOBAL_ACTION -> handleControlGlobalAction(payload)
                ControlMessages.SET_TEXT -> handleControlSetText(payload)
                ControlMessages.CAPTURE_GEOMETRY -> handleCaptureGeometry(payload)
                else -> handleLegacyData(payload)
            }
        }
    }

    private fun handleLegacyData(payload: String) {
        when (CommandParser.typeOf(payload)) {
            CommandTypes.OPEN_CAMERA,
            CommandTypes.CLOSE_CAMERA -> Unit
            CommandTypes.PING ->
                peerConnectionManager.sendData(PongCommand(localDeviceId).toPayload())
            CommandTypes.PONG -> update { copy(statusMessage = "Peer alive (PONG)") }
            CommandTypes.TOUCH -> handleIncomingTouch(payload)
            CommandTypes.KEY_BACK,
            CommandTypes.KEY_HOME,
            CommandTypes.KEY_RECENTS -> handleIncomingKey(CommandParser.typeOf(payload)!!)
        }
    }

    private fun handleControlTap(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return
        val x = obj.get("x")?.asFloat ?: return
        val y = obj.get("y")?.asFloat ?: return
        if (!ControlMessages.isValidNormalized(x) || !ControlMessages.isValidNormalized(y)) return
        val a11y = RemoteInputAccessibilityService.instance ?: return
        a11y.tapNormalized(x, y)
    }

    private fun handleControlSwipe(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return
        val duration = obj.get("durationMs")?.asLong ?: 300L
        val points = obj.getAsJsonArray("points")?.mapNotNull { el ->
            val p = el.asJsonObject
            val x = p.get("x")?.asFloat
            val y = p.get("y")?.asFloat
            if (x != null && y != null && ControlMessages.isValidNormalized(x) && ControlMessages.isValidNormalized(y)) {
                x to y
            } else null
        } ?: return
        RemoteInputAccessibilityService.instance?.swipeNormalizedPoints(points, duration)
    }

    private fun handleControlLongPress(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return
        val x = obj.get("x")?.asFloat ?: return
        val y = obj.get("y")?.asFloat ?: return
        val duration = obj.get("durationMs")?.asLong ?: 700L
        RemoteInputAccessibilityService.instance?.longPressNormalized(x, y, duration)
    }

    private fun handleControlGlobalAction(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val action = runCatching {
            gson.fromJson(payload, JsonObject::class.java).get("action")?.asString
        }.getOrNull() ?: return
        val a11y = RemoteInputAccessibilityService.instance ?: return
        when (action) {
            "BACK" -> a11y.pressBack()
            "HOME" -> a11y.pressHome()
            "RECENTS" -> a11y.pressRecents()
        }
    }

    private fun handleControlSetText(payload: String) {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        val text = runCatching {
            gson.fromJson(payload, JsonObject::class.java).get("text")?.asString
        }.getOrNull()?.take(512) ?: return
        RemoteInputAccessibilityService.instance?.setTextOnFocusedField(text)
    }

    private fun handleCaptureGeometry(payload: String) {
        if (_ui.value.mode != DeviceMode.CONTROL) return
        val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return
        val sessionId = obj.get("sessionId")?.asString.orEmpty()
        if (sessionId.isNotBlank() && activeSessionId.isNotBlank() && sessionId != activeSessionId) return
        val geom = CaptureGeometry(
            sessionId = sessionId,
            captureWidth = obj.get("captureWidth")?.asInt ?: 0,
            captureHeight = obj.get("captureHeight")?.asInt ?: 0,
            rotation = obj.get("rotation")?.asInt ?: 0
        )
        update { copy(captureGeometry = geom) }
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

    private suspend fun stopScreenLocally() {
        withContext(Dispatchers.Main) {
            peerConnectionManager.stopScreenShare()
            ScreenShareService.stop(context)
            val stillBound = boundPeerId != null
            update {
                copy(
                    dualCamera = DualCameraSessionState(),
                    screenShareActive = false,
                    needsScreenSharePermission = stillBound && mode == DeviceMode.REMOTE,
                    statusMessage = if (stillBound) {
                        "Screen share stopped — tap Allow screen share again"
                    } else {
                        "Media stopped"
                    }
                )
            }
        }
    }

    private suspend fun stopMediaFully() {
        stopScreenLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundScreenTrack = null
        pendingOfferSdp = null
        pendingOfferFromId = null
        remoteScreenReady = false
        clearPendingIce()
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
