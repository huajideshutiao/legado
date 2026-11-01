package io.legado.app.ui.compose.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.copy
import legado.ui.generated.resources.cut
import legado.ui.generated.resources.ic_arrow_back
import legado.ui.generated.resources.ic_more_vert
import legado.ui.generated.resources.paste
import legado.ui.generated.resources.select_all
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===== 澎湃 OS 浮动文本菜单规格 =====
// 取值来源: /product/app/MiuixEditor/MiuixEditor.apk 的资源表与 res/layout/floating_popup_*.xml
// —— 澎湃这套菜单的本体 (miui-framework 的 DecorViewStubImpl 反射 com.miuix.editor 的
// miuix.toolbar.FloatingActionMode)。Compose 起 ActionMode 时 originatingView 是
// AndroidComposeView 而非 TextView, 拿不到那套, 故自绘对齐其规格。
// 唯一按用户指定偏离 miuix: 阴影 8dp (miuix card_elevation 9dp), 背景走动态主题底栏色。
// 文本工具条圆角: 原版 dialog_corner_radius 13.09dp 按统一令牌归整为 shapeMd (12dp, arco_radius_md)
private val ToolbarShape = DesignTokens.shapeMd
private val ToolbarHeight = 50.dp // floating_toolbar_height
private val ToolbarTextSize = 16.sp // floating_toolbar_text_size
private val ToolbarElevation = 8.dp // 用户指定 (miuix card_elevation 9dp)
private val ToolbarSelectionGap = 18.dp // floating_toolbar_vertical_margin
private val ToolbarScreenMargin = 12.dp // floating_toolbar_min_horizontal_margin

/** 容器左右内边距 (floating_popup_container 的 CardView 内层): content_padding_start/end。 */
private val ToolbarContentPadding = 8.dp

/**
 * 菜单项左右内边距: menu_button_side_padding 8dp + menu_button_only_text_extra_padding 2dp。
 * 本菜单不画图标 (纯文字项), 故恒含那 2dp。叠上 [ToolbarContentPadding] 后首尾项文字距容器边
 * 18dp、项间 20dp, 与 miuix 一致。
 */
private val ToolbarItemSidePadding = 10.dp
private val ToolbarItemMinWidth = 50.dp // floating_toolbar_menu_button_minimum_width

private val OverflowIconSize = 22.dp // floating_toolbar_overflow_icon_width/height
private val OverflowButtonSidePadding = 10.9.dp // floating_toolbar_overflow_button_side_padding
private val OverflowPanelMinWidth = 100.dp // floating_toolbar_overflow_panel_min_width
private val OverflowPanelItemSidePadding = 16.dp // floating_toolbar_overflow_side_padding
private val OverflowPanelMaxHeight = 192.dp // 对齐 AOSP floating_toolbar_maximum_overflow_height

/** 一级横排菜单最多展示项数 (对齐 AOSP/miuix 浮动工具栏规格: 超过时收成 ⋮ 放入二级溢出菜单)。 */
private const val ToolbarMaxPrimaryItems = 4

// 进出场动画: miuix res/anim/fast_fade_in.xml (alpha 0→1, 80ms, @interpolator/decelerate_quad)
// 与 fast_fade_out.xml (alpha 1→0, 140ms, @interpolator/accelerate_quad)。纯 alpha, 无缩放。
// 两个插值器 xml 是无 factor 的 decelerate/accelerateInterpolator, 即 factor=1 的二次曲线。
private const val ToolbarFadeInMillis = 80
private const val ToolbarFadeOutMillis = 140
private val DecelerateQuad = Easing { 1f - (1f - it) * (1f - it) }
private val AccelerateQuad = Easing { it * it }

// 溢出面板开合变形: miuix FloatingToolbar openOverflow/closeOverflow 的 AnimationSet
// (dex 实证: 主体 setDuration(300) + QUARTIC_EASE_IN_OUT_INTERPOLATOR, 旧内容 100ms 淡出,
// 新内容 300ms 同插值器淡入)。变形期间容器右缘/底缘锚定 (⋮ 侧与选区侧不动),
// 见 TextToolbarPlacement 的右缘锚定与 AnimatedContent 的 BottomEnd 对齐。
private const val OverflowMorphMillis = 300
private const val OverflowFadeOutMillis = 100
private val QuarticEaseInOut = Easing { t ->
    if (t < 0.5f) 8f * t * t * t * t else 1f - 8f * (1f - t) * (1f - t) * (1f - t) * (1f - t)
}

/**
 * 弹层四周留白, 让 [ToolbarElevation] 的阴影落在**动画节点内部**。
 * 淡入淡出时 alpha<1 会把绘制提升到离屏缓冲并裁到节点 bounds, 画到界外的阴影会被切掉、
 * 等 alpha 回到 1 才突然出现。定位由 [TextToolbarPlacement] 扣掉这圈留白, 视觉位置不变。
 */
private val ToolbarShadowPadding = ToolbarElevation

/**
 * 本菜单弹层的窗口属性 (expect/actual 桥: common 无法访问各平台 actual 的额外构造参)。
 *
 * skiko 三端 (desktop/iOS/鸿蒙) 的 PopupProperties 默认 usePlatformInsets = true，
 * 框架会把系统栏/安全区从 provider 收到的 anchorBounds/windowSize 里扣掉
 * (UI-1.11.1 skikoMain Popup.skiko.kt:505..570；OHOS-1.9.2 同文件:573..620)，
 * 而本菜单的方向/限高/落点统一用 LocalWindowInfo 全窗 + WindowInsets.safeDrawing
 * 同一份可用区真值，两者必须同源，故 skiko 三端关掉该缩进；Android 的
 * PopupProperties 无此参数 (androidMain 1.11.2)，保持现有属性集。
 */
