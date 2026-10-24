package app.qcoldthrone.mobile.outbound.types

import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.BuildResult
import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.offsite.OffsiteConfigParser
import app.qcoldthrone.mobile.outbound.offsite.OffsiteParseResult
import app.qcoldthrone.mobile.outbound.offsite.OffsiteProtocols

/**
 * The "Offsite JSON Config" profile (docs/offsite-json-config.md §5, §7): one Xray-compatible document
 * stored as text, like [Custom]'s full-config subtype. The generator expands it into the core config;
 * this model only stores and describes it.
 */
class OffsiteConfig : Outbound("offsiteconfig") {
    @JvmField var config: String = ""

    /** Parse the stored document (§5); the editor and the generator share this entry point. */
    fun parse(): OffsiteParseResult = OffsiteConfigParser.parse(config)

    /** §7: tolerant on load; the document is validated by [parse], not here. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        if (obj.contains("config")) config = obj.string("config")
        return true
    }

    /** §7 storage: `{name?, type, config}`, modeled on [Custom]. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (name.isNotEmpty()) obj["name"] = name
        obj["type"] = "offsiteconfig"
        obj["config"] = config
        return obj
    }

    /** §7: identity without the display name (the profile dedup key). */
    override fun exportIdentity(): JsonObject = exportToJson().also { it.remove("name") }

    /** §8: the first egress tag, or the outbound count when only infrastructure is declared. */
    override fun displayAddress(): String {
        val document = parse().document ?: return ""
        val egress = document.outbounds.firstOrNull { !OffsiteProtocols.isInfra(it.protocol) }
        egress?.tag?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (document.outbounds.isEmpty()) "" else "<${document.outbounds.size}> outbounds"
    }

    override fun displayType(): String = "Offsite Config"

    /** §12: the profile is a whole config, not an Xray outbound. */
    override fun isXray(): Boolean = false

    /** §12: it cannot participate in chains as a hop. */
    override fun isEndpoint(): Boolean = false

    /** §6: the generator replaces every offsite outbound with a socks bridge. */
    override fun build(ctx: BuildContext): BuildResult =
        BuildResult(JsonObject(), "Offsite config is expanded by the generator")

    /** §16: sharing/export is absent in v1. */
    override fun exportToLink(): String = ""
}
