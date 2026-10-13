package io.legado.app.ui.compose.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_reduce
import legado.ui.generated.resources.plus
import legado.ui.generated.resources.reduce
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 自绘 MD2 SeekBar（风格锁定，替代 M3 Slider 大把手形态）：2dp track + 16dp 圆 thumb。
 * 语义对齐 SeekBar：拖动中回调 [onValueChange]，抬手回调 [onValueChangeFinished]。
 * 值的换算区间与绘制区间同源（两端各内缩一个滑块半径）；换算结果未变时不回调。
 */
@Composable
fun AppSlider(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    color: Color = AppTheme.colors.accent,
    trackColor: Color = color.copy(alpha = 0.3f),
) {
    val range = (max - min).coerceAtLeast(1)

    fun fractionToValue(fraction: Float): Int =
        (min + (fraction * range)).toInt().coerceIn(min, max)

    // 上次已回调的值: 指针事件率高于帧率, 换算结果未变时不再回调调用方
    val lastEmitted = remember { mutableIntStateOf(Int.MIN_VALUE) }

    fun emitAt(x: Float, widthPx: Float, radiusPx: Float) {
        val span = (widthPx - 2 * radiusPx).coerceAtLeast(1f)
        val v = fractionToValue(((x - radiusPx) / span).coerceIn(0f, 1f))
        if (v != lastEmitted.intValue) {
            lastEmitted.intValue = v
            onValueChange(v)
        }
    }

    val thumbRadius = 8.dp
    val disabledColor = AppTheme.colors.secondaryText.copy(alpha = 0.4f)
    val active = if (enabled) color else disabledColor
    val inactive = if (enabled) trackColor else disabledColor.copy(alpha = 0.2f)

    Box(
        modifier
            .height(DesignTokens.viewHeightLarge)
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                val rPx = thumbRadius.toPx()
                detectTapGestures(onTap = { pos ->
                    emitAt(pos.x, size.width.toFloat(), rPx)
                    onValueChangeFinished?.invoke()
                })
            }
            .pointerInput(enabled, min, max) {
                if (!enabled) return@pointerInput
                val rPx = thumbRadius.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                ) { change, _ ->
                    change.consume()
                    emitAt(change.position.x, size.width.toFloat(), rPx)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val fraction = ((value - min).toFloat() / range).coerceIn(0f, 1f)
            val cy = size.height / 2
            val rPx = thumbRadius.toPx()
            val trackH = 2.dp.toPx()
            val startX = rPx
            val endX = size.width - rPx
            val thumbX = startX + (endX - startX) * fraction
            drawLine(inactive, Offset(thumbX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            drawLine(active, Offset(startX, cy), Offset(thumbX, cy), trackH, StrokeCap.Round)
            drawCircle(active, rPx, Offset(thumbX, cy))
        }
    }
}

/**
 * 复刻 view_detail_seek_bar（DetailSeekBar）：标题 + 减 + 滑条 + 加 + 值。
 * [onChanged] 语义与原件一致：+/- 点击立即回调；拖动抬手回调；拖动中仅刷新值显示。
 */
@Composable
fun AppDetailSeekBar(
    title: String,
    value: Int,
    max: Int,
    onChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    textColor: Color = AppTheme.colors.primaryText,
    valueFormat: ((Int) -> String)? = null,
) {
    // 拖动中的预览值；-1 表示未在拖动
    var dragValue by remember { mutableIntStateOf(-1) }
    val shown = if (dragValue >= 0) dragValue else value
    Row(
        modifier.height(DesignTokens.viewHeightLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = textColor, maxLines = 1, modifier = Modifier.padding(end = DesignTokens.spacingLg))
        IconButton(onClick = { onChanged((value - 1).coerceIn(min, max)) }, modifier = Modifier.size(24.dp)) {
            Icon(
                painter = painterResource(Res.drawable.ic_reduce),
                contentDescription = stringResource(Res.string.reduce),
                tint = textColor,
            )
        }
        AppSlider(
            value = shown,
            max = max,
            min = min,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                if (dragValue >= 0) onChanged(dragValue)
                dragValue = -1
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChanged((value + 1).coerceIn(min, max)) }, modifier = Modifier.size(24.dp)) {
            Icon(
                painter = painterResource(Res.drawable.ic_add),
                contentDescription = stringResource(Res.string.plus),
                tint = textColor,
            )
        }
        Text(
            text = valueFormat?.invoke(shown) ?: shown.toString(),
            color = textColor,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}
