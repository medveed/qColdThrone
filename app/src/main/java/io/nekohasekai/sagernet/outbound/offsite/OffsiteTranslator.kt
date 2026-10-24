package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.bg.carrier.CarrierPlan
import app.qcoldthrone.mobile.bg.carrier.NativeCarrier
import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.config.GeneratorSettings
import app.qcoldthrone.mobile.outbound.config.RoutingInput
import app.qcoldthrone.mobile.outbound.json.JsonArray
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.json.JsonValues
import app.qcoldthrone.mobile.outbound.json.jsonObjectOf
import app.qcoldthrone.mobile.route.RuleSets

/**
 * One translated document (docs/offsite-json-config.md §6): the core `outbounds`/`route` sections, the remote
 * rule-sets, the optional `dns` section and the carrier plans that must be started before the core.
 */
data class OffsiteTranslation(
    @JvmField val outbounds: JsonArray,
    @JvmField val endpoints: JsonArray,
    @JvmField val route: JsonObject,
    @JvmField val ruleSetUrls: Map<String, String>,
    @JvmField val dns: JsonObject?,
    @JvmField val carrierPlans: List<CarrierPlan>,
    @JvmField val error: String?,
    /** Non-blocking diagnostics (dns `domains`/`expectIPs`) from §5.7. */
    @JvmField val warnings: List<OffsiteIssue> = emptyList(),
)

/**
 * Translates the Xray-compatible document into sing-box sections (§6.1-§6.4).
 *
 * Offsite outbounds become loopback SOCKS5 bridges backed by a [CarrierPlan]; native outbounds are built through
 * their own model; `routing.rules`/`routing.balancers` and `dns` are mapped best-effort. The caller starts the
 * returned plans before the core, as for any carrier-backed profile (§6.5).
 */
object OffsiteTranslator {

    /** §6.3 defaults. */
    private const val DEFAULT_EXPECTED = 3
    private const val DEFAULT_LEAST_PING_BASETIME = 300
    private const val DEFAULT_LEAST_LOAD_BASETIME = 30
    private const val DEFAULT_RANDOM_BASETIME = 30

    private val DURATION = Regex("^(\\d+)(ms|s|m|h)?$", RegexOption.IGNORE_CASE)

