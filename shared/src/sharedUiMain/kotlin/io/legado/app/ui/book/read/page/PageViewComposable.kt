package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * KMP 版阅读页面视图：用 Compose 替代 app 端 `PageView` (FrameLayout + ViewBookPageBinding)。
 *
 * 内部结构：`Box(background) { PageContentCanvas + HeaderTip + FooterTip + BatteryIndicator }`
 * 与 app 端 PageView 布局一一对应：
 * - 背景：先从 [ReaderDrawStyle] 绘制不透明底色，再把配置的背景图片按透明度中心裁剪叠加
 * - 主内容：[PageContentCanvas] fillMaxSize
 * - 顶部 tip：[HeaderTip] 显示章节标题/时间/电池（按 [ReadTipConfigShared] 6 槽位配置）
 * - 底部 tip：[FooterTip] 显示页码/进度（按 [ReadTipConfigShared] 6 槽位配置）
 * - 电池图标：[BatteryIndicator] 显示电池百分比
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
 * @param contentTranslationY 正文内容垂直平移提供者 (滚动模式行级滚动用, px)。
 *   在 graphicsLayer 绘制阶段调用, 只失效图层不触发重组; null = 不平移 (其他翻页模式零开销)。
 *   平移时正文固定裁剪在 [paddingTop, visibleBottom] 视口区 (对照旧
 *   ContentTextView.onDraw 的 canvas.clipRect(visibleRect)), 页眉/页脚 tip 不随内容滚动。
 * @param showChrome 是否渲染页面装饰 (背景/页眉/页脚/分割线)。false = 纯正文内容渲染,
 *   供滚动模式下一页连排使用 (对照旧 drawPage 只画 TextPage 内容, 背景由固定层提供)。
 * @param selection 页内文字选择状态, 透传给 [PageContentCanvas] (绘制块内订阅 tick 重绘)
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
    contentTranslationY: (() -> Float)? = null,
    showChrome: Boolean = true,
    selection: PageSelectionState? = null,
) {
    val providers = LocalReadConfigProviders.current
    val readTipConfig = providers.readTipConfig
    val readBookConfig = providers.readBookConfig
    // 颜色/字号/字体统一走 ReaderDrawStyle（内部订阅 ReadBookEvents.configChange 重建）
    val style = rememberReaderDrawStyle()
    // tip 行高与排版视口预留同源（ReaderRoute.buildLayoutConfig 用同一 tipRowHeightPx 公式），
    // 隐藏模式高度为 0，正文排版区随之扩展（对照 app 端 isGone 后 onSizeChanged 重排）
    val density = LocalDensity.current
    val headerTipHeight = if (readTipConfig.headerMode == 2) 0
    else tipRowHeightPx(
        density,
        readBookConfig.headerPaddingTop,
        readBookConfig.headerPaddingBottom
    )
    val footerTipHeight = if (readTipConfig.footerMode == 1) 0
    else tipRowHeightPx(
        density,
        readBookConfig.footerPaddingTop,
        readBookConfig.footerPaddingBottom
    )
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
            // 滚动模式下一页 (showChrome=false) 不画背景：背景由固定层（当前页）提供，
            // 避免整屏纯色覆盖当前页内容 (对照旧 drawPage 只画 TextPage 内容)
            .then(
                if (showChrome) Modifier.background(style.bgColor.copy(alpha = 1f))
                else Modifier
            )
            .fillMaxSize()
    ) {
        if (showChrome) {
            ReaderBackgroundImage(
                source = style.backgroundImageSource,
                alpha = style.backgroundImageAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 状态栏/导航栏避让层（对照原版 PageView 的 vwStatusBar/vwNavigationBar 占位 View：
        // 背景由外层 Box 铺满（含系统栏区域，显示阅读背景色），本层正文/页眉/页脚整体
        // 避让系统栏；系统栏隐藏时 inset=0，内容自然顶到窗口边，等价原版 hideStatusBar/
        // hideNavigationBar 配置下占位 View isGone。桌面端无系统栏 inset，恒为 no-op。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .platformStatusBarPadding()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                )
        ) {
            if (contentTranslationY != null) {
                // 滚动模式: 正文固定视口裁剪 + 行级平移 (对照旧 canvas.clipRect(visibleRect) +
                // withTranslation(0f, pageOffset))。裁剪在平移外层, 视口边界不随内容移动。
                val clipTop = textPage?.paddingTop?.toFloat() ?: 0f
                val clipBottom = textPage?.visibleBottom?.toFloat() ?: Float.MAX_VALUE
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            // clipRect 块内 receiver 为 DrawScope, drawContent 需显式指定外层
                            // ContentDrawScope receiver (K2 不隐式回退到外层接收者)
                            clipRect(
                                left = 0f,
                                top = clipTop,
                                right = size.width,
                                bottom = clipBottom
                            ) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 绘制阶段读取偏移: 滚动热路径只失效图层, 不触发重组
                            .graphicsLayer {
                                translationY = contentTranslationY()
                            }
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
            } else {
                // 非滚动模式: 原样渲染 (零额外层开销)
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

            // 顶部 tip：锚定顶部，行高 = 排版预留的页眉高度
            if (showChrome) {
                HeaderTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    heightPx = headerTipHeight,
                    startPaddingDp = readBookConfig.headerPaddingLeft,
                    endPaddingDp = readBookConfig.headerPaddingRight,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // 页眉分割线：贴合页眉底边（对照 app 端 vw_top_divider）
            if (showChrome && readTipConfig.headerMode != 2 && readBookConfig.showHeaderLine) {
                TipDivider(
                    color = tipDividerColor,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { headerTipHeight.toDp() }),
                )
            }

            // 底部 tip：锚定底部，行高 = 排版预留的页脚高度
            if (showChrome) {
                FooterTip(
                    textPage = textPage,
                    readTipConfig = readTipConfig,
                    textColor = style.tipColor,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    heightPx = footerTipHeight,
                    startPaddingDp = readBookConfig.footerPaddingLeft,
                    endPaddingDp = readBookConfig.footerPaddingRight,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 页脚分割线：贴合页脚顶边（对照 app 端 vw_bottom_divider）
            if (showChrome && readTipConfig.footerMode != 1 && readBookConfig.showFooterLine) {
                TipDivider(
                    color = tipDividerColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = with(density) { footerTipHeight.toDp() }),
                )
            }
        }
    }
}

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
 * 顶部 tip：对应 app 端 PageView llHeader (tvHeaderLeft/Middle/Right)。
 *
 * 按 [ReadTipConfigShared.headerMode] 决定是否显示：
 * - 0: 显示（与状态栏显示同步，桌面端无状态栏概念，按"显示"处理）
 * - 1: 显示
 * - 2: 隐藏
 */
@Composable
private fun HeaderTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    heightPx: Int,
    startPaddingDp: Int,
    endPaddingDp: Int,
    modifier: Modifier = Modifier,
) {
    if (readTipConfig.headerMode == 2) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { heightPx.toDp() })
            .padding(start = startPaddingDp.dp, end = endPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipSlot(
            tipType = readTipConfig.tipHeaderLeft,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
        )
        TipSlot(
            tipType = readTipConfig.tipHeaderMiddle,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
            align = Alignment.CenterHorizontally,
        )
        TipSlot(
            tipType = readTipConfig.tipHeaderRight,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
            align = Alignment.End,
        )
    }
}

