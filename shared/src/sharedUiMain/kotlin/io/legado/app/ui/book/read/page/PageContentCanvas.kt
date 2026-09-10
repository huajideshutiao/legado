package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.ui.book.read.page.TextLayoutCache.Companion.COLUMN_BATCH
import io.legado.app.ui.book.read.page.TextLayoutCache.Companion.build
import io.legado.app.ui.book.read.page.entities.TextLayoutCacheHandle
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.overlay.PageOverlayProjector
import io.legado.app.ui.book.read.page.overlay.SearchHighlightOverlay
import io.legado.app.ui.book.read.page.overlay.TTSHighlightOverlay
import kotlinx.coroutines.yield
import legado.shared.generated.resources.Res
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * KMP 版阅读内容绘制 Canvas：用 Compose Multiplatform Canvas API 替代
 * app 端 `ContentTextView.onDraw` → `TextPage.draw` → `TextLine.draw` → `TextColumn.draw`
 * 的 Android Canvas 自绘链路。
 *
 * 数据模型（[TextPage] / [io.legado.app.ui.book.read.page.entities.TextLine] /
 * [BaseColumn] 子类）已下沉 commonMain，本 Composable 仅消费数据绘制，不依赖任何平台 View/Canvas。
 *
 * 绘制顺序与 app 端一致：
 * 1. 遍历 `textPage.lines`，每行 `line.lineTop` 作为 y 偏移（drawText topLeft.y = lineBase - baselineOffset）
 * 2. 遍历 `line.columns`，按 [BaseColumn] 实际类型分发：
 *    - [TextColumn]: drawText(charData)（选中/搜索高亮底色由 [drawOverlayLayers] 独立叠加）
 *    - [ImageColumn]: drawImage(renderCache as? ImageBitmap, srcSize, dstSize)
 *    - [ReviewColumn]: drawCircle + drawText(countText)
 * 3. 朗读/搜索下划线（E-Ink）与 `ReadBookConfig.underline` 下划线：drawLine
 *
 * 样式取值由 [ReaderDrawStyle] 统一从 `ReadBookConfigShared` 读出（对应 app 端
 * `TextStyleProvider.upStyle` 的 titlePaint/contentPaint/reviewPaint）。
 *
 * 文字位置口径：与 app 端 `ColumnRender.drawTextColumn` 一致，x = column.start + letterSpacingHalf
 * + column.drawOffsetX（标点挤压裁左半的字形偏移，app 端无此机制），
 * y = line.lineBase - baselineOffset（drawText 的 topLeft 是文本框左上角，需把行基线折算回框顶）。
 * letterSpacingHalf 仅 API35+ 补偿；KMP 版统一补偿以保持视觉一致。
 *
 * @param drawTick 页内容原地变更版本号（段评气泡就地补丁等）：变更时强制本 Canvas 重绘
 *   （纯重绘零 measure；对照 [ReaderImageCache.version] 的读值订阅模式）
 * @param selection 页内文字选择状态：绘制块内读 [PageSelectionState.tick] 建立快照订阅，
 *   拖拽扩选只失效本 Canvas 重绘、不触发任何重组；高亮走 Overlay 投影层绘制
 * @param ttsHighlight 朗读高亮位置（章节 + 章内字符位置，null = 无高亮）：声明式参数，
 *   绘制期折算成本页高亮行区间。选区与搜索命中的变更只经 draw 阶段快照通知、组合期读不到，
 *   所以那两类高亮仍由 [drawOverlayLayers] 在绘制期直接投影真实状态
 * @param pagePos 页在选区页空间中的相对位置（0=当前页 / 1=下一页 / 2=下下页 / -1=上一页不参与）
 */