    /** Translate [document]; [error] is the first failure (§6.2, §6.3, §9). */
    @JvmStatic
    fun translate(
        document: OffsiteDocument,
        settings: GeneratorSettings,
        buildContext: BuildContext,
        routing: RoutingInput,
    ): OffsiteTranslation {
        var error: String? = null
        fun fail(message: String) {
            if (error == null) error = message
        }

        val outbounds = JsonArray()
        val endpoints = JsonArray()
        val carrierPlans = ArrayList<CarrierPlan>()
        val outboundTags = ArrayList<String>()
        val plansByKey = LinkedHashMap<String, CarrierPlan>()

        for ((index, outbound) in document.outbounds.withIndex()) {
            val tag = outbound.tag
            if (outbound.isOffsite) {
                val carrier = outbound.outbound as? NativeCarrier
                if (carrier == null) {
                    fail("offsite outbound $tag has no native carrier settings")
                    continue
                }
                try {
                    // Identical settings must share one carrier session: two plans with the same key make
                    // CarrierPool.acquire fail on the second lease. Synthetic negative ids keep the plans
                    // apart from stored profiles (§6.1, §7).
                    val plan = plansByKey.getOrPut(CarrierPlan.identityKey(carrier)) {
                        CarrierPlan.native(-(index + 1).toLong(), carrier)
                    }
                    if (plan !in carrierPlans) carrierPlans.add(plan)
                    outbounds.add(
                        jsonObjectOf(
                            "type" to "socks",
                            "tag" to tag,
                            "server" to "127.0.0.1",
                            "server_port" to plan.port,
                            "version" to "5",
                            "username" to plan.credentials.username,
                            "password" to plan.credentials.password,
                        ),
                    )
                    outboundTags.add(tag)
                } catch (e: Exception) {
                    fail("offsite outbound $tag: ${e.message ?: e::class.java.simpleName}")
                }
                continue
            }

            when (outbound.protocol) {
                "freedom", "direct" -> {
                    outbounds.add(jsonObjectOf("type" to "direct", "tag" to tag))
                    outboundTags.add(tag)
                }
                "blackhole", "block" -> {
                    outbounds.add(jsonObjectOf("type" to "block", "tag" to tag))
                    outboundTags.add(tag)
                }
                else -> {
                    val standard = outbound.outbound
                    if (standard == null || isXrayShaped(outbound.settings)) {
                        fail(standardShapeError(tag, outbound.protocol))
                        continue
                    }
                    val result = standard.build(buildContext)
                    if (result.error.isNotEmpty()) {
                        fail("standard outbound $tag: ${result.error}")
                        continue
                    }
                    val json = result.json.copy()
                    json["tag"] = tag
                    // WireGuard/AmneziaWG, Masque, OpenVPN and OpenConnect are sing-box endpoints, not outbounds.
                    if (standard.isEndpoint()) endpoints.add(json) else outbounds.add(json)
                    outboundTags.add(tag)
                }
            }
        }

        // Groups come after their members (§6.3).
        for (balancer in document.balancers) {
            val members = JsonArray()
            val seen = LinkedHashSet<String>()
            for (tag in outboundTags) {
                if (balancer.selectors.any { tag.startsWith(it) } && seen.add(tag)) members.add(tag)
            }
            if (members.isEmpty()) {
                fail("balancer ${balancer.tag} selects no outbounds")
                continue
            }
            // fallbackTag is a real core option resolved at start (the validator refused an unknown tag), not a member.
            outbounds.add(buildGroup(balancer, members, settings))
        }

        val route = JsonObject()
        val ruleSetUrls = LinkedHashMap<String, String>()

        fun addRuleSet(prefix: String, name: String, path: String, sink: JsonArray) {
            if (name.isEmpty()) return
            val tag = "$prefix-$name"
            if (sink.none { it == tag }) sink.add(tag)
            if (ruleSetUrls.containsKey(tag)) return
            val url = routing.catalog.urlOf(tag) ?: routing.catalog.urlOf(name)
            if (url == null) {
                fail("Unknown rule-set \"$name\" in $path")
                return
            }
            ruleSetUrls[tag] = RuleSets.mirrorLink(url, settings.rulesetMirror)
        }

        val rules = JsonArray()
        for ((index, rule) in document.rules.withIndex()) {
            val path = "routing.rules[$index]"
            val raw = rule.raw
            val obj = JsonObject()

            rule.outboundTag?.let { obj["outbound"] = it } ?: rule.balancerTag?.let { obj["outbound"] = it }

            if (raw.contains("inboundTag")) {
                if (raw.isArray("inboundTag")) {
                    if (rule.inboundTags.isNotEmpty()) obj["inbound"] = JsonValues.stringArray(rule.inboundTags)
                } else if (rule.inboundTags.size == 1) {
                    obj["inbound"] = rule.inboundTags[0]
                }
            }

            val domains = JsonArray()
            val domainSuffixes = JsonArray()
            val domainKeywords = JsonArray()
            val domainRegexes = JsonArray()
            val ruleSetTags = JsonArray()
            for (item in stringValues(raw, "domain")) {
                val value = item.trim()
                if (value.isEmpty()) continue
                when {
                    value.startsWith("full:") -> domains.add(value.substringAfter("full:"))
                    value.startsWith("domain:") -> domainSuffixes.add(value.substringAfter("domain:"))
                    value.startsWith("keyword:") -> domainKeywords.add(value.substringAfter("keyword:"))
                    value.startsWith("regexp:") -> domainRegexes.add(value.substringAfter("regexp:"))
                    value.startsWith("geosite:") ->
                        addRuleSet("geosite", value.substringAfter("geosite:"), "$path.domain", ruleSetTags)
                    else -> domainKeywords.add(value)
                }
            }
            if (domains.isNotEmpty()) obj["domain"] = domains
            if (domainSuffixes.isNotEmpty()) obj["domain_suffix"] = domainSuffixes
            if (domainKeywords.isNotEmpty()) obj["domain_keyword"] = domainKeywords
            if (domainRegexes.isNotEmpty()) obj["domain_regex"] = domainRegexes

            val ipCidrs = JsonArray()
            for (item in stringValues(raw, "ip")) {
                val value = item.trim()
                if (value.isEmpty()) continue
                if (value.startsWith("geoip:")) {
                    addRuleSet("geoip", value.substringAfter("geoip:"), "$path.ip", ruleSetTags)
                } else {
                    ipCidrs.add(withPrefix(value))
                }
            }
            if (ipCidrs.isNotEmpty()) obj["ip_cidr"] = ipCidrs
            if (ruleSetTags.isNotEmpty()) obj["rule_set"] = ruleSetTags

            fillPorts(obj, "port", "port_range", textValues(raw, "port"))
            fillPorts(obj, "source_port", "source_port_range", textValues(raw, "sourcePort"))

            val sourceCidrs = JsonArray()
            for (item in stringValues(raw, "source")) {
                val value = item.trim()
                if (value.isNotEmpty()) sourceCidrs.add(withPrefix(value))
            }
            if (sourceCidrs.isNotEmpty()) obj["source_ip_cidr"] = sourceCidrs

            val networks = textValues(raw, "network").flatMap { it.split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (networks.size == 1) obj["network"] = networks[0]
            else if (networks.size > 1) obj["network"] = JsonValues.stringArray(networks)

            val protocols = textValues(raw, "protocol").flatMap { it.split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (protocols.size == 1) obj["protocol"] = protocols[0]
            else if (protocols.size > 1) obj["protocol"] = JsonValues.stringArray(protocols)

            val users = stringValues(raw, "user").map { it.trim() }.filter { it.isNotEmpty() }
            if (users.size == 1) obj["auth_user"] = users[0]
            else if (users.size > 1) obj["auth_user"] = JsonValues.stringArray(users)

            // `type`, `ruleTag` and `attrs` are deliberately dropped (§5.5).
            if (obj.isNotEmpty()) rules.add(obj)
        }

        if (ruleSetUrls.isNotEmpty()) {
            val definitions = JsonArray()
            for ((tag, url) in ruleSetUrls) {
                definitions.add(
                    jsonObjectOf(
                        "type" to "remote",
                        "tag" to tag,
                        "format" to "binary",
                        "url" to url,
                    ),
                )
            }
            route["rule_set"] = definitions
        }
        route["rules"] = rules

        val finalTag = document.finalTag ?: document.outbounds.firstOrNull()?.tag
        if (!finalTag.isNullOrEmpty()) route["final"] = finalTag

        val warnings = ArrayList<OffsiteIssue>()
        val dns = document.dns?.let { buildDns(it, warnings) }

        return OffsiteTranslation(
            outbounds = outbounds,
            endpoints = endpoints,
            route = route,
            ruleSetUrls = ruleSetUrls,
            dns = dns,
            carrierPlans = carrierPlans,
            error = error,
            warnings = warnings,
        )
    }

    /**
     * §5.1: Xray `log` → sing-box `log`. Xray uses `loglevel`/`access`/`error`/`dnsLog`, none of which the core
     * accepts (`DisallowUnknownFields`), so only `level`/`disabled` are emitted.
     */
    @JvmStatic
    fun translateLog(log: JsonObject, defaultLevel: String): JsonObject {
        val level = log.string("loglevel").ifEmpty { log.string("level") }.ifEmpty { defaultLevel }.lowercase()
        return when (level) {
            "none" -> jsonObjectOf("disabled" to true)
            "warning" -> jsonObjectOf("level" to "warn")
            else -> jsonObjectOf("level" to level)
        }
    }

    /**
     * §6.3: an Xray balancer as a native `auto-selector`.
     *
     * `leastPing` ranks by average RTT (the new group `strategy`), `leastLoad` is Xray's jitter-ranked
     * random pick over the top-`expected` members (`balance_mode: connection`), and `random` does the same
     * over every healthy member. `maxRTT` gates on latency, `tolerance` is Xray's failure ratio
     * (`fail_tolerance`), `baselines` are deviation cut-offs, `fallbackTag` becomes the `fallback` option.
     */
    private fun buildGroup(balancer: OffsiteBalancer, members: JsonArray, settings: GeneratorSettings): JsonObject {
        val strategy = balancer.strategy ?: "random"
        val group = jsonObjectOf(
            "type" to "auto-selector",
            "tag" to balancer.tag,
            "outbounds" to members,
            "url" to settings.testUrl,
        )
        when (strategy) {
            "leastPing" -> {
                group["strategy"] = "leastPing"
                group["interval"] = "${settingsSeconds(balancer.settings, "basetime", DEFAULT_LEAST_PING_BASETIME)}s"
            }
            "leastLoad" -> {
                group["balance"] = true
                group["balance_mode"] = "connection"
                group["expected"] = settingsInt(balancer.settings, "expected") ?: DEFAULT_EXPECTED
                settingsMillis(balancer.settings, "maxRTT", 0).takeIf { it > 0 }?.let { group["max_rtt"] = "${it}ms" }
                settingsRatio(balancer.settings, "tolerance")?.let { group["fail_tolerance"] = it }
                settingsBaselines(balancer.settings)?.let { group["baselines"] = it }
                group["interval"] = "${settingsSeconds(balancer.settings, "basetime", DEFAULT_LEAST_LOAD_BASETIME)}s"
            }
            // random (the Xray default): a uniformly random healthy member, so every one stays qualified.
            else -> {
                group["balance"] = true
                group["balance_mode"] = "connection"
                group["expected"] = maxOf(members.size, 1)
                group["interval"] = "${settingsSeconds(balancer.settings, "basetime", DEFAULT_RANDOM_BASETIME)}s"
            }
        }
        balancer.fallbackTag?.takeIf { it.isNotEmpty() }?.let { group["fallback"] = it }
        return group
    }

    /** §5.7/§6.4: a full `dns` object, or null when the document has no `dns` section. */
    private fun buildDns(dns: OffsiteDns, warnings: MutableList<OffsiteIssue>): JsonObject {
        val servers = JsonArray()
        for ((index, server) in dns.servers.withIndex()) {
            val parsed = parseDnsAddress(server.address)
            if (parsed == null) {
                warnings.add(
                    OffsiteIssue(
                        OffsiteSeverity.Warning,
                        "dns.servers[$index].address",
                        "unsupported DNS address; ignored",
                    ),
                )
                continue
            }
            val tag = server.tag?.takeIf { it.isNotEmpty() } ?: "dns-$index"
            parsed["tag"] = tag
            servers.add(parsed)
            if (server.domains.isNotEmpty()) {
                warnings.add(
                    OffsiteIssue(
                        OffsiteSeverity.Warning,
                        "dns.servers[$index].domains",
                        "per-server domains are not supported in v1; ignored",
                    ),
                )
            }
            if (server.expectIps.isNotEmpty()) {
                warnings.add(
                    OffsiteIssue(
                        OffsiteSeverity.Warning,
                        "dns.servers[$index].expectIPs",
                        "expectIPs is not supported in v1; ignored",
                    ),
                )
            }
        }
        val obj = jsonObjectOf("servers" to servers)
        when (dns.queryStrategy) {
            "UseIP" -> obj["strategy"] = "prefer_ipv4"
            "UseIPv4" -> obj["strategy"] = "ipv4_only"
            "UseIPv6" -> obj["strategy"] = "ipv6_only"
        }
        return obj
    }

    /** `udp://`/`tcp://`/`tls://`/`https://`/plain/localhost → a sing-box DNS server; null for other schemes (§5.7). */
    private fun parseDnsAddress(address: String): JsonObject? {
        val value = address.trim()
        if (value.equals("localhost", ignoreCase = true)) return jsonObjectOf("type" to "local")
        if (value.startsWith("https://")) {
            val rest = value.substring("https://".length)
            val slash = rest.indexOf('/')
            val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else ""
            val (host, port) = splitHostPort(hostPort)
            val obj = jsonObjectOf("type" to "https", "server" to host)
            if (port != null) obj["server_port"] = port
            if (path.isNotEmpty()) obj["path"] = path
            return obj
        }
        // Any other explicit scheme (quic://, h3://, …) has no faithful sing-box mapping here.
        if (value.contains("://")) return null
        val (scheme, rest) = when {
            value.startsWith("tls://") -> "tls" to value.substring("tls://".length)
            value.startsWith("tcp://") -> "tcp" to value.substring("tcp://".length)
            value.startsWith("udp://") -> "udp" to value.substring("udp://".length)
            else -> "udp" to value
        }
        val (host, port) = splitHostPort(rest)
        val defaultPort = if (scheme == "tls") 853 else 53
        return jsonObjectOf("type" to scheme, "server" to host, "server_port" to (port ?: defaultPort))
    }

    /** `host`, `host:port` or `[v6]:port`; a bare IPv6 address keeps its colons. */
    private fun splitHostPort(text: String): Pair<String, Int?> {
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close > 0) {
                val host = text.substring(1, close)
                val port = if (text.length > close + 2 && text[close + 1] == ':') {
                    text.substring(close + 2).toIntOrNull()
                } else null
                return host to port
            }
        }
        val first = text.indexOf(':')
        if (first < 0) return text to null
        if (text.indexOf(':', first + 1) >= 0) return text to null
        return text.substring(0, first) to text.substring(first + 1).toIntOrNull()
    }

