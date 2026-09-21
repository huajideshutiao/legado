package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 对话框窗口属性构造桥: common 的 DialogProperties expect 只有 3 参数
 * (dismissOnBackPress / dismissOnClickOutside / usePlatformDefaultWidth),
 * decorFitsSystemWindows 是 Android 专属 (androidx ui-android 的 8 参构造器),
 * desktop/iOS/鸿蒙 的 actual 签名各不相同且无此参数, 经 expect/actual 按平台构造。
 *
 * decorFitsSystemWindows=false (仅 Android 有意义):
 * - 窗口 edge-to-edge (FLAG_LAYOUT_IN_SCREEN + fitInsets=0), 主题切到带
 *   backgroundDimEnabled 的 FloatingDialogWindowTheme
 * - softInputMode 由 compose 接管 (S+ = ADJUST_NOTHING, ≤R = ADJUST_RESIZE)
 * - DialogLayout.insetValue 不再丢弃 insets, ime insets 全量派发给内容
 *   (decor=true 时直接 return unchangedValue, imePadding 是 no-op)
 * 代价: 内容要自行避让系统栏 ([bottomSheetBottomInsets])。默认 true = 现行为,
 * 仅底部输入面板 (ReviewPost) 显式传 false。
 */
expect fun platformDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    decorFitsSystemWindows: Boolean = true,
): DialogProperties

/**
 * 底部避让 insets (底部弹层面板 / 编辑页根容器共用)。
 *
 * - Android (decor=false 弹窗) / iOS / 鸿蒙: ime ∪ navigationBars (逐边 max) —— 键盘
 *   弹出时 ime 与导航栏 insets 并存, max 让面板底边恰顶在键盘顶 (不多避让); 键盘收起
 *   时 ime=0 剩导航栏 (iOS 为底部安全区), 面板不贴物理屏底被黑条/home 指示条压住
 * - desktop: 仅 ime (无系统栏概念, navigationBars 恒 0, 取并集等价)
 */
@Composable
expect fun bottomSheetBottomInsets(): WindowInsets
