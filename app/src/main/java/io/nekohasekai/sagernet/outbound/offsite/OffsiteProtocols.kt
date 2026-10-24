package app.qcoldthrone.mobile.outbound.offsite

/**
 * Protocol sets of docs/offsite-json-config.md §5.3 and §5.4.
 *
 * [OFFSITE] is the carrier-backed set that is expanded by the generator; [STANDARD] is the minimal set of
 * Xray protocol names the core can serve natively in v1.
 */
object OffsiteProtocols {

    /** The 11 carrier protocols (§5.3). */
    @JvmField
    val OFFSITE: Set<String> = linkedSetOf(
        "qwdtt", "csqtt", "wdttplus", "olcrtc", "openflux", "dnstt", "vaydns", "slipstream",
        "masterdns", "s3bypass", "sstp",
    )

    /** The native protocols accepted in the same document (§5.4). */
    @JvmField
    val STANDARD: Set<String> = linkedSetOf(
        "freedom", "direct", "blackhole", "block", "socks", "http", "shadowsocks", "vmess", "vless",
        "trojan", "hysteria", "hysteria2", "hy2", "tuic", "juicity", "anytls", "shadowtls", "mieru",
        "snell", "naive", "wireguard", "masque", "openvpn", "openconnect", "trusttunnel",
    )

    /** Xray names that map to a different factory type (§5.4). */
    @JvmField
    val ALIASES: Map<String, String> = mapOf(
        "freedom" to "direct",
        "hy2" to "hysteria2",
        "hysteria2" to "hysteria",
    )

    /** Offsite aliases of kProtocols (OutboundFactory.TYPE_ALIASES): `wdtt` is the legacy qWDTT name. */
    @JvmField
    val OFFSITE_ALIASES: Map<String, String> = mapOf("wdtt" to "qwdtt")

    /** Infrastructure protocols that are not an egress for the profile list (§8). */
    @JvmField
    val INFRA: Set<String> = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")

    /** True for a carrier-backed offsite protocol (§5.3), including its aliases. */
    @JvmStatic
    fun isOffsite(protocol: String): Boolean = canonicalOffsite(protocol) in OFFSITE

    /** The factory type of an offsite protocol, following [OFFSITE_ALIASES]. */
    @JvmStatic
    fun canonicalOffsite(protocol: String): String = OFFSITE_ALIASES[protocol] ?: protocol

    /** True for a native protocol or one of its Xray aliases (§5.4). */
    @JvmStatic
    fun isStandard(protocol: String): Boolean = protocol in STANDARD

    /** True when [protocol] belongs to either accepted set. */
    @JvmStatic
    fun isKnown(protocol: String): Boolean = isOffsite(protocol) || isStandard(protocol)

    /** The factory type for a standard protocol, following [ALIASES]; unknown names are returned unchanged. */
    @JvmStatic
    fun canonicalStandard(protocol: String): String = ALIASES[protocol] ?: protocol

    /** True for a protocol that only forwards or blocks traffic (§8 displayAddress). */
    @JvmStatic
    fun isInfra(protocol: String): Boolean = protocol in INFRA
}
