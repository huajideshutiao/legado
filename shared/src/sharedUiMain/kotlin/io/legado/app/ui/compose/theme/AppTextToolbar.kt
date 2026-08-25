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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.cut
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.paste
import legado.shared.generated.resources.select_all
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===== 澎湃 OS 浮动文本菜单规格 =====
// 取值来源: /product/app/MiuixEditor/MiuixEditor.apk 的资源表与 res/layout/floating_popup_*.xml
// —— 澎湃这套菜单的本体 (miui-framework 的 DecorViewStubImpl 反射 com.miuix.editor 的
// miuix.toolbar.FloatingActionMode)。Compose 起 ActionMode 时 originatingView 是
// AndroidComposeView 而非 TextView, 拿不到那套, 故自绘对齐其规格。
// 唯一按用户指定偏离 miuix: 阴影 8dp (miuix card_elevation 9dp), 背景走动态主题底栏色。
private val ToolbarShape = RoundedCornerShape(13.09.dp) // dialog_corner_radius 13.089996dp
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
// 见 TextToolbarPositionProvider 的右缘锚定与 AnimatedContent 的 BottomEnd 对齐。
private const val OverflowMorphMillis = 300
private const val OverflowFadeOutMillis = 100
private val QuarticEaseInOut = Easing { t ->
    if (t < 0.5f) 8f * t * t * t * t else 1f - 8f * (1f - t) * (1f - t) * (1f - t) * (1f - t)
}

/**
 * 弹层四周留白, 让 [ToolbarElevation] 的阴影落在**动画节点内部**。
 * 淡入淡出时 alpha<1 会把绘制提升到离屏缓冲并裁到节点 bounds, 画到界外的阴影会被切掉、
 * 等 alpha 回到 1 才突然出现。定位由 [TextToolbarPositionProvider] 扣掉这圈留白, 视觉位置不变。
 */
private val ToolbarShadowPadding = ToolbarElevation

/** 菜单项文本样式, 兼作溢出折叠的测宽依据 (与 [ToolbarItem] 实际渲染一致)。 */
private val ToolbarTextStyle = TextStyle(fontSize = ToolbarTextSize, fontWeight = FontWeight.Medium)

/** 「查找替换」项标签 (对照原版 CodeView 的 ActionMode 菜单项)。 */
internal const val FIND_REPLACE_LABEL = "查找替换"

/** 一个菜单项 (标签 + 动作), 溢出折叠按这个列表切分。 */
internal class AppTextMenuEntry(val label: String, val onClick: () -> Unit)

/**
 * 一次菜单请求的内容快照。
 *
 * @param anchor 选区矩形, 坐标空间须与弹层父节点一致 (旧通道给内容树根坐标, 新通道给宿主 Box 局部坐标)
 */
