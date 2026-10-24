package app.qcoldthrone.mobile.bg.carrier

import app.qcoldthrone.mobile.outbound.types.Qwdtt
import app.qcoldthrone.mobile.outbound.types.Socks
import java.net.ServerSocket
import java.net.DatagramSocket
import java.net.InetAddress

/** One generation owns its bridge; no runtime secret is written back to a stored profile. */
class CarrierPlan private constructor(
    val profileId:Long, val kind:CarrierKind, val port:Int, val key:String,
    val credentials:CarrierBridgeCredentials, val privateJson:String,
) {
    fun outbound()=Socks().also {
        it.server="127.0.0.1";it.serverPort=port
        it.username=credentials.username;it.password=credentials.password
    }
    companion object {
        /**
         * A plan for any carrier-backed outbound (docs/offsite-json-config.md §6, §7): a free loopback port,
         * fresh SOCKS credentials and the client's private JSON. [native] is the generic entry point.
         * [withAccount] false checks the configuration only: automatic hashes are not requested from VK.
         * [interactive] false (tests) never waits for a VK sign-in.
         */
        fun native(profileId:Long, profile:NativeCarrier, withAccount:Boolean=true, interactive:Boolean=true):CarrierPlan =
            qwdtt(profileId, profile, withAccount=withAccount, interactive=interactive)

        /** Stable identity of a carrier profile: `exportToJson` minus name/tag, SHA-256 (used for pool dedup). */
        @JvmStatic
        fun identityKey(profile:NativeCarrier):String {
            val identity=(profile as app.qcoldthrone.mobile.outbound.Outbound).exportToJson().also { it.remove("name");it.remove("tag") }.toCompact()
            return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        fun qwdtt(profileId:Long, profile:NativeCarrier, withAccount:Boolean=true, interactive:Boolean=true):CarrierPlan {
            val key=identityKey(profile)
            CarrierPool.activePlan(key)?.let { return it }
            val runtimeProfile = if(profile is Qwdtt && withAccount) NativeHashPolicy.runtimeProfile(profile, { VkRelayAccount.hashes(it, interactive) }) { VkRelayAccount.tokenForCalls(interactive) } else profile
            val loopback=InetAddress.getByName("127.0.0.1")
            val credentials=CarrierBridgeCredentials.fresh()
            return ServerSocket(0,1,loopback).use { tcp -> DatagramSocket(0,loopback).use { udp ->
                CarrierPlan(profileId,profile.carrierKind,tcp.localPort,key,credentials,
                    runtimeProfile.privateConfig(tcp.localPort,udp.localPort,credentials).also {
                        if(profile is Qwdtt && profile.type=="qwdtt" && profile.hashSource=="auto") it["vk_auth"]="account"
                    }.toCompact())
            } }
        }
    }
}
