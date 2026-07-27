package io.legado.app.help.ui

/**
 * [OpenUrlProvider] iOS 端 stub 实现。
 *
 * iOS Kotlin/Native 无法直接调 UIApplication.openURL, 用 println stub。
 * 后续可通过 tsfn 桥接到 ArkTS/UIKit 真实化。
 * 在 iOS 宿主启动早期经 [registerIosOpenUrlProvider] 注册到 [OpenUrlProviders]。
 */
object IosOpenUrlProviderImpl : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int
    ) {
        // 控制台打印, 不阻塞 JS 执行线程
        println("[OpenUrl] $url")
    }
}

/** iOS 宿主启动早期注册一次, 任何 JsExtensionsCommon.openUrl 调用之前。 */
fun registerIosOpenUrlProvider() {
    OpenUrlProviders.register(IosOpenUrlProviderImpl)
}
