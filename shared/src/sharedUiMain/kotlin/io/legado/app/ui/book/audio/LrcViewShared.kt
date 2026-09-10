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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.Lrc
import io.legado.app.model.LrcLine
import io.legado.app.model.LrcWord
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.CoroutineScope
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
 * # 对照表 (原版 → 本实现, 除注明"刻意不复刻"处外参数/时序/插值一致)
 * | 原版 | 本实现 |
 * |------|--------|
 * | paint.textSize = 20.dpToPx() | fontSize = 20.sp (默认 fontScale=1, sp==dp) |
 * | lineMargin = 20.dpToPx() | lineMarginPx = 20.dp.toPx() |
 * | availableWidth = 宽 - paddingStart/End (16dp), 裁剪在 View 全宽 | 同 ([H_PADDING] 在控件内, clipToBounds 在全宽) |
 * | 行高 = StaticLayout.height + lineMargin | 行高 = layout.size.height + lineMarginPx |
 * | offset 累加 (prepareLayouts) | 同 |
 * | ALIGN_CENTER + lineSpacing(0,1) + includePad(false) | TextAlign.Center |
 * | scrollYOffset = 当前行中心 | scrollY = lines[i].offset + height/2 |
 * | 绘制 lineY = centerY + (line.offset - scrollYOffset) | 同 |
 * | updateProgress: lastIndex/currentIndex/colorProgress=0 | 同 |
 * | 首次 (lastIndex==-1) 无动画直接定位 | 同 |
 * | 切行滚动 startScroll(600ms, DecelerateInterpolator) | tween(600, [DECELERATE]) |
 * | DecelerateInterpolator: 1-(1-t)^2 | [DECELERATE] = Easing { 1f-(1f-it)*(1f-it) } |
 * | colorProgress 每帧 +0.1 (帧数驱动, 时长随刷新率变) | tween(100) 固定时长, 刻意不复刻 |
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
    lrcData: Lrc?,
    lrcProgress: Int,
    primaryColor: Color,
    secondaryColor: Color,
    onLineClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val lineMarginPx = with(LocalDensity.current) { LINE_MARGIN.toPx() }
    val hPaddingPx = with(LocalDensity.current) { H_PADDING.toPx() }

    var viewportW by remember { mutableIntStateOf(0) }
    val runtimeKey = resetKey ?: (lrcData to AudioPlayShared.durChapterIndex)
    // 每份歌词一套运行态: 数据变化即整体复位 (等价原版 setLrcData 的重置)
    val lrc = remember(runtimeKey) { LrcRuntime() }

    // 运行态换新或控件退出时，显式取消旧对象的动画 Job，避免泄漏
    DisposableEffect(lrc) {
        onDispose {
            lrc.cancelJobs()
        }
    }

    // 纯排版测量与滚动运行态分离: remember 内只执行纯计算并返回不可变行列表,
    // 严禁在 remember 期间产生取消动画或写 Snapshot 状态等副作用。
    // textMeasurer 随 density/fontScale/layoutDirection/字体解析器重建, 拿它当 key 就覆盖了
    // lineMarginPx 与 sp→px 的换算依赖。
    // lrcProgress 刻意不当 key: 只需要重排那一刻的值用于定位, 加进 key 会让每次切行都重测全部行
    val lines = remember(lrcData, viewportW, textMeasurer) {
        // 左右留白就是当前行 1.05 倍缩放的溢出余量 (复刻原版 availableWidth = 宽 - 左右 padding,
        // 裁剪留在全宽); 16dp 兜不住 5% 的超宽面板按视口比例抬升
        val inset = max(hPaddingPx, viewportW * SCALE_INSET_RATIO)
        measureLrcLines(
            lrcData, (viewportW - inset * 2).roundToInt(), textMeasurer, lineMarginPx
        )
    }

    // 排版结果到达后同步给运行态; 当视口重排且处于 autoScroll 时，校正回当前行中心
    LaunchedEffect(lines) {
        lrc.lines = lines
        if (lrc.autoScroll && lines.isNotEmpty() && lrc.currentIndex in lines.indices) {
            lrc.scrollJob?.cancel()
            lrc.scrollY = lrc.centerOf(lrc.currentIndex)
        }
    }

    // 切行 (复刻 updateProgress)。lines 也当 key: 首次测出行高那帧要把 lrcProgress 重放一遍
    LaunchedEffect(lrcProgress, lines) {
        lrc.lines = lines
        if (lrcProgress !in lines.indices || lrcProgress == lrc.currentIndex) {
            return@LaunchedEffect
        }
        lrc.lastIndex = lrc.currentIndex
        lrc.currentIndex = lrcProgress
        lrc.colorProgress = 0f
        lrc.autoScroll = true
        // 切行取消 pending 自动回中 (复刻 removeCallbacks(autoResetRunnable))
        lrc.manualTick++
        val target = lrc.centerOf(lrcProgress)
        if (lrc.lastIndex == -1) {
            // 首次: 无动画直接定位
            lrc.scrollJob?.cancel()
            lrc.scrollY = target
        } else {
            lrc.scrollTo(target, scope)
        }
        // 切行的颜色/缩放: 固定 100ms (原版是每帧 +0.1 的帧数驱动, 时长随刷新率变, 刻意不复刻)
        lrc.colorJob?.cancel()
        lrc.colorJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(100)) { v, _ -> lrc.colorProgress = v }
        }
    }

    // 逐字填充的帧驱动: 只有当前行带字标签时才逐帧刷新时钟, 普通歌词一帧都不多画。
    // 与 rememberLrcIndex 同一个原则 —— 时钟真源仍是播放引擎, 填充位置在绘制那一帧才求值,
    // 所以这里只把采样发出去触发重绘, 换行那一帧读到上一帧的时钟也只是"还没开始填", 不会闪。
    // currentIndex 走 snapshotFlow 而不是当 effect key: 它刻意不进组合, 当 key 会让每次切行都重组
    LaunchedEffect(lines) {
        snapshotFlow { lrc.currentIndex }.collectLatest { index ->
            if (lines.getOrNull(index)?.words.isNullOrEmpty()) return@collectLatest
            while (true) {
                lrc.fillClockMs = positionNowMs()
                withFrameNanos { }
            }
        }
    }

    // 手动滚动 5 秒后自动回中 (复刻 autoResetRunnable)。
    // manualTick 走 snapshotFlow 而不是当 effect key: 滚轮每个 tick 都 ++, 当 key 会让整个控件
    // 跟着重组; collectLatest 天然实现"新的手动滚动重启计时" (复刻 removeCallbacks+postDelayed)
    LaunchedEffect(lrc) {
        snapshotFlow { lrc.manualTick }.collectLatest { tick ->
            if (lrc.autoScroll || tick == 0 || lrc.dragging) return@collectLatest
            delay(5000)
            lrc.autoScroll = true
            if (lrc.currentIndex in lrc.lines.indices) {
                lrc.scrollTo(lrc.centerOf(lrc.currentIndex), scope)
            }
        }
    }

    // 惯性滑动状态: flingBehavior 的驱动目标 (ScrollableState 薄封装 scrollY)。
    // consumeScrollDelta 必须返回实际消费量 (new - old); 越界时未消费部分由 FlingBehavior 自然停止
    // (复刻 OverScroller 到达 min/max 即停)。
    val flingScrollState = rememberScrollableState { delta ->
        val old = lrc.scrollY
        lrc.scrollBy(delta)
        lrc.scrollY - old
    }
    // 平台默认惯性曲线 (spline 衰减; Android 与 OverScroller 同源物理, 密度经 LocalDensity 解析)
    val flingBehavior = ScrollableDefaults.flingBehavior()

    Canvas(
        modifier
            // 裁到自身边界: 当前行之上的歌词行会画到负 Y (原版 LrcView 是 View, 天然裁剪),
            // 不裁就会画到控件上方压住圆形封面。左右留白在控件内 (见 [H_PADDING]), 所以当前行
            // 的缩放溢出画在留白里, 不会被这里裁掉
            .clipToBounds()
            // 只有测量要重排才需要宽度进组合; 高度/命中判定直接用手势与绘制作用域自带的 size
            .onSizeChanged { viewportW = it.width }
            // 点击/拖动/fling (复刻 GestureDetector: onScroll/onFling/onSingleTapUp)。
            // key 用 lrc: 换歌后运行态整体换新, 手势协程必须重新绑定
            .pointerInput(lrc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 复刻原版 onDown 的 forceFinished: 触摸立即停掉进行中的 fling (spline 惯性时长 1~3s,
                    // 不中断会残留滑动)
                    lrc.scrollJob?.cancel()
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
                                if (!dragged && abs(totalDy) > viewConfiguration.touchSlop) {
                                    // 越过 touch slop 进入拖动 (原版 onScroll)
                                    dragged = true
                                    lrc.dragging = true
                                    lrc.beginManualScroll()
                                    // 原版首个 onScroll 的 distanceY 从 DOWN 点算起且不扣 slop
                                    // (GestureDetector.java:742 mLastFocus* 仍停在 DOWN),
                                    // 只吃增量会留一个 slop 宽的起手死区
                                    lrc.scrollBy(-totalDy)
                                    change.consume()
                                } else if (dragged) {
                                    // 原版 GestureDetector.distanceY = mLastFocusY - focusY (下滑为负,
                                    // 内容跟手); 此处 dy 为手指位移 (下滑为正), 取负对齐
                                    lrc.scrollBy(change.previousPosition.y - change.position.y)
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
                                            lrc.scrollJob?.cancel()
                                            lrc.scrollJob = scope.launch {
                                                flingScrollState.scroll {
                                                    // with() 显式 dispatch receiver (同 MangaRenderState.flingAfterMouseDrag
                                                    // 已验证模式: 成员扩展 performFling 需要外层 ScrollScope + FlingBehavior receiver)
                                                    with(flingBehavior) { performFling(-velocity) }
                                                }
                                            }
                                        }
                                    } else {
                                        // 点击行跳转 (复刻 onSingleTapUp 二分定位)
                                        val lines = lrc.lines
                                        val touchY = lrc.scrollY + change.position.y - size.height / 2f
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
                        lrc.dragging = false
                        lrc.manualTick++
                    }
                }
            }
            // 滚轮 (复刻 onGenericMotionEvent: 滚动量 = AXIS_VSCROLL * lineMargin * 3;
            // Scroll 事件 delta>0 = 向下滚(看后面) = scrollY 增大, 与原版 VSCROLL>0=上滚(看前面)=减小 语义等价)
            .pointerInput(lrc) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.scrollDelta.y != 0f && lrc.lines.isNotEmpty()) {
                            lrc.beginManualScroll()
                            lrc.scrollBy(change.scrollDelta.y * lineMarginPx * 3)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        if (lines.isEmpty()) return@Canvas
        val centerY = size.height / 2f
        val contentCenterX = size.width / 2f
        val scrollY = lrc.scrollY
        val currentIndex = lrc.currentIndex
        val lastIndex = lrc.lastIndex
        val colorProgress = lrc.colorProgress
        val fillClockMs = lrc.fillClockMs

        // 二分定位首个可见行 (复刻原版 firstVisible)
        val viewTop = scrollY - centerY
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
            // 原版: withTranslation(contentCenterX - layout.width / 2f, layoutY)
            // —— Compose 文本块宽度自适应 (短文本 < 视口宽), 需按块宽居中
            val topLeft = Offset(
                contentCenterX - layout.size.width / 2f,
                lineY + (line.height - layout.size.height) / 2f,
            )
            // 透明度 (复刻 calculateAlpha: 上下 0.35h 边界线性 1→40/255), 交给 drawText 内部 modulate
            val fade = calculateAlpha(lineCenterY, size.height)

            // 逐字行: 未唱的部分留在 secondaryColor, 已唱的前缀盖 primaryColor;
            // 整行 secondary→primary 的渐变只留给没有字标签的歌词, 否则会把填充对比冲掉
            val karaoke = isCurrent && line.words.isNotEmpty()
            val bodyColor = if (karaoke) secondaryColor else baseColor
            val sungChars = if (karaoke) line.charProgressAt(fillClockMs + Lrc.OFFSET_MS) else 0f
            // 只有当前/上一行会缩放, 其余行跳过 scale{} 的 save/concat/restore
            if (scaleFactor == 1f) {
                drawLrcLine(line, topLeft, bodyColor, fade, sungChars, primaryColor)
            } else {
                scale(scaleFactor, scaleFactor, pivot = Offset(contentCenterX, lineCenterY)) {
                    drawLrcLine(line, topLeft, bodyColor, fade, sungChars, primaryColor)
                }
            }
        }
    }
}

