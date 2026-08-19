package io.legado.app.ui.compose.platform

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口顶部被平台装饰占用、Compose 覆盖物 (菜单/划词条/补全条) 不该进入的高度。
 *
 * 桌面端 Windows 的窗口控制条由 native (legado_wndchrome) 画在一个 z-order 恒在
 * Compose 画布之上的 layered 子窗口里, 而 CMP 的 Popup 与主窗口共用同一块画布 ——
 * 覆盖物一旦落进那条区域就被盖住, 只能靠定位时主动避让。
 *
 * 默认 0.dp: 移动端无此问题, 桌面端由 Main.kt 按平台/全屏态注入。
 */
val LocalOverlayTopInset = compositionLocalOf { 0.dp }

/** 覆盖物与 [LocalOverlayTopInset] 之间的视觉留白。 */
val OverlayInsetGap: Dp = 8.dp
