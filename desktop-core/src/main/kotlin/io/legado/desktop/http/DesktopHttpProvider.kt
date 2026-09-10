package io.legado.desktop.http

import io.legado.app.help.http.OkHttpClientProvider
import io.legado.app.help.http.OkHttpProxyClientProvider
import okhttp3.OkHttpClient

/**
 * 桌面端 OkHttpClient / 代理 OkHttpClient Provider 实现。
 *
 * HttpHelper 主体下沉 shared/jvmAndAndroidMain 后, 本类仅作薄包装:
 * - [okHttpClient] / [getProxyClient] 直接转发到 shared `io.legado.app.help.http.okHttpClient`
 *   / `getProxyClient(proxy)`, 与 Android 端共用同一份 client 组装逻辑 (DRY)。
 * - 桌面端不注册 [io.legado.app.help.http.CronetProviders] 实现, shared createOkHttpClient
 *   内 `CronetProviders.get()` 返回 null, 自动跳过 Cronet eventListener/loader/interceptor
 *   (与原桌面裁剪版行为一致)。
 * - UA: shared HttpHelper 内 `UserAgentProviders.get()` 由桌面
 *   `registerDesktopSourceProviders` 注册 (PreferKey.userAgent 空 → AppConst.DEFAULT_USER_AGENT)。
 * - ProgressResponseBody 包装: 桌面端 MangaReaderPlatform.Image 已注册
 *   `ProgressManager.addListener(url)` (转圈环心显示下载百分比), 有监听器时
 *   `ProgressManager.getProgressListener(url)` 非空, 包装分支触发并逐字节回调;
 * - cookieJar: 桌面端经 `registerDesktopHttpProvider` 注册 [DesktopCookieJarBridge]
 *   到 [io.legado.app.help.http.CookieJarBridgeHolder], shared HttpHelper 通过该 holder
 *   桥接到桌面 cookie 实现。
 *
 * 实现接口:
 * - [OkHttpClientProvider]: 暴露 shared 单例 [okHttpClient];
 * - [OkHttpProxyClientProvider]: 暴露 shared [getProxyClient] 工厂 + 缓存 (在 shared 内维护)。
 */
class DesktopHttpProvider : OkHttpClientProvider, OkHttpProxyClientProvider {

    override val okHttpClient: OkHttpClient
        get() = io.legado.app.help.http.okHttpClient

    override fun getProxyClient(proxy: String?): OkHttpClient =
        io.legado.app.help.http.getProxyClient(proxy)
}
