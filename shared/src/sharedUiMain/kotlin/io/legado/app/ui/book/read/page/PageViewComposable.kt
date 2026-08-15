package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.platform.navigationBarFixedPadding
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.statusBarFixedPadding
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.flow.combine
import legado.shared.generated.resources.Res
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * KMP 版阅读页面视图：用 Compose 替代 app 端 `PageView` (FrameLayout + ViewBookPageBinding)。
 *
 * 供横向翻页模式（覆盖/滑动/仿真/无动画）使用；滚动翻页模式由 [ScrollPageView]
 * 单画布三页连排承担（2026-08 重构）。
 *
 * 布局结构与 app 端 view_book_page.xml 对齐（按原版布局约束机制重构 2026-08）：
 * ```
 * Box(背景) {
 *   Column(内容层, 系统栏避让) {
 *     HeaderTip      // 页眉: wrap 高度布局子节点 (对照原版 llHeader)
 *     Box(weight 1f) // 正文区: 剩余空间 (对照原版 contentTextView 被约束在分割线之间)
 *     FooterTip      // 页脚: wrap 高度布局子节点 (对照原版 llFooter)
 *   }
 * }
 * ```
 * 页眉/页脚是**布局占位子节点**而非叠加层：高度由布局系统测量（wrap_content，含各自
 * 分割线），正文区 = 容器高 − 页眉实测 − 页脚实测，与排版视口同一布局系统同帧适配
 * （对照原版 llHeader/llFooter wrap_content + ConstraintLayout 约束，正文与页脚天然一致）。
 * 页眉/页脚实际测量高度经 [onHeaderMeasured]/[onFooterMeasured] 上报（onSizeChanged）：
 * 排版层（ReaderRoute.buildLayoutConfig）以此实测值为单一来源更新正文视口高度并重排——
 * 首帧未测量时保持当前排版，测量回调后重排收敛（布局依赖顺序，非降级兜底）。
 * 显隐（headerMode/footerMode/headerTipVisible）只控制子节点是否组合，隐藏时高度自然为 0，
 * 正文区随之扩展（对照原版 isGone 后 contentTextView 自动扩展）。
 *
 * 与 app 端差异：状态栏/导航栏避让在本 Composable 内部完成（内容层
 * [platformStatusBarPadding] + 导航栏 bottom inset，等价 app 端 vwStatusBar/vwNavigationBar
 * 占位 View），背景层仍铺满全窗（含系统栏区域，显示阅读背景色）；
 * 桌面端无系统栏 inset，避让恒为 no-op。
 *
 * @param textPage 当前页内容，null 时显示加载占位
 * @param batteryLevel 电池电量 0-100，传 -1 表示不显示
 * @param clockText 当前系统时间 HH:mm，随 timeChanged 刷新
 * @param drawTick 页内容原地变更版本号（朗读高亮等），透传给 [PageContentCanvas] 强制重绘
 * @param selection 页内文字选择状态, 透传给 [PageContentCanvas] (绘制块内订阅 tick 重绘)
 * @param onHeaderMeasured 页眉实际测量高度回调 (px, 布局占位子节点 onSizeChanged)
 * @param onFooterMeasured 页脚实际测量高度回调 (px), 同上
 * @param onTextAreaMeasured 正文区实测尺寸回调 (px, 布局占位子节点 onSizeChanged):
 *   排版视口单一来源——正文区已被系统栏 inset + 页眉/页脚约束, 尺寸即原版
 *   contentTextView.onSizeChanged 的实测值
 */
