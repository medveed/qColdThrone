package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.OutboundFactory
import app.qcoldthrone.mobile.outbound.json.JsonArray
import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.json.JsonObject

/**
 * Tolerant parser for the Xray-compatible offsite document (docs/offsite-json-config.md §5).
 *
 * Parsing never throws: structural problems become [OffsiteIssue]s with stable paths. The document is
 * returned even when individual outbounds are invalid, so the UI can still show its tags (§8).
 */
object OffsiteConfigParser {

    private val TOP_LEVEL = setOf(
        "\$schema", "log", "remarks", "name", "dns", "inbounds", "outbounds", "routing",
        "observatory", "policy", "stats", "api", "reverse",
    )
    private val UNSUPPORTED_SECTIONS = setOf("observatory", "policy", "stats", "api", "reverse")
    private val ROUTING_FIELDS = setOf(
        "domainStrategy", "domainMatcher", "rules", "balancers", "final",
    )
    private val RULE_FIELDS = setOf(
        "type", "outboundTag", "balancerTag", "inboundTag", "domain", "ip", "port", "source",
        "sourcePort", "network", "protocol", "user", "attrs", "ruleTag",
    )
    private val BALANCER_FIELDS = setOf("tag", "selector", "strategy", "fallbackTag")
    private val DNS_FIELDS = setOf("servers", "queryStrategy", "rules", "hosts")

    /** Parse [text] into the model; see [OffsiteParseResult.ok]. */
    @JvmStatic
    fun parse(text: String): OffsiteParseResult {
        val root = JsonInput.parseObjectOrNull(text)
            ?: return OffsiteParseResult(null, listOf(error("", "document is not a JSON object")))

        val issues = ArrayList<OffsiteIssue>()

        for (key in root.keys()) {
            when (key) {
                in UNSUPPORTED_SECTIONS -> issues += warning(key, "section is not supported in v1")
                !in TOP_LEVEL -> issues += warning(key, "unknown top-level field")
            }
        }

        val log = JsonObject()
        if (root.contains("log")) {
            if (root.isObject("log")) log.merge(root.obj("log"))
            else issues += warning("log", "log must be an object; ignored")
        }

        if (root.contains("inbounds")) issues += warning("inbounds", "ignored; the app injects its own inbounds")

        val outbounds = parseOutbounds(root, issues) ?: return OffsiteParseResult(null, issues)

        val routing = if (root.contains("routing")) parseRouting(root, issues) else ParsedRouting()
        val dns = if (root.contains("dns")) parseDns(root, issues) else null

        val name = root.string("remarks").ifEmpty {
            root.string("name").ifEmpty {
                outbounds.firstOrNull { it.tag.isNotEmpty() }?.tag ?: "Offsite Config"
            }
        }

        val document = OffsiteDocument(
            name = name,
            outbounds = outbounds,
            rules = routing.rules,
            balancers = routing.balancers,
            finalTag = routing.finalTag,
            dns = dns,
            log = log,
            issues = issues.toList(),
        )
        return OffsiteParseResult(document, issues)
    }