/**
 * 底部 tip：对应 app 端 PageView llFooter (tvFooterLeft/Middle/Right)。
 *
 * 按 [ReadTipConfigShared.footerMode] 决定是否显示：
 * - 0: 显示
 * - 1: 隐藏
 */
@Composable
private fun FooterTip(
    textPage: TextPage?,
    readTipConfig: ReadTipConfigShared,
    textColor: Color,
    batteryLevel: Int,
    clockText: String,
    heightPx: Int,
    startPaddingDp: Int,
    endPaddingDp: Int,
    modifier: Modifier = Modifier,
) {
    if (readTipConfig.footerMode == 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { heightPx.toDp() })
            .padding(start = startPaddingDp.dp, end = endPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipSlot(
            tipType = readTipConfig.tipFooterLeft,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
        )
        TipSlot(
            tipType = readTipConfig.tipFooterMiddle,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
            align = Alignment.CenterHorizontally,
        )
        TipSlot(
            tipType = readTipConfig.tipFooterRight,
            textPage = textPage,
            textColor = textColor,
            batteryLevel = batteryLevel,
            clockText = clockText,
            modifier = Modifier.weight(1f),
            align = Alignment.End,
        )
    }
}

/**
 * tip 槽位：按 [tipType] 常量渲染对应内容。
 *
 * 与 app 端 PageView.getTipView 对应，但用 when + Composable 替代 BatteryView 多态：
 * - [ReadTipConfigShared.none]: 空 Spacer 占位
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
    align: Alignment.Horizontal = Alignment.Start,
) {
    val textAlign = when (align) {
        Alignment.Start -> TextAlign.Start
        Alignment.Center -> TextAlign.Center
        Alignment.End -> TextAlign.End
        else -> TextAlign.Start
    }
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
            fontSize = 12.sp,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ===== @Preview 合并自 androidMain 的 book/read/page/ReadPagePreviews.kt (PageViewComposable) =====

// ===== PageViewComposable =====


/**
 * 包装 [LegadoThemePreview], 在其基础上注入 [LocalReadConfigProviders] (走 stub prefs)。
 */
@Composable
private fun LegadoReadConfigPreview(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    LegadoThemePreview(dark = dark) {
        val prefs = LocalPreferenceStoreProvider.current
        val providers = ReadConfigProviders(prefs)
        CompositionLocalProvider(LocalReadConfigProviders provides providers) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}


@Preview
@Composable
fun PageViewComposableEmptyPreview() = LegadoReadConfigPreview {
    // textPage=null 时显示加载占位 (背景色取自 stub ReadBookConfig)
    PageViewComposable(
        textPage = null,
        batteryLevel = 75,
    )
}

@Preview
@Composable
fun PageViewComposableWithEmptyPagePreview() = LegadoReadConfigPreview {
    // 用 TextPage.emptyTextPage (无文字行, 仅显示 tip 占位)
    PageViewComposable(
        textPage = TextPage.emptyTextPage,
        batteryLevel = 50,
    )
}

@Preview
@Composable
fun PageViewComposableNoBatteryPreview() = LegadoReadConfigPreview {
    PageViewComposable(
        textPage = TextPage.emptyTextPage,
        batteryLevel = -1,
    )
}

