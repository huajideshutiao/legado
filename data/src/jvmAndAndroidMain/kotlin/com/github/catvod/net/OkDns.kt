package com.github.catvod.net

import com.github.catvod.bean.Doh
import com.github.catvod.utils.Util
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * TVBox 壳 DNS 解析器: FQCN / 构造器 / 全部 public 方法签名逐字对齐 FongMi catvod 模块的
 * net.OkDns (jar 经 Spider.safeDns() -> OkHttp.dns() 拿到它, 再调 addAll/setDoh/clear)。
 *
 * 与 FongMi 的已知差异: lookup() 的 DoH 传输退化为系统 DNS (宿主无 okhttp-dnsoverhttps
 * 依赖); hosts 覆盖表 (addAll) 完全生效, setDoh 仅记录已配置项不改变解析路径。
 */
class OkDns : Dns {

    private val map = ConcurrentHashMap<String, String>()
    private var supplier: Supplier<Doh>? = null
    private var doh: Doh? = null

    @Synchronized
    fun setDoh(item: Doh) {
        val url = item.getUrl().toHttpUrlOrNull()
        doh = if (url == null) null else item
        supplier = null
    }

    @Synchronized
    fun setDoh(supplier: Supplier<Doh>?) {
        this.supplier = supplier
    }

    fun clear() {
        map.clear()
    }

    fun addAll(hosts: List<String?>) {
        map.putAll(
            hosts.filterNotNull()
                .map { it.split("=", limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0].trim() to it[1].trim() },
        )
    }

    private fun get(hostname: String): String {
        val target = map[hostname]
        if (target != null) return target
        for ((key, value) in map) if (Util.containOrMatch(hostname, key)) return value
        return hostname
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val supplier = this.supplier
        if (supplier != null) initDoh(supplier)
        return Dns.SYSTEM.lookup(get(hostname))
    }

    @Synchronized
    private fun initDoh(supplier: Supplier<Doh>) {
        if (supplier !== this.supplier) return
        setDoh(supplier.get())
    }
}
