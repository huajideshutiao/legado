@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package io.legado.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppForegroundState
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.MainTab
import io.legado.app.ui.root.MainTabSwitcher
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.desktop.help.DesktopKeepAwake
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_brightness
import legado.ui.generated.resources.ic_daytime
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.JFrame

/**
 * 窗口控制栏 (**Linux 专用**)。
 *
 * - **Linux**: undecorated 窗口, 自绘全功能控制栏 (手写拖拽 + 双击最大化 + 自绘三键)。
 * - **Windows**: 整条控制条改由 native (legado_wndchrome) 在鼠标穿透的 layered 子窗口里绘制,
 *   命中测试全在 native WndProc, 不再走本组件 (见 DesktopNativeChromeHost / cpp/wndchrome)。
 * - **macOS**: 原生红绿灯标题栏, 不渲染本组件 (Main.kt 分支)。
 *
 * # 背景 (对照原版)
 * app 端无窗口概念; 桌面端此前用系统装饰标题栏 + DWM 着色 (WindowTitleBar.kt) 跟随主题, 但无法承载
 * 自定义菜单/置顶等窗口级能力。用户拍板: 去掉系统装饰, 自绘标题栏; macOS 保留原生。
 * 阅读页激活时标题栏染阅读背景色 ([readerWindowTint], DesktopReaderPlatformProvider.onEnter 维护)。
 * 2026-08 Windows 侧从 JBR CustomTitleBar 迁到自研 native 桥 (去 JBR 运行时依赖 + 三键可跟随任意
 * 主题色 + 消掉 rightInset/placement 那批 workaround), 本组件随之只服务 Linux。
 *
 * # 高度
 * 40dp (= AppTheme.DesignTokens.viewHeightLarge, 阅读页长按菜单锚点依赖, 不可改)。
 *
 * # 图标
 * 通用 Unicode 符号 (−/□/▣/✕/⋯), 不引入新依赖。
 */

/** 桌面端窗口级共享状态 (控制栏菜单 / F11 / 置顶开关 / 窗口显隐的单一状态源, 防两处不同步)。 */
object DesktopWindowChrome {
    /** 窗口置顶 (Window(alwaysOnTop=) 消费; 会话内状态, 不持久化)。 */
    var alwaysOnTop by mutableStateOf(false)

    /** 真全屏 (DesktopWindowController.setFullscreen 同步; 全屏时控制栏隐藏)。 */
    var fullscreen by mutableStateOf(false)

    /**
     * 窗口是否对用户可见 (Window(visible=) 消费)。
     *
     * 关闭到托盘后窗口只隐藏不销毁 (Compose 的 visible=false 保留界面内部状态与原生资源),
     * 恢复入口有托盘左键单击与二次启动前置两条, 都必须经 [hideToTray]/[restoreWindow] 改本状态
     * —— 只直改 AWT 可见性会让 Window(visible=) 的入参与实际可见性脱钩: 状态仍是 false
     * 时再点关闭按钮不产生变化, 窗口就关不掉了。
     *
     * 两条恢复入口在本状态已置 true 之后会额外立即置一次 AWT 可见性: Compose 应用
     * visible 排在后续 EDT 任务, 不先落实的话紧跟着的 toFront/requestFocus 对仍隐藏的窗口
     * 无效 (同向重复应用, 不与本状态分叉)。
     */
    var windowVisible by mutableStateOf(false)

    /**
     * 窗口是否被用户收进托盘 (托盘图标常驻与菜单恢复项的判据)。
     *
     * 与 [windowVisible] 同源同改: 单看可见性无法区分"启动前尚未显示"与"用户主动收起",
     * 前者不应让托盘图标常驻。
     */
    var hiddenToTray by mutableStateOf(false)

    /** 收起主窗口: 界面隐藏, 界面状态与后台任务继续 (托盘图标转为常驻)。 */
    fun hideToTray() {
        hiddenToTray = true
        windowVisible = false
        // 程序化隐藏不产生窗口事件 (AWT 在 Java 层只发 COMPONENT_HIDDEN), 前后台必须在这里同步
        applyForeground(false)
    }

    /** 恢复主窗口 (托盘左键/菜单项、二次启动前置共用)。 */
    fun restoreWindow() {
        hiddenToTray = false
        windowVisible = true
        applyForeground(true)
    }

    /** 最小化 / 还原 (窗口离开或回到屏幕): 与收进托盘同属窗口不可见, 前后台随之翻转。 */
    fun onIconifiedChanged(iconified: Boolean) {
        applyForeground(!iconified)
    }

