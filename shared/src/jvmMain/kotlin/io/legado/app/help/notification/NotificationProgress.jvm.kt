package io.legado.app.help.notification

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * [NotificationProgress] 的桌面 JVM actual 实现。
 *
 * 用 [java.awt.SystemTray] + [TrayIcon] 显示进度通知, 无 SystemTray 时退化为
 * println 到 stdout。
 *
 * # 设计要点
 * - 桌面端通知无"持久显示 + 进度条"概念, showProgress 用 TrayIcon.displayMessage
 *   显示 "[progress/max] content" 文本, 每次调用覆盖上一条
 * - 无 SystemTray (无头模式 / 无托盘) 时 println 到 stdout
 * - cancel 为 no-op: 桌面端 SystemTray 消息由系统自动超时消失, 无需主动取消
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopNotificationProgress : NotificationProgress {

    /** 系统托盘是否可用 (无头模式 / 无托盘时 false)。 */
    private val trayAvailable: Boolean by lazy {
        !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
    }

    /** 单例 TrayIcon, 懒加载。 */
    private val trayIcon: TrayIcon? by lazy {
        if (!trayAvailable) return@lazy null
        try {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color.ORANGE
            graphics.fillOval(0, 0, 16, 16)
            graphics.dispose()
            val icon = TrayIcon(image, "legado-progress")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            icon
        } catch (_: Exception) {
            null
        }
    }

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        // 拼接进度文本: "content (progress/max)" 或 "content"
        val progressText = if (max > 0) {
            "$content ($progress/$max)"
        } else {
            content
        }
        val icon = trayIcon
        if (icon != null) {
            try {
                icon.displayMessage(title, progressText, TrayIcon.MessageType.INFO)
                return
            } catch (_: Exception) {
                // 落到 stdout
            }
        }
        // stdout 兜底 (无头模式 / 无 SystemTray)
        println("[progress] $title | $progressText")
    }

    override fun cancel() {
        // 桌面端 SystemTray 消息由系统自动超时消失, 无需主动取消 (no-op)
        // TrayIcon 无 removeMessage API, 仅能 remove 整个 icon (会影响下次显示)
    }
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