@Composable
fun PageViewComposable(
    textPage: TextPage?,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    clockText: String = formatTimeOfDay(systemCurrentTimeMillis()),
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
    drawTick: Int = 0,
    selection: PageSelectionState? = null,
    onHeaderMeasured: ((Int) -> Unit)? = null,
    onFooterMeasured: ((Int) -> Unit)? = null,
    onTextAreaMeasured: ((IntSize) -> Unit)? = null,
) {
    val providers = LocalReadConfigProviders.current
    val readTipConfig = providers.readTipConfig
    val readBookConfig = providers.readBookConfig
    // 颜色/字号/字体统一走 ReaderDrawStyle（内部订阅 ReadBookEvents.configChange 重建）
    val style = rememberReaderDrawStyle()
    // tip 层刷新版本号：STYLE（tip 颜色/显隐）、UP_CONTENT（tip 槽位内容）、SYSTEM_UI
    // （headerMode=0 跟随状态栏显隐）事件到达时自增。ReadTipConfig/ReadBookConfig 是
    // prefs 直读、无 Compose 快照，版本号变化强制本层重组，重组时重新读取即取到最新值
    // （对照原版 STYLE → readView.upStyle() 直接重设 tip 视图，无需重新分页）；
    // 版本号同时传给 HeaderTip/FooterTip 作 remember 键，tip 内容只在事件到达时重读 prefs。
    var tipRefreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange.collect { changes ->
            if (changes.any { it in tipRefreshChanges }) tipRefreshTick++
        }
    }
    // 页眉/页脚显隐判定（对照原版 PageView.upTipStyle 的 isGone）：显隐只决定布局
    // 子节点是否组合——隐藏时不组合、占位高度自然为 0，正文区随之扩展，无任何独立
    // 预留公式（对照原版 isGone 后 contentTextView 自动扩展；headerMode=0 跟随
    // hideStatusBar 沉浸联动，无系统栏平台（桌面）恒显）。
    // tipRefreshTick 作为重组驱动：tip 配置事件（STYLE/UP_CONTENT/SYSTEM_UI）到达时
    // 本层顶层重组并重新读 prefs（无 Compose 快照，靠版本号强制重读），显隐/边距变化
    // 后布局系统重新测量子节点，实测高度经 onSizeChanged 上报反哺排版。
    val headerVisible = headerTipVisible(readTipConfig.headerMode, readBookConfig.hideStatusBar)
    val footerVisible = readTipConfig.footerMode != 1
    // 分割线颜色：对照 app 端 PageView.upStyle 的 tipDividerColor 解析
    // (-1 主题 divider 色 / 0 正文色 / 其他 自定义色)
    val tipDividerColor = when (readTipConfig.tipDividerColor) {
        -1 -> rememberColor("divider")
        0 -> style.textColor
        else -> Color(readTipConfig.tipDividerColor)
    }

    Box(
        modifier = modifier
            // commonMain 无 Brush.solidColor，Modifier.background 有 Color 重载，直接传纯色。
            // PageView 的底色必须保持不透明：旧 Android PageView 用不透明的 bgMeanColor
            // 作为底层，再叠加带 bgAlpha 的背景 Drawable；若直接把带 alpha 的颜色作为
            // Compose 背景，向后翻页时上一页会透出当前页/窗口背景。
            .background(style.bgColor.copy(alpha = 1f))
            .fillMaxSize()
    ) {
        ReaderBackgroundImage(
            source = style.backgroundImageSource,
            alpha = style.backgroundImageAlpha,
            modifier = Modifier.fillMaxSize(),
        )

        // 状态栏/导航栏避让层（对照原版 PageView 的 vwStatusBar/vwNavigationBar 占位 View：
        // 背景由外层 Box 铺满（含系统栏区域，显示阅读背景色），本层正文/页眉/页脚整体
        // 避让系统栏；系统栏隐藏时 inset=0，内容自然顶到窗口边，等价原版 hideStatusBar/
        // hideNavigationBar 配置下占位 View isGone。桌面端无系统栏 inset，恒为 no-op。
        // 内部为纵向布局占位结构（对照原版 view_book_page.xml 的 llHeader →
        // contentTextView → llFooter）：页眉/页脚 wrap 高度、正文占剩余空间，
        // 分割线随页眉/页脚行（子节点内），正文区与页脚顶边的间隔 = 正文 paddingBottom 配置。
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 事件化系统栏 padding (对齐原版 vwStatusBar/vwNavigationBar 配置驱动占位:
                // 菜单显隐动画期间恒定, 仅显隐翻转时重排一次, 不逐帧跟随)
                .statusBarFixedPadding()
                .navigationBarFixedPadding()
        ) {
            // 页眉：wrap 高度布局子节点（对照原版 llHeader，含页眉分割线）
            if (headerVisible) {
                HeaderTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    startPaddingDp = readBookConfig.headerPaddingLeft,
                    endPaddingDp = readBookConfig.headerPaddingRight,
                    topPaddingDp = readBookConfig.headerPaddingTop,
                    bottomPaddingDp = readBookConfig.headerPaddingBottom,
                    showDivider = readBookConfig.showHeaderLine,
                    dividerColor = tipDividerColor,
                    refreshTick = tipRefreshTick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onHeaderMeasured?.invoke(it.height) },
                )
            }

            // 正文区：剩余空间（weight 1f）。页眉/页脚是布局占位子节点，正文实际高度
            // 由布局系统测量（= 容器高 − 页眉实测 − 页脚实测）。排版视口单一来源：
            // 本 Box 实测尺寸经 onTextAreaMeasured 上报 ReaderRoute，直接作为
            // buildLayoutConfig 的 viewWidth/viewHeight（对照原版
            // ContentTextView.onSizeChanged → ChapterProvider.upViewSize）——同一布局系统
            // 同帧适配，正文末行与页脚顶边间隔恒为 paddingBottom 配置，永不重叠。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { onTextAreaMeasured?.invoke(it) }
            ) {
                // 正文直接绘制。正文区即布局占位子节点之间的剩余空间, 排版
                // 视口与其同帧同源 (页眉/页脚实测扣除), 正文天然不会画进页脚区, 渲染侧
                // 无需裁剪 (对照原版 contentTextView 与 llFooter 的 View 层级隔离)。
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    textPage?.let {
                        PageContentCanvas(
                            textPage = it,
                            modifier = Modifier.fillMaxSize(),
                            style = style,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            drawTick = drawTick,
                            selection = selection,
                        )
                    }
                }
            }

            // 页脚：wrap 高度布局子节点（对照原版 llFooter，含页脚分割线）
            if (footerVisible) {
                FooterTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    startPaddingDp = readBookConfig.footerPaddingLeft,
                    endPaddingDp = readBookConfig.footerPaddingRight,
                    topPaddingDp = readBookConfig.footerPaddingTop,
                    bottomPaddingDp = readBookConfig.footerPaddingBottom,
                    showDivider = readBookConfig.showFooterLine,
                    dividerColor = tipDividerColor,
                    refreshTick = tipRefreshTick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onFooterMeasured?.invoke(it.height) },
                )
            }
        }
    }
}