/**
 * 当前高亮行 (帧驱动派生, 喂给 [LrcViewShared] 的 lrcProgress)。
 *
 * 在要绘制的那一帧直接问播放引擎位置再二分定位: 引擎仍是唯一时钟真源, 只是把求值推迟到绘制时。
 * 于是既没有跨层推送的陈旧索引 (进界面 / 切歌不会先跳到旧行再滚过来), 也不存在调度迟到 ——
 * 比一帧更精细的高亮时机在屏幕上本就不可观测。
 *
 * 只有行变化时才写 state, 所以每帧只做"一次位置读 + 二分", 不产生重组或重绘; 界面不可见时
 * Compose 帧时钟暂停, 循环自然停摆, 不需要额外的订阅门控。暂停时也照常求值 —— 暂停中拖进度条
 * 要能带动高亮。
 *
 * 对外发布 (车载歌词) 是另一个消费者, 走 [io.legado.app.model.audio.LyricPublisher]:
 * 它需要息屏后台也推进, 但共用 [Lrc.indexAt] 同一份判定。
 */
@Composable
fun rememberLrcIndex(lrcData: Lrc?, resetKey: Any? = null): Int {
    // 初值就地算出来 (不等 LaunchedEffect 的下一帧): 换歌那一帧 LrcViewShared 就能按正确的行定位,
    // 否则会先居中第一行, 下一帧再 snap 过去 —— 肉眼是闪一下
    val key = resetKey ?: (lrcData to AudioPlayShared.durChapterIndex)
    var index by remember(key) { mutableIntStateOf(lrcData.indexNow()) }
    LaunchedEffect(key) {
        if (lrcData == null || !lrcData.hasTimeline) return@LaunchedEffect
        while (true) {
            index = lrcData.indexNow()
            withFrameNanos { }
        }
    }
    return index
}

