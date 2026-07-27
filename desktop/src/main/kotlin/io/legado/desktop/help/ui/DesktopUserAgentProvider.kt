package io.legado.desktop.help.ui

import io.legado.app.help.ui.UserAgentProvider
import io.legado.app.help.ui.UserAgentProviders

/**
 * [UserAgentProvider] 桌面端实现。
 *
 * desktop 无 WebView, 返回桌面 Chrome UA 替代 app 端 `WebSettings.getDefaultUserAgent`。
 * 在 main 入口经 [registerDesktopUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object DesktopUserAgentProviderImpl : UserAgentProvider {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    override fun getWebViewUA(): String = DESKTOP_UA
}

/** 桌面端 main 入口早期注册一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前。 */
fun registerDesktopUserAgentProvider() {
    UserAgentProviders.register(DesktopUserAgentProviderImpl)
}