/**
 * tip 层刷新事件集合：事件到达时 [PageViewComposable] 自增 tipRefreshTick 强制重组，
 * 重新读取 tip 槽位/颜色/显隐（对照原版 STYLE → readView.upStyle() 直接重设 tip 视图，
 * 不需要重新分页排版）。SYSTEM_UI 覆盖 headerMode=0 随隐藏状态栏联动的显隐切换。
 */
private val tipRefreshChanges = setOf(
    ReadConfigChange.STYLE,
    ReadConfigChange.UP_CONTENT,
    ReadConfigChange.SYSTEM_UI,
)

/**
 * 绘制实际背景图。
 *
 * 先由父 Box 绘制不透明 [ReaderDrawStyle.bgColor]，再把图片按中心裁剪叠加；这样
 * 图片未加载、加载失败或 alpha 小于 100% 时，页面仍有完整的阅读底色。该 Composable
 * 被每一个参与翻页的 PageView 使用，故上一页、当前页和翻起页录制内容的背景一致。
 */
@Composable
private fun ReaderBackgroundImage(
    source: String?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    if (source.isNullOrBlank()) return
    LaunchedEffect(source) {
        ReaderBackgroundImageCache.requestAsync(source)
    }
    Canvas(modifier = modifier) {
        // 只读 version 建立快照订阅；异步加载完成后重新执行 draw lambda。
        if (ReaderBackgroundImageCache.version < 0) return@Canvas
        val bitmap = ReaderBackgroundImageCache.peek(source) ?: return@Canvas
        drawReaderBackgroundBitmap(bitmap, alpha)
    }
}

/**
 * 页眉/页脚分割线：对应 app 端 view_book_page.xml 的 vw_top_divider / vw_bottom_divider。
 * 纯视觉叠加（1dp），不参与 tip 行排版高度（app 原版 0.5dp 同样忽略不计）。
 */
