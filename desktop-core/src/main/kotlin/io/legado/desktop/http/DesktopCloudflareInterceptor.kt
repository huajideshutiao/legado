package io.legado.desktop.http

import eu.kanade.tachiyomi.network.interceptor.CHALLENGE_TIMEOUT_MS
import eu.kanade.tachiyomi.network.interceptor.COOKIE_CLEARANCE
import eu.kanade.tachiyomi.network.interceptor.ChallengeInterceptorBase
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieStoreProviders
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * 书源栈 (shared okHttpClient) 的 Cloudflare 挑战拦截器。
 *
 * 触发判定 / 同 host 去重排队 / 挑战窗口复用 / 重发都在共享基类
 * [ChallengeInterceptorBase] (Android 端 WebView 版同一份), 本类只提供桌面求解通道:
 * 挑战求解经 [DesktopChallengeEngines] (:desktop 注册, 离屏加载 + 原生 cookie 轮询),
 * 30s 挑战窗由引擎内超时保证; 解出的 cookie 回写 CookieStore, 重发重新过 shared
 * HttpHelper 的 cookie bridge 拦截器, loadRequest 从 CookieStore 合入新 cf_clearance。
 *
 * 与 Android 版差异: 无 interactive 提前中止与 WebView 过旧检测 (引擎无对应桥),
 * 需人工交互的挑战靠 30s 超时放弃, 语义等效 (求解通道差异见共享基类 KDoc)。
 *
 * 覆盖范围: 只有 Android 与桌面挂 CF 自动解挑战; iOS / 鸿蒙的共享 HTTP 栈不挂本拦截器。
 */
open class DesktopCloudflareInterceptor : ChallengeInterceptorBase() {

    override fun isEngineAvailable(): Boolean =
        DesktopChallengeEngines.get()?.isAvailable() == true

    override fun onEngineUnavailable() {
        AppLog.put("无可用内嵌浏览器引擎, Cloudflare 挑战不可解")
    }

    override fun oldClearance(request: Request): String? =
        CookieStoreProviders.get()?.getCookie(request.url.toString())?.let { clearanceValue(it) }

    override fun clearClearance(request: Request) {
        CookieStoreProviders.get()?.removeCookie(request.url.toString(), COOKIE_CLEARANCE)
    }

    override fun solve(request: Request, oldClearance: String?) {
        val engine = DesktopChallengeEngines.get() ?: throw CloudflareBypassException()
        val cookies = runBlocking {
            engine.awaitChallengeCookies(
                url = request.url.toString(),
                headerMap = request.headers.toMap(),
                timeoutMs = CHALLENGE_TIMEOUT_MS,
            ) { cookieStr ->
                clearanceValue(cookieStr).let { it != null && it != oldClearance }
            }
        }
        CookieStoreProviders.get()?.replaceCookie(request.url.toString(), cookies)
    }
}
