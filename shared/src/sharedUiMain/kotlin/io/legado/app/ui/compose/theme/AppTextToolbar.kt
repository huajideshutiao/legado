package io.legado.app.ui.compose.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.roundToInt
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.cut
import legado.shared.generated.resources.paste
import legado.shared.generated.resources.select_all
import org.jetbrains.compose.resources.stringResource

// ===== 澎湃 OS 浮动文本菜单规格 =====
// 取值来源: /product/app/MiuixEditor/MiuixEditor.apk 资源表 —— 澎湃这套菜单的本体
// (miui-framework 的 DecorViewStubImpl 反射 com.miuix.editor 的 miuix.toolbar.FloatingActionMode)。
// 两处按用户指定偏离 miuix: 背景走动态主题底栏色 (miuix 是固定 白/#2C2C2E),
// 阴影 8dp (miuix card_elevation 是 9dp)。
private val ToolbarShape = RoundedCornerShape(13.dp) // dialog_corner_radius 13.09dp
private val ToolbarHeight = 50.dp // floating_toolbar_height
private val ToolbarTextSize = 16.sp // floating_toolbar_text_size
private val ToolbarElevation = 8.dp // 用户指定 (miuix card_elevation 9dp)
private val ToolbarSelectionGap = 18.dp // floating_toolbar_vertical_margin
private val ToolbarScreenMargin = 12.dp // floating_toolbar_min_horizontal_margin
private val OverflowIconSize = 22.dp // floating_toolbar_overflow_icon_width/height
private val OverflowButtonSidePadding = 11.dp // overflow_button_side_padding 10.9dp
private val OverflowPanelMinWidth = 100.dp // floating_toolbar_overflow_panel_min_width
private val OverflowPanelMaxHeight = 192.dp // 对齐 AOSP floating_toolbar_maximum_overflow_height

// 菜单项左右内边距: miuix 那份 dump 里没有 floating_toolbar 前缀的对应档 (只有通用的
// menu_button_side_padding 8dp, 8dp 实测挤), AOSP 同组件是 11dp。取 14dp 是按 miuix 这套
// 更大的尺寸族 (50dp 高 / 16sp / 13dp 圆角) 放大后的目视值, 不是 dump 出来的。
private val ToolbarItemSidePadding = 14.dp
private val ToolbarItemMinWidth = 48.dp // AOSP floating_toolbar_menu_button_minimum_width

/**
 * 弹层四周留白, 供 [ToolbarElevation] 的阴影落在**节点内部**。
 * 淡入淡出时 alpha<1 会把绘制提升到离屏缓冲并裁到节点 bounds, 画到界外的阴影会被切掉、
 * 等 alpha 回到 1 才突然出现; 留白后阴影始终在界内。定位由
 * [TextToolbarPositionProvider] 扣掉这圈留白, 视觉位置不变。
 */
private val ToolbarShadowPadding = ToolbarElevation

/** 菜单项文本样式, 兼作溢出折叠的测宽依据 (与 [ToolbarItem] 实际渲染一致)。 */
private val ToolbarTextStyle = TextStyle(fontSize = ToolbarTextSize, fontWeight = FontWeight.Medium)

/** 「查找替换」项标签 (对照原版 CodeView 的 ActionMode 菜单项)。 */
internal const val FIND_REPLACE_LABEL = "查找替换"

/** 一个菜单项 (标签 + 动作), 溢出折叠按这个列表切分。 */
internal class AppTextMenuEntry(val label: String, val onClick: () -> Unit)

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
        val p = params ?: return
        // 顺序对齐 framework Editor: 剪切/复制/粘贴/全选 → 查找替换
        val cutText = stringResource(Res.string.cut)
        val copyText = stringResource(Res.string.copy)
        val pasteText = stringResource(Res.string.paste)
        val selectAllText = stringResource(Res.string.select_all)
        val findReplace = state.findReplaceAction
        val entries = remember(p, findReplace, cutText, copyText, pasteText, selectAllText) {
            buildList {
                p.onCut?.let { add(AppTextMenuEntry(cutText, it)) }
                p.onCopy?.let { add(AppTextMenuEntry(copyText, it)) }
                p.onPaste?.let { add(AppTextMenuEntry(pasteText, it)) }
                p.onSelectAll?.let { add(AppTextMenuEntry(selectAllText, it)) }
                findReplace?.let { action ->
                    add(AppTextMenuEntry(FIND_REPLACE_LABEL) { hide(); action() })
                }
            }
        }
        if (entries.isEmpty()) return
        AppTextMenuPopup(anchor = p.rect, entries = entries)
    }
}

/**
 * 自绘文本选择菜单弹层, 四端共用, 视觉参考澎湃 OS 的浮动文本菜单 (见顶部规格常量)。
 * 支持溢出折叠: 一行放不下时尾部收成 ⋮, 点开换成竖排面板。
 *
 * @param anchor 选区矩形, 坐标空间须与本弹层父节点一致 (旧通道给的是内容树根坐标,
 *   新通道给的是宿主 Box 局部坐标, 两者的父节点分别就是那两个节点)
 */
