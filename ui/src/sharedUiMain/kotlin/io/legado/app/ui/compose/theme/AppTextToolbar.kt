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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.ScrollStateFastScrollbar
import io.legado.app.ui.compose.component.fastScrollPlatform
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.OverlayInsetGap
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.back
import legado.ui.generated.resources.copy
import legado.ui.generated.resources.cut
import legado.ui.generated.resources.ic_arrow_back
import legado.ui.generated.resources.ic_more_vert
import legado.ui.generated.resources.more
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

/**
 * 溢出面板上下边缘的默认细指示: 对齐 AOSP core/res/res/drawable/scroll_indicator_material.xml
 * (solid #1f000000, 1dp), 线宽取主题已有细描边档; 只在该方向仍有内容时画一条, 不遮字。
 */
private val ScrollEdgeIndicatorThickness = DesignTokens.strokeThin
private const val ScrollEdgeIndicatorAlpha = 0x1F / 255f

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
 * 等 alpha 回到 1 才突然出现。定位由 [textToolbarFrameOffset] 扣掉这圈留白, 视觉位置不变。
 */
private val ToolbarShadowPadding = ToolbarElevation

/**
 * 菜单可用区 (**窗口坐标**)：本窗口内容区与安全区取交集，边距只算一次。
 *
 * 契约:
 * - [contentArea] 必须已由调用方折到窗口坐标 (根坐标 + 实测根窗口偏移, 见
 *   [textMenuRectInWindow]); null = 不限制内容区, 直接用窗口安全区。
 * - [safeInsets] 四边按窗口原点计量 (Android 取本窗口 insets; 桌面为 0 加上控制条上沿),
 *   与窗口坐标同原点。
 * - [windowSize] 给出窗口安全区的右/下边界; 内容区比窗口小时交集由内容区决定。
 *
 * 交集为空时调用方不摆放卡片。
 */
internal fun textMenuAvailRect(
    contentArea: IntRect?,
    windowSize: IntSize,
    safeInsets: IntRect,
    overlayTopPx: Int,
    overlayGapPx: Int,
): IntRect {
    val top = max(safeInsets.top, if (overlayTopPx > 0) overlayTopPx + overlayGapPx else 0)
    val safe = IntRect(
        left = safeInsets.left,
        top = top,
        right = windowSize.width - safeInsets.right,
        bottom = windowSize.height - safeInsets.bottom,
    )
    return contentArea?.intersect(safe) ?: safe
}

/**
 * 本窗口的菜单可用区 (窗口坐标): [area] 为调用方已折到窗口坐标的内容区,
 * null = 整窗可用区 (主窗口路由与阅读页)。
 */
@Composable
private fun rememberTextMenuAvailRect(area: IntRect?): IntRect {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val overlayTopPx = with(density) { LocalOverlayTopInset.current.roundToPx() }
    val overlayGapPx = with(density) { OverlayInsetGap.roundToPx() }
    return textMenuAvailRect(
        contentArea = area,
        windowSize = LocalWindowInfo.current.containerSize,
        safeInsets = IntRect(
            left = safe.getLeft(density, layoutDirection),
            top = safe.getTop(density),
            right = safe.getRight(density, layoutDirection),
            bottom = safe.getBottom(density),
        ),
        overlayTopPx = overlayTopPx,
        overlayGapPx = overlayGapPx,
    )
}

/** 矩形按节点偏移平移（锚与保护矩形同一小范围纯转换，组合期/布局期共用） */
private fun Rect.translatedBy(origin: IntOffset): Rect =
    Rect(left + origin.x, top + origin.y, right + origin.x, bottom + origin.y)

/** 区域按节点偏移平移（内容区/可用区同一小范围纯转换）。 */
private fun IntRect.translatedBy(origin: IntOffset): IntRect =
    IntRect(left + origin.x, top + origin.y, right + origin.x, bottom + origin.y)

/** 窗口坐标 → 可用区局部坐标 (原点 = 可用区左上)。 */
private fun Rect.inAvail(availRect: IntRect): Rect = Rect(
    left - availRect.left,
    top - availRect.top,
    right - availRect.left,
    bottom - availRect.top,
)

/**
 * 锚/保护矩形 → **窗口坐标** (纯转换, 测试直接驱动)。
 *
 * 两个声明空间各自折一次: [AppTextMenuAnchorSpace.Root] 的矩形相对本窗口的 Compose 根,
 * 加上根在窗口中的偏移 ([rootOriginInWindow], 由同一节点的 `positionInWindow -
 * positionInRoot` 实测, 不假定默认值);
 * [AppTextMenuAnchorSpace.Container] 的矩形相对阅读页正文容器, 直接加宿主的窗口位置
 * ([hostOriginInWindow])。安全区/控制条禁区都在窗口坐标里, 转换后与可用区同一空间。
 */
internal fun textMenuRectInWindow(
    rect: Rect,
    space: AppTextMenuAnchorSpace,
    hostOriginInWindow: IntOffset,
    rootOriginInWindow: IntOffset,
): Rect = if (space == AppTextMenuAnchorSpace.Container) {
    rect.translatedBy(hostOriginInWindow)
} else {
    rect.translatedBy(rootOriginInWindow)
}

/**
 * 菜单项文字样式基线: 与环境 `LocalTextStyle` 合并一次后同时用于测宽与实际绘制,
 * 排布尺寸与画出来的文字出自同一份解析结果。
 */
private val ToolbarLabelStyle = TextStyle(fontSize = ToolbarTextSize, fontWeight = FontWeight.Medium)

/** 「查找替换」项标签 (对照原版 CodeView 的 ActionMode 菜单项)。 */
internal const val FIND_REPLACE_LABEL = "查找替换"

/** 一个菜单项 (标签 + 动作), 溢出折叠按这个列表切分。 */
internal class AppTextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * 菜单锚 (及保护矩形) 的坐标空间。
 *
 * 平台文本工具条与框架上下文菜单给出的选区矩形是根坐标 (Compose `positionInRoot`，
 * 即窗口内容坐标，对话框里是它自己的窗口)；阅读页正文是自绘 Canvas，选区几何与菜单宿主
 * 同处一个页面容器，是容器局部坐标。两条来源各自声明，宿主只做一次平移。
 */
