package io.legado.app.ui.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.BackLayerHandler
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.LocalStatusBarPaddingEnabled
import io.legado.app.ui.compose.platform.PlatformDialogDim
import io.legado.app.ui.compose.platform.PlatformSheetSystemBarIcons
import io.legado.app.ui.compose.platform.PlatformSheetWindowStyle
import io.legado.app.ui.compose.platform.bottomSheetBottomInsets
import io.legado.app.ui.compose.platform.rememberVisibleStatusBarHeightPx
import io.legado.app.ui.compose.theme.AppTextMenuState
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalAppTextMenuState
import io.legado.app.ui.compose.theme.ProvidePlatformTextMenu
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.toComposeEasing
import io.legado.app.utils.ScreenInfoProviders
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
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
 * 弹层展开到整窗的比例 (0 = 默认锚点高, 1 = 面板顶到窗口顶), 由 [AppBottomSheetDialog] 按
 * 拖拽位移给出, 面板容器按它收平顶角。逐帧变化的值, 用非 static 的 compositionLocal
 * (static 版每次变化都让整棵子树重组)。
 */
val LocalSheetExpandFraction = compositionLocalOf { 0f }

/**
 * 面板顶已进入顶部装饰带 (状态栏 / 桌面窗口控制条) 的高度: 面板容器按它把正文同量下移,
 * 底色仍铺满到窗口顶 —— 状态栏区域由面板底色接管, 顶栏不被状态栏图标压住。
 */
val LocalSheetTopOverlap = compositionLocalOf { 0.dp }

/**
 * 动画进行中屏蔽本节点子树的指针输入: Initial 阶段消费"按下/拖动/抬起"这类会触发动作的事件。
 *
 * 对话框/弹层滑入滑出与缩放淡出 (进度 < 1) 期间, 内容里的点击手势收不到未消费的 down
 * (down 被消费 awaitFirstDown 不成立, up 被消费即判手势取消), 动画途中的点击落在
 * 正在离场的旧按钮上不会触发。
 *
 * 悬停与滚轮不吃: 内嵌平台视图 (AndroidView / UIKitView) 的输入通道一旦看到"事件已被
 * Compose 消费"就停止向该视图派发, 而复位只发生在"全部抬手"——悬停与滚轮永不产生抬手,
 * 被吃一次即永久锁死 (表现为鼠标滚轮必须先点一下网页才能用)。这两类事件不触发动作,
 * 放行不影响本拦截的目的。
 */
internal fun Modifier.blockInputWhile(active: Boolean): Modifier = pointerInput(active) {
    if (!active) return@pointerInput
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                if (change.pressed || change.changedToUpIgnoreConsumed()) change.consume()
            }
        }
    }
}

/**
 * 在"当前独立窗口"内安装 per-window 文本菜单通道 (Android 新通道)。
 *
 * Compose CompositionLocal 跨 Dialog 窗口继承: Dialog 内长按文本时 framework 会用**本窗口**
 * 的选区节点坐标调 `contentBounds(主窗口 host 坐标)`, 两坐标不在同一 hierarchy, 抛
 * `IllegalArgumentException: layouts are not part of the same hierarchy` 闪退。故每个独立窗口
 * 的根再包一层 [ProvidePlatformTextMenu] (Box 坐标 + 弹层宿主都挂本窗口), 长按走本窗口自己的
 * provider —— 与 framework 默认实现 (每窗口 Box + coordinatesProvider, 见
 * AndroidTextContextMenuToolbarProvider) 的设计一致; 主窗口通道仍由 AppTheme 根部提供。
 */
@Composable
internal fun WithWindowTextMenu(content: @Composable () -> Unit) {
    ProvidePlatformTextMenu(
        state = LocalAppTextMenuState.current ?: remember { AppTextMenuState() },
        content = content,
    )
}

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
    /** 背景暗化: 原版 BaseDialogFragment 默认保留 dim */
    dim: Boolean = true,
    /** false = 跳过对话框进入/退出动画 (供满屏自管内容做窗口承载, 如无配对端点的大图查看器) */
    animate: Boolean = true,
    /**
     * 尺寸语义, 由宿主施加 (正文无需再各自钳制): 缺省 [AppDialogSizing.Adaptive] = 宽 0.9 锚点
     * (上限 800dp) + 高随内容收缩、封顶 0.8 锚点。
     * 满屏自管内容的窗口承载 (animate=false) 与显式传 [AppDialogSizing.Unconstrained] 者不受钳制。
     */
    sizing: AppDialogSizing = AppDialogSizing.Adaptive,
    content: @Composable () -> Unit,
) {
    // 顶层覆盖物返回拦截: 对话框打开期间返回键 (桌面端 ESC / 统一链) 优先关闭对话框,
    // 不落到页面/出栈 (对齐原版系统 Dialog 消费 BACK 的层级语义)。非 E-Ink 走 dismissing
    // 播退出动画, E-Ink 无动画直接关闭 (对齐 E-Ink 分支 Dialog 直连 onDismissRequest)。
    var dismissing by remember { mutableStateOf(false) }
    BackLayerHandler(enabled = true) {
        if (!animate || AppConfigProviders.get().isEInkMode) onDismissRequest() else dismissing = true
    }
    if (AppConfigProviders.get().isEInkMode) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = {
                WithWindowTextMenu {
                    Box(
                        Modifier
                            .appDialogSize(sizing)
                            .directionKeyFocusNavigation(LocalFocusManager.current),
                        contentAlignment = Alignment.Center,
                    ) { content() }
                }
            },
        )
        return
    }
    if (!animate) {
        // 无动画分支: 满屏自管内容的窗口承载。内容铺满容器并让开顶部平台装饰
        // ([LocalOverlayTopInset], 桌面端窗口控制条 40dp), 全屏内容坐标系与页面可用区对齐;
        // 此类调用方自管点击关闭 (dismissOnClickOutside=false), 不依赖平台外点关闭;
        // 暗度由内容自绘 (需要时仍可 dim=true 补平台 dim)
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = {
                WithWindowTextMenu {
                    if (dim) PlatformDialogDim()
                    val topInset = LocalOverlayTopInset.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (topInset > 0.dp) Modifier.padding(top = topInset) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        content()
                    }
                }
            },
        )
        return
    }
    // 对话框动画平台 spec (时长/插值器按平台对话框转场语义, Android 动态读系统动画时长缩放)
    val dialogSpec = remember { PlatformCapabilityProviders.get().dialogTransitionSpec }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        WithWindowTextMenu {
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
            val topInset = LocalOverlayTopInset.current
            // 不套 fillMaxSize: 对话框层 bounds 跟随内容尺寸 (CMP 桌面 rememberDialogMeasurePolicy
            // 把 layer.boundsInWindow 设为内容实测尺寸; Android 窗口 wrap_content), 铺满后
            // dismissOnClickOutside 的"外部"永远不存在, 外点关闭失效。保持 wrap 内容 +
            // padding(top = inset): 内容中心下移到可用区中心 (与铺满+padding 视觉等价),
            // 平台外点关闭天然可用。顶部 inset 条带虽归入层内不响应外点, 但恰是 Windows
            // native 控制条的物理覆盖区, 本就点不到; 移动端 inset=0 零影响
            Box(
                Modifier
                    .appDialogSize(sizing)
                    .directionKeyFocusNavigation(LocalFocusManager.current)
                    .then(if (topInset > 0.dp) Modifier.padding(top = topInset) else Modifier)
                    // 进出场动画期间内容不派生输入 (渐入未稳/渐出途中的点击不生效)
                    .blockInputWhile(p < 1f),
                contentAlignment = Alignment.Center,
            ) {
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
    }
}

