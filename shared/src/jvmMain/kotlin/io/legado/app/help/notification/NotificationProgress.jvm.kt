package io.legado.app.help.notification

import io.legado.app.help.toast.DesktopTrayNotifier
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import java.awt.Window

/**
 * [NotificationProgress] 的桌面 JVM actual 实现。
 *
 * 进度文本经 [DesktopTrayNotifier] 委托给宿主唯一托盘图标显示气泡 (进程内只该有一个托盘图标),
 * 未注册托盘时退化为 println 到 stdout。
 *
 * # 设计要点
 * - 桌面端通知无"持久显示 + 进度条"概念, showProgress 显示 "title | content (progress/max)"
 *   文本气泡, 每次调用覆盖上一条; 真进度条走 [DesktopTaskbar] (任务栏/Dock)
 * - cancel 只清任务栏进度, 气泡由系统自动超时消失
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopNotificationProgress : NotificationProgress {

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        DesktopTaskbar.show(progress, max)
        // 拼接进度文本: "content (progress/max)" 或 "content"
        val progressText = if (max > 0) {
            "$content ($progress/$max)"
        } else {
            content
        }
        val sent = runCatching {
            DesktopTrayNotifier.sender?.invoke("$title | $progressText") == true
        }.getOrDefault(false)
        if (sent) return
        // stdout 兜底 (无头模式 / 托盘未安装)
        println("[progress] $title | $progressText")
    }

    override fun cancel() {
        DesktopTaskbar.clear()
        // 桌面端托盘气泡由系统自动超时消失, 无需主动取消 (no-op)
    }
}

/**
 * 任务栏进度指示: Windows 任务栏图标进度条 / macOS Dock 徽章 (java.awt.Taskbar 标准 API)。
 * 平台不支持的能力自动跳过, 不做任何私有协议适配。
 */
object DesktopTaskbar {

    private val taskbar: Taskbar? by lazy {
        runCatching {
            if (GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) null
            else Taskbar.getTaskbar()
        }.getOrNull()
    }

    fun show(progress: Int, max: Int) {
        if (max <= 0) return
        val percent = (progress.toLong() * 100 / max).toInt().coerceIn(0, 100)
        apply(percent, "$progress/$max")
    }

    fun clear() = apply(-1, null)

    private fun apply(percent: Int, badge: String?) = runCatching {
        val bar = taskbar ?: return@runCatching
        if (bar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)) {
            // Windows: 进度绑定到具体窗口
            mainWindow()?.let { bar.setWindowProgressValue(it, percent) }
        }
        if (bar.isSupported(Taskbar.Feature.PROGRESS_VALUE)) {
            bar.setProgressValue(percent)
        }
        if (bar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
            bar.setIconBadge(badge)
        }
    }

    private fun mainWindow(): Window? =
        Window.getWindows().firstOrNull { it.isDisplayable && it.isVisible }
}

/**
 * 桌面宿主启动早期注册 [NotificationProgress] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopNotificationProgress() {
    NotificationProgresses.register(DesktopNotificationProgress())
}
