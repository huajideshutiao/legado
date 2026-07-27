package io.legado.desktop.help.ui

import io.legado.app.help.ui.ToastProvider
import io.legado.app.help.ui.ToastProviders
import java.awt.Toolkit

/**
 * [ToastProvider] 桌面端实现。
 *
 * desktop 无系统 Toast, 用 beep + 控制台打印替代 (JOptionPane 会阻塞, 不适合 toast)。
 * 在 main 入口经 [registerDesktopToastProvider] 注册到 [ToastProviders]。
 */
object DesktopToastProviderImpl : ToastProvider {

    override fun showToast(msg: String, long: Boolean) {
        // 蜂鸣提示 + 控制台输出, 不阻塞调用方 (JS 执行线程)
        runCatching { Toolkit.getDefaultToolkit().beep() }
        println("[Toast] $msg")
    }
}

/** 桌面端 main 入口早期注册一次, 任何 JsExtensionsCommon.toast 调用之前。 */
fun registerDesktopToastProvider() {
    ToastProviders.register(DesktopToastProviderImpl)
}
