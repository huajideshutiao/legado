package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 跨平台状态栏沉浸 padding。
 *
 * - Android: 走 [androidx.compose.foundation.layout.statusBarsPadding]
 * - 桌面 JVM / iOS / 鸿蒙: 无系统状态栏概念, 返回 this (无 padding)
 *
 * commonMain 侧的 Composable (如 AppTitleBar) 通过本函数获取状态栏 padding,
 * 避免 commonMain 直接依赖 Android 专属的 `Modifier.statusBarsPadding()`。
 */
expect fun Modifier.platformStatusBarPadding(): Modifier

/**
 * 跨平台导航栏 padding (返回 PaddingValues, 用于 LazyColumn contentPadding 等)。
 *
 * - Android: 走 `WindowInsets.navigationBars.asPaddingValues()` (避让手势导航栏)
 * - 桌面 JVM / iOS / 鸿蒙: 无系统导航栏概念, 返回 `PaddingValues(0)` (无 padding)
 *
 * commonMain 侧的 Composable (如 PreferenceScreen) 通过本函数获取默认
 * contentPadding, 避免 commonMain 直接依赖 Android 专属的
 * `WindowInsets.navigationBars.asPaddingValues()`。
 */
@Composable
expect fun rememberNavigationBarPaddingValues(): PaddingValues

// ---- 状态栏/导航栏显隐事件化 (对齐原版"配置驱动占位, 不逐帧跟随动画") ----
// 背景: 阅读/漫画页菜单显隐会触发系统状态栏显隐动画, 动画期间 insets 逐帧变化;
// 组合期直读或 windowInsetsPadding 会把作用域拖进逐帧重组/重排版 (原版用占位 View
// 由配置驱动 isGone, 动画期间布局零变化)。以下 API 只在"显隐翻转"边界写回状态,
// 动画期间布尔与高度恒定, 仅翻转时重排一次。

/** 状态栏当前是否隐藏 (事件性布尔, 动画期间不变, 非 Android 恒 false) */
@Composable
expect fun rememberStatusBarHidden(): Boolean

/** 导航栏当前是否隐藏 (事件性布尔, 动画期间不变, 非 Android 恒 false) */
@Composable
expect fun rememberNavigationBarHidden(): Boolean

/** 状态栏固定高度 px (首次组合采样, 配置变化时重采, 非 Android 恒 0) */
@Composable
expect fun rememberFixedStatusBarHeightPx(): Int

/** 导航栏固定高度 px (首次组合采样, 配置变化时重采, 非 Android 恒 0) */
@Composable
expect fun rememberFixedNavigationBarHeightPx(): Int

/**
 * 事件化状态栏 padding: 隐藏时 0, 显示时固定状态栏高; 显隐动画期间恒定,
 * 仅翻转时重排一次 (替换逐帧跟随的 platformStatusBarPadding, 对齐原版语义)。
 */
@Composable
fun Modifier.statusBarFixedPadding(): Modifier {
    val hidden = rememberStatusBarHidden()
    val heightPx = rememberFixedStatusBarHeightPx()
    val density = LocalDensity.current
    return if (hidden || heightPx <= 0) this
    else this.padding(top = with(density) { heightPx.toDp() })
}

/**
 * 事件化导航栏 padding (bottom): 隐藏时 0, 显示时固定导航栏高; 显隐动画期间恒定。
 */
@Composable
fun Modifier.navigationBarFixedPadding(): Modifier {
    val hidden = rememberNavigationBarHidden()
    val heightPx = rememberFixedNavigationBarHeightPx()
    val density = LocalDensity.current
    return if (hidden || heightPx <= 0) this
    else this.padding(bottom = with(density) { heightPx.toDp() })
}
