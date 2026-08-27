package attendance.help.device.webrtc

/**
 * WebRTC session façade.
 * Step 5+: peer connection, signaling, ICE, data channel commands.
 *
 * Media direction for v1 dual-camera display:
 * - Controller publishes local camera track.
 * - Remote renders Controller's remote track full-screen.
 * - Remote also publishes its camera (both cameras on); Controller keeps local preview as primary UI.
 */
interface WebRtcSession {
    suspend fun connect(peerSignalingUrl: String)
    suspend fun disconnect()
}
