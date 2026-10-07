package com.github.catvod.net

import com.github.catvod.bean.Proxy
import com.github.catvod.utils.Util
import java.io.IOException
import java.net.Authenticator
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.Objects
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TVBox 壳代理选择器: FQCN / 方法签名逐字对齐 FongMi catvod 模块的 net.OkProxySelector
 * (被 OkHttp.selector() 引用并挂到 OkHttpClient.Builder.proxySelector)。
 *
 * 与 FongMi 的差异仅 API 级别改写 (语义等价): List.of/List.copyOf 改为
 * Collections.singletonList / Collections.unmodifiableList (minSdk 24 无 desugaring)。
 */
open class OkProxySelector : ProxySelector() {

    private val proxyList = CopyOnWriteArrayList<Proxy>()

    private val system: ProxySelector? = ProxySelector.getDefault()

    @Volatile
    private var generation: Int = 0

    init {
        Authenticator.setDefault(ProxyAuthenticator(this))
    }

    /** 对齐原 getProxy() 公开面 (OkAuthenticator/ProxyAuthenticator 经它取规则表)。 */
    fun getProxy(): List<Proxy> = proxyList

    @Synchronized
    fun addAll(items: List<Proxy>) {
        if (items.isEmpty()) return
        items.forEach { it.init() }
        proxyList.addAll(items)
        java.util.Collections.sort(proxyList)
    }

    @Synchronized
    fun clear() {
        Authenticator.setDefault(null)
        generation++
        proxyList.clear()
    }

    private fun fallback(uri: URI): List<java.net.Proxy> =
        system?.select(uri) ?: Collections.singletonList(java.net.Proxy.NO_PROXY)

    override fun select(uri: URI?): List<java.net.Proxy> {
        if (proxyList.isEmpty() || uri?.host == null || "127.0.0.1" == uri.host) return fallback(uri!!)
        val item = find(uri)
        if (item != null && item.getProxies().isNotEmpty()) return item.getProxies()
        return fallback(uri)
    }

    internal fun policy(uri: URI, previous: Policy?): Policy {
        var item = find(uri)
        if (item == null && previous != null && previous.rule != null &&
            previous.generation == generation && proxyList.contains(previous.rule) &&
            previous.rule.getProxies().isNotEmpty() && uri.host != null && "127.0.0.1" != uri.host
        ) {
            item = previous.rule
        }
        val proxies = if (item != null && item.getProxies().isNotEmpty()) item.getProxies() else fallback(uri)
        return Policy(uri, item, proxies, system, generation)
    }

    internal fun find(uri: URI?): Proxy? {
        if (uri == null || uri.host == null || "127.0.0.1" == uri.host) return null
        for (item in proxyList) for (host in item.getHosts()) if (Util.containOrMatch(uri.host, host)) return item
        return null
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        system?.connectFailed(uri, sa, ioe)
    }

    internal class Policy(
        uri: URI,
        val rule: Proxy?,
        proxies: List<java.net.Proxy>,
        val system: ProxySelector?,
        val generation: Int,
    ) : ProxySelector() {

        private val scheme: String = uri.scheme!!.lowercase(Locale.ROOT)
        private val host: String = uri.host!!.lowercase(Locale.ROOT)
        private val port: Int = getPort(uri)
        private val proxies: List<java.net.Proxy> = Collections.unmodifiableList(ArrayList(proxies))

        fun sharesProxyCredentials(other: Policy?): Boolean =
            other != null && rule === other.rule && system === other.system &&
                generation == other.generation && proxies == other.proxies

        override fun select(uri: URI?): List<java.net.Proxy> = proxies

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            system?.connectFailed(uri, sa, ioe)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Policy) return false
            return rule === other.rule && system === other.system && generation == other.generation &&
                port == other.port && scheme == other.scheme && host == other.host && proxies == other.proxies
        }

        override fun hashCode(): Int = Objects.hash(
            System.identityHashCode(rule), System.identityHashCode(system),
            generation, scheme, host, port, proxies,
        )

        companion object {
            private fun getPort(uri: URI): Int {
                if (uri.port != -1) return uri.port
                if ("http".equals(uri.scheme, ignoreCase = true)) return 80
                if ("https".equals(uri.scheme, ignoreCase = true)) return 443
                return -1
            }
        }
    }
}
