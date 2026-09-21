package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 软键盘避让与"聚焦处保持可见"的归属 (2026-08-22 定案, 依据 AOSP android15/16-release 与
 * foundation 1.11 源码, 并经真机 dumpsys 核实)。三处各有其主, 本文件不提供任何滚动辅助:
 *
 * 1. **窗口不许动**: manifest 声明 `windowSoftInputMode="adjustResize"` (对照原版逐 Activity
 *    的写法)。`InsetsState.calculateInsets` 下 contentInsets 与 visibleInsets 都含 ime,
 *    `ViewRootImpl.scrollToRectOrFocus` 的平移闸门 (`visibleInsets > contentInsets`) 才是
 *    关的。未声明时窗口停在 ADJUST_UNSPECIFIED (实测 HyperOS 归一成 adjustPan), 任何
 *    `View.requestRectangleOnScreen` 都会把整窗上移 `rect.bottom - (height - ime)`, 表现为
 *    键盘上方一条等高留白 + 标题栏被顶出屏外。**不能靠运行时 setSoftInputMode 兜**: 实测
 *    没生效; 也不能换 adjustNothing (API<30 的 ime inset 由 compat 从"resize 后的
 *    systemWindowInsets"反推, adjustNothing 下恒 0, imePadding 全废, minSdk 24)。
 * 2. **避让**: 页面根容器一处 `imePadding()`。`WindowInsets.ime` 由 foundation 的
 *    InsetsListener.onProgress 逐帧写动画插值, 底部键盘辅助条作为该容器最后一个子项,
 *    就严格贴在键盘上沿, 不需要任何额外补偿。
 * 3. **光标可见**: 滚动容器内建的 `ContentInViewNode.onRemeasured` —— 视口收缩且焦点 rect
 *    由完整可见变被裁时启动一次 spring 滚动, 每帧重算目标直到重新可见。新版
 *    `BasicTextField(state=)` 经 `applyFocusProperties` 把焦点 rect 报为光标/选区 rect,
 *    所以超高代码字段也只滚到光标行。
 *
 * 曾有两层自研补偿 (逐 16ms 瞬移滚动 + 动画结束再 bringIntoView; 以及吸收 bringIntoView
 * 防窗口平移) 都已删除: 前者与内建机制抢同一个 scroll mutex, 后者在闸门关掉后没有意义。
 */

/**
 * 软键盘是否可见 (事件性布尔, 非逐帧数值)。
 *
 * 键盘动画期间 `WindowInsets.ime` 每帧更新, 组合期直接读会把读取者拖进逐帧重组。
 * Android actual 经 ViewTreeObserver 布局监听 + ViewCompat 只读 ime 可见性布尔
 * (动画期间布尔不变), 只在翻转时写回 —— 作为事件信号 (LaunchedEffect key / 布局分支) 用。
 * 非 Android 平台无软键盘概念, 恒 false。
 */
@Composable
expect fun rememberImeVisible(): Boolean

/**
 * 是否处于 IME 动画期间 (事件性)。
 *
 * Android actual 经 imeAnimationSource != imeAnimationTarget 判定 (见 foundation
 * WindowInsets.android.kt 的 InsetsListener): 动画期间 source 冻结为动画前值, target 在
 * 动画第一帧即为最终值, 结束时两者收敛 —— 只在动画边界翻转。供动画期间冻结高亮挂载
 * 窗口重算等场景使用。非 Android 平台无 IME 动画概念, 恒 false。
 */
@Composable
expect fun rememberImeAnimating(): Boolean