/** 按引擎当前位置定位高亮行; 无歌词/无时间轴时 -1。 */
private fun Lrc?.indexNow(): Int {
    if (this == null || !hasTimeline) return -1
    return indexAt(positionNowMs() + Lrc.OFFSET_MS)
}

/** 引擎当前位置; 引擎未就绪时用最近保存的章节进度。 */
private fun positionNowMs(): Int =
    AudioPlayCommanders.getOrNull()?.positionMs ?: AudioPlayShared.durChapterPos

private val FONT_SIZE = 20.sp   // 原版 paint.textSize = 20.dpToPx() (fontScale=1 时 sp==dp)
private val LINE_MARGIN = 20.dp // 原版 lineMargin = 20.dp.toPx()
private val H_PADDING = 16.dp   // 原版 iv_lrc paddingStart/End (arco_spacing_lg)

/** 当前行放大到 1.05 倍, 每侧溢出 2.5% 行宽; [H_PADDING] 兜不住的宽面板按这个比例留余量。 */
private const val SCALE_INSET_RATIO = 0.025f

/** 测量后的行 (复刻原版 LrcView.LrcLine); [words] 是逐字采样, 空表示这行整行高亮。 */
private class MeasuredLrcLine(
    val time: Int,
    val layout: TextLayoutResult,
    val height: Int,
    val offset: Float,
    val words: List<LrcWord>,
)

