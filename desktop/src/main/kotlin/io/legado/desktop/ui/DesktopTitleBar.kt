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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.jetbrains.WindowDecorations.CustomTitleBar
import com.sun.jna.Platform
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.MainTab
import io.legado.app.ui.root.MainTabSwitcher
import io.legado.app.ui.root.PlatformServiceProviders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_brightness
import legado.shared.generated.resources.ic_daytime
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.JFrame

/**
 * 窗口控制栏。
 *
 * - **Windows** ([nativeSystemButtons] = true): 窗口保持系统装饰 (decorated), 经 JBR
 *   CustomTitleBar API (WindowDecorations.setCustomTitleBar) 把客户区顶到窗口顶端,
 *   标题栏整条由 Compose 绘制; 最小化/最大化/关闭三键由 JBR 原生绘制 (controls.dark
 *   跟随背景亮度), 拖拽/双击最大化/贴靠/Snap Layouts 全部原生 (空白区 forceHitTest(false),
 *   按钮区 forceHitTest(true)); 深浅色切换 + ⋯菜单 (置顶/设置/无边框) 保留在本行。
 *   右侧按 titleBar.rightInset 让出原生按钮区。
 * - **Linux** ([nativeSystemButtons] = false): undecorated 窗口, 自绘全功能控制栏
 *   (手写拖拽 + 双击最大化 + 自绘三键), 保持原有实现不动。
 * - **macOS**: 原生红绿灯标题栏, 不渲染本组件 (Main.kt 分支)。
 *
 * # 背景 (对照原版)
 * app 端无窗口概念; 桌面端此前用系统装饰标题栏 + DWM 着色 (WindowTitleBar.kt) 跟随
 * 主题, 但无法承载自定义菜单/置顶等窗口级能力。用户拍板: Windows 去掉系统
 * 装饰, Compose 自绘标题栏; macOS 保留原生。阅读页激活时系统标题栏染阅读背景色
 * ([readerWindowTint], DesktopReaderPlatformProvider.onEnter 维护) 的既有需求,
 * 由本控制栏直接消费同一状态源延续。
 * 2026-08 用户拍板改回原生控制栏: Windows 走 JBR CustomTitleBar (原生三键+原生拖拽
 * +Compose 内容共存, 对照 JetBrains IDE/jewel/ab-download-manager 同款方案,
 * 探针已实证 AWT 层 wndproc 拦截无法实现同等效果)。
 *
 * # 高度
 * 40dp (= AppTheme.DesignTokens.viewHeightLarge, 阅读页长按菜单锚点依赖, 不可改)。
 *
 * # 图标
 * Windows: Segoe MDL2 Assets 字体字符 (系统自带, 码点 uE921/uE922/uE923/uE8BB/uE712
 * 与 WebView2Toolbar 同款字体方案); 其他平台 (Linux): 通用 Unicode 符号
 * (−/□/▣/✕/⋯), 不引入新依赖。
 */

/** 桌面端窗口级共享状态 (控制栏菜单 / F11 / 置顶开关的单一状态源, 防两处不同步)。 */
object DesktopWindowChrome {
    /** 窗口置顶 (Window(alwaysOnTop=) 消费; 会话内状态, 不持久化)。 */
    var alwaysOnTop by mutableStateOf(false)

    /** 真全屏 (DesktopWindowController.setFullscreen 同步; 全屏时控制栏隐藏)。 */
    var fullscreen by mutableStateOf(false)

