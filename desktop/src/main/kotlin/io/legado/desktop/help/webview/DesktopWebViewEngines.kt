package io.legado.desktop.help.webview

import com.sun.jna.Platform
import io.legado.app.constant.AppLog
import io.legado.desktop.help.webview.win.WebView2Runtime
import io.legado.desktop.help.webview.win.WindowsWebViewEngine

/**
 * 内嵌浏览器引擎的三级回退选择器。
 *
 * 1. **系统自带引擎** —— Windows 走 WebView2 Runtime (Win11 预装 / 装了 Edge 即有),
 *    零发行包体积增量;
 * 2. **兜底引擎** —— 系统引擎缺失时启用 [JavaFXWebViewEngine] (依赖已声明的
 *    `org.openjfx:javafx-web`, 无随包 native), 替代已归档的 KCEF 方案;
 * 3. **系统浏览器** —— 两级都不可用时 [get] 返回 null, 调用方回退 `Desktop.browse`,
 *    行为与接入本模块之前一致, 不崩。
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
        add(JavaFXWebViewEngine)
    }

    /**
     * 系统引擎缺失时给 UI 的引导信息 (null = 无引导可给)。
     * 模式参考 MpvDownloader/MpvInstallGuide: 先告诉用户装什么, 而不是静默降级。
     */
    fun installGuide(): InstallGuide? = when {
        Platform.isWindows() && WebView2Runtime.installedVersion() == null -> InstallGuide(
            message = "未检测到 Microsoft Edge WebView2 运行时, 书源的网页回源/登录/验证将回退到系统浏览器。" +
                "安装后重启应用即可启用内置浏览器。",
            downloadUrl = WebView2Runtime.BOOTSTRAPPER_URL,
        )

        else -> null
    }

    /** @param downloadUrl 可直接打开的下载地址, null 表示只能给文字指引 */
    class InstallGuide(val message: String, val downloadUrl: String?)
}
