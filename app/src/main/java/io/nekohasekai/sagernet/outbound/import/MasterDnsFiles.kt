package app.qcoldthrone.mobile.outbound.import

import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.json.jsonObjectOf

/** MasterDnsVPN's own client configuration and WhiteDNS's profile file, as the desktop reads them (SubscriptionParser.cpp). */
object MasterDnsFiles {
    private fun profile(domains: String, key: String, method: Int, label: String): JsonObject? {
        val list = domains.split(Regex("""[,\s"\[\]]+""")).filter { it.isNotEmpty() }
        if (list.isEmpty() || key.isBlank()) return null
        return jsonObjectOf("type" to "masterdns", "name" to label, "domain" to list.joinToString(","),
            "mkey" to key.trim(), "encryption" to method)
    }

    /** client_config.toml: KEY = value lines, comments after '#'. */
    fun fromToml(text: String): JsonObject? {
        val values = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            val equals = line.indexOf('=')
            if (equals > 0) values[line.substring(0, equals).trim().uppercase()] = line.substring(equals + 1).trim()
        }
        val domains = values["DOMAINS"] ?: return null
        var key = values["ENCRYPTION_KEY"] ?: return null
        if (key.length >= 2 && (key.startsWith('"') || key.startsWith('\'')) && key.last() == key.first()) key = key.substring(1, key.length - 1)
        return profile(domains, key, values["DATA_ENCRYPTION_METHOD"]?.toIntOrNull() ?: 1, "")
    }

    /** WhiteDNS's profile file (what a masterdns:// link carries) or MasterDnsVPN's JSON config. */
    fun fromJson(doc: JsonObject): JsonObject? {
        if (doc.string("schema") == "whitedns.profile") {
            val profile = doc.obj("profile")
            val server = profile.obj("server")
            return profile(server.string("domain"), server.string("encryption_key"),
                if (server.contains("encryption_method")) server.int("encryption_method") else 1, profile.string("name"))
        }
        if (doc.contains("DOMAINS") && doc.contains("ENCRYPTION_KEY")) {
            val domains = if (doc.isArray("DOMAINS")) doc.array("DOMAINS").strings() else listOf(doc.string("DOMAINS"))
            return profile(domains.joinToString(","), doc.string("ENCRYPTION_KEY"),
                if (doc.contains("DATA_ENCRYPTION_METHOD")) doc.int("DATA_ENCRYPTION_METHOD") else 1, "")
        }
        return null
    }
}
