package io.legado.app.ui.about

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import java.util.Calendar

/**
 * 月度阅读时长热力图。
 * 横向 7 列（周一到周日），按月渲染每天的阅读时长，颜色深浅表示时长长短。
 * 底部附带 5 级色阶图例。
 */
class MonthHeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val weekdayLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")

    private val cellGap = 3f.dpToPx()
    private val cellRadius = 4f.dpToPx()
    private val headerHeight = 22f.dpToPx()
    private val selectedInfoHeight = 28f.dpToPx()
    private val legendHeight = 24f.dpToPx()
    private val legendCellSize = 12f.dpToPx()
    private val legendCellGap = 3f.dpToPx()
    private val legendLabelGap = 6f.dpToPx()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dpToPx()
    }
    private val todayLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dpToPx()
        strokeCap = Paint.Cap.ROUND
    }
    private val dayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f.spToPx()
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f.spToPx()
    }
    private val selectedInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f.spToPx()
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.spToPx()
    }

    private val tmpRect = RectF()

    private var year: Int = 0
    private var month: Int = 0 // 1..12
    private var data: Map<Int, Long> = emptyMap()

    private var selectedDay: Int = 0

    private val todayYear: Int
    private val todayMonth: Int
    private val todayDay: Int

    /** 6 档色阶对应的 alpha 值 (0..255)。索引 0 = 完全没读，5 = 满 12 小时及以上 */
    private val levelAlphas = intArrayOf(24, 64, 112, 160, 208, 255)

    /** 单日满色阶对应的毫秒上限：12 小时 */
    private val maxReadMillis = 12L * 60L * 60L * 1000L

    var onDayClick: ((day: Int, readTime: Long, selected: Boolean) -> Unit)? = null

    init {
        val cal = Calendar.getInstance()
        todayYear = cal.get(Calendar.YEAR)
        todayMonth = cal.get(Calendar.MONTH) + 1
        todayDay = cal.get(Calendar.DAY_OF_MONTH)
        if (year == 0) {
            year = todayYear
            month = todayMonth
        }
        headerPaint.color = ColorUtils.setAlphaComponent(context.secondaryTextColor, 200)
        legendTextPaint.color = ColorUtils.setAlphaComponent(context.secondaryTextColor, 200)
        selectedInfoPaint.color = context.primaryTextColor
        selectedRingPaint.color = 0xC8767676.toInt()
        todayLinePaint.color = 0xC8767676.toInt()
        isClickable = true
    }

    fun setMonth(year: Int, month: Int, data: Map<Int, Long>, selectedDay: Int = 0) {
        this.year = year
        this.month = month
        this.data = data
        this.selectedDay = selectedDay
        requestLayout()
        invalidate()
    }

    private fun firstColumnIndex(): Int {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1)
        // Calendar.SUNDAY = 1 .. SATURDAY = 7;  我们按 周一=0
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    private fun daysInMonth(): Int {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun rowCount(): Int {
        if (month == 0) return 6
        val first = firstColumnIndex()
        val days = daysInMonth()
        return (first + days + 6) / 7
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = width - paddingLeft - paddingRight
        val cellSize = if (avail > 0) avail / 7f else 0f
        val rows = rowCount().coerceAtLeast(1)
        val height =
            paddingTop + headerHeight + cellSize * rows + selectedInfoHeight + legendHeight + paddingBottom
        setMeasuredDimension(width, height.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (month == 0) return
        val avail = width - paddingLeft - paddingRight
        val cellSize = avail / 7f
        if (cellSize <= 0f) return

        // 星期表头
        val headerBaseline = paddingTop + headerHeight / 2 -
            (headerPaint.descent() + headerPaint.ascent()) / 2
        for (i in 0..6) {
            val cx = paddingLeft + cellSize * (i + 0.5f)
            canvas.drawText(weekdayLabels[i], cx, headerBaseline, headerPaint)
        }

        val gridTop = paddingTop + headerHeight
        val first = firstColumnIndex()
        val days = daysInMonth()
        val accent = context.accentColor
        dayTextPaint.color = context.primaryTextColor
        selectedInfoPaint.color = context.primaryTextColor

        for (d in 1..days) {
            val idx = first + d - 1
            val col = idx % 7
            val row = idx / 7
            val left = paddingLeft + cellSize * col + cellGap
            val top = gridTop + cellSize * row + cellGap
            val right = left + cellSize - cellGap * 2
            val bottom = top + cellSize - cellGap * 2
            tmpRect.set(left, top, right, bottom)

            val value = data[d] ?: 0L
            val level = levelFor(value)
            cellPaint.color = ColorUtils.setAlphaComponent(accent, levelAlphas[level])
            canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, cellPaint)

            // 日期数字
            val cx = (left + right) / 2
            val cy = (top + bottom) / 2 - (dayTextPaint.descent() + dayTextPaint.ascent()) / 2
            canvas.drawText(d.toString(), cx, cy, dayTextPaint)

            // 今天加下划线，紧贴文字基线下方
            if (year == todayYear && month == todayMonth && d == todayDay) {
                val underlineY = cy + dayTextPaint.descent() + 4f.dpToPx()
                val underlineHalf = (right - left) * 0.3f
                canvas.drawLine(
                    cx - underlineHalf, underlineY,
                    cx + underlineHalf, underlineY,
                    todayLinePaint
                )
            }

            // 选中描边
            if (d == selectedDay) {
                canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, selectedRingPaint)
            }
        }

        val infoTop = gridTop + cellSize * rowCount()
        drawSelectedInfo(canvas, infoTop)
        drawLegend(canvas, accent, infoTop + selectedInfoHeight)
    }

    private fun drawLegend(canvas: Canvas, accent: Int, top: Float) {
        // 居中绘制：[少] ▢▢▢▢▢ [多]
        val cellCount = levelAlphas.size
        val cellsTotal = legendCellSize * cellCount + legendCellGap * (cellCount - 1)
        val lessText = "少"
        val moreText = "多"
        val lessW = legendTextPaint.measureText(lessText)
        val moreW = legendTextPaint.measureText(moreText)
        val totalW = lessW + legendLabelGap + cellsTotal + legendLabelGap + moreW
        val startX = paddingLeft + (width - paddingLeft - paddingRight - totalW) / 2f
        val centerY = top + legendHeight / 2f
        val textBaseline = centerY - (legendTextPaint.descent() + legendTextPaint.ascent()) / 2f

        legendTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(lessText, startX, textBaseline, legendTextPaint)

        var cellX = startX + lessW + legendLabelGap
        val cellTop = centerY - legendCellSize / 2f
        val cellBottom = centerY + legendCellSize / 2f
        for (i in 0 until cellCount) {
            cellPaint.color = ColorUtils.setAlphaComponent(accent, levelAlphas[i])
            tmpRect.set(cellX, cellTop, cellX + legendCellSize, cellBottom)
            canvas.drawRoundRect(tmpRect, cellRadius / 2f, cellRadius / 2f, cellPaint)
            cellX += legendCellSize + legendCellGap
        }
        cellX = cellX - legendCellGap + legendLabelGap
        canvas.drawText(moreText, cellX, textBaseline, legendTextPaint)
    }

    private fun drawSelectedInfo(canvas: Canvas, top: Float) {
        if (selectedDay == 0) return
        val readTime = data[selectedDay] ?: 0L
        val text = "${month}月${selectedDay}日 · ${formatDuration(readTime)}"
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = top + selectedInfoHeight / 2f -
            (selectedInfoPaint.descent() + selectedInfoPaint.ascent()) / 2f
        canvas.drawText(text, cx, cy, selectedInfoPaint)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val hours = ms / 3_600_000L
        val minutes = (ms % 3_600_000L) / 60_000L
        return buildString {
            if (hours > 0) append("${hours} 小时")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes} 分钟")
        }
    }

    private fun levelFor(value: Long): Int {
        if (value <= 0L) return 0
        // 固定 12 小时上限，按 1/5 分段：2.4h / 4.8h / 7.2h / 9.6h / 12h+
        val ratio = (value.toFloat() / maxReadMillis).coerceAtMost(1f)
        return when {
            ratio <= 0.2f -> 1
            ratio <= 0.4f -> 2
            ratio <= 0.6f -> 3
            ratio <= 0.8f -> 4
            else -> 5
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val avail = width - paddingLeft - paddingRight
            val cellSize = avail / 7f
            if (cellSize <= 0f) return false
            val x = event.x - paddingLeft
            val y = event.y - paddingTop - headerHeight
            if (x < 0 || y < 0) return false
            val col = (x / cellSize).toInt()
            val row = (y / cellSize).toInt()
            if (col !in 0..6) return false
            val first = firstColumnIndex()
            val days = daysInMonth()
            val d = row * 7 + col - first + 1
            if (d in 1..days) {
                performClick()
                val newSelected = if (selectedDay == d) 0 else d
                selectedDay = newSelected
                invalidate()
                onDayClick?.invoke(d, data[d] ?: 0L, newSelected != 0)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

}
