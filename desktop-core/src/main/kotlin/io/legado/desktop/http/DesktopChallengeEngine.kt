package io.legado.desktop.http

import kotlin.concurrent.Volatile

/**
 * CF 挑战求解引擎注入点 (:desktop-core 无 JNA 平台引擎依赖, 实现在 :desktop 主模块注册)。
 *
 * 书源栈 (shared okHttpClient) 收到 Cloudflare 挑战响应时, 经本接口驱动 :desktop
 * 内嵌浏览器引擎 (WebView2 / WebKitGTK / WKWebView, 见 DesktopWebViewEngine) 离屏解挑战。
 * headless 入口未注册实现时, 书源栈不挂挑战拦截器, 行为与未启用 CF 挑战处理一致。
 *
 * 语义对齐 Android 端 eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor。
 */
interface DesktopChallengeEngine {

    /** 内嵌浏览器引擎是否可用 (三平台系统引擎缺失时 CF 挑战不可解)。 */
    fun isAvailable(): Boolean

    /**
     * 离屏加载 url 并轮询浏览器 cookie (注入 CookieStore 既有 cookie 后导航):
     * [isSolved] 命中即返回命中时该 url 的完整 cookie 串; 超过 [timeoutMs] 抛异常
     * (调用方按绕过失败处理)。挑战页自动重导航属正常流程, 实现不得中断轮询。
     */
    suspend fun awaitChallengeCookies(
        url: String,
        headerMap: Map<String, String>?,
        delayTimeMs: Long = 1000L,
        timeoutMs: Long,
        isSolved: (cookies: String) -> Boolean,
    ): String
}

object DesktopChallengeEngines {

    @Volatile
    private var impl: DesktopChallengeEngine? = null

    /** :desktop 主模块启动早期注册一次 (任何书源请求之前)。 */
    fun register(impl: DesktopChallengeEngine) {
        this.impl = impl
    }

    /** 未注册返回 null (headless 入口 / 引擎体系未就绪), 调用方据此跳过 CF 挑战处理。 */
    fun get(): DesktopChallengeEngine? = impl
}