@Composable
fun PageContentCanvas(
    textPage: TextPage,
    modifier: Modifier = Modifier,
    style: ReaderDrawStyle = rememberReaderDrawStyle(),
    onClick: (TextColumn?) -> Unit = {},
    drawTick: Int = 0,
    selection: PageSelectionState? = null,
    ttsHighlight: TTSHighlightOverlay? = null,
    searchHighlight: SearchHighlightOverlay? = null,
    pagePos: Int = 0,
) {
    val textMeasurer: TextMeasurer = rememberReaderTextMeasurer()
    // 2026-08 回退: 曾加整页录制缓存 (pageLayer.record + drawLayer), 但 record 在 Canvas
    // onDraw (CanvasDrawScope) 内调用会与外层 scope 的 fontScale 委托互相递归 →
    // StackOverflowError (CanvasDrawScope.getFontScale ↔ LayoutNodeDrawScope.getFontScale),
    // 阅读页白屏。改为安全方案: 组合阶段按字符/字素享元缓存 TextLayoutResult (相同字符+样式 measure 一次),
    // 每帧绘制走 drawText(layoutResult, topLeft = Offset(x, y)) 零 measure (官方 API, 无 scope 环)。
    // 缓存挂 TextPage 实例 (render 侧 lazy 注入, 取或建挂载): 页实例/样式/密度匹配
    // 才复用——换页/配置变更才重缓存, 翻页前 prewarm 产物直接命中 (见 PageLayoutPrewarm)。
    // 朗读/搜索高亮颜色不参与 measure (颜色不改变几何), 由绘制期 drawText(layoutResult,
    // color=...) 覆盖: 高亮变化只经 drawTick 触发纯重绘 (零 measure), 不再每词全页
    // 重新 measure (~300 列/页)。
    val density = LocalDensity.current
    val layoutCache = remember(textPage, style, density) {
        ensureTextLayoutCache(textPage, textMeasurer, style, density)
    }

    // 正文图失败占位: 对齐原版 ImageProvider.errorBitmap (image_loading_error 资源图)。
    // 组合期解码一次并缓存 (Canvas onDraw 非组合上下文不能加载资源, 也避免每帧解码);
    // 解码完成后重组触发 Canvas 重绘, 失败图从灰块切换到错误图。
    val failedImage by produceState<ImageBitmap?>(null) {
        value = runCatching { Res.readBytes("drawable/image_loading_error.png").decodeToImageBitmap() }.getOrNull()
    }

    Canvas(modifier = modifier) {
        // 读位图就绪计数建立快照订阅：图片异步加载完成后本页自动重绘（值本身不参与绘制）
        if (ReaderImageCache.version < 0) return@Canvas
        // 段评气泡就地补丁等原地变更（不在 data class 相等性内，StateFlow 去重不重发）：
        // 消费 drawTick 使本绘制块在版本自增后重新执行（值本身不参与绘制；
        // 布局缓存 key 不含 drawTick——本块是纯重绘）
        if (drawTick < 0) return@Canvas
        // 文字选择起止变化：draw 阶段读 tick 建立订阅，只重绘不重组（拖拽热路径）
        if (selection != null && selection.tick < 0) return@Canvas
        drawPageContent(
            textPage = textPage,
            style = style,
            layoutCache = layoutCache,
            failedImage = failedImage,
            selection = selection,
            ttsHighlight = ttsHighlight,
            searchHighlight = searchHighlight,
            pagePos = pagePos,
        )
    }
}

/**
 * 字符/字素 TextLayoutResult 享元缓存（组合阶段构建, 相同字符+样式只 measure 一次/页）。
 *
 * 原实现每帧对每列 drawText(textMeasurer, ...) 全量 measure（~300 列/页 × 60fps 翻页动画）；
 * 后续改为逐列缓存；现进一步升级为基于“字符/字素簇 + 文字样式（isTitle）”的享元模式（Flyweight Pattern）复用。
 * 对正文中大量重复出现的相同汉字/标点（如“的”、“了”、“，”、“。”等），在相同样式下共享同一个
 * TextLayoutResult / ColumnLayout 实例。
 *
 * 每帧绘制走 [DrawScope.drawText] 的 layoutResult 重载（零 measure, 通过 topLeft 平移坐标重放 glyph）。
 * 基线折算/字间距补偿量一并缓存（统一口径）。
 *
 * 实例挂载在 [TextPage.textLayoutCache]（commonMain 接口句柄）上跨组合存续：
 * 命中且 [matches]（页实例/样式/密度）直接复用，换页/配置变更才重建；
 * 朗读/搜索高亮颜色不参与 measure (颜色不改变几何), 由绘制期
 * drawText(layoutResult, color=...) 覆盖, 高亮变化经 drawTick 纯重绘零 measure;
 * 选区高亮是绘制期投影出来的覆盖矩形，不参与缓存。
 *
 * miss 时（就地重排版等异常路径）按需 measure 补入，保证不出现缺字。
 */