/**
 * 逐字采样 + 一个收尾采样: Enhanced LRC 只标每个字的起点, 末字没有终点, 用下一行的时间戳补上。
 * 终点是首语种正文的末尾 —— 译文是 `\n` 之后追加的, 不参与填充。
 * 末行无下一行时间戳时，根据本行字符节奏预估终点时间，确保播放完毕时末尾字符能被点亮。
 */
private fun LrcLine.fillSamples(nextTimeMs: Int?): List<LrcWord> {
    if (words.isEmpty()) return emptyList()
    val end = text.indexOf('\n').let { if (it < 0) text.length else it }
    val lastWord = words.last()
    if (lastWord.charIndex >= end) return words
    val closingTimeMs = if (nextTimeMs != null) {
        nextTimeMs.coerceAtLeast(lastWord.timeMs)
    } else {
        val remainingChars = end - lastWord.charIndex
        val charSpan = if (words.size > 1) {
            val charDistance = (lastWord.charIndex - words.first().charIndex).coerceAtLeast(1)
            ((lastWord.timeMs - words.first().timeMs) / charDistance).coerceIn(200, 2000)
        } else {
            500
        }
        lastWord.timeMs + remainingChars * charSpan
    }
    return words + LrcWord(closingTimeMs, end)
}

/** [positionMs] 处已唱到第几个字符 (含小数); 还没到首个采样时 0。 */
private fun MeasuredLrcLine.charProgressAt(positionMs: Int): Float {
    var i = -1
    while (i + 1 <= words.lastIndex && words[i + 1].timeMs <= positionMs) i++
    if (i < 0) return 0f
    val from = words[i]
    val to = words.getOrNull(i + 1) ?: return from.charIndex.toFloat()
    val span = to.timeMs - from.timeMs
    if (span <= 0) return to.charIndex.toFloat()
    val t = ((positionMs - from.timeMs).toFloat() / span).coerceIn(0f, 1f)
    return from.charIndex + (to.charIndex - from.charIndex) * t
}

