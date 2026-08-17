package io.legado.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.MainTab
import io.legado.app.ui.root.MainTabSwitcher
import io.legado.app.ui.root.PlatformServiceProviders
import kotlinx.coroutines.delay
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_brightness
import legado.shared.generated.resources.ic_daytime
import org.jetbrains.compose.resources.painterResource
import java.awt.EventQueue
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Windows 原生控制条 (legado_wndchrome) 的 Compose 侧宿主。
 *
 * 控制条整条 (图标/应用名/深浅色键/⋯键/最小化-最大化-关闭) 由 native 在一个鼠标穿透的
 * layered 子窗口里绘制, 命中测试全在 native 的 WndProc 完成 —— 所以本组件**不渲染任何可见内容**,
 * 只负责三件事:
 *  1. 挂载 native 桥并把主题色/标题/图标/高度推给它 (状态源与旧 DesktopTitleBar 一致, 含阅读页染色)
 *  2. 承接 native 的按键回调 (深浅色切换 / ⋯菜单)
 *  3. 用 Compose 弹 ⋯菜单 (原生 Win32 菜单不吃深色主题; AppDropdownMenu 是独立 Popup 窗口,
 *     不在主窗口 HWND 上, 与 native 控制条无冲突)
 *
 * # 挂载时机 (踩过的坑)
 * CMP 在组合期还没 `setVisible`, 此时 `window.isDisplayable == false`, 拿不到 HWND ⇒ attach 必失败。
 * 所以这里轮询等窗口 realize (沿用既有做法, 最多约 3s), 挂上之后再把
 * 标题/主题/高度/图标**重推一遍** —— 首次组合时的推送发生在 attach 之前, 会全部落空。
 */
