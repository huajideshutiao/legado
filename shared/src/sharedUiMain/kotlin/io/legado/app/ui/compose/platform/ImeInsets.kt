package io.legado.app.ui.compose.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 窗口是否需要应用侧自行消费 ime insets。
 *
 * Android actual: 设备 Android 15+ 时窗口被强制 edge-to-edge (targetSdk 35+),
 * adjustResize 不再收缩窗口, ime insets 全量派发 → true; 低版本窗口由系统 resize
 * 收缩 → false。非 Android 平台无软键盘/窗口收缩概念, 恒 false (ime insets 恒 0,
 * 消费与否均为 no-op)。
 */
expect fun shouldConsumeImeInsets(): Boolean

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
 *   bringIntoView —— 聚焦字段需在键盘弹出后自行请求。
 *
 * 滚动时机: 键盘弹出动画期间视口逐帧收缩 (宿主 [imeDismissPadding] 逐帧跟随 ime),
 * 此阶段**不发起动画滚动** —— 逐帧动画滚动会取消重开互相打断 (旧 while 循环实现在
 * 键盘弹出时表现为明显卡顿); 动画期间的光标保持可见由容器注册的瞬移滚动器
 * ([imeScrollNowFor] + CodeTextField.imeScrollNow / [imeFollowVisibleOnIme]) 承担,
 * 无动画滚动不打断。本 modifier 只做动画结束 (imeAnimating 翻转, 视口已稳定) 后的
 * 一次 bringIntoView 收尾 (幂等: 瞬移路径下已可见则不滚)。键盘收起后不请求。
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
    // 键盘弹出动画结束 (imeAnimating true→false 翻转) 时视口已稳定, 请求一次收尾;
    // 动画期间 imeAnimating=true 恒成立, key 不变不重跑, 不会逐帧请求
    val imeVisible = rememberImeVisible()
    val imeAnimating = rememberImeAnimating()
    LaunchedEffect(focused, imeVisible, imeAnimating) {
        if (focused && imeVisible && !imeAnimating) {
            requester.bringIntoView(latestRectProvider?.invoke())
        }
    }
    return this.then(Modifier.bringIntoViewRequester(requester))
}

/**
 * verticalScroll 容器的键盘动画瞬移滚动器: 接收窗口坐标 rect, 底部越出视口时以无动画
 * 滚动 ([ScrollState.scrollBy] 瞬时) 把目标保持在视口底部上方 —— 视口逐帧收缩时字段/
 * 光标行始终可见, 且无动画滚动互相打断 (动画滚动逐帧重启才会卡顿)。
 *
 * 与 [Modifier.imeFollowVisibleOnIme] (字段 bounds 侧) / CodeTextField.imeScrollNow
 * (光标行侧) 配合: 字段侧提供窗口 rect, 本滚动器消费。
 *
 * @param scrollState 容器滚动状态
 * @param containerWindowY 容器顶部窗口 Y (容器 Modifier.onGloballyPositioned 记录)
 * @param marginPx 目标距视口底的最小余量 (px)
 * @param scope 协程作用域 (scrollBy 挂起)
 */
fun imeScrollNowFor(
    scrollState: ScrollState,
    containerWindowY: () -> Int,
    marginPx: Int,
    scope: CoroutineScope,
): (Rect) -> Unit = { windowRect ->
    val inViewportBottom = windowRect.bottom.roundToInt() - containerWindowY()
    val viewportH = scrollState.viewportSize
    if (inViewportBottom > viewportH - marginPx) {
        scope.launch { scrollState.scrollBy((inViewportBottom - viewportH + marginPx).toFloat()) }
    }
}

/**
 * LazyColumn 容器的键盘动画瞬移滚动器 (verticalScroll 版见 [imeScrollNowFor]):
 * 窗口坐标 rect 底部越出视口时, 按 rect 所在 item 无动画 scrollToItem —— 视口逐帧
 * 收缩时聚焦字段/光标行始终可见, 无动画滚动不打断。
 *
 * @param listState 列表滚动状态
 * @param containerWindowY 列表容器顶部窗口 Y (容器 Modifier.onGloballyPositioned 记录)
 * @param marginPx 目标距视口底的最小余量 (px)
 * @param scope 协程作用域 (scrollToItem 挂起)
 */