/**
 * 画一行歌词: [bodyColor] 打底, 再把前 [sungChars] 个字符 (含小数) 用 [sungColor] 盖一层。
 * [sungChars] 不为正时只打底, 与逐字歌词落地前逐像素一致。
 */
private fun DrawScope.drawLrcLine(
    line: MeasuredLrcLine,
    topLeft: Offset,
    bodyColor: Color,
    fade: Float,
    sungChars: Float,
    sungColor: Color,
) {
    val layout = line.layout
    drawText(layout, bodyColor, topLeft, alpha = fade)
    if (sungChars <= 0f) return
    val boundary = sungChars.toInt()
    if (boundary >= layout.layoutInput.text.length) {
        drawText(layout, sungColor, topLeft, alpha = fade)
        return
    }
    val visualLine = layout.getLineForOffset(boundary)
    val box = layout.getBoundingBox(boundary)
    // 已唱满的可视行整段盖掉 (长行换行与双语的第二段都靠这个边界分开)
    if (visualLine > 0) {
        clipRect(top = topLeft.y, bottom = topLeft.y + layout.getLineTop(visualLine)) {
            drawText(layout, sungColor, topLeft, alpha = fade)
        }
    }
    clipRect(
        right = topLeft.x + box.left + box.width * (sungChars - boundary),
        top = topLeft.y + layout.getLineTop(visualLine),
        bottom = topLeft.y + layout.getLineBottom(visualLine),
    ) {
        drawText(layout, sungColor, topLeft, alpha = fade)
    }
}

/**
 * 歌词运行态 (复刻原版 LrcView 的可变字段): 滚动/高亮/动画。滚动与颜色进度只被绘制、手势与
 * 协程读写, 组合里不读 —— 所以每帧只失效绘制不重组。每份歌词数据一个实例, 数据变化即整体
 * 复位 (等价原版 setLrcData 的重置)。
 */
private class LrcRuntime {
    /** 测量结果; 只在组合期由 [layout] 整体替换, 所以绘制直接用它的返回值即可 */
    var lines: List<MeasuredLrcLine> = emptyList()
    var currentIndex by mutableIntStateOf(-1)
    var lastIndex by mutableIntStateOf(-1)
    var colorProgress by mutableFloatStateOf(1f)
    /** 逐字填充用的时钟采样; 只有当前行带字标签时才逐帧刷新, 其余时候是陈旧值且无人读 */
    var fillClockMs by mutableIntStateOf(0)
    var scrollY by mutableFloatStateOf(0f)
    // 手动滚动计时信号 (每次 +1 重启 5s 回中; 复刻 removeCallbacks+postDelayed)
    var manualTick by mutableIntStateOf(0)
    var autoScroll = true
    /** 拖动中: 原版只在 ACTION_UP 才 postDelayed(autoResetRunnable), 故长拖期间不会中途回中 */
    var dragging = false
    var scrollJob: Job? = null
    var colorJob: Job? = null

