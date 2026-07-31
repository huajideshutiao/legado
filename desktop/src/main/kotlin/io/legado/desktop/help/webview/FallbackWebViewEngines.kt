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
 * 因此当前 Linux 上 [isAvailable] 恒返回 false, 由 [DesktopWebViewEngines] 继续往下回退;
 * 待办: 用 JNA 绑 `webkit_web_view_new` / `webkit_web_view_evaluate_javascript` /
 * `webkit_website_data_manager_get_cookie_manager`, 并把 GTK 主循环放进独立线程。
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

/**
 * 次选引擎 (系统引擎缺失时的兜底): 首次使用时下载独立 Chromium 内核到应用数据目录。
 *
 * # 本次落地范围
 * 只落"接口分层 + 运行时探测 + 引导": 类路径上没有 CEF 时 [isAvailable] 返回 false,
 * [DesktopWebViewEngines] 直接回退系统浏览器, 不影响现有行为。**刻意不写 import**,
 * 全部反射探测 —— 依赖坐标尚未定, 且 CEF 类不上主编译类路径才不会因为装不上/装错版本而
 * 在启动期抛 NoClassDefFoundError。
 *
 * # 依赖选型 (与任务书拟定的 `dev.datlag:kcef` 不同, 详见交付报告)
 * KCEF 仓库已 **archived**, README 首行是 "usage of KCEF is not recommended";
 * 它拉的是整包 JetBrains Runtime (~268MiB) 而非 CEF natives, 且离屏渲染 issue #5
 * 自 2024-01 挂到归档都没修。改荐 `me.friwi:jcefmaven` (在维护, 无 Compose 依赖,
 * 传递依赖只有 commons-compress + gson, 支持把 natives 作为依赖预置以免运行时下载)。
 *
 * # 待办 (接依赖后)
 * 1. [isAvailable] 里把 [CEF_PROBE_CLASS] 探到的 CefApp 初始化状态接上;
 * 2. [fetch] 用 `CefRendering.OFFSCREEN` / `windowless_rendering_enabled` 建离屏 browser,
 *    JS 取值走 `CefMessageRouter` 回调 (jcefmaven 无现成 suspend 封装, 需自建 ~80 行桥);
 * 3. cookie 走 `CefCookieManager.getGlobalManager().visitUrlCookies`;
 * 4. 首次下载走 [installDir] + 进度回调, UI 复用 MpvDownloader 的引导+进度模式。
 */
internal object FallbackWebViewEngine : DesktopWebViewEngine {

    override val id: String get() = "cef"

    /** 探测类: 存在即说明 CEF 依赖已进类路径。 */
    private const val CEF_PROBE_CLASS = "org.cef.CefApp"

    /** 内核安装目录, 与 mpv 便携版同级 (`{filesDir}/cef`)。 */
    val installDir: File
        get() = File(io.legado.app.help.file.AppFilesDirs.get().filesDir, "cef")

    private val onClasspath: Boolean by lazy {
        runCatching { Class.forName(CEF_PROBE_CLASS, false, javaClass.classLoader) }.isSuccess
    }

    override fun isAvailable(): Boolean {
        if (!onClasspath) return false
        AppLog.put("检测到 CEF 依赖但兜底引擎尚未接线, 继续回退系统浏览器")
        return false
    }

    override suspend fun fetch(request: WebViewFetchRequest): WebViewFetchResult =
        throw NoStackTraceException("CEF 兜底引擎尚未接线")

    override fun openWindow(request: WebViewWindowRequest): WebViewWindowHandle? = null
}
