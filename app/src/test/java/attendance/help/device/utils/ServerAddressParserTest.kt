package attendance.help.device.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressParserTest {

    private val defaultPort = 8765

    @Test
    fun lanIp_usesWsAndDefaultPort() {
        val ep = ServerAddressParser.parse("172.20.1.51", defaultPort).getOrThrow()
        assertEquals("172.20.1.51", ep.host)
        assertEquals(8765, ep.port)
        assertFalse(ep.secure)
        assertEquals("ws://172.20.1.51:8765", ServerAddressParser.toSignalingWsUrl(ep))
    }

    @Test
    fun lanIpWithPort_usesWs() {
        val ep = ServerAddressParser.parse("172.20.1.51:8765", defaultPort).getOrThrow()
        assertFalse(ep.secure)
        assertEquals("ws://172.20.1.51:8765", ServerAddressParser.toSignalingWsUrl(ep))
    }

    @Test
    fun renderDomain_usesWss443() {
        val ep = ServerAddressParser.parse("my-app.onrender.com", defaultPort).getOrThrow()
        assertEquals("my-app.onrender.com", ep.host)
        assertEquals(443, ep.port)
        assertTrue(ep.secure)
        assertEquals("wss://my-app.onrender.com", ServerAddressParser.toSignalingWsUrl(ep))
    }

    @Test
    fun httpsPrefix_usesWss443() {
        val ep = ServerAddressParser.parse("https://my-app.onrender.com", defaultPort).getOrThrow()
        assertTrue(ep.secure)
        assertEquals(443, ep.port)
        assertEquals("wss://my-app.onrender.com", ServerAddressParser.toSignalingWsUrl(ep))
    }

    @Test
    fun wssPrefix_usesWss443() {
        val ep = ServerAddressParser.parse("wss://my-app.onrender.com", defaultPort).getOrThrow()
        assertTrue(ep.secure)
        assertEquals("wss://my-app.onrender.com", ServerAddressParser.toSignalingWsUrl(ep))
    }

    @Test
    fun productionRenderHttpsLink_connectsViaWss() {
        val ep = ServerAddressParser.parse(
            "https://attendance-help-android.onrender.com",
            defaultPort
        ).getOrThrow()
        assertEquals("attendance-help-android.onrender.com", ep.host)
        assertEquals(443, ep.port)
        assertTrue(ep.secure)
        assertEquals(
            "wss://attendance-help-android.onrender.com",
            ServerAddressParser.toSignalingWsUrl(ep)
        )
    }

    @Test
    fun productionRenderHostOnly_connectsViaWss() {
        val ep = ServerAddressParser.parse(
            ServerAddressParser.DEFAULT_CLOUD_HUB,
            defaultPort
        ).getOrThrow()
        assertEquals(
            "wss://attendance-help-android.onrender.com",
            ServerAddressParser.toSignalingWsUrl(ep)
        )
    }

    @Test
    fun productionRenderHttpsTrailingSlash_connectsViaWss() {
        val ep = ServerAddressParser.parse(
            "https://attendance-help-android.onrender.com/",
            defaultPort
        ).getOrThrow()
        assertEquals("attendance-help-android.onrender.com", ep.host)
        assertEquals(
            "wss://attendance-help-android.onrender.com",
            ServerAddressParser.toSignalingWsUrl(ep)
        )
    }

    @Test
    fun explicitWsOnDomain_staysInsecure() {
        val ep = ServerAddressParser.parse("ws://my-app.onrender.com:8765", defaultPort).getOrThrow()
        assertFalse(ep.secure)
        assertEquals(8765, ep.port)
    }
}
