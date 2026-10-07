// android.webkit.CookieManager JVM 实现: 委托宿主的 cookie 存储 (CookieStoreProvider, 桌面端为
// SharedCookieStore/Room 持久化), 让扩展插件栈 (AndroidCookieJar) 与业务层 cookie、WebView 登录态
// 共用同一份数据; 空实现会让需登录或带 CF 挑战的插件源在桌面静默取不到数据。
package android.webkit

import io.legado.app.help.http.CookieStoreProviders

class CookieManager private constructor() {

    fun getCookie(url: String?): String? =
        url?.takeIf { it.isNotBlank() }
            ?.let { CookieStoreProviders.get()?.getCookie(it) }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 合并语义 (同名 key 覆盖, 与 Android WebKit 一致)。
     * `Max-Age<=0` 按删除处理: AndroidCookieJar.remove 就是用过期 cookie 串清旧 cookie 的。
     */
    fun setCookie(url: String?, value: String?) {
        val store = CookieStoreProviders.get() ?: return
        if (url.isNullOrBlank() || value.isNullOrBlank()) return
        val maxAge = value.substringAfter("Max-Age=", "")
            .substringBefore(';')
            .trim()
            .toLongOrNull()
        if (maxAge != null && maxAge <= 0) {
            store.removeCookie(url, value.substringBefore('=').trim())
        } else {
            store.replaceCookie(url, value)
        }
    }

    /** 对齐 android.webkit.CookieManager 的真实签名: setCookie/flush 无返回值 (API 21+)。 */
    fun setCookie(url: String?, value: String?, callback: ((Boolean) -> Unit)?) {
        setCookie(url, value)
        callback?.invoke(true)
    }

    fun flush() {
    }

    fun removeAllCookies(callback: ((Boolean) -> Unit)?) {
        CookieStoreProviders.get()?.clear()
        callback?.invoke(true)
    }

    companion object {

        @JvmStatic
        fun getInstance(): CookieManager = CookieManager()
    }
}
