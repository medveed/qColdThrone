package app.qcoldthrone.mobile.outbound.import

import app.qcoldthrone.mobile.outbound.types.Custom
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteConfigImportTest {

    private val offsite = """
        {
          "remarks": "Offsite mix",
          "outbounds": [
            {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}},
            {"tag":"direct","protocol":"freedom"}
          ],
          "routing": {"rules":[{"type":"field","outboundTag":"wdtt-1","domain":["example.com"]}]}
        }
    """.trimIndent()

    @Test
    fun offsiteDocumentImportsAsOneOffsiteConfig() {
        val result = ProfileImport.parse(offsite)
        assertEquals(result.messages.toString(), 1, result.outbounds.size)
        val profile = result.outbounds.single()
        assertTrue("expected OffsiteConfig, got ${profile.displayType()}", profile is OffsiteConfig)
        profile as OffsiteConfig
        assertEquals("Offsite mix", profile.name)
        assertEquals(offsite, profile.config)
        assertEquals(1, profile.parse().document!!.rules.size)
        assertEquals("qwdtt", profile.parse().document!!.outbounds.first().protocol)
    }

    @Test
    fun offsiteDocumentInsideProfilesWrapperIsStillOneOffsiteConfig() {
        val result = ProfileImport.parse("""{"profiles":[$offsite]}""")
        assertEquals(result.messages.toString(), 1, result.outbounds.size)
        val profile = result.outbounds.single()
        assertTrue("expected OffsiteConfig, got ${profile.displayType()}", profile is OffsiteConfig)
        assertEquals("Offsite mix", (profile as OffsiteConfig).name)
        assertEquals("wdtt-1", profile.parse().document!!.outbounds.first().tag)
    }

    @Test
    fun offsiteNameFallsBackToFirstOffsiteTag() {
        val body = """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}}]}"""
        val result = ProfileImport.parse(body)
        assertEquals(result.messages.toString(), 1, result.outbounds.size)
        assertEquals("wdtt-1", (result.outbounds.single() as OffsiteConfig).name)
    }

    @Test
    fun standardXrayConfigKeepsItsExistingImport() {
        val body = """{"outbounds":[{"tag":"hy","protocol":"hysteria","settings":{"version":2,"address":"example.org","port":443}}],"routing":{"balancers":[{"tag":"pool","selector":["hy"]}]}}"""
        val result = ProfileImport.parse(body)
        assertEquals(result.messages.toString(), 1, result.outbounds.size)
        val profile = result.outbounds.single()
        assertTrue("expected Custom, got ${profile.displayType()}", profile is Custom)
        assertEquals(Custom.CUSTOM_XRAY_FULL_CONFIG, (profile as Custom).subtype)
    }

}
