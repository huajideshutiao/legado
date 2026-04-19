package io.legado.app.ui.widget

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import io.legado.app.utils.dpToPx
import kotlin.math.min

class LrcView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val lrcData = mutableListOf<LrcLine>()
    private var currentIndex = -1
    private var lastIndex = -1
    private var primaryColor = 0xFFFFFFFF.toInt()
    private var secondaryColor = 0x80FFFFFF.toInt()

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 20.dpToPx().toFloat()
    }

    private var scrollYOffset = 0f
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var onPlayClickListener: ((Int) -> Unit)? = null

    private var autoScroll = true
    private var lastScrollTime = 0L
    private val autoResetRunnable = Runnable {
        if (!autoScroll && System.currentTimeMillis() - lastScrollTime >= 5000) {
            autoScroll = true
            scrollToIndex(currentIndex)
        }
    }

    private val colorEvaluator = ArgbEvaluator()
    private var colorProgress = 1.0f
    private val lineMargin = 20.dpToPx()

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dX: Float,
                dY: Float
            ): Boolean {
                autoScroll = false
                removeCallbacks(autoResetRunnable)
                scrollYOffset = (scrollYOffset + dY).coerceIn(0f, maxScrollY())
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                scroller.fling(
                    0,
                    scrollYOffset.toInt(),
                    0,
                    (-vY).toInt(),
                    0,
                    0,
                    0,
                    maxScrollY().toInt()
                )
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val touchY = scrollYOffset + e.y - height / 2f
                val clickedIndex = lrcData.binarySearch {
                    if (touchY < it.offset) 1 else if (touchY >= it.offset + it.height) -1 else 0
                }
                if (clickedIndex >= 0) {
                    onPlayClickListener?.invoke(lrcData[clickedIndex].time)
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }
        })

    class LrcLine(
        val time: Int,
        val text: String,
        var layout: StaticLayout? = null,
        var height: Int = 0,
        var offset: Float = 0f
    )

    fun setLrcData(data: List<Pair<Int, String>>) {
        lrcData.clear()
        data.mapTo(lrcData) { LrcLine(it.first, it.second) }
        prepareLayouts()
        currentIndex = -1
        lastIndex = -1
        if (autoScroll) {
            scrollYOffset = lrcData.firstOrNull()?.let { it.height / 2f } ?: 0f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepareLayouts()
    }

    private fun prepareLayouts() {
        val availableWidth = width - paddingLeft - paddingRight
        if (availableWidth <= 0 || lrcData.isEmpty()) return
        var currentOffset = 0f
        lrcData.forEach { line ->
            line.layout =
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, paint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build().also {
                        line.height = it.height + lineMargin
                        line.offset = currentOffset
                        currentOffset += line.height
                    }
        }
    }

    fun setColors(primary: Int, secondary: Int) {
        this.primaryColor = primary
        this.secondaryColor = secondary
        invalidate()
    }

    fun updateProgress(index: Int) {
        if (index != currentIndex && index in lrcData.indices) {
            lastIndex = currentIndex
            currentIndex = index
            colorProgress = 0f
            autoScroll = true
            removeCallbacks(autoResetRunnable)
            scrollToIndex(currentIndex)
        }
    }

    private fun scrollToIndex(index: Int) {
        if (index !in lrcData.indices) return
        val targetY = lrcData[index].offset + lrcData[index].height / 2f
        scroller.startScroll(0, scrollYOffset.toInt(), 0, (targetY - scrollYOffset).toInt(), 600)
        invalidate()
    }

    private fun maxScrollY() = lrcData.lastOrNull()?.let { it.offset + it.height / 2f } ?: 0f

    override fun computeScroll() {
        var needInvalidate = false
        if (scroller.computeScrollOffset()) {
            scrollYOffset = scroller.currY.toFloat()
            needInvalidate = true
        }
        if (colorProgress < 1.0f) {
            colorProgress = min(1.0f, colorProgress + 0.1f)
            needInvalidate = true
        }
        if (needInvalidate) invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            lastScrollTime = System.currentTimeMillis()
            removeCallbacks(autoResetRunnable)
            postDelayed(autoResetRunnable, 5000)
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (lrcData.isEmpty()) return
        val centerY = height / 2f
        val contentCenterX = (paddingLeft + width - paddingRight) / 2f

        // 二分查找定位首个可见行
        val viewTop = scrollYOffset - centerY
        val firstVisible = lrcData.binarySearch {
            if (it.offset + it.height < viewTop) -1 else 1
        }.inv()

        for (i in firstVisible until lrcData.size) {
            val line = lrcData[i]
            val lineY = centerY + (line.offset - scrollYOffset)
            if (lineY > height) break
            if (lineY + line.height < 0) continue

            val isCurrent = i == currentIndex
            val isLast = i == lastIndex

            paint.color = when {
                isCurrent -> colorEvaluator.evaluate(
                    colorProgress,
                    secondaryColor,
                    primaryColor
                ) as Int

                isLast -> colorEvaluator.evaluate(
                    colorProgress,
                    primaryColor,
                    secondaryColor
                ) as Int

                else -> secondaryColor
            }

            val scale = when {
                isCurrent -> 1f + 0.05f * colorProgress
                isLast -> 1.05f - 0.05f * colorProgress
                else -> 1f
            }

            val lineCenterY = lineY + line.height / 2f
            val layout = line.layout ?: continue
            canvas.withScale(scale, scale, contentCenterX, lineCenterY) {
                paint.alpha = (paint.alpha * calculateAlpha(lineCenterY)) / 255
                val layoutY = lineY + (line.height - layout.height) / 2f
                canvas.withTranslation(contentCenterX - layout.width / 2f, layoutY) {
                    layout.draw(this)
                }
            }
        }
    }

    private fun calculateAlpha(lineY: Float): Int {
        val fadeBoundary = height * 0.35f
        return when {
            lineY < fadeBoundary -> (lineY / fadeBoundary * 255).toInt().coerceIn(40, 255)
            lineY > height - fadeBoundary -> ((height - lineY) / fadeBoundary * 255).toInt()
                .coerceIn(40, 255)

            else -> 255
        }
    }

    fun setOnPlayClickListener(listener: (Int) -> Unit) {
        this.onPlayClickListener = listener
    }
}