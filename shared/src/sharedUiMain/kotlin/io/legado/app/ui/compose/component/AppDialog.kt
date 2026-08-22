package io.legado.app.ui.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.BackLayerHandler
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.LocalTransitionFrozenStatusBarHeightPx
import io.legado.app.ui.compose.platform.PlatformDialogDim
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.platform.rememberVisibleStatusBarHeightPx
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.toComposeEasing
import io.legado.app.utils.ScreenInfoProviders
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 底部弹层拖拽禁区: 内容区里"竖直手势归内部"的区域, 目前用于平台 WebView 这类 interop 视图。
 *
 * interop 视图 (Android AndroidView / iOS UIKitView) 不参与 Compose 的手势竞争:
 * Compose 侧一旦消费了位移, `PointerInteropFilter` 就给视图补发 ACTION_CANCEL 并掐断本次
 * 手势流 (Final pass 的 stopDispatching), 网页从此滚不动。所以弹层拖拽无法靠"抢先消费"
 * 让位, 只能整轮不参与竞争。
 *
 * 实例由 [AppBottomSheetDialog] 提供, 内容侧用 [sheetDragExclusion] 登记自身边界; 按下
 * 落点命中禁区时弹层放弃本轮手势 (全程不消费), 手势原样留给内部视图。顶栏等未登记区域
 * 照旧可下拉关闭。
 */
class SheetDragExclusion internal constructor() {

    /** 已登记区域 (window 坐标), 只在手势按下时读一次, 无需快照状态。 */
    private val zones = mutableMapOf<Any, Rect>()

    internal fun put(token: Any, bounds: Rect) {
        zones[token] = bounds
    }

    internal fun remove(token: Any) {
        zones.remove(token)
    }

    internal fun contains(windowPosition: Offset): Boolean =
        zones.values.any { it.contains(windowPosition) }
}

/** 当前底部弹层的拖拽禁区 (见 [SheetDragExclusion]), 不在弹层内时为 null。 */
val LocalSheetDragExclusion = staticCompositionLocalOf<SheetDragExclusion?> { null }

/** 把本节点登记为底部弹层的拖拽禁区 (见 [SheetDragExclusion]), 不在弹层内时为空操作。 */
@Composable
fun Modifier.sheetDragExclusion(): Modifier {
    val exclusion = LocalSheetDragExclusion.current ?: return this
    val token = remember { Any() }
    DisposableEffect(exclusion, token) {
        onDispose { exclusion.remove(token) }
    }
    return onGloballyPositioned { exclusion.put(token, it.boundsInWindow()) }
}

/**
 * 请求收起当前底部弹层: 播完退出动画再真正关闭 (等价于返回键/点击弹层外), 不在弹层内时为 null。
 *
 * 内容侧自己拦了返回键 (如半屏浏览器要先做网页后退/退出全屏) 但最终仍要关闭弹层时用它 ——
 * 直接调外部传入的关闭回调会把退出动画砍掉。
 */
