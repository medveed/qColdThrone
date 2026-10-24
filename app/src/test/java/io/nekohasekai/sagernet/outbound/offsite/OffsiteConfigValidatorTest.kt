package app.qcoldthrone.mobile.outbound.offsite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteConfigValidatorTest {

    private fun parse(text: String): OffsiteDocument {
        val result = OffsiteConfigParser.parse(text)
        return result.document ?: error("document did not parse: ${result.errors}")
    }

    private fun errors(text: String): List<OffsiteIssue> =
        OffsiteConfigValidator.validate(parse(text)).filter { it.severity == OffsiteSeverity.Error }

    @Test
    fun validDocumentHasNoErrors() {
        val document = parse(
            """
            {
              "remarks": "valid",
              "outbounds": [
                {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}},
                {"tag":"direct","protocol":"freedom"}
              ],
              "routing": {
                "rules": [{"type":"field","network":"tcp,udp","balancerTag":"auto"}],
                "balancers": [{"tag":"auto","selector":["wdtt-"],"strategy":{"type":"random"},"fallbackTag":"direct"}],
                "final": "auto"
              }
            }
            """.trimIndent()
        )
        assertTrue(OffsiteConfigValidator.validate(document).filter { it.severity == OffsiteSeverity.Error }.toString(), document.issues.isEmpty())
        assertTrue(OffsiteConfigValidator.validate(document).none { it.severity == OffsiteSeverity.Error })
    }

    @Test
    fun duplicateOutboundTagsAreAnError() {
        val issues = errors("""{"outbounds":[{"tag":"same","protocol":"freedom"},{"tag":"same","protocol":"freedom"}]}""")
        assertTrue(issues.toString(), issues.any { it.path == "outbounds[1].tag" })
    }

    @Test
    fun balancerSelectorMatchingNothingIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["other-"]}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[0].selector" })
    }

    @Test
    fun ruleReferencingAnUnknownOutboundTagIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"rules":[{"type":"field","outboundTag":"missing"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.rules[0].outboundTag" })
    }

    @Test
    fun ruleReferencingANonBalancerIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"rules":[{"type":"field","balancerTag":"node-1"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.rules[0].balancerTag" })
    }

    @Test
    fun unknownFinalTagIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"final":"missing"}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.final" })
    }

    @Test
    fun unknownBalancerStrategyIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["node-"],"strategy":{"type":"fastest"}}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[0].strategy.type" })
    }

    @Test
    fun balancerSelectingItselfIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"auto-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["auto"]}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[0].selector" })
    }

    @Test
    fun duplicateBalancerTagsAreAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["node-"]},{"tag":"auto","selector":["node-"]}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[1].tag" })
    }

    @Test
    fun outboundLimitIsEnforced() {
        val outbounds = (0..OffsiteConfigValidator.MAX_OUTBOUNDS).joinToString(",") {
            """{"tag":"o$it","protocol":"freedom"}"""
        }
        val issues = errors("""{"outbounds":[$outbounds]}""")
        assertTrue(issues.toString(), issues.any { it.path == "outbounds" })
    }

    @Test
    fun ruleLimitIsEnforced() {
        val rules = (0..OffsiteConfigValidator.MAX_RULES).joinToString(",") {
            """{"type":"field","outboundTag":"o"}"""
        }
        val issues = errors("""{"outbounds":[{"tag":"o","protocol":"freedom"}],"routing":{"rules":[$rules]}}""")
        assertTrue(issues.toString(), issues.any { it.path == "routing.rules" })
    }

    @Test
    fun documentSizeLimitIsEnforced() {
        val document = parse("""{"outbounds":[{"tag":"o","protocol":"freedom"}]}""")
        val issues = OffsiteConfigValidator.validate(document, OffsiteConfigValidator.MAX_DOCUMENT_BYTES + 1)
        assertTrue(issues.toString(), issues.any { it.severity == OffsiteSeverity.Error && it.path == "" })
    }

    @Test
    fun warningsDoNotBlockAValidDocument() {
        val document = parse(
            """
            {
              "unknownSection": true,
              "inbounds": [],
              "outbounds": [{"tag":"o","protocol":"freedom"}],
              "routing": {"rules":[{"type":"field","outboundTag":"o","attrs":["x"]}]},
              "dns": {"servers":["1.1.1.1"],"rules":[]}
            }
            """.trimIndent()
        )
        val issues = OffsiteConfigValidator.validate(document)
        assertTrue(issues.toString(), issues.none { it.severity == OffsiteSeverity.Error })
        assertFalse(issues.isEmpty())
        assertEquals(issues.size, issues.distinctBy { "${it.severity}|${it.path}|${it.message}" }.size)
    }

    @Test
    fun unknownBalancerFallbackTagIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["node-"],"fallbackTag":"missing"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[0].fallbackTag" })
    }

    @Test
    fun balancerFallingBackToItselfIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"node-1","protocol":"freedom"}],"routing":{"balancers":[{"tag":"auto","selector":["node-"],"fallbackTag":"auto"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.balancers[0].fallbackTag" })
    }

    @Test
    fun duplicateDnsServerTagsAreAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"o","protocol":"freedom"}],"dns":{"servers":[{"address":"1.1.1.1","tag":"x"},{"address":"8.8.8.8","tag":"x"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "dns.servers[1].tag" })
    }

    @Test
    fun invalidPortRangeIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"o","protocol":"freedom"}],"routing":{"rules":[{"type":"field","outboundTag":"o","port":"0,70000"}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.rules[0].port" })
    }

    @Test
    fun invalidCidrIsAnError() {
        val issues = errors(
            """{"outbounds":[{"tag":"o","protocol":"freedom"}],"routing":{"rules":[{"type":"field","outboundTag":"o","ip":["ext:geoip.dat:cn"]}]}}"""
        )
        assertTrue(issues.toString(), issues.any { it.path == "routing.rules[0].ip" })
    }
}
