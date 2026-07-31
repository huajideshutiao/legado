package io.legado.desktop.help.webview

import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import java.io.File

/**
 * Linux 系统引擎 (webkit2gtk) 占位。
 *
 * 探测已落地 ([isAvailable] 扫常见 so 路径), **会话实现尚未落地**: webkit2gtk 必须跑在
 * GTK 主循环上, 而本进程的主循环归 AWT/Skiko, 二者共存需要单独起 gtk 线程并处理
 * `gtk_init` 与 X11/Wayland 的线程亲和, 风险与工作量都远高于 Windows 的 WebView2 直调
 * (WebView2 可以完全跑在自建的 Win32 消息泵线程上, 与 AWT 零交集)。
 *
 * 因此当前 Linux 上 [isAvailable] 恒返回 false, 由 [DesktopWebViewEngines] 继续往下回退
 * 到 [JavaFXWebViewEngine] (依赖已声明的 javafx-web, 无需再引 GTK)。
 */
internal object LinuxWebViewEngine : DesktopWebViewEngine {

    override val id: String get() = "webkit2gtk"

    private val candidates = listOf(
        "/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.1.so.0",
        "/usr/lib/x86_64-linux-gnu/libwebkit2gtk-4.0.so.37",
        "/usr/lib64/libwebkit2gtk-4.1.so.0",
        "/usr/lib64/libwebkit2gtk-4.0.so.37",
        "/usr/lib/libwebkit2gtk-4.1.so.0",
    )

    /** 系统是否装了 webkit2gtk (仅供 [DesktopWebViewEngines.installGuide] 判断该不该提示)。 */
    fun runtimeInstalled(): Boolean = candidates.any { File(it).exists() }

    override fun isAvailable(): Boolean = false

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult =
        throw NoStackTraceException("Linux webkit2gtk 引擎尚未实现")

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? = null
}
