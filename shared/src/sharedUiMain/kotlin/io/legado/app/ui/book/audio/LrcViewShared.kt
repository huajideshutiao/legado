package io.legado.app.ui.book.audio

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * | 拖动: scrollYOffset+dY (GestureDetector dY=下滑为负, 内容跟手) | scrollY-dy (dy=手指位移下滑为正, 取负; 首帧含 slop 段) |
 * | 滚轮: AXIS_VSCROLL*lineMargin*3, 上滚看前(减) | 同 (Scroll 事件跨平台, 方向语义等价) |
 * | fling: OverScroller 物理衰减 + min/maxFlingVelocity 门限 | ScrollableDefaults.flingBehavior() (Android 端同一套 AOSP spline) + 同门限 |
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
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlopPx = viewConfiguration.touchSlop

    // 行模型 (复刻原版 LrcLine: time/layout/height/offset)
    class LrcLine(
        val time: Int,
        val layout: TextLayoutResult,
        val height: Int,
        val offset: Float,
    )

    var viewportW by remember { mutableIntStateOf(0) }
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
    // 拖动中标记: 原版只在 ACTION_UP postDelayed(autoResetRunnable), onScroll 仅 removeCallbacks,
    // 故长拖 (>5s) 期间不会中途自动回中
    var dragging by remember { mutableStateOf(false) }

    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var colorJob by remember { mutableStateOf<Job?>(null) }

    val fontSize = 20.sp
    val lineMarginPx = with(LocalDensity.current) { 20.dp.toPx() }

    // 测量 (复刻 prepareLayouts; 数据/宽度/测量环境变化时重建。
    // textMeasurer 由 density/fontScale/layoutDirection/字体解析器 remember 而来, 拿它当 key
    // 就覆盖了 lineMarginPx 与 sp→px 的换算依赖)
    LaunchedEffect(lrcData, viewportW, textMeasurer) {
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
            LrcLine(time, layout, h, offset).also { offset += h }
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

    // 手动滚动 5 秒后自动回中 (复刻 autoResetRunnable)。
    // manualTick 走 snapshotFlow 而不是当 effect key: 滚轮每个 tick 都 ++, 当 key 会让整个控件
    // 跟着重组; collectLatest 天然实现"新的手动滚动重启计时" (复刻 removeCallbacks+postDelayed)
    LaunchedEffect(Unit) {
        snapshotFlow { manualTick }.collectLatest { tick ->
            if (autoScroll || tick == 0 || dragging) return@collectLatest
            delay(5000)
            autoScroll = true
            val idx = currentIndex
            if (idx in lines.indices) {
                val target = lines[idx].offset + lines[idx].height / 2f
                scrollJob?.cancel()
                scrollJob = scope.launch {
                    animate(
                        scrollY,
                        target,
                        animationSpec = tween(600, easing = DECELERATE),
                    ) { v, _ -> scrollY = v }
                }
            }
        }
    }

    // pointerInput 在组合外执行, 直接读状态变量 (捕获的是 MutableState 对象, 取值恒最新)。
    // 不用 rememberUpdatedState 包一层: 那会让组合作用域订阅 scrollY, 滚动每帧都重组整个控件
    fun maxScrollY(): Float =
        lines.lastOrNull()?.let { it.offset + it.height / 2f } ?: 0f

    fun beginManualScroll() {
        autoScroll = false
        manualTick++
        scrollJob?.cancel()
        colorJob?.cancel()
        colorProgress = 1f
    }

    // 惯性滑动状态: flingBehavior 的驱动目标 (ScrollableState 薄封装 scrollY)。
    // consumeScrollDelta 必须返回实际消费量 (new - old); 越界时未消费部分由 FlingBehavior 自然停止
    // (复刻 OverScroller 到达 min/max 即停)。
    val flingScrollState = rememberScrollableState { delta ->
        val old = scrollY
        val max = maxScrollY()
        val new = (old + delta).coerceIn(0f, max)
        scrollY = new
        new - old
    }
    // 平台默认惯性曲线 (spline 衰减; Android 与 OverScroller 同源物理, 密度经 LocalDensity 解析)
    val flingBehavior = ScrollableDefaults.flingBehavior()

    Canvas(
        modifier
            // 只有测量要重排才需要宽度进组合; 高度/命中判定直接用手势与绘制作用域自带的 size
            .onSizeChanged { viewportW = it.width }
            // 点击/拖动/fling (复刻 GestureDetector: onScroll/onFling/onSingleTapUp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 复刻原版 onDown 的 forceFinished: 触摸立即停掉进行中的 fling (spline 惯性时长 1~3s,
                    // 不中断会残留滑动)
                    scrollJob?.cancel()
                    val velocityTracker = VelocityTracker()
                    // 速度采样必须走 addPointerInputChange (DOWN + 全部 MOVE): 它会把
                    // MotionEvent 批处理的 historical 采样点一并计入
                    velocityTracker.addPointerInputChange(down)
                    var dragged = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 每一帧统一追踪速度采样点，避免遗漏 touchSlop 判定阶段的位移数据
                            velocityTracker.addPointerInputChange(change)
                            // 跨平台判断位置变化 (positionChanged 是 Android 专属扩展)
                            if (change.position != change.previousPosition) {
                                val totalDy = change.position.y - down.position.y
                                if (!dragged && abs(totalDy) > touchSlopPx) {
                                    // 越过 touch slop 进入拖动 (原版 onScroll)
                                    dragged = true
                                    dragging = true
                                    beginManualScroll()
                                    // 原版首个 onScroll 的 distanceY 从 DOWN 点算起且不扣 slop
                                    // (GestureDetector.java:742 mLastFocus* 仍停在 DOWN),
                                    // 只吃增量会留一个 slop 宽的起手死区
                                    scrollY = (scrollY - totalDy).coerceIn(0f, maxScrollY())
                                    change.consume()
                                } else if (dragged) {
                                    // 原版 GestureDetector.distanceY = mLastFocusY - focusY (下滑为负,
                                    // 内容跟手); 此处 dy 为手指位移 (下滑为正), 取负对齐
                                    val dy = change.position.y - change.previousPosition.y
                                    scrollY = (scrollY - dy).coerceIn(0f, maxScrollY())
                                    change.consume()
                                }
                            }
                            when (event.type) {
                                PointerEventType.Release -> {
                                    if (dragged) {
                                        // 松手 fling: 平台默认 spline 衰减 (Android 端即
                                        // OverScroller 同一套物理)。速度取负: 拖动中 scrollY 与
                                        // 手指位移反号
                                        // 上下限同原版 GestureDetector (computeCurrentVelocity 按
                                        // maximumFlingVelocity 截顶, 低于 minimum 不 fling);
                                        // 非 Android 端两值默认 MAX/0 即不设门限
                                        val maxV = viewConfiguration.maximumFlingVelocity
                                        val velocity =
                                            velocityTracker.calculateVelocity(Velocity(maxV, maxV)).y
                                        if (abs(velocity) > viewConfiguration.minimumFlingVelocity) {
                                            scrollJob?.cancel()
                                            scrollJob = scope.launch {
                                                flingScrollState.scroll {
                                                    // with() 显式 dispatch receiver (同 MangaRenderState.flingAfterMouseDrag
                                                    // 已验证模式: 成员扩展 performFling 需要外层 ScrollScope + FlingBehavior receiver)
                                                    with(flingBehavior) { performFling(-velocity) }
                                                }
                                            }
                                        }
                                    } else {
                                        // 点击行跳转 (复刻 onSingleTapUp 二分定位)
                                        if (lines.isNotEmpty()) {
                                            val touchY =
                                                scrollY + change.position.y - size.height / 2f
                                            val idx = lines.binarySearch { line ->
                                                if (touchY < line.offset) 1
                                                else if (touchY >= line.offset + line.height) -1
                                                else 0
                                            }
                                            if (idx >= 0) {
                                                val line = lines[idx]
                                                // 点击宽度只限文本实际宽度 (用户拍板 2026-08):
                                                // 水平 = 文本宽, 文本两侧空白不触发跳转;
                                                // 垂直保持整行命中 (行高收窄会难受, 用户拍板)
                                                val textWidth = line.layout.size.width
                                                val dx = change.position.x - size.width / 2f
                                                if (dx in -textWidth / 2f..textWidth / 2f) {
                                                    onLineClick(line.time)
                                                }
                                            }
                                        }
                                    }
                                    break
                                }

                                // CMP PointerEventType 无 Cancel 成员 (javap 证实 1.10.1 仅
                                // Press/Release/Move/Enter/Exit/Scroll/Key/DragStart/DragStop);
                                // 手势取消由 awaitEachGesture 协程取消自然结束循环
                                else -> Unit
                            }
                        }
                    } finally {
                        // 复刻原版 ACTION_UP/ACTION_CANCEL: 手势收尾重启 5s 自动回中计时。
                        // 放 finally 里, 手势被取消也不会把 dragging 卡在 true
                        dragging = false
                        manualTick++
                    }
                }
            }
            // 滚轮 (复刻 onGenericMotionEvent: 滚动量 = AXIS_VSCROLL * lineMargin * 3;
            // Scroll 事件 delta>0 = 向下滚(看后面) = scrollY 增大, 与原版 VSCROLL>0=上滚(看前面)=减小 语义等价)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (delta != 0f && lines.isNotEmpty()) {
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
            val layout = line.layout
            // 透明度 (复刻 calculateAlpha: 上下 0.35h 边界线性 1→40/255)
            val fade = calculateAlpha(lineCenterY, size.height)

            scale(scaleFactor, scaleFactor, pivot = Offset(contentCenterX, lineCenterY)) {
                val textY = lineY + (line.height - layout.size.height) / 2f
                drawText(
                    textLayoutResult = layout,
                    // 原版: withTranslation(contentCenterX - layout.width / 2f, layoutY)
                    // —— Compose 文本块宽度自适应 (短文本 < 视口宽), 需按块宽居中
                    color = baseColor.copy(alpha = baseColor.alpha * fade),
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

/** 复刻原版 calculateAlpha: 距视口中心超过 35% 高度的行线性渐隐到 40/255。 */
private fun calculateAlpha(lineCenterY: Float, viewportHeight: Float): Float {
    val fadeBoundary = viewportHeight * 0.35f
    return when {
        lineCenterY < fadeBoundary -> (lineCenterY / fadeBoundary).coerceIn(40f / 255f, 1f)
        lineCenterY > viewportHeight - fadeBoundary ->
            ((viewportHeight - lineCenterY) / fadeBoundary).coerceIn(40f / 255f, 1f)

        else -> 1f
    }
}

// ==================== 封面取色 (复刻原版 getRepresentativeColor + updateLrcColor) ====================

/**
 * 复刻原版 `Bitmap.getRepresentativeColor` (BitmapUtils.kt):
 * - 缩放到 64px 最长边 (加载时已按 64px 请求, 这里的采样步长兜住更大的图)
 * - 过滤: alpha < 128 跳过; HSL 饱和度 <0.1 或 亮度 <0.1 或 >0.9 跳过
 * - 平均 RGB; 无有效像素时返回中心像素
 *
 * 一次性整块读回 (逐行 readPixels 在安卓端对 HARDWARE 位图每次都要整图拷贝), 通道走位运算,
 * HSL 复用同一个 FloatArray —— 与原版逐行等价且采样过程零分配。
 */
private fun ImageBitmap.representativeColor(): Color {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    val step = max(1, (max(width, height) / 64f).roundToInt())
    val hsl = FloatArray(3)
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    var y = 0
    while (y < height) {
        val rowStart = y * width
        var x = 0
        while (x < width) {
            val pixel = pixels[rowStart + x]
            if ((pixel ushr 24) >= 128) { // 原版 (pixel shr 24) and 0xFF >= 128
                val r = ColorUtils.red(pixel)
                val g = ColorUtils.green(pixel)
                val b = ColorUtils.blue(pixel)
                ColorUtils.RGBToHSL(r, g, b, hsl)
                if (hsl[1] >= 0.1f && hsl[2] >= 0.1f && hsl[2] <= 0.9f) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                }
            }
            x += step
        }
        y += step
    }
    // 全是黑白/透明时取中心像素 (复刻原版 pixels[size / 2])
    if (count == 0) return Color(pixels[pixels.size / 2])
    return Color(
        ColorUtils.argb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    )
}

/**
 * 复刻原版 `AudioPlayActivity.updateLrcColor`:
 * - meanColor → HSL; isLight = L > 0.6
 * - secondary: light → L-0.45 (下限 0.3); dark → L+0.45 (上限 0.7)
 * - primary: 基于 secondary 的 L 再 ±0.35 (下限 0.2 / 上限 0.8)
 * - 输出不透明色 (HSLToColor), 供歌词 setColors + SeekBar tint
 */
private fun adjustLrcColors(meanColor: Color): Pair<Color, Color> {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(meanColor.toArgb(), hsl)
    val l = hsl[2]
    val isLight = l > 0.6f
    val secondaryL = if (isLight) (l - 0.45f).coerceAtLeast(0.3f)
    else (l + 0.45f).coerceAtMost(0.7f)
    hsl[2] = secondaryL
    val secondary = Color(ColorUtils.HSLToColor(hsl))
    hsl[2] = if (isLight) (secondaryL - 0.35f).coerceAtLeast(0.2f)
    else (secondaryL + 0.35f).coerceAtMost(0.8f)
    val primary = Color(ColorUtils.HSLToColor(hsl))
    return primary to secondary
}

/** 封面 → 歌词/SeekBar 配色 (原版 updateLrcColor 的完整链路, 全端共享)。 */
private fun ImageBitmap.representativeLrcColors(): Pair<Color, Color> =
    adjustLrcColors(representativeColor())

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
        // 取色只要 64px 级别的小图 (原版 getRepresentativeColor 也是先缩到 64px 最长边):
        // 按原图请求会让像素读回整图搬一遍 (安卓端 Coil3 默认给 HARDWARE 位图, 读回还要先整图拷贝),
        // 而采样实际只用到几千个像素。磁盘缓存键只按 url, 不会多下载一次
        val bitmap = loader.loadCoverOrNull(coverUrl, sourceOrigin, widthPx = 64, heightPx = 64)
            ?: return@LaunchedEffect
        colors = withContext(Dispatchers.Default) { bitmap.representativeLrcColors() }
    }
    return colors
}