internal class TextLayoutCache(
    internal val contentCharLayouts: HashMap<String, ColumnLayout>,
    internal val titleCharLayouts: HashMap<String, ColumnLayout>,
    internal val reviewLayouts: HashMap<String, ColumnLayout>,
    private val textMeasurer: TextMeasurer,
    private val style: ReaderDrawStyle,
    private val density: Density,
    private val contentBaseline: Float,
    private val titleBaseline: Float,
    private val contentSpacingHalf: Float,
    private val titleSpacingHalf: Float,
) : TextLayoutCacheHandle {

    override fun invalidate() {
        // 清空并允许按需重建（miss 补入路径自动重建）
        contentCharLayouts.clear()
        titleCharLayouts.clear()
        reviewLayouts.clear()
    }

    override fun recycle() {
        contentCharLayouts.clear()
        titleCharLayouts.clear()
        reviewLayouts.clear()
    }

    /** 构建时的样式/密度快照是否与当前一致（一致才可复用） */
    fun matches(style: ReaderDrawStyle, density: Density): Boolean =
        this.style == style && this.density == density

    fun textLayout(column: TextColumn): ColumnLayout? {
        val isTitle = column.textLine.isTitle
        val map = if (isTitle) titleCharLayouts else contentCharLayouts
        val cached = map[column.charData]
        if (cached != null) return cached
        // miss 兜底: 就地重排版等异常路径按需 measure 补入, 不出现缺字
        // （正常路径构建期已全量填充, 此处零触发）
        return measureText(column.charData, isTitle)
    }

    internal fun measureText(charData: String, isTitle: Boolean): ColumnLayout {
        // 用基础样式 measure (lineStyle 已含正文色): 朗读/搜索高亮色
        // 不参与布局 (颜色不影响几何/换行), 由绘制期 drawText(color=...) 覆盖,
        // 避免朗读逐词推进触发全页重 measure (见 drawTextColumn)
        val layout = textMeasurer.measure(
            charData,
            if (isTitle) style.titleStyle else style.contentStyle,
        )
        val columnLayout = ColumnLayout(
            layout = layout,
            baselineOffset = if (isTitle) titleBaseline else contentBaseline,
            letterSpacingHalf = if (isTitle) titleSpacingHalf else contentSpacingHalf,
        )
        if (isTitle) {
            titleCharLayouts[charData] = columnLayout
        } else {
            contentCharLayouts[charData] = columnLayout
        }
        return columnLayout
    }

    fun reviewLayout(column: ReviewColumn): ColumnLayout? {
        val countText = column.countText
        val cached = reviewLayouts[countText]
        if (cached != null) return cached
        return measureReview(column)
    }

    internal fun measureReview(column: ReviewColumn): ColumnLayout {
        val countText = column.countText
        val lineStyle = if (column.textLine.isTitle) style.titleStyle else style.contentStyle
        val countTextStyle = lineStyle.copy(
            color = style.reviewColor,
            fontSize = style.reviewTextSize,
        )
        val countLayout = textMeasurer.measure(countText, countTextStyle)
        val columnLayout = ColumnLayout(
            layout = countLayout,
            baselineOffset = countLayout.getLineBaseline(0),
            letterSpacingHalf = 0f,
        )
        reviewLayouts[countText] = columnLayout
        return columnLayout
    }

    companion object {
        /** 增量预热每批实际 measure 的字符数（批间 yield 让出主线程） */
        private const val COLUMN_BATCH = 64

        /** 构建期预测量: baseline 折算 + letterSpacing 像素补偿（每套样式各测一次） */
        private class BaselineValues(
            val contentBaseline: Float,
            val titleBaseline: Float,
            val contentSpacingHalf: Float,
            val titleSpacingHalf: Float,
        ) {
            companion object {
                fun measure(
                    textMeasurer: TextMeasurer,
                    style: ReaderDrawStyle,
                    density: Density,
                ): BaselineValues {
                    // baseline 折算（每套样式各测一次）: drawText 的 topLeft 是文本框左上角,
                    // 行基线 lineBase 需减去首行 baseline 偏移得到框顶 y (与 app 端 drawText(x, lineBase) 不同)
                    val contentBaseline = textMeasurer.measure("水", style.contentStyle).getLineBaseline(0)
                    val titleBaseline = textMeasurer.measure("水", style.titleStyle).getLineBaseline(0)
                    // letterSpacing 是 em（字号倍数），折算像素补偿量：fontSizePx * em * 0.5
                    // （组合阶段无 DrawScope.toPx, 用传入密度换算）
                    val contentSpacingHalf = with(density) {
                        style.contentStyle.fontSize.toPx() * style.letterSpacingEm * 0.5f
                    }
                    val titleSpacingHalf = with(density) {
                        style.titleStyle.fontSize.toPx() * style.letterSpacingEm * 0.5f
                    }
                    return BaselineValues(contentBaseline, titleBaseline, contentSpacingHalf, titleSpacingHalf)
                }
            }
        }

        fun build(
            textPage: TextPage,
            textMeasurer: TextMeasurer,
            style: ReaderDrawStyle,
            density: Density,
        ): TextLayoutCache {
            val values = BaselineValues.measure(textMeasurer, style, density)
            val cache = TextLayoutCache(
                contentCharLayouts = HashMap(),
                titleCharLayouts = HashMap(),
                reviewLayouts = HashMap(),
                textMeasurer = textMeasurer,
                style = style,
                density = density,
                contentBaseline = values.contentBaseline,
                titleBaseline = values.titleBaseline,
                contentSpacingHalf = values.contentSpacingHalf,
                titleSpacingHalf = values.titleSpacingHalf,
            )
            fillAll(textPage, cache)
            return cache
        }

        /**
         * 增量构建（预热用）：与 [build] 同一享元 measure 实现（textLayout/reviewLayout
         * 的享元补入路径），每 [COLUMN_BATCH] 个实际新测字符 yield() 一次让出主线程——
         * 不阻塞首帧、不跨线程 measure（Compose TextMeasurer 无文档线程安全保证）。
         */
        suspend fun buildIncremental(
            textPage: TextPage,
            textMeasurer: TextMeasurer,
            style: ReaderDrawStyle,
            density: Density,
        ): TextLayoutCache {
            val values = BaselineValues.measure(textMeasurer, style, density)
            val cache = TextLayoutCache(
                contentCharLayouts = HashMap(),
                titleCharLayouts = HashMap(),
                reviewLayouts = HashMap(),
                textMeasurer = textMeasurer,
                style = style,
                density = density,
                contentBaseline = values.contentBaseline,
                titleBaseline = values.titleBaseline,
                contentSpacingHalf = values.contentSpacingHalf,
                titleSpacingHalf = values.titleSpacingHalf,
            )
            var measured = 0
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.lines[lineIndex]
                val isTitle = textLine.isTitle
                val columns = textLine.columns
                for (columnIndex in columns.indices) {
                    val column = columns[columnIndex]
                    when (column) {
                        is TextColumn -> {
                            val map = if (isTitle) cache.titleCharLayouts else cache.contentCharLayouts
                            if (!map.containsKey(column.charData)) {
                                cache.measureText(column.charData, isTitle)
                                if (++measured % COLUMN_BATCH == 0) yield()
                            }
                        }
                        is ReviewColumn -> {
                            if (!cache.reviewLayouts.containsKey(column.countText)) {
                                cache.measureReview(column)
                                if (++measured % COLUMN_BATCH == 0) yield()
                            }
                        }
                        else -> Unit // ButtonColumn 等暂无绘制
                    }
                }
            }
            return cache
        }

        private fun fillAll(textPage: TextPage, cache: TextLayoutCache) {
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.lines[lineIndex]
                val isTitle = textLine.isTitle
                val columns = textLine.columns
                for (columnIndex in columns.indices) {
                    val column = columns[columnIndex]
                    when (column) {
                        is TextColumn -> {
                            val map = if (isTitle) cache.titleCharLayouts else cache.contentCharLayouts
                            if (!map.containsKey(column.charData)) {
                                cache.measureText(column.charData, isTitle)
                            }
                        }
                        is ReviewColumn -> {
                            if (!cache.reviewLayouts.containsKey(column.countText)) {
                                cache.measureReview(column)
                            }
                        }
                        else -> Unit // ButtonColumn 等暂无绘制
                    }
                }
            }
        }
    }
}

