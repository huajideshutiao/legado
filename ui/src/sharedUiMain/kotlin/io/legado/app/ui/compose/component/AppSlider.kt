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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * 自绘 MD2 SeekBar（风格锁定，替代 M3 Slider 大把手形态）：2dp track + 16dp 圆 thumb。
 *
 * # 值口径（同类滑条唯一入口）
 *
 * - 位置只由手指的绝对坐标换算（四舍五入，出口钳制到区间），不做位移累加，
 *   因此外部回灌什么值都不会让位置产生方向性偏差或漂移；
 * - 换算区间与绘制区间同源（两端各内缩一个滑块半径）；
 * - 调整期间位置由本组件独占：外部 [value] 只在非调整期间决定滑块位置，回灌的量化值顶不回手指位置；
 * - 按下点落在滑块一个半径内时保留该偏移（按在滑块上不跳），否则按手指处定位；
 * - 本次调整内换算结果未变时不回调（指针事件率高于帧率）；每次调整都从零开始回报，
 *   上一次报过的数值不会再被吞；
 * - 区间与业务值走 [rememberUpdatedState]：调整中途变化不重启手势协程（重启会跳过 onDragCancel，
 *   位置会冻在预览值）；被停用、离开组合或配置变化导致的取消只作废本次调整的预览值，不回报落定
 *   （对齐 SeekBar 被停用/移除时不回调 onStopTrackingTouch）。作废也不会回滚调用方持有的预览值，
 *   所以调用方不要把预览值回灌成 [value]（回灌会把作废的预览当成业务值，滑块与文本一起冻住），
 *   预览只用于显示，业务值仍走非调整期间的 [value]。
 *
 * 业务值仍由调用方持有：非调整期间显示 [value]，抬手/点击落定时经 [onValueChangeFinished] 回报最终值。
 *
 * @param value 业务值（非调整期间决定滑块位置）
 * @param max 最大值
 * @param onValueChange 调整期间换算值变化时回调（null = 只要落定回调）
 * @param onValueChangeFinished 抬手或点击落定时回调，参数为最终值（null = 只要调整中回调）
 * @param secondary 缓冲值（null = 不绘制缓冲层）
 * @param secondaryColor 缓冲层颜色
 * @param color 已播层与滑块颜色；停用态保留其色相、整体减半透明
 */
