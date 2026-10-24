package app.qcoldthrone.mobile.bg.carrier
import app.qcoldthrone.mobile.outbound.json.JsonObject
interface NativeCarrier {
    val carrierKind:CarrierKind
    fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject
}
