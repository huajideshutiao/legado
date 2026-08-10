package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity

/**
 * 软键盘弹出时把聚焦字段重新滚进视口。
 *
 * 背景 (2026-08 核查定案, 依据 AOSP android15-release 源码):
 * - targetSdk 35+ 的窗口被强制 edge-to-edge (PhoneWindow.setDecorFitsSystemWindows 在
 *   mEdgeToEdgeEnforced 时直接 return, mDecorFitsSystemWindows 强制为 false), 窗口 frame
 *   不随 IME 收缩, ime insets 全量派发 (View.computeSystemWindowInsets 在 content listener
 *   为 null 时原样返回) —— 布局按 ime insets 加 padding 是**单份且必要**的避让;
 * - 因此键盘弹出后 LazyColumn/滚动容器视口变矮但滚动位置保持, 底部聚焦字段会进入
 *   键盘覆盖区;
 * - 点击获焦时 CoreTextField 只会请求一次 bringIntoView (onFocusChanged 触发), 且
 *   TextFieldCoreModifier 明确"容器尺寸变化时不 bringIntoView", IME 弹出本身不触发任何
 *   bringIntoView —— 聚焦字段需在 ime 弹出期间持续重新请求直至稳定。
 *
 * desktop/iOS/鸿蒙 上 ime inset 恒为 0 (desktop 无软键盘 inset 概念), 此 modifier 为 no-op。
 *
 * @param focused 本字段是否持焦 (仅聚焦字段在键盘弹出时滚动自身)
 * @param rectProvider 光标行 rect 提供者 (字段局部坐标, 可空): 为空时按整个字段 bounds 请求,
 *   适合单行/矮字段; 长字段应提供光标行 rect 并上下扩几行容错, 避免整个字段被顶飞
 */
@Composable
fun Modifier.bringIntoViewOnIme(
    focused: Boolean,
    rectProvider: (() -> Rect?)? = null,
): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    // ime 弹出动画期间高度逐帧变化, 每帧重启请求直至稳定; 键盘收起后不请求
    val imeBottom = WindowInsets.ime.getBottom(density)
    val latestRectProvider by rememberUpdatedState(rectProvider)
    LaunchedEffect(focused, imeBottom) {
        if (focused && imeBottom > 0) {
            requester.bringIntoView(latestRectProvider?.invoke())
        }
    }
    return this.then(Modifier.bringIntoViewRequester(requester))
}

/**
 * 当前是否处于 IME 收起动画中。
 *
 * Android actual 经 foundation-layout 的 imeAnimationSource/imeAnimationTarget 判定
 * (收起动画期间 source=动画前键盘高 > target=0); 非 Android 平台无 IME 动画概念,
 * 恒 false。
 */
@Composable
expect fun rememberImeHiding(): Boolean

/**
 * ime 避让 padding: 键盘弹出动画期间跟随 ime (工具栏随键盘升起, 时机不变),
 * 收起动画期间立即归零 (对齐原版 KeyboardToolPop.onGlobalLayout 键盘收起时 rootView
 * padding 立刻归 0 不等动画)。
 *
 * 消除收起动画期间的底部空隙: ime insets 动画与键盘视觉动画存在不同步窗口 (键盘视觉
 * 已滑出屏幕而 insets 尚未归零) 时, 内容区底 = 屏幕底 - ime, 工具栏底边下方暴露
 * ime 高度的一段窗口背景空白; padding 提前归零后内容区立即拉满, 工具栏贴屏幕底,
 * 空隙不再出现。非 Android 平台 ime 恒 0, 天然 no-op。
 */
@Composable
fun Modifier.imeDismissPadding(): Modifier {
    val hiding = rememberImeHiding()
    // hiding 时 ime 归零动作已在系统动画第一帧生效 (source > target), 返回原 modifier;
    // 否则与 windowInsetsPadding(WindowInsets.ime) 同语义 (insets 值变化由该 modifier
    // 的 measure 机制驱动重测, 无需组合期读取)
    return if (hiding) this else this.windowInsetsPadding(WindowInsets.ime)
}
