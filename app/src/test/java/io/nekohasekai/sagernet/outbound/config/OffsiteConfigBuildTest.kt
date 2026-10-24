package app.qcoldthrone.mobile.outbound.config

import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteConfigBuildTest {

    private val document = """
        {
          "log": {"loglevel": "warning"},
          "outbounds": [
            {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn1.example.com","server_port":56000,"password":"secret-1","hashes":["hash1"],"mode":"wg"}},
            {"tag":"wdtt-2","protocol":"qwdtt","settings":{"server":"turn2.example.com","server_port":56000,"password":"secret-2","hashes":["hash2"],"mode":"wg"}},
            {"tag":"direct","protocol":"freedom"},
            {"tag":"block","protocol":"blackhole"}
          ],
          "routing": {
            "rules": [
              {"type":"field","domain":["domain:example.com"],"outboundTag":"direct"}
            ],
            "balancers": [
              {"tag":"auto","selector":["wdtt-"],"strategy":{"type":"leastPing"}}
            ],
            "final": "direct"
          },
          "dns": {
            "servers": ["1.1.1.1"],
            "queryStrategy": "UseIP"
          }
        }
    """.trimIndent()

    private fun build(config: String, settings: GeneratorSettings = GeneratorSettings(vpnMode = true)): GeneratedConfig {
        val profile = OffsiteConfig().apply {
            name = "Offsite"
            this.config = config
        }
        return ConfigGenerator(ProfileProvider { profile }, settings).build(1)
    }

    private fun JsonObject.outbound(tag: String): JsonObject =
        array("outbounds").filterIsInstance<JsonObject>().first { it.string("tag") == tag }

    @Test
    fun vpnBuildContainsTunSocksBridgesGroupAndCarrierPlans() {
        val result = build(document)
        assertNull(result.error)
        assertTrue(result.ok)

        val core = JsonInput.parseObject(result.coreConfig)
        // Xray `loglevel` is mapped to sing-box `level` (the core rejects unknown fields).
        assertEquals("warn", core.obj("log").string("level"))
        assertFalse(core.obj("log").contains("loglevel"))
        assertTrue(
            core.array("inbounds").filterIsInstance<JsonObject>().any { it.string("type") == "tun" },
        )
        assertEquals("direct", core.obj("route").string("final"))
        assertEquals("dns-0", core.obj("route").obj("default_domain_resolver").string("server"))
        assertEquals("prefer_ipv4", core.obj("dns").string("strategy"))
        assertTrue(core.obj("route").bool("auto_detect_interface"))
        // sniff/hijack-dns head rules come before the document's rules.
        assertEquals("sniff", (core.obj("route").array("rules")[0] as JsonObject).string("action"))
        assertEquals("hijack-dns", (core.obj("route").array("rules")[1] as JsonObject).string("action"))

        assertEquals("socks", core.outbound("wdtt-1").string("type"))
        assertEquals("socks", core.outbound("wdtt-2").string("type"))
        assertEquals("direct", core.outbound("direct").string("type"))
        assertEquals("block", core.outbound("block").string("type"))
        val group = core.outbound("auto")
        assertEquals("urltest", group.string("type"))
        assertEquals(listOf("wdtt-1", "wdtt-2"), group.array("outbounds").strings())
        val domainRule = core.obj("route").array("rules").filterIsInstance<JsonObject>()
            .first { it.contains("domain_suffix") }
        assertEquals(listOf("example.com"), domainRule.array("domain_suffix").strings())

        assertEquals(2, result.carrierPlans.size)
        assertEquals("172.19.0.1/24", result.tunIPv4Cidr)
    }

    @Test
    fun invalidDocumentFailsWithItsPath() {
        val result = build(
            """{"outbounds":[{"tag":"dupe","protocol":"freedom"},{"tag":"dupe","protocol":"blackhole"}]}""",
        )
        assertFalse(result.ok)
        assertNotNull(result.error)
        assertTrue(result.error!!, result.error!!.contains("outbounds[1].tag"))
    }

    @Test
    fun dnsFallsBackToTheFullConfigDefaultWhenTheDocumentHasNone() {
        val result = build(
            """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""",
        )
        assertNull(result.error)
        val core = JsonInput.parseObject(result.coreConfig)
        val server = core.obj("dns").array("servers")[0] as JsonObject
        assertEquals("dns-direct", server.string("tag"))
        assertEquals("1.1.1.1", server.string("server"))
    }

    @Test
    fun singBoxShapedStandardOutboundIsBuilt() {
        val result = build(
            """{"outbounds":[{"tag":"socks-out","protocol":"socks","settings":{"server":"192.0.2.10","server_port":1080}}]}""",
        )
        assertNull(result.error)
        val core = JsonInput.parseObject(result.coreConfig)
        val socks = core.outbound("socks-out")
        assertEquals("socks", socks.string("type"))
        assertEquals("192.0.2.10", socks.string("server"))
        assertEquals(1080L, socks["server_port"])
        assertTrue(result.carrierPlans.isEmpty())
    }

    @Test
    fun wireguardAndAmneziaWgAreBuiltAsEndpoints() {
        val result = build(
            """{"outbounds":[{"tag":"awg-1","protocol":"wireguard","settings":{
                "private_key":"aGVsbG8=",
                "address":["10.0.0.2/32"],
                "peers":[{"public_key":"d29ybGQ=","address":"203.0.113.5","port":51820,"allowed_ips":["0.0.0.0/0"]}],
                "amnezia_wg":{"jc":4,"jmin":40,"jmax":70,"s1":0,"s2":0,"h1":"1","h2":"2","h3":"3","h4":"4"}
            }}]}""",
        )
        assertNull(result.error)
        val core = JsonInput.parseObject(result.coreConfig)
        val endpoint = core.array("endpoints").filterIsInstance<JsonObject>().first { it.string("tag") == "awg-1" }
        assertEquals("wireguard", endpoint.string("type"))
        assertFalse(core.array("outbounds").filterIsInstance<JsonObject>().any { it.string("tag") == "awg-1" })
    }

    @Test
    fun mieruStandardOutboundIsAccepted() {
        val result = build(
            """{"outbounds":[{"tag":"mieru-1","protocol":"mieru","settings":{"server":"203.0.113.9","server_port":443,"transport":"TCP","username":"u","password":"p"}}]}""",
        )
        assertNull(result.error)
        val core = JsonInput.parseObject(result.coreConfig)
        assertEquals("mieru", core.outbound("mieru-1").string("type"))
    }

    @Test
    fun identicalCarrierSettingsShareOnePlanAndPort() {
        val settings = """{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["h"],"mode":"wg","device_id":"fixed-device"}"""
        val result = build(
            """{"outbounds":[
                {"tag":"wdtt-a","protocol":"qwdtt","settings":$settings},
                {"tag":"wdtt-b","protocol":"qwdtt","settings":$settings}
            ]}""",
        )
        assertNull(result.error)
        assertEquals(1, result.carrierPlans.size)
        val core = JsonInput.parseObject(result.coreConfig)
        assertEquals(core.outbound("wdtt-a")["server_port"], core.outbound("wdtt-b")["server_port"])
    }

    @Test
    fun wdttAliasIsAcceptedAsQwdtt() {
        val result = build(
            """{"outbounds":[{"tag":"w","protocol":"wdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"p","hashes":["h"],"mode":"wg"}}]}""",
        )
        assertNull(result.error)
        assertEquals(1, result.carrierPlans.size)
    }

    @Test
    fun xrayShapedStandardOutboundFailsWithTheExplicitMessage() {
        val result = build(
            """{"outbounds":[{"tag":"socks-out","protocol":"socks","settings":{"servers":[{"address":"192.0.2.10","port":1080}]}}]}""",
        )
        assertFalse(result.ok)
        assertTrue(result.error!!, result.error!!.contains("socks-out"))
        assertTrue(result.error!!, result.error!!.contains("Xray shape conversion is not implemented yet"))
    }

    @Test
    fun offsiteConfigIsMeasuredAsItsOwnTestBox() {
        val profile = OffsiteConfig().apply { config = document }
        val result = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings()).buildTest(listOf(TestCandidate(1)))
        assertNull(result.error)
        assertFalse(result.skipped.containsKey(1))
        val testCore = JsonInput.parseObject(result.fullConfigs.getValue(1))
        // The egress graph is kept, the routing rules are dropped (they never apply to the probe dial).
        assertTrue(testCore.array("outbounds").filterIsInstance<JsonObject>().any { it.string("tag") == "wdtt-1" })
        assertFalse(testCore.obj("route").contains("rules"))
        assertFalse(testCore.obj("route").contains("rule_set"))
        assertEquals("direct", testCore.obj("route").string("final"))
        assertTrue(testCore.obj("route").bool("auto_detect_interface"))
        assertEquals(0, testCore.array("inbounds").size)
        // The probe resolves the egress server's own address directly, never through the tunnel it is starting.
        assertEquals("dns-direct", testCore.obj("route").obj("default_domain_resolver").string("server"))
        assertTrue(
            testCore.obj("dns").array("servers").filterIsInstance<JsonObject>().any { it.string("tag") == "dns-direct" },
        )
        assertEquals(2, result.fullConfigCarrierPlans.getValue(1).size)
    }

    @Test
    fun offsiteEndpointOnlyEgressIsTheProbeDefault() {
        val awg = """{"outbounds":[{"tag":"AWG","protocol":"wireguard","settings":{
            "private_key":"aGVsbG8=","address":["10.0.0.2/32"],
            "peers":[{"public_key":"d29ybGQ=","address":"example.com","port":51820}]}}]}"""
        val profile = OffsiteConfig().apply { config = awg }
        val result = ConfigGenerator(ProfileProvider { profile }, GeneratorSettings()).buildTest(listOf(TestCandidate(1)))
        assertNull(result.error)
        assertFalse(result.skipped.containsKey(1))
        val testCore = JsonInput.parseObject(result.fullConfigs.getValue(1))
        assertEquals("AWG", testCore.obj("route").string("final"))
        assertTrue(testCore.array("endpoints").filterIsInstance<JsonObject>().any { it.string("tag") == "AWG" })
        assertTrue(result.fullConfigCarrierPlans.getValue(1).isEmpty())
    }
}
