// Copyright The Mihon Authors. Apache-2.0.
// 挑战求解通道是平台专属的: Android 用 WebView, 桌面用内嵌浏览器引擎。共享的是状态机与
// cookie 契约, 故把状态机收在本类, 两端只实现"怎么解"。
package eu.kanade.tachiyomi.network.interceptor

import io.legado.app.constant.AppLog
import io.legado.app.help.http.cookieJarHeader
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

/**
 * Cloudflare 挑战拦截器共享骨架 (Android WebView 版与桌面引擎版共用同一份状态机)。
 *
 * # 覆盖范围
 * 自动解挑战只挂在 Android 与桌面的 HTTP 栈上。iOS / 鸿蒙的共享 HTTP 栈
 * (`KmpHttpTypes` / `NativeHttpProvider`) 不挂本拦截器, 这两端遇到挑战仍按原版行为
 * 把挑战响应交回调用方 (需人工在登录窗完成)。
 *
 * # 状态机
 * - 触发: `cf-mitigated: challenge` + `Server` 头 (Cloudflare 官方检测方式);
 * - per-host 公平锁去重排队: 并发挑战只解一次, 其余请求按挑战窗口复用同一次求解结果;
 * - 求解通道由子类提供 ([solve]); 解前读/清旧 `cf_clearance` 也由子类实现, 范围是
 *   "该端所有可能留存旧值的存储" (Android: webkit CookieManager + CookieStore;
 *   桌面: CookieStore —— 引擎 cookie 无删除 API, 由"解出的值 ≠ 旧值"兜底判定)。
 *
 * # 与上游 mihon CloudflareInterceptor 的差异
 * 新增同 host 并发去重排队与解前主动跳过 ([canCarrySolvedCookies])。
 */
abstract class ChallengeInterceptorBase : Interceptor {

    // 同 host 挑战去重: 并发 403 只允许一个请求开引擎解挑战, 其余排队复用结果
    private val hostStates = ConcurrentHashMap<String, HostChallengeState>()

    /** 求解引擎是否可用; 不可用时原样返回挑战响应。 */
    protected abstract fun isEngineAvailable(): Boolean

    /** 引擎不可用时的日志文案 (两端不同, 由子类给)。 */
    protected abstract fun onEngineUnavailable()

    /** 引擎可用时的准备动作 (Android: WebView 预热)。 */
    protected open fun prepareEngine() {}

    /** 解前读取旧 cf_clearance 值 (无则 null), 作为"是否解出新值"的判定基线。 */
    protected abstract fun oldClearance(request: Request): String?

    /** 解前清掉旧 cf_clearance。 */
    protected abstract fun clearClearance(request: Request)

    /**
     * 求解并把解出的 cookie 回写宿主存储; 未解出抛 [CloudflareBypassException]。
     *
     * @param oldClearance [oldClearance] 读到的旧值, 引擎据此判定"解出了新值"
     */
    protected abstract fun solve(request: Request, oldClearance: String?)

    /**
     * 解出的 cookie 能否随重发请求带上。
     *
     * 宿主 OkHttp 栈的 cookie 桥只在请求带 `CookieJar` 伪头时把 CookieStore 合入请求,
     * 不带伪头的请求解了也带不上 —— 每个这样的请求白等一整个挑战窗, 书源批量并发抓取时
     * 就是"逐请求 30s 隐藏求解"的风暴。默认要求伪头; client 自带 OkHttp CookieJar 的栈
     * (Android 插件栈) 覆写为放行。
     */
    protected open fun canCarrySolvedCookies(request: Request): Boolean =
        request.header(cookieJarHeader) != null

    final override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) {
            return response
        }
        if (!isEngineAvailable()) {
            onEngineUnavailable()
            return response
        }
        // 解出的 cookie 带不上就没必要开引擎等一整个挑战窗 (见 [canCarrySolvedCookies])
        if (!canCarrySolvedCookies(request)) {
            AppLog.put("请求未启用 CookieJar, 解出的挑战 cookie 带不上, 跳过求解")
            return response
        }
        prepareEngine()

        val state = hostStates.computeIfAbsent(request.url.host) { HostChallengeState() }
        // 本请求遭遇挑战的时刻, 用于区分排队可复用的解与更早轮次的旧解 (nanoTime 单调)
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
                    // 成功 → 重发时 cookie 桥合入新解 cf_clearance; 失败 → 同样抛异常
                    if (!state.lastSolveOk) {
                        throw CloudflareBypassException()
                    }
                } else {
                    val old = oldClearance(request)
                    clearClearance(request)
                    try {
                        solve(request, old)
                        state.lastSolveOk = true
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

    /** 挑战检测: `cf-mitigated: challenge` + Server 头 (Cloudflare 官方检测方式)。 */
    private fun isChallenge(response: Response): Boolean =
        response.header("cf-mitigated") == "challenge" &&
                response.header("Server") in SERVER_CHECK

    /** 从 "; name=value; ..." 串提取 cf_clearance 值 (对照 CookieStore.getKey)。 */
    protected fun clearanceValue(cookieStr: String): String? {
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
const val CHALLENGE_TIMEOUT_MS = 30_000L

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
const val COOKIE_CLEARANCE = "cf_clearance"

// 上游为 MR.strings.information_cloudflare_bypass_failure; 宿主无 moko 资源
private const val CLOUDFLARE_BYPASS_FAILURE_MESSAGE = "Failed to bypass Cloudflare challenge"

/** 绕过失败信号 (求解未解出 / 排队超时); 拦截器统一包装成 [IOException]。 */
class CloudflareBypassException : Exception()
