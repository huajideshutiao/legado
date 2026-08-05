package io.legado.app.ui.book.audio

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.image.BookImageLoaders
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 全端共享歌词控件: 逐项复刻原版 LrcView
 * (origin/quickjs app/src/main/java/io/legado/app/ui/widget/LrcView.kt)。
 *
 * Android 端原 LrcView.kt (AndroidView 包装) 已由本控件取代, 各端仅一份实现。
 *
 * # 对照表 (原版 → 本实现, 参数/时序/插值全部一致)
 * | 原版 | 本实现 |
 * |------|--------|
 * | paint.textSize = 20.dpToPx() | fontSize = 20.sp (默认 fontScale=1, sp==dp) |
 * | lineMargin = 20.dpToPx() | lineMarginPx = 20.dp.toPx() |
 * | 行高 = StaticLayout.height + lineMargin | 行高 = layout.size.height + lineMarginPx |
 * | offset 累加 (prepareLayouts) | 同 |
 * | ALIGN_CENTER + lineSpacing(0,1) + includePad(false) | TextAlign.Center |
 * | scrollYOffset = 当前行中心 | scrollY = lines[i].offset + height/2 |
 * | 绘制 lineY = centerY + (line.offset - scrollYOffset) | 同 |
 * | updateProgress: lastIndex/currentIndex/colorProgress=0 | 同 |
 * | 首次 (lastIndex==-1) 无动画直接定位 | 同 |
 * | 切行滚动 startScroll(600ms, DecelerateInterpolator) | tween(600, [DECELERATE]) |
 * | DecelerateInterpolator: 1-(1-t)^2 | [DECELERATE] = Easing { 1f-(1f-it)*(1f-it) } |
 * | colorProgress 每帧 +0.1 (≈10帧) | tween(167) (60fps 下 10 帧) |
 * | 颜色: current=ArgbEval(sec→pri), last=ArgbEval(pri→sec) | lerp 同参同序 |
 * | 缩放: current 1+0.05p, last 1.05-0.05p, 锚点(内容中心X,行中心Y) | 同 |
 * | 透明度: 上下 0.35h 边界线性 255→40, 与颜色 alpha 相乘 | 同 (calculateAlpha) |
 * | 点击: touchY=scrollYOffset+y-h/2, 二分 offset 区间, 回调 time | 同 (touchSlop 判定 tap/drag) |
 * | 拖动: scrollYOffset±dy coerce 0..maxScrollY | 同 |
 * | 滚轮: AXIS_VSCROLL*lineMargin*3, 上滚看前(减) | 同 (Scroll 事件跨平台, 方向语义等价) |
 * | fling: OverScroller 物理衰减 | 近似: 按松手速度估算终点 + 400ms 减速 (物理曲线跨平台无法逐字复刻) |
 * | 手动滚动 5s 后 autoScroll=true + 回中当前行 | 同 (manualTick 重置计时, 切行取消) |
 * | setLrcData: 重置 + 滚到第一行中心 | 数据变化时同 |
 * | onSizeChanged: 重排 + autoScroll 时回中当前行 | 同 (宽度变化保留 currentIndex) |
 * | 默认色 0xFFFFFFFF / 0x80FFFFFF | 同 |
 *
 * 颜色: 封面取色成功后传 [primaryColor]/[secondaryColor] (不透明, 对应原版 setColors),
 * 取色前用原版默认值。
 */
