package attendance.help.device.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object TailscaleIpFinder {
    /**
     * Finds a Tailscale CGNAT address (100.64.0.0/10) on this device.
     * Falls back to the first non-loopback IPv4 if Tailscale is not detected.
     */
    fun findPreferredIp(): String? {
        val addresses = mutableListOf<String>()
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            if (!intf.isUp) continue
            for (addr in Collections.list(intf.inetAddresses)) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                addresses += addr.hostAddress ?: continue
            }
        }
        return addresses.firstOrNull { isTailscaleIp(it) } ?: addresses.firstOrNull()
    }

    fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        // 100.64.0.0 – 100.127.255.255
        return a == 100 && b in 64..127
    }
}