/**
 * 居中对话框正文统一容器：8dp 圆角 (shapeDefault) + fillet 底色收口于此, 与命令式宿主
 * filletBackground 同规格; 居中对话框正文一律用它, 不再各自声明 shape。
 * 贴底弹层面板用 [AppSheetSurface]。
 */
@Composable
fun AppDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AppTheme.DesignTokens.shapeDefault,
    color: Color = AppTheme.colors.fillet,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        content = content,
    )
}

/**
 * 标准对话框壳 (顶栏 + 正文 + 底栏三槽), 居中对话框一律用它, 三处尺寸口径在此收口:
 *
 * - **标准顶栏** ([DialogTitleBar]): 返回箭头 + 标题 + 右侧动作, 56dp 行; 调用方不再各写标题行;
 * - **正文**: 默认四边 8dp 内边距; 正文以 `weight(1f, fill = false)` 占剩余空间,
 *   长内容需自带滚动 (weight + 内部滚动才能让底栏恒可见, 不要靠外部把整个壳撑高);
 * - **标准底栏** ([AppDialogBar]): 左右 8dp, 纵向 8dp 由本壳给 (正文与按钮之间、按钮与对话框边之间),
 *   按钮一律 [AppTextButton]。不需要按钮的对话框传 `bottomBar = null`。
 *
 * 视觉与既有手写壳体一致: fillet 底色 + 8dp 圆角, 宽度 0.9 锚点 (上限 800dp),
 * 高度按内容自适应并封顶 0.8 锚点 (长列表型传 `fullHeight = true` 固定全高)。
 *
 * @param title          顶栏标题
 * @param subtitle       顶栏副标题 (可空)
 * @param titleContent   顶栏标题槽覆盖 (如筛选输入框)；非空时替掉返回箭头旁的标题文本
 * @param onBack         顶栏返回箭头动作, 缺省关闭对话框
 * @param titleActions   顶栏右侧动作槽 (IconButton / AppTextButton)
 * @param bottomBar      底栏按钮槽; null = 不排底栏。需要左侧按钮时在槽内用 `Spacer(Modifier.weight(1f))`
 * @param contentPadding 正文内边距 (默认四边 8dp; 正文自带分块留白的弹窗传 [PaddingValues] 收窄)
 * @param fullHeight      true = 高度固定 0.8 锚点 (长列表型); false = 按内容自适应并封顶 0.8
 * @param properties      窗口属性 (默认 [AppDialogSizes.properties]; 点外部不关闭等语义在此传)
 */
@Composable
fun AppDialogScaffold(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleClickable: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    /** 顶栏返回箭头动作, 缺省 = 关闭对话框 (筛选态等需“箭头退子状态而非关窗”时覆盖) */
    onBack: () -> Unit = onDismissRequest,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    titleActions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(DesignTokens.spacingDefault),
    fullHeight: Boolean = false,
    properties: DialogProperties = AppDialogSizes.properties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialog(onDismissRequest = onDismissRequest, properties = properties) {
        AppDialogSurface(
            modifier = modifier.appDialogSize(fullHeight = fullHeight),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    titleClickable = titleClickable,
                    onTitleClick = onTitleClick,
                    titleContent = titleContent,
                    actions = titleActions,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        // 有底栏时正文不再留底边距: 8dp 由底栏自身给 (两边都给就是 16dp)
                        .padding(
                            if (bottomBar == null) {
                                contentPadding
                            } else {
                                val layoutDirection = LocalLayoutDirection.current
                                PaddingValues(
                                    start = contentPadding.calculateStartPadding(layoutDirection),
                                    top = contentPadding.calculateTopPadding(),
                                    end = contentPadding.calculateEndPadding(layoutDirection),
                                    bottom = 0.dp,
                                )
                            },
                        ),
                    content = content,
                )
                if (bottomBar != null) {
                    Box(
                        Modifier.padding(DesignTokens.spacingDefault),
                    ) {
                        AppDialogBar(actions = bottomBar)
                    }
                }
            }
        }
    }
}