/** 字符/字素享元缓存项: 布局结果 + 绘制辅助量 (享元模式共享实例)。 */
internal class ColumnLayout(
    val layout: TextLayoutResult,
    val baselineOffset: Float,
    val letterSpacingHalf: Float,
)

/**
 * 取或建页的逐列 TextLayoutResult 缓存（组合期调用；与 [PageContentCanvas] 的
 * remember 挂载同一挂载点 [TextPage.textLayoutCache]）。
 *
 * 滚动模式单画布三页连排（[ScrollPageView]）在组合期对快照三页调用本函数：
 * 预热窗口内页直接命中，窗口外 miss 时同步全量构建兜底（正常路径由
 * PageLayoutPrewarmEffect 覆盖，仅章装载完成与预热之间的竞态间隙触发）。
 */
internal fun ensureTextLayoutCache(
    textPage: TextPage,
    textMeasurer: TextMeasurer,
    style: ReaderDrawStyle,
    density: Density,
): TextLayoutCache {
    val cached = textPage.textLayoutCache
    if (cached is TextLayoutCache && cached.matches(style, density)) {
        return cached
    }
    return TextLayoutCache.build(textPage, textMeasurer, style, density).also {
        textPage.textLayoutCache = it
    }
}

/**
 * 单页内容绘制主体：与 app 端 `TextPageRender.drawPage` → `TextLine.draw` 对应。
 *
 * 采用分层绘制架构：
 * 1. 基础不可变文字/图片/段评层：[drawBasePageContent]（朗读高亮只在此层换文字色）
 * 2. Overlay 叠加层（选区、搜索高亮）：[drawOverlayLayers]
 *
 * @param offsetY 整页垂直平移量（px）：滚动模式单画布三页连排用，对照原版
 *   `drawPage(canvas, relativeOffset)` 的页间偏移；0 时不套 translate 零开销
 * @param selection 当前选区状态机（可选，用于动态拖拽划选的 Overlay 投影）
 * @param ttsHighlight 朗读高亮位置（null = 无高亮），绘制期折算成本页高亮行区间
 * @param pagePos 页面在视口中的相对位置索引（滚动模式 0/1/2，默认 0）
 */
