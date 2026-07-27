package io.legado.app.help.ui

import android.webkit.WebSettings
import splitties.init.appCtx

/**
 * [UserAgentProvider] 的 app 端实现。
 *
 * 委托原 [WebSettings.getDefaultUserAgent], 行为与下沉前完全一致。
 * 在 App.onCreate 经 [registerAndroidUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object AndroidUserAgentProvider : UserAgentProvider {

    override fun getWebViewUA(): String {
        return WebSettings.getDefaultUserAgent(appCtx)
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
