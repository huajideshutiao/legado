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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Platform
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.MainTab
import io.legado.app.ui.root.MainTabSwitcher
import io.legado.app.ui.root.PlatformServiceProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_brightness
import legado.shared.generated.resources.ic_daytime
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.JFrame

/**
 * 自绘窗口控制栏 (Windows/Linux 主窗口 undecorated 后接管标题栏职责; macOS 保留原生
 * 红绿灯标题栏, 不渲染本组件)。
 *
 * # 背景 (对照原版)
 * app 端无窗口概念; 桌面端此前用系统装饰标题栏 + DWM 着色 (WindowTitleBar.kt) 跟随
 * 主题, 但无法承载自定义菜单/置顶等窗口级能力。用户拍板: Windows 去掉系统
 * 装饰, Compose 自绘标题栏; macOS 保留原生。阅读页激活时系统标题栏染阅读背景色
 * ([readerWindowTint], DesktopReaderPlatformProvider.onEnter 维护) 的既有需求,
 * 由本控制栏直接消费同一状态源延续。
 *
 * # 能力与实现 (对应 CMP 1.10.1 官方 API, 已核对该版本 ui-desktop 源码)
 * - undecorated 窗口的**边缘调整大小**由 `WindowDecoration.Undecorated()` 官方内置
 *   (8dp 边缘 resizer, UndecoratedWindowResizer), 无需 WM_NCHITTEST hack;
 * - **拖动移动**: CMP 1.8 起移除 Modifier.windowDraggable(), 这里用 detectDragGestures
 *   + MouseInfo 绝对屏幕坐标 (官方 UndecoratedWindowResizer 同款取位方式), 按"按下时
 *   窗口位置 + 鼠标位移"计算目标位置, 无累积误差; 最大化状态按下即经 AWT
 *   setExtendedState(NORMAL) **同步**还原后重校准抓取偏移, 不跳变;
 * - **双击最大化/还原**: detectTapGestures(onDoubleTap) + WindowState.placement
 *   (1.10 起以 placement 取代 isMaximized; SwingWindow updater 应用时同步 AWT 状态,
 *   AWT 侧用户操作经 windowStateListener 回写 placement, 双向一致);
 * - **最小化/最大化/关闭**: WindowState.isMinimized / placement / onCloseRequest;
 * - **置顶**: Window(alwaysOnTop=) 官方参数, 状态存 [DesktopWindowChrome] 单一来源,
 *   Main.kt 窗口参数消费; 菜单显示勾选态;
 * - **全屏**: 复用 DesktopFullscreenController (F11 同路径), 状态由
 *   DesktopWindowController.setFullscreen 同步进 [DesktopWindowChrome.fullscreen],
 *   控制栏在全屏时隐藏;
 * - **深浅色切换**: 复用 DesktopThemeStoreProvider.updateDark (写持久层 + emit
 *   RECREATE → AppTheme 重组), 与设置页同源联动;
 * - **设置/退出**: navigator.push(AppRoute.MyConfig) (AppGlobalShortcuts 同款) /
 *   onCloseRequest (= Window 的 onCloseRequest = ::exitApplication, 与窗口关闭一致)。
 *
 * # 图标
 * Windows: Segoe MDL2 Assets 字体字符 (系统自带, 码点 uE921/uE922/uE923/uE8BB/uE712
 * 与 WebView2Toolbar 同款字体方案); 其他平台 (Linux): 通用 Unicode 符号
 * (−/□/▣/✕/⋯), 不引入新依赖。
 *
 * # 视觉
 * 高度 40dp (= AppTheme.DesignTokens.viewHeightLarge), 背景 = 阅读页染色 (激活时)
 * 或 AppTheme.colors.background, 前景按背景亮度反推 (textColorFor, 与 DWM 标题栏
 * 同语义); 按钮 hover 半透明叠色, 关闭按钮 hover 红色 (Windows 惯例)。
 */

/** 桌面端窗口级共享状态 (控制栏菜单 / F11 / 置顶开关的单一状态源, 防两处不同步)。 */
object DesktopWindowChrome {
    /** 窗口置顶 (Window(alwaysOnTop=) 消费; 会话内状态, 不持久化)。 */
    var alwaysOnTop by mutableStateOf(false)

    /** 真全屏 (DesktopWindowController.setFullscreen 同步; 全屏时控制栏隐藏)。 */
    var fullscreen by mutableStateOf(false)
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
    var menuExpanded by remember { mutableStateOf(false) }
    val maximized = windowState.placement == WindowPlacement.Maximized

