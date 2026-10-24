package app.qcoldthrone.mobile.outbound.offsite

import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.json.JsonObject

/** Offsite JSON config model (§5 of docs/offsite-json-config.md). */

/** Issue severity of the parser/validator (§5.1, §9). */
enum class OffsiteSeverity { Error, Warning }

/**
 * One diagnostic with a stable document path, for example `outbounds[2].settings.password` (§9, §8).
 * Messages never carry secret values (§11).
 */
data class OffsiteIssue(
    @JvmField val severity: OffsiteSeverity,
    @JvmField val path: String,
    @JvmField val message: String,
)

/**
 * One entry of `outbounds[]` (§5.2, §5.4): the raw `settings`, the parsed profile when the type is
 * available, and whether the type is a carrier-backed offsite protocol (§5.3).
 */
data class OffsiteOutbound(
    @JvmField val tag: String,
    @JvmField val protocol: String,
    @JvmField val settings: JsonObject,
    @JvmField val outbound: Outbound?,
    @JvmField val isOffsite: Boolean,
)

/** One `routing.rules[]` entry (§5.5); [raw] keeps every field for the translator. */
data class OffsiteRule(
    @JvmField val raw: JsonObject,
    @JvmField val outboundTag: String?,
    @JvmField val balancerTag: String?,
    @JvmField val inboundTags: List<String>,
)

/** One `routing.balancers[]` entry (§5.6). [strategy] is the Xray strategy type, null when absent. */
data class OffsiteBalancer(
    @JvmField val tag: String,
    @JvmField val selectors: List<String>,
    @JvmField val strategy: String?,
    @JvmField val settings: JsonObject,
    @JvmField val fallbackTag: String?,
)

/** One `dns.servers[]` entry, normalized from either the string or the object form (§5.7). */
data class OffsiteDnsServer(
    @JvmField val address: String,
    @JvmField val tag: String?,
    @JvmField val domains: List<String>,
    @JvmField val expectIps: List<String>,
)

/** The `dns` section best-effort model (§5.7); rules and hosts are only flagged, not modeled. */
data class OffsiteDns(
    @JvmField val servers: List<OffsiteDnsServer>,
    @JvmField val queryStrategy: String?,
    @JvmField val hasRules: Boolean,
    @JvmField val hasHosts: Boolean,
)

/** A parsed document (§5.1); [issues] carries the parser diagnostics for the whole document. */
data class OffsiteDocument(
    @JvmField val name: String,
    @JvmField val outbounds: List<OffsiteOutbound>,
    @JvmField val rules: List<OffsiteRule>,
    @JvmField val balancers: List<OffsiteBalancer>,
    @JvmField val finalTag: String?,
    @JvmField val dns: OffsiteDns?,
    @JvmField val log: JsonObject,
    @JvmField val issues: List<OffsiteIssue>,
)

/**
 * Parser output (§9): [document] is null only when the text is not a JSON object or `outbounds` is
 * missing/not an array. [ok] is false when any issue is an Error.
 */
data class OffsiteParseResult(
    @JvmField val document: OffsiteDocument?,
    @JvmField val issues: List<OffsiteIssue>,
) {
    val ok: Boolean get() = issues.none { it.severity == OffsiteSeverity.Error }
    val errors: List<OffsiteIssue> get() = issues.filter { it.severity == OffsiteSeverity.Error }
    val warnings: List<OffsiteIssue> get() = issues.filter { it.severity == OffsiteSeverity.Warning }
}