internal enum class AppTextMenuAnchorSpace {
    /** 根坐标：主窗口内容区 / 对话框窗口内容区 */
    Root,

    /** 页面容器局部坐标 (阅读页正文容器) */
    Container,
}

/**
 * 一次菜单请求的内容快照。
 *
 * @param anchor 锚矩形 (含两游标的菜单锚，或图片长按 40px 方区), 坐标空间见 [anchorSpace]
 * @param session 本次显示会话的身份 (与 [anchor]/标签无关的稳定标识, 可以是对象也可以是
 *   旧通道那样的递增会话号): 同一会话内选区/几何/文案更新必须沿用同一身份, hide 之后的新
 *   请求必须换成新身份。菜单的交互状态 (是否展开溢出面板、面板滚动位置) 归属这个身份,
 *   新会话从收起态开始。
 * @param protectedRects 二维避让矩形 (与 anchor 同坐标系)：定位流程检查当前可见卡片
 *   与它们的实际相交，能放下时不遮任一 (选区场景传两个真实手柄；无保护需求的调用方不传，
 *   通用机制不绑定具体调用方)
 */
internal class AppTextMenuContent(
    val anchor: Rect,
    val entries: List<AppTextMenuEntry>,
    val session: Any,
    val protectedRects: List<Rect> = emptyList(),
    val anchorSpace: AppTextMenuAnchorSpace = AppTextMenuAnchorSpace.Root,
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
    /** 本窗口可摆放区域 (根坐标); null = 整窗可用区 */
    area: IntRect?,
    content: @Composable () -> Unit,
)

/**
 * 注入两条文本菜单通道并挂上本窗口的菜单宿主。
 *
 * 通道属于窗口而不属于主题: 同一窗口已有通道时 (对话框根由 [WithWindowTextMenu] 自装,
 * 主题在弹窗正文里再包一层) 只透传内容 —— 否则内容会落到更小的子区域上作定位边界。
 *
 * 两条通道同时装但互斥: 同一平台上 `isNewContextMenuEnabled` 只有一个取值, 框架只会走其中
 * 一条。这样即使该 flag 的默认值随版本翻转, 菜单外观也不变。
 */
@Composable
fun ProvideAppTextToolbar(content: @Composable () -> Unit) {
    if (LocalAppTextMenuState.current != null) {
        content()
        return
    }
    WithTextMenuWindowArea { area ->
        ProvideWindowTextMenu(area, content)
    }
}

/**
 * 量出本节点在根坐标中的区域 (即本窗口可绘制、可命中的内容区), 交给 [content] 作菜单边界。
 *
 * 读数只写状态, 不改变布局: Box 尺寸仍由内容决定, 菜单宿主自己零尺寸。
 */
@Composable
internal fun WithTextMenuWindowArea(content: @Composable (IntRect?) -> Unit) {
    var area by remember { mutableStateOf<IntRect?>(null) }
    Box(
        Modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            val rect = IntRect(
                pos.x.roundToInt(),
                pos.y.roundToInt(),
                pos.x.roundToInt() + coords.size.width,
                pos.y.roundToInt() + coords.size.height,
            )
            if (rect != area) area = rect
        },
    ) {
        content(area)
    }
}

/**
 * 本窗口的两条文本菜单通道 + 菜单宿主。
 *
 * 对话框窗口只绘制、只命中自己的内容区, 故由调用方量出本窗口内容区传入; 主窗口路由不传
 * (null = 整窗可用区)。
 *
 * @param area 本窗口可摆放区域 (根坐标)
 */