    /** 行中心 (滚动定位基准, 复刻 scrollYOffset = 当前行中心) */
    fun centerOf(index: Int): Float = lines[index].let { it.offset + it.height / 2f }

    /** 600ms 减速滚动 (复刻 scroller.startScroll(..., 600) + DecelerateInterpolator) */
    fun scrollTo(target: Float, scope: CoroutineScope) {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            animate(scrollY, target, animationSpec = tween(600, easing = DECELERATE)) { v, _ ->
                scrollY = v
            }
        }
    }

    /** 手动滚动一段距离 (拖动/滚轮共用; 复刻 scrollYOffset 加减 + 边界钳制到末行中心) */
    fun scrollBy(delta: Float) {
        val max = if (lines.isEmpty()) 0f else centerOf(lines.lastIndex)
        scrollY = (scrollY + delta).coerceIn(0f, max)
    }

    /** 手动滚动起手 (复刻 onScroll: 停掉自动滚动与颜色动画, 重启回中计时) */
    fun beginManualScroll() {
        autoScroll = false
        manualTick++
        scrollJob?.cancel()
        colorJob?.cancel()
        colorProgress = 1f
    }

    fun cancelJobs() {
        scrollJob?.cancel()
        scrollJob = null
        colorJob?.cancel()
        colorJob = null
    }

    fun reset() {
        cancelJobs()
        currentIndex = -1
        lastIndex = -1
        colorProgress = 1f
        fillClockMs = 0
        scrollY = 0f
        manualTick = 0
        autoScroll = true
        dragging = false
    }
}

/**
 * 纯排版测量: 逐行计算 TextLayoutResult、行高与垂直偏移, 不修改任何运行态。
 *
 * @param availableW 扣除留白后的文本可用宽 (复刻原版 availableWidth)。
 */
private fun measureLrcLines(
    data: Lrc?,
    availableW: Int,
    measurer: TextMeasurer,
    marginPx: Float,
): List<MeasuredLrcLine> {
    val src = data?.lines
    if (availableW <= 0 || src.isNullOrEmpty()) return emptyList()
    val style = TextStyle(fontSize = FONT_SIZE, textAlign = TextAlign.Center)
    var offset = 0f
    return src.mapIndexed { i, line ->
        val layout = measurer.measure(
            line.text, style, constraints = Constraints(maxWidth = availableW)
        )
        MeasuredLrcLine(
            line.timeMs,
            layout,
            layout.size.height + marginPx.roundToInt(),
            offset,
            line.fillSamples(src.getOrNull(i + 1)?.timeMs),
        ).also { offset += it.height }
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
 * 封面取色状态 (全端共享): 封面 URL 变化时经 [BookImageLoaders] 加载封面, 计算
 * [representativeLrcColors]; 未注册 loader / 加载失败 / 无封面 → null (用原版默认色)。
 * 对照原版 AudioPlayActivity.updateCover → updateLrcColor 链路。
 *
 * keep-previous: 加载期间保留旧配色, 新色算出才换 (原版 updateLrcColor 无中间态, 不会先闪回默认色);
 * 但换到无封面/加载失败的书要回落默认色, 不能沿用上一本的配色 (同 SharedAudioCoverSlot 的语义)。
 */
@Composable
fun rememberLrcColors(coverUrl: String?, sourceOrigin: String? = null): Pair<Color, Color>? {
    var colors by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(coverUrl, sourceOrigin) {
        // 取色只要 64px 级别的小图 (原版 getRepresentativeColor 也是先缩到 64px 最长边):
        // 按原图请求会让像素读回整图搬一遍 (安卓端 Coil3 默认给 HARDWARE 位图, 读回还要先整图拷贝),
        // 而采样实际只用到几千个像素。磁盘缓存键只按 url, 不会多下载一次
        val bitmap = if (coverUrl.isNullOrBlank()) null else BookImageLoaders.getOrNull()
            ?.loadCoverOrNull(coverUrl, sourceOrigin, widthPx = 64, heightPx = 64)
        colors = bitmap?.let { withContext(Dispatchers.Default) { it.representativeLrcColors() } }
    }
    return colors
}