internal class AppTextMenuContent(val anchor: Rect, val entries: List<AppTextMenuEntry>)

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
    // 菜单项每帧重建 (标签来自快照感知的 data()), 用标签串当稳定 key, 避免重组重置折叠态
    val labelsKey = data.entries.joinToString("\u0000") { it.label }
    // 折叠位置与横排/面板尺寸出自同一遍度量 (见 rememberMenuMetrics)
    val metrics = rememberMenuMetrics(data.entries, labelsKey, marginPx)
    val visibleCount = metrics.visibleCount
    var overflowOpen by remember(labelsKey) { mutableStateOf(false) }
    val overflowTransition = updateTransition(overflowOpen, label = "overflow")
    // 横排与面板的最终尺寸在开合前就已知：弹层全程按两者的最大包围盒定尺, 开合只改内部
    // 卡片, 动画开始/逐帧/结束都不触发 WindowManager 重排 (与系统浮动菜单同策略,
    // 见 AOSP LocalFloatingToolbarPopup.updatePopupSize)。窗口比卡片大的那圈透明区由
    // 下方的空白捕获层兜住, 点空白即关菜单, 不碰任何窗口级可触区 API。
    val contentSize = metrics.maxSize
    val windowHeight = LocalWindowInfo.current.containerSize.height
    // 弹出方向按最大包围盒判定, 开合过程中不翻转
    val opensUpwards =
        remember(data.anchor, metrics.maxSize.height, windowHeight, marginPx, gapPx) {
            windowHeight <= 0 || data.anchor.top - metrics.maxSize.height - gapPx >= marginPx
    }
    val positionProvider = remember(
        data.anchor,
        gapPx,
        marginPx,
        shadowPx,
        contentSize,
        metrics.rowSize.width,
        opensUpwards,
    ) {
        TextToolbarPositionProvider(
            rect = data.anchor,
            gapPx = gapPx,
            marginPx = marginPx,
            shadowPx = shadowPx,
            rowWidthPx = metrics.rowSize.width,
            contentWidthPx = contentSize.width,
            contentHeightPx = contentSize.height,
            opensUpwards = opensUpwards,
        )
    }

    // focusable=false: 不抢文本框焦点/选区, 显隐交由框架回调驱动
    // onDismissRequest 空实现: 鼠标微动会触发系统 dismiss 回调清空选区菜单,
    // 改由框架 (旧通道 hide() / 新通道取消 show 协程) 在选区真正清除时统一隐藏
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = {},
        properties = PopupProperties(focusable = false, clippingEnabled = false),
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
            // 空白捕获层挂最外层 (含阴影留白圈): Android 的 Popup 是独立窗口, 落在卡片外
            // 透明区的点击既出不去、下层也收不到, 与其白吞不如直接关菜单。手势检测都在主传递
            // (叶→根) 等按下, 卡片上的点击先被子项消费, 本层拿到的是已消费事件不会误触发。
            Box(
                Modifier
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .padding(ToolbarShadowPadding),
            ) {
                // 卡片贴稳定包围盒的右侧；垂直方向按 Popup 的固定定位方向贴顶部或底部。
                Box(
                    modifier = Modifier.requiredSize(
                        with(density) { contentSize.width.toDp() },
                        with(density) { contentSize.height.toDp() },
                    ),
                    contentAlignment = if (opensUpwards) Alignment.BottomEnd else Alignment.TopEnd,
                ) {
                    ToolbarSurface(eInk) {
                        overflowTransition.AnimatedContent(
                            // 开合变形: 容器尺寸 300ms 四次缓入缓出, 旧内容 100ms 淡出、新内容 300ms
                            // 淡入 (miuix openOverflow/closeOverflow 同参); E-Ink 直接切换。
                            // 按弹出方向贴住容器右上/右下角，变形期间锚边保持静止
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
                            contentAlignment = if (opensUpwards) Alignment.BottomEnd else Alignment.TopEnd,
                        ) { open ->
                            if (open) {
                                OverflowPanel(
                                    data.entries.drop(visibleCount),
                                    collapseAtTop = !opensUpwards,
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

/**
 * 菜单排布度量: 标签宽度只量一遍, 折叠位置 (一行放不下时尾部收 ⋮) 与横排/溢出面板的
 * 最终尺寸都从这一遍推出 —— 两者用同一批 padding/最小宽常量, 分开算过就会漂移。
 * 窗口尺寸未知 (首帧 containerSize 为 0) 时不折叠。
 */
@Composable
private fun rememberMenuMetrics(
    entries: List<AppTextMenuEntry>,
    labelsKey: String,
    marginPx: Int,
): MenuMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    return remember(labelsKey, windowWidth, marginPx, density) {
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
            val itemWidths = textWidths.map { max(it + itemSidePaddingPx, itemMinWidthPx) }
            val available = windowWidth - marginPx * 2 - contentPaddingPx
            val visibleCount = if (windowWidth <= 0 || itemWidths.sum() <= available) {
                entries.size
            } else {
                // 放不下时给 ⋮ 留位, 至少留一项
                var used = overflowButtonWidthPx
                var n = 0
                while (n < itemWidths.size && used + itemWidths[n] <= available) {
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
                textWidths.drop(visibleCount).max() + panelSidePaddingPx,
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
) {
    /** 横排与面板的稳定包围盒: Popup 全程按它定尺, 开合只在内部变形。 */
    val maxSize = IntSize(
        width = max(rowSize.width, panelSize.width),
        height = max(rowSize.height, panelSize.height),
    )
}

/** 卡片外壳: 动态底栏色 + 13.09dp 圆角 + 8dp 阴影; E-Ink 去阴影改描边。 */
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
 * 溢出面板: 竖排剩余项, 最小宽 100dp / 项左右 16dp / 最高 192dp 可滚; 一端固定返回按钮。
 *
 * @param collapseAtTop 面板向下展开时返回按钮放列表上方, 向上展开时放下方。两种摆法都让
 *   ← 落在展开前 ⋮ 的位置上, 变形期间该行不动 (同 AOSP LocalFloatingToolbarPopup
 *   按 mOpenOverflowUpwards 给溢出按钮定的顶/底锚)。
 */
@Composable
private fun OverflowPanel(
    entries: List<AppTextMenuEntry>,
    collapseAtTop: Boolean,
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
                .heightIn(max = OverflowPanelMaxHeight)
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
 * 锚定: 选区 rect 上方居中优先, 放不下翻下方, 左右按屏边距 clamp。
 * anchorBounds.topLeft 为弹层父节点在窗口中的偏移, 与 rect 坐标原点一致, 相加得窗口坐标。
 *
 * [shadowPx] 是弹层内容四周为阴影留的白 (见 ToolbarShadowPadding): popupContentSize 含这圈留白,
 * 故按视觉尺寸算完位置再整体回退 shadowPx。
 */
private class TextToolbarPositionProvider(
    private val rect: Rect,
    private val gapPx: Int,
    private val marginPx: Int,
    private val shadowPx: Int,
    private val rowWidthPx: Int,
    private val contentWidthPx: Int,
    private val contentHeightPx: Int,
    private val opensUpwards: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = contentWidthPx
        val height = contentHeightPx
        val originX = anchorBounds.left
        val originY = anchorBounds.top
        val centerX = originX + ((rect.left + rect.right) / 2f).roundToInt()
        // 横排与展开面板共用同一右缘，开合动画只在稳定包围盒内部变形。
        val rowX = (centerX - rowWidthPx / 2)
            .coerceIn(marginPx, (windowSize.width - rowWidthPx - marginPx).coerceAtLeast(marginPx))
        val x = (rowX + rowWidthPx - width)
            .coerceIn(marginPx, (windowSize.width - width - marginPx).coerceAtLeast(marginPx))
        val y = if (opensUpwards) {
            originY + rect.top.roundToInt() - height - gapPx
        } else {
            originY + rect.bottom.roundToInt() + gapPx
        }
        val boundedY = y.coerceIn(
            marginPx,
            (windowSize.height - height - marginPx).coerceAtLeast(marginPx),
        )
        return IntOffset(x - shadowPx, boundedY - shadowPx)
    }
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
    DisposableEffect(state, action) {
        state?.findReplaceAction = action
        onDispose {
            if (state?.findReplaceAction === action) {
                state.findReplaceAction = null
            }
        }
    }
}
