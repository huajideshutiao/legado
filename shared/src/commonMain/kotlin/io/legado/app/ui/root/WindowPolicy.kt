package io.legado.app.ui.root

import io.legado.app.help.config.ReadBookConfigProviders

/**
 * 路由窗口策略：描述每个 Route 进入时窗口应如何配置。
 * 平台入口（Android MainActivity / Desktop Window / iOS VC / OHOS Ability）
 * 观察当前 Route 的 WindowPolicy 统一操作窗口。
 */
data class WindowPolicy(
    val fullscreen: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientation: OrientationPolicy = OrientationPolicy.Unspecified,
    val systemBars: SystemBarsPolicy = SystemBarsPolicy.Default,
    val softInput: SoftInputPolicy = SoftInputPolicy.Default,
    val pictureInPicture: Boolean = false,
)

/** 路由 → WindowPolicy 映射。各 Route 自带默认策略，平台可覆盖。 */
object WindowPolicies {
    val Default = WindowPolicy()
    val Reader =
        WindowPolicy(fullscreen = true, keepScreenOn = true, systemBars = SystemBarsPolicy.Hidden)
    val Manga = WindowPolicy(
        fullscreen = true,
        keepScreenOn = true,
        orientation = OrientationPolicy.Portrait,
        systemBars = SystemBarsPolicy.Hidden
    )
    val VideoPlayer = WindowPolicy(
        keepScreenOn = true,
        // 原版 VideoPlayActivity 不强制横屏, 由用户在播放页手动切换; 这里默认不锁方向
        orientation = OrientationPolicy.Unspecified,
        pictureInPicture = true
    )
    val AudioPlay = WindowPolicy(keepScreenOn = true)
    val ReviewPost = WindowPolicy(softInput = SoftInputPolicy.Resize)

    // 编辑页文本域在页面底部, 固定 adjustResize: 避免 adjustUnspecified 对 Compose 层级
    // 判不可滚动而落 adjustPan, 弹键盘时整页(含标题栏)被顶起 (对照原 BookInfoEditActivity 可滚动布局→resize)
    val BookInfoEdit = WindowPolicy(softInput = SoftInputPolicy.Resize)
    val WebView = WindowPolicy()
    val Normal = WindowPolicy()

    /** 根据 AppRoute 返回对应 WindowPolicy */
    fun forRoute(route: AppRoute): WindowPolicy = when (route) {
        is AppRoute.Reader -> Reader
        is AppRoute.MangaReader -> Manga
        is AppRoute.VideoPlay -> VideoPlayer
        is AppRoute.AudioPlay -> AudioPlay
        is AppRoute.ReviewPost -> ReviewPost
        is AppRoute.BookInfoEdit -> BookInfoEdit
        is AppRoute.WebView -> WebView
        else -> Normal
    }
}

/**
 * 阅读页系统栏策略：对照原版 ReadBookActivity.upSystemUiVisibility 语义
 * (toolBarHide = 菜单未显示时)：
 * - 菜单显示时状态栏/导航栏一律显示
 * - 菜单隐藏时分别跟随 hideStatusBar / hideNavigationBar 配置（默认不隐藏）
 */
fun readerSystemBarsPolicy(menuVisible: Boolean): SystemBarsPolicy {
    val cfg = ReadBookConfigProviders.getOrNull()
    val hideStatus = cfg?.hideStatusBar == true
    val hideNav = cfg?.hideNavigationBar == true
    return when {
        menuVisible -> SystemBarsPolicy.Default
        hideStatus && hideNav -> SystemBarsPolicy.Hidden
        hideStatus -> SystemBarsPolicy.HiddenStatusBar
        hideNav -> SystemBarsPolicy.HiddenNavigationBar
        else -> SystemBarsPolicy.Default
    }
}
