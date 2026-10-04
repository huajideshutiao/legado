package io.legado.desktop.http

import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieStoreProviders
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

/**
 * 书源栈 (shared okHttpClient) 的 Cloudflare 挑战拦截器, 语义对齐 Android 端
 * eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor (决策 1a 全端化):
 *
 * - 触发: cf-mitigated: challenge + Server 头 (官方检测方式);
 * - per-host 公平锁去重排队: 并发挑战只解一次, 排队者按挑战窗口
 *   (lastAttemptEnd >= 遭遇挑战时刻) 复用结果, 失败同样抛异常, 避免串行重试;
 * - 挑战求解经 [DesktopChallengeEngines] (:desktop 注册, 离屏加载 + 原生 cookie 轮询),
 *   30s 挑战窗由引擎内超时保证;
 * - cookie 回写 CookieStore (replaceCookie 合并语义), 重发 chain.proceed 重新过
 *   shared HttpHelper 的 cookie bridge 拦截器, loadRequest 从 CookieStore 合入新
 *   cf_clearance (mergeCookies 后参覆盖)。
 *
 * 与 Android 版差异: 无 interactive 提前中止与 WebView 过旧检测 (引擎无对应桥),
 * 需人工交互的挑战靠 30s 超时放弃, 语义等效。
 */
class DesktopCloudflareInterceptor : Interceptor {

    // 同 host 挑战去重: 并发 403 只允许一个请求开引擎解挑战, 其余排队复用结果
    private val hostStates = ConcurrentHashMap<String, HostChallengeState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) {
            return response
        }

        val engine = DesktopChallengeEngines.get()
        if (engine == null || !engine.isAvailable()) {
            // 引擎未注册 (headless) 或不可用: 无解, 原样返回挑战响应
            // (回退语义同 Android WebViewUtil.supportsWebView == false)
            AppLog.put("无可用内嵌浏览器引擎, Cloudflare 挑战不可解")
            return response
        }

        val state = hostStates.computeIfAbsent(request.url.host) { HostChallengeState() }
        // 本请求遭遇挑战的时刻, 用于区分排队可复用的解与更早轮次的旧解
        // (对照 Android SystemClock.elapsedRealtime, nanoTime 单调)
        val challengeStart = System.nanoTime() / 1_000_000

        response.close()
        try {
            // 超过挑战等待上限仍未拿到锁, 视同绕过失败 (排队者不再重复开引擎)
            if (!state.lock.tryLock(CHALLENGE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw CloudflareBypassException()
            }
            try {
                if (state.lastAttemptEnd >= challengeStart) {
                    // 本次挑战窗口内已有请求完成解挑战, 直接复用其结果:
                    // 成功 → 重发时 cookie bridge 合入新解 cf_clearance; 失败 → 同样抛异常
                    if (!state.lastSolveOk) {
                        throw CloudflareBypassException()
                    }
                } else {
                    val store = CookieStoreProviders.get()
                    val oldClearance = store?.getCookie(request.url.toString())
                        ?.let { clearanceValue(it) }
                    // 解前清 store 旧值: 引擎注入的是清后 cookie, 避免旧 cf_clearance
                    // 让引擎立即"命中"; 新值由下方 replaceCookie 回写
                    store?.removeCookie(request.url.toString(), COOKIE_CLEARANCE)
                    try {
                        val cookies = runBlocking {
                            engine.awaitChallengeCookies(
                                url = request.url.toString(),
                                headerMap = request.headers.toMap(),
                                timeoutMs = CHALLENGE_TIMEOUT_MS,
                            ) { cookieStr ->
                                clearanceValue(cookieStr).let { it != null && it != oldClearance }
                            }
                        }
                        state.lastSolveOk = true
                        store?.replaceCookie(request.url.toString(), cookies)
                    } finally {
                        state.lastAttemptEnd = System.nanoTime() / 1_000_000
                    }
                }
            } finally {
                state.lock.unlock()
            }

            return chain.proceed(request)
        }
        // OkHttp 的 enqueue 只处理 IOException, 统一包装避免崩溃整个 app
        catch (e: CloudflareBypassException) {
            throw IOException(CLOUDFLARE_BYPASS_FAILURE_MESSAGE, e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun isChallenge(response: Response): Boolean =
        response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK

    /** 从 "; name=value; ..." 串提取 cf_clearance 值 (对照 CookieStore.getKey)。 */
    private fun clearanceValue(cookieStr: String): String? {
        cookieStr.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == COOKIE_CLEARANCE) {
                return pair.substring(index + 1).trim()
            }
        }
        return null
    }

    private class HostChallengeState {

        /** 公平锁: 先触发挑战的请求先解 */
        val lock = ReentrantLock(true)

        /** 最近一次解挑战尝试结束时刻 (nanoTime/1e6); 排队者以它判定结果是否属于本次挑战窗口 */
        @Volatile
        var lastAttemptEnd = Long.MIN_VALUE

        /** 最近一次解挑战是否成功 */
        @Volatile
        var lastSolveOk = false
    }
}

// 与上游 awaitFor30Seconds 对齐, 排队锁的上限留出余量
private const val CHALLENGE_WAIT_SECONDS = 35L

// 引擎内挑战轮询窗 (对照 Android CountDownLatch 30s)
private const val CHALLENGE_TIMEOUT_MS = 30_000L

// 上游为 MR.strings.information_cloudflare_bypass_failure; 宿主无 moko 资源
private const val CLOUDFLARE_BYPASS_FAILURE_MESSAGE = "Failed to bypass Cloudflare challenge"

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private const val COOKIE_CLEARANCE = "cf_clearance"

private class CloudflareBypassException : Exception()
