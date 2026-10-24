package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.types.Csqtt
import app.qcoldthrone.mobile.outbound.types.Direct
import app.qcoldthrone.mobile.outbound.types.DnsTunnel
import app.qcoldthrone.mobile.outbound.types.OlcRtc
import app.qcoldthrone.mobile.outbound.types.OpenFlux
import app.qcoldthrone.mobile.outbound.types.Qwdtt
import app.qcoldthrone.mobile.outbound.types.S3Bypass
import app.qcoldthrone.mobile.outbound.types.Sstp
import app.qcoldthrone.mobile.outbound.types.TrustTunnel
import app.qcoldthrone.mobile.outbound.types.WdttPlus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteConfigParserTest {

    private val hex64 = "0123456789abcdef".repeat(4)

    private fun allProtocolsDocument() = """
        {
          "remarks": "Offsite mix",
          "outbounds": [
            {"tag":"qwdtt-1","protocol":"qwdtt","settings":{"server":"turn1.example.com","server_port":56000,"password":"secret","hashes":["hash1"],"mode":"wg"}},
            {"tag":"csqtt-1","protocol":"csqtt","settings":{"server":"turn2.example.com","server_port":46000,"password":"secret","hashes":["hash1"],"obfs":"unified"}},
            {"tag":"wdttplus-1","protocol":"wdttplus","settings":{"server":"turn3.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}},
            {"tag":"olcrtc-1","protocol":"olcrtc","settings":{"provider":"jitsi","transport":"datachannel","room":"https://meet.example.com/room","key":"$hex64"}},
            {"tag":"openflux-1","protocol":"openflux","settings":{"transports":[{"type":"telemost","url":"https://telemost.example.com/room"}]}},
            {"tag":"dnstt-1","protocol":"dnstt","settings":{"domain":"dnstt.example.com","pubkey":"$hex64"}},
            {"tag":"vaydns-1","protocol":"vaydns","settings":{"domain":"vaydns.example.com","pubkey":"$hex64"}},
            {"tag":"slipstream-1","protocol":"slipstream","settings":{"domain":"slipstream.example.com"}},
            {"tag":"masterdns-1","protocol":"masterdns","settings":{"domain":"masterdns.example.com","mkey":"mkey"}},
            {"tag":"s3bypass-1","protocol":"s3bypass","settings":{"storage":{"type":"s3","endpoint":"https://s3.example.com","bucket":"bucket","accessKey":"key","secretKey":"secret"}}},
            {"tag":"sstp-1","protocol":"sstp","settings":{"server":"vpn.example.com","server_port":443,"username":"user","password":"pass"}}
          ]
        }
    """.trimIndent()

    @Test
    fun allElevenOffsiteProtocolsParseIntoTheirModels() {
        val result = OffsiteConfigParser.parse(allProtocolsDocument())
        assertTrue(result.errors.toString(), result.ok)
        val document = result.document!!
        assertNotNull(document)
        assertEquals("Offsite mix", document.name)
        assertEquals(11, document.outbounds.size)
        assertTrue(document.outbounds.all { it.isOffsite })

        val byTag = document.outbounds.associateBy { it.tag }
        assertTrue(byTag.getValue("qwdtt-1").outbound is Qwdtt)
        assertTrue(byTag.getValue("csqtt-1").outbound is Csqtt)
        assertTrue(byTag.getValue("wdttplus-1").outbound is WdttPlus)
        assertTrue(byTag.getValue("olcrtc-1").outbound is OlcRtc)
        assertTrue(byTag.getValue("openflux-1").outbound is OpenFlux)
        assertTrue(byTag.getValue("dnstt-1").outbound is DnsTunnel)
        assertTrue(byTag.getValue("vaydns-1").outbound is DnsTunnel)
        assertTrue(byTag.getValue("slipstream-1").outbound is DnsTunnel)
        assertTrue(byTag.getValue("masterdns-1").outbound is DnsTunnel)
        assertTrue(byTag.getValue("s3bypass-1").outbound is S3Bypass)
        assertTrue(byTag.getValue("sstp-1").outbound is Sstp)
        assertEquals("vaydns", byTag.getValue("vaydns-1").outbound?.type)
        assertEquals("slipstream", byTag.getValue("slipstream-1").outbound?.type)
        assertEquals("masterdns", byTag.getValue("masterdns-1").outbound?.type)
    }

    @Test
    fun offsiteSettingsGetTypeAndNameInjected() {
        val document = OffsiteConfigParser.parse(allProtocolsDocument()).document!!
        val qwdtt = document.outbounds.first { it.tag == "qwdtt-1" }
        assertEquals("qwdtt", qwdtt.settings.string("type"))
        assertEquals("qwdtt-1", qwdtt.settings.string("name"))
        assertEquals("turn1.example.com", qwdtt.settings.string("server"))
        // The injected copy is not the caller's document text.
        assertEquals("secret", qwdtt.settings.string("password"))
    }

    @Test
    fun routingRulesBalancersAndFinalAreParsed() {
        val text = """
            {
              "outbounds": [
                {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}},
                {"tag":"direct","protocol":"freedom"}
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  {"type":"field","domain":["geosite:category-ads-all"],"outboundTag":"direct"},
                  {"type":"field","network":"tcp,udp","balancerTag":"auto"},
                  {"inboundTag":["tun"],"outboundTag":"direct"}
                ],
                "balancers": [
                  {"tag":"auto","selector":["wdtt-"],"strategy":{"type":"leastPing","settings":{"basetime":"5m"}},"fallbackTag":"direct"}
                ],
                "final": "auto"
              }
            }
        """.trimIndent()
        val result = OffsiteConfigParser.parse(text)
        assertTrue(result.errors.toString(), result.ok)
        val document = result.document!!
        assertEquals(3, document.rules.size)
        assertEquals("direct", document.rules[0].outboundTag)
        assertNull(document.rules[0].balancerTag)
        assertEquals("auto", document.rules[1].balancerTag)
        assertEquals(listOf("tun"), document.rules[2].inboundTags)
        assertEquals(1, document.balancers.size)
        val balancer = document.balancers.single()
        assertEquals("auto", balancer.tag)
        assertEquals(listOf("wdtt-"), balancer.selectors)
        assertEquals("leastPing", balancer.strategy)
        assertEquals("5m", balancer.settings.string("basetime"))
        assertEquals("direct", balancer.fallbackTag)
        assertEquals("auto", document.finalTag)
    }

    @Test
    fun unknownProtocolIsAnErrorAtTheProtocolPath() {
        val result = OffsiteConfigParser.parse("""{"outbounds":[{"tag":"x","protocol":"splithttp"}]}""")
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.path == "outbounds[0].protocol" })
        assertNotNull(result.document)
    }

    @Test
    fun nonObjectJsonIsRejected() {
        for (text in listOf("[]", "", "not json", "42")) {
            val result = OffsiteConfigParser.parse(text)
            assertNull(text, result.document)
            assertFalse(text, result.ok)
        }
    }

    @Test
    fun missingOrEmptyOutboundsIsAnError() {
        for (text in listOf("""{"log":{"loglevel":"warning"}}""", """{"outbounds":[]}""", """{"outbounds":{}}""")) {
            val result = OffsiteConfigParser.parse(text)
            assertNull(text, result.document)
            assertTrue(text, result.errors.any { it.path == "outbounds" })
        }
    }

    @Test
    fun inboundsIsAcceptedWithAWarning() {
        val text = """{"inbounds":[{"port":1080,"protocol":"socks"}],"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        val result = OffsiteConfigParser.parse(text)
        assertTrue(result.ok)
        assertTrue(result.warnings.any { it.path == "inbounds" })
    }

    @Test
    fun standardOutboundsAreAccepted() {
        val text = """
            {
              "outbounds": [
                {"tag":"direct","protocol":"freedom"},
                {"tag":"block","protocol":"blackhole"},
                {"tag":"socks","protocol":"socks","settings":{"type":"socks","server":"127.0.0.1","server_port":1080}},
                {"tag":"tt","protocol":"trusttunnel","settings":{"server":"tt.example.com","server_port":443,"username":"u","password":"p"}}
              ]
            }
        """.trimIndent()
        val result = OffsiteConfigParser.parse(text)
        assertTrue(result.errors.toString(), result.ok)
        val byTag = result.document!!.outbounds.associateBy { it.tag }
        assertFalse(byTag.getValue("direct").isOffsite)
        assertTrue(byTag.getValue("direct").outbound is Direct)
        assertNull(byTag.getValue("block").outbound)
        assertTrue(byTag.getValue("tt").outbound is TrustTunnel)
    }

    @Test
    fun malformedOffsiteSettingsAreAnError() {
        val text = """{"outbounds":[{"tag":"bad","protocol":"sstp","settings":{"server":"vpn.example.com","server_port":443}}]}"""
        val result = OffsiteConfigParser.parse(text)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.path == "outbounds[0].settings" })
    }

    @Test
    fun unknownTopLevelSectionIsOnlyAWarning() {
        val text = """{"somethingNew":true,"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        val result = OffsiteConfigParser.parse(text)
        assertTrue(result.ok)
        assertTrue(result.warnings.any { it.path == "somethingNew" })
    }

    @Test
    fun documentRoundTripsThroughJsonText() {
        val parsed = OffsiteConfigParser.parse(allProtocolsDocument()).document!!
        assertEquals(11, parsed.outbounds.size)
        // The parser reads its own compact output when wrapped back into the document shape.
        val settings = JsonInput.parseObject(parsed.outbounds[0].settings.toCompact())
        assertEquals("qwdtt", settings.string("type"))
    }
}
