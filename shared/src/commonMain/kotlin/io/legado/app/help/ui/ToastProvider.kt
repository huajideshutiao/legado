package io.legado.app.help.ui

import kotlin.concurrent.Volatile

/**
 * Toast 提示能力 provider (跨平台抽象)。
 *
 * 原 app 端 `JsExtensions.toast` / `longToast` 的 UI 依赖经此接口抽象,
 * 由 app 端 [AndroidToastProvider] 在 App.onCreate 经 [registerAndroidToastProvider]
 * 注册, 供 shared/commonMain 的 `JsExtensionsCommon` 回调。
 *
 * 模式参考 `VerificationUiProvider` / `VerificationUiProviders`。
 */
interface ToastProvider {

    /** 弹 Toast 提示, [long] 为 true 时停留较长时间。 */
    fun showToast(msg: String, long: Boolean = false)
}

/**
 * [ToastProvider] 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `ToastProviders.get().showToast(...)` 替代原 `appCtx.toastOnUi(...)`,
 * 仅多一层 provider 间接。
 */
object ToastProviders {
    @Volatile
    private var impl: ToastProvider? = null

    /** 宿主启动早期注册一次 (任何 JsExtensionsCommon.toast 调用之前)。 */
    fun register(impl: ToastProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ToastProvider = impl ?: error("ToastProviders not registered")
}