/**
 * 贴底弹层统一面板容器：底栏色 (bottomBackground) + 顶角圆角随上拉展开收平 (拉到底为 0) 收口于此,
 * 形状与底色均不开放修改, 保证全仓弹层同一观感。同时承担两件事:
 *
 * - **顶部装饰带避让**: 面板可铺到窗口顶, 顶角 16dp 是圆角也是"未展开"的标志; 展开进状态栏/
 *   桌面窗口控制条那一条后, 底色继续铺满 ([LocalSheetTopOverlap] 表达进入量), 正文同量下移,
 *   顶栏不被状态栏图标压住。因为背景必须铺在装饰带下, 避让只能在底色之内施加 —— 这也是它
 *   不在骨架外层的原因。
 * - **底部系统栏/键盘避让**: 弹层窗口不再把安全区/系统栏从内容里让出去
 *   (见 [io.legado.app.ui.compose.platform.platformSheetDialogProperties]), 系统栏避让由本容器
 *   统一按 [bottomSheetBottomInsets] 补 (顶部则由 [LocalSheetTopOverlap] 按实际进入量补);
 *   skiko 三端的键盘仍由窗口按 ime 自行避让 (那里 ime inset 恒为 0, 不双重避让)。
 *
 * [AppBottomSheetDialog] 面板一律用它。
 */