@Composable
internal fun ProvideWindowTextMenu(
    area: IntRect?,
    content: @Composable () -> Unit,
) {
    val state = remember { AppTextMenuState() }
    val legacyToolbar = remember { LegacyAppTextToolbar() }
    CompositionLocalProvider(
        LocalAppTextMenuState provides state,
        LocalTextToolbar provides legacyToolbar,
    ) {
        ProvidePlatformTextMenu(state, area) { content() }
        legacyToolbar.Host(state, area)
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

    /**
     * 显示会话身份: hide() 之后再次 showMenu() 才是新会话; 同一会话内的选区/几何更新
     * (params 非空时的重复 showMenu) 沿用同一身份, 菜单交互状态不会因此重置。
     */
    private var session by mutableIntStateOf(0)

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
        if (params == null) session++
        params = Params(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        if (params == null) session++
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
    fun Host(state: AppTextMenuState, area: IntRect?) {
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
            entries.takeIf { it.isNotEmpty() }?.let { AppTextMenuContent(p.rect, it, session) }
        }
        AppTextMenuHost(content, area)
    }
}

/**
 * 自绘文本选择菜单宿主, 四端共用, 视觉参考澎湃 OS 的浮动文本菜单 (见顶部规格常量)。
 * 一行放不下时尾部收成 ⋮, 点开变形为竖排溢出面板, ← 收回横排 (落在 ⋮ 原位:
 * 向上展开时在面板底部, 向下展开时在面板顶部)。
 *
 * 菜单画在发起它的窗口内: 占位节点零尺寸, 菜单层不参与正文/对话框内容尺寸计算, 卡片按
 * 根坐标摆放。横排与溢出面板共用一个最大外框 ([MenuMetrics.frameSize]), 锚定边 (LTR 右缘 /
 * RTL 左缘) 与展开侧边缘由这一组尺寸一次算定, 开合变形期间整个卡片都在框内生长,
 * 不再逐帧重算落点。
 *
 * @param content null = 隐藏。退场动画期间原请求已失效 (新通道的 dataProvider 已解绑),
 *   故用最后一帧内容把卡片留住, 动画播完再卸载。这期间卡片只留绘制、不再接收输入
 *   (同 AOSP 在 dismiss 时清空窗口可触域, 旧闭包不会在淡出途中被再次执行)。
 * @param area 本窗口可摆放区域 (根坐标); null = 整窗可用区 (主窗口路由与阅读页)
 */
@Composable
internal fun AppTextMenuHost(
    content: AppTextMenuContent?,
    area: IntRect? = null,
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
    AppTextMenuOverlay(
        data = data,
        area = area,
        visibleState = visibleState,
        interactive = content != null,
    )
}

/**
 * 本窗口的菜单占位与卡片: 宿主只在这里组合, 隐藏后随组合组一起消失, 每次重新显示都重新
 * 测量占位节点位置 (窗口位置变化不会被上一轮的旧坐标沿用)。
 */
@Composable
private fun AppTextMenuOverlay(
    data: AppTextMenuContent,
    area: IntRect?,
    visibleState: MutableTransitionState<Boolean>,
    interactive: Boolean,
) {
    // 占位节点的真实布局坐标: null = 尚未取得真值, 此时不组合卡片, 不按伪原点产生可见输出
    var space by remember { mutableStateOf<MenuHostSpace?>(null) }
    Layout(
        content = {
            val hostSpace = space
            if (hostSpace != null) {
                // 交互状态 (溢出面板展开/面板滚动) 归本次显示会话: 淡出未结束时到达的新请求
                // 用新的会话身份重建卡片, 从收起态开始, 不继承旧请求的展开态
                key(data.session) {
                    AppTextMenuCard(
                        data = data,
                        area = area,
                        space = hostSpace,
                        visibleState = visibleState,
                        interactive = interactive,
                    )
                }
            }
        },
        modifier = Modifier.onGloballyPositioned { coords ->
            // 每轴 roundToInt 折 IntOffset (同 CodeTextField 的字段窗口位置写法)。
            // 根在窗口中的偏移取同一节点两套实测坐标之差 (positionInWindow - positionInRoot),
            // 不按窗口类型假定是否为 0
            val inRoot = coords.positionInRoot()
            val inWindow = coords.positionInWindow()
            val rootOffset = IntOffset(
                (inWindow.x - inRoot.x).roundToInt(),
                (inWindow.y - inRoot.y).roundToInt(),
            )
            val hostInWindow = IntOffset(inWindow.x.roundToInt(), inWindow.y.roundToInt())
            val measured = MenuHostSpace(rootOffset, hostInWindow)
            if (measured != space) space = measured
        },
    ) { measurables, _ ->
        // 零尺寸占位: 菜单层不参与任何父布局测量; 卡片在占位节点之外绘制与命中,
        // 占位节点自身不挂 alpha/clip/off-screen 图层 (否则会把卡片裁成 0 或吞掉输入)
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(0, 0) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * 一个窗口内的坐标真值 (全部由同一节点的真实布局坐标换算, 不假定默认值):
 * [rootOriginInWindow] = `positionInWindow - positionInRoot`, 即本窗口 Compose 根在窗口中的
 * 偏移; [hostOriginInWindow] = 占位宿主在窗口中的位置。
 * 锚/保护矩形、内容区、insets 与落点都先归一到窗口坐标再互相运算。
 */
private data class MenuHostSpace(
    val rootOriginInWindow: IntOffset,
    val hostOriginInWindow: IntOffset,
)

/**
 * 菜单卡片: 固定最大外框 + 框内按展开方向贴边。
 *
 * 外框尺寸 = 横排/面板视觉尺寸的逐轴最大值 (+ 两圈阴影留白), 位置只由 [TextToolbarPlacement]
 * 与这组尺寸算出, 变形动画只改框内卡片尺寸; 框本身透明且不挂输入, 命中判定自然落到下层
 * 正文/选区手柄 (Compose 对无输入、无裁切的父节点只查孩子, 孩子没命中就继续查后面的
 * sibling)。
 */
@Composable
private fun AppTextMenuCard(
    data: AppTextMenuContent,
    area: IntRect?,
    space: MenuHostSpace,
    visibleState: MutableTransitionState<Boolean>,
    interactive: Boolean,
) {
    val eInk = LocalEInk.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 与环境文字样式合并一次: 测宽与实际绘制共用同一份解析结果
    val labelStyle = LocalTextStyle.current.merge(ToolbarLabelStyle)
    val gapPx = with(density) { ToolbarSelectionGap.roundToPx() }
    val marginPx = with(density) { ToolbarScreenMargin.roundToPx() }
    val shadowPx = with(density) { ToolbarShadowPadding.roundToPx() }
    // 内容区按同一实测偏移折到窗口坐标, 再与窗口安全区取交集
    val contentAreaInWindow = remember(area, space.rootOriginInWindow) {
        area?.translatedBy(space.rootOriginInWindow)
    }
    val availRect = rememberTextMenuAvailRect(contentAreaInWindow)
    if (availRect.width <= 0 || availRect.height <= 0) return
    // 菜单项每帧重建 (标签来自快照感知的 data()), 用标签串当稳定 key, 避免重组重置折叠态
    val labelsKey = data.entries.joinToString("\u0000") { it.label }
    // 折叠位置与横排/面板/各项尺寸出自同一遍度量 (见 rememberMenuMetrics); 绘制按它定宽
    // 可用区放不下"仅 ⋮"的最小行时没有可用布局: 不摆放卡片
    val metrics = rememberMenuMetrics(
        entries = data.entries,
        labelsKey = labelsKey,
        marginPx = marginPx,
        availWidthPx = availRect.width,
        labelStyle = labelStyle,
    ) ?: return
    val visibleCount = metrics.visibleCount
    // 卡片按 data.session 分组 (见 AppTextMenuOverlay): 这里的展开/滚动状态天然属于本次显示
    // 会话, 淡出未结束时到达的新请求从收起态开始
    var overflowOpen by remember { mutableStateOf(false) }
    val overflowTransition = updateTransition(overflowOpen, label = "overflow")
    val hasOverflow = visibleCount < data.entries.size
    // 锚与保护矩形折到窗口坐标 (同一实测偏移), 再归一化到可用区
    val anchorInAvail = remember(data.anchor, data.anchorSpace, space, availRect) {
        textMenuRectInWindow(
            data.anchor,
            data.anchorSpace,
            space.hostOriginInWindow,
            space.rootOriginInWindow,
        ).inAvail(availRect)
    }
    val protectedInAvail = remember(data.protectedRects, data.anchorSpace, space, availRect) {
        data.protectedRects.map {
            textMenuRectInWindow(
                it,
                data.anchorSpace,
                space.hostOriginInWindow,
                space.rootOriginInWindow,
            ).inAvail(availRect)
        }
    }
    val availSize = IntSize(availRect.width, availRect.height)
    // 同一组尺寸同时约束绘制、共同锚边、屏边钳位与保护避让; 变形动画不重算这份决策
    val placement = remember(
        anchorInAvail, protectedInAvail, availSize, layoutDirection, gapPx, marginPx,
        metrics.rowSize, metrics.panelSize, hasOverflow,
    ) {
        TextToolbarPlacement.decide(
            anchorOnWindow = anchorInAvail,
            windowSize = availSize,
            layoutDirection = layoutDirection,
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
    }
    val frameSize = metrics.frameSize
    // 溢出面板可滚区上限: 展开位置上的可用空间 (含保护截断/空隙选择) 扣掉返回行
    // (AOSP updateOverflowHeight 按可用收高), 再受规格上限 192dp 约束。
    // 可用区容纳返回行时渲染高 ≤ 该可用空间; 可用区不足返回行高时面板最小可见高
    // 仍为返回行, 会超出该可用空间 (如实)
    val panelListMaxHeightPx = min(
        with(density) { OverflowPanelMaxHeight.roundToPx() },
        (placement.panelAvailHeightPx - metrics.rowSize.height).coerceAtLeast(0),
    )
    // 本帧卡片的实际渲染高 (返回行恒在, 可滚区按可用空间收): 钳位只认它, 不认透明外框高
    val cardHeightPx = min(
        metrics.panelSize.height,
        placement.panelAvailHeightPx.coerceAtLeast(metrics.rowSize.height),
    )
    // 外框左上角 (可用区局部坐标 → 宿主局部坐标): 卡片锚定边由 placement 给定, 两圈阴影留白
    // 向外扩; RTL 时物理边由同一布局方向镜像得到
    val frameOffset = remember(
        anchorInAvail, availSize, placement, layoutDirection, frameSize, cardHeightPx,
        availRect, space, gapPx, marginPx, shadowPx, metrics.rowSize.height,
    ) {
        val frameInAvail = textToolbarFrameOffset(
            anchorInAvail = anchorInAvail,
            availSize = availSize,
            placement = placement,
            layoutDirection = layoutDirection,
            frameWidthPx = frameSize.width,
            frameHeightPx = frameSize.height,
            cardHeightPx = cardHeightPx,
            rowHeightPx = metrics.rowSize.height,
            gapPx = gapPx,
            marginPx = marginPx,
            shadowPx = shadowPx,
        )
        // 可用区局部 → 窗口 → 宿主局部坐标 (占位宿主的窗口位置是同一实测值)
        IntOffset(
            frameInAvail.x + availRect.left - space.hostOriginInWindow.x,
            frameInAvail.y + availRect.top - space.hostOriginInWindow.y,
        )
    }
    // 展开方向决定贴哪条边: 向上展开贴 End 下角, 向下展开贴 End 上角 (End 随布局方向:
    // LTR 为物理右侧, RTL 为物理左侧, 与锚定边的镜像一致)
    val frameAlignment = if (placement.expandsUpwards) Alignment.BottomEnd else Alignment.TopEnd
    // AnimatedContent 内部按 Ltr 解析 contentAlignment (1.11.1/1.11.2 实现), 与外面的
    // 方向感知 End 不同源: 这里显式给物理对齐, 保证 RTL 下内容仍贴物理左边
    val contentAlignment = when {
        layoutDirection != LayoutDirection.Rtl -> frameAlignment
        placement.expandsUpwards -> Alignment.BottomStart
        else -> Alignment.TopStart
    }
    Box(
        Modifier
            // 外框位置是已折算好的物理坐标: 不能用会按 RTL 镜像 x 的 offset
            .absoluteOffset { frameOffset }
            .size(
                with(density) { (frameSize.width + shadowPx * 2).toDp() },
                with(density) { (frameSize.height + shadowPx * 2).toDp() },
            )
            // 外框尺寸固定, 卡片按展开方向贴在外框的锚定边上 (测量仍受外框约束:
            // 绘制尺寸来自同一份度量, 不需要放宽测量)
            .wrapContentSize(frameAlignment),
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
            // 阴影留白圈在淡入淡出的离屏缓冲之内, 卡片阴影不随 alpha 被切掉
            Box(Modifier.padding(ToolbarShadowPadding)) {
                ToolbarSurface(eInk) {
                    Box {
                        // 卡片背景的命中遮罩: 透明外框不挂它, 活动卡片内部空白不落到正文
                        if (interactive) MenuInputShield()
                        overflowTransition.AnimatedContent(
                            // 开合变形: 容器尺寸 300ms 四次缓入缓出, 旧内容 100ms 淡出、新内容
                            // 300ms 淡入 (miuix openOverflow/closeOverflow 同参); E-Ink 直接切换。
                            // 按面板展开方向贴住容器 End 侧, 变形期间锚边保持静止
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
                            contentAlignment = contentAlignment,
                        ) { open ->
                            if (open) {
                                OverflowPanel(
                                    entries = data.entries.drop(visibleCount),
                                    collapseAtTop = !placement.expandsUpwards,
                                    maxListHeight = with(density) { panelListMaxHeightPx.toDp() },
                                    panelWidth = with(density) { metrics.panelSize.width.toDp() },
                                    labelStyle = labelStyle,
                                    onCollapse = { overflowOpen = false },
                                    interactive = interactive,
                                )
                            } else {
                                Row(
                                    Modifier
                                        .width(with(density) { metrics.rowSize.width.toDp() })
                                        .padding(horizontal = ToolbarContentPadding),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    data.entries.take(visibleCount).forEachIndexed { index, entry ->
                                        ToolbarItem(
                                            text = entry.label,
                                            widthPx = metrics.itemWidths[index],
                                            labelStyle = labelStyle,
                                            interactive = interactive,
                                            onClick = entry.onClick,
                                        )
                                    }
                                    if (hasOverflow) {
                                        OverflowButton(metrics.overflowWidthPx, interactive) {
                                            overflowOpen = true
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
 * 活动卡片背景的输入遮罩: 卡片内部空白/内边距的触摸停在本窗口的菜单上, 不落到正文;
 * 透明外框不挂它。只占用命中路径, 不产生任何动作; 退场时整块不组合 (卡片只留绘制)。
 */
@Composable
private fun BoxScope.MenuInputShield() {
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/**
 * 菜单排布度量: 标签宽度只量一遍 (与绘制共用同一份解析后的文字样式), 折叠位置与横排/面板/
 * 单项的最终尺寸都从这一遍推出; 实际渲染按这里给的尺寸定宽, 展示与落位不再各算一套。
 *
 * 宽度上限 = 可用区宽扣掉两侧屏边距。一级项只在**整项 (含内边距) 放得下**时保留, 放不下的
 * 一律收进溢出面板 (因此窄到连一个一级项都放不下时可以只有 ⋮); 面板宽同样受该上限约束,
 * 超长标签由单行省略 ([TextOverflow.Ellipsis]) 显示。可用区连"仅 ⋮"的最小行都放不下时
 * 返回 null —— 没有可用布局, 不摆放卡片。
 */
@Composable
private fun rememberMenuMetrics(
    entries: List<AppTextMenuEntry>,
    labelsKey: String,
    marginPx: Int,
    availWidthPx: Int,
    labelStyle: TextStyle,
): MenuMetrics? {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labelsKey, availWidthPx, marginPx, density, labelStyle) {
        with(density) {
            // 内边距按实际 Modifier.padding 的逐边取整口径: horizontal 两侧各 roundToPx 一次
            // (foundation-layout 1.11.1 Padding.kt: start.roundToPx() + end.roundToPx())，
            // 不能把两侧合并成一个 Dp 再取整 (2.75 等密度下会少 1px)
            val contentPaddingPx = ToolbarContentPadding.roundToPx() * 2
            val itemSidePaddingPx = ToolbarItemSidePadding.roundToPx() * 2
            val itemMinWidthPx = ToolbarItemMinWidth.roundToPx()
            val overflowWidthPx =
                (OverflowIconSize + OverflowButtonSidePadding * 2).roundToPx()
            val toolbarHeightPx = ToolbarHeight.roundToPx()
            // 面板项左右内边距: start 与 end 各按渲染时真正传入的 Dp 表达式取整
            val panelRowPaddingPx = OverflowPanelItemSidePadding.roundToPx() +
                (OverflowPanelItemSidePadding + fastScrollPlatform.touchWidth).roundToPx()
            val panelMinWidthPx = OverflowPanelMinWidth.roundToPx()
            val panelMaxHeightPx = OverflowPanelMaxHeight.roundToPx()

            // 卡片最大宽 = 可用区宽减两侧屏边距 (可为 0/负: 此时下方判定为无可用布局)
            val maxCardWidthPx = availWidthPx - marginPx * 2
            // 横排内容预算 = 卡片最大宽 - 容器左右内边距 (同一份边距只扣一次, 下游不再夹边)
            val rowContentBudgetPx = maxCardWidthPx - contentPaddingPx
            // 连"仅 ⋮"的最小行都放不下: 没有可用布局, 调用方不摆放卡片 (不假造 1px 上限)
            if (rowContentBudgetPx < overflowWidthPx) {
                return@with null
            }
            val textWidths = entries.map {
                measurer.measure(
                    text = AnnotatedString(it.label),
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }
            // 一级项宽 = 文字宽 + 两侧内边距 (最小 50dp); 一级项只按整项放得下才保留
            val itemWidths = textWidths.map {
                max(it + itemSidePaddingPx, itemMinWidthPx)
            }
            val maxPrimary = if (entries.size <= 5) entries.size else ToolbarMaxPrimaryItems
            val visibleCount =
                if (entries.size <= 5 && itemWidths.sum() <= rowContentBudgetPx) {
                    entries.size
                } else {
                    // 放不下或超过规格时给 ⋮ 留位, 其余项收进二级面板; 一项都放不下时
                    // 一级项为 0 (只留 ⋮), 动作全部保留在面板里。预算里已扣掉容器内边距,
                    // 因此这里放得下的项数与最终行宽一致
                    var used = overflowWidthPx
                    var n = 0
                    while (n < itemWidths.size && n < maxPrimary && used + itemWidths[n] <= rowContentBudgetPx) {
                        used += itemWidths[n]
                        n++
                    }
                    n
                }
            val hasOverflow = visibleCount < entries.size
            val rowSize = IntSize(
                contentPaddingPx + itemWidths.take(visibleCount).sum() +
                    if (hasOverflow) overflowWidthPx else 0,
                toolbarHeightPx,
            )
            if (!hasOverflow) {
                return@with MenuMetrics(
                    visibleCount = visibleCount,
                    itemWidths = itemWidths,
                    overflowWidthPx = overflowWidthPx,
                    rowSize = rowSize,
                    panelSize = rowSize,
                )
            }
            val panelWidth = menuPanelWidthPx(
                maxLabelWidthPx = textWidths.drop(visibleCount).maxOrNull() ?: 0,
                rowPaddingPx = panelRowPaddingPx,
                minWidthPx = panelMinWidthPx,
                maxWidthPx = maxCardWidthPx,
            )
            // 面板 = 可滚项区 (受最高档约束) + 固定的返回行
            val panelHeight = min(
                (entries.size - visibleCount) * toolbarHeightPx,
                panelMaxHeightPx,
            ) + toolbarHeightPx
            MenuMetrics(
                visibleCount = visibleCount,
                itemWidths = itemWidths,
                overflowWidthPx = overflowWidthPx,
                rowSize = rowSize,
                panelSize = IntSize(panelWidth, panelHeight),
            )
        }
    }
}

/**
 * 溢出面板内容宽: 最宽项 + 面板项左右内边距 (含滚动条可触宽度的逐边像素值), 受最小宽与
 * 可用上限双重约束 (上限小于最小宽时按上限, 由单行省略收窄文字)。
 *
 * [rowPaddingPx] 必须是渲染时 `Modifier.padding(start=…, end=…)` 两侧各自 roundToPx 之和,
 * 面板宽与项内边距出自同一份像素口径, 滑块才不会压住标签、文字也不会被无故省略。
 */
internal fun menuPanelWidthPx(
    maxLabelWidthPx: Int,
    rowPaddingPx: Int,
    minWidthPx: Int,
    maxWidthPx: Int,
): Int = max(minWidthPx, maxLabelWidthPx + rowPaddingPx).coerceAtMost(maxWidthPx)

private data class MenuMetrics(
    val visibleCount: Int,
    /** 每个菜单项的最终宽 (含左右内边距), 与 entries 同序 */
    val itemWidths: List<Int>,
    val overflowWidthPx: Int,
    val rowSize: IntSize,
    val panelSize: IntSize,
) {
    /**
     * 横排与溢出面板共用的最大外框 (视觉尺寸, 不含阴影留白): 绘制、共同锚定边、
     * 屏边钳位与保护避让都用这一组尺寸, 开合变形只在框内生长。
     */
    val frameSize: IntSize
        get() = IntSize(
            width = max(rowSize.width, panelSize.width),
            height = max(rowSize.height, panelSize.height),
        )
}

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

/**
 * 菜单项: 高 50dp / 宽由排布度量给定 (含左右 10dp 内边距) / 16sp medium。
 * 文字与测宽同一样式, 单行省略; 不抢焦点 ([interactive] false = 退场, 只留绘制且不挂输入)。
 */
@Composable
private fun ToolbarItem(
    text: String,
    widthPx: Int,
    labelStyle: TextStyle,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .height(ToolbarHeight)
            .then(if (interactive) Modifier.menuClickable(onClick) else Modifier)
            .padding(horizontal = ToolbarItemSidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = labelStyle,
            color = AppTheme.colors.primaryText,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** ⋮ 溢出按钮: 宽由排布度量给定 (22dp 图标 + 左右 10.9dp), 对齐 miuix overflow 档。 */
@Composable
private fun OverflowButton(widthPx: Int, interactive: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .height(ToolbarHeight)
            .then(if (interactive) Modifier.menuClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_more_vert),
            // 与 AOSP 展开按钮一致: 该按钮要有可读名称 (聚焦/读屏/语义定位都用它)
            contentDescription = stringResource(Res.string.more),
            tint = AppTheme.colors.primaryText,
            modifier = Modifier.size(OverflowIconSize),
        )
    }
}

/**
 * 菜单控件的点击入口: 不参与焦点 (菜单是本窗口内的浮层, 不能把焦点从文本字段/阅读页
 * 抢走; 原 Popup 本身就是不抢焦点的窗口)。
 */
private fun Modifier.menuClickable(onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }.clickable(onClick = onClick)

/**
 * 溢出面板: 竖排剩余项, 宽度由排布度量给定 (含滚动条可触宽度) / 项左右 16dp / 可滚区受
 * 规格上限与展开方向可用空间 (取小, 见调用处) 双重约束; 一端固定返回按钮, 不参与滚动。
 *
 * 滚动提示复用 [ScrollStateFastScrollbar] (FastScroll 的绘制/显隐/拖动实现) 与上下边缘
 * 细指示, 都由真实 [androidx.compose.foundation.ScrollState] 驱动。
 *
 * @param collapseAtTop 面板向下展开时返回按钮放列表上方, 向上展开时放下方。两种摆法都让
 *   ← 落在展开前 ⋮ 的位置上, 变形期间该行不动 (同 AOSP LocalFloatingToolbarPopup
 *   按 mOpenOverflowUpwards 给溢出按钮定的顶/底锚)。
 * @param maxListHeight 可滚项区高度上限 (Dp): 展开方向可用空间扣掉返回行后再受 192dp
 *   规格约束 (AOSP updateOverflowHeight 按可用空间收溢出项数的同语义)
 * @param interactive false = 退场动画期间: 列表只按当前滚动位置绘制, 不挂滚动/点击/拖动输入
 */
@Composable
private fun OverflowPanel(
    entries: List<AppTextMenuEntry>,
    collapseAtTop: Boolean,
    maxListHeight: Dp,
    panelWidth: Dp,
    labelStyle: TextStyle,
    onCollapse: () -> Unit,
    interactive: Boolean,
) {
    val scrollState = rememberScrollState()
    // 滚动条可触宽度占的右侧内边距: 标签不落到滑块下面 (与测宽同一常量)
    val scrollbarInset = fastScrollPlatform.touchWidth
    Column(Modifier.width(panelWidth)) {
        if (collapseAtTop) CollapseButton(onCollapse, interactive)
        // 列表视口: 高度由内容决定 (滚动提示层用 matchParentSize, 不反过来把视口撑高)
        Box(
            Modifier
                .heightIn(max = maxListHeight)
                .clipToBounds(),
        ) {
            if (interactive) {
                Column(Modifier.verticalScroll(scrollState)) {
                    PanelEntries(entries, labelStyle, scrollbarInset, interactive = true)
                }
            } else {
                // 退场: 同一滚动位置的纯绘制版本, 不挂滚动/拖动节点。内容按无界高测量
                // (视口外的行不被压扁), 顶部对齐同 verticalScroll 的放置, 再上移当前偏移
                Column(
                    Modifier
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .absoluteOffset { IntOffset(0, -scrollState.value) },
                ) {
                    PanelEntries(entries, labelStyle, scrollbarInset, interactive = false)
                }
            }
            Box(Modifier.matchParentSize()) {
                // 该方向还有内容时画一条默认细指示 (canScrollBackward/Forward 是真实滚动判定)
                ScrollEdgeIndicator(scrollState, top = true)
                ScrollEdgeIndicator(scrollState, top = false)
                ScrollStateFastScrollbar(scrollState, interactive = interactive)
            }
        }
        if (!collapseAtTop) CollapseButton(onCollapse, interactive)
    }
}

/** 面板项: 通栏行 (高亮充满整行宽), 文字与测宽同一样式, 单行省略。 */
@Composable
private fun PanelEntries(
    entries: List<AppTextMenuEntry>,
    labelStyle: TextStyle,
    scrollbarInset: Dp,
    interactive: Boolean,
) {
    entries.forEach { entry ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(ToolbarHeight)
                .then(if (interactive) Modifier.menuClickable(entry.onClick) else Modifier)
                .padding(
                    start = OverflowPanelItemSidePadding,
                    end = OverflowPanelItemSidePadding + scrollbarInset,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = entry.label,
                style = labelStyle,
                color = AppTheme.colors.primaryText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 列表上下边缘的默认细指示: 该方向还能继续滚时贴边画一条 (AOSP
 * scroll_indicator_material: 1dp 高、12% 前景色), 不遮字也不做过渡。
 */
@Composable
private fun BoxScope.ScrollEdgeIndicator(state: ScrollState, top: Boolean) {
    val visible = if (top) state.canScrollBackward else state.canScrollForward
    if (!visible) return
    Box(
        Modifier
            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(ScrollEdgeIndicatorThickness)
            .background(AppTheme.colors.primaryText.copy(alpha = ScrollEdgeIndicatorAlpha)),
    )
}

/** 面板返回按钮: ← 箭头 (项目资源 ic_arrow_back, 同实机), 行高同菜单项, 点击收回横排。 */
@Composable
private fun CollapseButton(onClick: () -> Unit, interactive: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .then(if (interactive) Modifier.menuClickable(onClick) else Modifier)
            .padding(horizontal = OverflowPanelItemSidePadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_arrow_back),
            // 与 AOSP 收回按钮一致: 该按钮要有可读名称
            contentDescription = stringResource(Res.string.back),
            tint = AppTheme.colors.primaryText,
            modifier = Modifier.size(OverflowIconSize),
        )
    }
}

/**
 * 菜单落位决策 (纯几何, 测试直接驱动)。
 *
 * 横向在"布局方向起点"一侧算: LTR 的锚定边是物理右缘, RTL 是距物理左缘的距离 —— 锚与
 * 保护矩形先按同一布局方向镜像, 决策、屏边钳位与保护检测全部在这一个空间里完成
 * (AOSP openOverflow/closeOverflow 的 isInRTLMode 分支同语义: LTR 锁右、RTL 锁左)。
 *
 * [decide] 其余部分: 收起态横排按横排实际行高定上下方向；溢出面板按 AOSP
 * FloatingToolbar.refreshCoordinatesAndOverflowDirection 同序四分支安排展开方向；横排与
 * 溢出面板共用一条锚定边 ([frameEdgePx], 按 [MenuMetrics.frameSize] 一次钳位), 绘制与
 * 保护检测用同一横向基准, 变形期间该边不动；与保护矩形二维相交时把面板生长截断到保护边
 * 之外，截断后放不下最小面板 (返回行+2项) 的分支跳过；四个锚定分支都放不下时在可用区内找
 * 保护矩形 (含 gap 缓冲) 之间的空隙，取最高可容面板的间隔放面板；所有实际空隙都不足以容纳
 * 面板最小可见高度时面板按剩余空间收高仍可能与保护矩形重叠 —— 那是物理放不下的唯一情形，
 * 如实重叠不宣称避让成功。
 *
 * @param opensUpwards 收起态横排落位方向: 锚顶上方放得下横排 (含 gap) 为 true
 * @param expandsUpwards 溢出面板展开方向 (四分支, 无溢出时与 [opensUpwards] 同)
 * @param panelAvailHeightPx 面板展开位置上的可用高度 (含保护截断/空隙选择, 渲染层按它收面板)
 * @param frameEdgePx 横排与面板共用的锚定边, 距布局方向起点 (LTR = 物理右缘,
 *   RTL = 距物理左缘), 落点函数与保护检测共用
 * @param panelTopOverridePx 非空 = 四个锚定分支都放不下，面板顶直接放该局部坐标
 *   (从保护空隙中选出)；空隙不足时面板仍放该处并可能与保护重叠
 */
internal class TextToolbarPlacement private constructor(
    val opensUpwards: Boolean,
    val expandsUpwards: Boolean,
    val panelAvailHeightPx: Int,
    val frameEdgePx: Int,
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
            layoutDirection: LayoutDirection,
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
            val mirrored = layoutDirection == LayoutDirection.Rtl
            fun Rect.mirrored(): Rect = if (mirrored) {
                Rect(windowSize.width - right, top, windowSize.width - left, bottom)
            } else {
                this
            }
            val anchor = anchorOnWindow.mirrored()
            val anchorTop = floor(anchor.top).toInt()
            val anchorBottom = ceil(anchor.bottom).toInt()
            val centerX = ((anchor.left + anchor.right) / 2f).roundToInt()
            // 共同锚定边: 横排按锚中心定初始位置, 再按共同外框宽度做一次屏边钳位。
            // 横排与面板都贴这条边 —— 面板更宽时向反方向生长、横排随之移动, 变形期间该边静止
            val frameWidthPx = if (hasOverflow) maxOf(rowWidthPx, panelWidthPx) else rowWidthPx
            val rowEdge = (centerX - rowWidthPx / 2)
                .coerceIn(
                    marginPx,
                    (windowSize.width - rowWidthPx - marginPx).coerceAtLeast(marginPx),
                ) + rowWidthPx
            val frameEdge = rowEdge.coerceIn(
                marginPx + frameWidthPx,
                (windowSize.width - frameWidthPx - marginPx).coerceAtLeast(marginPx) + frameWidthPx,
            )
            val opensUpwards = anchorTop - rowHeightPx - gapPx >= topBoundPx
            if (!hasOverflow) {
                return TextToolbarPlacement(
                    opensUpwards = opensUpwards, expandsUpwards = opensUpwards,
                    panelAvailHeightPx = 0, frameEdgePx = frameEdge, panelTopOverridePx = null,
                )
            }
            // 保护检测用共同外框的 x 范围作包络 —— 开合变形期间的中间宽度矩形恒落在
            // 横排与面板两矩形之间，被该包络覆盖，不因单帧宽度变化漏判左右保护
            val panelRight = frameEdge
            val envelopeStart = frameEdge - frameWidthPx
            // 保护矩形折整并四边外扩（floor 左上、ceil 右下）, 与锚同在布局方向空间
            val guards = protectedRects.map {
                val g = it.mirrored()
                IntRect(
                    floor(g.left).toInt(),
                    floor(g.top).toInt(),
                    ceil(g.right).toInt(),
                    ceil(g.bottom).toInt(),
                )
            }
            // 面板区间 [lo, hi] 沿生长方向与保护矩形 x 重叠时截断到保护边之外;
            // 纵向不相交的保护不约束
            fun availClamped(lo: Int, hi: Int, growsUp: Boolean): Int {
                var avail = hi - lo
                for (g in guards) {
                    if (envelopeStart >= g.right || panelRight <= g.left) continue
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
                    panelAvailHeightPx = avail1, frameEdgePx = frameEdge, panelTopOverridePx = null,
                )
            }
            // 分支 2: 面板顶 = 横排顶 = 锚顶 - rowH - gap, 向下长
            val panelTop2 = anchorTop - rowHeightPx - gapPx
            val avail2 = availClamped(panelTop2, windowSize.height - marginPx, growsUp = false)
            if (opensUpwards && avail2 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = true, expandsUpwards = false,
                    panelAvailHeightPx = avail2, frameEdgePx = frameEdge, panelTopOverridePx = null,
                )
            }
            // 分支 3: 面板顶 = 锚底 + gap, 向下长
            val avail3 = availClamped(anchorBottom + gapPx, windowSize.height - marginPx, growsUp = false)
            if (avail3 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = false, expandsUpwards = false,
                    panelAvailHeightPx = avail3, frameEdgePx = frameEdge, panelTopOverridePx = null,
                )
            }
            // 分支 4: 面板底 = 横排底 = 锚底 + gap + rowH, 向上长
            val panelBottom4 = anchorBottom + gapPx + rowHeightPx
            val rowFitsBelow = panelBottom4 <= windowSize.height - marginPx
            val avail4 = availClamped(topBoundPx, panelBottom4, growsUp = true)
            if (rowFitsBelow && avail4 >= minPanel) {
                return TextToolbarPlacement(
                    opensUpwards = false, expandsUpwards = true,
                    panelAvailHeightPx = avail4, frameEdgePx = frameEdge, panelTopOverridePx = null,
                )
            }
            // 四个锚定分支都放不下: 在可用区内按同一实际面板 x 收集与保护矩形 (含 gap
            // 缓冲) 相交的区段, 求剩余空隙, 取最高可容面板的间隔放面板; 所有空隙都不足
            // 面板最小可见高度 (返回行) 时仍放最大空隙处, 面板按剩余空间收高并可能与
            // 保护矩形重叠 —— 那是物理放不下的唯一路径, 如实重叠不宣称避让成功
            val bars = guards.mapNotNull { g ->
                if (envelopeStart >= g.right || panelRight <= g.left) return@mapNotNull null
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
                frameEdgePx = frameEdge,
                panelTopOverridePx = best?.first ?: topBoundPx,
            )
        }
    }
}

/**
 * 固定最大外框的落点 (纯几何, 测试直接驱动)。
 *
 * 外框宽高与 [TextToolbarPlacement] 出自同一组 [MenuMetrics.frameSize]: 横排与面板共用的
 * 锚定边、屏边距钳位、保护避让都由它一次算定, 变形动画只改框内卡片尺寸。外框按展开方向
 * 贴住选区侧或横排原位的边缘: 向上展开固定 End 下角, 向下展开固定 End 上角
 * (End 随布局方向: LTR 右、RTL 左)。
 *
 * 返回值是**含阴影留白的外框左上角** (可用区局部坐标, 已按 [layoutDirection] 折回物理
 * 左右)。视觉卡片在框内贴 End 对齐, 故卡片的锚定边 = [TextToolbarPlacement.frameEdgePx],
 * 与阴影大小无关。
 *
 * 钳位按**实际渲染的卡片高** ([cardHeightPx], 同出于 placement + 度量) 而不是外框高:
 * 外框透明且不接收输入, 越过可用区不产生可见内容, 卡住它的高度只会把卡片无端推离锚边。
 * 卡片本身超过可用区时边界不成立 (物理限制, 如实越界)。
 */
internal fun textToolbarFrameOffset(
    anchorInAvail: Rect,
    availSize: IntSize,
    placement: TextToolbarPlacement,
    layoutDirection: LayoutDirection,
    frameWidthPx: Int,
    frameHeightPx: Int,
    cardHeightPx: Int,
    rowHeightPx: Int,
    gapPx: Int,
    marginPx: Int,
    shadowPx: Int,
): IntOffset {
    val mirrored = layoutDirection == LayoutDirection.Rtl
    val anchor = if (mirrored) {
        Rect(
            availSize.width - anchorInAvail.right,
            anchorInAvail.top,
            availSize.width - anchorInAvail.left,
            anchorInAvail.bottom,
        )
    } else {
        anchorInAvail
    }
    val anchorTop = floor(anchor.top).toInt()
    val anchorBottom = ceil(anchor.bottom).toInt()
    val sameSide = placement.opensUpwards == placement.expandsUpwards
    val top = placement.panelTopOverridePx ?: when {
        sameSide && placement.opensUpwards -> anchorTop - cardHeightPx - gapPx
        sameSide -> anchorBottom + gapPx
        placement.opensUpwards -> anchorTop - rowHeightPx - gapPx
        else -> anchorBottom + gapPx + rowHeightPx - cardHeightPx
    }
    val maxTop = (availSize.height - cardHeightPx - marginPx).coerceAtLeast(marginPx)
    val boundedTop = top.coerceIn(marginPx, maxTop)
    // 向上展开: 卡片底缘 = 锚定边缘 (框底), 框顶向反方向长; 向下展开: 卡片顶缘 = 框顶
    val frameTop = if (placement.expandsUpwards) {
        boundedTop + cardHeightPx - frameHeightPx
    } else {
        boundedTop
    }
    // 布局方向空间里的外框起点 (距起点边), 再折回物理左上角
    val frameStart = placement.frameEdgePx - frameWidthPx - shadowPx
    val x = if (mirrored) {
        availSize.width - (frameStart + frameWidthPx + shadowPx * 2)
    } else {
        frameStart
    }
    return IntOffset(x = x, y = frameTop - shadowPx)
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
