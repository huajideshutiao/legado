package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.preview.LegadoThemePreview

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
 *    - [TextColumn]: drawText(charData) + 选中/搜索结果高亮 drawRect
 *    - [ImageColumn]: drawImage(renderCache as? ImageBitmap, srcSize, dstSize)
 *    - [ReviewColumn]: drawCircle + drawText(countText)
 * 3. 朗读/搜索下划线（E-Ink）与 `ReadBookConfig.underline` 下划线：drawLine
 *
 * 样式取值由 [ReaderDrawStyle] 统一从 `ReadBookConfigShared` 读出（对应 app 端
 * `TextStyleProvider.upStyle` 的 titlePaint/contentPaint/reviewPaint）。
 *
 * 文字位置口径：与 app 端 `ColumnRender.drawTextColumn` 一致，x = column.start + letterSpacingHalf，
 * y = line.lineBase - baselineOffset（drawText 的 topLeft 是文本框左上角，需把行基线折算回框顶）。
 * letterSpacingHalf 仅 API35+ 补偿；KMP 版统一补偿以保持视觉一致。
 *
 * @param drawTick 页内容原地变更版本号（朗读高亮等）：变更时强制本 Canvas 重绘，
 *   不参与绘制逻辑（对照 [ReaderImageCache.version] 的读值订阅模式）
 * @param selection 页内文字选择状态：绘制块内读 [PageSelectionState.tick] 建立快照订阅，
 *   拖拽扩选只失效本 Canvas 重绘、不触发任何重组；高亮本身读 `TextColumn.selected` 数据
 */
@Composable
fun PageContentCanvas(
    textPage: TextPage,
    modifier: Modifier = Modifier,
    style: ReaderDrawStyle = rememberReaderDrawStyle(),
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
    drawTick: Int = 0,
    selection: PageSelectionState? = null,
) {
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    // 2026-08 回退: 曾加整页录制缓存 (pageLayer.record + drawLayer), 但 record 在 Canvas
    // onDraw (CanvasDrawScope) 内调用会与外层 scope 的 fontScale 委托互相递归 →
    // StackOverflowError (CanvasDrawScope.getFontScale ↔ LayoutNodeDrawScope.getFontScale),
    // 阅读页白屏。改为安全方案: 组合阶段逐列缓存 TextLayoutResult (measure 一次),
    // 每帧绘制走 drawText(layoutResult) 零 measure (官方 API, 无 scope 环)。
    // 缓存 key: 页实例/样式/页内容版本(drawTick: 朗读高亮等变色)——换页/配置变更/高亮变化才重缓存
    val density = LocalDensity.current
    val layoutCache = remember(textPage, style, drawTick) {
        TextLayoutCache.build(textPage, textMeasurer, style, density)
    }

    Canvas(modifier = modifier) {
        // 读位图就绪计数建立快照订阅：图片异步加载完成后本页自动重绘（值本身不参与绘制）
        if (ReaderImageCache.version < 0) return@Canvas
        // 朗读高亮等原地变更（isReadAloud 不在 data class 相等性内，StateFlow 去重不重发）：
        // 消费 drawTick 使本绘制块在版本自增后重新执行（值本身不参与绘制）
        if (drawTick < 0) return@Canvas
        // 文字选择起止变化：draw 阶段读 tick 建立订阅，只重绘不重组（拖拽热路径）
        if (selection != null && selection.tick < 0) return@Canvas
        drawPageContent(
            textPage = textPage,
            style = style,
            layoutCache = layoutCache,
        )
    }
}

/**
 * 逐列 TextLayoutResult 缓存（组合阶段构建, measure 一次/页）。
 *
 * 原实现每帧对每列 drawText(textMeasurer, ...) 全量 measure（~300 列/页 × 60fps 翻页动画）；
 * 本缓存把 measure 收敛到构建时一次, 每帧绘制走 [DrawScope.drawText] 的 layoutResult 重载
 * （零 measure, 只重放 glyph）。基线折算/字间距补偿量一并缓存（与列布局同 key）。
 *
 * key 由调用方 remember(textPage, style, drawTick) 保证: 换页新 TextPage、配置变更新
 * ReaderDrawStyle、朗读高亮等变色经 drawTick 自增重缓存; 选区高亮 (column.selected) 是
 * 绘制期覆盖矩形不参与缓存。
 */