val LocalSheetDismissRequest = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 对话框统一窗口, 带 app 版 Animation.Dialog 动画: 进入 decelerate 中心缩放
 * (系统 dialog_enter.xml: 200ms scale 0.96→1 + 淡入), 退出 accelerate 淡出
 * (系统 dialog_exit.xml: 150ms); 时长/插值器读平台动画 spec (Android 端动态读系统动画缩放),
 * E-Ink 模式跳过动画 (对齐 app 版 windowAnimations = 0)。
 *
 * onDismissRequest 先播退出动画再真正关闭: 平台 Dialog 一旦组合移除立即消失,
 * 直接回调会把退出动画砍掉。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = AppDialogSizes.properties(),
    /** 背景暗化: 原版 BaseDialogFragment 默认保留 dim, 个别对话框 (PaddingConfigDialog) 清 FLAG_DIM_BEHIND */
    dim: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 顶层覆盖物返回拦截: 对话框打开期间返回键 (桌面端 ESC / 统一链) 优先关闭对话框,
    // 不落到页面/出栈 (对齐原版系统 Dialog 消费 BACK 的层级语义)。非 E-Ink 走 dismissing
    // 播退出动画, E-Ink 无动画直接关闭 (对齐 E-Ink 分支 Dialog 直连 onDismissRequest)。
    var dismissing by remember { mutableStateOf(false) }
    BackLayerHandler(enabled = true) {
        if (AppConfigProviders.get().isEInkMode) onDismissRequest() else dismissing = true
    }
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = { content() },
        )
        return
    }
    // 对话框动画平台 spec (时长/插值器按平台对话框转场语义, Android 动态读系统动画时长缩放)
    val dialogSpec = remember { PlatformCapabilityProviders.get().dialogTransitionSpec }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        // Android 补平台 dim 0.6 (对齐桌面/iOS 0.6 scrim); E-Ink 分支在上方已跳过 (对齐原版 E-Ink 清 dim)
        if (dim) PlatformDialogDim()
        val progress = remember { Animatable(0f) }
        // 进入: 缩放 enterScaleFrom→1 + 淡入 (对齐系统 dialog_enter.xml, 参数读平台 spec)
        LaunchedEffect(Unit) {
            progress.animateTo(
                1f,
                tween(
                    durationMillis = dialogSpec.enterDurationMillis,
                    easing = dialogSpec.enterEasing.toComposeEasing(),
                )
            )
        }
        // 退出: 淡出播完再关闭 (对齐系统 dialog_exit.xml, 参数读平台 spec)
        LaunchedEffect(dismissing) {
            if (dismissing) {
                progress.animateTo(
                    0f,
                    tween(
                        durationMillis = dialogSpec.exitDurationMillis,
                        easing = dialogSpec.exitEasing.toComposeEasing(),
                    )
                )
                onDismissRequest()
            }
        }
        val p = progress.value
        // 不套 fillMaxSize: 对话框窗口是 wrap_content, 撑满会占掉整个可用空间;
        // Box 尺寸跟随内容, 缩放/淡入只作用于内容框本身
        Box(
            Modifier.graphicsLayer {
                val scale = dialogSpec.enterScaleFrom + (1f - dialogSpec.enterScaleFrom) * p
                scaleX = scale
                scaleY = scale
                alpha = if (dialogSpec.enterFadeIn) p else 1f
            },
        ) {
            content()
        }
    }
}

