package attendance.help.device.network.signaling

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Controller-side signaling server bound to all interfaces (reachable via Tailscale IP).
 */
class SignalingServer(
    port: Int,
    private val codec: SignalingCodec,
    private val onMessage: (SignalingMessage) -> Unit,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit,
    private val expectedPairingCode: () -> String?
) : WebSocketServer(InetSocketAddress(port)) {

    private val peerSocket = AtomicReference<WebSocket?>(null)

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Timber.i("Signaling client connected from %s", conn.remoteSocketAddress)
        peerSocket.getAndSet(conn)?.close()
        onClientConnected()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Timber.i("Signaling client closed: %s", reason)
        if (peerSocket.compareAndSet(conn, null)) {
            onClientDisconnected()
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val decoded = codec.decode(message) ?: return
        if (decoded is SignalingMessage.Hello) {
            val expected = expectedPairingCode()
            if (expected.isNullOrBlank() || decoded.pairingCode != expected) {
                conn.send(codec.encode(SignalingMessage.Reject("Invalid pairing code")))
                conn.close()
                return
            }
        }
        onMessage(decoded)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) = Unit

    override fun onError(conn: WebSocket?, ex: Exception) {
        Timber.e(ex, "Signaling server error")
    }

    override fun onStart() {
        Timber.i("Signaling server started on port %s", address.port)
    }

    fun send(message: SignalingMessage) {
        val socket = peerSocket.get() ?: return
        if (socket.isOpen) {
            socket.send(codec.encode(message))
        }
    }

    fun hasPeer(): Boolean = peerSocket.get()?.isOpen == true
}
