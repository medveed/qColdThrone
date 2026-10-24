package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.config.GeneratorSettings
import app.qcoldthrone.mobile.outbound.config.RoutingInput
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.route.RouteProfile
import app.qcoldthrone.mobile.route.RuleSetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteTranslatorTest {

    private val document = OffsiteConfigParser.parse(
        """
        {
          "outbounds": [
            {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn1.example.com","server_port":56000,"password":"secret-1","hashes":["hash1"],"mode":"wg"}},
            {"tag":"wdtt-2","protocol":"qwdtt","settings":{"server":"turn2.example.com","server_port":56000,"password":"secret-2","hashes":["hash2"],"mode":"wg"}},
            {"tag":"direct","protocol":"freedom"},
            {"tag":"block","protocol":"blackhole"}
          ],
          "routing": {
            "rules": [
              {
                "type": "field",
                "domain": ["full:example.org", "domain:example.com", "keyword:ads", "regexp:^ad\\.", "geosite:category-ads-all"],
                "outboundTag": "direct"
              },
              {
                "type": "field",
                "ip": ["10.0.0.0/8", "192.0.2.1", "geoip:ru"],
                "port": "80,443,1000-2000",
                "network": "tcp,udp",
                "balancerTag": "auto"
              }
            ],
            "balancers": [
              {"tag":"auto","selector":["wdtt-"],"strategy":{"type":"leastPing"},"fallbackTag":"direct"}
            ]
          },
          "dns": {
            "servers": [
              {"address":"https://dns.example.com/dns-query","tag":"remote"},
              {"address":"8.8.8.8","domains":["example.org"]}
            ],
            "queryStrategy": "UseIPv4"
          }
        }
        """.trimIndent(),
    ).also { assertTrue(it.errors.toString(), it.ok) }.document!!

    private val catalog = RuleSetCatalog(
        listOf(
            "geosite-category-ads-all" to "https://example.com/geosite.srs",
            "geoip-ru" to "https://example.com/geoip.srs",
        ),
    )

    private fun translate(): OffsiteTranslation = OffsiteTranslator.translate(
        document,
        GeneratorSettings(),
        BuildContext.DEFAULT,
        RoutingInput(RouteProfile.defaultProfile(), catalog),
    )

    @Test
    fun offsiteOutboundsBecomeDistinctSocksBridges() {
        val translation = translate()
        assertNull(translation.error)

        val wdtt1 = translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "wdtt-1" }
        val wdtt2 = translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "wdtt-2" }
        for (bridge in listOf(wdtt1, wdtt2)) {
            assertEquals("socks", bridge.string("type"))
            assertEquals("127.0.0.1", bridge.string("server"))
            assertEquals("5", bridge.string("version"))
            assertTrue(bridge.string("username").isNotEmpty())
            assertTrue(bridge.string("password").isNotEmpty())
        }
        assertTrue(wdtt1.int("server_port") > 0)
        assertTrue(wdtt2.int("server_port") > 0)
        assertTrue(wdtt1.int("server_port") != wdtt2.int("server_port"))
        assertTrue(wdtt1.string("username") != wdtt2.string("username"))

        // Plans are numbered from the document order with synthetic negative ids (§6.1, §7).
        assertEquals(2, translation.carrierPlans.size)
        assertEquals(-1L, translation.carrierPlans[0].profileId)
        assertEquals(-2L, translation.carrierPlans[1].profileId)

        assertEquals("direct", translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "direct" }.string("type"))
        assertEquals("block", translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "block" }.string("type"))
    }

    @Test
    fun leastPingBalancerBecomesAutoSelectorWithAFallback() {
        val translation = translate()
        val group = translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "auto" }
        assertEquals("auto-selector", group.string("type"))
        assertEquals("leastPing", group.string("strategy"))
        assertEquals(GeneratorSettings().testUrl, group.string("url"))
        assertEquals("300s", group.string("interval"))
        assertEquals(listOf("wdtt-1", "wdtt-2"), group.array("outbounds").strings())
        // fallbackTag is a real core option, not a group member.
        assertEquals("direct", group.string("fallback"))
        assertTrue(!group.array("outbounds").strings().contains("direct"))
        // The group is appended after every regular outbound.
        assertEquals("auto", (translation.outbounds[translation.outbounds.size - 1] as JsonObject).string("tag"))
    }

    @Test
    fun leastLoadBalancerUsesTheCoreLeastLoadSemantics() {
        val doc = OffsiteConfigParser.parse(
            """
            {"outbounds":[{"tag":"a","protocol":"freedom"},{"tag":"b","protocol":"freedom"}],
             "routing":{"balancers":[{"tag":"auto","selector":["a","b"],
               "strategy":{"type":"leastLoad","settings":{"expected":2,"maxRTT":500,"tolerance":0.5,"basetime":45,"baselines":["100ms","300ms"]}}}]}}
            """.trimIndent(),
        ).document!!
        val translation = OffsiteTranslator.translate(
            doc, GeneratorSettings(), BuildContext.DEFAULT,
            RoutingInput(RouteProfile.defaultProfile(), RuleSetCatalog(emptyList())),
        )
        val group = translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "auto" }
        assertEquals("auto-selector", group.string("type"))
        // Xray leastLoad = a random pick over the top-`expected` healthy members (jitter-ranked by the core).
        assertTrue(group.bool("balance"))
        assertEquals("connection", group.string("balance_mode"))
        assertEquals(2, group.int("expected"))
        assertEquals("500ms", group.string("max_rtt"))
        // Xray leastLoad `tolerance` is a failure ratio, not milliseconds.
        assertEquals(0.5, group.double("fail_tolerance"), 0.0)
        assertEquals("45s", group.string("interval"))
        assertEquals(listOf("100ms", "300ms"), group.array("baselines").strings())
    }

    @Test
    fun randomBalancerBalancesPerConnectionOverEveryMember() {
        val doc = OffsiteConfigParser.parse(
            """
            {"outbounds":[{"tag":"a","protocol":"freedom"},{"tag":"b","protocol":"freedom"}],
             "routing":{"balancers":[{"tag":"auto","selector":["a","b"]}]}}
            """.trimIndent(),
        ).document!!
        val translation = OffsiteTranslator.translate(
            doc, GeneratorSettings(), BuildContext.DEFAULT,
            RoutingInput(RouteProfile.defaultProfile(), RuleSetCatalog(emptyList())),
        )
        val group = translation.outbounds.filterIsInstance<JsonObject>().first { it.string("tag") == "auto" }
        assertEquals("auto-selector", group.string("type"))
        assertTrue(group.bool("balance"))
        assertEquals("connection", group.string("balance_mode"))
        // expected = member count keeps every healthy member qualified (a uniformly random pick).
        assertEquals(2, group.int("expected"))
        assertTrue(!group.contains("balance_interval"))
        assertTrue(!group.contains("strategy"))
    }

    @Test
    fun routingRulesMapEveryFieldAndResolveRuleSets() {
        val translation = translate()
        assertNull(translation.error)
        val rules = translation.route.array("rules")
        assertEquals(2, rules.size)

        val domainRule = rules[0] as JsonObject
        assertEquals("direct", domainRule.string("outbound"))
        assertEquals(listOf("example.org"), domainRule.array("domain").strings())
        assertEquals(listOf("example.com"), domainRule.array("domain_suffix").strings())
        assertEquals(listOf("ads"), domainRule.array("domain_keyword").strings())
        assertEquals(listOf("^ad\\."), domainRule.array("domain_regex").strings())
        assertEquals(listOf("geosite-category-ads-all"), domainRule.array("rule_set").strings())
        assertTrue(!domainRule.contains("type"))

        val ipRule = rules[1] as JsonObject
        assertEquals("auto", ipRule.string("outbound"))
        assertEquals(listOf("10.0.0.0/8", "192.0.2.1/32"), ipRule.array("ip_cidr").strings())
        assertEquals(listOf("geoip-ru"), ipRule.array("rule_set").strings())
        assertEquals(listOf(80L, 443L), ipRule.array("port").toList())
        assertEquals(listOf("1000:2000"), ipRule.array("port_range").strings())
        assertEquals(listOf("tcp", "udp"), ipRule.array("network").strings())

        assertEquals("wdtt-1", translation.route.string("final"))
        assertEquals("https://example.com/geosite.srs", translation.ruleSetUrls["geosite-category-ads-all"])
        assertEquals("https://example.com/geoip.srs", translation.ruleSetUrls["geoip-ru"])
        assertEquals(2, translation.route.array("rule_set").size)
        val definition = translation.route.array("rule_set")[0] as JsonObject
        assertEquals("remote", definition.string("type"))
        assertEquals("binary", definition.string("format"))
    }

    @Test
    fun dnsServersAndQueryStrategyAreTranslated() {
        val translation = translate()
        val dns = translation.dns!!
        assertEquals("ipv4_only", dns.string("strategy"))
        val servers = dns.array("servers")
        assertEquals(2, servers.size)

        val remote = servers[0] as JsonObject
        assertEquals("https", remote.string("type"))
        assertEquals("dns.example.com", remote.string("server"))
        assertEquals("/dns-query", remote.string("path"))
        assertEquals("remote", remote.string("tag"))

        val plain = servers[1] as JsonObject
        assertEquals("udp", plain.string("type"))
        assertEquals("8.8.8.8", plain.string("server"))
        assertEquals(53L, plain["server_port"])
        assertEquals("dns-1", plain.string("tag"))

        // Per-server domains are a v1 warning, not a failure (§5.7).
        assertTrue(translation.warnings.any { it.path == "dns.servers[1].domains" })
    }

    @Test
    fun missingDnsSectionYieldsNoDnsObject() {
        val noDns = OffsiteConfigParser.parse(
            """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""",
        ).document!!
        val translation = OffsiteTranslator.translate(
            noDns,
            GeneratorSettings(),
            BuildContext.DEFAULT,
            RoutingInput(RouteProfile.defaultProfile(), RuleSetCatalog(emptyList())),
        )
        assertNull(translation.dns)
        assertNull(translation.error)
        assertEquals("direct", translation.route.string("final"))
        assertTrue(translation.carrierPlans.isEmpty())
    }

    @Test
    fun unknownRuleSetIsAnErrorWithItsPathAndName() {
        val unknown = OffsiteConfigParser.parse(
            """
            {
              "outbounds": [{"tag":"direct","protocol":"freedom"}],
              "routing": {"rules": [{"type":"field","domain":["geosite:does-not-exist"],"outboundTag":"direct"}]}
            }
            """.trimIndent(),
        ).document!!
        val translation = OffsiteTranslator.translate(
            unknown,
            GeneratorSettings(),
            BuildContext.DEFAULT,
            RoutingInput(RouteProfile.defaultProfile(), RuleSetCatalog(emptyList())),
        )
        assertNotNull(translation.error)
        assertTrue(translation.error!!, translation.error!!.contains("routing.rules[0].domain"))
        assertTrue(translation.error!!, translation.error!!.contains("does-not-exist"))
    }
}