/**
 * 底部弹层对话框 (对照原版 BaseBottomDialogFragment gravity=Bottom 语义):
 * 内容对齐容器底部, 进入从下方滑入 200ms + 淡入, 退出 150ms 下滑淡出;
 * E-Ink 模式跳过动画。
 *
 * # 跨平台贴底实现
 *
 * CMP 的 [Dialog] 在桌面端是主窗口内的 ComposeSceneLayer 并默认居中放置, 直接
 * `fillMaxWidth + BottomCenter` 在 wrap 内容里没有视觉效果, 表现为居中卡片。
 * 这里把内容铺满整个容器 ([fillMaxSize]) —— 层随内容铺满后坐标归 (0,0) ——
 * 再靠 `align(BottomCenter)` 把 sheet 贴到底部。铺满后点击都在层内,
 * `dismissOnClickOutside` 不再触发, 用透明点击层手动关闭 (scrim 由 Dialog 层自带)。
 *
 * # 下拉拖拽关闭 (对照原版 BottomSheetDialog 手势语义)
 *
 * 内容层挂两条互补的拖拽路径, 两条路径按指针事件竞争天然互斥, 不会重复累计:
 * - nestedScroll 连接: 面板内部可滚动区域 (目录/评论等 LazyColumn) 滚动到顶、
 *   无法再消费位移时, 框架把剩余位移经 onPostScroll 派发给连接 → 面板跟随;
 *   列表未到顶时列表自己消费位移, 面板不动, 列表正常滚动 (与 M3 ModalBottomSheet
 *   的协调方式一致, 不碰列表滚动)。
 * - pointerInput 竖直拖拽: 无内部滚动消费位移 (朗读面板等非滚动内容) 时赢得
 *   touch slop 竞争, 面板直接跟随手指; 内部滚动消费了位移则检测自动让位。
 *
 * 位移带 ×0.6 阻力, 向上拖回弹 (不越位); 松手时位移达阈值 (max(120dp, 面板高/4))
 * 或向下 fling 超 800dp/s → 走现有滑出动画关闭 (从当前位移续播, 无跳变);
 * 否则弹簧动画回弹复位。E-Ink 分支无动画无手势, 保持禁用。
 *
 * 除下拉关闭外, 面板还支持上推展开到视觉全屏 (对照 BottomSheetBehavior 双锚点语义):
 * 顶栏/空白区向上拖 → 面板高度实时放大 (底部贴窗底), 上限 = 锚点全高 - 状态栏高
 * (视觉全屏, 不压系统栏; 桌面 = 主窗口全高); 上推过半或快速上推 → 弹簧吸附全屏,
 * 否则回弹默认高度。全屏态下拉先缩回默认高度、再下拉才关闭 (先缩回再关闭)。
 * 展开的阻力系数与下拉相同 (×0.6)。内部列表滚动到顶/底时, 剩余位移经 nestedScroll
 * 路径同样参与展开/缩回。
 *
 * 拖拽只在"无可滚动内容消费位移"的区域生效 (顶栏/空白区): 内部 Compose 滚动组件
 * (LazyColumn 等) 会自己消费竖直手势, 面板不跟随。平台 WebView 这类 interop 视图不参与
 * Compose 手势竞争, 需内容侧用 [sheetDragExclusion] 登记为拖拽禁区 (见 [SheetDragExclusion])。
 *
 * [fullScreen] 为 true 时面板撑满窗口且整体停用拖拽关闭 (全屏态没有"半屏"可回弹的语义,
 * 顶栏误滑不该丢掉整个页面), 内容原地变高 —— 组合位置不变, 内嵌 WebView 等重量级视图
 * 不会被重建。
 */