    /** Bare IPs become a host route; CIDRs pass through (§5.5). */
    private fun withPrefix(value: String): String =
        if (value.contains('/')) value else if (value.contains(':')) "$value/128" else "$value/32"

    private fun fillPorts(obj: JsonObject, portKey: String, rangeKey: String, values: List<String>) {
        val ports = JsonArray()
        val ranges = JsonArray()
        for (value in values) {
            for (token in value.split(',')) {
                val item = token.trim()
                if (item.isEmpty()) continue
                val dash = item.indexOf('-')
                if (dash > 0) {
                    ranges.add(item.substring(0, dash) + ":" + item.substring(dash + 1))
                } else {
                    item.toIntOrNull()?.let { ports.add(it) }
                }
            }
        }
        if (ports.isNotEmpty()) obj[portKey] = ports
        if (ranges.isNotEmpty()) obj[rangeKey] = ranges
    }

    /** String values of a rule field; a scalar string or a string array (§5.5). */
    private fun stringValues(obj: JsonObject, key: String): List<String> = when {
        !obj.contains(key) -> emptyList()
        obj.isArray(key) -> obj.array(key).strings().filter { it.isNotEmpty() }
        obj.isString(key) -> listOf(obj.string(key))
        else -> emptyList()
    }

    /** Text values of a scalar or array field, including numbers (`port`: 80). */
    private fun textValues(obj: JsonObject, key: String): List<String> = when {
        !obj.contains(key) -> emptyList()
        obj.isArray(key) -> obj.array(key).map { JsonValues.variantToString(it) }.filter { it.isNotEmpty() }
        else -> listOf(JsonValues.variantToString(obj[key])).filter { it.isNotEmpty() }
    }

