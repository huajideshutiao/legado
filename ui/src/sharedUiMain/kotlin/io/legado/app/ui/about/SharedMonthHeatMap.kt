package io.legado.app.ui.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils

/**
 * 纯 Compose 月度阅读热力图 (替代 app 端 AndroidView `MonthHeatMapView`)。
 *
 * 7 列 (周一到周日), 按月渲染每天的阅读时长, 颜色深浅表示时长长短。
 * 底部附 6 级色阶图例 + 选中日信息行。点击切选, 长按触发删除回调。
 *
 * 布局/颜色/交互与 app 端 `MonthHeatMapView` 完全对齐, 仅将 Canvas/View 桥接改为
 * Compose `Canvas` + `drawText`, 让 shared 路由无需平台 AndroidView 注入即可渲染。
 *
 * 分三段独立测量 (表头固定高 / 网格按 7 列宽高比 / 底部固定高): 网格高度由
 * `aspectRatio` 从宽度推出, 组件因此支持 intrinsic 测量 —— `BoxWithConstraints`
 * 内部是 SubcomposeLayout, 不实现 intrinsic, 会让外层 `height(IntrinsicSize.Min)`
 * 直接抛 IllegalStateException (宽屏双列并排等高即触发)。
 */
@Composable
fun SharedMonthHeatMap(
    year: Int,
    month: Int, // 1..12
    data: Map<Int, Long>,
    selectedDay: Int,
    todayYear: Int,
    todayMonth: Int,
    todayDay: Int,
    onDayClick: (day: Int, readTime: Long, selected: Boolean) -> Unit,
    onDayLongClick: (day: Int, readTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (month == 0) return
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val cellGapPx = with(density) { 3.dp.toPx() }
    val cellRadiusPx = with(density) { 4.dp.toPx() }
    // 三段固定高 (dp 单一来源: Canvas 高度与绘制坐标同源)
    val headerHeight = 22.dp
    val selectedInfoHeight = 28.dp
    val legendHeight = 24.dp
    val footerHeight = selectedInfoHeight + legendHeight
    val headerHeightPx = with(density) { headerHeight.toPx() }
    val selectedInfoHeightPx = with(density) { selectedInfoHeight.toPx() }
    val legendHeightPx = with(density) { legendHeight.toPx() }
    val legendCellSizePx = with(density) { 12.dp.toPx() }
    val legendCellGapPx = with(density) { 3.dp.toPx() }
    val legendLabelGapPx = with(density) { 6.dp.toPx() }

    val weekdayLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
    val maxReadSecs = 12L * 60L * 60L

    val firstColumnIndex = remember(year, month) { firstColumnIndex(year, month) }
    val daysInMonth = remember(year, month) { daysInMonth(year, month) }
    val rowCount = remember(firstColumnIndex, daysInMonth) {
        (firstColumnIndex + daysInMonth + 6) / 7
    }

    Column(modifier) {
        val textColor = colors.primaryText
        val secondaryColor = colors.secondaryText
        val accent = colors.accent
        val isDark = colors.isDark

        val headerStyle = TextStyle(fontSize = 11.sp, color = secondaryColor)
        val dayStyle = TextStyle(fontSize = 11.sp, color = textColor)
        val selectedInfoStyle = TextStyle(fontSize = 12.sp, color = textColor)
        val legendStyle = TextStyle(fontSize = 10.sp, color = secondaryColor)

        Canvas(Modifier.fillMaxWidth().height(headerHeight)) {
            drawHeatmapHeader(
                weekdayLabels = weekdayLabels,
                headerHeightPx = headerHeightPx,
                textMeasurer = textMeasurer,
                headerStyle = headerStyle,
            )
        }

        // 网格: 高度由 7 列宽高比推出 (cellSize = 宽/7, 高 = cellSize * rowCount)
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(7f / rowCount)
                .pointerInput(year, month) {
                    detectTapGestures(
                        onTap = { offset ->
                            val cellSize = size.width / 7f
                            if (cellSize <= 0f) return@detectTapGestures
                            val x = offset.x
                            val y = offset.y
                            if (x < 0 || y < 0) return@detectTapGestures
                            val col = (x / cellSize).toInt()
                            val row = (y / cellSize).toInt()
                            if (col !in 0..6) return@detectTapGestures
                            val d = row * 7 + col - firstColumnIndex + 1
                            if (d in 1..daysInMonth) {
                                val newSelected = if (selectedDay == d) 0 else d
                                onDayClick(d, data[d] ?: 0L, newSelected != 0)
                            }
                        },
                        onLongPress = { offset ->
                            val cellSize = size.width / 7f
                            if (cellSize <= 0f) return@detectTapGestures
                            val x = offset.x
                            val y = offset.y
                            if (x < 0 || y < 0) return@detectTapGestures
                            val col = (x / cellSize).toInt()
                            val row = (y / cellSize).toInt()
                            if (col !in 0..6) return@detectTapGestures
                            val d = row * 7 + col - firstColumnIndex + 1
                            if (d in 1..daysInMonth) {
                                onDayLongClick(d, data[d] ?: 0L)
                            }
                        },
                    )
                },
        ) {
            drawHeatmapGrid(
                cellGapPx = cellGapPx,
                cellRadiusPx = cellRadiusPx,
                year = year,
                month = month,
                data = data,
                selectedDay = selectedDay,
                todayYear = todayYear,
                todayMonth = todayMonth,
                todayDay = todayDay,
                maxReadSecs = maxReadSecs,
                firstColumnIndex = firstColumnIndex,
                daysInMonth = daysInMonth,
                accent = accent,
                isDark = isDark,
                textMeasurer = textMeasurer,
                dayStyle = dayStyle,
            )
        }

        Canvas(Modifier.fillMaxWidth().height(footerHeight)) {
            drawHeatmapFooter(
                selectedInfoHeightPx = selectedInfoHeightPx,
                legendHeightPx = legendHeightPx,
                legendCellSizePx = legendCellSizePx,
                legendCellGapPx = legendCellGapPx,
                legendLabelGapPx = legendLabelGapPx,
                cellRadiusPx = cellRadiusPx,
                month = month,
                data = data,
                selectedDay = selectedDay,
                accent = accent,
                isDark = isDark,
                textMeasurer = textMeasurer,
                selectedInfoStyle = selectedInfoStyle,
                legendStyle = legendStyle,
            )
        }
    }
}