@Composable
private fun TipDivider(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * 顶部 tip：对应 app 端 PageView llHeader (tvHeaderLeft/Middle/Right)，
 * 布局占位子节点（wrap 高度，含页眉分割线）。
 *
 * 显隐由调用方（[PageViewComposable]）按 [ReadTipConfigShared.headerMode] 决定：
 * - 1: 恒显
 * - 2: 恒隐
 * - 0: 仅当隐藏状态栏时显示（[headerTipVisible]，跟随沉浸联动，对照原版
 *   `llHeader.isGone = when(headerMode){ 1->false; 2->true; else->!hideStatusBar }`）
 * 隐藏时不组合，高度自然为 0（对照原版 isGone）。
 * 高度由布局系统测量（wrap = 上下内边距 + tip 文本行高 + 可选分割线），
 * 经调用方 onSizeChanged 上报反哺排版视口。
 *
 * @param refreshTick tip 配置刷新版本号（[PageViewComposable] 的 tipRefreshTick）：
 *   prefs 直读无快照，版本号变化时本函数重新组合并重读槽位/模式（对照原版 STYLE →
 *   upStyle 直接重设 tip 视图）；同时作为 remember 键缓存槽位值，避免无关重组反复读 prefs
 */
@Composable
private fun HeaderTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    startPaddingDp: Int,
    endPaddingDp: Int,
    topPaddingDp: Int,
    bottomPaddingDp: Int,
    showDivider: Boolean,
    dividerColor: Color,
    refreshTick: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TipBar(
            slots = remember(refreshTick) {
                listOf(
                    readTipConfig.tipHeaderLeft,
                    readTipConfig.tipHeaderMiddle,
                    readTipConfig.tipHeaderRight,
                )
            },
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            startPaddingDp = startPaddingDp,
            endPaddingDp = endPaddingDp,
            topPaddingDp = topPaddingDp,
            bottomPaddingDp = bottomPaddingDp,
        )
        // 页眉分割线：随页眉行（子节点内底部，对照 app 端 vw_top_divider），
        // 计入页眉子节点实测高度——排版视口扣除后正文区与分割线永不重叠
        if (showDivider) {
            TipDivider(color = dividerColor)
        }
    }
}

/**
 * 页眉/页脚三栏 tip 行（对应原版 view_book_page.xml 的 llHeader / llFooter）。
 *
 * 三栏定位与原版 ConstraintLayout 一一对应：
 * - 左栏：`0dp + layout_constraintHorizontal_weight=1`，撑满「父宽 - 右栏宽」至右栏左缘，
 *   文字 Start 对齐（原版 tv_header_left / tv_footer_left）
 * - 中栏：`wrap_content` + Left/Right→Parent，绝对居中于整行（原版 tv_header_middle /
 *   tv_footer_middle）；槽位为空时与原版 `visibility=gone` 一致不组合、不占位
 * - 右栏：`wrap_content` + Right→Parent 靠右（原版 tv_header_right / tv_footer_right），
 *   不依赖 textAlign 生效与否，物理贴行右缘
 *
 * 高度为 wrap（上下内边距 + tip 文本行高），由布局系统测量（对照原版 llHeader/llFooter
 * wrap_content）；文本行高在 [TipSlot] 显式锁定 1.6 倍字号，行盒稳定不随字体平台漂移。
 *
 * 实现：Box 内一层 Row（左栏 weight(1f) 占满剩余 + 右栏 wrap 落行尾）+ 中栏 align(Center)
 * 叠加居中。空左栏用 weight Spacer 占位，保证右栏仍靠右（原版左栏 gone 后右栏约束不变）。
 *
 * # 高度恒为内容高度（页脚消失根因的治本约束，2026-08）
 *
 * Box 内 Row **不得加 fillMaxHeight**：布局占位子节点（HeaderTip/FooterTip）作为外层
 * Column 的非 weight 子节点，测量时收到的是「剩余空间」约束（RowColumnMeasurePolicy：
 * maxHeight = 容器高 − 已占用，随测量递减）。若 Row 加 fillMaxHeight，在有限约束下会被
 * 强制撑满（FillNode：minHeight = maxHeight = 约束高）——HeaderTip 第一个测量会撑到整页高、
 * 正文 weight(1f) 分到 0、FooterTip 收到 maxHeight=0 被压没（桌面端页脚消失回归根因）。
 * 本 Box 不加任何阻断约束的 modifier（wrapContentHeight 默认 unbounded=false 也不阻断，
 * 无效）：Row 高度 = tip 内容行高，任何有限/无限高度约束下 Box 恒为 wrap 内容高度。
 */
