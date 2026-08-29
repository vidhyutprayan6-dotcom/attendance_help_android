package attendance.help.device.webrtc

import attendance.help.device.domain.model.WebRtcTransportDiagnostics
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.PeerConnection
import timber.log.Timber

/** Structured WebRTC transport diagnostics. */
class WebRtcDiagnostics {
    var localDeviceId: String = ""
    var remoteDeviceId: String = ""
    var sessionId: String = ""
    var role: String = ""
    var peerGeneration: Int = 0
    var turnConfigured: Boolean = false
    var forceRelayOnly: Boolean = false
    var captureActive: Boolean = false

    var signalingState: PeerConnection.SignalingState? = null
    var iceGatheringState: PeerConnection.IceGatheringState? = null
    var iceConnectionState: PeerConnection.IceConnectionState? = null
    var connectionState: PeerConnection.PeerConnectionState? = null
    var dataChannelState: String = "CLOSED"

    var localHostCandidates: Int = 0
    var localSrflxCandidates: Int = 0
    var localRelayCandidates: Int = 0
    var remoteHostCandidates: Int = 0
    var remoteSrflxCandidates: Int = 0
    var remoteRelayCandidates: Int = 0

    var remoteDescriptionSet: Boolean = false
    var localDescriptionSet: Boolean = false
    var queuedRemoteCandidates: Int = 0
    var appliedRemoteCandidates: Int = 0
    var failedAddCandidateCalls: Int = 0
    var remoteVideoReceived: Boolean = false
    var lastIceError: String = ""
    var lastDiagnosticEvent: String = ""

    fun resetForNewPeer(generation: Int) {
        signalingState = null
        iceGatheringState = null
        iceConnectionState = null
        connectionState = null
        dataChannelState = "CLOSED"
        localHostCandidates = 0
        localSrflxCandidates = 0
        localRelayCandidates = 0
        remoteHostCandidates = 0
        remoteSrflxCandidates = 0
        remoteRelayCandidates = 0
        remoteDescriptionSet = false
        localDescriptionSet = false
        queuedRemoteCandidates = 0
        appliedRemoteCandidates = 0
        failedAddCandidateCalls = 0
        remoteVideoReceived = false
        lastIceError = ""
        peerGeneration = generation
    }

    fun log(event: String, pc: PeerConnection? = null) {
        lastDiagnosticEvent = event
        syncFromPeer(pc)
        Timber.tag(TAG).i(buildLine(event))
    }

    fun logIceCandidateError(event: IceCandidateErrorEvent, pc: PeerConnection?) {
        val safeUrl = event.url?.substringBefore("@").orEmpty()
        lastIceError = "code=${event.errorCode} text=${event.errorText} url=$safeUrl"
        log("ICE_CANDIDATE_ERROR code=${event.errorCode} text=${event.errorText} address=${event.address} port=${event.port} url=$safeUrl", pc)
    }

