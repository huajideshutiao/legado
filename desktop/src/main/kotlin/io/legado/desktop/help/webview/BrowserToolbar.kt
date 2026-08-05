package io.legado.desktop.help.webview

/**
 * 桌面端浏览器窗口工具栏动作 (三端共用, 2026-08-06 抽象防遗漏)。
 *
 * 行为契约 (三端必须一致, 曾因各写一套反复遗漏):
 * - 按钮集合: 返回/前进/刷新 + 溢出菜单 + 确定(仅验证/登录模式) + 收藏/朗读/分享/登录
 *   (仅 RSS 模式, 2026-08-07); **无关闭按钮** (原生窗口标题栏自带 ✕),
 *   **无标题文字** (标题只同步 OS 窗口标题栏);
 * - 菜单按钮右对齐窗口右缘, 左组按钮靠左 (2026-08-07 用户拍板);
 * - 返回语义: 有历史时历史后退, 无历史时关闭窗口 (对照原版 toolbar 返回 = finish);
 * - 菜单项 (对照原版 WebViewRoute 菜单): 浏览器打开/拷贝 URL (**无刷新** ——
 *   工具栏已有刷新按钮, 2026-08-07 用户拍板) + **禁用源/删除源**
 *   (sourceKey 非空时显示, 2026-08-08 补齐原版 web_view.xml 菜单);
 * - 按钮均为图标 (Windows: Segoe MDL2 Assets 字体字符 / GTK: 图标名 / macOS: 符号字符)。
 */
enum class ToolbarAction {
    BACK, FORWARD, REFRESH, COPY_URL, OPEN_IN_BROWSER, FULL_SCREEN, MENU, OK, CLOSE,

    // 书源菜单 (对照原版 web_view.xml 禁用源/删除源; 仅 sourceKey 非空时显示,
    // 动作由引擎分发: 禁用直接执行成功关窗, 删除先弹确认再执行, 2026-08-08)
    DISABLE_SOURCE, DELETE_SOURCE,

    // RSS 模式按钮 (2026-08-07: RSS 阅读去页面外壳, 功能移入窗口工具栏)
    STAR_TOGGLE, READ_ALOUD, SHARE, LOGIN,
}

/**
 * 浏览器窗口工具栏抽象 (Win32 / GTK / AppKit 三端实现)。
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

    /** RSS 收藏态 (星图标实心/空心), 仅 RSS 模式按钮存在时生效。 */
    fun setStarred(starred: Boolean) {}

    /**
     * 释放控件引用 (窗口销毁时由引擎调用, 2026-08-07): 销毁后队列里残留的
     * setStarred 等状态任务见 disposed 标志直接跳过, 防悬垂句柄 (对齐 Windows 实现)。
     */
    fun dispose() {}
}