@Composable
fun AppBottomSheetDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = AppDialogSizes.properties(),
    maxHeight: Dp? = null,
    fullScreen: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 顶层覆盖物返回拦截 (同 [AppDialog]): 底部弹层打开期间返回键优先收起弹层
    var dismissing by remember { mutableStateOf(false) }
    // 内容侧请求"带动画收起" (见 [LocalSheetDismissRequest]), E-Ink 无动画直接关闭。
    // remember 住: staticCompositionLocalOf 的值一变就整棵子树重组, 不能每次重组给新 lambda
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val requestDismiss: () -> Unit = remember {
        {
            if (AppConfigProviders.get().isEInkMode) {
                currentOnDismissRequest()
            } else {
                dismissing = true
            }
        }
    }
    BackLayerHandler(enabled = true) { requestDismiss() }
    // 内容区拖拽禁区 (平台 WebView 等 interop 视图自行消化竖直手势, 见 [SheetDragExclusion])
    val dragExclusion = remember { SheetDragExclusion() }
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            CompositionLocalProvider(
                LocalSheetDragExclusion provides dragExclusion,
                LocalSheetDismissRequest provides requestDismiss,
            ) {
                BottomSheetScaffold(
                    onDismissRequest = onDismissRequest,
                    maxHeight = maxHeight,
                    fullScreen = fullScreen,
                ) { content() }
            }
        }
        return
    }
    // 底部弹层动画平台 spec (与 AppDialog 同 spec: 时长/插值器按平台对话框转场语义)
    val dialogSpec = remember { PlatformCapabilityProviders.get().dialogTransitionSpec }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        // 底部弹层不压暗底层 (对照原版 BaseBottomDialogFragment/setupAsBottomDialog
        // 的 clearFlags(FLAG_DIM_BEHIND) + dimAmount=0, 同 LegadoApp ModalBottomSheet 先例)。
        // 桌面/iOS/鸿蒙 CMP Dialog 自带 0.6 scrim 且 DialogProperties 无 scrimColor 参数
        // (common expect 仅 3 参数), 无法关闭, 属平台限制; Android 端不再补 FLAG_DIM_BEHIND。
        val progress = remember { Animatable(0f) }
        // 拖拽位移 (px): 双向 —— 负 = 上推 (面板高度放大, 见 heightPx), 正 = 下拉
        // (面板整体下移, translationY 相加; 触发关闭时退出动画从当前位置继续下滑,
        // 无跳变); 松手后吸附默认锚点/视觉全屏或弹簧回弹复位
        var dragOffset by remember { mutableStateOf(0f) }
        // 默认锚点高 (px): 面板未展开时的实测高度 (内容 wrap 封顶 0.7 锚点高的自然高),
        // 由 onSizeChanged 在 dragOffset == 0 时同步; 展开拖拽期间保持初值作"缩回目标",
        // 回弹到默认锚点后恢复跟随 (桌面窗口 resize 时默认锚点自动更新)
        var defaultAnchorPx by remember { mutableStateOf(0) }
        // 面板实际高度 (onSizeChanged 测量), 用于 1/4 面板高的关闭位移阈值
        var sheetHeightPx by remember { mutableStateOf(0) }
        // 回弹复位动画 job: 新拖拽开始时取消, 避免回弹与新位移互相覆盖
        var bounceJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        // 拖拽关闭总开关: 全屏态整体停用 (两条拖拽路径共用, 经 state 读取, 免得
        // remember 住的 nestedScroll 连接捕获到旧值)
        val dragEnabled = rememberUpdatedState(!fullScreen)
        // 弹层节点坐标 (与下方 pointerInput 同层): 把按下点换算到 window 坐标做禁区命中判定
        var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // 拖拽参数: 位移阻力 ×0.6 (0.5~0.7 区间, 更接近原生手感);
        // 关闭位移阈值 max(120dp, 面板高/4); 快速下拉 fling 速度阈值 800dp/s
        val minDismissDistancePx = with(density) { 120.dp.toPx() }
        val flingDismissVelocityPx = with(density) { 800.dp.toPx() }
        // 上推展开 fling 速度阈值: 与下拉关闭同速反向 (快速上推直接吸附全屏)
        val flingExpandVelocityPx = with(density) { 800.dp.toPx() }
        // 视觉全屏高 (px) = 锚点全高 - 状态栏高 - 顶部平台装饰高: 移动端展开后顶栏停在
        // 状态栏之下 (视觉全屏, 不压系统栏); 桌面无状态栏, 扣的是窗口控制条
        // ([LocalOverlayTopInset], 见 BottomSheetScaffold 注释)。手势闭包经
        // [fullAnchorState] 读最新值 (桌面窗口 resize / 状态栏显隐时跟随)
        val anchorHeightPx = LocalDialogAnchorSize.current?.height
            ?: ScreenInfoProviders.get().screenHeightPx
        val overlayTopInsetPx = with(density) { LocalOverlayTopInset.current.roundToPx() }
        val fullAnchorPx = (anchorHeightPx - rememberVisibleStatusBarHeightPx() - overlayTopInsetPx)
            .coerceAtLeast(0)
        val fullAnchorState = rememberUpdatedState(fullAnchorPx)
        // 回弹/吸附动画: 从当前位移续播到目标锚点 (新拖拽开始时由 drag 路径取消 bounceJob)
        val animateDragTo: (Float) -> Unit = { target ->
            bounceJob?.cancel()
            bounceJob = scope.launch {
                animate(
                    initialValue = dragOffset,
                    targetValue = target,
                    animationSpec = spring(),
                ) { value, _ -> dragOffset = value }
            }
        }
        // 松手/停止滚动后的归位判定 (pointer 拖拽路径与 nestedScroll 路径共用):
        // 拖拽位移现为双向 —— 负 = 上推 (面板高度放大), 正 = 下拉 (关闭方向)。
        // 锚点吸附: 上推过半/快速上推 → 吸附视觉全屏; 下拉达阈值/快速下拉 (且已
        // 越过默认锚点) → 走现有滑出动画关闭; 其余 → 弹簧回弹默认锚点。
        // 全屏态下拉回弹到 0 = 缩回默认高度, 再下拉才关 (先缩回再关闭)
        val settleDrag: (Float) -> Unit = { velocityY ->
            if (!dismissing) {
                val dismissDistancePx = maxOf(minDismissDistancePx, sheetHeightPx * 0.25f)
                // 展开量 (px) = 视觉全屏高 - 默认锚点高
                val expandDistancePx = (fullAnchorState.value - defaultAnchorPx).toFloat()
                when {
                    dragOffset > 0f &&
                        (dragOffset >= dismissDistancePx || velocityY >= flingDismissVelocityPx) ->
                        dismissing = true

                    velocityY <= -flingExpandVelocityPx -> animateDragTo(-expandDistancePx)
                    dragOffset <= -expandDistancePx / 2f -> animateDragTo(-expandDistancePx)
                    dragOffset != 0f -> animateDragTo(0f)
                }
            }
        }
        // 滚动内容协调: 内部列表到顶后无法消费的位移经 onPostScroll 派发到本连接,
        // 面板跟随; 未到顶时列表自己消费, 余量为 0, 面板不动 (同 M3 ModalBottomSheet
        // ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection 机制, 仅收 UserInput,
        // 排除列表自身 fling/惯性位移; 鼠标滚轮到顶同样派发, 属原生 sheet 行为)
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (
                        !dragEnabled.value ||
                        dismissing ||
                        source != NestedScrollSource.UserInput ||
                        available.y == 0f
                    ) {
                        return Offset.Zero
                    }
                    // 新的拖拽开始: 取消回弹动画, 从当前位移继续
                    bounceJob?.cancel()
                    bounceJob = null
                    // 双向夹紧: 上推 (available.y 负 = 列表滚到底继续上滑) 展开到视觉
                    // 全屏为止; 下拉 (available.y 正 = 列表滚到顶继续下滑) 不设下限,
                    // 由 settleDrag 判定关闭/回弹
                    dragOffset = (dragOffset + available.y * DragResistance)
                        .coerceAtLeast((defaultAnchorPx - fullAnchorState.value).toFloat())
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    // 本回调只在"内部列表赢得手势并 fling"时触发 (两条拖拽路径互斥:
                    // 面板跟手的 pointerInput 路径赢得手势时列表无 fling, 不会走到这里;
                    // 列表赢得手势时 pointerInput 已让位, 关闭判定由 pointerInput 路径负责)。
                    // 因此这里的 fling 必然是列表自身滚动 (滚到顶/底的剩余惯性), 速度
                    // 不得触发"快速下拉关闭"——只按位移判定 (滚到顶后继续下拉、位移达
                    // 阈值仍可关闭) 或回弹复位面板残留位移 (滚动尾段把面板带起的一点位移)。
                    settleDrag(0f)
                    return available
                }
            }
        }
        // 进入: 从底部滑入 + 淡入 (对齐原版底部弹层动画, 参数读平台 spec)
        LaunchedEffect(Unit) {
            progress.animateTo(
                1f,
                tween(
                    durationMillis = dialogSpec.enterDurationMillis,
                    easing = dialogSpec.enterEasing.toComposeEasing(),
                )
            )
        }
        // 退出: 下滑淡出播完再关闭
        LaunchedEffect(dismissing) {
            if (dismissing) {
                progress.animateTo(
                    0f,
                    tween(
                        durationMillis = dialogSpec.exitDurationMillis,
                        easing = dialogSpec.exitEasing.toComposeEasing(),
                    )
                )
                onDismissRequest()
            }
        }
        // 进入全屏: 清掉残留拖拽位移与回弹动画 (全屏态不再有拖拽路径去归位它)
        LaunchedEffect(fullScreen) {
            if (fullScreen) {
                bounceJob?.cancel()
                bounceJob = null
                dragOffset = 0f
            }
        }
        val p = progress.value
        val dragOffsetPx = dragOffset
        // 滑入/滑出位移取"0.7 锚点高与面板实测高的较大值": 常规弹层实测高 ≤ 0.7 锚点高,
        // 取值不变; 全屏态面板更高, 靠实测高才能整块滑出窗口而不残留顶部一截
        val anchorSlidePx = with(LocalDensity.current) { AppDialogSizes.fullHeight().toPx() }
        val slideHeightPx = maxOf(sheetHeightPx.toFloat(), anchorSlidePx)
        BottomSheetScaffold(
            // 外部点击与返回键一致走 dismissing 退出动画路径
            onDismissRequest = { dismissing = true },
            maxHeight = maxHeight,
            fullScreen = fullScreen,
            // 拖拽中面板高度改由状态驱动: 上推变高 (dragOffset 负), 下拉保持默认
            // 锚点高并整体下移 (dragOffset 正); 回到默认锚点 (dragOffset == 0) 恢复
            // wrap 布局, 高度连续无跳变
            heightPx = if (dragOffset != 0f) {
                (defaultAnchorPx - dragOffset.coerceAtMost(0f)).roundToInt()
            } else null,
            modifier = Modifier
                .onSizeChanged {
                    sheetHeightPx = it.height
                    // 未展开时同步默认锚点 (初始 = 内容自然高, 桌面窗口 resize 跟随)
                    if (dragOffset == 0f) defaultAnchorPx = it.height
                }
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer {
                    // 进入/退出动画位移 + 下拉拖拽位移: 上推部分由面板高度放大表达,
                    // 不参与平移 (面板底部始终贴窗底); 拖拽中触发关闭时退出动画从
                    // 当前拖拽位继续下滑, 无跳变
                    translationY = slideHeightPx * (1f - p) + dragOffsetPx.coerceAtLeast(0f)
                    alpha = if (dialogSpec.enterFadeIn) p else 1f
                }
                .onGloballyPositioned { sheetCoordinates = it }
                // 非滚动内容面板的拖拽路径: 内部无滚动消费位移时赢得竖直 slop,
                // 面板跟随手指; 内部 Compose 滚动消费了位移则本检测自动让位
                // (awaitVerticalTouchSlopOrCancellation 遇已消费的位置变化返回 null),
                // 由上方 nestedScroll 连接接手
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 先挂起等待 down (保证无事件时挂起而非空转)
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 已进入退出流程: 忽略后续手势, 等待本次 pointer up 后结束本轮
                        // (若在 awaitFirstDown 之前 return, 无 pressed 时 awaitEachGesture 的
                        // awaitAllPointersUp 不挂起, 会形成 busy loop 占死 EDT → 桌面端卡死)
                        if (dismissing) {
                            var upEvent = awaitPointerEvent(PointerEventPass.Final)
                            while (upEvent.changes.any { it.pressed }) {
                                upEvent = awaitPointerEvent(PointerEventPass.Final)
                            }
                            return@awaitEachGesture
                        }
                        // 本轮不参与手势竞争 (全程不消费位移, 手势原样留给内部):
                        // 全屏态停用拖拽, 或按下落点命中拖拽禁区 —— 禁区内是平台 WebView 这
                        // 类 interop 视图, 只要 Compose 侧消费了位移它就会收到 ACTION_CANCEL
                        // 而彻底滚不动 (见 [SheetDragExclusion]), 所以不能靠"抢先消费"让位。
                        // 此处已有按下中的指针, awaitEachGesture 末尾的 awaitAllPointersUp
                        // 会正常挂起, 不会空转。
                        val downInExclusion = sheetCoordinates?.let {
                            dragExclusion.contains(it.localToWindow(down.position))
                        } == true
                        if (!dragEnabled.value || downInExclusion) return@awaitEachGesture
                        var overSlop = 0f
                        val dragChange =
                            awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                overSlop = over
                            } ?: return@awaitEachGesture
                        bounceJob?.cancel()
                        bounceJob = null
                        // 首段位移含越过 slop 的 overshoot, 与后续 delta 连续;
                        // 下限夹紧在视觉全屏展开量 (上推越位不越界)
                        dragOffset = (dragOffset + overSlop * DragResistance)
                            .coerceAtLeast((defaultAnchorPx - fullAnchorState.value).toFloat())
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        velocityTracker.addPosition(dragChange.uptimeMillis, dragChange.position)
                        drag(dragChange.id) { change ->
                            val deltaY = change.positionChange().y
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // 双向: 向下 (deltaY 正) → 位移正向累积 (下拉/关闭方向);
                            // 向上 (deltaY 负) → 位移负向累积 → 面板高度放大,
                            // 到视觉全屏后夹紧不越位
                            dragOffset = (dragOffset + deltaY * DragResistance)
                                .coerceAtLeast((defaultAnchorPx - fullAnchorState.value).toFloat())
                        }
                        settleDrag(velocityTracker.calculateVelocity().y)
                    }
                },
        ) {
            CompositionLocalProvider(
                LocalSheetDragExclusion provides dragExclusion,
                LocalSheetDismissRequest provides requestDismiss,
            ) { content() }
        }
    }
}