@Composable
fun LrcViewShared(
    lrcData: List<Pair<Int, String>>?,
    lrcProgress: Int,
    primaryColor: Color,
    secondaryColor: Color,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    // 行模型 (复刻原版 LrcLine: time/text/layout/height/offset)
    class LrcLine(
        val time: Int,
        val text: String,
        val layout: TextLayoutResult?,
        val height: Int,
        val offset: Float,
    )

    var viewportW by remember { mutableIntStateOf(0) }
    var viewportH by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var lastData by remember { mutableStateOf<List<Pair<Int, String>>?>(null) }

    // 原版字段
    var currentIndex by remember { mutableIntStateOf(-1) }
    var lastIndex by remember { mutableIntStateOf(-1) }
    var colorProgress by remember { mutableFloatStateOf(1f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableStateOf(true) }
    // 手动滚动计时 (每次手动滚动 +1, 重启 5s 自动回中; 复刻 removeCallbacks+postDelayed)
    var manualTick by remember { mutableIntStateOf(0) }

    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var colorJob by remember { mutableStateOf<Job?>(null) }

    val fontSize = 20.sp
    val lineMarginPx = with(LocalDensity.current) { 20.dp.toPx() }

    // 测量 (复刻 prepareLayouts; 数据/宽度变化时重建)
    LaunchedEffect(lrcData, viewportW, fontSize) {
        val data = lrcData ?: emptyList()
        if (viewportW <= 0 || data.isEmpty()) {
            lines = emptyList()
            return@LaunchedEffect
        }
        val dataChanged = lastData !== lrcData
        lastData = lrcData
        val oldCurrent = currentIndex
        val oldAutoScroll = autoScroll
        val style = TextStyle(fontSize = fontSize, textAlign = TextAlign.Center)
        var offset = 0f
        val newLines = data.map { (time, text) ->
            val layout = textMeasurer.measure(
                text,
                style,
                constraints = Constraints(maxWidth = viewportW),
            )
            val h = layout.size.height + lineMarginPx.roundToInt()
            LrcLine(time, text, layout, h, offset).also { offset += h }
        }
        lines = newLines
        if (dataChanged) {
            // setLrcData: 重置全部状态, 滚到第一行中心
            currentIndex = -1
            lastIndex = -1
            colorProgress = 1f
            autoScroll = true
            scrollJob?.cancel()
            scrollY = newLines.firstOrNull()?.let { it.height / 2f } ?: 0f
        } else {
            // onSizeChanged: 重排后 autoScroll 时回中当前行 (保留 currentIndex/lastIndex)
            if (oldAutoScroll && oldCurrent in newLines.indices) {
                scrollJob?.cancel()
                scrollY = newLines[oldCurrent].offset + newLines[oldCurrent].height / 2f
            }
        }
    }

    // 切行 (复刻 updateProgress)
    LaunchedEffect(lrcProgress, lines) {
        val index = lrcProgress
        if (index < 0 || index >= lines.size || index == currentIndex) return@LaunchedEffect
        lastIndex = currentIndex
        currentIndex = index
        colorProgress = 0f
        autoScroll = true
        // 切行取消 pending 自动回中 (复刻 removeCallbacks(autoResetRunnable))
        manualTick++
        val target = lines[index].offset + lines[index].height / 2f
        if (lastIndex == -1) {
            // 首次: 无动画直接定位
            scrollJob?.cancel()
            scrollY = target
        } else {
            // 600ms 减速滚动 (复刻 scroller.startScroll(..., 600) + DecelerateInterpolator)
            scrollJob?.cancel()
            val from = scrollY
            scrollJob = scope.launch {
                animate(from, target, animationSpec = tween(600, easing = DECELERATE)) { v, _ ->
                    scrollY = v
                }
            }
        }
        // 颜色渐变: 原版 computeScroll 每帧 +0.1 ≈ 10 帧 (60fps ≈ 167ms)
        colorJob?.cancel()
        colorJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(167)) { v, _ -> colorProgress = v }
        }
    }

    // 手动滚动 5 秒后自动回中 (复刻 autoResetRunnable)
    LaunchedEffect(manualTick, lines) {
        if (autoScroll || manualTick == 0) return@LaunchedEffect
        delay(5000)
        autoScroll = true
        val idx = currentIndex
        if (idx in lines.indices) {
            val target = lines[idx].offset + lines[idx].height / 2f
            scrollJob?.cancel()
            scrollJob = scope.launch {
                animate(scrollY, target, animationSpec = tween(600, easing = DECELERATE)) { v, _ ->
                    scrollY = v
                }
            }
        }
    }

    // pointerInput 在组合外执行, 状态经 rememberUpdatedState 取最新值
    val currentLines by rememberUpdatedState(lines)
    val currentScrollY by rememberUpdatedState(scrollY)
    val currentViewportH by rememberUpdatedState(viewportH)

    fun maxScrollY(): Float =
        currentLines.lastOrNull()?.let { it.offset + it.height / 2f } ?: 0f

    fun beginManualScroll() {
        autoScroll = false
        manualTick++
        scrollJob?.cancel()
        colorJob?.cancel()
        colorProgress = 1f
    }

    Canvas(
        modifier
            .onSizeChanged {
                viewportW = it.width
                viewportH = it.height
            }
            // 点击/拖动/fling (复刻 GestureDetector: onScroll/onFling/onSingleTapUp)
            .pointerInput(lrcData) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 跨平台判断位置变化 (positionChanged 是 Android 专属扩展)
                        if (change.position != change.previousPosition) {
                            val totalDy = change.position.y - down.position.y
                            if (!dragged && abs(totalDy) > touchSlopPx) {
                                // 越过 touch slop 进入拖动 (原版 onScroll)
                                dragged = true
                                beginManualScroll()
                            }
                            if (dragged) {
                                val dy = change.position.y - change.previousPosition.y
                                scrollY = (scrollY + dy).coerceIn(0f, maxScrollY())
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        }
                        when (event.type) {
                            PointerEventType.Release -> {
                                if (dragged) {
                                    // 松手 fling: OverScroller 物理衰减的跨平台近似
                                    // (按松手速度估算终点 + 400ms 减速; 原版 spline 曲线无法逐字复刻)
                                    val velocity = velocityTracker.calculateVelocity().y
                                    if (abs(velocity) > 200f) {
                                        val target = (scrollY - velocity / 2000f)
                                            .coerceIn(0f, maxScrollY())
                                        scrollJob?.cancel()
                                        scrollJob = scope.launch {
                                            animate(
                                                scrollY,
                                                target,
                                                animationSpec = tween(400, easing = DECELERATE),
                                            ) { v, _ -> scrollY = v }
                                        }
                                    }
                                } else {
                                    // 点击行跳转 (复刻 onSingleTapUp 二分定位)
                                    if (currentLines.isNotEmpty()) {
                                        val touchY = currentScrollY + change.position.y -
                                            currentViewportH / 2f
                                        val idx = currentLines.binarySearch { line ->
                                            if (touchY < line.offset) 1
                                            else if (touchY >= line.offset + line.height) -1
                                            else 0
                                        }
                                        if (idx >= 0) onLineClick(currentLines[idx].time)
                                    }
                                }
                                break
                            }

                            else -> Unit
                        }
                    }
                }
            }
            // 滚轮 (复刻 onGenericMotionEvent: 滚动量 = AXIS_VSCROLL * lineMargin * 3;
            // Scroll 事件 delta>0 = 向下滚(看后面) = scrollY 增大, 与原版 VSCROLL>0=上滚(看前面)=减小 语义等价)
            .pointerInput(lrcData) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (delta != 0f && currentLines.isNotEmpty()) {
                            beginManualScroll()
                            scrollY = (scrollY + delta * lineMarginPx * 3)
                                .coerceIn(0f, maxScrollY())
                            event.changes.firstOrNull()?.consume()
                        }
                    }
                }
            },
    ) {
        if (lines.isEmpty()) return@Canvas
        val centerY = size.height / 2f
        val contentCenterX = size.width / 2f
        val viewTop = scrollY - centerY

        // 二分定位首个可见行 (复刻原版 firstVisible)
        val firstVisible = lines.binarySearch { line ->
            if (line.offset + line.height < viewTop) -1 else 1
        }.inv()

        for (i in firstVisible until lines.size) {
            val line = lines[i]
            val lineY = centerY + (line.offset - scrollY)
            if (lineY > size.height) break
            if (lineY + line.height < 0) continue

            val isCurrent = i == currentIndex
            val isLast = i == lastIndex

            // 颜色渐变 (复刻 ArgbEvaluator.evaluate)
            val baseColor = when {
                isCurrent -> lerp(secondaryColor, primaryColor, colorProgress)
                isLast -> lerp(primaryColor, secondaryColor, colorProgress)
                else -> secondaryColor
            }
            // 缩放 (复刻 withScale: 1→1.05 / 1.05→1, 锚点行中心)
            val scaleFactor = when {
                isCurrent -> 1f + 0.05f * colorProgress
                isLast -> 1.05f - 0.05f * colorProgress
                else -> 1f
            }
            val lineCenterY = lineY + line.height / 2f
            val layout = line.layout ?: continue
            // 透明度 (复刻 calculateAlpha: 上下 0.35h 边界线性 255→40)
            val fade = calculateAlpha(lineCenterY, size.height)

            scale(scaleFactor, scaleFactor, pivot = Offset(contentCenterX, lineCenterY)) {
                val textY = lineY + (line.height - layout.size.height) / 2f
                drawText(
                    textLayoutResult = layout,
                    // 原版: withTranslation(contentCenterX - layout.width / 2f, layoutY)
                    // —— Compose 文本块宽度自适应 (短文本 < 视口宽), 需按块宽居中
                    color = baseColor.copy(alpha = baseColor.alpha * (fade / 255f)),
                    topLeft = Offset(contentCenterX - layout.size.width / 2f, textY),
                )
            }
        }
    }
}

