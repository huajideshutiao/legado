package io.legado.app.ui.root

import io.legado.app.help.config.AppConfigProviders
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
    /**
     * 默认 [SoftInputPolicy.Resize]: 全 app 自己用 imePadding 做键盘避让, 窗口必须派发
     * ime insets (API<30 只有 adjustResize 下才有 ime inset), 且 adjustResize 下
     * contentInsets == visibleInsets, ViewRootImpl 的窗口平移闸门是关的 (见 ImeInsets.kt)。
     * 不用 [SoftInputPolicy.Default]: 它对应的 SOFT_INPUT_STATE_UNSPECIFIED 按 AOSP
     * Window.setSoftInputMode 契约会被整个丢弃, adjust 位停在上一页或厂商归一化结果
     * (实测 HyperOS 把 unspecified 归一成 adjustPan)。
     */
    val softInput: SoftInputPolicy = SoftInputPolicy.Resize,
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
        // 原版 VideoPlayActivity 继承 VMBaseActivity 默认 fullScreen=true:
        // 竖屏内容铺到透明状态栏之后、由标题栏按 inset 回避, 横屏切全屏时整体隐藏系统栏
        fullscreen = true,
        keepScreenOn = true,
        // 原版 VideoPlayActivity 不强制横屏, 由用户在播放页手动切换; 这里默认不锁方向
        orientation = OrientationPolicy.Unspecified,
        pictureInPicture = true
    )
    val AudioPlay = WindowPolicy(
        // 原版 AudioPlayActivity 继承 VMBaseActivity 默认 fullScreen=true:
        // 模糊封面背景铺满到透明状态栏之后, 标题栏按状态栏 inset 回避
        fullscreen = true,
        keepScreenOn = true
    )

    // 原版 BookInfoActivity 同样 fullScreen=true: 封面/模糊背景铺到状态栏之后, 页内自行回避
    val BookInfo = WindowPolicy(fullscreen = true)

    /** 根据 AppRoute 返回对应 WindowPolicy */
    fun forRoute(route: AppRoute): WindowPolicy = when (route) {
        is AppRoute.Reader -> Reader
        is AppRoute.MangaReader -> Manga
        is AppRoute.VideoPlay -> VideoPlayer
        is AppRoute.AudioPlay -> AudioPlay
        is AppRoute.BookInfo -> BookInfo
        // 其余页面无窗口特化: 编辑页/搜索/列表/WebView 曾各自声明 adjustResize, 现已是
        // 全局默认 (见 WindowPolicy.softInput), 逐页声明只剩重复
        else -> Default
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

/**
 * 阅读页屏幕方向策略：读 screenOrientation pref 映射 [OrientationPolicy]
 * (对照原版 `ReadBookActivity.setOrientation` 的 "0"~"4" 分支)：
 * "0"=跟随系统 "1"=竖向 "2"=横向 "3"=跟随传感器 "4"=反向竖屏。
 * 桌面端 setOrientation 为 no-op，策略值无副作用（设置项按 hasScreenOrientation 隐藏）。
 */
fun readerOrientationPolicy(): OrientationPolicy = when (AppConfigProviders.get().screenOrientation) {
    "1" -> OrientationPolicy.Portrait
    "2" -> OrientationPolicy.Landscape
    "3" -> OrientationPolicy.Sensor
    "4" -> OrientationPolicy.ReversePortrait
    else -> OrientationPolicy.Unspecified
}

/**
 * 阅读页常亮策略：由 keepLight pref 决定（对照原版 upScreenTimeOut 语义）——
 * "0"=跟随系统（不强制常亮，交还系统息屏）、"-1"=永不熄屏、"N"=常亮 N 秒。
 * Android 阅读页的窗口策略值不直接作用（MainActivity.applyWindowKeepScreenOn 在
 * 阅读页活动时改走 upScreenTimeOut 计时管理，避免 SYSTEM_UI 重应用互相覆盖）；
 * 桌面/移动端按策略直接驱动平台常亮（best-effort，无计时器则常亮到退出阅读页）。
 */
fun readerKeepScreenOnPolicy(): Boolean = AppConfigProviders.get().keepLight != "0"
