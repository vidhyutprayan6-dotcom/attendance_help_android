package attendance.help.device.network.hub

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HubClient(
    private val codec: HubCodec = HubCodec(),
    private val onMessage: (HubMessage) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val http = buildClient()
    private val socket = AtomicReference<WebSocket?>(null)

    fun connect(wsUrl: String) {
        disconnect()
        try {
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
                    Timber.e(t, "Hub client failure url=%s", wsUrl)
                    socket.compareAndSet(webSocket, null)
                    onFailure(t)
                }
            })
            socket.set(ws)
        } catch (t: Throwable) {
            Timber.e(t, "Hub client connect threw for url=%s", wsUrl)
            onFailure(t)
        }
    }

    fun send(message: HubMessage) {
        runCatching { socket.get()?.send(codec.encode(message)) }
    }

    fun disconnect() {
        runCatching { socket.getAndSet(null)?.close(1000, "bye") }
    }

    fun isConnected(): Boolean = socket.get() != null

    companion object {
        /**
         * System DNS first; if that fails (common on some emulators / VPN DNS),
         * fall back to DNS-over-HTTPS via Google so cloud hubs still resolve.
         */
        fun buildClient(): OkHttpClient {
            val bootstrap = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val doh = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .build()
            val resilientDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (first: UnknownHostException) {
                        Timber.w(first, "System DNS failed for %s — trying DoH", hostname)
                        try {
                            doh.lookup(hostname)
                        } catch (second: UnknownHostException) {
                            val detail = UnknownHostException(
                                "Unable to resolve host \"$hostname\". " +
                                    "Open https://$hostname in the device browser to wake Render, " +
                                    "check Wi‑Fi/DNS, or use a LAN hub IP (start-hub.bat)."
                            )
                            detail.initCause(second)
                            throw detail
                        }
                    }
                }
            }
            return OkHttpClient.Builder()
                .dns(resilientDns)
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(45, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** HTTPS GET wakes a sleeping Render free-tier service before WSS connect. */
        fun wakeCloudHost(host: String) {
            val url = "https://$host/"
            runCatching {
                // Reuse the resilient DNS client (DoH fallback for emulators).
                val client = buildClient().newBuilder()
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    Timber.i("Hub wake %s -> HTTP %d", url, response.code)
                }
            }.onFailure { Timber.w(it, "Hub wake failed for %s (will still try WSS)", host) }
        }
    }
}
