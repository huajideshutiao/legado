package com.github.catvod.net

import com.github.catvod.bean.Proxy
import com.github.catvod.utils.Util
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** TVBox 壳代理认证器 (签名对齐 FongMi catvod 模块的 net.OkAuthenticator)。 */
class OkAuthenticator(private val selector: OkProxySelector) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (route == null || response.request.header("Proxy-Authorization") != null) return null
        val proxyAddress = route.proxy.address() as? InetSocketAddress ?: return null
        val policy = response.request.tag(OkProxySelector.Policy::class.java)
        val userInfo = if (policy == null) {
            findUserInfo(response.request.url.host, proxyAddress.hostName)
        } else {
            val rule = policy.rule
            rule?.getUserInfo(proxyAddress.hostName, "http")
        }
        if (userInfo == null) return null
        val separator = userInfo.indexOf(':')
        val username = if (separator == -1) userInfo else userInfo.substring(0, separator)
        val password = if (separator == -1) "" else userInfo.substring(separator + 1)
        return response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(username, password, StandardCharsets.UTF_8))
            .build()
    }

    private fun findUserInfo(requestHost: String, proxyHost: String): String? =
        selector.getProxy()
            .filter { matchesHost(it, requestHost) }
            .firstNotNullOfOrNull { it.getUserInfo(proxyHost, "http") }

    private fun matchesHost(item: Proxy, requestHost: String): Boolean =
        item.getHosts().any { host -> Util.containOrMatch(requestHost, host) }
}