@Composable
fun AppSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = AppTheme.DesignTokens
    val expand = LocalSheetExpandFraction.current
    val topOverlap = LocalSheetTopOverlap.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = tokens.radiusLg * (1f - expand),
            topEnd = tokens.radiusLg * (1f - expand),
        ),
        color = AppTheme.colors.bottomBackground,
    ) {
        Box(
            Modifier
                .then(if (topOverlap > 0.dp) Modifier.padding(top = topOverlap) else Modifier)
                .windowInsetsPadding(bottomSheetBottomInsets()),
            propagateMinConstraints = true,
        ) { content() }
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
 * 面板宽度与上拉联动: 收起态取 [DesignTokens.dialogMaxWidth] 与窗口宽的较小值 (窗口比上限宽时
 * 收成居中面板、两侧露出底层界面), 随上拉到整屏同步放宽到整窗宽, 回落时收回; 窄窗与移动端
 * 窗口宽本就小于上限, 恒为铺满。
 *
 * # 下拉拖拽关闭 (对照原版 BottomSheetDialog 手势语义)
 *
 * 内容层挂两条互补的拖拽路径, 两条路径按指针事件竞争天然互斥, 不会重复累计:
 * - nestedScroll 连接: 面板内部可滚动区域 (目录/评论等 LazyColumn) 滚动到顶、
 *   无法再消费下拉位移时, 框架把剩余位移经 onPostScroll 派发给连接 → 面板跟随;
 *   列表未到顶时列表自己消费位移, 面板不动, 列表正常滚动 (与 M3 ModalBottomSheet
 *   的协调方式一致, 不碰列表滚动)。滚到底继续上滑的剩余位移不接 (见下文展开语义)。
 * - pointerInput 竖直拖拽: 无内部滚动消费位移 (朗读面板等非滚动内容) 时赢得
 *   touch slop 竞争, 面板直接跟随手指; 内部滚动消费了位移则检测自动让位。
 *
 * 下拉位移带 ×0.6 阻力; 松手时位移达阈值 (max(120dp, 面板高/4))
 * 或向下 fling 超 800dp/s → 走现有滑出动画关闭 (从当前位移续播, 无跳变);
 * 否则弹簧动画回弹复位。E-Ink 分支无动画无手势, 保持禁用。
 *
 * 除下拉关闭外, 面板还支持上推展开到真全屏 (对照 BottomSheetBehavior 双锚点语义), 只对面板
 * 静止高已被收起态上限截断的弹层生效 (内容撑不满上限的弹层上拉不放大面板, 也不改变顶角与
 * 宽度; 内容容器自己撑满上限的弹层仍在可上拉之列)。
 * 展开方式: 面板内向上拖/上滑 → 面板高度实时放大 (底部贴窗底), 上限 = 锚点整窗高 (含顶部装饰带);
 * 展开过程里顶角从 16dp 收到 0、面板底色铺到窗口顶 (状态栏区域由面板接管)、宽度从收起态
 * 上限放宽到整窗宽, 正文按面板顶进入装饰带的量下移, 顶栏不被状态栏图标压住; 上推过半或
 * 快速上推 → 弹簧吸附全屏 (触摸拖拽与列表内上滑两条路径同判定), 否则回弹默认高度。
 * 全屏态下拉先缩回默认高度、再下拉才关闭
 * (先缩回再关闭)。桌面端最顶部那条窗口控制条是 native 子窗口 (z-order 恒在画布之上,
 * 平台不允许被覆盖), 那一条仍由控制条自绘, 面板底色铺到它之下。
 * 展开的阻力系数与下拉相同 (×0.6)。向上位移在 nestedScroll 的 pre 阶段就由面板消费
 * (对照 M3 ConsumeSwipeWithinBottomSheetBounds 与 BottomSheetBehavior 的 onPreScroll:
 * 向上位移先把面板顶到全屏, 面板到顶后的剩余上滑才归列表), 列表未展开时上滑不滚列表;
 * 顶栏/空白区这类无内部滚动消费位移的区域仍走 pointerInput 路径跟随手指。
 * 内部列表滚到顶后继续下拉, 剩余位移仍经 nestedScroll 路径缩回展开态/关闭面板。
 *
 * 面板跟手的指针拖拽只在"无可滚动内容消费位移"的区域生效 (顶栏/空白区): 内部 Compose 滚动
 * 组件 (LazyColumn 等) 赢得手势后本轮拖拽整轮让位, 由上面的 nestedScroll 路径接管。
 * 平台 WebView 这类 interop 视图不参与 Compose 手势竞争, 需内容侧用 [sheetDragExclusion]
 * 登记为拖拽禁区 (见 [SheetDragExclusion])。
 *
 * [fullScreen] 为 true 时面板撑满窗口、外观等同手拉到底 (展开比例恒 1: 顶角收平、底色铺到窗口顶),
 * 并整体停用拖拽关闭 (全屏态没有"半屏"可回弹的语义, 顶栏误滑不该丢掉整个页面), 内容原地变高
 * —— 组合位置不变, 内嵌 WebView 等重量级视图不会被重建。
 *
 * 弹层窗口贴边 ([AppDialogSizes.sheetProperties]): 面板要画到窗口顶的状态栏区域, 且 ime insets
 * 只有贴边窗口才真正派发给内容; 随之而来的底部系统栏/键盘避让与主题自带暗化
 * 分别由 [AppSheetSurface] 与 [PlatformSheetWindowStyle] 收口。
 *
 * 打开期间压暗底层: 走对话框窗口/层自带的暗化 (Android 由 [PlatformDialogDim] 补 0.6,
 * 桌面/iOS/鸿蒙用对话框自带的 0.6 蒙层), 与居中对话框同一套; E-Ink 分支不补 (对齐原版清 dim)。
 */
@Composable
fun AppBottomSheetDialog(
    onDismissRequest: () -> Unit,
    /** 收起态面板高上限 (半屏锚点高; 不传取窗口可用高的 0.8 倍), 同时是可上拉判定的上限 */
    maxHeight: Dp? = null,
    fullScreen: Boolean = false,
    /** 外部请求收起: 置 true 走退出动画 (E-Ink 直接关闭), 完成后回调 onDismissRequest */
    dismissed: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 窗口属性固定为弹层语义 (贴边窗口), 不开放给调用方 —— 传对话框属性会让面板到不了窗口顶
    val properties = remember { AppDialogSizes.sheetProperties() }
    // 收起态面板高上限 (半屏锚点高): 面板高被它截断的弹层才允许上拉展开 (判定见下方 onSizeChanged)
    val anchorMaxHeight = maxHeight ?: AppDialogSizes.fullHeight()
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
        LaunchedEffect(dismissed) {
            if (dismissed) onDismissRequest()
        }
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            WithWindowTextMenu {
                CompositionLocalProvider(
                    LocalSheetDragExclusion provides dragExclusion,
                    LocalSheetDismissRequest provides requestDismiss,
                ) {
                    // E-Ink 无拖拽: 只有默认锚点与全屏态两档, 展开比例按全屏标志取 0/1
                    val fullScreenFraction = if (fullScreen) 1f else 0f
                    val density = LocalDensity.current
                    val topBandPx = sheetTopDecorationHeightPx()
                    BottomSheetScaffold(
                        onDismissRequest = onDismissRequest,
                        anchorMaxHeight = anchorMaxHeight,
                        fullScreen = fullScreen,
                        expandFraction = fullScreenFraction,
                        topOverlap = if (fullScreen) with(density) { topBandPx.toDp() } else 0.dp,
                        panelMaxWidth = sheetPanelMaxWidth(fullScreenFraction),
                    ) { content() }
                }
            }
        }
        return
    }
    // 底部弹层动画平台 spec (与 AppDialog 同 spec: 时长/插值器按平台对话框转场语义)
    val dialogSpec = remember { PlatformCapabilityProviders.get().dialogTransitionSpec }
    Dialog(onDismissRequest = { dismissing = true }, properties = properties) {
        WithWindowTextMenu {
            val progress = remember { Animatable(0f) }
            // 拖拽位移 (px): 双向 —— 负 = 上推 (面板高度放大, 见 heightPx), 正 = 下拉
            // (面板整体下移, translationY 相加; 触发关闭时退出动画从当前位置继续下滑,
            // 无跳变); 松手后吸附默认锚点/视觉全屏或弹簧回弹复位
            var dragOffset by remember { mutableStateOf(0f) }
            // 指针手势进行中标记 (按下到全部抬起): 无 pointer down 的滚动输入 (滚轮/触控板平移)
            // 的位移只滚列表, 不改变面板高度 (它们不是"拖面板"的手势)
            val touchGesture = remember { mutableStateOf(false) }
            // 本次手势是否拖拽/展开过面板: 两条手势路径共享, 每次按下时复位
            val panelDraggedInGesture = remember { mutableStateOf(false) }
            // 默认锚点高 (px): 面板未展开时的实测高度 (内容 wrap 封顶 0.8 锚点高的自然高),
            // 由 onSizeChanged 在 dragOffset == 0 时同步; 展开拖拽期间保持初值作"缩回目标",
            // 回弹到默认锚点后恢复跟随 (桌面窗口 resize 时默认锚点自动更新)
            var defaultAnchorPx by remember { mutableStateOf(0) }
            // 面板实际高度 (onSizeChanged 测量), 用于 1/4 面板高的关闭位移阈值
            var sheetHeightPx by remember { mutableStateOf(0) }
            // 可上拉展开: 面板静止高已被收起态上限截断才为 true (内容容器自己撑满上限的弹层
            // 也在可上拉之列)。
            // 展开过程中面板高由拖拽位移驱动、静止高不可测量, 故判定只在面板处于静止锚点时
            // 刷新 (见 onSizeChanged) —— 展开中上限变化 (旋转 / 窗口 resize) 不会把已在展开的
            // 面板判成"内容撑不满"而收掉它的展开
            var expandable by remember { mutableStateOf(false) }
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
            // 上推展开 fling 速度阈值: 中低速轻拂直接吸附全屏
            val flingExpandVelocityPx = with(density) { 450.dp.toPx() }
            // 展开上限 (px) = 锚点整窗高: 拉到底即面板顶到窗口顶 (底色铺到状态栏/控制条之下),
            // 顶部装饰带的避让由面板容器按实际进入量施加 (见 [LocalSheetTopOverlap])。
            // 手势闭包经 [fullAnchorState] 读最新值 (桌面窗口 resize / 状态栏显隐时跟随)
            val fullAnchorPx = (LocalDialogAnchorSize.current?.height
                ?: ScreenInfoProviders.get().screenHeightPx).coerceAtLeast(0)
            val fullAnchorState = rememberUpdatedState(fullAnchorPx)
            // 收起态上限 (px): 静止测量时判定面板高是否被它截断 (见 onSizeChanged)
            val anchorCapState = rememberUpdatedState(with(density) { anchorMaxHeight.toPx() })
            // 展开量 (px) = 整窗高 - 默认锚点高: 两条拖拽路径的上推下限与吸附判定
            // 共用同一换算 (为让 remember 住的 nestedScroll 连接读到最新锚点而写成 lambda);
            // 不可上拉展开的弹层恒为 0 = 没有可展开的余量, 上拉不放大也不改变顶角与宽度;
            // 极短窗口下默认高可能反超整窗高, 同样夹到 0
            val expandDistance: () -> Float = {
                if (expandable) {
                    (fullAnchorState.value - defaultAnchorPx).toFloat().coerceAtLeast(0f)
                } else {
                    0f
                }
            }
            // 顶部装饰带高 px (状态栏 / 桌面窗口控制条)
            val topBandPx = sheetTopDecorationHeightPx()
            // 展开比例 (0 = 默认锚点, 1 = 面板顶到窗口顶): 顶角收平与面板放宽按它插值
            val expandFraction = if (fullScreen) {
                1f
            } else {
                val distance = expandDistance()
                if (distance <= 0f) 0f else (-dragOffset / distance).coerceIn(0f, 1f)
            }
            // 拖拽态面板高 (px): 与传给骨架的高度同源同式。面板顶下移量与正文下移量必须
            // 同源相消, 换成上一帧实测高就与同帧的位移高差一帧 —— 每帧正文相对面板多偏
            // 一段等于该帧手指位移的距离 (全屏态轻微下拉时正文抖动)
            val dragHeightPx = if (dragOffset != 0f) {
                (defaultAnchorPx - dragOffset.coerceAtMost(0f)).roundToInt()
            } else {
                null
            }
            // 面板顶已进入装饰带的量 = 正文下移量 (面板铺到窗口顶时等于整条装饰带高):
            // 静止态取实测高, 拖拽态取同帧位移高
            val topOverlapPx =
                (topBandPx - (fullAnchorPx - (dragHeightPx ?: sheetHeightPx))).coerceAtLeast(0)
            // 回弹/吸附动画: 从当前位移续播到目标锚点 (新拖拽开始时由 drag 路径取消 bounceJob)
            val animateDragTo: (Float) -> Unit = { target ->
                bounceJob?.cancel()
                bounceJob = scope.launch {
                    animate(
                        initialValue = dragOffset,
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ -> dragOffset = value }
                }
            }
            // 松手/停止滚动后的归位判定 (pointer 拖拽路径与 nestedScroll 路径共用):
            // 拖拽位移现为双向 —— 负 = 上推 (面板高度放大), 正 = 下拉 (关闭方向)。
            // 锚点吸附: 向上轻拂/慢推过半 → 吸附全屏; 展开态向下轻拂/下拉过半 → 缩回半屏;
            // 越过半屏下拉达阈值/快速下拉 → 走滑出动画关闭; 其余 → 弹簧回弹默认锚点。
            val settleDrag: (Float) -> Unit = { velocityY ->
                if (!dismissing) {
                    val dismissDistancePx = maxOf(minDismissDistancePx, sheetHeightPx * 0.25f)
                    val expandDistancePx = expandDistance()
                    when {
                        dragOffset > 0f &&
                            (dragOffset >= dismissDistancePx || velocityY >= flingDismissVelocityPx) ->
                            dismissing = true

                        velocityY <= -flingExpandVelocityPx -> animateDragTo(-expandDistancePx)
                        velocityY >= flingExpandVelocityPx && dragOffset < 0f -> animateDragTo(0f)
                        dragOffset <= -expandDistancePx * 0.5f -> animateDragTo(-expandDistancePx)
                        dragOffset != 0f -> animateDragTo(0f)
                    }
                }
            }
            // 滚动内容协调: 内部列表到顶后无法消费的下拉位移经 onPostScroll 派发到本连接,
            // 面板跟随缩回/关闭 (未到顶时列表自己消费, 余量为 0, 面板不动); 上滑则在 onPreScroll
            // 阶段先由面板消费 (展开到全屏后余量才放行给列表) —— 两阶段同 M3
            // ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection 机制, 仅收 UserInput,
            // 排除列表自身 fling/惯性位移; 无指针按下的滚动输入 (滚轮/触控板平移) 只滚列表,
            // 其位移不改变面板高度 (不是"拖面板"的手势)。
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    /**
                     * 当前静止锚点 (吸附目标, 判定与 settleDrag 同源): 展开超 50% 取
                     * 视觉全屏锚点 (负值), 否则取默认锚点 0。
                     */
                    private fun restAnchor(): Float {
                        val expandDistancePx = expandDistance()
                        return if (dragOffset <= -expandDistancePx * 0.5f) -expandDistancePx else 0f
                    }

                    /**
                     * 本次 fling 的吸附已由 onPreFling 按输入速度认领: onPostFling 只归还速度,
                     * 不再按残留位移二次判定 —— 两次回调之间面板位移尚未动画到位, 二次判定
                     * 会把 onPreFling 的吸附取消 (快速短滑被弹回默认高度)。
                     */
                    private var flingClaimed = false

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // 向上位移 (available.y 负) 一律先由面板消费: 展开途中全额吸收,
                        // 触顶当帧消化余量阻断泄露; 面板已在全屏时完全放行给列表
                        if (!touchGesture.value) {
                            return Offset.Zero
                        }
                        if (
                            !dragEnabled.value ||
                            dismissing ||
                            source != NestedScrollSource.UserInput ||
                            available.y >= 0f
                        ) {
                            return Offset.Zero
                        }
                        val expandDistancePx = expandDistance()
                        if (dragOffset <= -expandDistancePx) return Offset.Zero
                        panelDraggedInGesture.value = true
                        // 新手势的位移开始: 撤销上一次 fling 的认领
                        flingClaimed = false
                        bounceJob?.cancel()
                        bounceJob = null
                        dragOffset = (dragOffset + available.y).coerceAtLeast(-expandDistancePx)
                        // 展开途中 (含触顶当帧) 全额消费向上位移, 阻断余量流入列表
                        return Offset(0f, available.y)
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // 只接下拉方向 (available.y 正 = 列表滚到顶继续下滑): 先缩回展开态,
                        // 继续下拉由 settleDrag 判定关闭/回弹。向上的位移在 onPreScroll 已按
                        // 展开量消费完 (面板到全屏后余量放行), 这里不再接。
                        if (!touchGesture.value) {
                            // 无指针按下的滚动输入 (滚轮/触控板平移), 或列表自身 fling/程序化滚动:
                            // 面板不参与
                            return Offset.Zero
                        }
                        if (
                            !dragEnabled.value ||
                            dismissing ||
                            source != NestedScrollSource.UserInput ||
                            available.y <= 0f
                        ) {
                            return Offset.Zero
                        }
                        // 新手势的位移开始: 撤销上一次 fling 的认领
                        flingClaimed = false
                        // 新的拖拽开始: 取消回弹动画, 从当前位移继续
                        bounceJob?.cancel()
                        bounceJob = null
                        val previousOffset = dragOffset
                        // 全屏态缩回半屏区间 (dragOffset < 0): 完全跟手;
                        // 越过半屏进入下拉关闭区间 (dragOffset >= 0): 施加 DragResistance 防误关
                        val step = if (dragOffset < 0f) {
                            val remainingToZero = -dragOffset
                            if (available.y <= remainingToZero) {
                                available.y
                            } else {
                                remainingToZero + (available.y - remainingToZero) * DragResistance
                            }
                        } else {
                            available.y * DragResistance
                        }
                        dragOffset += step
                        val usedY = if (previousOffset < 0f) {
                            val remainingToZero = -previousOffset
                            if (step <= remainingToZero) {
                                step
                            } else {
                                remainingToZero + (step - remainingToZero) / DragResistance
                            }
                        } else {
                            (step / DragResistance).coerceAtMost(available.y)
                        }
                        return Offset(0f, usedY)
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        // 向上 fling (available.y < 0): 推动展开的手势全额吸收惯性用于吸附全屏,
                        // 阻断余量砸向列表; 全屏列表纯滚动时放行给列表自由 fling。
                        if (!dragEnabled.value || dismissing) {
                            return Velocity.Zero
                        }
                        // 列表自身的滚动惯性不改变面板锚点: 向下 fling 一律放行,
                        // 面板缩回/关闭只按手指拖拽累积的位移判定 (见 onPostFling)
                        if (available.y > 0f) return Velocity.Zero
                        // 面板已到全屏时列表可自由 fling; 只有本次手势拖拽过面板且它还有展开余量,
                        // 才由面板吸收向上惯性完成吸附
                        if (panelDraggedInGesture.value && dragOffset > -expandDistance() + 0.5f) {
                            settleDrag(available.y)
                            flingClaimed = true
                            return available
                        }
                        val anchor = restAnchor()
                        if (abs(dragOffset - anchor) < 0.5f) return Velocity.Zero
                        settleDrag(available.y)
                        // 认领本次 fling: 吸附已交给 settleDrag, onPostFling 不再按残留位移判定
                        flingClaimed = true
                        return available
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        // onPreFling 已按输入速度认领本次 fling 的吸附: 只归还速度
                        if (flingClaimed) {
                            flingClaimed = false
                            return available
                        }
                        // 本回调只在"内部列表赢得手势并 fling"时触发 (两条拖拽路径互斥:
                        // 面板跟手的 pointerInput 路径赢得手势时列表无 fling, 不会走到这里;
                        // 列表赢得手势时 pointerInput 已让位, 面板位移的归位与吸附由本回调收尾)。
                        // 因此这里的 fling 必然是列表自身滚动 (滚到顶/底的剩余惯性), 速度
                        // 不得触发"快速下拉关闭"——只按位移判定 (滚到顶后继续下拉、位移达
                        // 阈值仍可关闭) 或回弹复位面板残留位移 (滚动尾段把面板带起的一点位移)。
                        // 面板已在静止锚点 (无需归位) 时不认领这份速度: 交还给列表自己的边界
                        // 回弹效果 (Android 拉伸 overscroll 的 fling 动画), 否则会被我们白吃掉。
                        // 0.5px 容差: 乘除往返的浮点残差不算"需要归位"
                        val anchor = restAnchor()
                        if (abs(dragOffset - anchor) < 0.5f) return Velocity.Zero
                        // 到达这里只可能是下拉方向 (上滑 fling 已由 onPreFling 认领): 只按
                        // 位移判定, 不认列表自身的 fling 速度, 免得滚到顶的惯性关掉面板
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
            // 外部收起请求与内部路径 (返回键/点外部/拖拽) 同走退出动画
            LaunchedEffect(dismissed) {
                if (dismissed) dismissing = true
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
            // 滑入/滑出位移取"0.8 锚点高与面板实测高的较大值": 常规弹层实测高 ≤ 0.8 锚点高,
            // 取值不变; 全屏态面板更高, 靠实测高才能整块滑出窗口而不残留顶部一截
            val anchorSlidePx = with(LocalDensity.current) { AppDialogSizes.fullHeight().toPx() }
            val slideHeightPx = maxOf(sheetHeightPx.toFloat(), anchorSlidePx)
            BottomSheetScaffold(
                // 外部点击与返回键一致走 dismissing 退出动画路径
                onDismissRequest = { dismissing = true },
                anchorMaxHeight = anchorMaxHeight,
                fullScreen = fullScreen,
                expandFraction = expandFraction,
                topOverlap = with(density) { topOverlapPx.toDp() },
                panelMaxWidth = sheetPanelMaxWidth(expandFraction),
                // 拖拽中面板高度改由状态驱动: 上推变高 (dragOffset 负), 下拉保持默认
                // 锚点高并整体下移 (dragOffset 正); 回到默认锚点 (dragOffset == 0) 恢复
                // wrap 布局, 高度连续无跳变
                heightPx = dragHeightPx,
                modifier = Modifier
                    .onSizeChanged {
                        sheetHeightPx = it.height
                        // 未展开时同步默认锚点 (初始 = 内容自然高, 桌面窗口 resize 跟随) 与
                        // 可上拉判定 (面板高达到当时上限 = 被截断; 1px 容差覆盖上限的 dp↔px 往返)
                        if (dragOffset == 0f) {
                            defaultAnchorPx = it.height
                            expandable = it.height >= anchorCapState.value - 1f
                        }
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
                    // 进出场动画期间内容不派生输入 (面板滑出途中点按钮不触发)
                    .blockInputWhile(p < 1f)
                    // 指针按下到全部抬起的手势区间跟踪: 与面板自己是否让位无关 (面板让位后
                    // awaitEachGesture 立即返回, 包在其中的复位会早于手指抬起)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            touchGesture.value = true
                            panelDraggedInGesture.value = false
                            try {
                                // 等所有指针抬起 (不消费事件, 只跟踪)
                                var event = awaitPointerEvent(PointerEventPass.Final)
                                while (event.changes.any { it.pressed }) {
                                    event = awaitPointerEvent(PointerEventPass.Final)
                                }
                            } finally {
                                touchGesture.value = false
                            }
                        }
                    }
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
                            val dragChange =
                                awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                } ?: return@awaitEachGesture
                            // 手指真正开始拖拽才接管归位动画: 纯点击/轻触不打断进行中的吸附动画
                            bounceJob?.cancel()
                            bounceJob = null
                            // 补偿包含防误触门槛在内的全额位移, 消除起步脱节与滞后感
                            val initialDelta = dragChange.position.y - down.position.y
                            val initialStep = if (initialDelta < 0f) {
                                initialDelta
                            } else if (dragOffset < 0f) {
                                val remainingToZero = -dragOffset
                                if (initialDelta <= remainingToZero) {
                                    initialDelta
                                } else {
                                    remainingToZero + (initialDelta - remainingToZero) * DragResistance
                                }
                            } else {
                                initialDelta * DragResistance
                            }
                            dragOffset = (dragOffset + initialStep)
                                .coerceAtLeast(-expandDistance())
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            velocityTracker.addPosition(dragChange.uptimeMillis, dragChange.position)
                            drag(dragChange.id) { change ->
                                val deltaY = change.positionChange().y
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                // 双向: 上推完全跟手; 下拉在展开态完全跟手, 越过半屏关闭方向施加阻力
                                val step = if (deltaY < 0f) {
                                    deltaY
                                } else if (dragOffset < 0f) {
                                    val remainingToZero = -dragOffset
                                    if (deltaY <= remainingToZero) {
                                        deltaY
                                    } else {
                                        remainingToZero + (deltaY - remainingToZero) * DragResistance
                                    }
                                } else {
                                    deltaY * DragResistance
                                }
                                dragOffset = (dragOffset + step)
                                    .coerceAtLeast(-expandDistance())
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
            // 底层压暗走窗口/对话框层 (与居中对话框同一套): Android 补 0.6, skiko 三端用对话框
            // 自带的 0.6 蒙层; E-Ink 分支在上方不补 (对齐原版 E-Ink 清 dim)。
            // 必须排在骨架之后: 骨架里的贴边窗口样式先清掉主题自带暗化, 再由这里统一补 0.6,
            // 顺序颠倒会把补上的 0.6 又清成 0
            PlatformDialogDim()
        }
    }
}

