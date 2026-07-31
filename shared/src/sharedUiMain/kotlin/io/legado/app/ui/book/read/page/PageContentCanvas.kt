package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

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
 */
@Composable
fun PageContentCanvas(
    textPage: TextPage,
    modifier: Modifier = Modifier,
    style: ReaderDrawStyle = rememberReaderDrawStyle(),
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
) {
    val textMeasurer: TextMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        // 读位图就绪计数建立快照订阅：图片异步加载完成后本页自动重绘（值本身不参与绘制）
        if (ReaderImageCache.version < 0) return@Canvas
        drawPageContent(
            textPage = textPage,
            textMeasurer = textMeasurer,
            style = style,
        )
    }
}

/**
 * 单页内容绘制主体：与 app 端 `TextPageRender.drawPage` → `TextLine.draw` 对应。
 */
private fun DrawScope.drawPageContent(
    textPage: TextPage,
    textMeasurer: TextMeasurer,
    style: ReaderDrawStyle,
) {
    // baseline 折算：drawText 的 topLeft 是文本框左上角，行基线 lineBase 需减去首行 baseline 偏移得到框顶 y。
    // 与 app 端 Android Canvas.drawText(x, lineBase) 直接用 baseline 不同。标题/正文两套字号各测一次。
    val contentBaseline = textMeasurer.measure("水", style.contentStyle).getLineBaseline(0)
    val titleBaseline = textMeasurer.measure("水", style.titleStyle).getLineBaseline(0)
    // letterSpacing 是 em（字号倍数），折算像素补偿量：fontSizePx * em * 0.5
    val contentSpacingHalf = style.contentStyle.fontSize.toPx() * style.letterSpacingEm * 0.5f
    val titleSpacingHalf = style.titleStyle.fontSize.toPx() * style.letterSpacingEm * 0.5f
    val underlineWidth = 1.dp.toPx()
    for (lineIndex in textPage.lines.indices) {
        val textLine = textPage.lines[lineIndex]
        val isTitle = textLine.isTitle
        val lineStyle = if (isTitle) style.titleStyle else style.contentStyle
        val baselineOffset = if (isTitle) titleBaseline else contentBaseline
        val letterSpacingHalf = if (isTitle) titleSpacingHalf else contentSpacingHalf
        val lineTop = textLine.lineTop
        val lineBase = textLine.lineBase
        val lineHeight = textLine.lineBottom - textLine.lineTop
        for (columnIndex in textLine.columns.indices) {
            val column = textLine.columns[columnIndex]
            when (column) {
                is TextColumn -> drawTextColumn(
                    column = column,
                    textMeasurer = textMeasurer,
                    textStyle = lineStyle,
                    textColor = if (textLine.isReadAloud || column.isSearchResult) {
                        style.accentColor
                    } else {
                        style.textColor
                    },
                    selectedColor = style.selectedColor,
                    searchColor = style.searchColor,
                    letterSpacingHalf = letterSpacingHalf,
                    lineTop = lineTop,
                    lineBase = lineBase,
                    baselineOffset = baselineOffset,
                    lineHeight = lineHeight,
                )
                is ImageColumn -> drawImageColumn(
                    column = column,
                    lineTop = lineTop,
                    lineHeight = lineHeight,
                    placeholderColor = style.textColor,
                )
                is ReviewColumn -> drawReviewColumn(
                    column = column,
                    textMeasurer = textMeasurer,
                    textStyle = lineStyle,
                    reviewColor = style.reviewColor,
                    reviewTextSize = style.reviewTextSize,
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
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    textColor: Color,
    selectedColor: Color,
    searchColor: Color,
    letterSpacingHalf: Float,
    lineTop: Float,
    lineBase: Float,
    baselineOffset: Float,
    lineHeight: Float,
) {
    val x = column.start + letterSpacingHalf
    val y = lineBase - baselineOffset
    drawText(
        textMeasurer = textMeasurer,
        text = column.charData,
        style = textStyle.copy(color = textColor),
        topLeft = Offset(x, y),
    )
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
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    reviewColor: Color,
    reviewTextSize: TextUnit,
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
    // 数字居中绘制：drawText topLeft 是文本框左上角，
    // 用 baselineOffset 折算到圆心上方使文字视觉居中。
    val countTextStyle = textStyle.copy(color = reviewColor, fontSize = reviewTextSize)
    val countBaseline = textMeasurer.measure(column.countText, countTextStyle).getLineBaseline(0)
    drawText(
        textMeasurer = textMeasurer,
        text = column.countText,
        style = countTextStyle,
        topLeft = Offset(
            centerX - radius * 0.5f,
            centerY - countBaseline * 0.5f,
        ),
    )
}