    /**
     * 同步 app 前后台: 判据是"窗口是否可见"。
     *
     * 收进托盘与最小化都是窗口离开屏幕 = 退到后台; 只是丢焦点 (别的应用抢走焦点而本窗口仍在
     * 屏幕上) 不改变前后台 —— 桌面窗口能与其他应用同屏并存, 拿失活当后台会让阅读/朗读/视频
     * 在用户只是切去浏览器时被挂起。屏幕常亮同步该判据: 窗口不可见就不该压着系统不熄屏。
     */
    private fun applyForeground(foreground: Boolean) {
        AppForegroundState.set(foreground)
        DesktopKeepAwake.setWindowVisible(foreground)
    }

    /**
     * 主界面退出请求 (由 Main 组装时注入)。
     *
     * 供共享层的"再按一次退出程序"使用: 桌面端该手势的语义是退到后台 (对齐原版 Android
     * 双击返回 finish 主界面、服务与通知继续), 而不是结束进程 —— 真退出入口只有托盘菜单
     * 的"退出"。未注入时调用方不做任何动作。
     */
    @Volatile
    var interfaceExitRequest: (() -> Unit)? = null
}

@Composable
fun DesktopTitleBar(
    appName: String,
    icon: Painter?,
    window: ComposeWindow,
    windowState: WindowState,
    themeStore: DesktopThemeStoreProvider,
    navigator: AppNavigator,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    // 阅读页激活时染阅读背景色 (延续原系统标题栏染色需求, 同一状态源)
    val bg = readerWindowTint.value ?: colors.background
    val fg = textColorFor(bg)
    val darkBg = bg.luminance() < 0.5f
    val maximized = windowState.placement == WindowPlacement.Maximized

    // AWT 默认白底, 深色主题下启动首帧会闪白; 背景同步主题色。
    // undecorated 窗口顺带声明系统圆角 (DWM 不画无边框窗口圆角, 显式恢复);
    // 真全屏时由 DesktopFullscreenController 切换为无圆角, 退出恢复。
    SideEffect {
        window.background = java.awt.Color(bg.red, bg.green, bg.blue)
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
    }
    // 圆角跟随 placement (AWT 侧用户操作经 windowStateListener 回写 placement)
    LaunchedEffect(windowState.placement) {
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppTheme.DesignTokens.viewHeightLarge)
            .background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleBarLeftGroup(icon = icon, appName = appName, fg = fg)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowDragger(window)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { toggleMaximize(windowState) })
                },
        )
            TitleBarActionButtons(
                themeStore = themeStore,
                fg = fg,
                darkBg = darkBg,
                navigator = navigator,
            )
            // 自绘三键 (Linux)
            ChromeIconButton(
                icon = "\u2212", // Minimize / −
                fg = fg,
                darkBg = darkBg,
                onClick = { windowState.isMinimized = true },
            )
            ChromeIconButton(
                icon = if (maximized) "\u25A3" else "\u25A1", // ▣ / □
                fg = fg,
                darkBg = darkBg,
                onClick = { toggleMaximize(windowState) },
            )
            ChromeIconButton(
                icon = "\u2715", // Close / ✕
                fg = fg,
                darkBg = darkBg,
                isClose = true,
                onClick = onCloseRequest,
            )
    }
}

/** 左侧组: 应用图标 + 名称 (Windows/Linux 共用)。 */
@Composable
private fun TitleBarLeftGroup(
    icon: Painter?,
    appName: String,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Image(
                painter = it,
                contentDescription = null,
                modifier = Modifier.padding(start = DesignTokens.spacingMd, end = DesignTokens.spacingDefault).size(18.dp),
            )
        }
        Text(
            text = appName,
            color = fg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = DesignTokens.spacingXs),
        )
    }
}

