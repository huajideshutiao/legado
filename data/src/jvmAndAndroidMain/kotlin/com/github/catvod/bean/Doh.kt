package com.github.catvod.bean

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.InetAddress
import java.util.Collections

/**
 * DoH (DNS over HTTPS) 配置模型, FQCN 与字段/方法签名逐字对齐 FongMi catvod 模块的 bean.Doh
 * (jar 以 compileOnly 方式按该 FQCN 链接, 见 net.OkDns.setDoh)。
 *
 * 与 FongMi 的唯一差异: FongMi 的 Doh.get(Context) 读的是它自己 catvod 模块资源里的
 * R.array.doh_name / R.array.doh_url; 本宿主没有 catvod 资源模块, 因此 get(Context)
 * 改为返回空列表 —— 清单由用户配置 (VodConfig.doh) 经 arrayFrom/objectFrom 传入,
 * 这两个入口逐字照搬 FongMi, 是 OkDns 实际用到的面。
 */
class Doh {

    @field:SerializedName("name")
    private var name: String? = null

    @field:SerializedName("url")
    private var url: String? = null

    @field:SerializedName("ips")
    private var ips: List<String>? = null

    /** FongMi 从 catvod 模块资源数组读取内置 DoH 清单; 本宿主无该资源模块, 返回空列表。 */
    fun name(name: String?): Doh = apply { this.name = name }

    fun url(url: String?): Doh = apply { this.url = url }

    fun getName(): String = name ?: ""

    fun getUrl(): String = url ?: ""

    fun getIps(): List<String> = ips ?: Collections.emptyList()

    fun getHosts(): List<InetAddress>? = try {
        val list = ArrayList<InetAddress>()
        for (ip in getIps()) list.add(InetAddress.getByName(ip))
        if (list.isEmpty()) null else list
    } catch (ignored: Exception) {
        null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Doh) return false
        return getUrl() == other.getUrl()
    }

    override fun hashCode(): Int = getUrl().hashCode()

    override fun toString(): String = Gson().toJson(this)

    companion object {

        /** 对齐 FongMi Doh.get(Context) 静态签名; 本宿主无内置清单, 返回空列表。 */
        @JvmStatic
        fun get(context: android.content.Context?): List<Doh> = ArrayList()

        @JvmStatic
        fun objectFrom(str: String?): Doh {
            val item = try {
                Gson().fromJson(str, Doh::class.java)
            } catch (e: Exception) {
                null
            }
            return item ?: Doh()
        }

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Doh> = try {
            val listType: Type = TypeToken.getParameterized(List::class.java, Doh::class.java).type
            val items = Gson().fromJson<List<Doh>>(element, listType)
            items ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
    }
}
