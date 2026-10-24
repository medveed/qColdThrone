package app.qcoldthrone.mobile.outbound.config

import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.types.Socks
import org.junit.Assert.*
import org.junit.Test

class AndroidVpnListenerTest {
    private fun build(vpn: Boolean): JsonObject {
        val profile = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
        val result = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings(
            vpnMode = vpn, mixedInboundEnabled = true, httpProxyEnabled = true,
        )).build(1)
        assertNull(result.error)
        return JsonInput.parseObject(result.coreConfig)
    }

    @Test fun vpnDoesNotExposeProxyOrDnsListenersEvenWithLegacySettings() {
        val types = build(true).array("inbounds").map { (it as JsonObject).string("type") }
        assertEquals(listOf("tun"), types)
    }

    @Test fun explicitProxyModeStillHasItsProxyListener() {
        val types = build(false).array("inbounds").map { (it as JsonObject).string("type") }
        assertTrue(types.contains("socks") && types.contains("http"))
        assertFalse(types.contains("tun"))
    }
}