/** 右侧组: 深浅色切换 + ⋯菜单 (无边框/置顶/设置; Windows/Linux 共用)。 */
@Composable
private fun TitleBarActionButtons(
    themeStore: DesktopThemeStoreProvider,
    fg: Color,
    darkBg: Boolean,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // ---- 深浅色切换按钮 (原版语义: 夜间→ic_daytime 太阳, 日间→ic_brightness) ----
        val themeIcon =
            if (themeStore.isDark) Res.drawable.ic_daytime else Res.drawable.ic_brightness
        val themeInteraction = remember { MutableInteractionSource() }
        val themeHovered by themeInteraction.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(46.dp, AppTheme.DesignTokens.viewHeightLarge)
                .background(if (themeHovered) hoverColor(darkBg, false) else Color.Transparent)
                .clickable(interactionSource = themeInteraction, indication = null) {
                    themeStore.updateDark(!themeStore.isDark)
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(themeIcon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(fg),
            )
        }
        // ---- 菜单按钮 (⋯) + 下拉菜单 ----
        Box {
            ChromeIconButton(
                icon = "\u22EF", // More / ⋯
                fg = fg,
                darkBg = darkBg,
                fontSize = 16.sp,
                onClick = { menuExpanded = !menuExpanded },
            )
            AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                // 无边框 (真全屏, 与 F11 同路径)
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    toggleFullscreen()
                }) {
                    Text(if (DesktopWindowChrome.fullscreen) "✓ 无边框" else "无边框")
                }
                // 窗口置顶
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    DesktopWindowChrome.alwaysOnTop = !DesktopWindowChrome.alwaysOnTop
                }) {
                    Text(if (DesktopWindowChrome.alwaysOnTop) "✓ 窗口置顶" else "窗口置顶")
                }
                // 设置 (置底; 主页在栈顶时切"我的"tab, 其他页面 push 设置页)
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    val top = navigator.backStack.value.lastOrNull()?.route
                    if (top is AppRoute.Main) {
                        MainTabSwitcher.switchTo(MainTab.MY)
                    } else {
                        navigator.push(AppRoute.MyConfig)
                    }
                }) {
                    Text("设置")
                }
            }
        }
    }
}

/** 最大化/还原切换 (真全屏时控制栏隐藏不可达, 无冲突)。 */
private fun toggleMaximize(windowState: WindowState) {
    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
        WindowPlacement.Floating
    } else {
        WindowPlacement.Maximized
    }
}

/** 全屏切换: 与 F11 同路径 (PlatformServices.window → DesktopFullscreenController)。 */
private fun toggleFullscreen() {
    PlatformServiceProviders.get().window.setFullscreen(!DesktopWindowChrome.fullscreen)
}

/**
 * 标题栏拖拽移动 (X11 优先交还 WM, 见 [LinuxWmMoveResize]; 交接失败才走下面的手写跟随):
 *
 * 原手写实现: 最大化状态按下先经 AWT setExtendedState 同步还原 (Windows 标题栏
 * 惯例), 再用"按下时窗口位置 + 鼠标绝对位移"跟随; 还原瞬间的窗口位置突变被按下时
 * 重校准吸收, 无跳变。禁用延迟/轮询, 纯事件驱动。
 */
private fun Modifier.windowDragger(window: ComposeWindow): Modifier = pointerInput(window) {
    var grabOffset: Point? = null
    var wmHandled = false
    detectDragGestures(
        onDragStart = {
            if ((window.extendedState and JFrame.MAXIMIZED_BOTH) != 0) {
                window.extendedState = JFrame.NORMAL
            }
            // 优先把拖动交还窗口管理器 (X11 _NET_WM_MOVERESIZE): 手写 setLocation 的方式 WM
            // 完全不知情, 丢掉贴靠/平铺/拖动平滑。交接成功后整个过程由 WM 接管, 本地不再跟随。
            wmHandled = LinuxWmMoveResize.startMove(window)
            if (wmHandled) return@detectDragGestures
            val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            grabOffset = Point(mouse.x - window.x, mouse.y - window.y)
        },
        onDrag = { change, _ ->
            if (wmHandled) return@detectDragGestures
            change.consume()
            val offset = grabOffset ?: return@detectDragGestures
            val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            window.setLocation(mouse.x - offset.x, mouse.y - offset.y)
        },
        onDragEnd = { grabOffset = null; wmHandled = false },
        onDragCancel = { grabOffset = null; wmHandled = false },
    )
}

/** 控制栏图标按钮: hover 半透明叠色, 关闭按钮 hover 红色 (Windows 惯例)。 */
@Composable
private fun ChromeIconButton(
    icon: String,
    fg: Color,
    darkBg: Boolean,
    isClose: Boolean = false,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(46.dp, AppTheme.DesignTokens.viewHeightLarge)
            .background(if (hovered) hoverColor(darkBg, isClose) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            color = if (hovered && isClose) Color.White else fg,
            fontSize = fontSize,
        )
    }
}

/** hover 叠色: 深底白 20% / 浅底黑 ~7% (Win11 标题栏观感)。 */
private fun hoverColor(darkBg: Boolean, isClose: Boolean): Color = when {
    isClose -> if (darkBg) Color(0xFFD13438) else Color(0xFFE81123)
    darkBg -> Color(0x33FFFFFF)
    else -> Color(0x12000000)
}
