package io.legado.app.help.ui

import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * [ToastProvider] 的 app 端实现。
 *
 * 委托原 [appCtx.toastOnUi] / [appCtx.longToastOnUi], 行为与下沉前完全一致。
 * 在 App.onCreate 经 [registerAndroidToastProvider] 注册到 [ToastProviders]。
 */
object AndroidToastProvider : ToastProvider {

    override fun showToast(msg: String, long: Boolean) {
        if (long) {
            appCtx.longToastOnUi(msg)
        } else {
            appCtx.toastOnUi(msg)
        }
    }
}

/**
 * 注册 app 端 [AndroidToastProvider] 到 [ToastProviders]。
 *
 * 在 App.onCreate 早期调用一次, 任何 JsExtensionsCommon.toast 调用之前。
 */
fun registerAndroidToastProvider() {
    ToastProviders.register(AndroidToastProvider)
}
