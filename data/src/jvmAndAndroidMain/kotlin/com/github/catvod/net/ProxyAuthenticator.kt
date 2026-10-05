package com.github.catvod.net

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.Objects

/** TVBox 壳代理认证器 (签名对齐 FongMi catvod 模块的 net.ProxyAuthenticator)。 */
class ProxyAuthenticator(private val selector: OkProxySelector) : Authenticator() {

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val userInfo = findUserInfo(requestingHost)
        if (userInfo == null || !userInfo.contains(":")) return null
        val index = userInfo.indexOf(':')
        return PasswordAuthentication(userInfo.substring(0, index), userInfo.substring(index + 1).toCharArray())
    }

    private fun findUserInfo(proxyHost: String?): String? =
        selector.getProxy().map { it.getUserInfo(proxyHost ?: "") }.filter(Objects::nonNull).firstOrNull()
}
