@file:Suppress("UnusedReceiverParameter")

package io.legado.app.utils

import android.webkit.CookieManager

fun CookieManager.removeCookie(url: String) {
    val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
    val host = NetworkUtils.getSubDomain(url)
    val cookieGlob = getCookie(baseUrl) ?: return

    val directives = listOf(
        // 1. 普通 Cookie
        "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
        // 2. 跨站高安全 Cookie
        "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None",
        // 3. 针对 Cloudflare/Chrome 的 CHIPS 独立分区 Cookie
        "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; SameSite=None; Partitioned"
    )

    cookieGlob.split(";").forEach { entry ->
        if (entry.isBlank()) return@forEach
        val name = entry.substringBefore("=").trim()
        directives.forEach { directive ->
            setCookie(baseUrl, "$name=; $directive; Domain=.$host")
            setCookie(baseUrl, "$name=; $directive")
        }
    }
}