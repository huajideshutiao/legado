package io.legado.app.help.ui

import android.webkit.WebSettings
import io.legado.app.App
import io.legado.app.help.UserAgentProvider
import io.legado.app.help.UserAgentProviders

/**
 * [UserAgentProvider] 的 app 端实现。
 *
 * 委托原 [WebSettings.getDefaultUserAgent], 行为与下沉前完全一致。
 * 在 App.onCreate 经 [registerAndroidUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object AndroidUserAgentProvider : UserAgentProvider {

    // 合并后接口要求 get(); 本对象经 register() 注册进 WebView UA 槽位,
    // 容器 get() (HTTP 语义) 不会走到这里, 直接复用 WebView UA 作为兜底。
    override fun get(): String = getWebViewUA()

    override fun getWebViewUA(): String {
        return WebSettings.getDefaultUserAgent(App.instance)
    }
}

/**
 * 注册 app 端 [AndroidUserAgentProvider] 到 [UserAgentProviders]。
 *
 * 在 App.onCreate 早期调用一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前。
 */
fun registerAndroidUserAgentProvider() {
    UserAgentProviders.register(AndroidUserAgentProvider)
}
