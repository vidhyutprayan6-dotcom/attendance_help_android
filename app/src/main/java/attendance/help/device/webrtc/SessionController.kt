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
import attendance.help.device.domain.model.WebRtcTransportDiagnostics
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.network.hub.HubClient
import attendance.help.device.network.hub.HubMessage
import attendance.help.device.service.CameraCaptureService
import attendance.help.device.service.LinkStatusService
import attendance.help.device.service.ScreenShareService
import attendance.help.device.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import attendance.help.device.utils.DeviceHints
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
    private val screenShareCoordinator: ScreenShareCoordinator,
    private val cameraSessionManager: CameraSessionManager
) {
    companion object {
        /** Minimum bind duration before automatic server/client release is honored. */
        const val MIN_SESSION_MS = 15_000L
    }

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
    private var cameraRenderer: SurfaceViewRenderer? = null
    private var localCameraPreview: SurfaceViewRenderer? = null
    private var inboundCameraTrack: VideoTrack? = null
    private var cameraSessionActive = false
    private var cameraRenegotiateAttempts = 0
    private var remoteCameraFirstFrame = false
    private val cameraDebugLines = ArrayDeque<String>(12)
    private var remoteScreenReady = false
    private var sessionEpoch = 0
    private var sessionBoundAtMs: Long = 0L
    private var webrtcRetryCount = 0
    private var handlingWebRtcFailure = false
    /** Monotonic id for each offer/answer exchange; prevents stale SDP from breaking signaling. */
    private var negotiationGeneration = 0
    private var awaitingAnswerGeneration = 0
    private var answeringOfferGeneration = 0
    private var answerSentForGeneration = 0
    private var offerInFlight = false
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
                        peerCreated = false
                        update {
                            copy(
                                remoteSessionState = RemoteSessionState.WAITING,
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
        if (DeviceHints.isProbablyEmulator()) {
            scope.launch {
                withContext(Dispatchers.Main) {
                    peerConnectionManager.ensureInitialized()
                }
            }
        }
        Timber.i(
            "SessionController deviceId=%s emulator=%s",
            localDeviceId,
            DeviceHints.isProbablyEmulator()
        )
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

    fun bindCameraRenderer(renderer: SurfaceViewRenderer?) {
        cameraRenderer?.let { old -> inboundCameraTrack?.removeSink(old) }
        cameraRenderer = renderer
        renderer?.let { r ->
            peerConnectionManager.initRenderer(r, mirror = false)
            inboundCameraTrack?.let { track ->
                track.setEnabled(true)
                r.post {
                    runCatching { track.addSink(r) }
                    r.requestLayout()
                    logCameraDebug("REMOTE renderer bound track=${track.id()} sinkAdded")
                }
            } ?: logCameraDebug("REMOTE renderer bound (no track yet)")
        }
    }

    fun unbindCameraRenderer() {
        cameraRenderer?.let { r -> inboundCameraTrack?.removeSink(r) }
        cameraRenderer = null
    }

    fun bindLocalCameraPreview(renderer: SurfaceViewRenderer?) {
        localCameraPreview?.let { old -> peerConnectionManager.detachLocalCameraPreview(old) }
        localCameraPreview = renderer
        renderer?.let {
            peerConnectionManager.initRenderer(it, mirror = true)
            peerConnectionManager.attachLocalCameraPreview(it)
        }
    }

    fun unbindLocalCameraPreview() {
        localCameraPreview?.let { peerConnectionManager.detachLocalCameraPreview(it) }
        localCameraPreview = null
    }

    /**
     * Activate camera mode from either phone.
     * CONTROL always opens the published camera; REMOTE may open local-only (not published).
     * No SDP renegotiation — camera m-line was negotiated at initial WebRTC setup.
     */
    fun startCameraSession() {
        scope.launch {
            sessionMutex.withLock {
                if (_ui.value.boundPeer == null) {
                    update { copy(statusMessage = "Connect to a peer before starting cameras") }
                    return@withLock
                }
                if (!_ui.value.transportConnected) {
                    update { copy(statusMessage = "Wait until WebRTC is connected, then start cameras") }
                    return@withLock
                }
                val peerId = boundPeerId ?: return@withLock
                val cmd = cameraSessionManager.newCommandId()
                if (!cameraSessionManager.beginStart(cmd)) {
                    update { copy(statusMessage = "Cameras already starting or active") }
                    return@withLock
                }
                cameraSessionManager.markPreparing()
                Timber.tag("CAMERA_SYNC").i(
                    "CAMERA_START_REQUEST role=%s sessionId=%s cmd=%s",
                    _ui.value.mode,
                    activeSessionId,
                    cmd
                )

                when (_ui.value.mode) {
                    DeviceMode.CONTROL -> {
                        val ok = activateControlCameraLocked()
                        if (!ok) return@withLock
                        hubClient?.send(
                            HubMessage.CameraStart(fromId = localDeviceId, toId = peerId)
                        )
                        markCameraUiActiveLocked(
                            if (peerConnectionManager.isControlCameraSenderAttached()) {
                                "Cameras active — Control camera streaming"
                            } else {
                                "Cameras on — negotiating video to Remote…"
                            }
                        )
                    }
                    DeviceMode.REMOTE -> {
                        // Ask Control to open published camera; open local sensor optionally.
                        hubClient?.send(
                            HubMessage.CameraStart(fromId = localDeviceId, toId = peerId)
                        )
                        val localOnly = withContext(Dispatchers.Main) {
                            peerConnectionManager.startRemoteLocalCameraOnly()
                        }
                        if (localOnly.isFailure) {
                            Timber.tag("CAMERA_CAPTURE").w(
                                "REMOTE local camera optional failed: %s",
                                localOnly.exceptionOrNull()?.message
                            )
                        }
                        cameraSessionActive = true
                        cameraSessionManager.markActive()
                        ensureCameraReceivePathLocked()
                        update {
                            copy(
                                dualCamera = DualCameraSessionState(
                                    isActive = true,
                                    bothCamerasOn = true,
                                    controlShowsRemoteFeed = true,
                                    remoteShowsControlFeed = inboundCameraTrack != null
                                ),
                                statusMessage = if (inboundCameraTrack != null) {
                                    "Cameras active — Control camera received"
                                } else {
                                    "Cameras on — waiting for Control camera video…"
                                }
                            )
                        }
                    }
                    else -> {
                        cameraSessionManager.markError("INVALID_ROLE")
                    }
                }
            }
        }
    }

    fun stopCameraSession() {
        scope.launch {
            sessionMutex.withLock {
                val peerId = boundPeerId
                val cmd = cameraSessionManager.newCommandId()
                cameraSessionManager.beginStop(cmd)
                if (peerId != null) {
                    hubClient?.send(
                        HubMessage.CameraStop(fromId = localDeviceId, toId = peerId)
                    )
                }
                stopCamerasLocallyLocked()
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun logCameraDebug(line: String) {
        val stamped = "${System.currentTimeMillis() % 100_000}: $line"
        cameraDebugLines.addLast(stamped)
        while (cameraDebugLines.size > 10) cameraDebugLines.removeFirst()
        Timber.tag("CAMERA_SYNC").i(line)
        if (BuildConfig.DEBUG) {
            val summary = peerConnectionManager.cameraDebugSummary()
            update {
                copy(
                    cameraDebugLog = (cameraDebugLines + summary).joinToString("\n")
                )
            }
        }
    }

    /** CONTROL: FGS + start capture into camera track (pre-negotiated or pending Remote re-offer). */
    private suspend fun activateControlCameraLocked(): Boolean {
        if (!hasCameraPermission()) {
            cameraSessionManager.markError("PERMISSION_REQUIRED")
            update {
                copy(statusMessage = context.getString(R.string.permission_camera_required))
            }
            return false
        }
        Timber.tag("CAMERA_SYNC").i("CAMERA_PERMISSION_OK")
        logCameraDebug("CAMERA_PERMISSION_OK")
        withContext(Dispatchers.Main) {
            peerConnectionManager.ensureControlCameraSenderReady()
            peerConnectionManager.logTransceiverState("CONTROL_PRE_CAPTURE")
        }
        runCatching { CameraCaptureService.start(context) }
        val result = withContext(Dispatchers.Main) {
            peerConnectionManager.startControlCameraCapture(localPreview = localCameraPreview)
        }
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "CAMERA_ERROR"
            cameraSessionManager.markError(reason)
            runCatching { CameraCaptureService.stop(context) }
            logCameraDebug("CAMERA_ERROR $reason")
            update { copy(statusMessage = "Camera failed: $reason") }
            return false
        }
        logCameraDebug(
            "CONTROL_CAMERA_CAPTURER_START attached=${peerConnectionManager.isControlCameraSenderAttached()}"
        )
        if (!peerConnectionManager.isControlCameraSenderAttached()) {
            logCameraDebug("WARN sender not attached — waiting for Remote re-offer")
        }
        cameraSessionActive = true
        cameraSessionManager.markActive()
        return true
    }

    /**
     * REMOTE only: if Control camera track is not inbound yet, add RECV slot and re-offer.
     * Keeps Remote as sole offerer (screen + camera m-lines). Safe while already CONNECTED.
     */
    private fun ensureCameraReceivePathLocked() {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        if (inboundCameraTrack != null) {
            Timber.tag("CAMERA_WEBRTC").i("camera receive path OK — inbound track present")
            return
        }
        peerConnectionManager.ensureRemoteCameraReceiverSlot()
        peerConnectionManager.logTransceiverState("REMOTE_CAMERA_ENSURE")
        if (offerInFlight) {
            Timber.tag("CAMERA_WEBRTC").i("camera renegotiate deferred — offer in flight")
            return
        }
        if (cameraRenegotiateAttempts >= 2) {
            Timber.tag("CAMERA_WEBRTC").w("camera renegotiate attempts exhausted")
            update {
                copy(statusMessage = "Control camera not received — stop/start cameras or reconnect")
            }
            return
        }
        cameraRenegotiateAttempts++
        logCameraDebug("CAMERA_SLOT_RENEGOTIATE attempt=$cameraRenegotiateAttempts")
        update { copy(statusMessage = "Negotiating Control camera video…") }
        beginRemoteOfferLocked()
    }

    private fun markCameraUiActiveLocked(message: String) {
        update {
            copy(
                dualCamera = DualCameraSessionState(
                    isActive = true,
                    bothCamerasOn = true,
                    controlShowsRemoteFeed = true,
                    remoteShowsControlFeed = true
                ),
                statusMessage = message
            )
        }
    }

    private fun stopCamerasLocallyLocked() {
        Timber.tag("CAMERA_SYNC").i("CAMERA_STOPPING")
        cameraSessionActive = false
        cameraRenegotiateAttempts = 0
        remoteCameraFirstFrame = false
        cameraDebugLines.clear()
        peerConnectionManager.stopFrontCamera()
        runCatching { CameraCaptureService.stop(context) }
        cameraSessionManager.markStopped()
        update {
            copy(
                dualCamera = DualCameraSessionState(),
                cameraDebugLog = if (BuildConfig.DEBUG) peerConnectionManager.cameraDebugSummary() else "",
                statusMessage = if (transportConnected) {
                    "Cameras stopped"
                } else {
                    statusMessage
                }
            )
        }
        Timber.tag("CAMERA_SYNC").i("CAMERA_STOPPED")
    }

    fun requestScreenSharePermission() {
        if (_ui.value.mode != DeviceMode.REMOTE) return
        if (_ui.value.boundPeer == null) {
            update { copy(statusMessage = "No Control connected yet") }
            return
        }
        refreshAccessibilityState()
        if (!RemoteInputAccessibilityService.isEnabled()) {
            update {
                copy(
                    statusMessage = "Enable Accessibility first, then share screen",
                    accessibilityEnabled = false
                )
            }
            return
        }
        update {
            copy(
                remoteSessionState = RemoteSessionState.REQUESTING_SCREEN_PERMISSION,
                statusMessage = "Allow screen capture when prompted…"
            )
        }
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
            if (hasActiveBind()) {
                Timber.d("announcePresence skipped — active bind in progress")
                return@launch
            }
            val mode = _ui.value.mode
            if (mode == DeviceMode.NONE) return@launch
            registerWithHub(mode)
        }
    }

    private suspend fun registerWithHub(mode: DeviceMode) {
        val name = sessionRepository.displayName.first()
        hubClient?.send(
            HubMessage.Register(
                deviceId = localDeviceId,
                displayName = name,
                mode = mode.name
            )
        )
        if (mode == DeviceMode.CONTROL && !hasActiveBind()) {
            hubClient?.send(HubMessage.RequestRemotes(localDeviceId))
        }
        Timber.i("registerWithHub mode=%s bound=%s", mode, hasActiveBind())
    }

    private fun hasActiveBind(): Boolean =
        boundPeerId != null || _ui.value.boundPeer != null

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

    /** Remote is the sole WebRTC offerer — creates offer after screen share is active. */
    private suspend fun beginRemoteOffer() {
        sessionMutex.withLock { beginRemoteOfferLocked() }
    }

    /** Caller must hold [sessionMutex] when invoking from an already-locked block. */
    private fun beginRemoteOfferLocked() {
        val peerId = boundPeerId ?: return
        if (_ui.value.mode != DeviceMode.REMOTE) return
        if (!peerConnectionManager.isLocalMediaPublishing()) return
        if (offerInFlight) {
            Timber.d("beginRemoteOffer skipped — offer already in flight")
            return
        }

        negotiationGeneration++
        val gen = negotiationGeneration
        awaitingAnswerGeneration = gen
        offerInFlight = true
        clearPendingIce()

        // Pre-negotiate CONTROL camera RECV m-line with the initial offer (no later renegotiation).
        peerConnectionManager.ensureRemoteCameraReceiverSlot()

        webrtcLog("WEBRTC createOffer")
        peerConnectionManager.createOffer(
            onSuccess = { sdp ->
                webrtcLog("WEBRTC setLocalOffer success")
                if (peerConnectionManager.signalingState() !=
                    PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                ) {
                    Timber.e(
                        "Offer setLocalDescription succeeded but signaling=%s (expected HAVE_LOCAL_OFFER)",
                        peerConnectionManager.signalingState()
                    )
                }
                webrtcLog("WEBRTC sendOffer")
                hubClient?.send(
                    HubMessage.RelayOffer(
                        fromId = localDeviceId,
                        toId = peerId,
                        sdp = sdp.description,
                        negotiationGen = gen,
                        sessionId = activeSessionId
                    )
                )
            },
            onError = { err ->
                offerInFlight = false
                scope.launch {
                    update { copy(statusMessage = "Video offer failed: $err") }
                }
            }
        )
    }

    private fun syncDiagnosticsContext() {
        val role = when (_ui.value.mode) {
            DeviceMode.REMOTE -> "REMOTE"
            DeviceMode.CONTROL -> "CONTROL"
            else -> "NONE"
        }
        peerConnectionManager.updateDiagnosticsContext(
            localDeviceId = localDeviceId,
            remoteDeviceId = boundPeerId.orEmpty(),
            sessionId = activeSessionId,
            role = role
        )
    }

    private fun evaluateTransportState(diag: WebRtcTransportDiagnostics) {
        val captureActive = diag.captureActive || _ui.value.screenShareActive
        val connected = diag.transportConnected
        val turnWarning = diag.turnConfigured && diag.localRelayCandidates == 0 &&
            diag.iceGatheringState == "COMPLETE"
        update {
            copy(
                webrtcDiagnostics = diag,
                transportConnected = connected,
                webrtcState = diag.connectionState,
                screenShareActive = captureActive,
                remoteSessionState = when {
                    boundPeer == null -> RemoteSessionState.DISCONNECTED
                    captureActive && connected -> RemoteSessionState.STREAMING
                    captureActive -> RemoteSessionState.STARTING_STREAM
                    boundPeer != null -> RemoteSessionState.WAITING
                    else -> remoteSessionState
                },
                sessionLinkState = if (connected) SessionLinkState.STREAMING else sessionLinkState,
                statusMessage = when {
                    connected && mode == DeviceMode.CONTROL ->
                        "Remote screen connected — touch to control"
                    connected && mode == DeviceMode.REMOTE ->
                        "Session connected — Control can see and operate this phone"
                    turnWarning ->
                        "TURN configured but no relay ICE candidate — check TURN reachability from this device"
                    captureActive ->
                        "WebRTC connecting — ICE ${diag.iceConnectionState} (local relay=${diag.localRelayCandidates})"
                    else -> statusMessage
                }
            )
        }
        if (connected) {
            webrtcRetryCount = 0
            offerInFlight = false
        }
    }

    private fun webrtcLog(step: String) {
        WebRtcSignalingLog.log(
            step = step,
            sessionId = activeSessionId,
            deviceId = localDeviceId,
            signalingState = peerConnectionManager.signalingState()
        )
    }

    private fun answerIncomingOfferLocked(fromId: String, sdp: String, gen: Int) {
        webrtcLog("WEBRTC receiveOffer")
        peerConnectionManager.applyRemoteOffer(
            SessionDescription(SessionDescription.Type.OFFER, sdp),
            onDone = {
                webrtcLog("WEBRTC setRemoteOffer success")
                flushPendingIceCandidates()
                // Attach CONTROL camera sender track before answer so SDP includes camera send m-line.
                peerConnectionManager.ensureControlCameraSenderReady()
                webrtcLog("WEBRTC createAnswer")
                peerConnectionManager.createAnswer(
                    onSuccess = { answer ->
                        webrtcLog("WEBRTC setLocalAnswer success")
                        if (gen > 0 && gen == answerSentForGeneration) {
                            Timber.w("Ignoring duplicate answer send gen=%d", gen)
                            return@createAnswer
                        }
                        answerSentForGeneration = gen
                        webrtcLog("WEBRTC sendAnswer")
                        hubClient?.send(
                            HubMessage.RelayAnswer(
                                fromId = localDeviceId,
                                toId = fromId,
                                sdp = answer.description,
                                negotiationGen = gen,
                                sessionId = activeSessionId
                            )
                        )
                        flushPendingIceCandidates()
                        if (_ui.value.mode == DeviceMode.CONTROL && cameraSessionActive) {
                            peerConnectionManager.reattachControlCameraSenderAfterNegotiation()
                            localCameraPreview?.let { preview ->
                                peerConnectionManager.attachLocalCameraPreview(preview)
                            }
                            logCameraDebug(
                                "CONTROL reattached after answer attached=" +
                                    peerConnectionManager.isControlCameraSenderAttached()
                            )
                        }
                    },
                    onError = { err ->
                        Timber.e("createAnswer failed: %s", err)
                        scope.launch {
                            update { copy(statusMessage = "Video answer failed: $err") }
                        }
                    }
                )
            },
            onError = { err ->
                Timber.e("setRemote offer failed: %s", err)
                scope.launch {
                    update { copy(statusMessage = "Video negotiation failed: $err") }
                }
            }
        )
    }

    /**
     * Validates relay SDP/ICE messages: not self-echoed, addressed to us, from bound peer,
     * and matching the active session when sessionId is present.
     */
    private fun isValidRelayMessage(fromId: String, toId: String, sessionId: String): Boolean {
        if (fromId == localDeviceId) {
            Timber.w("Ignoring self-echoed relay message fromId=%s", fromId)
            return false
        }
        if (toId != localDeviceId) {
            Timber.w("Ignoring relay not addressed to us toId=%s local=%s", toId, localDeviceId)
            return false
        }
        val peerId = boundPeerId
        if (peerId == null || fromId != peerId) {
            Timber.w("Ignoring relay from unknown peer fromId=%s bound=%s", fromId, peerId)
            return false
        }
        if (activeSessionId.isNotBlank() && sessionId.isNotBlank() && sessionId != activeSessionId) {
            Timber.w(
                "Ignoring relay for stale session msg=%s active=%s",
                sessionId,
                activeSessionId
            )
            return false
        }
        return true
    }

    private fun resetNegotiationState() {
        negotiationGeneration = 0
        awaitingAnswerGeneration = 0
        answeringOfferGeneration = 0
        answerSentForGeneration = 0
        offerInFlight = false
    }

    /** End control of the remote; remote becomes available again for other controls. */
    fun releaseRemoteControl() {
        scope.launch {
            sessionMutex.withLock {
                val peerId = boundPeerId
                if (peerId != null) {
                    hubClient?.send(
                        HubMessage.SessionUnbind(
                            fromId = localDeviceId,
                            peerId = peerId
                        )
                    )
                }
                applySessionReleasedLocked("Disconnected from remote")
            }
        }
    }

    private suspend fun applySessionReleased(message: String) {
        sessionMutex.withLock {
            applySessionReleasedLocked(message)
        }
    }

    private suspend fun applySessionReleasedLocked(message: String) {
        stopScreenLocally()
        peerConnectionManager.release()
        peerCreated = false
        inboundScreenTrack = null
        boundPeerId = null
        remoteScreenReady = false
        clearPendingIce()
        resetNegotiationState()
        sessionEpoch++
        activeSessionId = ""
        sessionBoundAtMs = 0L
        stopCamerasLocallyLocked()
        cameraSessionManager.reset()
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
                        if (hasActiveBind()) {
                            registerWithHub(mode)
                        } else {
                            setMode(mode)
                        }
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
                    val turn = message.turnConfig ?: TurnServerConfig()
                    peerConnectionManager.setTurnConfig(turn)
                    Timber.i(
                        "RegisterAck turnUrls=%d turnUserBlank=%s",
                        turn.urls.size,
                        turn.username.isBlank()
                    )
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
                        remoteScreenReady = false
                        clearPendingIce()
                        resetNegotiationState()
                        boundPeerId = null
                        webrtcRetryCount = 0

                        peerCreated = false

                        boundPeerId = peer.deviceId
                        sessionBoundAtMs = System.currentTimeMillis()
                        syncDiagnosticsContext()
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
                                accessibilityEnabled = RemoteInputAccessibilityService.isEnabled(),
                                webrtcState = "NEW"
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
                    sessionMutex.withLock {
                        val reason = message.reason.ifBlank { "Remote released" }
                        if (shouldIgnoreAutoUnbound(reason)) {
                            Timber.w(
                                "Ignoring premature session_unbound reason=%s elapsed=%dms",
                                reason,
                                System.currentTimeMillis() - sessionBoundAtMs
                            )
                            return@withLock
                        }
                        if (boundPeerId != null || _ui.value.boundPeer != null) {
                            applySessionReleasedLocked(reason)
                        }
                    }
                }
                is HubMessage.ScreenReady -> {
                    if (_ui.value.mode != DeviceMode.CONTROL) return@launch
                    if (message.toId != localDeviceId) return@launch
                    remoteScreenReady = true
                    update {
                        copy(
                            statusMessage = "Remote screen ready — waiting for WebRTC offer…",
                            screenShareActive = true,
                            remoteSessionState = RemoteSessionState.STARTING_STREAM
                        )
                    }
                }
                is HubMessage.RelayOffer -> {
                    sessionMutex.withLock {
                        if (!isValidRelayMessage(message.fromId, message.toId, message.sessionId)) {
                            return@withLock
                        }
                        val gen = message.negotiationGen
                        when (_ui.value.mode) {
                            DeviceMode.CONTROL -> {
                                // Initial session: Control answers Remote's offer.
                                if (gen > 0 && gen <= answerSentForGeneration) {
                                    Timber.w(
                                        "Ignoring duplicate/stale offer gen=%d (answered=%d)",
                                        gen,
                                        answerSentForGeneration
                                    )
                                    return@withLock
                                }
                                if (!ensurePeer(isControlDevice = true, force = false)) {
                                    Timber.e("RelayOffer ignored — Control peer not ready")
                                    update { copy(statusMessage = "WebRTC setup failed — try disconnect and reconnect") }
                                    return@withLock
                                }
                                answeringOfferGeneration = gen
                                clearPendingIce()
                                answerIncomingOfferLocked(message.fromId, message.sdp, gen)
                            }
                            DeviceMode.REMOTE -> {
                                // Remote is sole initial offerer — ignore peer offers (no camera renegotiation).
                                Timber.d("RelayOffer ignored on REMOTE — Remote does not answer")
                            }
                            else -> Unit
                        }
                    }
                }
                is HubMessage.RelayAnswer -> {
                    sessionMutex.withLock {
                        if (!isValidRelayMessage(message.fromId, message.toId, message.sessionId)) {
                            return@withLock
                        }
                        val gen = message.negotiationGen
                        if (_ui.value.mode != DeviceMode.REMOTE) {
                            Timber.d("RelayAnswer ignored — only Remote applies answers")
                            return@withLock
                        }
                        if (gen > 0 && gen != awaitingAnswerGeneration) {
                            Timber.w(
                                "Ignoring stale answer gen=%d (awaiting=%d)",
                                gen,
                                awaitingAnswerGeneration
                            )
                            return@withLock
                        }
                        val state = peerConnectionManager.signalingState()
                        if (state != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                            WebRtcSignalingLog.log(
                                step = "Ignoring unexpected/duplicate answer. state=${state?.name}",
                                sessionId = activeSessionId,
                                deviceId = localDeviceId,
                                signalingState = state
                            )
                            return@withLock
                        }
                        webrtcLog("WEBRTC receiveAnswer")
                        peerConnectionManager.applyRemoteAnswer(
                            SessionDescription(SessionDescription.Type.ANSWER, message.sdp),
                            onDone = {
                                webrtcLog("WEBRTC setRemoteAnswer success")
                                offerInFlight = false
                                flushPendingIceCandidates()
                                peerConnectionManager.logTransceiverState("REMOTE_AFTER_ANSWER")
                                scope.launch {
                                    sessionMutex.withLock {
                                        if (cameraSessionActive && inboundCameraTrack == null) {
                                            peerConnectionManager.pollInboundCameraTrack()?.let { track ->
                                                logCameraDebug("pollInboundCameraTrack id=${track.id()}")
                                                attachInboundCameraTrackLocked(track)
                                            }
                                            if (inboundCameraTrack == null) {
                                                ensureCameraReceivePathLocked()
                                            }
                                        } else if (cameraSessionActive && inboundCameraTrack != null) {
                                            rebindInboundCameraRendererLocked()
                                        }
                                    }
                                }
                            },
                            onError = { err ->
                                Timber.e("RelayAnswer failed: %s", err)
                                scope.launch {
                                    update { copy(statusMessage = "Video answer failed: $err") }
                                }
                            }
                        )
                    }
                }
                is HubMessage.RelayIce -> {
                    sessionMutex.withLock {
                        if (!isValidRelayMessage(message.fromId, message.toId, message.sessionId)) {
                            return@withLock
                        }
                        val gen = message.negotiationGen
                        if (_ui.value.mode == DeviceMode.REMOTE &&
                            gen > 0 &&
                            gen != awaitingAnswerGeneration &&
                            gen != answeringOfferGeneration
                        ) {
                            return@withLock
                        }
                        if (_ui.value.mode == DeviceMode.CONTROL &&
                            gen > 0 &&
                            gen != answeringOfferGeneration &&
                            gen != awaitingAnswerGeneration
                        ) {
                            return@withLock
                        }
                        val candidate = IceCandidate(
                            message.sdpMid,
                            message.sdpMLineIndex ?: 0,
                            message.candidate
                        )
                        if (!peerConnectionManager.addRemoteIceCandidate(candidate)) {
                            pendingIceCandidates.add(candidate)
                        }
                    }
                }
                is HubMessage.WebRtcReconnect -> {
                    sessionMutex.withLock {
                        if (!isValidRelayMessage(message.fromId, message.toId, message.sessionId)) {
                            return@withLock
                        }
                        clearPendingIce()
                        resetNegotiationState()
                        inboundScreenTrack = null
                        peerConnectionManager.teardownPeerForReconnect()
                        peerCreated = false
                        update {
                            copy(
                                statusMessage = "Peer requested WebRTC reconnect — waiting for new offer…",
                                transportConnected = false
                            )
                        }
                    }
                }
                is HubMessage.CameraStart -> {
                    sessionMutex.withLock {
                        if (message.toId != localDeviceId) return@withLock
                        if (message.fromId == localDeviceId) return@withLock
                        val cmd = cameraSessionManager.newCommandId()
                        Timber.tag("CAMERA_SYNC").i(
                            "CAMERA_START_REQUEST peer=%s role=%s",
                            message.fromId,
                            _ui.value.mode
                        )
                        when (_ui.value.mode) {
                            DeviceMode.CONTROL -> {
                                if (!cameraSessionManager.beginStart(cmd)) {
                                    // Already active — peer just syncing
                                    if (cameraSessionActive) return@withLock
                                } else {
                                    cameraSessionManager.markPreparing()
                                }
                                val ok = activateControlCameraLocked()
                                if (ok) {
                                    markCameraUiActiveLocked("Cameras active — Control camera streaming")
                                }
                            }
                            DeviceMode.REMOTE -> {
                                if (!cameraSessionManager.beginStart(cmd)) {
                                    if (cameraSessionActive) {
                                        ensureCameraReceivePathLocked()
                                        return@withLock
                                    }
                                } else {
                                    cameraSessionManager.markPreparing()
                                }
                                val localOnly = withContext(Dispatchers.Main) {
                                    peerConnectionManager.startRemoteLocalCameraOnly()
                                }
                                cameraSessionActive = true
                                cameraSessionManager.markActive()
                                ensureCameraReceivePathLocked()
                                update {
                                    copy(
                                        dualCamera = DualCameraSessionState(
                                            isActive = true,
                                            bothCamerasOn = true,
                                            controlShowsRemoteFeed = true,
                                            remoteShowsControlFeed = inboundCameraTrack != null
                                        ),
                                        statusMessage = when {
                                            inboundCameraTrack != null ->
                                                "Cameras active — Control camera received"
                                            localOnly.isFailure ->
                                                "Cameras on — negotiating Control camera…"
                                            else ->
                                                "Cameras on — waiting for Control camera video…"
                                        }
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                is HubMessage.CameraStop -> {
                    sessionMutex.withLock {
                        if (message.toId != localDeviceId) return@withLock
                        cameraSessionManager.beginStop(cameraSessionManager.newCommandId())
                        stopCamerasLocallyLocked()
                    }
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
        sessionMutex.withLock {
            if (_ui.value.mode != DeviceMode.REMOTE) return
            val peerId = boundPeerId ?: return

            update {
                copy(
                    remoteSessionState = RemoteSessionState.SCREEN_PERMISSION_GRANTED,
                    statusMessage = "Starting screen share…"
                )
            }

            ScreenShareService.startAndAwait(context)
            delay(300)

            update { copy(remoteSessionState = RemoteSessionState.STARTING_STREAM) }

            clearPendingIce()

            val shareResult = withContext(Dispatchers.Main) {
                var lastResult: Result<Unit> = Result.failure(
                    IllegalStateException("WebRTC peer could not be created")
                )
                repeat(3) { attempt ->
                    webrtcLog("WEBRTC createPeerConnection")
                    lastResult = peerConnectionManager.prepareRemoteScreenShare(
                        listeners = webRtcListeners(),
                        permissionResultData = Intent(resultData),
                        forceRecreatePeer = attempt > 0
                    )
                    if (lastResult.isSuccess) return@withContext lastResult
                    delay(150)
                }
                lastResult
            }

            if (shareResult.isFailure) {
                peerCreated = false
                ScreenShareService.stop(context)
                val message = shareResult.exceptionOrNull()?.message ?: "Screen capture failed"
                update {
                    copy(
                        remoteSessionState = RemoteSessionState.WAITING,
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
            peerCreated = true

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
                    remoteSessionState = RemoteSessionState.STARTING_STREAM,
                    needsScreenSharePermission = false,
                    screenShareActive = true,
                    captureGeometry = geom,
                    statusMessage = "Screen capture active — starting WebRTC…",
                    accessibilityEnabled = RemoteInputAccessibilityService.isEnabled()
                )
            }

            syncDiagnosticsContext()

            hubClient?.send(HubMessage.ScreenReady(fromId = localDeviceId, toId = peerId))
            beginRemoteOfferLocked()
        }
    }

    private fun flushPendingIceCandidates() {
        if (pendingIceCandidates.isEmpty()) return
        val copy = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        copy.forEach { peerConnectionManager.addRemoteIceCandidate(it) }
    }

    private fun clearPendingIce() {
        pendingIceCandidates.clear()
    }

    private fun ensurePeer(isControlDevice: Boolean, force: Boolean = false): Boolean {
        syncDiagnosticsContext()
        val effectiveForce = force || !peerCreated
        if (effectiveForce) {
            webrtcLog("WEBRTC createPeerConnection")
        }
        val ok = peerConnectionManager.createPeerConnection(
            isControlDevice = isControlDevice,
            force = effectiveForce,
            listeners = webRtcListeners()
        )
        if (ok) {
            peerCreated = true
        }
        return ok && peerConnectionManager.hasPeerConnection()
    }

    private fun webRtcListeners(): WebRtcListeners = WebRtcListeners(
        onIceCandidate = { c ->
            val peerId = boundPeerId ?: return@WebRtcListeners
            val gen = when (_ui.value.mode) {
                DeviceMode.REMOTE -> {
                    if (awaitingAnswerGeneration > 0) awaitingAnswerGeneration
                    else answeringOfferGeneration
                }
                DeviceMode.CONTROL -> answeringOfferGeneration
                else -> 0
            }
            hubClient?.send(
                HubMessage.RelayIce(
                    fromId = localDeviceId,
                    toId = peerId,
                    candidate = c.sdp,
                    sdpMid = c.sdpMid,
                    sdpMLineIndex = c.sdpMLineIndex,
                    negotiationGen = gen,
                    sessionId = activeSessionId
                )
            )
        },
        onConnectionChange = { state ->
            scope.launch {
                Timber.i("WebRTC peer state -> %s", state)
                evaluateTransportState(peerConnectionManager.diagnosticsSnapshot())
                when (state) {
                    PeerConnection.PeerConnectionState.FAILED -> {
                        handleWebRtcFailure("WebRTC connection failed")
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        if (_ui.value.transportConnected) {
                            handleWebRtcFailure("WebRTC disconnected")
                        }
                    }
                    else -> Unit
                }
            }
        },
        onIceConnectionChange = { iceState ->
            Timber.i("WebRTC ICE state -> %s", iceState)
            if (iceState == PeerConnection.IceConnectionState.FAILED) {
                scope.launch { handleWebRtcFailure("ICE connection failed") }
            }
        },
        onDataMessage = { handleData(it) },
        onRemoteVideoTrack = { track ->
            scope.launch {
                if (_ui.value.mode != DeviceMode.CONTROL) return@launch
                remoteRenderer?.let { r -> inboundScreenTrack?.removeSink(r) }
                inboundScreenTrack = track
                remoteRenderer?.let { track.addSink(it) }
                evaluateTransportState(peerConnectionManager.diagnosticsSnapshot())
            }
        },
        onRemoteCameraTrack = { track ->
            scope.launch {
                if (_ui.value.mode != DeviceMode.REMOTE) return@launch
                attachInboundCameraTrackLocked(track)
            }
        },
        onDiagnosticsChanged = { diag ->
            scope.launch { evaluateTransportState(diag) }
        }
    )

    private fun rebindInboundCameraRendererLocked() {
        val track = inboundCameraTrack ?: return
        val renderer = cameraRenderer ?: return
        track.setEnabled(true)
        renderer.post {
            runCatching {
                track.addSink(renderer)
                renderer.requestLayout()
            }
            logCameraDebug("REMOTE rebind renderer track=${track.id()}")
        }
    }

    private fun attachInboundCameraTrackLocked(track: VideoTrack) {
        if (inboundCameraTrack?.id() == track.id()) {
            rebindInboundCameraRendererLocked()
            return
        }
        logCameraDebug("REMOTE_CONTROL_CAMERA_TRACK_RECEIVED id=${track.id()}")
        cameraRenderer?.let { r -> inboundCameraTrack?.removeSink(r) }
        inboundCameraTrack = track
        track.setEnabled(true)
        val firstFrame = object : org.webrtc.VideoSink {
            private val seen = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun onFrame(frame: org.webrtc.VideoFrame) {
                if (seen.compareAndSet(false, true)) {
                    remoteCameraFirstFrame = true
                    Timber.tag("CAMERA_WEBRTC").i("REMOTE_CONTROL_CAMERA_FIRST_FRAME")
                    logCameraDebug("REMOTE_CONTROL_CAMERA_FIRST_FRAME")
                    scope.launch {
                        update {
                            copy(
                                dualCamera = dualCamera.copy(
                                    isActive = true,
                                    bothCamerasOn = true,
                                    remoteShowsControlFeed = true
                                ),
                                statusMessage = "Control camera video playing"
                            )
                        }
                    }
                }
            }
        }
        track.addSink(firstFrame)
        rebindInboundCameraRendererLocked()
        if (cameraSessionActive) {
            cameraRenegotiateAttempts = 0
            update {
                copy(
                    dualCamera = dualCamera.copy(
                        isActive = true,
                        bothCamerasOn = true,
                        remoteShowsControlFeed = remoteCameraFirstFrame
                    ),
                    statusMessage = if (remoteCameraFirstFrame) {
                        "Control camera video playing"
                    } else {
                        "Control camera track received — waiting for frames…"
                    }
                )
            }
        }
    }

    private suspend fun handleWebRtcFailure(reason: String) {
        if (handlingWebRtcFailure) return
        handlingWebRtcFailure = true
        try {
            peerConnectionManager.diagnostics.logIceFailureReport(null)
            if (webrtcRetryCount >= 2) {
                offerInFlight = false
                update {
                    copy(
                        statusMessage = "$reason — tap Disconnect and try again",
                        remoteSessionState = if (screenShareActive) {
                            RemoteSessionState.STARTING_STREAM
                        } else {
                            RemoteSessionState.WAITING
                        }
                    )
                }
                return
            }
            webrtcRetryCount++
            val peerId = boundPeerId
            update { copy(statusMessage = "$reason — clean reconnect ($webrtcRetryCount/2)…") }
            peerId?.let { id ->
                hubClient?.send(
                    HubMessage.WebRtcReconnect(
                        fromId = localDeviceId,
                        toId = id,
                        sessionId = activeSessionId,
                        peerGeneration = peerConnectionManager.currentPeerGeneration()
                    )
                )
            }
            sessionMutex.withLock {
                clearPendingIce()
                resetNegotiationState()
                inboundScreenTrack = null
                when (_ui.value.mode) {
                    DeviceMode.REMOTE -> {
                        if (!peerConnectionManager.isLocalMediaPublishing()) {
                            update {
                                copy(statusMessage = "$reason — stop and restart screen share to retry")
                            }
                            return@withLock
                        }
                        peerConnectionManager.teardownPeerForReconnect()
                        peerCreated = false
                        delay(800)
                        if (!ensurePeer(isControlDevice = false, force = true)) {
                            update { copy(statusMessage = "WebRTC reconnect failed — peer could not be recreated") }
                            return@withLock
                        }
                        if (!peerConnectionManager.reattachScreenTrackToPeer()) {
                            update { copy(statusMessage = "WebRTC reconnect failed — could not reattach screen track") }
                            return@withLock
                        }
                        beginRemoteOfferLocked()
                    }
                    DeviceMode.CONTROL -> {
                        peerConnectionManager.teardownPeerForReconnect()
                        peerCreated = false
                        update {
                            copy(statusMessage = "$reason — waiting for Remote reconnect offer…")
                        }
                    }
                    else -> Unit
                }
            }
        } finally {
            handlingWebRtcFailure = false
        }
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

    private fun shouldIgnoreAutoUnbound(reason: String): Boolean {
        if (sessionBoundAtMs <= 0L) return false
        val elapsed = System.currentTimeMillis() - sessionBoundAtMs
        if (elapsed >= MIN_SESSION_MS) return false
        return when (reason) {
            "re_registered", "peer_disconnected" -> true
            else -> false
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