// ---- 绘制 (Canvas DrawScope 扩展) ----
// 三段各自独立测量: 表头/网格/底部。网格 Canvas 的 size.width 即卡片可用宽,
// cellSize = size.width / 7 (与原 BoxWithConstraints 的 maxWidth 同值)。

/** 星期表头 (固定 22dp 高) */
private fun DrawScope.drawHeatmapHeader(
    weekdayLabels: Array<String>,
    headerHeightPx: Float,
    textMeasurer: TextMeasurer,
    headerStyle: TextStyle,
) {
    for (i in 0..6) {
        val cx = size.width / 7f * (i + 0.5f)
        val text = textMeasurer.measure(AnnotatedString(weekdayLabels[i]), headerStyle)
        drawText(
            text,
            topLeft = Offset(
                cx - text.size.width / 2f,
                headerHeightPx / 2f - text.size.height / 2f,
            ),
        )
    }
}

/** 热力图单元格网格 (高度 = 宽/7 * 行数, 由 aspectRatio 决定) */
private fun DrawScope.drawHeatmapGrid(
    cellGapPx: Float,
    cellRadiusPx: Float,
    year: Int,
    month: Int,
    data: Map<Int, Long>,
    selectedDay: Int,
    todayYear: Int,
    todayMonth: Int,
    todayDay: Int,
    maxReadSecs: Long,
    firstColumnIndex: Int,
    daysInMonth: Int,
    accent: Color,
    isDark: Boolean,
    textMeasurer: TextMeasurer,
    dayStyle: TextStyle,
) {
    val cellSize = size.width / 7f
    if (cellSize <= 0f) return

    for (d in 1..daysInMonth) {
        val idx = firstColumnIndex + d - 1
        val col = idx % 7
        val row = idx / 7
        val left = cellSize * col + cellGapPx
        val top = cellSize * row + cellGapPx
        val right = left + cellSize - cellGapPx * 2
        val bottom = top + cellSize - cellGapPx * 2

        val value = data[d] ?: 0L
        val level = levelFor(value, maxReadSecs)
        val bgColor = colorForLevel(level, accent, isDark)
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellRadiusPx, cellRadiusPx),
        )

        // 日期数字
        val dayText = textMeasurer.measure(AnnotatedString(d.toString()), dayStyle)
        drawText(
            dayText,
            topLeft = Offset(
                (left + right) / 2f - dayText.size.width / 2f,
                (top + bottom) / 2f - dayText.size.height / 2f,
            ),
        )

        // 今天加下划线
        if (year == todayYear && month == todayMonth && d == todayDay) {
            val underlineY =
                (top + bottom) / 2f + dayText.size.height / 2f + with(this) { 4.dp.toPx() }
            val underlineHalf = (right - left) * 0.3f
            val cx = (left + right) / 2f
            drawLine(
                color = Color(0xC8767676),
                start = Offset(cx - underlineHalf, underlineY),
                end = Offset(cx + underlineHalf, underlineY),
                strokeWidth = with(this) { 2.dp.toPx() },
            )
        }

        // 选中描边
        if (d == selectedDay) {
            drawRoundRect(
                color = Color(0xC8767676),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    cellRadiusPx,
                    cellRadiusPx
                ),
                style = Stroke(width = with(this) { 2.5.dp.toPx() }),
            )
        }
    }
}

