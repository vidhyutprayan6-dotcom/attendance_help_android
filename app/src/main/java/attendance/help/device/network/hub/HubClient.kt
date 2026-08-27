package attendance.help.device.network.hub

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HubClient(
    private val codec: HubCodec = HubCodec(),
    private val onMessage: (HubMessage) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)

    fun connect(wsUrl: String) {
        disconnect()
        val request = Request.Builder().url(wsUrl).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) {
                codec.decode(text)?.let(onMessage)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket.compareAndSet(webSocket, null)
                onClosed(reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Hub client failure")
                socket.compareAndSet(webSocket, null)
                onFailure(t)
            }
        })
        socket.set(ws)
    }

    fun send(message: HubMessage) {
        socket.get()?.send(codec.encode(message))
    }

    fun disconnect() {
        socket.getAndSet(null)?.close(1000, "bye")
    }

    fun isConnected(): Boolean = socket.get() != null
}
