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

    /** HTTP 请求 UA, 未注册时回退 [AppConst.UA_NAME]。 */
    fun get(): String = impl?.get() ?: AppConst.UA_NAME

    /** WebView 默认 UA, 未注册 WebView 实现时回退 HTTP UA, 再回退 [AppConst.UA_NAME]。 */
    fun getWebViewUA(): String =
        webViewImpl?.getWebViewUA() ?: impl?.getWebViewUA() ?: AppConst.UA_NAME
}
