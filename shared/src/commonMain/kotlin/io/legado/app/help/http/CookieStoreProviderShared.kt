package io.legado.app.help.http

/**
 * Cookie 业务层 Provider 跨平台抽象 (KMP)。
 *
 * # 背景
 * app 端 [io.legado.app.help.http.CookieStore] / [io.legado.app.help.http.CookieManager]
 * 依赖 Android 专属资源 (appDb.cookieDao / CacheManager 内存缓存 / android.webkit.CookieManager),
 * 无法直接下沉 shared/commonMain。本接口把业务层 cookie 读写 API 抽象出来,
 * 让 desktop / iOS / 鸿蒙宿主注入实现后即可使用与 app 端等价的 cookie 管理能力。
 *
 * # 与已有下沉部分的关系
 * - **OkHttp 层** ([CookieJarBridge] / [CookieJarBridgeHolder]): 处理 HTTP 请求/响应的
 *   cookie 自动加载/保存 (loadRequest/saveResponse), 已在 commonMain 下沉, 各平台注册 Holder。
 *   本接口不重复 OkHttp 层 API, 仅覆盖业务层 (setCookie/getCookie/removeCookie 等)。
 * - **纯函数** ([cookieToMap] / [mapToCookie] / [mergeCookies] / [mergeCookiesToMap]):
 *   已在 [CookieUtils] 下沉 commonMain, 各平台共享, 本接口不重复声明。
 * - **Android 专属** (applyToWebView / WebView CookieManager 同步): 不在通用接口内,
 *   app 端 [io.legado.app.help.http.CookieManager] 保留 Android 专属方法不动。
 *
 * # 模式参考
 * - [io.legado.app.help.DirectLinkUploadStoreProviders] (interface + Registry 注入先例)
 * - [CookieJarBridgeHolder] (同包同模式, OkHttp 层注入)
 *
 * # 注册时机
 * 宿主启动早期注册一次 (任何 shared 业务代码调用之前):
 * - app 端: App.onCreate 中 `registerAndroidCookieStoreProvider()`;
 * - desktop: Main 中 `registerDefaultJvmCookieStoreProvider()`;
 * - iOS: `registerIosProviders()` 中 `registerDefaultIosCookieStoreProvider()`;
 * - 鸿蒙: `registerOhosProviders()` 中 `registerDefaultOhosCookieStoreProvider()`。
 *
 * 未注册时 [get] 返回 null, 调用方应处理 null (与 [CookieJarBridgeHolder.get] 一致)。
 */
interface CookieStoreProvider {

    /**
     * 保存 cookie (对应 app 端 `CookieStore.setCookie`)。
     *
     * @param url 完整 URL (必须 http/https 开头)
     * @param cookie cookie 字符串 ("k1=v1; k2=v2"), null 视为空串
     */
    fun setCookie(url: String, cookie: String?)

    /**
     * 替换/合并 cookie (对应 app 端 `CookieStore.replaceCookie`)。
     *
     * 已有 cookie 按 key 合并, 新 cookie 覆盖同名 key。
     *
     * @param url 完整 URL
     * @param cookie cookie 字符串
     */
    fun replaceCookie(url: String, cookie: String)

    /**
     * 获取 url 所属二级域名的 cookie (含 session cookie, 对应 app 端 `CookieStore.getCookie`)。
     *
     * @param url 完整 URL 或 domain
     * @return cookie 字符串 ("k1=v1; k2=v2"), 无 cookie 返回空串
     */
    fun getCookie(url: String): String

    /**
     * 获取 cookie 中指定 key 的值 (对应 app 端 `CookieStore.getKey`)。
     *
     * @param url 完整 URL 或 domain
     * @param key cookie key
     * @return cookie value, 不存在返回空串
     */
    fun getKey(url: String, key: String): String

    /**
     * 移除 url 所属二级域名的所有 cookie (对应 app 端 `CookieStore.removeCookie`)。
     *
     * @param url 完整 URL 或 domain
     */
    fun removeCookie(url: String)

    /**
     * 移除 url 所属二级域名中指定 key 的 cookie (对应 app 端 `CookieManager.removeCookie`)。
     *
     * @param url 完整 URL 或 domain
     * @param key cookie key
     */
    fun removeCookie(url: String, key: String)

    /**
     * 获取不含 session cookie 的持久 cookie (对应 app 端 `CookieManager.getCookieNoSession`)。
     *
     * @param url 完整 URL 或 domain
     * @return cookie 字符串, 无 cookie 返回空串
     */
    fun getCookieNoSession(url: String): String

    /**
     * 获取会话期 cookie (对应 app 端 `CookieManager.getSessionCookie`)。
     *
     * @param domain 二级域名
     * @return session cookie 字符串, 不存在返回 null
     */
    fun getSessionCookie(domain: String): String?

    /**
     * 清除所有 cookie (对应 app 端 `CookieStore.clear`)。
     */
    fun clear()
}

/**
 * [CookieStoreProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `CookieStoreProviders.get()?.setCookie(...)` 替代直接 app 单例调用,
 * 行为与 app 端一致, 仅多一层 provider 间接。
 * 未注册时 [get] 返回 null (调用方应处理 null, 与 [CookieJarBridgeHolder.get] 一致)。
 *
 * 模式参考 [io.legado.app.help.DirectLinkUploadStoreProviders] /
 * [CookieJarBridgeHolder]。
 */
object CookieStoreProviders {

    @Volatile
    private var impl: CookieStoreProvider? = null

    /** 宿主启动早期注册一次 (任何 shared 调用之前)。 */
    fun register(impl: CookieStoreProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null。 */
    fun get(): CookieStoreProvider? = impl
}
