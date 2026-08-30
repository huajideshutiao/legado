package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.help.UserAgentProviders.impl
import io.legado.app.help.UserAgentProviders.register
import kotlin.concurrent.Volatile

/**
 * UserAgent 能力 provider (跨平台抽象, 合并自原 help.ui.UserAgentProvider)。
 *
 * - [get]: HTTP 请求 UA (fun interface 唯一抽象方法, 支持 SAM lambda 注册)
 * - [getWebViewUA]: WebView 默认 UA, 默认实现回退 [get];
 *   只实现 [get] 的注册方 (HTTP UA 端) 自动获得 WebView UA 兜底。
 */
fun interface UserAgentProvider {
    fun get(): String

    /** 获取 WebView 默认 UA, 默认回退 [get]。 */
    fun getWebViewUA(): String = get()
}

/**
 * [UserAgentProvider] 容器。宿主启动早期注册一次。
 *
 * 两个槽位 (分别向后兼容原 help / help.ui 两套注册 API):
 * - [impl]: HTTP 请求 UA, 缺省回退 [AppConst.UA_NAME];
 * - WebView UA: 经 [register] 注册, 缺省依次回退 HTTP UA ([impl] 的 [UserAgentProvider.getWebViewUA]) /
 *   [AppConst.UA_NAME]。
 */
object UserAgentProviders {

    /** HTTP 请求 UA 实现 (宿主启动早期注册一次)。 */
    @Volatile
    var impl: UserAgentProvider? = null

    @Volatile
    private var webViewImpl: UserAgentProvider? = null

    /** 注册 WebView UA 实现 (宿主启动早期注册一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前)。 */
    fun register(provider: UserAgentProvider) {
        webViewImpl = provider
    }

    /** HTTP 请求 UA, 优先读实现, 为空或未注册时回退 [AppConst.DEFAULT_USER_AGENT]。 */
    fun get(): String = impl?.get()?.takeIf { it.isNotBlank() } ?: AppConst.DEFAULT_USER_AGENT

    /** WebView 默认 UA, 未注册 WebView 实现时回退 HTTP UA, 再回退 [AppConst.DEFAULT_USER_AGENT]。 */
    fun getWebViewUA(): String =
        webViewImpl?.getWebViewUA()?.takeIf { it.isNotBlank() }
            ?: impl?.getWebViewUA()?.takeIf { it.isNotBlank() }
            ?: AppConst.DEFAULT_USER_AGENT
}

/**
 * 从请求头 Map 中提取 User-Agent (大小写不敏感)。
 * 若未指定或为空白, 默认回落至 [fallback] (缺省为 [UserAgentProviders.get])。
 */
fun Map<String, String>?.getUserAgent(
    fallback: () -> String = { UserAgentProviders.get() }
): String {
    if (this.isNullOrEmpty()) return fallback()
    for ((k, v) in this) {
        if (k.equals(AppConst.UA_NAME, ignoreCase = true) && v.isNotBlank()) {
            return v
        }
    }
    return fallback()
}