@Composable
internal fun AppTextMenuPopup(anchor: Rect, entries: List<AppTextMenuEntry>) {
    val eInk = LocalEInk.current
    val density = LocalDensity.current
    val gapPx = with(density) { ToolbarSelectionGap.roundToPx() }
    val marginPx = with(density) { ToolbarScreenMargin.roundToPx() }
    val positionProvider = remember(anchor, gapPx, marginPx) {
        TextToolbarPositionProvider(anchor, gapPx, marginPx)
    }
    // 菜单项每帧重建 (标签来自快照感知的 data()), 用标签串当稳定 key, 避免重组重置折叠态
    val labelsKey = entries.joinToString("\u0000") { it.label }
    val visibleCount = rememberVisibleCount(entries, labelsKey, marginPx)
    var overflowOpen by remember(labelsKey) { mutableStateOf(false) }

    // focusable=false: 不抢文本框焦点/选区, 显隐交由框架回调驱动
    // onDismissRequest 空实现: 鼠标微动会触发系统 dismiss 回调清空选区菜单,
    // 改由框架 (旧通道 hide() / 新通道取消 show 协程) 在选区真正清除时统一隐藏
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = {},
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        ToolbarSurface(eInk) {
            if (overflowOpen) {
                OverflowPanel(entries.drop(visibleCount))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entries.take(visibleCount).forEach { ToolbarItem(it.label, it.onClick) }
                    if (visibleCount < entries.size) {
                        OverflowButton { overflowOpen = true }
                    }
                }
            }
        }
    }
}

/**
 * 一行能放下几项: 组合期用 [rememberTextMeasurer] 量文本宽, 与窗口可用宽比较。
 * 放不下时给 ⋮ 留位。窗口尺寸未知 (首帧 containerSize 为 0) 时不折叠。
 */
@Composable
private fun rememberVisibleCount(
    entries: List<AppTextMenuEntry>,
    labelsKey: String,
    marginPx: Int,
): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    return remember(labelsKey, windowWidth, marginPx, density) {
        if (windowWidth <= 0) return@remember entries.size
        val sidePaddingPx = with(density) { (ToolbarItemSidePadding * 2).roundToPx() }
        val overflowPx = with(density) { (OverflowIconSize + OverflowButtonSidePadding * 2).roundToPx() }
        val widths = entries.map {
            measurer.measure(AnnotatedString(it.label), ToolbarTextStyle).size.width + sidePaddingPx
        }
        val available = windowWidth - marginPx * 2
        if (widths.sum() <= available) return@remember entries.size
        var used = overflowPx
        var n = 0
        while (n < widths.size && used + widths[n] <= available) {
            used += widths[n]
            n++
        }
        n.coerceAtLeast(1)
    }
}

/** 卡片外壳: 动态底栏色 + 13dp 圆角 + 8dp 阴影; E-Ink 去阴影改描边。 */
@Composable
private fun ToolbarSurface(eInk: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
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

/** 菜单项: 50dp 高 / 左右 8dp / 16sp medium (对齐 miuix 的 sans-serif-medium), 无最小宽。 */
@Composable
private fun ToolbarItem(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(ToolbarHeight)
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

/** ⋮ 溢出按钮: 22dp 图标区自绘三点, 左右 11dp (对齐 miuix overflow 档)。 */
@Composable
private fun OverflowButton(onClick: () -> Unit) {
    val color = AppTheme.colors.primaryText
    Box(
        Modifier
            .height(ToolbarHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = OverflowButtonSidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(OverflowIconSize)) {
            val r = size.minDimension / 14f
            val cx = size.width / 2f
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawCircle(color = color, radius = r, center = Offset(cx, size.height * f))
            }
        }
    }
}

/** 溢出面板: 竖排剩余项, 最小宽 100dp / 最高 192dp 可滚 (对齐 miuix + AOSP 档)。 */
@Composable
private fun OverflowPanel(entries: List<AppTextMenuEntry>) {
    Column(
        Modifier
            .widthIn(min = OverflowPanelMinWidth)
            .heightIn(max = OverflowPanelMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        entries.forEach { entry ->
            Box(
                Modifier
                    .height(ToolbarHeight)
                    .clickable(onClick = entry.onClick)
                    .padding(horizontal = ToolbarItemSidePadding * 2),
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
}

/**
 * 锚定: 选区 rect 上方居中优先, 放不下翻下方, 左右按屏边距 clamp。
 * anchorBounds.topLeft 为弹层父节点在窗口中的偏移, 与 rect 坐标原点一致, 相加得窗口坐标。
 */
private class TextToolbarPositionProvider(
    private val rect: Rect,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val originX = anchorBounds.left
        val originY = anchorBounds.top
        val centerX = originX + ((rect.left + rect.right) / 2f).roundToInt()
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        var y = originY + rect.top.roundToInt() - popupContentSize.height - gapPx
        if (y < marginPx) {
            y = originY + rect.bottom.roundToInt() + gapPx
        }
        y = y.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
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
