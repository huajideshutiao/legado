package io.legado.desktop.help.webview

/**
 * 桌面端浏览器窗口工具栏动作 (三端共用, 2026-08-06 抽象防遗漏)。
 *
 * 行为契约 (三端必须一致, 曾因各写一套反复遗漏):
 * - 按钮集合: 返回/前进/刷新 + 溢出菜单 + 确定(仅验证模式); **无关闭按钮**
 *   (原生窗口标题栏自带 ✕), **无标题文字** (标题只同步 OS 窗口标题栏);
 * - 返回语义: 有历史时历史后退, 无历史时关闭窗口 (对照原版 toolbar 返回 = finish);
 * - 菜单项 (对照原版 WebViewRoute 菜单): 刷新/浏览器打开/拷贝 URL/全屏。
 */
enum class ToolbarAction {
    BACK, FORWARD, REFRESH, COPY_URL, OPEN_IN_BROWSER, FULL_SCREEN, MENU, OK, CLOSE
}

/**
 * 浏览器窗口工具栏抽象 (Win32 自绘 / GTK 控件 / AppKit 控件三端实现)。
 *
 * 状态方法任意线程可调 (实现内部负责线程归队); [onAction] 在平台 UI 线程回调。
 */
interface BrowserToolbar {

    /** 按钮/菜单项动作回调 (平台 UI 线程)。 */
    var onAction: ((ToolbarAction) -> Unit)?

    /** 返回/前进可用态。 */
    fun setCanNavigate(back: Boolean, forward: Boolean)

    /** 加载态 (进度条显示/隐藏)。 */
    fun setLoading(value: Boolean)

    /** 当前工具栏总高度 (px); 溢出菜单展开时 Windows 实现动态增高, 其余固定。 */
    fun layoutHeight(): Int = 0
}