internal expect fun textMenuPopupProperties(): PopupProperties

/**
 * 弹层可用区 (窗口坐标)：同一 LocalWindowInfo 全窗 + WindowInsets.safeDrawing 四边，
 * 桌面控制条 (LocalOverlayTopInset + OverlayInsetGap) 与安全区上沿同窗口坐标取 max。
 * 组合期测量/限高与布局期定位都用这一份真值；不读框架 provider 的 windowSize
 * (Android<30 clipping=false 分支是 resources.displayMetrics 物理屏尺寸，
 * 与 LocalWindowInfo 的应用窗尺寸不同源)。
 */
@Composable
private fun rememberTextMenuAvailRect(): IntRect {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val overlayTopPx = with(density) { LocalOverlayTopInset.current.roundToPx() }
    val overlayGapPx = with(density) { OverlayInsetGap.roundToPx() }
    val top = max(
        safe.getTop(density),
        if (overlayTopPx > 0) overlayTopPx + overlayGapPx else 0,
    )
    return IntRect(
        left = safe.getLeft(density, layoutDirection),
        top = top,
        right = windowSize.width - safe.getRight(density, layoutDirection),
        bottom = windowSize.height - safe.getBottom(density),
    )
}

/** 矩形按父节点偏移折到窗口坐标（锚与保护矩形同一小范围纯转换，组合期/布局期共用） */
private fun Rect.translatedBy(origin: IntOffset): Rect =
    Rect(left + origin.x, top + origin.y, right + origin.x, bottom + origin.y)

/** 菜单项文本样式, 兼作溢出折叠的测宽依据 (与 [ToolbarItem] 实际渲染一致)。 */
private val ToolbarTextStyle = TextStyle(fontSize = ToolbarTextSize, fontWeight = FontWeight.Medium)

/** 「查找替换」项标签 (对照原版 CodeView 的 ActionMode 菜单项)。 */
internal const val FIND_REPLACE_LABEL = "查找替换"

/** 一个菜单项 (标签 + 动作), 溢出折叠按这个列表切分。 */
internal class AppTextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * 一次菜单请求的内容快照。
 *
 * @param anchor 锚矩形 (含两游标的菜单锚，或图片长按 40px 方区), 坐标空间须与弹层父节点一致
 *   (旧通道给内容树根坐标, 新通道给宿主 Box 局部坐标)
 * @param protectedRects 二维避让矩形 (与 anchor 同坐标系)：定位流程逐帧检查当前可见卡片
 *   与它们的实际相交，能放下时不遮任一 (选区场景传两个真实手柄；无保护需求的调用方不传，
 *   通用机制不绑定具体调用方)
 */
internal class AppTextMenuContent(
    val anchor: Rect,
    val entries: List<AppTextMenuEntry>,
    val protectedRects: List<Rect> = emptyList(),
)

/**
 * 自绘文本菜单的共享状态。目前只挂「查找替换」扩展项, 由两条平台通道
 * (见 [ProvidePlatformTextMenu]) 共读, 故不放在任一通道的实现里。
 */
class AppTextMenuState internal constructor() {
    /** null = 不显示该项, 保证代码编辑屏之外长按选词看不到它。 */
    internal var findReplaceAction: (() -> Unit)? by mutableStateOf(null)
}

internal val LocalAppTextMenuState = staticCompositionLocalOf<AppTextMenuState?> { null }

/**
 * 安装平台文本菜单通道。
 *
 * Android 的 foundation (androidx 1.11.2, 见 libs.versions.toml 的 cmp 映射注释) 里
 * `ComposeFoundationFlags.isNewContextMenuEnabled` 默认 **true**, SelectionContainer 与
 * BasicTextField 全改读 `LocalTextContextMenuToolbarProvider`, 旧的 [LocalTextToolbar] 恒不
 * 被调用; 该 local 为 null 时框架自己装 AndroidTextContextMenuToolbarProvider 起平台
 * ActionMode —— 这就是系统菜单 (含澎湃「流转」等项) 顶掉自绘的原因。Android actual 提前占位。
 *
 * 桌面/iOS/鸿蒙走 JetBrains 的 foundation 构建, 同一 flag 为 **false** (且新通道的
 * skiko/native actual 还是空实现, CMP-7819), 选区菜单仍走 [LocalTextToolbar], 直接透传。
 */
@Composable
internal expect fun ProvidePlatformTextMenu(
    state: AppTextMenuState,
    content: @Composable () -> Unit,
)

/**
 * 注入两条文本菜单通道并挂上弹层宿主。由 [AppTheme] 调用 —— app 端根组合经
 * BaseComposeActivity 的 `AppTheme { Content() }` 覆盖全部路由。
 *
 * 两条通道同时装但互斥: 同一平台上 `isNewContextMenuEnabled` 只有一个取值, 框架只会走其中
 * 一条。这样即使该 flag 的默认值随版本翻转, 菜单外观也不变。
 */
@Composable
fun ProvideAppTextToolbar(content: @Composable () -> Unit) {
    val state = remember { AppTextMenuState() }
    val legacyToolbar = remember { LegacyAppTextToolbar() }
    CompositionLocalProvider(
        LocalAppTextMenuState provides state,
        LocalTextToolbar provides legacyToolbar,
    ) {
        ProvidePlatformTextMenu(state) { content() }
        legacyToolbar.Host(state)
    }
}

/**
 * 旧通道 ([LocalTextToolbar]) 适配, 桌面/iOS/鸿蒙生效。
 *
 * 这条通道的签名不带选中文本, 也没有平台附加项 (三端本身没有 ACTION_PROCESS_TEXT 等价机制),
 * 故只列框架四项 + 查找替换。
 */
