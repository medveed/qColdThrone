package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.json.JsonValues

/**
 * Semantic validation of a parsed document (docs/offsite-json-config.md §9).
 *
 * The parser issues are included so one call gives the complete diagnostic set; the checks below add
 * cross-reference and limit checks that the parser cannot make.
 */
object OffsiteConfigValidator {

    /** §9.7: document size limit (1 MiB). */
    const val MAX_DOCUMENT_BYTES: Int = 1 shl 20

    /** §9.7: outbound count limit. */
    const val MAX_OUTBOUNDS: Int = 256

    /** §9.7: routing rule count limit. */
    const val MAX_RULES: Int = 2048

    /** §5.6: the strategy types the translator can map. */
    @JvmField
    val STRATEGIES: Set<String> = setOf("random", "leastPing", "leastLoad")

    /** Returns every error and warning for [document]; an empty list means the document is valid. */
    @JvmStatic
    @JvmOverloads
    fun validate(document: OffsiteDocument, sourceLength: Int = 0): List<OffsiteIssue> {
        val issues = ArrayList<OffsiteIssue>()
        val seen = HashSet<String>()
        fun add(issue: OffsiteIssue) {
            if (seen.add("${issue.severity}|${issue.path}|${issue.message}")) issues += issue
        }
        fun validatePorts(raw: JsonObject, key: String, path: String) {
            val values = when {
                !raw.contains(key) -> return
                raw.isArray(key) -> raw.array(key).map { JsonValues.variantToString(it) }
                else -> listOf(JsonValues.variantToString(raw[key]))
            }
            for (value in values) for (token in value.split(',')) {
                val item = token.trim()
                if (item.isEmpty()) continue
                val dash = item.indexOf('-')
                val parts = if (dash > 0) listOf(item.substring(0, dash), item.substring(dash + 1)) else listOf(item)
                if (parts.any { it.toIntOrNull()?.let { n -> n !in 1..65535 } ?: true }) {
                    add(error(path, "invalid port range: $item"))
                }
            }
        }
        fun validateAddresses(raw: JsonObject, key: String, path: String) {
            val values = when {
                !raw.contains(key) -> return
                raw.isArray(key) -> raw.array(key).map { JsonValues.variantToString(it) }
                else -> listOf(JsonValues.variantToString(raw[key]))
            }
            for (value in values) {
                val item = value.trim()
                if (item.isEmpty() || item.startsWith("geoip:")) continue
                if (!CIDR.matches(item) && !ADDRESS.matches(item)) add(error(path, "not an IP or CIDR: $item"))
            }
        }
        document.issues.forEach(::add)

        if (sourceLength > MAX_DOCUMENT_BYTES)
            add(error("", "document is larger than 1 MiB"))
        if (document.outbounds.size > MAX_OUTBOUNDS)
            add(error("outbounds", "more than $MAX_OUTBOUNDS outbounds"))
        if (document.rules.size > MAX_RULES)
            add(error("routing.rules", "more than $MAX_RULES rules"))

        val outboundTags = LinkedHashSet<String>()
        for ((index, outbound) in document.outbounds.withIndex()) {
            when {
                outbound.tag.isEmpty() -> add(error("outbounds[$index].tag", "must be a non-empty string"))
                !outboundTags.add(outbound.tag) -> add(error("outbounds[$index].tag", "duplicate tag: ${outbound.tag}"))
            }
        }

        val balancerTags = HashSet<String>()
        val allBalancerTags = document.balancers.map { it.tag }.filter { it.isNotEmpty() }.toSet()
        for ((index, balancer) in document.balancers.withIndex()) {
            val path = "routing.balancers[$index]"
            if (balancer.tag.isEmpty()) {
                add(error("$path.tag", "must be a non-empty string"))
            } else {
                if (!balancerTags.add(balancer.tag))
                    add(error("$path.tag", "duplicate balancer tag: ${balancer.tag}"))
                if (balancer.tag in outboundTags)
                    add(error("$path.tag", "balancer tag collides with an outbound tag: ${balancer.tag}"))
            }

            if (balancer.selectors.isEmpty()) {
                add(error("$path.selector", "must be a non-empty string or array of strings"))
            } else {
                val matches = outboundTags.filter { tag -> balancer.selectors.any { tag.startsWith(it) } }
                if (matches.isEmpty())
                    add(error("$path.selector", "selector does not match any outbound tag"))
                if (balancer.tag.isNotEmpty() && balancer.selectors.any { balancer.tag.startsWith(it) })
                    add(error("$path.selector", "balancer selects itself"))
            }

            val strategy = balancer.strategy
            if (strategy != null && strategy !in STRATEGIES)
                add(error("$path.strategy.type", "unknown strategy: $strategy"))

            balancer.fallbackTag?.let { fallback ->
                if (balancer.tag.isNotEmpty() && fallback == balancer.tag) {
                    add(error("$path.fallbackTag", "balancer cannot fall back to itself"))
                } else if (fallback !in outboundTags && fallback !in allBalancerTags) {
                    add(error("$path.fallbackTag", "unknown fallback tag: $fallback"))
                }
            }
        }

        for ((index, rule) in document.rules.withIndex()) {
            val path = "routing.rules[$index]"
            rule.outboundTag?.let { tag ->
                if (tag !in outboundTags) add(error("$path.outboundTag", "unknown outbound tag: $tag"))
            }
            rule.balancerTag?.let { tag ->
                if (tag !in balancerTags) {
                    val message = if (tag in outboundTags) "not a balancer: $tag" else "unknown balancer tag: $tag"
                    add(error("$path.balancerTag", message))
                }
            }
            if (rule.raw.contains("attrs")) add(warning("$path.attrs", "not supported; ignored"))
            validatePorts(rule.raw, "port", "$path.port")
            validatePorts(rule.raw, "sourcePort", "$path.sourcePort")
            validateAddresses(rule.raw, "ip", "$path.ip")
            validateAddresses(rule.raw, "source", "$path.source")
        }

        document.finalTag?.let { tag ->
            if (tag !in outboundTags && tag !in balancerTags)
                add(error("routing.final", "unknown tag: $tag"))
        }

        document.dns?.let { dns ->
            if (dns.hasRules) add(warning("dns.rules", "not supported in v1; ignored"))
            if (dns.hasHosts) add(warning("dns.hosts", "not supported in v1; ignored"))
            val dnsTags = HashSet<String>()
            dns.servers.forEachIndexed { dnsIndex, server ->
                val tag = server.tag?.takeIf { it.isNotEmpty() } ?: "dns-$dnsIndex"
                if (!dnsTags.add(tag)) add(error("dns.servers[$dnsIndex].tag", "duplicate DNS server tag: $tag"))
            }
            if (dns.servers.isEmpty()) add(warning("dns", "no usable DNS server; the app DNS configuration is used"))
        }

        return issues
    }

    /** A CIDR literal (`10.0.0.0/8`, `2001:db8::/32`). */
    private val CIDR = Regex("^[0-9a-fA-F:.]+/\\d{1,3}$")

    /** A bare IPv4/IPv6 address. */
    private val ADDRESS = Regex("^[0-9a-fA-F:.]+$")

    private fun error(path: String, message: String) =
        OffsiteIssue(OffsiteSeverity.Error, path, message)

    private fun warning(path: String, message: String) =
        OffsiteIssue(OffsiteSeverity.Warning, path, message)
}
