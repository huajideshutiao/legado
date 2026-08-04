package io.legado.app.help.toast

import io.legado.app.help.toast.DesktopTrayNotifier.sender


/**
 * 托盘通知发送器的注册点。
 *
 * 进程内唯一 TrayIcon 由桌面宿主的托盘组件 (desktop `DesktopMediaTray`) 持有 ——
 * 一个应用只该有一个托盘图标, 故 Toaster 不再自建, 改为经此委托;
 * 未注册 (无头 / 无托盘 / 托盘未安装) 时 toast 落 stdout。
 */
object DesktopTrayNotifier {
    /** 返回 false 表示未发出, 调用方走兜底。 */
    @Volatile
    var sender: ((message: String) -> Boolean)? = null

    /**
     * 主窗口内 toast 宿主 (desktop 模块 DesktopToasts 注入), 优先于托盘气泡:
     * 托盘通知受 Windows 通知设置/专注助手影响经常静默不显示, 窗口内 UI toast
     * 才是与 app 端 Toast 语义一致的可靠反馈; 窗口最小化时由宿主自行转 [sender]。
     */
    @Volatile
    var uiSender: ((message: String) -> Boolean)? = null
}

/**
 * [Toaster] 的桌面 JVM actual 实现。
 *
 * 经 [DesktopTrayNotifier] 委托给宿主托盘图标显示气泡通知, 未注册时退化为 println 到 stdout。
 *
 * # 设计要点
 * - 调用线程不限: `TrayIcon.displayMessage` 内部线程安全, 可在任意线程调用
 * - 消息类型映射: 桌面端无"短/长"概念, 都走 `TrayIcon.MessageType.INFO`;
 *   toastLong 仅在 stdout 模式下加 `[LONG]` 前缀区分
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` (app 端 help/media/)。
 */
class DesktopToaster : Toaster {

    override fun toast(message: String) {
        showMessage(message, isLong = false)
    }

    override fun toastLong(message: String) {
        showMessage(message, isLong = true)
    }

    /** 显示消息: 优先主窗口 UI toast, 其次宿主托盘图标, 退化到 stdout。 */
    private fun showMessage(message: String, isLong: Boolean) {
        val sent = runCatching {
            DesktopTrayNotifier.uiSender?.invoke(message) == true ||
                DesktopTrayNotifier.sender?.invoke(message) == true
        }.getOrDefault(false)
        if (sent) return
        // stdout 兜底 (无头模式 / 托盘未安装)
        if (isLong) {
            println("[toast-long] $message")
        } else {
            println("[toast] $message")
        }
    }
}

/**
 * 桌面宿主启动早期注册 [Toaster] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `Toasters.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopToaster() {
    Toasters.register(DesktopToaster())
}
