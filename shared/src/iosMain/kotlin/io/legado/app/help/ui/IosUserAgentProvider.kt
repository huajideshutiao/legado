package io.legado.app.help.ui

import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders

/**
 * [UserAgentProvider] iOS 端 stub 实现。
 *
 * iOS 无 WebView KMP API, 返回 iOS Safari UA 常量替代 app 端 `WebSettings.getDefaultUserAgent`。
 * 在 iOS 宿主启动早期经 [registerIosUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object IosUserAgentProviderImpl : UserAgentProvider {

    private const val IOS_SAFARI_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    // 合并后接口要求 get(); 本对象经 register() 注册进 WebView UA 槽位,
    // 容器 get() (HTTP 语义) 不会走到这里, 直接复用 WebView UA 作为兜底。
    override fun get(): String = getWebViewUA()

    override fun getWebViewUA(): String = IOS_SAFARI_UA
}

/** iOS 宿主启动早期注册一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前。 */
fun registerIosUserAgentProvider() {
    UserAgentProviders.register(IosUserAgentProviderImpl)
}