@Composable
fun AppSlider(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    min: Int = 0,
    enabled: Boolean = true,
    color: Color = AppTheme.colors.accent,
    trackColor: Color = color.copy(alpha = 0.3f),
    secondary: Int? = null,
    secondaryColor: Color = trackColor,
    onValueChange: ((Int) -> Unit)? = null,
    onValueChangeFinished: ((Int) -> Unit)? = null,
) {
    val range = (max - min).coerceAtLeast(1)
    val thumbRadius = 8.dp

    // 调整期间的预览值 (Int.MIN_VALUE = 未在调整): 调整期间位置只由手指换算
    val dragValue = remember { mutableIntStateOf(Int.MIN_VALUE) }
    // 按下点与滑块位置的距离 (px), 超过一个滑块半径归零 (对齐 SeekBar 的抓取偏移)
    val grabOffset = remember { mutableFloatStateOf(0f) }
    // 本次调整已回报的值: 换算结果未变时不重复回调
    val emitted = remember { mutableIntStateOf(Int.MIN_VALUE) }

    // 手势协程持有启动时的闭包: 区间与业务值经状态读最新值, 只有停用开关进 key
    val currentMin = rememberUpdatedState(min)
    val currentMax = rememberUpdatedState(max)
    val currentValue = rememberUpdatedState(value)
    val onChange = rememberUpdatedState(onValueChange)
    val onFinished = rememberUpdatedState(onValueChangeFinished)

    val shown = if (dragValue.intValue == Int.MIN_VALUE) value else dragValue.intValue

    fun liveRange(): Int = (currentMax.value - currentMin.value).coerceAtLeast(1)

    fun fraction(v: Int, from: Int, span: Int): Float =
        ((v - from).toFloat() / span).coerceIn(0f, 1f)

    fun valueAt(x: Float, widthPx: Float, radiusPx: Float): Int {
        val span = sliderTrackSpan(widthPx, radiusPx)
        val f = ((x + grabOffset.floatValue - sliderTrackStart(widthPx, radiusPx)) / span)
            .coerceIn(0f, 1f)
        val from = currentMin.value
        val to = currentMax.value
        // 出口钳制: 区间为空 (max == min) 时恒等于该值 (对齐 SeekBar 的 range = 0)
        return (from + (f * liveRange()).roundToInt())
            .coerceIn(from, if (to < from) from else to)
    }

    /** 手指处定位: 预览值与回调同源, 换算值未变时不回调 */
    fun report(x: Float, widthPx: Float, radiusPx: Float) {
        val v = valueAt(x, widthPx, radiusPx)
        dragValue.intValue = v
        if (v != emitted.intValue) {
            emitted.intValue = v
            onChange.value?.invoke(v)
        }
    }

    /** 按住时记下手指与滑块的偏移: 一个滑块半径内保留 (按在滑块上不跳), 否则归零 */
    fun rememberGrab(x: Float, widthPx: Float, radiusPx: Float) {
        val v = if (dragValue.intValue == Int.MIN_VALUE) currentValue.value else dragValue.intValue
        val span = sliderTrackSpan(widthPx, radiusPx)
        val thumbX = sliderTrackStart(widthPx, radiusPx) +
            span * fraction(v, currentMin.value, liveRange())
        val offset = thumbX - x
        grabOffset.floatValue = if (abs(offset) > radiusPx) 0f else offset
    }

    /** 清掉本次调整的手势状态 (预览值 / 抓取偏移 / 去重缓存) */
    fun clearGesture() {
        dragValue.intValue = Int.MIN_VALUE
        grabOffset.floatValue = 0f
        emitted.intValue = Int.MIN_VALUE
    }

    /** 抬手或点击落定: 回报最终值并交还位置所有权 */
    fun finish() {
        val v = dragValue.intValue
        clearGesture()
        onFinished.value?.invoke(v)
    }

    // 停用态保留调用方色相, 整体减半透明 (对齐 SeekBar 的 disabledAlpha 语义)
    val active = if (enabled) color else color.copy(alpha = color.alpha / 2f)
    val base = if (enabled) trackColor else trackColor.copy(alpha = trackColor.alpha / 2f)
    val buffer = if (enabled) secondaryColor else secondaryColor.copy(alpha = secondaryColor.alpha / 2f)

    Box(
        modifier
            .height(DesignTokens.viewHeightLarge)
            .pointerInput(enabled) {
                // 停用时不接管手势; 调整中途被停用或离开组合时协程被直接取消, 走不到 onDragCancel
                if (!enabled) return@pointerInput
                val rPx = thumbRadius.toPx()
                try {
                    detectTapGestures(
                        onPress = { pos -> rememberGrab(pos.x, size.width.toFloat(), rPx) },
                        onTap = { pos ->
                            // 本次调整从零开始回报, 上一次调整报过的数值不再被吞
                            emitted.intValue = Int.MIN_VALUE
                            report(pos.x, size.width.toFloat(), rPx)
                            finish()
                        },
                    )
                } catch (e: CancellationException) {
                    // 作废本次调整: 未落定的预览值不回报
                    clearGesture()
                    throw e
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val rPx = thumbRadius.toPx()
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { emitted.intValue = Int.MIN_VALUE },
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, _ ->
                        change.consume()
                        report(change.position.x, size.width.toFloat(), rPx)
                    }
                } catch (e: CancellationException) {
                    // 作废本次调整: 未落定的预览值不回报
                    clearGesture()
                    throw e
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2
            val rPx = thumbRadius.toPx()
            val trackH = 2.dp.toPx()
            // 宽度不足两个半径时两端收到中线, 避免反向线段与滑块出框
            val startX = sliderTrackStart(size.width, rPx)
            val endX = size.width - startX
            val span = sliderTrackSpan(size.width, rPx)
            val thumbX = startX + span * fraction(shown, min, range)
            drawLine(base, Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            secondary?.let {
                val bufferX = startX + span * fraction(it, min, range)
                if (bufferX > startX) {
                    drawLine(buffer, Offset(startX, cy), Offset(bufferX, cy), trackH, StrokeCap.Round)
                }
            }
            drawLine(active, Offset(startX, cy), Offset(thumbX, cy), trackH, StrokeCap.Round)
            drawCircle(active, rPx, Offset(thumbX, cy))
        }
    }
}

/**
 * 滑块轨道的可用起点与跨度（换算与绘制同源）：两端各内缩一个滑块半径，宽度不足两个
 * 半径时起点收到中线、跨度不小于 1px。滑条族部件（自绘滑块、取色渐变滑条）共用同一份公式。
 */
internal fun sliderTrackStart(widthPx: Float, radiusPx: Float): Float =
    radiusPx.coerceAtMost(widthPx / 2f)

internal fun sliderTrackSpan(widthPx: Float, radiusPx: Float): Float =
    (widthPx - 2 * radiusPx).coerceAtLeast(1f)

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
    // 拖动中的预览值；-1 表示未在拖动（仅用于拖动中显示数值，滑块位置由 AppSlider 自己持有）
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
            value = value,
            max = max,
            min = min,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue = -1
                onChanged(it)
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