private class LegacyAppTextToolbar : TextToolbar {

    private var params by mutableStateOf<Params?>(null)

    override val status: TextToolbarStatus
        get() = if (params != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    // 1.11 起 BasicTextField 调 6 参数版本; 两个签名都显式 override, 不依赖
    // interface default 转发链路 (曾因此导致长按菜单不弹出)。
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        params = Params(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        params = Params(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        params = null
    }

    private class Params(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
        val onPaste: (() -> Unit)?,
        val onCut: (() -> Unit)?,
        val onSelectAll: (() -> Unit)?,
    )

    @Composable
    fun Host(state: AppTextMenuState) {
        // 不提前 return: 退场动画期间 params 已为 null, 宿主仍要参与组合
        val p = params
        // 顺序对齐 framework Editor: 剪切/复制/粘贴/全选 → 查找替换
        val cutText = stringResource(Res.string.cut)
        val copyText = stringResource(Res.string.copy)
        val pasteText = stringResource(Res.string.paste)
        val selectAllText = stringResource(Res.string.select_all)
        val findReplace = state.findReplaceAction
        val content = remember(p, findReplace, cutText, copyText, pasteText, selectAllText) {
            if (p == null) return@remember null
            val entries = buildList {
                p.onCut?.let { add(AppTextMenuEntry(cutText, it)) }
                p.onCopy?.let { add(AppTextMenuEntry(copyText, it)) }
                p.onPaste?.let { add(AppTextMenuEntry(pasteText, it)) }
                p.onSelectAll?.let { add(AppTextMenuEntry(selectAllText, it)) }
                findReplace?.let { action ->
                    add(AppTextMenuEntry(FIND_REPLACE_LABEL) { hide(); action() })
                }
            }
            entries.takeIf { it.isNotEmpty() }?.let { AppTextMenuContent(p.rect, it) }
        }
        AppTextMenuHost(content) { hide() }
    }
}

/**
 * 自绘文本选择菜单宿主, 四端共用, 视觉参考澎湃 OS 的浮动文本菜单 (见顶部规格常量)。
 * 一行放不下时尾部收成 ⋮, 点开变形为竖排溢出面板, ← 收回横排 (落在 ⋮ 原位:
 * 向上展开时在面板底部, 向下展开时在面板顶部)。
 *
 * @param content null = 隐藏。退场动画期间原请求已失效 (新通道的 dataProvider 已解绑),
 *   故用最后一帧内容把弹层留住, 动画播完再卸载弹层窗口。
 * @param onDismiss 点击弹层内空白区 (卡片外) 时回调, 由调用方关闭自己的菜单请求源。
 */
@Composable
internal fun AppTextMenuHost(
    content: AppTextMenuContent?,
    onDismiss: () -> Unit,
) {
    var lastContent by remember { mutableStateOf<AppTextMenuContent?>(null) }
    val visibleState = remember { MutableTransitionState(false) }
    if (content != null) lastContent = content
    visibleState.targetState = content != null
    if (visibleState.isIdle && !visibleState.currentState) {
        if (lastContent != null) lastContent = null
        return
    }
    val data = lastContent ?: return

    val eInk = LocalEInk.current
    val density = LocalDensity.current
    val gapPx = with(density) { ToolbarSelectionGap.roundToPx() }
    val marginPx = with(density) { ToolbarScreenMargin.roundToPx() }
    val shadowPx = with(density) { ToolbarShadowPadding.roundToPx() }
    // 弹层可用区真值 (窗口坐标): 全窗 + WindowInsets.safeDrawing, 桌面控制条与安全区
    // 上沿取 max。组合期测量/限高与布局期落点只用这一份, 不读框架 provider 的 windowSize。
    val availRect = rememberTextMenuAvailRect()
    // 菜单项每帧重建 (标签来自快照感知的 data()), 用标签串当稳定 key, 避免重组重置折叠态
    val labelsKey = data.entries.joinToString("\u0000") { it.label }
    // 折叠位置与横排/面板尺寸出自同一遍度量 (见 rememberMenuMetrics); 一级折叠按可用宽度
    val metrics = rememberMenuMetrics(data.entries, labelsKey, marginPx, availRect.width)
    val visibleCount = metrics.visibleCount
    var overflowOpen by remember(labelsKey) { mutableStateOf(false) }
    val overflowTransition = updateTransition(overflowOpen, label = "overflow")
    val hasOverflow = visibleCount < data.entries.size
    // 弹层父节点在窗口中的偏移 (与 Popup 的 anchorBounds 同一节点同一真值):
    // null = 尚未取得布局真值, 此时不组合弹层, 不按伪原点产生可见输出
    var parentWindowOffset by remember { mutableStateOf<IntOffset?>(null) }
    // 决策输入归一化到可用区局部坐标 (原点 = 可用区左上), 复用同一 decide 纯函数;
    // 落点由 textToolbarPosition 加回可用区原点
    fun decideInAvail(
        anchorInAvail: Rect,
        availSize: IntSize,
        protectedInAvail: List<Rect>,
    ) = TextToolbarPlacement.decide(
        anchorOnWindow = anchorInAvail,
        windowSize = availSize,
        gapPx = gapPx,
        marginPx = marginPx,
        topBoundPx = marginPx,
        rowWidthPx = metrics.rowSize.width,
        rowHeightPx = metrics.rowSize.height,
        panelWidthPx = metrics.panelSize.width,
        panelFullHeightPx = metrics.panelSize.height,
        hasOverflow = hasOverflow,
        protectedRects = protectedInAvail,
    )
    Box(
        Modifier.onGloballyPositioned { coords ->
            // 每轴 roundToInt 折 IntOffset (同 CodeTextField 的字段窗口位置写法)
            val pos = coords.positionInWindow()
            val offset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            if (offset != parentWindowOffset) parentWindowOffset = offset
        },
    ) {
        val layoutOrigin = parentWindowOffset
        // 父节点偏移/可用区未取得真值前不组合弹层 (框架在无法定位弹层时同样隐藏)
        if (layoutOrigin != null && availRect.width > 0 && availRect.height > 0) {
            val popupProperties = remember { textMenuPopupProperties() }
            // 锚与保护矩形同为父节点局部坐标: 按同一父偏移折窗口坐标, 再归一化到可用区
            fun Rect.inAvail(): Rect = Rect(
                left - availRect.left,
                top - availRect.top,
                right - availRect.left,
                bottom - availRect.top,
            )
            val anchorInAvail = remember(data.anchor, layoutOrigin, availRect) {
                data.anchor.translatedBy(layoutOrigin).inAvail()
            }
            val protectedInAvail = remember(data.protectedRects, layoutOrigin, availRect) {
                data.protectedRects.map { it.translatedBy(layoutOrigin).inAvail() }
            }
            // 组合期决策驱动面板限高; 布局期 provider 用框架 anchorBounds 复算同参决策
            val placement = remember(
                anchorInAvail, protectedInAvail, availRect, gapPx, marginPx,
                metrics.rowSize, metrics.panelSize, hasOverflow,
            ) {
                decideInAvail(anchorInAvail, IntSize(availRect.width, availRect.height), protectedInAvail)
            }
            // 溢出面板可滚区上限: 展开位置上的可用空间 (含保护截断/空隙选择) 扣掉返回行
            // (AOSP updateOverflowHeight 按可用收高), 再受规格上限 192dp 约束。
            // 可用区容纳返回行时渲染高 ≤ 该可用空间; 可用区不足返回行高时面板最小可见高
            // 仍为返回行, 会超出该可用空间 (如实)
            val panelListMaxHeightPx = min(
                with(density) { OverflowPanelMaxHeight.roundToPx() },
                (placement.panelAvailHeightPx - metrics.rowSize.height).coerceAtLeast(0),
            )
            // 弹层内容测量尺寸含两圈阴影留白；定位按扣除后的视觉卡片尺寸算，
            // 开合变形动画期间逐帧变化，锚边（选区侧或横排原位）静止
            val positionProvider = remember(
                data.anchor, data.protectedRects, availRect, gapPx, marginPx, shadowPx,
                metrics.rowSize, metrics.panelSize, hasOverflow,
            ) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = textToolbarPosition(
                        anchor = data.anchor,
                        anchorBounds = anchorBounds,
                        availRect = availRect,
                        popupContentSize = popupContentSize,
                        // 锚与保护矩形同为父局部坐标, 由定位函数按本次 anchorBounds 一并折窗口
                        protectedRects = data.protectedRects,
                        decide = ::decideInAvail,
                        shadowPx = shadowPx,
                        gapPx = gapPx,
                        marginPx = marginPx,
                        rowWidthPx = metrics.rowSize.width,
                        rowHeightPx = metrics.rowSize.height,
                    )
                }
            }
            // focusable=false: 不抢文本框焦点/选区, 显隐交由框架回调驱动
            // onDismissRequest 空实现: 鼠标微动会触发系统 dismiss 回调清空选区菜单,
            // 改由框架 (旧通道 hide() / 新通道取消 show 协程) 在选区真正清除时统一隐藏。
            // 直接用框架 Popup: properties 由 [textMenuPopupProperties] 按平台构造
            // (skiko 三端关 usePlatformInsets), 不再经 AppPopup 的二次外框钳位,
            // 避免非零上沿时改动本菜单已按可用区全量算好的落点
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {},
                properties = popupProperties,
            ) {
                AnimatedVisibility(
                    visibleState = visibleState,
                    // E-Ink 屏不做渐变动画 (残影且无灰阶过渡意义)
                    enter = if (eInk) EnterTransition.None else {
                        fadeIn(tween(ToolbarFadeInMillis, easing = DecelerateQuad))
                    },
                    exit = if (eInk) ExitTransition.None else {
                        fadeOut(tween(ToolbarFadeOutMillis, easing = AccelerateQuad))
                    },
                ) {
                    // 空白捕获层挂最外层 (含阴影留白圈): Android 的 Popup 是独立窗口且窗口尺寸
                    // 跟随内容, 落在卡片外阴影圈的点击下层收不到, 与其白吞不如直接关菜单。
                    // 手势检测都在主传递 (叶→根) 等按下, 卡片上的点击先被子项消费, 本层拿到的是
                    // 已消费事件不会误触发。
                    Box(
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                            .padding(ToolbarShadowPadding),
                    ) {
                        ToolbarSurface(eInk) {
                            overflowTransition.AnimatedContent(
                                // 开合变形: 容器尺寸 300ms 四次缓入缓出, 旧内容 100ms 淡出、新内容 300ms
                                // 淡入 (miuix openOverflow/closeOverflow 同参); E-Ink 直接切换。
                                // 按面板展开方向贴住容器右上/右下角，变形期间锚边保持静止
                                transitionSpec = {
                                    if (eInk) {
                                        (EnterTransition.None togetherWith ExitTransition.None) using
                                            SizeTransform { _, _ -> snap() }
                                    } else {
                                        fadeIn(tween(OverflowMorphMillis, easing = QuarticEaseInOut)) togetherWith
                                            fadeOut(tween(OverflowFadeOutMillis, easing = QuarticEaseInOut)) using
                                            SizeTransform { _, _ ->
                                                tween(OverflowMorphMillis, easing = QuarticEaseInOut)
                                            }
                                    }
                                },
                                contentAlignment = if (placement.expandsUpwards) Alignment.BottomEnd else Alignment.TopEnd,
                            ) { open ->
                                if (open) {
                                    OverflowPanel(
                                        data.entries.drop(visibleCount),
                                        collapseAtTop = !placement.expandsUpwards,
                                        maxListHeight = with(density) { panelListMaxHeightPx.toDp() },
                                        onCollapse = { overflowOpen = false },
                                    )
                                } else {
                                    Row(
                                        Modifier.padding(horizontal = ToolbarContentPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        data.entries.take(visibleCount).forEach { ToolbarItem(it.label, it.onClick) }
                                        if (visibleCount < data.entries.size) {
                                            OverflowButton { overflowOpen = true }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 菜单排布度量: 标签宽度只量一遍, 折叠位置 (一行放不下时尾部收 ⋮) 与横排/溢出面板的
 * 最终尺寸都从这一遍推出 —— 两者用同一批 padding/最小宽常量, 分开算过就会漂移。
 * 一级折叠按可用区宽度 ([availWidthPx], 窗口全宽减安全区/控制条) 留出屏边距, 宽度未
 * 取得真值 (0) 时不折叠。
 */
@Composable
private fun rememberMenuMetrics(
    entries: List<AppTextMenuEntry>,
    labelsKey: String,
    marginPx: Int,
    availWidthPx: Int,
): MenuMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labelsKey, availWidthPx, marginPx, density) {
        with(density) {
            val itemSidePaddingPx = (ToolbarItemSidePadding * 2).roundToPx()
            val itemMinWidthPx = ToolbarItemMinWidth.roundToPx()
            val contentPaddingPx = (ToolbarContentPadding * 2).roundToPx()
            val overflowButtonWidthPx =
                (OverflowIconSize + OverflowButtonSidePadding * 2).roundToPx()
            val toolbarHeightPx = ToolbarHeight.roundToPx()
            val panelSidePaddingPx = (OverflowPanelItemSidePadding * 2).roundToPx()
            val panelMinWidthPx = OverflowPanelMinWidth.roundToPx()
            val panelMaxHeightPx = OverflowPanelMaxHeight.roundToPx()

            val textWidths = entries.map {
                measurer.measure(AnnotatedString(it.label), ToolbarTextStyle).size.width
            }
            // 测宽增加 2px 防抖留白, 避免桌面/Skia 字体栅格化微小差异导致的边缘裁切
            val itemWidths = textWidths.map { max(it + itemSidePaddingPx + 2, itemMinWidthPx) }
            val available = availWidthPx - marginPx * 2 - contentPaddingPx
            val maxPrimary = if (entries.size <= 5) entries.size else ToolbarMaxPrimaryItems
            val visibleCount =
                if (availWidthPx > 0 && entries.size <= 5 && itemWidths.sum() <= available) {
                    entries.size
                } else {
                    // 放不下或超过规格时给 ⋮ 留位, 尾部项收进二级面板
                    var used = overflowButtonWidthPx
                    var n = 0
                    while (
                        n < itemWidths.size && n < maxPrimary &&
                        (availWidthPx <= 0 || used + itemWidths[n] <= available)
                    ) {
                        used += itemWidths[n]
                        n++
                    }
                    n.coerceAtLeast(1)
                }
            val hasOverflow = visibleCount < entries.size
            val rowSize = IntSize(
                contentPaddingPx + itemWidths.take(visibleCount).sum() +
                    if (hasOverflow) overflowButtonWidthPx else 0,
                toolbarHeightPx,
            )
            if (!hasOverflow) {
                return@with MenuMetrics(visibleCount, rowSize, rowSize)
            }
            val panelWidth = max(
                panelMinWidthPx,
                (textWidths.drop(visibleCount).maxOrNull() ?: 0) + panelSidePaddingPx + 4,
            )
            // 面板 = 可滚项区 (受最高档约束) + 底部固定返回行
            val panelHeight = min(
                (entries.size - visibleCount) * toolbarHeightPx,
                panelMaxHeightPx,
            ) + toolbarHeightPx
            MenuMetrics(visibleCount, rowSize, IntSize(panelWidth, panelHeight))
        }
    }
}

private data class MenuMetrics(
    val visibleCount: Int,
    val rowSize: IntSize,
    val panelSize: IntSize,
)

/** 卡片外壳: 动态底栏色 + 12dp 圆角 (shapeMd) + 8dp 阴影; E-Ink 去阴影改描边。 */
@Composable
private fun ToolbarSurface(
    eInk: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .then(if (eInk) Modifier else Modifier.shadow(ToolbarElevation, ToolbarShape))
            .clip(ToolbarShape)
            .background(AppTheme.colors.bottomBackground)
            .then(
                if (eInk) {
                    Modifier.border(DesignTokens.strokeThin, AppTheme.colors.primaryText, ToolbarShape)
                } else {
                    Modifier
                }
            ),
        content = { content() },
    )
}

/** 菜单项: 50dp 高 / 最小宽 50dp / 左右 10dp / 16sp medium (对齐 miuix sans-serif-medium)。 */
@Composable
private fun ToolbarItem(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(ToolbarHeight)
            .widthIn(min = ToolbarItemMinWidth)
            .clickable(onClick = onClick)
            .padding(horizontal = ToolbarItemSidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppTheme.colors.primaryText,
            fontSize = ToolbarTextSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** ⋮ 溢出按钮: 22dp 图标区 (项目资源 ic_more_vert), 左右 10.9dp (对齐 miuix overflow 档)。 */
@Composable
private fun OverflowButton(onClick: () -> Unit) {
    Box(
        Modifier
            .height(ToolbarHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = OverflowButtonSidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_more_vert),
            contentDescription = null,
            tint = AppTheme.colors.primaryText,
            modifier = Modifier.size(OverflowIconSize),
        )
    }
}

/**
 * 溢出面板: 竖排剩余项, 最小宽 100dp / 项左右 16dp / 可滚区受规格上限与展开方向可用空间
 * (取小, 见调用处) 双重约束; 一端固定返回按钮。
 *
 * @param collapseAtTop 面板向下展开时返回按钮放列表上方, 向上展开时放下方。两种摆法都让
 *   ← 落在展开前 ⋮ 的位置上, 变形期间该行不动 (同 AOSP LocalFloatingToolbarPopup
 *   按 mOpenOverflowUpwards 给溢出按钮定的顶/底锚)。
 * @param maxListHeight 可滚项区高度上限 (Dp): 展开方向可用空间扣掉返回行后再受 192dp
 *   规格约束 (AOSP updateOverflowHeight 按可用空间收溢出项数的同语义)
 */
@Composable
private fun OverflowPanel(
    entries: List<AppTextMenuEntry>,
    collapseAtTop: Boolean,
    maxListHeight: Dp,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // 固有宽定容 (最宽项决定面板宽, 受 min 100dp 约束), 窄项才能通栏填满
        modifier
            .widthIn(min = OverflowPanelMinWidth)
            .width(IntrinsicSize.Min),
    ) {
        if (collapseAtTop) CollapseButton(onClick = onCollapse)
        Column(
            Modifier
                .heightIn(max = maxListHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            entries.forEach { entry ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ToolbarHeight)
                        .clickable(onClick = entry.onClick)
                        .padding(horizontal = OverflowPanelItemSidePadding),
                    // 通栏行文字左对齐: 高亮 (ripple) 充满整行宽
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = entry.label,
                        color = AppTheme.colors.primaryText,
                        fontSize = ToolbarTextSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        if (!collapseAtTop) CollapseButton(onClick = onCollapse)
    }
}

/** 面板返回按钮: ← 箭头 (项目资源 ic_arrow_back, 同实机), 行高同菜单项, 点击收回横排。 */
@Composable
private fun CollapseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = OverflowPanelItemSidePadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_back),
            contentDescription = null,
            tint = AppTheme.colors.primaryText,
            modifier = Modifier.size(OverflowIconSize),
        )
    }
}

/**
 * 弹层落位决策 (纯几何, 测试直接驱动)。
 *
 * [decide] 全部基于窗口坐标与同一 windowSize: 收起态横排按横排实际行高定上下方向；
 * 溢出面板按 AOSP FloatingToolbar.refreshCoordinatesAndOverflowDirection 同序四分支
 * 安排展开方向；面板实际 x 范围与落点函数共用同一横向钳位，与保护矩形二维相交时把
 * 面板生长截断到保护边之外，截断后放不下最小面板 (返回行+2项) 的分支跳过；四个锚定
 * 分支都放不下时在可用区内找保护矩形 (含 gap 缓冲) 之间的空隙，取最高可容面板的间隔
 * 放面板；所有实际空隙都不足以容纳面板最小可见高度时面板按剩余空间收高仍可能与保护
 * 矩形重叠 —— 那是物理放不下的唯一情形，如实重叠不宣称避让成功。
 *
 * @param opensUpwards 收起态横排落位方向: 锚顶上方放得下横排 (含 gap) 为 true
 * @param expandsUpwards 溢出面板展开方向 (四分支, 无溢出时与 [opensUpwards] 同)
 * @param panelAvailHeightPx 面板展开位置上的可用高度 (含保护截断/空隙选择, 渲染层按它收面板)
 * @param rowX 横排左缘 (屏边距钳位后)，落点函数与保护检测共用同一横向基准
 * @param panelTopOverridePx 非空 = 四个锚定分支都放不下，面板顶直接放该窗口坐标
 *   (从保护空隙中选出)；空隙不足时面板仍放该处并可能与保护重叠
 */
internal class TextToolbarPlacement private constructor(
    val opensUpwards: Boolean,
    val expandsUpwards: Boolean,
    val panelAvailHeightPx: Int,
    val rowX: Int,
    val panelTopOverridePx: Int?,
) {
    companion object {
        /** AOSP 溢出方向判定门槛: 返回行 + 最小 2 项 (MIN_OVERFLOW_SIZE) */
        private fun minPanelHeightPx(panelFullHeightPx: Int, rowHeightPx: Int): Int =
            minOf(panelFullHeightPx, 3 * rowHeightPx)

        /**
         * 四分支 (AOSP 同序):
         * 1. 锚顶上方放得下面板 → 上放横排 + 面板向上展开 (底 = 锚顶 - gap)
         * 2. 上方只够横排、过选区向下够面板 → 上放横排 + 面板向下展开 (顶 = 横排顶)
         * 3. 锚底下方放得下面板 → 下放横排 + 面板向下展开 (顶 = 锚底 + gap)
         * 4. 下方只够横排、过选区向上够面板 → 下放横排 + 面板向上展开 (底 = 横排底)
         * 5. 都不够 → 在可用区内找保护空隙放面板 (panelTopOverridePx)
         */
        fun decide(
            anchorOnWindow: Rect,
            windowSize: IntSize,
            gapPx: Int,
            marginPx: Int,
            topBoundPx: Int,
            rowWidthPx: Int,
            rowHeightPx: Int,
            panelWidthPx: Int,
            panelFullHeightPx: Int,
            hasOverflow: Boolean,
            protectedRects: List<Rect>,
        ): TextToolbarPlacement {
            // 几何数值口径: 浮点锚/保护边界一律折像素整数, 与面板之间的边界向"远离"
            // 方向取整 —— 上方边界向下取整、下方边界向上取整、保护矩形四边外扩;
            // 舍入只会增大间距, 不吞分数像素也不会因舍入侵入保护
            val anchorTop = floor(anchorOnWindow.top).toInt()
            val anchorBottom = ceil(anchorOnWindow.bottom).toInt()
            val centerX = ((anchorOnWindow.left + anchorOnWindow.right) / 2f).roundToInt()
            val rowX = (centerX - rowWidthPx / 2)
                .coerceIn(marginPx, (windowSize.width - rowWidthPx - marginPx).coerceAtLeast(marginPx))
            val opensUpwards = anchorTop - rowHeightPx - gapPx >= topBoundPx
            if (!hasOverflow) {
                return TextToolbarPlacement(
                    opensUpwards = opensUpwards, expandsUpwards = opensUpwards,
                    panelAvailHeightPx = 0, rowX = rowX, panelTopOverridePx = null,
                )
            }
            // 面板/横排的实际 x 范围: 与落点函数共用同一横向钳位（rightAlignedX）。
            // 保护检测用两态并集作包络 —— 开合变形期间的中间宽度矩形恒落在横排与
            // 面板两矩形之间，被该包络覆盖，不因单帧宽度变化漏判右侧/左侧保护
            val rowRight = rowX + rowWidthPx
            val panelX = rightAlignedX(rowX, rowWidthPx, panelWidthPx, windowSize.width, marginPx)
            val panelLeft = minOf(rowX, panelX)
            val panelRight = maxOf(rowRight, panelX + panelWidthPx)
            // 保护矩形折整并四边外扩（floor 左上、ceil 右下）
            val guards = protectedRects.map {
                IntRect(
                    floor(it.left).toInt(),
                    floor(it.top).toInt(),
                    ceil(it.right).toInt(),
                    ceil(it.bottom).toInt(),
                )
            }
            // 面板区间 [lo, hi] 沿生长方向与保护矩形 x 重叠时截断到保护边之外;
            // 纵向不相交的保护不约束
            fun availClamped(lo: Int, hi: Int, growsUp: Boolean): Int {
                var avail = hi - lo
                for (g in guards) {
                    if (panelLeft >= g.right || panelRight <= g.left) continue
                    if (lo < g.bottom && hi > g.top) {
                        avail = if (growsUp) minOf(avail, hi - g.bottom - gapPx)
                        else minOf(avail, g.top - gapPx - lo)
                    }
                }
                return avail
            }
            val minPanel = minPanelHeightPx(panelFullHeightPx, rowHeightPx)
            // 分支 1: 面板底 = 锚顶 - gap, 向上长
            val avail1 = availClamped(topBoundPx, anchorTop - gapPx, growsUp = true)
            if (avail1 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = true, expandsUpwards = true,
                    panelAvailHeightPx = avail1, rowX = rowX, panelTopOverridePx = null,
                )
            }
            // 分支 2: 面板顶 = 横排顶 = 锚顶 - rowH - gap, 向下长
            val panelTop2 = anchorTop - rowHeightPx - gapPx
            val avail2 = availClamped(panelTop2, windowSize.height - marginPx, growsUp = false)
            if (opensUpwards && avail2 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = true, expandsUpwards = false,
                    panelAvailHeightPx = avail2, rowX = rowX, panelTopOverridePx = null,
                )
            }
            // 分支 3: 面板顶 = 锚底 + gap, 向下长
            val avail3 = availClamped(anchorBottom + gapPx, windowSize.height - marginPx, growsUp = false)
            if (avail3 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = false, expandsUpwards = false,
                    panelAvailHeightPx = avail3, rowX = rowX, panelTopOverridePx = null,
                )
            }
            // 分支 4: 面板底 = 横排底 = 锚底 + gap + rowH, 向上长
            val panelBottom4 = anchorBottom + gapPx + rowHeightPx
            val rowFitsBelow = panelBottom4 <= windowSize.height - marginPx
            val avail4 = availClamped(topBoundPx, panelBottom4, growsUp = true)
            if (rowFitsBelow && avail4 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = false, expandsUpwards = true,
                    panelAvailHeightPx = avail4, rowX = rowX, panelTopOverridePx = null,
                )
            }
            // 四个锚定分支都放不下: 在可用区内按同一实际面板 x 收集与保护矩形 (含 gap
            // 缓冲) 相交的区段, 求剩余空隙, 取最高可容面板的间隔放面板; 所有空隙都不足
            // 面板最小可见高度 (返回行) 时仍放最大空隙处, 面板按剩余空间收高并可能与
            // 保护矩形重叠 —— 那是物理放不下的唯一路径, 如实重叠不宣称避让成功
            val bars = guards.mapNotNull { g ->
                if (panelLeft >= g.right || panelRight <= g.left) return@mapNotNull null
                val top = maxOf(g.top - gapPx, topBoundPx)
                val bottom = minOf(g.bottom + gapPx, windowSize.height - marginPx)
                if (bottom > top) top to bottom else null
            }.sortedBy { it.first }
            val gaps = ArrayList<Pair<Int, Int>>()
            var cursor = topBoundPx
            for ((barTop, barBottom) in bars) {
                if (barTop > cursor) gaps.add(cursor to barTop - cursor)
                if (barBottom > cursor) cursor = barBottom
            }
            if (cursor < windowSize.height - marginPx) {
                gaps.add(cursor to windowSize.height - marginPx - cursor)
            }
            val best = gaps.maxByOrNull { it.second }
            return TextToolbarPlacement(
                opensUpwards = false, expandsUpwards = false,
                panelAvailHeightPx = best?.second ?: 0,
                rowX = rowX,
                panelTopOverridePx = best?.first ?: topBoundPx,
            )
        }
    }
}

/** 横排右缘对齐内容并钳到屏边距：落点与保护检测共用同一横向钳位 (同文件私有) */
private fun rightAlignedX(rowX: Int, rowWidthPx: Int, contentWidth: Int, windowWidth: Int, marginPx: Int): Int =
    (rowX + rowWidthPx - contentWidth)
        .coerceIn(marginPx, (windowWidth - contentWidth - marginPx).coerceAtLeast(marginPx))

/**
 * 弹层落点 (布局期逐帧)。
 *
 * [anchor] 与 [protectedRects] 是弹层父节点局部坐标，按本次 [anchorBounds].topLeft 折到
 * 窗口坐标（与框架交给 provider 的 anchorBounds 同一份输入，父位置变化时锚与保护矩形
 * 同步）；再归一化到 [availRect]（本菜单可用区真值：全窗 + safeDrawing，桌面控制条与
 * 安全区上沿取 max），复用 [TextToolbarPlacement.decide] 同一纯函数，返回时加回真实
 * 可用区原点。视觉尺寸 = 弹层内容实测扣两圈阴影留白，返回时只外扩一次留白。
 *
 * 不读 provider 的 windowSize（Android<30 clipping=false 分支为物理屏尺寸，与可用区
 * 真值不同源）。锚/保护边界均折像素整数并向远离方向取整，可用高度自然向下取整。
 */
internal fun textToolbarPosition(
    anchor: Rect,
    anchorBounds: IntRect,
    availRect: IntRect,
    popupContentSize: IntSize,
    protectedRects: List<Rect>,
    decide: (anchorInAvail: Rect, availSize: IntSize, protectedInAvail: List<Rect>) -> TextToolbarPlacement,
    shadowPx: Int,
    gapPx: Int,
    marginPx: Int,
    rowWidthPx: Int,
    rowHeightPx: Int,
): IntOffset {
    // 视觉卡片尺寸 = 弹层内容实测 - 两圈阴影留白
    val width = popupContentSize.width - shadowPx * 2
    val height = popupContentSize.height - shadowPx * 2
    val windowOrigin = IntOffset(anchorBounds.left, anchorBounds.top)
    val windowAnchor = anchor.translatedBy(windowOrigin)
    // 归一化到可用区局部坐标 (原点 = 可用区左上)
    fun Rect.inAvail(): Rect = Rect(
        left - availRect.left,
        top - availRect.top,
        right - availRect.left,
        bottom - availRect.top,
    )
    // 锚与保护矩形同属父局部坐标, 在这里按本次 anchorBounds 一起折窗口再归一化
    val placement = decide(
        windowAnchor.inAvail(),
        IntSize(availRect.width, availRect.height),
        protectedRects.map { it.translatedBy(windowOrigin).inAvail() },
    )
    // 横排/面板右缘对齐并钳到可用区边距 (与保护检测同一钳位)
    val x = rightAlignedX(placement.rowX, rowWidthPx, width, availRect.width, marginPx)
    // 纵向: 同侧展开锚边 = 选区侧 (内容高变 y 随动); 对侧展开锚边 = 横排原位 (窗口
    // 向反方向生长); 可用区能容纳面板与边距时本体落在可用区内; 面板最小可见高
    // (返回行) 本身超过可用区时边界不成立, 如实越界 (物理限制)
    val anchorInAvail = windowAnchor.inAvail()
    val anchorTop = floor(anchorInAvail.top).toInt()
    val anchorBottom = ceil(anchorInAvail.bottom).toInt()
    val sameSide = placement.opensUpwards == placement.expandsUpwards
    val overrideTop = placement.panelTopOverridePx
    val y = when {
        overrideTop != null -> overrideTop
        sameSide && placement.opensUpwards -> anchorTop - height - gapPx
        sameSide -> anchorBottom + gapPx
        placement.opensUpwards -> anchorTop - rowHeightPx - gapPx
        else -> anchorBottom + gapPx + rowHeightPx - height
    }
    // 物理空隙不足时面板可能仍与保护重叠 (如实); 可用区能容纳面板高 + 上下边距时
    // 本体落在可用区内, 返回行本身超过可用区高时边界不成立 (如实越界)
    val maxTop = (availRect.height - height - marginPx).coerceAtLeast(marginPx)
    val boundedY = y.coerceIn(marginPx, maxTop)
    // 可用区局部坐标 → 窗口坐标（含阴影留白外扩）
    return IntOffset(x - shadowPx + availRect.left, boundedY - shadowPx + availRect.top)
}

/**
 * 为文本选择菜单注册「查找替换」项。
 *
 * 动作无参 (两条通道的回调都不带选中文本), 由注册方自行从聚焦编辑器取选区。
 * 注销带归属校验: 导航过渡期新旧屏幕并存时只清自己注册的那个。
 */
@Composable
fun TextToolbarFindReplaceEffect(action: () -> Unit) {
    val state = LocalAppTextMenuState.current
    val currentAction by rememberUpdatedState(action)
    DisposableEffect(state) {
        if (state == null) return@DisposableEffect onDispose {}
        val registeredAction: () -> Unit = { currentAction() }
        state.findReplaceAction = registeredAction
        onDispose {
            if (state.findReplaceAction === registeredAction) {
                state.findReplaceAction = null
            }
        }
    }
}
