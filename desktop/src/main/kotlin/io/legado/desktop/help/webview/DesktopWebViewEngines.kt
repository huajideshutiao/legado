package io.legado.desktop.help.webview

import com.sun.jna.Platform
import io.legado.app.constant.AppLog
import io.legado.desktop.help.webview.DesktopWebViewEngines.get
import io.legado.desktop.help.webview.gtk.LinuxWebViewEngine
import io.legado.desktop.help.webview.mac.MacWebViewEngine
import io.legado.desktop.help.webview.win.WebView2Runtime
import io.legado.desktop.help.webview.win.WindowsWebViewEngine

/**
 * 内嵌浏览器引擎的按平台回退选择器。
 *
 * 每个平台都优先系统自带引擎 (零发行包体积增量):
 * 1. **Windows** —— WebView2 Runtime (Win11 预装 / 装了 Edge 即有), JNA+COM 直调;
 * 2. **Linux** —— webkit2gtk-4.1 (主流发行版预装), 专用 GTK 线程 + JNA 直绑;
 * 3. **macOS** —— WKWebView (系统框架), JNA + Objective-C 直绑;
 * 4. **无引擎** —— [get] 返回 null, 调用方回退 `Desktop.browse` 系统浏览器, 不崩。
 *
 * 历史上曾以 JavaFX WebView (OpenJFX 21 内嵌 2018 年 WebKit 606.1) 作跨平台兜底, 因内核
 * 过老达不到书源网页需求 (ES2017+ 缺失 / 无资源拦截 / cookie 反射 hack) 已移除, 不再有
 * 二级兜底 —— 系统引擎缺失时直接回退系统浏览器并给出安装引导。
 */
object DesktopWebViewEngines {

    @Volatile
    private var resolved: DesktopWebViewEngine? = null

    @Volatile
    private var probed = false

    /** 当前可用引擎; 全不可用返回 null (调用方回退系统浏览器)。 */
    @Synchronized
    fun get(forceRefresh: Boolean = false): DesktopWebViewEngine? {
        if (probed && !forceRefresh) return resolved
        resolved = candidates().firstOrNull { engine ->
            runCatching { engine.isAvailable() }
                .onFailure { AppLog.put("内嵌浏览器引擎 ${engine.id} 探测异常", it) }
                .getOrDefault(false)
        }
        probed = true
        AppLog.put("内嵌浏览器引擎: ${resolved?.id ?: "无 (回退系统浏览器)"}")
        return resolved
    }

    fun isAvailable(): Boolean = get() != null

    private fun candidates(): List<DesktopWebViewEngine> = buildList {
        if (Platform.isWindows()) add(WindowsWebViewEngine)
        if (Platform.isLinux()) add(LinuxWebViewEngine)
        if (Platform.isMac()) add(MacWebViewEngine)
    }

    /**
     * 系统引擎缺失时给 UI 的引导信息 (null = 无引导可给)。
     * 先告诉用户装什么, 而不是静默降级。
     */
    fun installGuide(): InstallGuide? = when {
        Platform.isWindows() && WebView2Runtime.installedVersion() == null -> InstallGuide(
            message = "未检测到 Microsoft Edge WebView2 运行时, 书源的网页回源/登录/验证将回退到系统浏览器。" +
                "安装后重启应用即可启用内置浏览器。",
            downloadUrl = WebView2Runtime.BOOTSTRAPPER_URL,
        )

        Platform.isLinux() && !LinuxWebViewEngine.runtimeInstalled() -> InstallGuide(
            message = "未检测到 WebKitGTK (libwebkit2gtk-4.1), 书源的网页回源/登录/验证将回退到系统浏览器。" +
                "安装后重启应用即可启用内置浏览器。",
            downloadUrl = null,
        )

        else -> null
    }

    /** @param downloadUrl 可直接打开的下载地址, null 表示只能给文字指引 */
    class InstallGuide(val message: String, val downloadUrl: String?)
}
