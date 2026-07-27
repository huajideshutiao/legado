package io.legado.app.help.ui

/**
 * [ToastProvider] iOS 端 stub 实现。
 *
 * iOS Kotlin/Native 无系统 toast API, 用 println stub (NSAlert 会阻塞调用线程)。
 * 后续可通过 tsfn 桥接到 ArkTS/UIKit 真实化。
 * 在 iOS 宿主启动早期经 [registerIosToastProvider] 注册到 [ToastProviders]。
 */
object IosToastProviderImpl : ToastProvider {

    override fun showToast(msg: String, long: Boolean) {
        // 控制台打印, 不阻塞 JS 执行线程
        println("[Toast] $msg")
    }
}

/** iOS 宿主启动早期注册一次, 任何 JsExtensionsCommon.toast 调用之前。 */
fun registerIosToastProvider() {
    ToastProviders.register(IosToastProviderImpl)
}
