package com.github.catvod.bean

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections

/**
 * TVBox 壳代理规则模型: FQCN / 字段 / 方法签名逐字对齐 FongMi catvod 模块的 bean.Proxy
 * (被 net.OkProxySelector / net.OkAuthenticator / net.ProxyAuthenticator 引用)。
 *
 * 与 FongMi 的差异均为纯 API 级别, 语义等价, 且公开方法签名不变:
 * - FongMi 用 Stream.toList() (API 34), 等价改写为 Collectors.toList() 语义 (minSdk 24);
 * - android.net.Uri 解析改用 java.net.URI (Uri 仅出现在私有实现面, 不参与 jar 契约;
 *   getScheme/getHost/getPort/getUserInfo 语义一一对应)。
 */
class Proxy : Comparable<Proxy> {

    @field:SerializedName("name")
    private var name: String? = null

    @field:SerializedName("hosts")
    private var hosts: List<String>? = null

    @field:SerializedName("urls")
    private var urls: List<String>? = null

    private var proxies: List<java.net.Proxy>? = null
    private var uris: List<URI>? = null
    private var wildcard = false

    fun init() {
        wildcard = hosts.orEmpty().any { it.contains("*") }
        uris = urls.orEmpty().map { parseUri(it) }.filter { isValid(it) }
        proxies = uris.orEmpty().mapNotNull { create(it) }
    }

    /** 对齐 android.net.Uri.parse 的宽容面: 非法串返回无 scheme/host/port 的空 URI 而非抛出。 */
    private fun parseUri(spec: String): URI = try {
        URI.create(spec.trim())
    } catch (e: Exception) {
        URI.create("")
    }

    fun getName(): String = if (name.isNullOrEmpty()) "" else name!!

    fun getHosts(): List<String> = hosts ?: Collections.emptyList()

    fun getUrls(): List<String> = urls ?: Collections.emptyList()

    fun getProxies(): List<java.net.Proxy> = proxies ?: Collections.emptyList()

    fun getUserInfo(host: String): String? = getUserInfo(host, "socks")

    fun getUserInfo(host: String, scheme: String): String? =
        uris.orEmpty()
            .filter { isScheme(it, scheme) && host.equals(it.host, ignoreCase = true) }
            .mapNotNull { it.userInfo }
            .firstOrNull()

    private fun isValid(uri: URI): Boolean =
        uri.scheme != null && uri.host != null && uri.port > 0

    private fun create(uri: URI): java.net.Proxy? {
        val address = InetSocketAddress.createUnresolved(uri.host, uri.port)
        if (isScheme(uri, "http")) return java.net.Proxy(java.net.Proxy.Type.HTTP, address)
        if (isScheme(uri, "socks")) return java.net.Proxy(java.net.Proxy.Type.SOCKS, address)
        return null
    }

    private fun isScheme(uri: URI, scheme: String): Boolean =
        uri.scheme?.startsWith(scheme) == true

    override fun compareTo(other: Proxy): Int = wildcard.compareTo(other.wildcard)

    companion object {

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Proxy> = try {
            val listType: Type = TypeToken.getParameterized(List::class.java, Proxy::class.java).type
            val items = Gson().fromJson<List<Proxy>>(element, listType)
            items ?: Collections.emptyList()
        } catch (e: Exception) {
            Collections.emptyList()
        }
    }
}
