package app.qcoldthrone.mobile.outbound.common

import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.import.ClashProxy
import app.qcoldthrone.mobile.outbound.QtStrings
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.link.LinkParser
import app.qcoldthrone.mobile.outbound.link.ParsedLink

/** xrayMultiplex (include/configs/common/xrayMultiplex.h, src/configs/common/xrayMultiplex.cpp). `useDefault` true means "Keep Default". */
class XrayMultiplex {
    @JvmField var enabled: Boolean = false
    @JvmField var useDefault: Boolean = true
    @JvmField var concurrency: Int = 0
    @JvmField var xudpConcurrency: Int = 16

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayMultiplex.cpp:6-15. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("mux")) {
            enabled = q.value("mux").replace("1", "true") == "true"
            useDefault = false
        }
        if (q.has("mux_concurrency")) concurrency = QtStrings.toInt(q.value("mux_concurrency"))
        if (q.has("mux_xudp_concurrency")) xudpConcurrency = QtStrings.toInt(q.value("mux_xudp_concurrency"))
        return true
    }

    /** xrayMultiplex.cpp:17-23. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) {
            enabled = obj.bool("enabled")
            useDefault = false
        }
        if (obj.contains("concurrency")) concurrency = obj.int("concurrency")
        if (obj.contains("xudpConcurrency")) xudpConcurrency = obj.int("xudpConcurrency")
        return true
    }

    /** xrayMultiplex.cpp:25-32. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val smux = proxy.obj("smux")
        enabled = smux.bool("enabled")
        useDefault = false
        val maxStreams = smux.int("max-streams")
        if (maxStreams > 0) concurrency = maxStreams
        return true
    }

    /** xrayMultiplex.cpp:34-43. */
    fun exportToLink(): List<Pair<String, String>> {
        if (useDefault) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("mux" to if (enabled) "true" else "false")
        if (enabled) {
            if (concurrency > 0) q.add("mux_concurrency" to concurrency.toString())
            if (xudpConcurrency > 0) q.add("mux_xudp_concurrency" to xudpConcurrency.toString())
        }
        return q
    }

    /** xrayMultiplex.cpp:45-54. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (useDefault) return obj
        obj["enabled"] = enabled
        if (!enabled) return obj
        if (concurrency > 0) obj["concurrency"] = concurrency
        if (xudpConcurrency > 0) obj["xudpConcurrency"] = xudpConcurrency
        return obj
    }

    /** xrayMultiplex.cpp:56-63. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (useDefault && ctx.xrayMuxDefaultOn) obj["enabled"] = true
        if (!obj.bool("enabled")) return JsonObject()
        if (ctx.xrayMuxConcurrency > 0 && concurrency <= 0) obj["concurrency"] = ctx.xrayMuxConcurrency
        if (xudpConcurrency > 0) obj["xudpConcurrency"] = xudpConcurrency
        return obj
    }
}