/**
 * 底部弹层骨架: 透明点击层铺满 (点击关闭) + sheet 内容贴底。
 * 需作为 Dialog 内容的根, 内容 fillMaxSize 让弹层覆盖整个容器。
 *
 * [fullScreen] 时 sheet 撑满窗口 (仍贴底对齐, 只是高度到顶), 不再受 [maxHeight] /
 * 0.7 锚点高约束。
 *
 * 全屏时补 [platformStatusBarPadding]: 常规弹层高度只有 0.7 锚点高、顶部到不了状态栏;
 * 全屏后顶部会真的贴到窗口顶, 这层 padding 把前提补回来。它只消费"尚未被窗口消费"的 inset ——
 * decorFitsSystemWindows=true 的窗口已自行避让时为 0, 不会双重避让; 只有 Android 15+
 * 强制 edge-to-edge 的全屏窗口才真正生效。
 *
 * 同理补 [LocalOverlayTopInset]: 桌面端窗口控制条不在 Compose 画布内 (Windows 是 z-order
 * 恒在画布之上的 native 子窗口, Linux 是 Column 里的自绘条), 而弹层是铺满整窗的 Popup 层 ——
 * 全屏后顶栏会被控制条盖住, 只能主动避让。移动端恒 0。
 *
 * 顶部 inset 归本骨架所有: 弹层内 [LocalTransitionFrozenStatusBarHeightPx] 提供 0
 * ("此处没有状态栏"), content 里的顶栏 (如 [AppTitleBar]) 不再自行叠加 —— 弹层贴底、
 * 顶部够不到状态栏, 自行避让只会凭空多出一层状态栏高的空白带。
 */
