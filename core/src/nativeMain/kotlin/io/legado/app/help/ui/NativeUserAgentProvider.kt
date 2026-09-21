package io.legado.app.help.ui

import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders

/**
 * [UserAgentProvider] native (iOS/鸿蒙) 端 stub 实现: 两端都无 WebView KMP API,
 * 返回各自平台的 UA 常量替代 app 端 `WebSettings.getDefaultUserAgent` (见 [nativeWebViewUserAgent])。
 *
 * 在宿主启动早期经 [registerNativeUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object NativeUserAgentProviderImpl : UserAgentProvider {

    // 合并后接口要求 get(); 本对象经 register() 注册进 WebView UA 槽位,
    // 容器 get() (HTTP 语义) 不会走到这里, 直接复用 WebView UA 作为兜底。
    override fun get(): String = getWebViewUA()

    override fun getWebViewUA(): String = nativeWebViewUserAgent
}

/** 宿主启动早期注册一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前。 */
fun registerNativeUserAgentProvider() {
    UserAgentProviders.register(NativeUserAgentProviderImpl)
}

/** 各端 WebView 默认 UA 常量 (两端唯一差异)。 */
internal expect val nativeWebViewUserAgent: String