/** 复刻 android.view.animation.DecelerateInterpolator: f(t) = 1 - (1-t)^2 */
private val DECELERATE: Easing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction)
}

/** 复刻原版 calculateAlpha: 距视口中心超过 35% 高度的行线性渐隐到 40。 */
private fun calculateAlpha(lineCenterY: Float, viewportHeight: Float): Int {
    val fadeBoundary = viewportHeight * 0.35f
    return when {
        lineCenterY < fadeBoundary -> (lineCenterY / fadeBoundary * 255).toInt().coerceIn(40, 255)
        lineCenterY > viewportHeight - fadeBoundary ->
            ((viewportHeight - lineCenterY) / fadeBoundary * 255).toInt().coerceIn(40, 255)

        else -> 255
    }
}

// ==================== 封面取色 (复刻原版 getRepresentativeColor + updateLrcColor) ====================

/** 像素读取抽象 (由 [ImageBitmapPixelReader] 提供; 与平台无关)。 */
interface PixelReader {
    val width: Int
    val height: Int
    fun pixel(x: Int, y: Int): Color
}

private class ImageBitmapPixelReader(
    private val bitmap: ImageBitmap,
) : PixelReader {
    private val pixelMap = bitmap.toPixelMap()
    override val width: Int = pixelMap.width
    override val height: Int = pixelMap.height
    override fun pixel(x: Int, y: Int): Color = pixelMap[x, y]
}