@Composable
private fun BottomSheetScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    fullScreen: Boolean = false,
    /** 非空 = 拖拽展开态的面板固定高度 (px), 由宿主拖拽位移状态驱动 */
    heightPx: Int? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 透明点击层: 铺满全窗, 点击关闭 sheet (铺满后 dismissOnClickOutside 不触发)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismissRequest() }
        )
        Box(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    when {
                        fullScreen -> Modifier
                            .fillMaxHeight()
                            .platformStatusBarPadding()
                            .padding(top = LocalOverlayTopInset.current)
                        // 拖拽展开态: 固定高度由位移状态驱动 (wrap 内容贴底留白,
                        // fillMaxSize 内容自然拉伸)
                        heightPx != null -> Modifier.height(with(LocalDensity.current) { heightPx.toDp() })
                        else -> Modifier.heightIn(max = maxHeight ?: AppDialogSizes.fullHeight())
                    }
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CompositionLocalProvider(LocalTransitionFrozenStatusBarHeightPx provides 0) {
                content()
            }
        }
    }
}

/**
 * 面板拖拽手势: 手指位移 → 面板位移/高度的阻力系数 (0.5~0.7 区间, 更接近原生
 * BottomSheet 手感; 下拉关闭与上推展开共用)。
 */
private const val DragResistance = 0.6f