/** 选中日信息行 + 色阶图例 (固定 52dp 高: 信息行 28dp + 图例 24dp) */
private fun DrawScope.drawHeatmapFooter(
    selectedInfoHeightPx: Float,
    legendHeightPx: Float,
    legendCellSizePx: Float,
    legendCellGapPx: Float,
    legendLabelGapPx: Float,
    cellRadiusPx: Float,
    month: Int,
    data: Map<Int, Long>,
    selectedDay: Int,
    accent: Color,
    isDark: Boolean,
    textMeasurer: TextMeasurer,
    selectedInfoStyle: TextStyle,
    legendStyle: TextStyle,
) {
    // 选中日信息行
    if (selectedDay != 0) {
        val readTime = data[selectedDay] ?: 0L
        val infoText = "${month}月${selectedDay}日 · ${formatDuration(readTime)}"
        val text = textMeasurer.measure(AnnotatedString(infoText), selectedInfoStyle)
        drawText(
            text,
            topLeft = Offset(
                (size.width - text.size.width) / 2f,
                (selectedInfoHeightPx - text.size.height) / 2f,
            ),
        )
    }

    // 色阶图例
    drawLegend(
        legendTop = selectedInfoHeightPx,
        legendHeightPx = legendHeightPx,
        legendCellSizePx = legendCellSizePx,
        legendCellGapPx = legendCellGapPx,
        legendLabelGapPx = legendLabelGapPx,
        legendRadiusPx = cellRadiusPx / 2f,
        accent = accent,
        isDark = isDark,
        textMeasurer = textMeasurer,
        legendStyle = legendStyle,
    )
}

private fun DrawScope.drawLegend(
    legendTop: Float,
    legendHeightPx: Float,
    legendCellSizePx: Float,
    legendCellGapPx: Float,
    legendLabelGapPx: Float,
    legendRadiusPx: Float,
    accent: Color,
    isDark: Boolean,
    textMeasurer: TextMeasurer,
    legendStyle: TextStyle,
) {
    val cellCount = 6
    val cellsTotal = legendCellSizePx * cellCount + legendCellGapPx * (cellCount - 1)
    val lessText = "少"
    val moreText = "多"
    val lessLayout = textMeasurer.measure(AnnotatedString(lessText), legendStyle)
    val moreLayout = textMeasurer.measure(AnnotatedString(moreText), legendStyle)
    val lessW = lessLayout.size.width.toFloat()
    val moreW = moreLayout.size.width.toFloat()
    val totalW = lessW + legendLabelGapPx + cellsTotal + legendLabelGapPx + moreW
    val startX = (size.width - totalW) / 2f
    val centerY = legendTop + legendHeightPx / 2f
    val textBaselineY = centerY - lessLayout.size.height / 2f

    drawText(lessLayout, topLeft = Offset(startX, textBaselineY))

    var cellX = startX + lessW + legendLabelGapPx
    val cellTop = centerY - legendCellSizePx / 2f
    for (i in 0 until cellCount) {
        drawRoundRect(
            color = colorForLevel(i, accent, isDark),
            topLeft = Offset(cellX, cellTop),
            size = Size(legendCellSizePx, legendCellSizePx),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                legendRadiusPx,
                legendRadiusPx
            ),
        )
        cellX += legendCellSizePx + legendCellGapPx
    }
    cellX = cellX - legendCellGapPx + legendLabelGapPx
    drawText(moreLayout, topLeft = Offset(cellX, textBaselineY))
}

// ---- 颜色 (对照 MonthHeatMapView.getColorForLevel) ----

private fun colorForLevel(level: Int, accent: Color, isDark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accent.toArgb(), hsl)
    // 对照 app 端: isBackgroundDark = !isDarkTheme; 深色背景用更亮色阶
    val l = if (isDark) {
        0.3f + level * 0.08f
    } else {
        0.92f - level * 0.1f
    }
    val alpha = if (level == 0) 35f / 255f else (80f + level * 35f) / 255f
    hsl[2] = l.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = alpha)
}

private fun levelFor(value: Long, maxReadSecs: Long): Int {
    if (value <= 0L) return 0
    val ratio = (value.toFloat() / maxReadSecs).coerceAtMost(1f)
    return when {
        ratio <= 0.2f -> 1
        ratio <= 0.4f -> 2
        ratio <= 0.6f -> 3
        ratio <= 0.8f -> 4
        else -> 5
    }
}

private fun formatDuration(secs: Long): String {
    if (secs <= 0L) return "0 分钟"
    val hours = secs / 3600L
    val minutes = (secs % 3600L) / 60L
    return buildString {
        if (hours > 0) append("$hours 小时")
        if (hours > 0 && minutes > 0) append(" ")
        if (minutes > 0) append("$minutes 分钟")
    }
}

// ---- 日期计算 (Howard Hinnant 算法, 与 ReadRecordScreenModel 私有实现等价) ----

private fun firstColumnIndex(year: Int, month: Int): Int {
    val days = daysFromCivil(year, month, 1)
    // 1970-01-01 (days=0) 是周四; dayOfWeek: 1=Sunday..7=Saturday
    val dayOfWeek = floorMod(days + 4L, 7L).toInt() + 1
    // 转为周一=0 偏移
    return (dayOfWeek + 5) % 7
}

private fun daysInMonth(year: Int, month: Int): Int {
    val start = daysFromCivil(year, month, 1)
    val (nextY, nextM) = if (month == 12) (year + 1) to 1 else year to (month + 1)
    val nextStart = daysFromCivil(nextY, nextM, 1)
    return (nextStart - start).toInt()
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val m = if (month > 2) month else month + 9
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m - 3) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