private class TextLayoutCache(
    private val textByColumn: HashMap<TextColumn, ColumnLayout>,
    private val reviewByColumn: HashMap<ReviewColumn, ColumnLayout>,
) {
    fun textLayout(column: TextColumn): ColumnLayout? = textByColumn[column]

    fun reviewLayout(column: ReviewColumn): ColumnLayout? = reviewByColumn[column]

    companion object {
        fun build(
            textPage: TextPage,
            textMeasurer: TextMeasurer,
            style: ReaderDrawStyle,
            density: Density,
        ): TextLayoutCache {
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
            val textByColumn = HashMap<TextColumn, ColumnLayout>()
            val reviewByColumn = HashMap<ReviewColumn, ColumnLayout>()
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.lines[lineIndex]
                val isTitle = textLine.isTitle
                val lineStyle = if (isTitle) style.titleStyle else style.contentStyle
                val baselineOffset = if (isTitle) titleBaseline else contentBaseline
                val letterSpacingHalf = if (isTitle) titleSpacingHalf else contentSpacingHalf
                for (column in textLine.columns) {
                    when (column) {
                        is TextColumn -> {
                            // 列内文字颜色: 朗读/搜索结果高亮用 accent 色 (与绘制期同一判定)
                            val color = if (textLine.isReadAloud || column.isSearchResult) {
                                style.accentColor
                            } else {
                                style.textColor
                            }
                            val layout = textMeasurer.measure(
                                column.charData,
                                lineStyle.copy(color = color),
                            )
                            textByColumn[column] = ColumnLayout(
                                layout = layout,
                                baselineOffset = baselineOffset,
                                letterSpacingHalf = letterSpacingHalf,
                            )
                        }

                        is ReviewColumn -> {
                            val countTextStyle = lineStyle.copy(
                                color = style.reviewColor,
                                fontSize = style.reviewTextSize,
                            )
                            val countLayout = textMeasurer.measure(column.countText, countTextStyle)
                            reviewByColumn[column] = ColumnLayout(
                                layout = countLayout,
                                baselineOffset = countLayout.getLineBaseline(0),
                                letterSpacingHalf = 0f,
                            )
                        }

                        else -> Unit
                    }
                }
            }
            return TextLayoutCache(textByColumn, reviewByColumn)
        }
    }
}

/** 单列缓存项: 布局结果 + 绘制辅助量 (与 TextColumn 对象同生命周期, 页内引用稳定)。 */
private class ColumnLayout(
    val layout: TextLayoutResult,
    val baselineOffset: Float,
    val letterSpacingHalf: Float,
)

/**
 * 单页内容绘制主体：与 app 端 `TextPageRender.drawPage` → `TextLine.draw` 对应。
 */
