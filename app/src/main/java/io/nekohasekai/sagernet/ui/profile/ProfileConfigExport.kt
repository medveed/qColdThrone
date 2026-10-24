package app.qcoldthrone.mobile.ui.profile

import app.qcoldthrone.mobile.bg.proto.CoreConfigs
import app.qcoldthrone.mobile.database.ProxyEntity
import app.qcoldthrone.mobile.ktx.toStringPretty
import org.json.JSONObject

/** "Export configuration" of the profile share menu: the core config the profile would start with, as pretty JSON. */
object ProfileConfigExport {

    /** The config text and a file name for it. */
    fun export(entity: ProxyEntity): Pair<String, String> {
        val result = CoreConfigs.buildMain(entity)

        val text = StringBuilder(JSONObject(result.coreConfig).toStringPretty())
        val xray = result.xrayConfig
        if (!xray.isNullOrBlank()) {
            text.append("\n\n// xray\n").append(JSONObject(xray).toStringPretty())
        }
        val name = if (xray.isNullOrBlank()) "${entity.displayName()}.json" else "${entity.displayName()}.txt"
        return text.toString() to name
    }
}
