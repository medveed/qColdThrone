package app.qcoldthrone.mobile.outbound.types

import app.qcoldthrone.mobile.outbound.BuildContext
import app.qcoldthrone.mobile.outbound.import.ClashProxy
import app.qcoldthrone.mobile.outbound.BuildResult
import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.QtStrings
import app.qcoldthrone.mobile.outbound.json.JsonObject
import app.qcoldthrone.mobile.outbound.link.Base64Strict
import app.qcoldthrone.mobile.outbound.link.LinkBuilder
import app.qcoldthrone.mobile.outbound.link.LinkParser

/** socks (include/configs/outbounds/socks.h, src/configs/outbounds/socks.cpp). */
class Socks : Outbound("socks") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var version: Int = 5
    @JvmField var uot: Boolean = false

    /** socks.cpp:9-44. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("version")) {
            version = QtStrings.toInt(q.value("version"))
        } else {
            if (url.scheme == "socks4") version = 4
            if (url.scheme == "socks5") version = 5
        }
        if (url.password.isNotEmpty() || url.userName.isNotEmpty()) {
            username = url.userName
            password = url.password
            // v2rayN base64-encodes "user:pass" into the username
            if (password.isEmpty() && username.isNotEmpty()) {
                val decoded = Base64Strict.decodeToString(username)
                if (decoded.isNotEmpty()) {
                    username = QtStrings.substrBefore(decoded, ":")
                    password = QtStrings.substrAfter(decoded, ":")
                }
            }
        }
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        if (serverPort == 0) serverPort = 1080
        return server.isNotEmpty()
    }

    /** socks.cpp:57-65. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "socks5") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        return true
    }

    /** socks.cpp:46-55. `version` is read with QJsonValue::toInt(), which yields 0 for the "4" string ExportToJson writes. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "socks") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("version")) version = obj.int("version")
        if (obj.contains("uot")) uot = obj.bool("uot")
        return true
    }

    /** socks.cpp:67-89. */
    override fun exportToLink(): String {
        val url = LinkBuilder(if (version == 4) "socks4" else "socks5")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** socks.cpp:91-101. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = "4"
        if (uot) obj["uot"] = uot
        return obj
    }

    /** socks.cpp:103-113. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = "4"
        if (uot) obj["uot"] = uot
        return BuildResult(obj)
    }

    /** socks.cpp:115-118. */
    override fun displayType(): String = "Socks"
}