private fun DrawScope.drawPageContent(
    textPage: TextPage,
    style: ReaderDrawStyle,
    layoutCache: TextLayoutCache,
) {
    // 基线折算/字间距补偿/逐列 TextLayoutResult 均已在 [TextLayoutCache] 构建时缓存,
    // 每帧只重放绘制 (drawText(layoutResult) 零 measure)。
    val underlineWidth = 1.dp.toPx()
    for (lineIndex in textPage.lines.indices) {
        val textLine = textPage.lines[lineIndex]
        val isTitle = textLine.isTitle
        val lineTop = textLine.lineTop
        val lineBase = textLine.lineBase
        val lineHeight = textLine.lineBottom - textLine.lineTop
        for (columnIndex in textLine.columns.indices) {
            val column = textLine.columns[columnIndex]
            when (column) {
                is TextColumn -> {
                    val cached = layoutCache.textLayout(column)
                    if (cached != null) {
                        drawTextColumn(
                            column = column,
                            layout = cached,
                            textColor = if (textLine.isReadAloud || column.isSearchResult) {
                                style.accentColor
                            } else {
                                style.textColor
                            },
                            selectedColor = style.selectedColor,
                            searchColor = style.searchColor,
                            lineTop = lineTop,
                            lineBase = lineBase,
                            lineHeight = lineHeight,
                        )
                    }
                }
                is ImageColumn -> drawImageColumn(
                    column = column,
                    lineTop = lineTop,
                    lineHeight = lineHeight,
                    placeholderColor = style.textColor,
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
        }
        // 墨水屏模式下的朗读/搜索下划线（与 app 端 TextLine.drawTextLine 的 isEInkMode 分支一致）
        if (style.isEInk && (textLine.isReadAloud || textLine.searchResultColumnCount > 0)) {
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
 * 绘制文字列：对应 app 端 `ColumnRender.drawTextColumn`。
 *
 * 选中高亮：[TextColumn.selected] 为 true 时绘制整列背景矩形。
 * 搜索结果高亮：[TextColumn.isSearchResult] 为 true 时绘制半透明高亮矩形。
 * 文字位置：x = column.start + letterSpacingHalf（API35+ 补偿口径），
 * y = lineBase - baselineOffset（drawText topLeft 是文本框左上角，lineBase 折算回框顶）。
 */
private fun DrawScope.drawTextColumn(
    column: TextColumn,
    layout: ColumnLayout,
    textColor: Color,
    selectedColor: Color,
    searchColor: Color,
    lineTop: Float,
    lineBase: Float,
    lineHeight: Float,
) {
    val x = column.start + layout.letterSpacingHalf
    val y = lineBase - layout.baselineOffset
    // 越界保护 (对照原版 Android Canvas.drawText: 越界不绘制也不崩):
    // drawText(layoutResult) 内部用 `scopeSize - topLeft` 算文本约束, topLeft 超出画布时
    // 约束为负直接抛 IllegalArgumentException (maxWidth must be >= minWidth)。
    // 窗口 resize / 翻页动画期间旧排版数据可能暂时超出画布, 完全越界时跳过, 部分可见时正常画。
    if (x >= size.width || y >= size.height) return
    // 缓存布局零 measure 重放 (文字颜色已按高亮状态固化进布局, 见 TextLayoutCache.build)
    drawText(layout.layout, topLeft = Offset(x, y))
    if (column.selected) {
        drawRect(
            color = selectedColor,
            topLeft = Offset(column.start, lineTop),
            size = Size(column.end - column.start, lineHeight),
        )
    }
    if (column.isSearchResult) {
        drawRect(
            color = searchColor,
            topLeft = Offset(column.start, lineTop),
            size = Size(column.end - column.start, lineHeight),
        )
    }
}

/**
 * 绘制图片列：对应 app 端 `ColumnRender.drawImageColumn`。
 *
 * 位图取自 [ReaderImageCache]（排版时 `ImageResolver.getImageSize` 已解码入缓存），
 * 被 LRU 淘汰时就地发起异步补加载，加载完成自增 version 触发本页重绘。
 * app 端 actual 侧填过 [ImageColumn.renderCache] 时优先用它。
 *
 * 加载中画灰色占位块，取图失败画带叉的错误占位（对照 app 端 ImageProvider 的 errorBitmap）。
 *
 * 缩放比例：保持原图宽高比，按 containerW/containerH 中较小者缩放，
 * 居中放置（与 app 端 `ImageDrawCache.updateDrawCache` 算法一致）。
 */
private fun DrawScope.drawImageColumn(
    column: ImageColumn,
    lineTop: Float,
    lineHeight: Float,
    placeholderColor: Color,
) {
    val containerW = column.end - column.start
    val containerH = lineHeight
    val bitmap = column.renderCache as? ImageBitmap ?: ReaderImageCache.peek(column.src)
    if (bitmap == null) {
        val failed = ReaderImageCache.isFailed(column.src)
        if (!failed) ReaderImageCache.requestAsync(column.src)
        drawImagePlaceholder(column.start, lineTop, containerW, containerH, placeholderColor, failed)
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

/** 图片占位：加载中为浅色实块，失败时再叠一个叉。 */
private fun DrawScope.drawImagePlaceholder(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    failed: Boolean,
) {
    if (width <= 0f || height <= 0f) return
    drawRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(left, top),
        size = Size(width, height),
    )
    if (!failed) return
    val stroke = 1.dp.toPx()
    drawRect(
        color = color.copy(alpha = 0.3f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = stroke),
    )
    drawLine(
        color = color.copy(alpha = 0.3f),
        start = Offset(left, top),
        end = Offset(left + width, top + height),
        strokeWidth = stroke,
    )
    drawLine(
        color = color.copy(alpha = 0.3f),
        start = Offset(left + width, top),
        end = Offset(left, top + height),
        strokeWidth = stroke,
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
    if (column.count == 0) return
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
    // 用缓存 layout 的 baseline 折算到圆心上方使文字视觉居中 (TextLayoutCache.build 已 measure)
    val layout = layout ?: return
    val baseline = layout.baselineOffset
    val countTopLeft = Offset(
        centerX - radius * 0.5f,
        centerY - baseline * 0.5f,
    )
    // 越界保护：同 [drawTextColumn]，约束为负会抛异常（resize/翻页动画期间旧排版可能越界）
    if (countTopLeft.x >= size.width || countTopLeft.y >= size.height) return
    // 缓存布局零 measure 重放
    drawText(layout.layout, topLeft = countTopLeft)
}

// ===== @Preview 合并自 androidMain 的 book/read/page/ReadPagePreviews.kt (PageContentCanvas) =====

// ===== PageContentCanvas =====


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
fun PageContentCanvasEmptyPreview() = LegadoReadConfigPreview {
    // 用 emptyTextPage (无文字行), 仅渲染空 Canvas, 验证测量/绘制链路不崩
    PageContentCanvas(
        textPage = TextPage.emptyTextPage,
        modifier = Modifier.fillMaxSize(),
    )
}