/** 顶部装饰带高 px: 状态栏 (移动端) + 桌面窗口控制条高。 */
@Composable
private fun sheetTopDecorationHeightPx(): Int =
    rememberVisibleStatusBarHeightPx() +
        with(LocalDensity.current) { LocalOverlayTopInset.current.roundToPx() }

/** 弹层可用窗口宽 px (锚点宽; 未注入时回落屏幕宽)。 */
@Composable
private fun sheetWindowWidthPx(): Int =
    LocalDialogAnchorSize.current?.width ?: ScreenInfoProviders.get().screenWidthPx

/** 面板宽度上限: 收起态 = 对话框宽度上限与窗口宽的较小值, 拉到底 = 整窗宽, 中间按比例插值。 */
@Composable
private fun sheetPanelMaxWidth(expandFraction: Float): Dp {
    val windowWidth = with(LocalDensity.current) { sheetWindowWidthPx().toDp() }
    val collapsedWidth = minOf(DesignTokens.dialogMaxWidth, windowWidth)
    return collapsedWidth + (windowWidth - collapsedWidth) * expandFraction
}

/**
 * 底部弹层骨架: 透明点击层铺满 (点击关闭) + sheet 内容贴底。
 * 需作为 Dialog 内容的根, 内容 fillMaxSize 让弹层覆盖整个容器。
 *
 * [fullScreen] 时 sheet 撑满窗口 (仍贴底对齐, 只是高度到顶), 不再受 [anchorMaxHeight] /
 * 0.8 锚点高约束; 外观由调用方按展开比例给 (与手拉到底同一形态)。
 *
 * 顶部装饰带 (状态栏 / 桌面窗口控制条) 的避让由本骨架与面板容器共同承担: 面板可以铺到窗口顶,
 * 骨架把"面板顶进入装饰带的量"经 [LocalSheetTopOverlap] 交给 [AppSheetSurface], 由容器在底色
 * 之内把正文下移 (底色铺满, 顶栏不被状态栏图标压住); 同时给 [LocalStatusBarPaddingEnabled]
 * 提供 false ("此处不叠加状态栏"), content 里的顶栏 (如 [AppTitleBar]) 不再自行叠加 ——
 * 否则叠成两层避让。
 *
 * 面板宽度上限与顶角收平都按 [expandFraction] 插值: 收起态 = 对话框宽度上限, 拉到底 = 整窗宽
 * (窄窗与移动端窗口宽本就小于上限, 恒为铺满)。两个量都由宿主算好传入, 骨架只负责施加。
 *
 * 底层压暗由对话框窗口/层自带 (见 [AppBottomSheetDialog]), 骨架不画; [PlatformSheetWindowStyle]
 * 清掉贴边窗口主题自带的暗化, 让暗度只由 [PlatformDialogDim] 的 0.6 定义, 且 E-Ink 不被主题
 * 默认值压暗。
 */
