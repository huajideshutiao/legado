// android.net.Uri JVM stub: 供扩展 dex 链接 Uri 引用, parse 返回空壳实例, 取值方法良性返回 null。
package android.net

class Uri {

    fun getPath(): String? = null

    fun getQueryParameter(key: String?): String? = null

    companion object {

        @JvmStatic
        fun parse(s: String?): Uri = Uri()
    }
}