internal fun DrawScope.drawPageContent(
    textPage: TextPage,
    style: ReaderDrawStyle,
    layoutCache: TextLayoutCache,
    failedImage: ImageBitmap?,
    offsetY: Float = 0f,
    selection: PageSelectionState? = null,
    ttsHighlight: TTSHighlightOverlay? = null,
    searchHighlight: SearchHighlightOverlay? = null,
    pagePos: Int = 0,
) {
    if (offsetY == 0f) {
        drawPageContentInner(
            textPage,
            style,
            layoutCache,
            failedImage,
            selection,
            ttsHighlight,
            searchHighlight,
            pagePos
        )
    } else {
        translate(left = 0f, top = offsetY) {
            drawPageContentInner(
                textPage,
                style,
                layoutCache,
                failedImage,
                selection,
                ttsHighlight,
                searchHighlight,
                pagePos
            )
        }
    }
}

/**
 * [drawPageContent] 的零平移主体。
 */
private fun DrawScope.drawPageContentInner(
    textPage: TextPage,
    style: ReaderDrawStyle,
    layoutCache: TextLayoutCache,
    failedImage: ImageBitmap?,
    selection: PageSelectionState? = null,
    ttsHighlight: TTSHighlightOverlay? = null,
    searchHighlight: SearchHighlightOverlay? = null,
    pagePos: Int = 0,
) {
    // 1. 基础不可变内容层（文字、图片、段评气泡、基础下划线、朗读/搜索高亮文字色）
    val ttsLines = ttsHighlight?.let { PageOverlayProjector.projectTTS(textPage, it) } ?: IntRange.EMPTY
    drawBasePageContent(textPage, style, layoutCache, failedImage, ttsLines, searchHighlight)

    // 2. 独立叠加绘制层（Overlay 几何投影高亮）
    drawOverlayLayers(textPage, style, selection, searchHighlight, pagePos)
}

/**
 * 绘制基础排版内容（文字、图片、段评与静态排版下划线）。
 * 该层为纯粹的排版快照渲染，不包含任何动态选区或高亮背景色块。
 *
 * @param ttsLines 朗读高亮行索引闭区间（[IntRange.EMPTY] = 本页无朗读高亮）：
 *   命中行只换文字色，不画任何矩形（对照 app 端 isReadAloud 行标志的视觉）
 */
