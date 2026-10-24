package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.OutboundFactory
import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsiteConfigTest {

    private val document = """
        {
          "remarks": "Offsite mix",
          "outbounds": [
            {"tag":"wdtt-1","protocol":"qwdtt","settings":{"server":"turn.example.com","server_port":56000,"password":"secret","hashes":["hash1"]}},
            {"tag":"direct","protocol":"freedom"}
          ]
        }
    """.trimIndent()

    @Test
    fun profileJsonRoundTrips() {
        val profile = OffsiteConfig().apply {
            name = "Offsite mix"
            config = document
        }
        val json = profile.exportToJson()
        assertEquals("offsiteconfig", json.string("type"))
        assertEquals("Offsite mix", json.string("name"))
        assertEquals(document, json.string("config"))

        val restored = OutboundFactory.newByType("offsiteconfig")
        assertTrue(restored is OffsiteConfig)
        assertTrue(restored.parseFromJson(JsonInput.parseObject(json.toCompact())))
        restored as OffsiteConfig
        assertEquals("Offsite mix", restored.name)
        assertEquals(document, restored.config)
        assertEquals(json, restored.exportToJson())
    }

    @Test
    fun exportOmitsEmptyNameAndIdentityHasNoName() {
        val profile = OffsiteConfig().apply { config = document }
        assertFalse(profile.exportToJson().contains("name"))
        assertFalse(profile.exportIdentity().contains("name"))
        assertEquals(document, profile.exportIdentity().string("config"))
    }

    @Test
    fun parseFromJsonIsTolerantOfAnInvalidDocument() {
        val profile = OffsiteConfig()
        assertTrue(profile.parseFromJson(JsonInput.parseObject("""{"name":"bad","type":"offsiteconfig","config":"{"}""")))
        assertEquals("bad", profile.name)
        assertFalse(profile.parse().ok)
        assertNull(profile.parse().document)
    }

    @Test
    fun displayTypeAndAddress() {
        val profile = OffsiteConfig().apply { config = document }
        assertEquals("Offsite Config", profile.displayType())
        assertEquals("wdtt-1", profile.displayAddress())

        profile.config = """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}]}"""
        assertEquals("<2> outbounds", profile.displayAddress())

        profile.config = "not json"
        assertEquals("", profile.displayAddress())
    }

    @Test
    fun profileIsSelfContainedAndNotShareable() {
        val profile = OffsiteConfig().apply { config = document }
        assertFalse(profile.isXray())
        assertFalse(profile.isEndpoint())
        assertEquals("", profile.exportToLink())
        val built = profile.build(BuildContext())
        assertFalse(built.ok)
        assertEquals("Offsite config is expanded by the generator", built.error)
    }
}
