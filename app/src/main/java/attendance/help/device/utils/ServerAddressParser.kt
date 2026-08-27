package attendance.help.device.utils

/**
 * Parses user-entered server addresses safely.
 *
 * Accepted examples:
 * - 172.20.1.51
 * - 172.20.1.51:8765
 * - ws://172.20.1.51:8765
 * - http://172.20.1.51:8765/
 */
object ServerAddressParser {

    data class Endpoint(
        val host: String,
        val port: Int
    )

    fun parse(rawInput: String, defaultPort: Int): Result<Endpoint> {
        return runCatching {
            var raw = rawInput.trim()
            require(raw.isNotEmpty()) { "Server address is empty" }

            raw = raw.removePrefix("ws://")
                .removePrefix("wss://")
                .removePrefix("http://")
                .removePrefix("https://")

            // Drop path/query if pasted
            raw = raw.substringBefore('/').substringBefore('?').trim()
            require(raw.isNotEmpty()) { "Invalid server address" }

            val host: String
            val port: Int
            if (raw.contains(':')) {
                val idx = raw.lastIndexOf(':')
                host = raw.substring(0, idx).trim()
                val portPart = raw.substring(idx + 1).trim()
                port = portPart.toIntOrNull()
                    ?: error("Port must be a number (example: 172.20.1.51:8765)")
                require(port in 1..65535) { "Port out of range" }
            } else {
                host = raw
                port = defaultPort
            }

            require(host.isNotBlank()) { "Host is empty" }
            require(!host.contains(' ')) { "Host cannot contain spaces" }
            // Reject accidental double-port leftovers
            require(!host.contains(':')) { "Invalid host. Enter IP only, or IP:port once." }

            Endpoint(host = host, port = port)
        }
    }

    fun toWsUrl(endpoint: Endpoint): String = "ws://${endpoint.host}:${endpoint.port}"
}
