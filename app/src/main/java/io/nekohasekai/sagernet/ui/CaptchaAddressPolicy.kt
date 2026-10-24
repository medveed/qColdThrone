package app.qcoldthrone.mobile.ui

import java.net.URI
import java.util.Locale

internal object CaptchaAddressPolicy {
    fun allowed(value: String): Boolean {
        val uri=runCatching { URI(value) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https",ignoreCase=true) && uri.rawUserInfo==null && uri.port in listOf(-1,443) &&
            listOf("vk.com","vk.ru","ok.ru","okcdn.ru").any { host==it || host.endsWith("."+it) }
    }
}