    fun recordLocalCandidate(candidate: IceCandidate, pc: PeerConnection?) {
        when (parseCandidateType(candidate.sdp)) {
            CandidateType.HOST -> localHostCandidates++
            CandidateType.SRFLX -> localSrflxCandidates++
            CandidateType.RELAY -> localRelayCandidates++
            CandidateType.UNKNOWN -> Unit
        }
        val type = parseCandidateType(candidate.sdp)
        val protocol = parseCandidateProtocol(candidate.sdp)
        log(
            "LOCAL_ICE_CANDIDATE type=$type protocol=$protocol sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
            pc
        )
        if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
            logLocalIceSummary(pc)
        }
    }

    fun recordRemoteCandidate(candidate: IceCandidate, pc: PeerConnection?) {
        when (parseCandidateType(candidate.sdp)) {
            CandidateType.HOST -> remoteHostCandidates++
            CandidateType.SRFLX -> remoteSrflxCandidates++
            CandidateType.RELAY -> remoteRelayCandidates++
            CandidateType.UNKNOWN -> Unit
        }
        val type = parseCandidateType(candidate.sdp)
        val protocol = parseCandidateProtocol(candidate.sdp)
        log(
            "REMOTE_ICE_CANDIDATE type=$type protocol=$protocol sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
            pc
        )
    }

    fun logLocalIceSummary(pc: PeerConnection?) {
        syncFromPeer(pc)
        log(
            "LOCAL_ICE_SUMMARY host=$localHostCandidates srflx=$localSrflxCandidates relay=$localRelayCandidates",
            pc
        )
        if (turnConfigured && localRelayCandidates == 0) {
            Timber.tag(TAG).e(
                "TURN configured but no relay candidate gathered. " +
                    "TURN may be unreachable from this device or credentials are wrong."
            )
        }
    }

    fun logRemoteIceSummary(pc: PeerConnection?) {
        syncFromPeer(pc)
        log(
            "REMOTE_ICE_SUMMARY host=$remoteHostCandidates srflx=$remoteSrflxCandidates relay=$remoteRelayCandidates",
            pc
        )
    }

    fun logIceFailureReport(pc: PeerConnection?) {
        syncFromPeer(pc)
        val report = buildString {
            appendLine("ICE FAILURE REPORT")
            appendLine("Local: host=$localHostCandidates srflx=$localSrflxCandidates relay=$localRelayCandidates")
            appendLine("Remote: host=$remoteHostCandidates srflx=$remoteSrflxCandidates relay=$remoteRelayCandidates")
            appendLine("TURN configured=$turnConfigured turnRelayAvailable=${localRelayCandidates > 0}")
            appendLine("forceRelayOnly=$forceRelayOnly")
            appendLine("remoteDescriptionSet=$remoteDescriptionSet localDescriptionSet=$localDescriptionSet")
            appendLine("queuedRemoteCandidates=$queuedRemoteCandidates appliedRemoteCandidates=$appliedRemoteCandidates failedAddCandidate=$failedAddCandidateCalls")
            appendLine("signalingState=${signalingState?.name}")
            appendLine("iceConnectionState=${iceConnectionState?.name}")
            appendLine("connectionState=${connectionState?.name}")
            appendLine("iceGatheringState=${iceGatheringState?.name}")
            appendLine("dataChannelState=$dataChannelState")
            if (lastIceError.isNotBlank()) appendLine("lastIceError=$lastIceError")
        }
        Timber.tag(TAG).e(report)
        lastDiagnosticEvent = "ICE_FAILURE_REPORT"
    }

    fun snapshot(): WebRtcTransportDiagnostics {
        val turnRelayAvailable = localRelayCandidates > 0
        val transportConnected =
            connectionState == PeerConnection.PeerConnectionState.CONNECTED &&
                dataChannelState == "OPEN" &&
                (role != "CONTROL" || remoteVideoReceived)
        return WebRtcTransportDiagnostics(
            localDeviceId = localDeviceId,
            remoteDeviceId = remoteDeviceId,
            sessionId = sessionId,
            role = role,
            peerGeneration = peerGeneration,
            signalingState = signalingState?.name ?: "NEW",
            iceGatheringState = iceGatheringState?.name ?: "NEW",
            iceConnectionState = iceConnectionState?.name ?: "NEW",
            connectionState = connectionState?.name ?: "NEW",
            dataChannelState = dataChannelState,
            localHostCandidates = localHostCandidates,
            localSrflxCandidates = localSrflxCandidates,
            localRelayCandidates = localRelayCandidates,
            remoteHostCandidates = remoteHostCandidates,
            remoteSrflxCandidates = remoteSrflxCandidates,
            remoteRelayCandidates = remoteRelayCandidates,
            turnConfigured = turnConfigured,
            turnRelayAvailable = turnRelayAvailable,
            forceRelayOnly = forceRelayOnly,
            remoteDescriptionSet = remoteDescriptionSet,
            localDescriptionSet = localDescriptionSet,
            queuedRemoteCandidates = queuedRemoteCandidates,
            appliedRemoteCandidates = appliedRemoteCandidates,
            failedAddCandidateCalls = failedAddCandidateCalls,
            remoteVideoReceived = remoteVideoReceived,
            captureActive = captureActive,
            transportConnected = transportConnected,
            lastIceError = lastIceError,
            lastDiagnosticEvent = lastDiagnosticEvent
        )
    }

    private fun syncFromPeer(pc: PeerConnection?) {
        if (pc == null) return
        signalingState = pc.signalingState()
        iceGatheringState = pc.iceGatheringState()
        iceConnectionState = pc.iceConnectionState()
        connectionState = pc.connectionState()
    }

    private fun buildLine(event: String): String {
        return buildString {
            append("WEBRTC_DIAG ")
            append("device=$localDeviceId ")
            append("remote=$remoteDeviceId ")
            append("session=$sessionId ")
            append("generation=$peerGeneration ")
            append("role=$role ")
            append("signaling=${signalingState?.name ?: "null"} ")
            append("ice=${iceConnectionState?.name ?: "null"} ")
            append("connection=${connectionState?.name ?: "null"} ")
            append("gathering=${iceGatheringState?.name ?: "null"} ")
            append("event=$event")
        }
    }

    private enum class CandidateType { HOST, SRFLX, RELAY, UNKNOWN }

    companion object {
        private const val TAG = "WEBRTC_DIAG"

        fun parseCandidateType(sdp: String): CandidateType {
            val typ = Regex("""typ\s+(\w+)""").find(sdp)?.groupValues?.getOrNull(1)?.lowercase()
            return when (typ) {
                "host" -> CandidateType.HOST
                "srflx" -> CandidateType.SRFLX
                "relay" -> CandidateType.RELAY
                else -> CandidateType.UNKNOWN
            }
        }

        fun parseCandidateProtocol(sdp: String): String {
            return Regex("""\s(\d+)\s+(udp|tcp)\s""").find(sdp)?.groupValues?.getOrNull(2) ?: "unknown"
        }
    }
}
