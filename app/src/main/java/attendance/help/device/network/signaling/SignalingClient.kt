package attendance.help.device.network.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Remote-side signaling client connecting to Controller Tailscale IP.
 */
class SignalingClient(
    private val codec: SignalingCodec,
    private val onMessage: (SignalingMessage) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val socketRef = AtomicReference<WebSocket?>(null)

    fun connect(wsUrl: String) {
        disconnect()
        val request = Request.Builder().url(wsUrl).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("Signaling client open")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                codec.decode(text)?.let(onMessage)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.i("Signaling client closed: %s", reason)
                socketRef.compareAndSet(webSocket, null)
                onClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Signaling client failure")
                socketRef.compareAndSet(webSocket, null)
                onFailure(t)
            }
        })
        socketRef.set(socket)
    }

    fun send(message: SignalingMessage) {
        socketRef.get()?.send(codec.encode(message))
    }

    fun disconnect() {
        socketRef.getAndSet(null)?.close(1000, "client disconnect")
    }

    fun isConnected(): Boolean = socketRef.get() != null
}
