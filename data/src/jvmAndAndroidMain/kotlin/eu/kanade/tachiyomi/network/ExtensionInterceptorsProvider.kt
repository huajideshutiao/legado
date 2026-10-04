// Copyright The Keiyoushi Contributors. Apache-2.0.
// 扩展客户端拦截器链的平台缝合点: NetworkHelper (本模块, 双端共用) 的两条链差异段由此提供。
// Android 端由 ExtensionCompat (app 模块) 注册: client 链含 CloudflareInterceptor
// (WebView 体系留在 app 模块), cloudflareClient 链无; JVM 端 (desktop) 注册无 WebView 的等价链。
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver
import okhttp3.Interceptor

interface ExtensionInterceptorsProvider {

    /**
     * client 链拦截器 (Android: Uncaught/UserAgent/Cloudflare 三件; 顺序即插入序, 前插到宿主共享 client 之前)。
     *
     * @param context 宿主上下文 (Android 真实 Application; JVM 为桌面端存根实例)
     * @param cookieJar 扩展客户端 cookie 解析器 (挑战 cookie 回写能力, 见 ChallengeCookieResolver)
     * @param defaultUserAgent 宿主默认 UA 提供者 (与 NetworkHelper.defaultUserAgentProvider 同源)
     */
    fun clientInterceptors(
        context: Context,
        cookieJar: ChallengeCookieResolver,
        defaultUserAgent: () -> String,
    ): List<Interceptor>

    /** 1.4 契约链 (无 Cloudflare 挑战处理段): 与 [clientInterceptors] 的差异段仅挑战处理。 */
    fun cloudflareClientInterceptors(
        context: Context,
        cookieJar: ChallengeCookieResolver,
        defaultUserAgent: () -> String,
    ): List<Interceptor>
}
