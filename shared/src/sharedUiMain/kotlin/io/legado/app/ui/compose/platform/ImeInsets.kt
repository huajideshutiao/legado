package io.legado.app.ui.compose.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

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
    val latestRectProvider by rememberUpdatedState(rectProvider)
    // 事件性信号 (非逐帧数值, 见 rememberImeVisible/rememberImeAnimating):
    // 键盘弹出动画期间视口逐帧收缩 (宿主 windowInsetsPadding 逐帧跟随时), 持续请求
    // 直至动画结束; 动画结束后请求一次收尾。宿主已事件化 (imeDismissPadding) 时视口
    // 在动画第一帧即稳定, 循环请求幂等无副作用。键盘收起后不请求。
    val imeVisible = rememberImeVisible()
    val imeAnimating = rememberImeAnimating()
    LaunchedEffect(focused, imeVisible, imeAnimating) {
        if (focused && imeVisible) {
            while (imeAnimating) {
                requester.bringIntoView(latestRectProvider?.invoke())
                delay(16)
            }
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
 * 软键盘是否可见 (事件性布尔, 非逐帧数值)。
 *
 * 键盘弹出/收起动画期间 `WindowInsets.ime` 每帧更新 (androidx InsetsListener.onProgress
 * 逐帧写动画插值): 组合期直接读取会把读取者拖进逐帧重组。Android actual 经
 * ViewTreeObserver 布局监听 + ViewCompat 只读 ime 可见性布尔 (动画期间布尔不变),
 * 只在"可见/不可见"翻转时写回状态 —— 作为事件信号 (LaunchedEffect key / 布局分支判定) 使用。
 * 非 Android 平台无软键盘概念, 恒 false。
 */
@Composable
expect fun rememberImeVisible(): Boolean

/**
 * 是否处于 IME 动画期间 (事件性)。
 *
 * Android actual 经 imeAnimationSource != imeAnimationTarget 判定 (见 foundation
 * WindowInsets.android.kt 的 InsetsListener): 动画期间 source 冻结为动画前值, target
 * 在动画第一帧即置为最终目标值, 两者不相等; 动画结束两者收敛 —— 该状态只在动画边界
 * 翻转, 不随逐帧插值变化。供键盘动画期间冻结高亮挂载窗口等场景使用。
 * 非 Android 平台无 IME 动画概念, 恒 false。
 */
@Composable
expect fun rememberImeAnimating(): Boolean

/**
 * IME 目标高度 (px, 事件性): 键盘弹出动画第一帧即确定最终键盘高, 动画期间恒定;
 * 无动画时 = 当前 ime 高度。供 [imeDismissPadding] 做固定高度 padding (只在动画边界
 * 重算), 避免逐帧跟随动画插值导致整屏每帧重测。非 Android 平台 ime 恒 0。
 */
@Composable
expect fun rememberImeTargetBottomPx(): Int

/**
 * ime 避让 padding: 键盘弹出时立即垫上最终键盘高 (动画第一帧 target 即确定, 动画期间
 * 恒定), 收起动画期间立即归零 (对齐原版 KeyboardToolPop.onGlobalLayout 键盘收起时
 * rootView padding 立刻归 0 不等动画)。
 *
 * 消除收起动画期间的底部空隙: ime insets 动画与键盘视觉动画存在不同步窗口 (键盘视觉
 * 已滑出屏幕而 insets 尚未归零) 时, 内容区底 = 屏幕底 - ime, 工具栏底边下方暴露
 * ime 高度的一段窗口背景空白; padding 提前归零后内容区立即拉满, 工具栏贴屏幕底,
 * 空隙不再出现。非 Android 平台 ime 恒 0, 天然 no-op。
 *
 * 与旧实现 (windowInsetsPadding 逐帧跟随动画插值) 的差异: IME 动画期间 insets 每帧
 * 变化, 旧实现整屏每帧重测; 本实现 padding 只在动画边界 (事件性) 重算一次 ——
 * 键盘弹出: imeAnimationTarget 在动画第一帧即置为最终键盘高 → 立即垫满;
 * 键盘收起: source > target (rememberImeHiding) → 立即归零。
 */
@Composable
fun Modifier.imeDismissPadding(): Modifier {
    val density = LocalDensity.current
    val hiding = rememberImeHiding()
    // 动画目标高度: 弹出动画期间恒定 (= 最终键盘高), 无动画时 = 当前 ime 高度
    val imeHeight = rememberImeTargetBottomPx()
    // hiding 时 ime 归零动作已在系统动画第一帧生效 (source > target), 返回原 modifier;
    // 否则以固定高度 padding (只在动画边界重算, 动画期间不再逐帧重测整屏)
    return if (hiding || imeHeight <= 0) this
    else this.padding(bottom = with(density) { imeHeight.toDp() })
}