    /** A 0..1 ratio (`tolerance` is a failure ratio in Xray leastLoad); null when absent or out of range. */
    private fun settingsRatio(settings: JsonObject, key: String): Double? {
        val value = settings[key] ?: return null
        val ratio = when (value) {
            is Double -> value
            is Long -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return ratio.takeIf { it > 0.0 && it <= 1.0 }
    }

    /** `baselines` (Xray durations, passed through unchanged): RTT-deviation cut-offs for the qualified set. */
    private fun settingsBaselines(settings: JsonObject): JsonArray? {
        val array = settings["baselines"] as? JsonArray ?: return null
        return array.takeIf { it.isNotEmpty() }
    }

    private fun settingsInt(settings: JsonObject, key: String): Int? {
        val value = settings[key] ?: return null
        val number = if (value is String) value.trim().toIntOrNull() else JsonValues.toInteger(value).toInt()
        return number?.takeIf { it > 0 }
    }

    private fun settingsSeconds(settings: JsonObject, key: String, default: Int): Int {
        val value = settings[key] ?: return default
        val text = JsonValues.variantToString(value).trim()
        val match = DURATION.matchEntire(text) ?: return default
        val number = match.groupValues[1].toIntOrNull() ?: return default
        return when (match.groupValues[2].lowercase()) {
            "ms" -> maxOf(1, number / 1000)
            "m" -> number * 60
            "h" -> number * 3600
            else -> number
        }
    }

    private fun settingsMillis(settings: JsonObject, key: String, default: Int): Int {
        val value = settings[key] ?: return default
        val text = JsonValues.variantToString(value).trim()
        text.toIntOrNull()?.let { return it }
        val match = DURATION.matchEntire(text) ?: return default
        val number = match.groupValues[1].toIntOrNull() ?: return default
        return when (match.groupValues[2].lowercase()) {
            "s" -> number * 1000
            "m" -> number * 60_000
            "h" -> number * 3_600_000
            else -> number
        }
    }

    /** The v1 boundary: only sing-box-shaped `settings` are accepted for native protocols (§5.4, §12). */
    private fun isXrayShaped(settings: JsonObject): Boolean =
        settings.contains("servers") || settings.contains("vnext")

    private fun standardShapeError(tag: String, protocol: String): String =
        "standard outbound $tag (protocol $protocol) must use sing-box-shaped settings in v1; " +
            "Xray shape conversion is not implemented yet"
}
