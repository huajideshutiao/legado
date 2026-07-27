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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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
 * 3. 朗读/搜索下划线：drawLine（与 app 端 `TextLine.drawTextLine` 的 E-Ink 分支对齐）
 *
 * 文字位置口径：与 app 端 `ColumnRender.drawTextColumn` 一致，x = column.start + letterSpacingHalf，
 * y = line.lineBase - baselineOffset（drawText 的 topLeft 是文本框左上角，需把行基线折算回框顶）。
 * letterSpacingHalf 仅 API35+ 补偿；KMP 版统一补偿以保持视觉一致。
 */
@Composable
fun PageContentCanvas(
    textPage: TextPage,
    modifier: Modifier = Modifier,
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
) {
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val density: Density = LocalDensity.current
    val layoutDirection: LayoutDirection = LocalLayoutDirection.current

    // 颜色：默认值与 app 端 ThemeStore.accentColor(0xFF165DFF) / ReadBookConfig.textColor(0xFF3E3D3B) 对齐
    // 后续 ReadConfigProviders actual 注入后改为读取配置（TODO: 接入 LocalReadConfigProviders）
    val textColor: Color = Color(0xFF3E3D3B)
    val accentColor: Color = Color(0xFF165DFF)
    val selectedColor: Color = Color(0x80165DFF)
    val searchColor: Color = Color(0x66165DFF)
    val underlineColor: Color = textColor

    // 默认文字样式（与 app 端 ChapterProvider.contentPaint 默认值对齐：textSize=20sp, letterSpacing=0.1）
    val textStyle: TextStyle = TextStyle(
        color = textColor,
        fontSize = 20.sp,
        letterSpacing = 0.1.sp,
    )

    Canvas(modifier = modifier) {
        drawPageContent(
            textPage = textPage,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            textColor = textColor,
            accentColor = accentColor,
            selectedColor = selectedColor,
            searchColor = searchColor,
            underlineColor = underlineColor,
            density = density,
            layoutDirection = layoutDirection,
        )
    }
}

/**
 * 单页内容绘制主体：与 app 端 `TextPageRender.drawPage` → `TextLine.draw` 对应。
 */
private fun DrawScope.drawPageContent(
    textPage: TextPage,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    textColor: Color,
    accentColor: Color,
    selectedColor: Color,
    searchColor: Color,
    underlineColor: Color,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    // DrawScope 实现了 Density，letterSpacing.toPx() 直接用本 scope 即可
    val letterSpacingPx = textStyle.letterSpacing.toPx()
    val letterSpacingHalf = letterSpacingPx * 0.5f
    // baseline 折算：drawText 的 topLeft 是文本框左上角，行基线 lineBase 需减去首行 baseline 偏移得到框顶 y。
    // 与 app 端 Android Canvas.drawText(x, lineBase) 直接用 baseline 不同。
    val baselineOffset = textMeasurer.measure("水", textStyle).getLineBaseline(0)
    for (lineIndex in textPage.lines.indices) {
        val textLine = textPage.lines[lineIndex]
        val lineTop = textLine.lineTop
        val lineBase = textLine.lineBase
        val lineHeight = textLine.lineBottom - textLine.lineTop
        for (columnIndex in textLine.columns.indices) {
            val column = textLine.columns[columnIndex]
            when (column) {
                is TextColumn -> drawTextColumn(
                    column = column,
                    textMeasurer = textMeasurer,
                    textStyle = textStyle,
                    textColor = if (textLine.isReadAloud || column.isSearchResult) accentColor else textColor,
                    selectedColor = selectedColor,
                    searchColor = searchColor,
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
                )
                is ReviewColumn -> drawReviewColumn(
                    column = column,
                    textMeasurer = textMeasurer,
                    textStyle = textStyle,
                    accentColor = accentColor,
                    lineTop = lineTop,
                    lineHeight = lineHeight,
                    baselineOffset = baselineOffset,
                )
                else -> Unit // ButtonColumn 等暂无绘制
            }
        }
        // 朗读/搜索结果下划线（与 app 端 E-Ink 模式对齐）
        if (textLine.isReadAloud || textLine.searchResultColumnCount > 0) {
            drawLine(
                color = underlineColor,
                start = Offset(textLine.lineStart + textLine.indentWidth, lineTop + lineHeight - 1f),
                end = Offset(textLine.lineEnd, lineTop + lineHeight - 1f),
                strokeWidth = 1f,
            )
        }
    }
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
 * ImageBitmap 从 [ImageColumn.renderCache] 取（actual 平台填充）；
 * 若 renderCache 为 null 则跳过绘制（与 app 端 bitmap=null 兜底一致）。
 *
 * 缩放比例：保持原图宽高比，按 containerW/containerH 中较小者缩放，
 * 居中放置（与 app 端 `ImageDrawCache.updateDrawCache` 算法一致）。
 */
private fun DrawScope.drawImageColumn(
    column: ImageColumn,
    lineTop: Float,
    lineHeight: Float,
) {
    val bitmap = column.renderCache as? ImageBitmap ?: return
    val containerW = column.end - column.start
    val containerH = lineHeight
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

/**
 * 绘制段评气泡：对应 app 端 `ColumnRender.drawReviewColumn`。
 *
 * 简化版：用 drawCircle 画外圈（与 app 端 ReviewIcon 椭圆形状近似），
 * 居中绘制 count 文字。app 端精确的胶囊气泡 + 数字字号缓存留待后续下沉。
 */
private fun DrawScope.drawReviewColumn(
    column: ReviewColumn,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    accentColor: Color,
    lineTop: Float,
    lineHeight: Float,
    baselineOffset: Float,
) {
    if (column.count == 0) return
    val containerW = column.end - column.start
    val radius = minOf(containerW, lineHeight) * 0.45f
    val centerX = column.start + containerW / 2f
    val centerY = lineTop + lineHeight / 2f
    drawCircle(
        color = accentColor.copy(alpha = 0.2f),
        radius = radius,
        center = Offset(centerX, centerY),
    )
    drawCircle(
        color = accentColor,
        radius = radius,
        center = Offset(centerX, centerY),
        style = Stroke(width = 1.5f),
    )
    // 数字居中绘制：drawText topLeft 是文本框左上角，
    // 用 baselineOffset 折算到圆心上方使文字视觉居中。
    val countTextStyle = textStyle.copy(color = accentColor, fontSize = textStyle.fontSize * 0.6f)
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