@Composable
fun DesktopNativeChromeHost(
    appName: String,
    window: ComposeWindow,
    themeStore: DesktopThemeStoreProvider,
    navigator: AppNavigator,
) {
    val colors = AppTheme.colors
    // 阅读页激活时染阅读背景色 (与旧 DesktopTitleBar 同一状态源, 语义不变)
    val bg = readerWindowTint.value ?: colors.background
    val fg = textColorFor(bg)
    val dark = bg.luminance() < 0.5f
    val density = LocalDensity.current
    val captionPx = (AppTheme.DesignTokens.viewHeightLarge.value * density.density).roundToInt()

    // ⋯菜单锚点: native 回调给出按钮左下角 (客户区相对物理像素)
    var menuAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var attached by remember { mutableStateOf(false) }

    LaunchedEffect(window) {
        repeat(30) {
            if (window.isDisplayable) {
                attached = DesktopWindowChromeNative.attach(
                    window = window,
                    // 回调在 AWT-Windows 线程 (native WndProc) 触发, 必须投递到 EDT 再动 Compose 状态
                    onThemeToggle = { _, _ ->
                        EventQueue.invokeLater { themeStore.updateDark(!themeStore.isDark) }
                    },
                    onMenu = { x, y ->
                        EventQueue.invokeLater { menuAnchor = x to y }
                    },
                    // 标题栏是 native 地盘, 鼠标事件不进 Compose ⇒ ⋯菜单靠点击 dismiss 的机制在
                    // 标题栏上失效 (拖标题栏时菜单不关, 用户实测)。由 native 主动通知来关。
                    onCaptionPress = {
                        EventQueue.invokeLater { menuAnchor = null }
                    },
                )
                return@LaunchedEffect
            }
            delay(100)
        }
        AppLog.put("窗口控制条: 窗口约 3s 内未 realize, 放弃挂载")
    }

    // AWT 默认白底, 深色主题下启动首帧会闪白; 背景同步主题色 (延续旧实现)
    SideEffect {
        window.background = java.awt.Color(bg.red, bg.green, bg.blue)
    }

    // 以下推送一律把 attached 作为 key: attach 晚于首次组合, 挂上后必须重推
    LaunchedEffect(attached, bg, fg, dark) {
        if (attached) DesktopWindowChromeNative.setTheme(bg.toArgb(), fg.toArgb(), dark)
    }
    LaunchedEffect(attached, appName) {
        if (attached) DesktopWindowChromeNative.setTitle(appName)
    }
    LaunchedEffect(attached, captionPx) {
        if (attached) DesktopWindowChromeNative.setCaptionHeightPx(captionPx)
    }

    // 应用图标: 与窗口图标同源 (desktop/src/main/resources/icon.png)
    val appIcon = remember {
        runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }
        }.getOrNull()
    }
    LaunchedEffect(attached, appIcon) {
        if (attached) DesktopWindowChromeNative.setBitmap(0, appIcon)
    }

    // 深浅色切换图标: 原版语义 (夜间→ic_daytime 太阳, 日间→ic_brightness), 复用同一批矢量资源。
    // 矢量图无法直接喂 native, 按当前 DPI 栅格化成白色蒙版, 由 native 侧按 fg 着色
    // (栅格化成白色而非 fg: 主题色变化时不必重新栅格化, 只有 DPI 变化才需要)。
    val themePainter = painterResource(
        if (themeStore.isDark) Res.drawable.ic_daytime else Res.drawable.ic_brightness
    )
    val themeIconPx = (20 * density.density).roundToInt()
    val themeIcon = remember(themePainter, themeIconPx) {
        runCatching { themePainter.rasterizeWhite(themeIconPx, density) }.getOrNull()
    }
    LaunchedEffect(attached, themeIcon) {
        if (attached) DesktopWindowChromeNative.setBitmap(1, themeIcon)
    }

    menuAnchor?.let { (x, y) ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset((x / density.density).dp, (y / density.density).dp)
                    .size(1.dp)
            ) {
                AppDropdownMenu(expanded = true, onDismissRequest = { menuAnchor = null }) {
                    DropdownMenuItem(onClick = {
                        menuAnchor = null
                        PlatformServiceProviders.getOrNull()?.window
                            ?.setFullscreen(!DesktopWindowChrome.fullscreen)
                    }) {
                        Text(if (DesktopWindowChrome.fullscreen) "✓ 无边框" else "无边框")
                    }
                    DropdownMenuItem(onClick = {
                        menuAnchor = null
                        DesktopWindowChrome.alwaysOnTop = !DesktopWindowChrome.alwaysOnTop
                    }) {
                        Text(if (DesktopWindowChrome.alwaysOnTop) "✓ 窗口置顶" else "窗口置顶")
                    }
                    DropdownMenuItem(onClick = {
                        menuAnchor = null
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
}

/**
 * 控制条在 Compose 侧的占位。
 *
 * 不能用裸 `Spacer`: 它什么都不画, 那块区域露出的是 Skia 清屏色, 未必等于主题底色 ——
 * 一旦 native 控制条底边与本占位顶边差一个取整像素 (125% 等分数缩放下会), 就露出一条异色发丝
 * (用户实测)。这里显式涂**与 native 完全同一个颜色源**的底色, 于是即便有残余一行也看不出来;
 * native 侧另有 1px 重叠 (见 wndchrome.c reposition) 双保险。
 */
@Composable
fun ChromeStripSpacer(modifier: Modifier = Modifier) {
    val bg = readerWindowTint.value ?: AppTheme.colors.background
    Box(
        modifier
            .fillMaxWidth()
            .height(AppTheme.DesignTokens.viewHeightLarge)
            .background(bg)
    )
}

/** 矢量 Painter → 白色蒙版 BufferedImage (native 侧再按前景色着色)。 */
private fun Painter.rasterizeWhite(sizePx: Int, density: Density): BufferedImage {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val target = Size(sizePx.toFloat(), sizePx.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), target) {
        draw(target, colorFilter = ColorFilter.tint(Color.White))
    }
    return bitmap.toAwtImage()
}