    // undecorated 窗口 AWT 默认白底, 深色主题下启动首帧会闪白; 背景同步主题色
    // Win11 22H2+: 顺带声明系统圆角 (DWM), 无边框窗口不丢圆角; 真全屏时由
    // DesktopFullscreenController 切换为无圆角 (方角屏), 退出全屏恢复
    // 圆角决策统一走 shouldRoundWindowCorner (真全屏/最大化都去圆角),
    // 防止本控制栏重组把真全屏已去掉的圆角无条件加回
    SideEffect {
        window.background = java.awt.Color(bg.red, bg.green, bg.blue)
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
    }
    // 最大化时窗口铺满工作区 (贴边), 圆角与贴边冲突 (用户拍板 2026-08); 还原恢复圆角。
    // 跟随 placement 响应: AWT 侧用户操作 (snap/任务栏) 经 windowStateListener 回写
    // placement, 双向一致 (与 toggleMaximize 共用同一状态源)
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
        // ---- 应用图标 + 名称 (最左) ----
        icon?.let {
            Image(
                painter = it,
                contentDescription = null,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(18.dp),
            )
        }
        Text(
            text = appName,
            color = fg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
        // ---- 拖拽区 (拖动移动 + 双击最大化/还原) ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowDragger(window)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { toggleMaximize(windowState) })
                },
        )
        // ---- 深浅色切换按钮 (原版语义: 夜间→ic_daytime 太阳, 日间→ic_brightness; 点击切换) ----
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
        // ---- 菜单按钮 (⋯) + 下拉菜单 (右侧; 无分割线; 设置置底) ----
        Box {
            ChromeIconButton(
                icon = if (Platform.isWindows()) "\uE712" else "\u22EF", // More / ⋯
                fg = fg,
                darkBg = darkBg,
                fontSize = 16.sp,
                onClick = { menuExpanded = true },
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
        // ---- 窗口控制按钮 (Windows 惯例右侧) ----
        ChromeIconButton(
            icon = if (Platform.isWindows()) "\uE921" else "\u2212", // Minimize / −
            fg = fg,
            darkBg = darkBg,
            onClick = { windowState.isMinimized = true },
        )
        ChromeIconButton(
            icon = if (Platform.isWindows()) {
                if (maximized) "\uE923" else "\uE922" // Restore / Maximize
            } else {
                if (maximized) "\u25A3" else "\u25A1" // ▣ / □
            },
            fg = fg,
            darkBg = darkBg,
            onClick = { toggleMaximize(windowState) },
        )
        ChromeIconButton(
            icon = if (Platform.isWindows()) "\uE8BB" else "\u2715", // Close / ✕
            fg = fg,
            darkBg = darkBg,
            isClose = true,
            onClick = onCloseRequest,
        )
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
    val controller = PlatformServiceProviders.getOrNull()?.window ?: return
    controller.setFullscreen(!DesktopWindowChrome.fullscreen)
}

/**
 * 标题栏拖拽移动: 最大化状态按下先经 AWT setExtendedState 同步还原 (Windows 标题栏
 * 惯例), 再用"按下时窗口位置 + 鼠标绝对位移"跟随; 还原瞬间的窗口位置突变被按下时
 * 重校准吸收, 无跳变。禁用延迟/轮询, 纯事件驱动。
 */
private fun Modifier.windowDragger(window: ComposeWindow): Modifier = pointerInput(window) {
    var grabOffset: Point? = null
    detectDragGestures(
        onDragStart = {
            if ((window.extendedState and JFrame.MAXIMIZED_BOTH) != 0) {
                window.extendedState = JFrame.NORMAL
            }
            val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            grabOffset = Point(mouse.x - window.x, mouse.y - window.y)
        },
        onDrag = { change, _ ->
            change.consume()
            val offset = grabOffset ?: return@detectDragGestures
            val mouse = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            window.setLocation(mouse.x - offset.x, mouse.y - offset.y)
        },
        onDragEnd = { grabOffset = null },
        onDragCancel = { grabOffset = null },
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
            fontFamily = if (Platform.isWindows()) segoeMdl2 else null,
        )
    }
}

/** Windows: Segoe MDL2 Assets 系统字体 (WebView2Toolbar 同款); 其他平台默认字体。 */
private val segoeMdl2: FontFamily = FontFamily("Segoe MDL2 Assets")

/** hover 叠色: 深底白 20% / 浅底黑 ~7% (Win11 标题栏观感)。 */
private fun hoverColor(darkBg: Boolean, isClose: Boolean): Color = when {
    isClose -> if (darkBg) Color(0xFFD13438) else Color(0xFFE81123)
    darkBg -> Color(0x33FFFFFF)
    else -> Color(0x12000000)
}
