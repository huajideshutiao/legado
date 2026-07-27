package io.legado.app.ui.book.manga.render

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.min
import com.google.android.material.R as materialR

/** 信息条文字对齐(原 ReaderInfoBarView.ALIGN_*) */
const val INFO_BAR_ALIGN_LEFT = 0
const val INFO_BAR_ALIGN_CENTER = 1

/**
 * 漫画阅读信息条(原 ReaderInfoBarView 自绘移植)：左/中对齐的进度文字 + 右对齐时钟，
 * 描边+填充双层描字，文字按条高 80% 自适应缩放，居中时再按可用宽度收缩；
 * 时钟随 ACTION_TIME_TICK 刷新，刘海/系统栏横向 inset 达到内边距时避让。
 */
@Composable
fun MangaInfoBar(text: String, alignment: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT) }
    var timeText by remember { mutableStateOf(timeFormat.format(Date())) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                timeText = timeFormat.format(Date())
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_TICK),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val colorText = remember(context) {
        ColorUtils.setAlphaComponent(
            context.obtainStyledAttributes(intArrayOf(materialR.attr.colorOnSurface)).use {
                it.getColor(0, Color.BLACK)
            },
            200,
        )
    }
    val colorOutline = remember(context) {
        ColorUtils.setAlphaComponent(
            context.obtainStyledAttributes(intArrayOf(materialR.attr.colorSurface)).use {
                it.getColor(0, Color.WHITE)
            },
            200,
        )
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val textBounds = remember { Rect() }
    val systemBars = WindowInsets.systemBars

    Canvas(modifier) {
        paint.strokeWidth = 2.dp.toPx()
        paint.setShadowLayer(2f, 1f, 1f, Color.GRAY)
        val paddingLeft = 16.dp.toPx()
        val paddingRight = 16.dp.toPx()
        val insetLeft = 10.dp.toPx()
        val insetRight = 10.dp.toPx()
        val insetTop = min(insetLeft, insetRight)
        val sysLeft = systemBars.getLeft(this, layoutDirection)
        val sysRight = systemBars.getRight(this, layoutDirection)
        val cutoutInsetLeft = if (sysLeft >= paddingLeft) sysLeft else 0
        val cutoutInsetRight = if (sysRight >= paddingRight) sysRight else 0
        val innerHeight = size.height - insetTop
        val innerWidth = size.width - paddingLeft - paddingRight - insetLeft - insetRight

        // 自适应字号：先按 48px 测量，再按高度 80% 与(居中时)可用宽度缩放
        val testTextSize = 48f
        paint.textSize = testTextSize
        paint.getTextBounds(text, 0, text.length, textBounds)
        val maxTextHeight = innerHeight * 0.8f
        val widthScale = if (alignment == INFO_BAR_ALIGN_CENTER) {
            val availableWidth = innerWidth - cutoutInsetLeft - cutoutInsetRight
            val requiredWidth = paint.measureText(text)
            if (requiredWidth > availableWidth) availableWidth / requiredWidth else 1f
        } else {
            1f
        }
        paint.textSize = testTextSize * min(maxTextHeight / textBounds.height(), widthScale)
        paint.getTextBounds(text, 0, text.length, textBounds)

        val ty = innerHeight / 2f + textBounds.height() / 2f - textBounds.bottom
        val textX = when (alignment) {
            INFO_BAR_ALIGN_CENTER -> {
                val textWidth = paint.measureText(text)
                (size.width / 2f).coerceIn(
                    paddingLeft + insetLeft + cutoutInsetLeft + textWidth / 2,
                    size.width - paddingRight - insetRight - cutoutInsetRight - textWidth / 2
                )
            }

            else -> paddingLeft + insetLeft + cutoutInsetLeft
        }
        paint.textAlign = when (alignment) {
            INFO_BAR_ALIGN_CENTER -> Paint.Align.CENTER
            else -> Paint.Align.LEFT
        }
        drawTextOutline(paint, colorText, colorOutline, text, textX, insetTop + ty)

        paint.textAlign = Paint.Align.RIGHT
        drawTextOutline(
            paint, colorText, colorOutline, timeText,
            size.width - paddingRight - insetRight - cutoutInsetRight,
            insetTop + ty,
        )
    }
}

private fun DrawScope.drawTextOutline(
    paint: Paint,
    colorText: Int,
    colorOutline: Int,
    text: String,
    x: Float,
    y: Float,
) {
    drawContext.canvas.nativeCanvas.run {
        paint.color = colorOutline
        paint.style = Paint.Style.STROKE
        drawText(text, x, y, paint)
        paint.color = colorText
        paint.style = Paint.Style.FILL
        drawText(text, x, y, paint)
    }
}