private fun DrawScope.drawBasePageContent(
    textPage: TextPage,
    style: ReaderDrawStyle,
    layoutCache: TextLayoutCache,
    failedImage: ImageBitmap?,
    ttsLines: IntRange,
    searchHighlight: SearchHighlightOverlay?,
) {
    // 基线折算/字间距补偿/字符享元 TextLayoutResult 均已在 [TextLayoutCache] 构建时缓存,
    // 每帧只重放绘制 (drawText(layoutResult, topLeft = Offset(x, y)) 零 measure)。
    val underlineWidth = 1.dp.toPx()
    for (lineIndex in textPage.lines.indices) {
        val textLine = textPage.lines[lineIndex]
        val isReadAloud = lineIndex in ttsLines
        val lineTop = textLine.lineTop
        val lineBase = textLine.lineBase
        val lineHeight = textLine.lineBottom - textLine.lineTop
        var columnChapterOffset = 0
        for (columnIndex in textLine.columns.indices) {
            val column = textLine.columns[columnIndex]
            when (column) {
                is TextColumn -> {
                    val cached = layoutCache.textLayout(column)
                    if (cached != null) {
                        val isSearchHit = searchHighlight?.let {
                            PageOverlayProjector.isSearchRangeHit(
                                textPage = textPage,
                                highlight = it,
                                start = textLine.chapterPosition + columnChapterOffset,
                                endExclusive = textLine.chapterPosition + columnChapterOffset + column.charData.length,
                            )
                        } == true
                        drawTextColumn(
                            column = column,
                            layout = cached,
                            textColor = if (isReadAloud || isSearchHit) {
                                style.accentColor
                            } else {
                                style.textColor
                            },
                            lineBase = lineBase,
                        )
                    }
                }
                is ImageColumn -> drawImageColumn(
                    column = column,
                    lineTop = lineTop,
                    lineHeight = lineHeight,
                    placeholderColor = style.textColor,
                    failedImage = failedImage,
                )
                is ReviewColumn -> drawReviewColumn(
                    column = column,
                    layout = layoutCache.reviewLayout(column),
                    reviewColor = style.reviewColor,
                    lineTop = lineTop,
                    lineHeight = lineHeight,
                )
                else -> Unit // ButtonColumn 等暂无绘制
            }
            columnChapterOffset += if (column is TextColumn) column.charData.length else 1
        }
        val hasSearchResult = searchHighlight?.let {
            PageOverlayProjector.isSearchRangeHit(
                textPage = textPage,
                highlight = it,
                start = textLine.chapterPosition,
                endExclusive = textLine.chapterPosition + textLine.charSize,
            )
        } == true
        // 墨水屏模式下的朗读/搜索下划线（与 app 端 TextLine.drawTextLine 的 isEInkMode 分支一致）
        if (style.isEInk && (isReadAloud || hasSearchResult)) {
            drawLineUnderline(
                textLine.lineStart + textLine.indentWidth, textLine.lineEnd,
                lineTop + lineHeight - underlineWidth, style.textColor, underlineWidth
            )
        }
        // 配置项下划线（与 app 端 ReadBookConfig.underline → drawUnderline 一致，图片行不画）
        if (style.underline && !textLine.isImage) {
            drawLineUnderline(
                textLine.lineStart + textLine.indentWidth, textLine.lineEnd,
                lineTop + lineHeight - underlineWidth, style.textColor, underlineWidth
            )
        }
    }
}

/**
 * 绘制 Overlay 叠加层（全部几何都由 [PageOverlayProjector] 投影，本层只负责涂色）：
 * 1. [PageSelectionState] 交互选区（长按划选、手柄拖拽、搜索跳转选区）
 * 2. 排版产物自带的搜索命中标记（选区取消后仍留在搜索态的高亮）
 */
private fun DrawScope.drawOverlayLayers(
    textPage: TextPage,
    style: ReaderDrawStyle,
    selection: PageSelectionState? = null,
    searchHighlight: SearchHighlightOverlay? = null,
    pagePos: Int = 0,
) {
    // 1. 绘制手势选择状态机投影（拖拽热路径：边投影边画，零 List/矩形对象分配）
    if (selection != null && selection.isActive) {
        val selectedColor = style.selectedColor
        PageOverlayProjector.projectSelectionState(textPage, selection, pagePos) { l, t, r, b, _ ->
            drawRect(color = selectedColor, topLeft = Offset(l, t), size = Size(r - l, b - t))
        }
    }

    // 2. 搜索命中是外部章内区间；每页独立求交，跨页结果自然覆盖所有涉及页面
    if (searchHighlight != null) {
        val searchColor = style.searchColor
        PageOverlayProjector.projectSearchResult(textPage, searchHighlight) { l, t, r, b, _ ->
            drawRect(color = searchColor, topLeft = Offset(l, t), size = Size(r - l, b - t))
        }
    }
}

/**
 * 把 [PageSelectionState] 的当前选区投影到 [pagePos] 页并逐个 emit 高亮矩形。
 *
 * 留在 sharedUiMain（而非 projector 本体）：[PageSelectionState] 不在 commonMain；
 * 本函数只做"页维度门控 + 行列钳制"，几何算法走 [PageOverlayProjector.projectSelection]。
 */