    /** `outbounds` is required and non-empty; null means the document cannot be represented (§5.1). */
    private fun parseOutbounds(root: JsonObject, issues: MutableList<OffsiteIssue>): List<OffsiteOutbound>? {
        if (!root.contains("outbounds")) {
            issues += error("outbounds", "required section is missing")
            return null
        }
        if (!root.isArray("outbounds")) {
            issues += error("outbounds", "must be an array")
            return null
        }
        val source = root.array("outbounds")
        if (source.isEmpty()) {
            issues += error("outbounds", "must contain at least one outbound")
            return null
        }

        val result = ArrayList<OffsiteOutbound>()
        val tags = HashSet<String>()
        for ((index, item) in source.withIndex()) {
            val path = "outbounds[$index]"
            val obj = item as? JsonObject
            if (obj == null) {
                issues += error(path, "must be an object")
                continue
            }
            val tag = obj.string("tag")
            if (tag.isEmpty()) issues += error("$path.tag", "must be a non-empty string")
            else if (!tags.add(tag)) issues += error("$path.tag", "duplicate tag")
            val rawProtocol = obj.string("protocol")
            if (rawProtocol.isEmpty()) {
                issues += error("$path.protocol", "must be a non-empty string")
                continue
            }
            // Offsite aliases (wdtt → qwdtt) resolve to their model type; standard aliases are handled by parseStandard.
            val protocol = OffsiteProtocols.canonicalOffsite(rawProtocol)

            var settings = JsonObject()
            if (obj.contains("settings")) {
                if (obj.isObject("settings")) settings = obj.obj("settings").copy()
                else {
                    issues += error("$path.settings", "must be an object")
                    continue
                }
            }
            // §5.2: parseFromJson of every type requires the profile type and name.
            if (!settings.contains("type")) settings["type"] = protocol
            if (!settings.contains("name")) settings["name"] = tag

            if (OffsiteProtocols.isOffsite(protocol)) {
                if (obj.contains("streamSettings"))
                    issues += warning("$path.streamSettings", "not used by offsite protocols; configure the transport in settings")
                val outbound = OutboundFactory.newByType(protocol)
                var parsed = false
                if (outbound.invalid) {
                    issues += error("$path.protocol", "protocol is not supported: $protocol")
                } else {
                    parsed = outbound.parseFromJson(settings) && !outbound.invalid
                    if (!parsed) issues += error("$path.settings", "invalid settings for protocol $protocol")
                }
                result += OffsiteOutbound(tag, protocol, settings, outbound.takeIf { parsed }, true)
                continue
            }

            if (!OffsiteProtocols.isStandard(protocol)) {
                issues += error("$path.protocol", "unknown protocol: $rawProtocol")
                result += OffsiteOutbound(tag, protocol, settings, null, false)
                continue
            }

            val standard = parseStandard(protocol, tag, settings)
            // infra (freedom/blackhole) has no ported model and is emitted by the translator directly.
            if (standard == null && !OffsiteProtocols.isInfra(protocol)) {
                issues += error("$path.settings", "invalid settings for protocol $protocol")
            }
            result += OffsiteOutbound(tag, protocol, settings, standard, false)
        }
        return result
    }

    /** Best-effort model for a native outbound: an unported model leaves [OffsiteOutbound.outbound] null (§5.4). */
    private fun parseStandard(protocol: String, tag: String, settings: JsonObject): Outbound? {
        val factoryType = OffsiteProtocols.canonicalStandard(protocol)
        val outbound = OutboundFactory.newByType(factoryType)
        if (outbound.invalid) return null
        val obj = settings.copy()
        if (!obj.contains("type")) obj["type"] = factoryType
        if (obj.string("name").isEmpty()) obj["name"] = tag
        return outbound.takeIf { it.parseFromJson(obj) && !it.invalid }
    }

    private class ParsedRouting(
        val rules: List<OffsiteRule> = emptyList(),
        val balancers: List<OffsiteBalancer> = emptyList(),
        val finalTag: String? = null,
    )

    private fun parseRouting(root: JsonObject, issues: MutableList<OffsiteIssue>): ParsedRouting {
        if (!root.isObject("routing")) {
            issues += error("routing", "must be an object")
            return ParsedRouting()
        }
        val routing = root.obj("routing")
        for (key in routing.keys()) if (key !in ROUTING_FIELDS) issues += warning("routing.$key", "unknown field")
        val domainStrategy = routing.string("domainStrategy")
        if (domainStrategy.isNotEmpty() && domainStrategy != "AsIs") {
            issues += warning("routing.domainStrategy", "not supported in v1; ignored")
        }
        if (routing.string("domainMatcher").isNotEmpty()) {
            issues += warning("routing.domainMatcher", "not supported in v1; ignored")
        }

        val rules = if (routing.contains("rules")) parseRules(routing, issues) else emptyList()
        val balancers = if (routing.contains("balancers")) parseBalancers(routing, issues) else emptyList()
        val finalTag = routing.string("final").takeIf { it.isNotEmpty() }
        return ParsedRouting(rules, balancers, finalTag)
    }

    private fun parseRules(routing: JsonObject, issues: MutableList<OffsiteIssue>): List<OffsiteRule> {
        if (!routing.isArray("rules")) {
            issues += error("routing.rules", "must be an array")
            return emptyList()
        }
        val result = ArrayList<OffsiteRule>()
        for ((index, item) in routing.array("rules").withIndex()) {
            val path = "routing.rules[$index]"
            val rule = item as? JsonObject
            if (rule == null) {
                issues += error(path, "must be an object")
                continue
            }
            val type = rule.string("type")
            if (rule.contains("type") && (!rule.isString("type") || type != "field"))
                issues += error("$path.type", "only \"field\" rules are supported")
            for (key in rule.keys()) {
                if (key == "attrs") issues += warning("$path.attrs", "not supported; ignored")
                else if (key !in RULE_FIELDS) issues += warning("$path.$key", "unknown field")
            }
            result += OffsiteRule(
                raw = rule,
                outboundTag = rule.string("outboundTag").takeIf { it.isNotEmpty() },
                balancerTag = rule.string("balancerTag").takeIf { it.isNotEmpty() },
                inboundTags = stringList(rule, "inboundTag"),
            )
        }
        return result
    }

