package io.legado.app.help.ui

/**
 * [UserAgentProvider] 鸿蒙端 stub 实现。
 *
 * 返回鸿蒙 WebView 默认 UA 常量; 真实实现后续可经 tsfn 桥接取 @ohos.web.webview 默认 UA。
 * 在 [registerOhosProviders] 经 [registerOhosUserAgentProvider] 注册到 [UserAgentProviders]。
 */
object OhosUserAgentProviderImpl : UserAgentProvider {

    private const val OHOS_WEBVIEW_UA =
        "Mozilla/5.0 (Linux; Android 12; HarmonyOS) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/99.0.4844.88 HuaweiBrowser/14.0.2.300 Mobile Safari/537.36"

    override fun getWebViewUA(): String = OHOS_WEBVIEW_UA
}

/** 鸿蒙宿主启动早期注册一次, 任何 JsExtensionsCommon.getWebViewUA 调用之前。 */
fun registerOhosUserAgentProvider() {
    UserAgentProviders.register(OhosUserAgentProviderImpl)
}
