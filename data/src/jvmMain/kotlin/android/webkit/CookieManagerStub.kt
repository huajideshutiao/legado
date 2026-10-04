// android.webkit.CookieManager JVM stub: cookie 读写为良性空实现 (getCookie 恒 null), 仅供扩展 dex 链接。
package android.webkit

class CookieManager private constructor() {

    fun getCookie(url: String?): String? = null

    fun setCookie(url: String?, value: String?): Boolean = true

    fun removeAllCookies(callback: ((Boolean) -> Unit)?) {
    }

    companion object {

        @JvmStatic
        fun getInstance(): CookieManager = CookieManager()
    }
}