@Composable
private fun BottomSheetScaffold(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** 收起态面板高上限 (半屏锚点高) */
    anchorMaxHeight: Dp,
    fullScreen: Boolean = false,
    /** 展开到整窗的比例 (0..1): 顶角收平与面板放宽按它插值 */
    expandFraction: Float,
    /** 面板顶已进入顶部装饰带的高度: 正文同量下移, 底色仍铺满 */
    topOverlap: Dp,
    /** 面板宽度上限 (收起态 = 对话框宽度上限, 拉到底 = 整窗宽) */
    panelMaxWidth: Dp,
    /** 非空 = 拖拽展开态的面板固定高度 (px), 由宿主拖拽位移状态驱动 */
    heightPx: Int? = null,
    content: @Composable () -> Unit,
) {
    // 贴边窗口: 系统栏透明 + 清掉主题自带暗化 (面板底色才能铺到窗口顶; 暗度改由
    // [PlatformDialogDim] 统一补 0.6 —— 那一次调用必须排在本骨架之后, 否则清在补后,
    // 补上的 0.6 会被清成 0)
    PlatformSheetWindowStyle()
    // 系统栏图标明暗: 面板顶进入状态栏区域后状态栏那一条由面板底色接管, 按底色亮度定;
    // 导航栏那一条面板恒铺满 (面板贴底, 避让加在底色之内), 恒按底色亮度定
    val bottomBackgroundIsLight = AppTheme.colors.bottomBackground.luminance() >= 0.5f
    PlatformSheetSystemBarIcons(
        lightStatusBarIcons = if (topOverlap > 0.dp) bottomBackgroundIsLight else null,
        lightNavigationBarIcons = bottomBackgroundIsLight,
    )
    Box(Modifier.fillMaxSize().directionKeyFocusNavigation(LocalFocusManager.current)) {
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
                // 宽度上限与居中对话框同一口径、随展开放宽; 钳制须排在 fillMaxWidth 之前
                // (顺序颠倒时上限会被已固定的窗口宽盖掉)
                .widthIn(max = panelMaxWidth)
                .fillMaxWidth()
                .then(
                    when {
                        fullScreen -> Modifier.fillMaxHeight()
                        // 拖拽展开态: 固定高度由位移状态驱动 (wrap 内容贴底留白,
                        // fillMaxSize 内容自然拉伸)
                        heightPx != null -> Modifier.height(with(LocalDensity.current) { heightPx.toDp() })
                        else -> Modifier.heightIn(max = anchorMaxHeight)
                    }
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CompositionLocalProvider(
                LocalStatusBarPaddingEnabled provides false,
                LocalSheetExpandFraction provides expandFraction,
                LocalSheetTopOverlap provides topOverlap,
            ) {
                content()
            }
        }
    }
}

/**
 * 面板拖拽手势: 下拉关闭位移阻力系数 (0.5~0.7 区间, 贴合原生手感与防误关安全门槛)。
 */
private const val DragResistance = 0.6f
