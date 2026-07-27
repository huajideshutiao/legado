package io.legado.app.help.ui

import kotlin.concurrent.Volatile

/**
 * WebView UserAgent 获取能力 provider (跨平台抽象)。
 *
 * 原 app 端 `JsExtensions.getWebViewUA` 的平台依赖经此接口抽象,
 * 由 app 端 [AndroidUserAgentProvider] 在 App.onCreate 经 [registerAndroidUserAgentProvider]
 * 注册, 供 shared/commonMain 的 `JsExtensionsCommon` 回调。
 *
 * 模式参考 `VerificationUiProvider` / `VerificationUiProviders`。
 */
interface UserAgentProvider {

    /** 获取 WebView 默认 UserAgent (对应 app 端 `WebSettings.getDefaultUserAgent(appCtx)`)。 */
    fun getWebViewUA(): String
}

/**
 * [UserAgentProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `UserAgentProviders.get().getWebViewUA()` 替代原
 * `WebSettings.getDefaultUserAgent(appCtx)`, 仅多一层 provider 间接。
 */
object UserAgentProviders {
    @Volatile
    private var impl: UserAgentProvider? = null

    /** 宿主启动早期注册一次 (任何 JsExtensionsCommon.getWebViewUA 调用之前)。 */
    fun register(impl: UserAgentProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): UserAgentProvider = impl ?: error("UserAgentProviders not registered")
}