inline fun PageOverlayProjector.projectSelectionState(
    textPage: TextPage,
    selectionState: PageSelectionState,
    pagePos: Int = 0,
    emit: (left: Float, top: Float, right: Float, bottom: Float, lineIndex: Int) -> Unit,
) {
    if (!selectionState.isActive || !selectionState.start.isValid || !selectionState.end.isValid) return
    val lines = textPage.lines
    if (lines.isEmpty()) return
    val s = selectionState.start
    val e = selectionState.end
    val forward = s.compareTo(e) <= 0
    val start = if (forward) s else e
    val end = if (forward) e else s
    if (pagePos < start.pagePos || pagePos > end.pagePos) return

    val onStartPage = pagePos == start.pagePos
    val onEndPage = pagePos == end.pagePos
    projectSelection(
        textPage = textPage,
        startLineIndex = if (onStartPage) start.lineIndex.coerceIn(0, lines.lastIndex) else 0,
        startColIndex = if (onStartPage) start.columnIndex else 0,
        endLineIndex = if (onEndPage) end.lineIndex.coerceIn(0, lines.lastIndex) else lines.lastIndex,
        // 非终点页：整行选中（列上界由 projectSelection 按各行 lastIndex 钳制）
        endColIndex = if (onEndPage) end.columnIndex else Int.MAX_VALUE,
        emit = emit,
    )
}

private fun DrawScope.drawLineUnderline(
    startX: Float,
    endX: Float,
    y: Float,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(
        color = color,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = strokeWidth,
    )
}

/**
 * 绘制文字列：对应 app 端 `ColumnRender.drawTextColumn` 的文字绘制部分。
 *
 * 文字位置：x = column.start + letterSpacingHalf（API35+ 补偿口径）+ column.drawOffsetX
 * （标点挤压裁左半的字形左移量，未挤压为 0），
 * y = lineBase - baselineOffset（drawText topLeft 是文本框左上角，lineBase 折算回框顶）。
 *
 * 选中高亮与搜索背景已解耦至 [drawOverlayLayers] 独立绘制（按列盒，不跟随字形偏移）。
 */
private fun DrawScope.drawTextColumn(
    column: TextColumn,
    layout: ColumnLayout,
    textColor: Color,
    lineBase: Float,
) {
    val x = column.start + layout.letterSpacingHalf + column.drawOffsetX
    val y = lineBase - layout.baselineOffset
    // 越界保护 (对照原版 Android Canvas.drawText: 越界不绘制也不崩):
    // drawText(layoutResult) 内部用 `scopeSize - topLeft` 算文本约束, topLeft 超出画布时
    // 约束为负直接抛 IllegalArgumentException (maxWidth must be >= minWidth)。
    // 窗口 resize / 翻页动画期间旧排版数据可能暂时超出画布, 完全越界时跳过, 部分可见时正常画。
    if (x >= size.width || y >= size.height) return
    // 缓存布局零 measure 重放; 朗读/搜索高亮色在绘制期覆盖 (官方 drawText 重载:
    // 颜色不参与布局, 只改 paint.color 重放 glyph)。高亮变化零 measure 纯重绘,
    // 视觉与原"颜色固化进布局"一致 (同判定同色值)
    drawText(layout.layout, topLeft = Offset(x, y), color = textColor)
}

/**
 * 绘制图片列：对应 app 端 `ColumnRender.drawImageColumn`。
 *
 * 位图取自 [ReaderImageCache]（排版时 `ImageResolver.getImageSize` 已解码入缓存），
 * 被 LRU 淘汰时就地发起异步补加载，加载完成自增 version 触发本页重绘。
 * app 端 actual 侧填过 [ImageColumn.renderCache] 时优先用它。
 *
 * 加载中画灰色占位块，取图失败绘制 image_loading_error 错误图
 * （对照 app 端 ImageProvider 的 errorBitmap，按原图占位矩形等比居中）。
 *
 * 缩放比例：保持原图宽高比，按 containerW/containerH 中较小者缩放，
 * 居中放置（与 app 端 `ImageDrawCache.updateDrawCache` 算法一致）。
 */
