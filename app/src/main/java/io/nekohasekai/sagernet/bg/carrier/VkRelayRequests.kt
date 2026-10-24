package app.qcoldthrone.mobile.bg.carrier

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** qWDTT's request format, independently testable without an Android account. */
internal object VkRelayRequests {
    val cookieDomains=listOf("https://vk.com","https://vk.ru","https://m.vk.com","https://m.vk.ru",
        "https://login.vk.com","https://login.vk.ru","https://oauth.vk.com","https://oauth.vk.ru","https://id.vk.com","https://id.vk.ru")
    fun cookieHeader(cookies: Map<String,String>) = cookieDomains.flatMap { host ->
        cookies[host].orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }.distinct().joinToString("; ")
    fun startCall(token: String): Request = Request.Builder().url(
        "https://api.vk.ru/method/calls.start".toHttpUrl().newBuilder()
            .addQueryParameter("access_token",token).addQueryParameter("v","5.199").build()
    ).get().build()
}