    private fun parseBalancers(routing: JsonObject, issues: MutableList<OffsiteIssue>): List<OffsiteBalancer> {
        if (!routing.isArray("balancers")) {
            issues += error("routing.balancers", "must be an array")
            return emptyList()
        }
        val result = ArrayList<OffsiteBalancer>()
        for ((index, item) in routing.array("balancers").withIndex()) {
            val path = "routing.balancers[$index]"
            val balancer = item as? JsonObject
            if (balancer == null) {
                issues += error(path, "must be an object")
                continue
            }
            for (key in balancer.keys()) if (key !in BALANCER_FIELDS) issues += warning("$path.$key", "unknown field")

            val tag = balancer.string("tag")
            if (tag.isEmpty()) issues += error("$path.tag", "must be a non-empty string")

            val selectors = stringListOrString(balancer, "selector")
            if (selectors.isEmpty()) issues += error("$path.selector", "must be a non-empty string or array of strings")

            var strategy: String? = null
            var strategySettings = JsonObject()
            if (balancer.contains("strategy")) {
                when (val node = balancer["strategy"]) {
                    is String -> strategy = node.ifEmpty { null }
                    is JsonObject -> {
                        strategy = node.string("type").takeIf { it.isNotEmpty() }
                        strategySettings = node.obj("settings").copy()
                        for (key in node.keys()) if (key !in setOf("type", "settings"))
                            issues += warning("$path.strategy.$key", "unknown field")
                    }
                    else -> issues += warning("$path.strategy", "must be a string or an object")
                }
            }

            result += OffsiteBalancer(
                tag = tag,
                selectors = selectors,
                strategy = strategy,
                settings = strategySettings,
                fallbackTag = balancer.string("fallbackTag").takeIf { it.isNotEmpty() },
            )
        }
        return result
    }

    private fun parseDns(root: JsonObject, issues: MutableList<OffsiteIssue>): OffsiteDns? {
        if (!root.isObject("dns")) {
            issues += warning("dns", "must be an object; ignored")
            return null
        }
        val dns = root.obj("dns")
        for (key in dns.keys()) if (key !in DNS_FIELDS) issues += warning("dns.$key", "unknown field")

        val servers = ArrayList<OffsiteDnsServer>()
        if (dns.contains("servers")) {
            if (!dns.isArray("servers")) {
                issues += warning("dns.servers", "must be an array; ignored")
            } else {
                for ((index, item) in dns.array("servers").withIndex()) {
                    val path = "dns.servers[$index]"
                    when (item) {
                        is String -> {
                            if (item.isNotEmpty()) servers += OffsiteDnsServer(item, null, emptyList(), emptyList())
                            else issues += warning(path, "address is empty")
                        }
                        is JsonObject -> {
                            val address = item.string("address")
                            if (address.isEmpty()) {
                                issues += warning("$path.address", "must be a non-empty string")
                            } else {
                                servers += OffsiteDnsServer(
                                    address = address,
                                    tag = item.string("tag").takeIf { it.isNotEmpty() },
                                    domains = stringList(item, "domains"),
                                    expectIps = stringList(item, "expectIPs"),
                                )
                            }
                        }
                        else -> issues += warning(path, "must be a string or an object")
                    }
                }
            }
        }

        val hasRules = dns.contains("rules")
        if (hasRules) issues += warning("dns.rules", "not supported in v1; ignored")
        val hasHosts = dns.contains("hosts")
        if (hasHosts) issues += warning("dns.hosts", "not supported in v1; ignored")

        return OffsiteDns(
            servers = servers,
            queryStrategy = dns.string("queryStrategy").takeIf { it.isNotEmpty() },
            hasRules = hasRules,
            hasHosts = hasHosts,
        )
    }

    /** A string or an array of strings, blank entries dropped; anything else is empty. */
    private fun stringListOrString(obj: JsonObject, key: String): List<String> {
        if (!obj.contains(key)) return emptyList()
        return when (val value = obj[key]) {
            is String -> listOfNotNull(value.takeIf { it.isNotEmpty() })
            is JsonArray -> value.strings().filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    /** An optional string (`inboundTag`) or array of strings. */
    private fun stringList(obj: JsonObject, key: String): List<String> = stringListOrString(obj, key)

    private fun error(path: String, message: String) =
        OffsiteIssue(OffsiteSeverity.Error, path, message)

    private fun warning(path: String, message: String) =
        OffsiteIssue(OffsiteSeverity.Warning, path, message)
}
