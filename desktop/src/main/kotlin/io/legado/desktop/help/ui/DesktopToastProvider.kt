package io.legado.desktop.help.ui

import io.legado.app.help.toast.Toasters
import io.legado.app.help.ui.ToastProvider
import io.legado.app.help.ui.ToastProviders

/**
 * [ToastProvider] 桌面端实现。
 *
 * 转发到已注册的 [Toasters] (桌面 actual 为 DesktopToaster): 经 DesktopTrayNotifier 交给
 * DesktopMediaTray 唯一托盘图标发气泡通知, 无托盘/无头时它自己落 stdout 兜底。
 * 原实现只 beep + println, 书源级提示 (校验成功 / 规则错误) 用户完全看不到。
 * 不另起 Snackbar 宿主: 托盘通知在窗口最小化时同样可见, 且托盘所有权已收口在 DesktopMediaTray。
 */
object DesktopToastProviderImpl : ToastProvider {

    override fun showToast(msg: String, long: Boolean) {
        // 未注册 Toasters (get() 抛异常) 时退回控制台, 不打断调用方 (JS 执行线程)
        val toaster = runCatching { Toasters.get() }.getOrNull()
        if (toaster == null) {
            println("[Toast] $msg")
            return
        }
        if (long) toaster.toastLong(msg) else toaster.toast(msg)
    }
}

/** 桌面端 main 入口早期注册一次, 任何 JsExtensionsCommon.toast 调用之前。 */
fun registerDesktopToastProvider() {
    ToastProviders.register(DesktopToastProviderImpl)
}