/**
 * 复刻原版 `Bitmap.getRepresentativeColor` (BitmapUtils.kt):
 * - 缩放到 64px 最长边 (此处用等距采样步长等价)
 * - 过滤: alpha < 128 跳过; HSL 饱和度 <0.1 或 亮度 <0.1 或 >0.9 跳过
 * - 平均 RGB; 无有效像素时返回中心像素
 */
fun representativeColorOf(reader: PixelReader): Color {
    val step = max(1, (max(reader.width, reader.height) / 64f).roundToInt())
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    var middle = Color.Transparent
    var y = 0
    while (y < reader.height) {
        var x = 0
        while (x < reader.width) {
            val c = reader.pixel(x, y)
            if (x == reader.width / 2 && y == reader.height / 2) middle = c
            if (c.alpha > 0.5f) { // 原版 (pixel shr 24) and 0xFF >= 128
                val (_, s, l) = rgbToHsl(c.red, c.green, c.blue)
                if (s >= 0.1f && l >= 0.1f && l <= 0.9f) {
                    rSum += (c.red * 255).roundToInt()
                    gSum += (c.green * 255).roundToInt()
                    bSum += (c.blue * 255).roundToInt()
                    count++
                }
            }
            x += step
        }
        y += step
    }
    return if (count == 0) {
        middle
    } else {
        Color(
            red = (rSum / count) / 255f,
            green = (gSum / count) / 255f,
            blue = (bSum / count) / 255f,
        )
    }
}

