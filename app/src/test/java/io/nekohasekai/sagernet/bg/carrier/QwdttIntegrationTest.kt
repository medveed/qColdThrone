package app.qcoldthrone.mobile.bg.carrier

import app.qcoldthrone.mobile.outbound.OutboundFactory
import app.qcoldthrone.mobile.outbound.types.Qwdtt
import app.qcoldthrone.mobile.outbound.json.JsonInput
import org.junit.Assert.*
import org.junit.Test

class QwdttIntegrationTest {
    private fun profile()=Qwdtt().apply {
        assertTrue(parseFromLink("qwdtt://config?peer=192.0.2.1%3A56000&pass=a%2Bb&hashes=demo_hash_123456789&device_id=stable-test&mode=raw"))
    }
    @Test fun importPreservesLiteralPlusAndDeviceIdentity() {
        val original=profile()
        assertEquals("a+b",original.password)
        val restored=Qwdtt()
        assertTrue(restored.parseFromLink(original.exportToLink()))
        assertEquals(original.password,restored.password)
        assertEquals("stable-test",restored.deviceId)
        assertEquals("qwdtt",OutboundFactory.typeForScheme(original.exportToLink()))
        assertTrue(OutboundFactory.newByType("qwdtt") is Qwdtt)
    }
    @Test fun runtimePlanDoesNotPersistPortsOrBridgeSecrets() {
        val profile=profile()
        val before=profile.exportToJson().toCompact()
        val plan=CarrierPlan.qwdtt(7,profile)
        val config=JsonInput.parseObject(plan.privateJson)
        assertEquals("127.0.0.1:"+plan.port,config.string("socks"))
        assertEquals(plan.credentials.username,config.string("socks_user"))
        assertEquals(plan.credentials.password,plan.outbound().password)
        assertEquals(before,profile.exportToJson().toCompact())
        assertFalse(before.contains(plan.credentials.password))
        assertEquals("stable-test",config.string("device_id"))
    }
    @Test fun nameAndDeviceIdDoNotChangeSubscriptionIdentity() {
        val profile=profile()
        val identity=profile.exportIdentity().toCompact()
        profile.name="Renamed";profile.deviceId="another-device"
        assertEquals(identity,profile.exportIdentity().toCompact())
    }
    @Test fun invalidPrivateConfigIsRejectedBeforeNativeLaunch() {
        val profile=profile();profile.password="bad|secret"
        try { CarrierPlan.qwdtt(7,profile);fail("invalid password accepted") } catch(_:IllegalArgumentException) {}
    }
    @Test fun excessQwdttHashesKeepFirstFourSlots() {
        val input=profile().exportToJson()
        val hashes=(1..7).map { "test_hash_123456789_$it" }
        input["hashes"]=app.qcoldthrone.mobile.outbound.json.JsonArray.of(*hashes.toTypedArray())
        val restored=Qwdtt()
        assertTrue(restored.parseFromJson(input))
        assertEquals(hashes.take(4),restored.hashes)
        assertEquals(hashes.take(4),Qwdtt().apply { assertTrue(parseFromLink(restored.exportToLink())) }.hashes)
    }
    @Test fun excessCsqttLinkHashesKeepFirstSixSlots() {
        val hashes=(1..8).map { "test_hash_123456789_$it" }
        val link="csqtt://connect?v=2&host=192.0.2.1&peer=46000&password=test-password&hashes="+hashes.joinToString("+")
        val restored=app.qcoldthrone.mobile.outbound.types.Csqtt()
        assertTrue(restored.parseFromLink(link))
        assertEquals(hashes.take(6),restored.hashes)
        val again=app.qcoldthrone.mobile.outbound.types.Csqtt()
        assertTrue(again.parseFromLink(restored.exportToLink()))
        assertEquals(restored.hashes,again.hashes)
    }

}