fun imeScrollNowFor(
    listState: LazyListState,
    containerWindowY: () -> Int,
    marginPx: Int,
    scope: CoroutineScope,
): (Rect) -> Unit {
    val scroll: (Rect) -> Unit = { windowRect ->
        val info = listState.layoutInfo
        // 视口高度 (px): 用 endOffset - startOffset (FastScroll 已验证的 API), 避免
        // viewportSize (Size/Float) 在跨端源码集的类型差异
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val inViewportBottom = windowRect.bottom.roundToInt() - containerWindowY()
        if (inViewportBottom > viewportH - marginPx) {
            val first = info.visibleItemsInfo.firstOrNull()
            if (first != null) {
                // 视口顶的内容坐标: 首个可见 item 的列表坐标 - 其相对视口顶的滚动偏移
                val scrollPos = first.offset - listState.firstVisibleItemScrollOffset
                val contentY = inViewportBottom + scrollPos
                val item = info.visibleItemsInfo.firstOrNull { itemInfo ->
                    // size 是主轴像素 (Int, 非 IntSize): 见 foundation 1.10 LazyListItemInfo
                    itemInfo.offset <= contentY && contentY <= itemInfo.offset + itemInfo.size
                }
                if (item != null) {
                    val target = scrollPos + (inViewportBottom - viewportH + marginPx)
                    scope.launch { listState.scrollToItem(item.index, item.offset - target) }
                }
            }
        }
    }
    return scroll
}

/**
 * 键盘弹出动画期间保持聚焦字段可见 (瞬移跟随视口收缩, 无动画不打断)。
 *
 * 视口逐帧收缩时滚动容器若用动画滚动逐帧跟随会取消重开互相打断 (表现为卡顿); 本
 * modifier 把字段窗口 bounds 逐帧交给容器注册的瞬移滚动器 ([imeScrollNowFor]), 字段
 * 始终可见。仅字段 bounds 级跟随 (无光标行精度), 适合单行/矮字段 (替换规则/书籍信息
 * 编辑); 长代码字段用 CodeTextField.imeScrollNow 的光标行级跟随。
 * 容器未注册滚动器 (scrollNow = null) 时为 no-op, 兜底由 bringIntoViewOnIme 承担。
 *
 * @param focused 本字段是否持焦 (仅聚焦字段在键盘弹出时跟随)
 * @param scrollNow 容器瞬移滚动器 (窗口坐标 rect → 无动画滚动到可见), 可空
 */
@Composable
fun Modifier.imeFollowVisibleOnIme(
    focused: Boolean,
    scrollNow: ((Rect) -> Unit)?,
): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val imeVisible = rememberImeVisible()
    val imeAnimating = rememberImeAnimating()
    val hiding = rememberImeHiding()
    LaunchedEffect(focused, imeVisible, imeAnimating, hiding, scrollNow) {
        // 仅键盘弹出动画期间跟随: 收起动画视口扩张, 字段不会越界
        if (!focused || !imeVisible || hiding || scrollNow == null) return@LaunchedEffect
        while (imeAnimating) {
            val c = coords ?: break
            scrollNow(windowBounds(c))
            delay(16)
        }
        // 动画结束收尾一次 (视口已稳定)
        coords?.let { scrollNow(windowBounds(it)) }
    }
    return this.then(Modifier.onGloballyPositioned { coords = it })
}

private fun windowBounds(coords: LayoutCoordinates): Rect {
    val pos = coords.positionInWindow()
    return Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
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
 * ime 避让 padding: 键盘弹出动画期间逐帧跟随 ime insets (内容区平滑收缩, 与键盘滑入
 * 动画同步, 无跳变无空白), 收起动画期间立即归零 (对齐原版 KeyboardToolPop.onGlobalLayout
 * 键盘收起时 rootView padding 立刻归 0 不等动画)。
 *
 * Android 15+ (targetSdk 35+ 强制 edge-to-edge) 窗口 frame 不随 IME 收缩, ime insets
 * 全量派发, 逐帧跟随是唯一避让路径 (对齐原版 onApplyWindowInsets 逐帧更新
 * initialPadding + onGlobalLayout setPadding 的平滑语义); Android 14- 窗口由系统
 * adjustResize 收缩 (系统动画同样平滑), 再消费 insets 会双重避让产生键盘上方空白 →
 * [shouldConsumeImeInsets] 为 false 时 no-op。非 Android 平台 ime 恒 0, 天然 no-op。
 *
 * 与事件化实现 (动画第一帧瞬间垫满最终键盘高) 的差异: 事件化在键盘滑入前内容已跳到
 * 最终位置, 视觉为整页跳变 (上抬) + 键盘上方空白; 逐帧跟随与键盘动画同步, 无跳变。
 */
@Composable
fun Modifier.imeDismissPadding(): Modifier {
    // 低版本窗口由系统 resize 收缩 (平滑), 无需也不应再消费 ime insets (双重避让)
    if (!shouldConsumeImeInsets()) return this
    val hiding = rememberImeHiding()
    // hiding 时 ime 归零动作已在系统动画第一帧生效 (source > target), 返回原 modifier;
    // 否则逐帧跟随 ime 动画值 (insets 值变化由该 modifier 的 measure 机制驱动重测)
    return if (hiding) this else this.windowInsetsPadding(WindowInsets.ime)
}