/**
 * 复刻原版 `AudioPlayActivity.updateLrcColor`:
 * - meanColor → HSL; isLight = L > 0.6
 * - secondary: light → L-0.45 (下限 0.3); dark → L+0.45 (上限 0.7)
 * - primary: 基于 secondary 的 L 再 ±0.35 (下限 0.2 / 上限 0.8)
 * - 输出不透明色 (HSLToColor), 供歌词 setColors + SeekBar tint
 */
fun adjustLrcColors(meanColor: Color): Pair<Color, Color> {
    val (h, s, l) = rgbToHsl(meanColor.red, meanColor.green, meanColor.blue)
    val isLight = l > 0.6f
    val secondaryL = if (isLight) (l - 0.45f).coerceAtLeast(0.3f)
    else (l + 0.45f).coerceAtMost(0.7f)
    val secondary = hslToColor(h, s, secondaryL)
    val primaryL = if (isLight) (secondaryL - 0.35f).coerceAtLeast(0.2f)
    else (secondaryL + 0.35f).coerceAtMost(0.8f)
    val primary = hslToColor(h, s, primaryL)
    return primary to secondary
}

/** 封面 → 歌词/SeekBar 配色 (原版 updateLrcColor 的完整链路, 全端共享)。 */
fun ImageBitmap.representativeLrcColors(): Pair<Color, Color> =
    adjustLrcColors(representativeColorOf(ImageBitmapPixelReader(this)))

/**
 * 封面取色状态 (全端共享): 封面 URL 变化时经 [BookImageLoaders] 加载封面,
 * 计算 [representativeLrcColors]; 未注册 loader / 加载失败 / 无封面 → null (用原版默认色)。
 * 对照原版 AudioPlayActivity.updateCover → updateLrcColor 链路。
 */
@Composable
fun rememberLrcColors(coverUrl: String?, sourceOrigin: String? = null): Pair<Color, Color>? {
    var colors by remember(coverUrl) { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(coverUrl, sourceOrigin) {
        colors = null
        if (coverUrl.isNullOrBlank()) return@LaunchedEffect
        val loader = BookImageLoaders.getOrNull() ?: return@LaunchedEffect
        val bitmap = loader.loadCoverOrNull(coverUrl, sourceOrigin)
        if (bitmap != null) colors = bitmap.representativeLrcColors()
    }
    return colors
}

// ---- RGB ↔ HSL (标准转换, 对照 android.graphics.ColorUtils 的 HSL 空间) ----

private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val maxC = max(r, max(g, b))
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    var h = 0f
    when (maxC) {
        r -> h = ((g - b) / delta).mod(6f)
        g -> h = (b - r) / delta + 2f
        b -> h = (r - g) / delta + 4f
    }
    h *= 60f
    if (h < 0) h += 360f
    val l = (maxC + minC) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
    return Triple(h, s, l)
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h / 60f).mod(6f)
    val x = c * (1f - abs(hp.mod(2f) - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(red = r1 + m, green = g1 + m, blue = b1 + m)
}