private fun DrawScope.drawImageColumn(
    column: ImageColumn,
    lineTop: Float,
    lineHeight: Float,
    placeholderColor: Color,
    failedImage: ImageBitmap?,
) {
    val containerW = column.end - column.start
    val containerH = lineHeight
    val bitmap = column.renderCache as? ImageBitmap ?: ReaderImageCache.peek(column.src)
    if (bitmap == null) {
        val failed = ReaderImageCache.isFailed(column.src)
        if (!failed && column.src.isNotBlank()) ReaderImageCache.requestAsync(column.src)
        drawImagePlaceholder(column.start, lineTop, containerW, containerH, placeholderColor, failed, failedImage)
        return
    }
    val bW = bitmap.width.toFloat()
    val bH = bitmap.height.toFloat()
    if (bW <= 0f || bH <= 0f) return
    val drawScale = (containerW / bW).coerceAtMost(containerH / bH)
    val finalW = bW * drawScale
    val finalH = bH * drawScale
    val offsetX = (containerW - finalW) / 2f
    val offsetY = (containerH - finalH) / 2f
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(
            x = (column.start + offsetX).toInt(),
            y = (lineTop + offsetY).toInt(),
        ),
        dstSize = IntSize(
            width = finalW.toInt().coerceAtLeast(1),
            height = finalH.toInt().coerceAtLeast(1),
        ),
    )
}

/** 图片占位：加载中为浅色实块（本地增强，原版无加载中占位）；失败时再叠错误图
 * （对齐原版 ImageProvider 的 errorBgPaint 灰底 + errorBitmap 直画语义）。 */
private fun DrawScope.drawImagePlaceholder(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    failed: Boolean,
    errorImage: ImageBitmap?,
) {
    if (width <= 0f || height <= 0f) return
    drawRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(left, top),
        size = Size(width, height),
    )
    if (!failed) return
    val image = errorImage ?: return
    // 对齐原版 drawBitmap(bitmap, null, cachedRectF, paint) 语义: 错误图按自身宽高比在
    // 容器矩形内等比居中 (image_loading_error 512×512 即正方形), 与正常图片列居中缩放算法一致
    val scale = (width / image.width).coerceAtMost(height / image.height)
    val drawW = image.width * scale
    val drawH = image.height * scale
    drawImage(
        image = image,
        dstOffset = IntOffset(
            x = (left + (width - drawW) / 2f).toInt(),
            y = (top + (height - drawH) / 2f).toInt(),
        ),
        dstSize = IntSize(
            width = drawW.toInt().coerceAtLeast(1),
            height = drawH.toInt().coerceAtLeast(1),
        ),
    )
}

/**
 * 绘制段评气泡：对应 app 端 `ColumnRender.drawReviewColumn`。
 *
 * 简化版：用 drawCircle 画外圈（与 app 端 ReviewIcon 椭圆形状近似），
 * 居中绘制 count 文字。app 端精确的胶囊气泡 + 数字字号缓存留待后续下沉。
 * 颜色/字号取 [ReaderDrawStyle]（对应 app 端 reviewPaint = 正文色 60% + 0.45 倍字号）。
 */
private fun DrawScope.drawReviewColumn(
    column: ReviewColumn,
    layout: ColumnLayout?,
    reviewColor: Color,
    lineTop: Float,
    lineHeight: Float,
) {
    if (column.count <= 0) return
    val containerW = column.end - column.start
    val radius = minOf(containerW, lineHeight) * 0.45f
    val centerX = column.start + containerW / 2f
    val centerY = lineTop + lineHeight / 2f
    drawCircle(
        color = reviewColor.copy(alpha = reviewColor.alpha * 0.33f),
        radius = radius,
        center = Offset(centerX, centerY),
    )
    drawCircle(
        color = reviewColor,
        radius = radius,
        center = Offset(centerX, centerY),
        style = Stroke(width = 1.5f),
    )
    // 数字居中绘制：drawText topLeft 是文本框左上角,
    // 用缓存 layout 的实际宽高折算到圆心使文字视觉居中 (TextLayoutCache.build 已 measure)
    val layout = layout ?: return
    val textWidth = layout.layout.size.width.toFloat()
    val textHeight = layout.layout.size.height.toFloat()
    val countTopLeft = Offset(
        centerX - textWidth / 2f,
        centerY - textHeight / 2f,
    )
    // 越界保护：同 [drawTextColumn]，约束为负会抛异常（resize/翻页动画期间旧排版可能越界）
    if (countTopLeft.x >= size.width || countTopLeft.y >= size.height) return
    // 缓存布局零 measure 重放
    drawText(layout.layout, topLeft = countTopLeft)
}
