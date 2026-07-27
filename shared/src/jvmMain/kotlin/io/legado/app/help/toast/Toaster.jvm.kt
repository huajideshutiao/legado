package io.legado.app.help.toast

import java.awt.AWTException
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * [Toaster] 的桌面 JVM actual 实现。
 *
 * 用 [java.awt.SystemTray] + [TrayIcon] 显示通知, 无 SystemTray 时 (无头模式 /
 * 无系统托盘) 退化为 println 到 stdout。
 *
 * # 设计要点
 * - 调用线程不限: `SystemTray.displayMessage` 内部线程安全, 可在任意线程调用
 * - TrayIcon 单例懒加载: 首次显示时尝试添加, 失败则走 stdout 兜底
 * - 消息类型映射: 桌面端无"短/长"概念, 都走 `TrayIcon.MessageType.INFO`;
 *   toastLong 仅在 stdout 模式下加 `[LONG]` 前缀区分
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` (app 端 help/media/)。
 */
class DesktopToaster : Toaster {

    /** 系统托盘是否可用 (无头模式 / 无托盘时 false)。 */
    private val trayAvailable: Boolean by lazy {
        !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
    }

    /** 单例 TrayIcon, 懒加载 (首次显示通知时添加到 SystemTray)。 */
    private val trayIcon: TrayIcon? by lazy {
        if (!trayAvailable) return@lazy null
        try {
            // 用 16x16 透明 BufferedImage 作图标 (无资源依赖)
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color.BLUE
            graphics.fillOval(0, 0, 16, 16)
            graphics.dispose()
            val icon = TrayIcon(image, "legado")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            icon
        } catch (_: AWTException) {
            // 添加失败 (如图标不支持), 退化为 stdout
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun toast(message: String) {
        showMessage(message, isLong = false)
    }

    override fun toastLong(message: String) {
        showMessage(message, isLong = true)
    }

    /** 显示消息: 优先 SystemTray, 退化到 stdout。 */
    private fun showMessage(message: String, isLong: Boolean) {
        val icon = trayIcon
        if (icon != null) {
            try {
                icon.displayMessage(
                    "legado",
                    message,
                    TrayIcon.MessageType.INFO
                )
                return
            } catch (_: Exception) {
                // 显示失败, 落到 stdout
            }
        }
        // stdout 兜底 (无头模式 / 无 SystemTray)
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
