package io.legado.app.ui.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlin.math.abs

/**
 * 纯 Compose 月度阅读热力图 (替代 app 端 AndroidView `MonthHeatMapView`)。
 *
 * 7 列 (周一到周日), 按月渲染每天的阅读时长, 颜色深浅表示时长长短。
 * 底部附 6 级色阶图例 + 选中日信息行。点击切选, 长按触发删除回调。
 *
 * 布局/颜色/交互与 app 端 `MonthHeatMapView` 完全对齐, 仅将 Canvas/View 桥接改为
 * Compose `Canvas` + `drawText`, 让 shared 路由无需平台 AndroidView 注入即可渲染。
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
    val headerHeightPx = with(density) { 22.dp.toPx() }
    val selectedInfoHeightPx = with(density) { 28.dp.toPx() }
    val legendHeightPx = with(density) { 24.dp.toPx() }
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

    BoxWithConstraints(modifier) {
        val availWidthPx = with(density) { maxWidth.toPx() }
        val cellSize = availWidthPx / 7f
        val totalHeightPx = headerHeightPx + cellSize * rowCount +
            selectedInfoHeightPx + legendHeightPx

        val textColor = colors.primaryText
        val secondaryColor = colors.secondaryText
        val accent = colors.accent
        val isDark = colors.isDark

        val headerStyle = TextStyle(fontSize = 11.sp, color = secondaryColor)
        val dayStyle = TextStyle(fontSize = 11.sp, color = textColor)
        val selectedInfoStyle = TextStyle(fontSize = 12.sp, color = textColor)
        val legendStyle = TextStyle(fontSize = 10.sp, color = secondaryColor)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { totalHeightPx.toDp() })
                .pointerInput(year, month) {
                    detectTapGestures(
                        onTap = { offset ->
                            val cellSize = availWidthPx / 7f
                            if (cellSize <= 0f) return@detectTapGestures
                            val x = offset.x
                            val y = offset.y - headerHeightPx
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
                            val cellSize = availWidthPx / 7f
                            if (cellSize <= 0f) return@detectTapGestures
                            val x = offset.x
                            val y = offset.y - headerHeightPx
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
            drawHeatmap(
                cellSize = cellSize,
                cellGapPx = cellGapPx,
                cellRadiusPx = cellRadiusPx,
                headerHeightPx = headerHeightPx,
                selectedInfoHeightPx = selectedInfoHeightPx,
                legendHeightPx = legendHeightPx,
                legendCellSizePx = legendCellSizePx,
                legendCellGapPx = legendCellGapPx,
                legendLabelGapPx = legendLabelGapPx,
                weekdayLabels = weekdayLabels,
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
                rowCount = rowCount,
                accent = accent,
                isDark = isDark,
                textMeasurer = textMeasurer,
                headerStyle = headerStyle,
                dayStyle = dayStyle,
                selectedInfoStyle = selectedInfoStyle,
                legendStyle = legendStyle,
            )
        }
    }
}

// ---- 绘制 (Canvas DrawScope 扩展) ----

private fun DrawScope.drawHeatmap(
    cellSize: Float,
    cellGapPx: Float,
    cellRadiusPx: Float,
    headerHeightPx: Float,
    selectedInfoHeightPx: Float,
    legendHeightPx: Float,
    legendCellSizePx: Float,
    legendCellGapPx: Float,
    legendLabelGapPx: Float,
    weekdayLabels: Array<String>,
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
    rowCount: Int,
    accent: Color,
    isDark: Boolean,
    textMeasurer: TextMeasurer,
    headerStyle: TextStyle,
    dayStyle: TextStyle,
    selectedInfoStyle: TextStyle,
    legendStyle: TextStyle,
) {
    if (cellSize <= 0f) return

    // 星期表头
    for (i in 0..6) {
        val cx = cellSize * (i + 0.5f)
        val text = textMeasurer.measure(AnnotatedString(weekdayLabels[i]), headerStyle)
        drawText(
            text,
            topLeft = Offset(
                cx - text.size.width / 2f,
                headerHeightPx / 2f - text.size.height / 2f,
            ),
        )
    }

    val gridTop = headerHeightPx

    // 热力图单元格
    for (d in 1..daysInMonth) {
        val idx = firstColumnIndex + d - 1
        val col = idx % 7
        val row = idx / 7
        val left = cellSize * col + cellGapPx
        val top = gridTop + cellSize * row + cellGapPx
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

    // 选中日信息行
    val infoTop = gridTop + cellSize * rowCount
    if (selectedDay != 0) {
        val readTime = data[selectedDay] ?: 0L
        val infoText = "${month}月${selectedDay}日 · ${formatDuration(readTime)}"
        val text = textMeasurer.measure(AnnotatedString(infoText), selectedInfoStyle)
        drawText(
            text,
            topLeft = Offset(
                (size.width - text.size.width) / 2f,
                infoTop + (selectedInfoHeightPx - text.size.height) / 2f,
            ),
        )
    }

    // 色阶图例
    drawLegend(
        legendTop = infoTop + selectedInfoHeightPx,
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
    val hsl = rgbToHsl(accent.red, accent.green, accent.blue)
    // 对照 app 端: isBackgroundDark = !isDarkTheme; 深色背景用更亮色阶
    val l = if (isDark) {
        0.3f + level * 0.08f
    } else {
        0.92f - level * 0.1f
    }
    val alpha = if (level == 0) 35f / 255f else (80f + level * 35f) / 255f
    val rgb = hslToRgb(hsl[0], hsl[1], l.coerceIn(0f, 1f))
    return Color(rgb[0], rgb[1], rgb[2], alpha)
}

private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val h: Float
    val s: Float
    if (max == min) {
        h = 0f
        s = 0f
    } else {
        val d = max - min
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        when (max) {
            r -> h = ((g - b) / d + if (g < b) 6f else 0f) * 60f
            g -> h = ((b - r) / d + 2f) * 60f
            else -> h = ((r - g) / d + 4f) * 60f
        }
    }
    return floatArrayOf(h, s, l)
}

private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        h < 60f -> {
            r = c; g = x; b = 0f
        }

        h < 120f -> {
            r = x; g = c; b = 0f
        }

        h < 180f -> {
            r = 0f; g = c; b = x
        }

        h < 240f -> {
            r = 0f; g = x; b = c
        }

        h < 300f -> {
            r = x; g = 0f; b = c
        }

        else -> {
            r = c; g = 0f; b = x
        }
    }
    return floatArrayOf(r + m, g + m, b + m)
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