    /**
     * 主窗口系统按钮区让位宽度 (JBR rightInset, 逻辑px): Main.kt 在
     * setCustomTitleBar 生效后写入触发重组 —— 首帧为 0 时自绘按钮会与系统三键
     * 重叠且无重组源 (2026-08-13 用户实测), 单靠组件内 SideEffect 刷新不可靠。
     */
    var titleBarRightInset by mutableStateOf(0f)
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
    /** Windows=true: 原生三键+原生拖拽 (JBR CustomTitleBar); false: Linux 自绘全功能。 */
    nativeSystemButtons: Boolean = false,
    /** JBR 自定义标题栏 (Main.kt 创建; Windows 非空, 其他平台 null)。 */
    customTitleBar: CustomTitleBar? = null,
) {
    val colors = AppTheme.colors
    // 阅读页激活时染阅读背景色 (延续原系统标题栏染色需求, 同一状态源)
    val bg = readerWindowTint.value ?: colors.background
    val fg = textColorFor(bg)
    val darkBg = bg.luminance() < 0.5f
    val maximized = windowState.placement == WindowPlacement.Maximized

    // AWT 默认白底, 深色主题下启动首帧会闪白; 背景同步主题色。
    // Windows (decorated+JBR): 客户区顶到窗口顶, 标题栏区由 Compose 画, 同样需要背景同步;
    //   圆角由系统自动处理 (标准窗口 Win11 默认圆角, 最大化自动去角), 不手动设 DWM 圆角。
    // Linux (undecorated): 顺带声明系统圆角 (DWM 不画无边框窗口圆角, 显式恢复);
    //   真全屏时由 DesktopFullscreenController 切换为无圆角, 退出恢复。
    SideEffect {
        window.background = java.awt.Color(bg.red, bg.green, bg.blue)
        if (!nativeSystemButtons) {
            applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
        }
        customTitleBar?.let { bar ->
            // 高度传逻辑单位 (Dp.value), 勿 toPx (JBR 原生侧自乘 scale, 高分屏双倍缩放坑)
            bar.height = AppTheme.DesignTokens.viewHeightLarge.value
            bar.putProperty("controls.dark", darkBg)
            // rightInset 变化 (set 生效/最大化切换) 时刷新全局状态
            val ri = bar.rightInset
            if (ri != DesktopWindowChrome.titleBarRightInset) {
                DesktopWindowChrome.titleBarRightInset = ri
            }
        }
    }
    // Linux 圆角跟随 placement (AWT 侧用户操作经 windowStateListener 回写 placement)
    LaunchedEffect(windowState.placement) {
        if (!nativeSystemButtons) {
            applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
        }
    }

    if (nativeSystemButtons && customTitleBar != null) {
        // Windows JBR: Box 层叠布局 —— 右侧按钮组绝对定位并按 rightInset 让位,
        // 窗口任意宽度下都不会溢出到系统三键区 (Row 固定宽溢出曾致"调整尺寸时
        // 自绘按钮跟不上系统三键", 用户实测 2026-08-13)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(AppTheme.DesignTokens.viewHeightLarge)
                .background(bg)
                .jbrHitTestRouter(customTitleBar),
        ) {
            // 底层: 拖拽空白区 (事件未消费 → forceHitTest(false) 原生拖拽/双击)
            Box(Modifier.fillMaxSize())
            // 左侧: 图标 + 名称 (Box 默认 TopStart 对齐曾致贴顶, 显式 CenterStart 恢复居中原状;
            // 2026-08-13 晚用户报"图标与名称太靠顶部")
            TitleBarLeftGroup(
                icon = icon,
                appName = appName,
                fg = fg,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            // 右侧: 深浅色 + ⋯ (绝对右对齐, 右缘按 rightInset+8dp 让位, 永不溢出)
            TitleBarActionButtons(
                themeStore = themeStore,
                fg = fg,
                darkBg = darkBg,
                navigator = navigator,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = (DesktopWindowChrome.titleBarRightInset + 8f).dp),
            )
        }
    } else {
        // Linux: 原 Row 结构 (自绘全功能)
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
    }
}

/**
 * JBR 命中路由 (Windows, jewel TitleBar.Windows.kt 同款机制):
 * 逐指针事件声明标题栏命中语义——事件未被消费 (空白/拖拽区) → forceHitTest(false)
 * (原生拖拽/双击最大化/贴靠); 事件被消费 (按钮上, Press 进入直到 Release) →
 * forceHitTest(true) (客户区交互, Compose 处理点击)。
 */
private fun Modifier.jbrHitTestRouter(titleBar: CustomTitleBar): Modifier =
    pointerInput(titleBar) {
        val currentContext = currentCoroutineContext()
        awaitPointerEventScope {
            var inUserControl = false
            while (currentContext.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach {
                    if (!it.isConsumed && !inUserControl) {
                        titleBar.forceHitTest(false)
                    } else {
                        if (event.type == PointerEventType.Press) {
                            inUserControl = true
                        }
                        if (event.type == PointerEventType.Release) {
                            inUserControl = false
                        }
                        titleBar.forceHitTest(true)
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