@Composable
private fun TipBar(
    slots: List<Int>,
    textPage: TextPage?,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    startPaddingDp: Int,
    endPaddingDp: Int,
    topPaddingDp: Int,
    bottomPaddingDp: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = startPaddingDp.dp,
                top = topPaddingDp.dp,
                end = endPaddingDp.dp,
                bottom = bottomPaddingDp.dp,
            ),
    ) {
        // 行盒高度恒为 tip 文本行高, 与内容无关 (对照原版 wrap_content 的 TextView: 空文字
        // 仍占满字体行盒)。否则 textPage 未到时槽位塌成零高, 首排视口偏大, 内容到达后
        // 页脚长高再排一次 —— 排版视口与正文内容形成循环依赖。
        val tipLineHeight = with(LocalDensity.current) {
            (READ_TIP_TEXT_SIZE_SP * 1.6f).sp.toDp()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tipLineHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (slots[0] == ReadTipConfigShared.none) {
                // 左栏空：weight 占位，右栏仍靠右（原版左栏 gone 后右栏 Right→Parent 不变）
                Spacer(modifier = Modifier.weight(1f))
            } else {
                TipSlot(
                    tipType = slots[0],
                    textPage = textPage,
                    textColor = textColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
            }
            if (slots[2] != ReadTipConfigShared.none) {
                TipSlot(
                    tipType = slots[2],
                    textPage = textPage,
                    textColor = textColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    textAlign = TextAlign.End,
                )
            }
        }
        // 中栏：wrap 内容绝对居中于整行（原版 Left→Parent + Right→Parent；gone 时不组合）
        if (slots[1] != ReadTipConfigShared.none) {
            TipSlot(
                tipType = slots[1],
                textPage = textPage,
                textColor = textColor,
                batteryLevel = batteryLevel,
                clockText = clockText,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 底部 tip：对应 app 端 PageView llFooter (tvFooterLeft/Middle/Right)，
 * 布局占位子节点（wrap 高度，含页脚分割线）。
 *
 * 显隐由调用方（[PageViewComposable]）按 [ReadTipConfigShared.footerMode] 决定
 * （0: 显示 / 1: 隐藏，对照原版 isGone）；隐藏时不组合，高度自然为 0。
 * 高度由布局系统测量（wrap = 上下内边距 + tip 文本行高 + 可选分割线），
 * 经调用方 onSizeChanged 上报反哺排版视口（单一来源，无独立预留公式）。
 *
 * @param refreshTick tip 配置刷新版本号（同 [HeaderTip]，槽位内容变更时重读 prefs）
 */
@Composable
private fun FooterTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    startPaddingDp: Int,
    endPaddingDp: Int,
    topPaddingDp: Int,
    bottomPaddingDp: Int,
    showDivider: Boolean,
    dividerColor: Color,
    refreshTick: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // 页脚分割线：随页脚行（子节点内顶部，对照 app 端 vw_bottom_divider），
        // 计入页脚子节点实测高度——排版视口扣除后正文区与分割线永不重叠
        if (showDivider) {
            TipDivider(color = dividerColor)
        }
        TipBar(
            slots = remember(refreshTick) {
                listOf(
                    readTipConfig.tipFooterLeft,
                    readTipConfig.tipFooterMiddle,
                    readTipConfig.tipFooterRight,
                )
            },
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            startPaddingDp = startPaddingDp,
            endPaddingDp = endPaddingDp,
            topPaddingDp = topPaddingDp,
            bottomPaddingDp = bottomPaddingDp,
        )
    }
}

/**
 * tip 槽位：按 [tipType] 常量渲染对应内容。
 *
 * 与 app 端 PageView.getTipView 对应，但用 when + Composable 替代 BatteryView 多态：
 * - [ReadTipConfigShared.none]: 由 [TipBar] 层过滤不组合（原版 visibility=gone），不走到本函数
 * - [ReadTipConfigShared.chapterTitle]: textPage.title
 * - [ReadTipConfigShared.time]: HH:mm（当前系统时间，随 timeChanged 刷新）
 * - [ReadTipConfigShared.battery] / [ReadTipConfigShared.batteryPercentage]: 电池图标 / 百分比文字
 * - [ReadTipConfigShared.page] / [ReadTipConfigShared.pageAndTotal]: 页码
 * - [ReadTipConfigShared.totalProgress] / [ReadTipConfigShared.totalProgress1]: 进度百分比
 * - [ReadTipConfigShared.bookName]: 书名（暂用 title 占位，actual 接入 ReadBookShared 后替换）
 * - [ReadTipConfigShared.timeBattery] / [ReadTipConfigShared.timeBatteryPercentage]: 时间 + 电池组合
 */
@Composable
private fun TipSlot(
    tipType: Int,
    textPage: TextPage?,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val text: String = when (tipType) {
        ReadTipConfigShared.none -> ""
        ReadTipConfigShared.chapterTitle -> textPage?.title ?: ""
        ReadTipConfigShared.time -> clockText
        ReadTipConfigShared.battery -> if (batteryLevel >= 0) "$batteryLevel%" else ""
        ReadTipConfigShared.batteryPercentage -> if (batteryLevel >= 0) "$batteryLevel%" else ""
        ReadTipConfigShared.page -> textPage?.let { "${it.index + 1}/${it.pageSize.takeIf { p -> p > 0 } ?: "-"}" } ?: ""
        ReadTipConfigShared.totalProgress -> textPage?.readProgress ?: ""
        ReadTipConfigShared.totalProgress1 -> textPage?.let { "${it.chapterIndex + 1}/${it.chapterSize.takeIf { s -> s > 0 } ?: "-"}" } ?: ""
        ReadTipConfigShared.pageAndTotal -> textPage?.let {
            val ps = it.pageSize.takeIf { p -> p > 0 } ?: "-"
            "${it.index + 1}/$ps  ${it.readProgress}"
        } ?: ""
        ReadTipConfigShared.bookName -> textPage?.title ?: ""
        ReadTipConfigShared.timeBattery -> if (batteryLevel >= 0) "$clockText $batteryLevel%" else clockText
        ReadTipConfigShared.timeBatteryPercentage -> if (batteryLevel >= 0) "$clockText $batteryLevel%" else clockText
        else -> ""
    }

    if (tipType == ReadTipConfigShared.battery && batteryLevel >= 0) {
        // 电池槽位用 BatteryIndicator 图标
        BatteryIndicator(
            batteryLevel = batteryLevel,
            color = textColor,
            modifier = modifier,
        )
    } else if (text.isNotEmpty()) {
        Text(
            text = text,
            color = textColor,
            fontSize = READ_TIP_TEXT_SIZE_SP.sp,
            // 行高显式锁定 1.6 倍字号（与布局占位子节点的 wrap 高度测量同一口径）：
            // 不继承 LocalTextStyle 默认行高，任何字体/平台/边距配置下文本都装进稳定
            // 行盒，页脚文字不会溢出子节点底边探入正文区（正文末行与 FooterTip 顶边
            // 的间隔恒为 paddingBottom）
            lineHeight = (READ_TIP_TEXT_SIZE_SP * 1.6f).sp,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    } else {
        Spacer(modifier = modifier)
    }
}

/**
 * 电池图标：简化版（与 app 端 BatteryView 视觉对齐）。
 *
 * app 端用自定义 View + drawable 绘制电池外形 + 液柱；
 * KMP 版用 Canvas 矩形 + Text 百分比近似，保持跨平台一致。
 */
@Composable
private fun BatteryIndicator(
    batteryLevel: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val safeLevel = batteryLevel.coerceIn(0, 100)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$safeLevel%",
            color = color,
            fontSize = 11.sp,
            // 显式行高 ≤ tip 行盒（12sp × 1.6），与 TipSlot 同口径不溢出预留行盒
            lineHeight = (11 * 1.6f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 滚动翻页模式专用视图：单画布三页连排（复刻原版 `ContentTextView.onDraw` 的
 * `drawPage(0/1/2)` 单 View 模型）。
 *
 * # 2026-08 重构背景（跨页错帧闪烁根治）
 *
 * 原三槽位结构（三个 [PageViewComposable]）中：页数据经 StateFlow →
 * collectAsState → **下一帧重组生效**；滚动偏移经 graphicsLayer **绘制期当帧生效**。
 * 跨页折算时两者分裂 → 渲染帧里"旧页 + 新偏移"错位一整页高 → 单帧闪烁。
 * 原版无此窗口：单 View 单 onDraw，textPage 与 pageOffset 同帧自洽。
 *
 * 本组件把三页数据 + 偏移收进 [ScrollPageDelegateCompose] 的渲染快照（普通字段，
 * 手势/外部路径在**同一同步块**内更新页数据与偏移），绘制期原子读取：
 * - 页数据：Canvas draw 块内绘制期直读 `scrollDelegate.renderPage(i)`，
 *   页切换由 [ScrollPageDelegateCompose.renderVersion]（snapshot state）驱动
 *   同帧重绘（draw 阶段快照读订阅，对照 ReaderImageCache.version 同款机制）；
 * - 偏移：graphicsLayer translationY 绘制期读 contentOffset，滚动热路径零重绘；
 * - 任意事件时序下，draw 帧读到的 (页, 偏移) 都是同一批更新后的自洽组合，
 *   跨页无错帧窗口（原版同款原子性）。
 *
 * # 结构（对照原版 view_book_page.xml + ContentTextView）
 *
 * 固定层（背景/背景图 + 页眉/页脚 tip 布局占位子节点）与 [PageViewComposable]
 * 完全一致；正文区为单个 Canvas：视口裁剪（clipRect(paddingTop, visibleBottom)）+
 * graphicsLayer 平移 + 三页按 relativeOffset 累加 y 连排绘制（对照原版 drawPage 的
 * relativeOffset(0/1/2)，章末短页 + 新章短页时第 3 页补位）。
 *
 * # 逐列 layout 缓存
 *
 * 组合期对快照三页 ensure 缓存（预热窗口命中零开销）；draw 期 miss 时同步构建兜底
 * （仅章装载完成与预热的竞态间隙触发，保正确性不出现缺字帧）。
 *
 * @param scrollDelegate 滚动翻页委托（渲染快照持有者）
 * @param textPage 当前页（tip 槽位/排版参数来源，随 StateFlow 重组）
 * 其余参数语义同 [PageViewComposable]。
 */
@Composable
fun ScrollPageView(
    scrollDelegate: ScrollPageDelegateCompose,
    viewModel: ReadBookViewModelShared,
    textPage: TextPage?,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    clockText: String = formatTimeOfDay(systemCurrentTimeMillis()),
    drawTick: Int = 0,
    selection: PageSelectionState? = null,
    onHeaderMeasured: ((Int) -> Unit)? = null,
    onFooterMeasured: ((Int) -> Unit)? = null,
    onTextAreaMeasured: ((IntSize) -> Unit)? = null,
) {
    // 外部路径页变化同步（切章/重排/跳页/初始装载）：四页流任一变化 → delegate 同步快照。
    // 手势路径跨页已在 applyScrollDelta 同一同步块内同步，流收集到的引用相等 → 无操作。
    LaunchedEffect(scrollDelegate) {
        combine(
            viewModel.curTextPage,
            viewModel.prevTextPage,
            viewModel.nextTextPage,
            viewModel.nextPlusTextPage,
        ) { _, _, _, _ -> }
            .collect { scrollDelegate.syncRenderPages() }
    }
    // 外部滚动偏移重置（切章/重排归零）同步（自原 renderPageAnimation 迁移）
    LaunchedEffect(scrollDelegate) {
        viewModel.scrollOffset.collect { offset ->
            scrollDelegate.onExternalScrollOffset(offset)
        }
    }

    val providers = LocalReadConfigProviders.current
    val readTipConfig = providers.readTipConfig
    val readBookConfig = providers.readBookConfig
    // 颜色/字号/字体统一走 ReaderDrawStyle（内部订阅 ReadBookEvents.configChange 重建）
    val style = rememberReaderDrawStyle()
    // tip 层刷新版本号（同 PageViewComposable 的 tipRefreshTick）
    var tipRefreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange.collect { changes ->
            if (changes.any { it in tipRefreshChanges }) tipRefreshTick++
        }
    }
    val density = LocalDensity.current
    val headerVisible = headerTipVisible(readTipConfig.headerMode, readBookConfig.hideStatusBar)
    val footerVisible = readTipConfig.footerMode != 1
    // 分割线颜色：对照 app 端 PageView.upStyle 的 tipDividerColor 解析
    val tipDividerColor = when (readTipConfig.tipDividerColor) {
        -1 -> rememberColor("divider")
        0 -> style.textColor
        else -> Color(readTipConfig.tipDividerColor)
    }
    // 惯性物理密度组合期注入（依赖 density；onFling 的 SplineFling 消费）
    scrollDelegate.provideDensity(density.density)
    // 组合期确保三页 layout 缓存就绪（预热窗口内命中零开销；miss 时同步构建兜底）
    val measurer = rememberReaderTextMeasurer()
    for (i in 0..2) {
        val page = scrollDelegate.renderPage(i) ?: continue
        ensureTextLayoutCache(page, measurer, style, density)
    }
    // 正文图失败占位（同 PageContentCanvas 语义）
    val failedImage by produceState<ImageBitmap?>(null) {
        value = runCatching {
            Res.readBytes("drawable/image_loading_error.png").decodeToImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = modifier
            // 底色必须不透明（同 PageViewComposable 约束）
            .background(style.bgColor.copy(alpha = 1f))
            .fillMaxSize()
    ) {
        ReaderBackgroundImage(
            source = style.backgroundImageSource,
            alpha = style.backgroundImageAlpha,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarFixedPadding()
                .navigationBarFixedPadding()
        ) {
            // 页眉：wrap 高度布局子节点（同 PageViewComposable）
            if (headerVisible) {
                HeaderTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    startPaddingDp = readBookConfig.headerPaddingLeft,
                    endPaddingDp = readBookConfig.headerPaddingRight,
                    topPaddingDp = readBookConfig.headerPaddingTop,
                    bottomPaddingDp = readBookConfig.headerPaddingBottom,
                    showDivider = readBookConfig.showHeaderLine,
                    dividerColor = tipDividerColor,
                    refreshTick = tipRefreshTick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onHeaderMeasured?.invoke(it.height) },
                )
            }

            // 正文区：剩余空间（weight 1f）。单一 Canvas 三页连排（对照原版 ContentTextView）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { onTextAreaMeasured?.invoke(it) }
            ) {
                // 视口裁剪（对照原版 canvas.clipRect(visibleRect)）：正文区与排版视口
                // 同帧同源，裁剪边界取排版产物 paddingTop/visibleBottom
                val clipTop = textPage?.paddingTop?.toFloat() ?: 0f
                val clipBottom = textPage?.visibleBottom?.toFloat() ?: Float.MAX_VALUE
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(
                                left = 0f,
                                top = clipTop,
                                right = size.width,
                                bottom = clipBottom,
                            ) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            // 绘制阶段读取偏移：滚动热路径只失效图层，不触发重组
                            .graphicsLayer {
                                translationY = scrollDelegate.contentOffset
                            }
                    ) {
                        // draw 期快照读：renderVersion（页切换）/drawTick（朗读高亮）/
                        // selection.tick（选区）/ReaderImageCache.version（图片）任一
                        // 变化都只重绘不重组；页数据绘制期直读 → 与偏移同帧自洽
                        if (scrollDelegate.renderVersion < 0) return@Canvas
                        if (drawTick < 0) return@Canvas
                        if (selection != null && selection.tick < 0) return@Canvas
                        if (ReaderImageCache.version < 0) return@Canvas
                        // 三页连排（对照原版 drawPage 的 relativeOffset(0/1/2)）：
                        // 第 i 页绘制 y = 前面页高之和，整画布随 graphicsLayer 平移偏移
                        var offsetY = 0f
                        for (i in 0..2) {
                            val page = scrollDelegate.renderPage(i) ?: continue
                            var cache = page.textLayoutCache as? TextLayoutCache
                            if (cache == null || !cache.matches(style, density)) {
                                // 预热竞态间隙兜底：绘制期同步构建，保正确性不出现缺字帧
                                cache = TextLayoutCache.build(page, measurer, style, density)
                                page.textLayoutCache = cache
                            }
                            drawPageContent(page, style, cache, failedImage, offsetY)
                            offsetY += page.height
                        }
                    }
                }
            }

            // 页脚：wrap 高度布局子节点（同 PageViewComposable）
            if (footerVisible) {
                FooterTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    startPaddingDp = readBookConfig.footerPaddingLeft,
                    endPaddingDp = readBookConfig.footerPaddingRight,
                    topPaddingDp = readBookConfig.footerPaddingTop,
                    bottomPaddingDp = readBookConfig.footerPaddingBottom,
                    showDivider = readBookConfig.showFooterLine,
                    dividerColor = tipDividerColor,
                    refreshTick = tipRefreshTick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { onFooterMeasured?.invoke(it.height) },
                )
            }
        }
    }
}
