package io.legado.app.ui.book.audio

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.Status
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.Lrc
import io.legado.app.model.LrcLine
import io.legado.app.model.LrcWord
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全端共享歌词控件: 逐项复刻原版 LrcView
 * (origin/quickjs app/src/main/java/io/legado/app/ui/widget/LrcView.kt)。
 *
 * Android 端原 LrcView.kt (AndroidView 包装) 已由本控件取代, 各端仅一份实现。
 *
 * # 与原版的差异
 * 排版与配色是本控件自有的一套, **刻意不复刻原版**: 大字左对齐、逐字字号放大、
 * 能量驱动的辉光、封面色高亮。原版的居中排版与整行颜色插值不再存在。
 *
 * # 与原文一致的部分 (时序/交互语义逐项对齐)
 * | 原版 | 本实现 |
 * |------|--------|
 * | 行高 = StaticLayout.height + lineMargin | 行高 = layout.size.height + lineMarginPx |
 * | offset 累加 (prepareLayouts) | 同 |
 * | scrollYOffset = 当前行中心 | scrollY = lines[i].offset + height/2 |
 * | 绘制 lineY = centerY + (line.offset - scrollYOffset) | lineY = centerY + (offset + 行高差累加 - scrollY) |
 * | updateProgress: lastIndex/currentIndex/colorProgress=0 | 同 |
 * | 首次 (lastIndex==-1) 无动画直接定位 | 同 |
 * | 切行滚动 startScroll(600ms, DecelerateInterpolator) | 同有切行滚动: 2.5D=400ms 的 Settle 曲线 (不复刻 600ms 减速) |
 * | colorProgress 每帧 +0.1 (帧数驱动, 时长随刷新率变) | 统一切行时间轴: 点亮 1.25D / 退场 1D, 刻意不复刻 |
 * | 透明度: 上下 0.35h 边界线性 255→40, 与景深相乘 | 同 (calculateAlpha) |
 * | 点击: touchY=scrollYOffset+y-h/2, 定位命中的行, 回调 time | 同 (touchSlop 判定 tap/drag; 行高不恒定时按显示行高逐行定位) |
 * | 拖动: scrollYOffset+dY (GestureDetector dY=下滑为负, 内容跟手) | scrollY-dy (dy=手指位移下滑为正, 取负; 首帧含 slop 段) |
 * | 滚轮: AXIS_VSCROLL*lineMargin*3, 上滚看前(减) | 同 (Scroll 事件跨平台, 方向语义等价) |
 * | fling: OverScroller 物理衰减 + min/maxFlingVelocity 门限 | ScrollableDefaults.flingBehavior() (Android 端同一套 AOSP spline) + 同门限 |
 * | 手动滚动 5s 后 autoScroll=true + 回中当前行 | 同 (manualTick 重置计时, 切行取消) |
 * | setLrcData: 重置 + 滚到第一行中心 | 单实例 + 数据变化显式 reset; 复位后由切行直接定位当前句 |
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
    isPlaying: Boolean = AudioPlayShared.status == Status.PLAY,
    playSpeed: Float = AudioPlayShared.playSpeed,
) {
    // 排版缓存: 当前行与淡出行的字号/颜色每帧连续变化, 每次都是新文本 (必然重测);
    // 大缓存留给基础排版, 避免被动画帧冲掉后重测
    val textMeasurer = rememberTextMeasurer(cacheSize = 128)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 墨水屏不挂能量驱动: 辉光是逐帧重栅格化的模糊阴影, 墨水屏既消耗电也拖影
    val eInk = LocalEInk.current
    val lineMarginPx = with(density) { LRC_LINE_MARGIN.toPx() }
    val hPaddingPx = with(density) { LRC_H_PADDING.toPx() }
    // 辉光半径随音频能量扩张的上下限 (绘制期按能量插值)
    val glowRadiusMinPx = with(density) { GLOW_MIN_RADIUS.toPx() }
    val glowRadiusMaxPx = with(density) { GLOW_MAX_RADIUS.toPx() }

    var viewportW by remember { mutableIntStateOf(0) }
    // 单实例运行态 (对齐原版 LrcView 的字段模型): 不随数据换实例, 复位走显式 reset。
    // 以换实例代替复位会让所有持旧引用的 effect/手势断供 (行表空 → 点击/拖动静默失效)
    val lrc = remember { LrcRuntime() }
    // 逐字填充的跳转目标追踪器: 与高亮调度同一口径 (跳转当帧按目标定位), 独立一份纪元记忆
    val fillSeekTracker = remember { AudioPlayShared.SeekTargetTracker() }

    // 观察底层真实物理音频 PCM 能量 (只写入 lrc.energy, 杜绝根层重组闪烁)
    LaunchedEffect(isPlaying, eInk) {
        if (!isPlaying || eInk) {
            lrc.energy = 0f
            return@LaunchedEffect
        }
        AudioPlayShared.audioEnergy.collect {
            // 记下派发时刻: 绘制期据此判断音频管道是否还在供数 (断供才衰减)
            lrc.energy = it
            lrc.energyAtMs = positionNowMs()
        }
    }

    // 能量帧循环: 绘制期只有平滑后的能量一个输入 (见 [LrcRuntime.smoothEnergy])。
    // 逐字歌词本来就有帧驱动, 但整行高亮 (无字标签) 的普通歌词平时一帧都不刷 ——
    // 不挂这个循环, 能量就只会在每次 PCM 派发那一帧跳一格 (既不连续又会闪)。
    LaunchedEffect(isPlaying, eInk) {
        if (!isPlaying || eInk) return@LaunchedEffect
        var lastFrameNs = 0L
        while (isActive) {
            if (lrc.energy <= ENERGY_SILENT_THRESHOLD && lrc.smoothEnergy <= ENERGY_SILENT_THRESHOLD) {
                // 静音段: 平滑值已经落到地板, 低频推进即可 (省电)
                lrc.resetEnergyEnvelope()
                delay(ENERGY_IDLE_POLL_MS)
                continue
            }
            withFrameNanos { frameNs ->
                val dtMs = if (lastFrameNs == 0L) 16f
                else ((frameNs - lastFrameNs) / 1_000_000f).coerceIn(1f, 100f)
                lastFrameNs = frameNs
                lrc.advanceEnergy(dtMs, lrc.decayedEnergy(positionNowMs()))
            }
        }
    }

    // 控件退出时取消动画 Job，避免泄漏
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
    // 文本可用宽: 左对齐, 锚点在左侧, 左右各留 [LRC_H_PADDING] (放大靠重排, 不会溢出这一列宽)
    val availableW = remember(viewportW, hPaddingPx) {
        (viewportW - hPaddingPx * 2).roundToInt()
    }
    val lines = remember(lrcData, availableW, textMeasurer, lineMarginPx) {
        measureLrcLines(lrcData, availableW, textMeasurer, lineMarginPx, LRC_TEXT_STYLE)
    }

    // 歌词数据变化 → 显式复位 (复刻原版 setLrcData: 索引归位/停滚动动画/回中)。
    // 必须注册在下方两个 effect 之前: 同一组合内 effect 按出现顺序串行执行,
    // 同帧保证 "先复位 → 再喧行表 → 再按当前句定位" 的顺序
    LaunchedEffect(lrcData) {
        lrc.reset()
    }

    // 排版结果到达后同步给运行态; 当视口重排且处于 autoScroll 时，校正回当前行中心
    // (复刻原版 onSizeChanged: 只重排版不复位索引, 宽度变化保留 currentIndex)
    LaunchedEffect(lines) {
        lrc.lines = lines
        lrc.retainWrapSlides(-1, -1)
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
        lrc.lastProgress = 0f
        // 先把当前行唱到哪存下来, 供下一帧的"上一行淡出"使用
        lrc.prevSungProgress = lrc.maxSungProgress
        lrc.maxSungProgress = 0f
        lrc.fillClockMs = positionNowMs()
        lrc.autoScroll = true
        // 切行取消 pending 自动回中 (复刻 removeCallbacks(autoResetRunnable))
        lrc.manualTick++
        lrc.colorJob?.cancel()
        if (lrc.lastIndex == -1) {
            // 首次: 无动画直接定位并直接点亮
            lrc.scrollJob?.cancel()
            lrc.scrollY = lrc.centerOf(lrcProgress)
            lrc.applySwitchTimeline(SWITCH_TOTAL_MS)
        } else {
            // 非首次: 滚动、点亮、退场由同一条切行时间轴驱动
            lrc.scrollJob?.cancel()
            val fromScroll = lrc.scrollY
            val toScroll = lrc.centerOf(lrcProgress)
            val start = TimeSource.Monotonic.markNow()
            lrc.colorJob = scope.launch {
                while (isActive) {
                    val elapsedMs = start.elapsedNow().inWholeMilliseconds
                    val scrollProgress = (elapsedMs.toFloat() / SCROLL_MS).coerceIn(0f, 1f)
                    lrc.scrollY = fromScroll +
                        (toScroll - fromScroll) * MOTION_SETTLE.transform(scrollProgress)
                    lrc.applySwitchTimeline(elapsedMs)
                    if (elapsedMs >= SWITCH_TOTAL_MS) break
                    withFrameNanos { }
                }
            }
        }
    }

    // 逐字填充的帧驱动: 播放态门控 + 字词有效区间门控。
    // 1. 只有当前行带字标签时才激活, 普通歌词一帧都不画;
    // 2. 暂停/缓冲时停在当前采样点, 挂起不刷帧;
    // 3. 句前等待段 (前奏/空隙) 精准 delay 到首字开唱, 避免无效空转刷帧;
    // 4. 整句唱完后固定 100% 填满并挂起停帧, 避免等待下一行期间持续无效重绘;
    // 5. 仅在真正的唱词区间以 withFrameNanos 高刷驱动平滑变色;
    // 6. 跳转信号联动: 句内 seek 立即打断睡眠/停帧, 并按信号里的目标位置重定位。
    LaunchedEffect(lines, isPlaying, playSpeed) {
        // 输入 = 当前行 (快照状态) + 跳转信号 (StateFlow): 任一变化都重启本轮填充
        combine(
            snapshotFlow { lrc.currentIndex },
            AudioPlayShared.seekSignal,
        ) { index, signal -> index to signal }.collectLatest { (index, signal) ->
            // 跳转当帧的首次取样用目标位置, 之后回读位置真源
            var pendingSeekMs = fillSeekTracker.targetOf(signal)
            val words = lines.getOrNull(index)?.words
            if (words.isNullOrEmpty()) return@collectLatest
            val firstWordTime = words.first().timeMs
            val lastWordTime = words.last().timeMs
            val speed = playSpeed.coerceAtLeast(0.1f)

            while (isActive) {
                val truth = positionNowMs()
                val now = (pendingSeekMs ?: truth) + Lrc.OFFSET_MS
                pendingSeekMs = null
                lrc.fillClockMs = now - Lrc.OFFSET_MS

                if (!isPlaying) {
                    // 暂停/缓冲: 采样一次固定在当前变色进度, 随后挂起休眠
                    // (调试模式例外: 位置由滑条给, 暂停也要跟着刷新)
                    break
                }

                if (now < firstWordTime) {
                    // 句前等待段: 尚未开唱, 精准延时到首字开唱时刻 (跳转未落地时短间隔重算)
                    val waitMs = AudioPlayShared.lyricWakeUpMs(
                        ((firstWordTime - now) / speed).toLong(),
                        truth,
                        signal.targetMs,
                    )
                    if (waitMs > 0) {
                        delay(waitMs)
                        continue
                    }
                } else if (now >= lastWordTime) {
                    // 整句已唱完: 固定停在末字 100% 填充, 停帧挂起等待切行
                    break
                }

                // 正在唱词区间: 逐帧平滑重绘
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
            // 不裁就会画到控件上方压住圆形封面。左右留白在控件内 (见 [H_PADDING]), 放大后的字
            // 由排版自己折行, 不会溢出到留白之外
            .clipToBounds()
            // 只有测量要重排才需要宽度进组合; 高度/命中判定直接用手势与绘制作用域自带的 size
            .onSizeChanged { viewportW = it.width }
            // 点击/拖动/fling (复刻 GestureDetector: onScroll/onFling/onSingleTapUp);
            // 运行态单实例不换新, 手势协程一次绑定即可
            .pointerInput(Unit) {
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
                                        val idx = lrc.lineIndexAt(touchY)
                                        if (idx >= 0) {
                                            val line = lines[idx]
                                            // 点击宽度只限文本实际宽度 (用户拍板 2026-08):
                                            // 水平 = 文本宽, 文本两侧空白不触发跳转;
                                            // 垂直保持整行命中 (行高收窄会难受, 用户拍板)
                                            val textWidth = line.layout.size.width
                                            val hit = change.position.x in hPaddingPx..(hPaddingPx + textWidth)
                                            if (hit) {
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
            .pointerInput(Unit) {
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
        val scrollY = lrc.scrollY
        val currentIndex = lrc.currentIndex
        val lastIndex = lrc.lastIndex
        val colorProgress = lrc.colorProgress
        val lastProgress = lrc.lastProgress
        val fillClockMs = lrc.fillClockMs
        // 沉浸模式的辉光能量: 已平滑的真实 PCM 电平 (绘制期只读这一个值)
        val energy = if (eInk) 0f else lrc.smoothEnergy.coerceIn(0f, 1f)
        // 高亮色: 跟主色(封面取色) 的明暗取极值——主色亮(深色封面) 用白,
        // 主色暗(浅色封面) 用黑。这样歌词在两种背景下都有层次。
        val immersiveHighlight = if (primaryColor.luminance() > 0.5f) Color.White else Color.Black

        // 二分定位首个可见行 (复刻原版 firstVisible)。用基础偏移即可: 缩放只改变行高不改变行序
        val viewTop = scrollY - centerY
        val firstVisible = lines.binarySearch { line ->
            if (line.offset + line.height < viewTop) -1 else 1
        }.inv()

        val marginPxInt = lineMarginPx.roundToInt()
        // 切行期间辉光整体收一下 (Enter 曲线 0.75D 收回), 与"新行变亮"形成明暗对比
        val glowDip = 1f - GLOW_DIP_AMOUNT * (1f - MOTION_ENTER.transform(
            (colorProgress.coerceIn(0f, 1f) * LIT_IN_MS / GLOW_DIP_MS.toFloat()).coerceIn(0f, 1f)
        ))
        val dipEnergy = (energy * glowDip).coerceIn(0f, 1f)
        val glowRadiusPx =
            glowRadiusMinPx + (glowRadiusMaxPx - glowRadiusMinPx) * dipEnergy.pow(GLOW_RADIUS_CURVE)
        fun glowAt(lit: Float, color: Color): Shadow? = if (lit > GLOW_VISIBLE_MIN) {
            Shadow(
                color = color.copy(alpha = (GLOW_ALPHA_BASE + GLOW_ALPHA_ENERGY * energy) * lit),
                offset = Offset.Zero,
                blurRadius = glowRadiusPx,
            )
        } else {
            null
        }

        // 当前行的已唱字数 (含小数)
        val curLine = lines.getOrNull(currentIndex)
        val curHasWords = curLine?.words?.isNotEmpty() == true
        val curLit = if (curLine != null && curHasWords) {
            val raw = curLine.charProgressAt(fillClockMs + Lrc.OFFSET_MS)
            // 抖动抑制只挡"来回抖一下"的小幅回落 (时钟量化造成), 明显回退必须跟随 ——
            // 否则倒放/拖动回退时字不回暗、颜色不回退, 画面像卡住
            when {
                raw > lrc.maxSungProgress -> lrc.maxSungProgress = raw
                lrc.maxSungProgress - raw > SUNK_PROGRESS_REWIND_EPS -> lrc.maxSungProgress = raw
            }
            lrc.maxSungProgress
        } else {
            0f
        }
        val curColor = immersiveHighlight
        val curProgress = colorProgress.coerceIn(0f, 1f)
        // 当前行的实时排版先算出来: 放大后行高可能变高, 下面的行要靠它定位。
        // 有字标签的行按字长大; 没有字标签的行整行一起长大 (没有逐字时间可跟, 只能跟切行进度)
        val curLayout = if (curLine != null) {
            if (curHasWords) {
                val curTop = centerY + (curLine.offset - scrollY)
                measureStyledLrc(
                    curLine, textMeasurer, LRC_TEXT_STYLE, availableW,
                    bodyColor = secondaryColor,
                    litColor = curColor,
                    litChars = curLit,
                    sizeScaleAt = CHAR_GROW_SCALE,
                    glow = glowAt(curProgress, curColor),
                    alpha = calculateAlpha(curTop + curLine.height / 2f, size.height),
                )
            } else {
                measureUniformLrc(
                    curLine, textMeasurer, LRC_TEXT_STYLE, availableW,
                    // 切行进度本身已经是 Enter 曲线 (见 applySwitchTimeline), 这里直接用,
                    // 再套一次缓动会让字号在开场几十毫秒内就长完, 看起来像瞬间到位
                    scale = 1f + CHAR_GROW_GAIN * curProgress,
                )
            }
        } else {
            null
        }


        // 行高映射: 放大会改变行高, 下面各行按前面各行的高度差累加 (放大只向下徽长, 所以只会下推)
        var shift = 0f
        for (j in 0 until minOf(firstVisible, lines.size)) shift += lines[j].displayHeight - lines[j].height

        for (i in firstVisible until lines.size) {
            val line = lines[i]
            val lineTop = centerY + (line.offset + shift - scrollY)
            if (lineTop > size.height) break

            val isCurrent = i == currentIndex
            val isLast = i == lastIndex
            val hasWords = line.words.isNotEmpty()

            // progress: 切行进度 0→1。弹簧会过冲, 插值必须夹紧 (过冲只用来给颜色/字号动画加回弹感之外的余量)
            val progress = colorProgress.coerceIn(0f, 1f)
            // lit: 点亮程度 1=高亮色 / 0=纯底色。当前行渐亮, 上一行渐暗, 其余恒暗。
            // 点亮程度: 当前行走"进入"进度, 上一行走"退出"进度 (两条曲线不同)
            val lit = when {
                isCurrent -> progress
                isLast -> 1f - lastProgress.coerceIn(0f, 1f)
                else -> 0f
            }
            // 透明度只跟视口位置走 (对齐原版 calculateAlpha): 离屏幕上下边缘越近越淡,
            // 与"离当前行几行"无关 —— 半屏之内任何一行都是满不透明度。
            val alpha = calculateAlpha(lineTop + line.height / 2f, size.height)

            // 已唱字数 (含小数): 当前行按字标签实时推进; 上一行沿用切行快照 (沉浸) / 满填充 (经典)。
            val litChars = when {
                !hasWords -> 0f
                isCurrent -> curLit
                isLast -> lrc.prevSungProgress
                else -> 0f
            }

            // 当前行逐字行恒为高亮色 (整行颜色不跟着切行进度漂), 上一行淡出才用 lit 插值
            var litColor =
                if (isCurrent && hasWords) immersiveHighlight
                else lerp(secondaryColor, immersiveHighlight, lit)
            val glowShadow = glowAt(lit, immersiveHighlight)

            // 这行真正拿来画的排版: 逐字放大 (当前行) / 回落动画 (刚唱完的行) / 基础排版
            val displayLayout: TextLayoutResult
            val mode: LrcLineMode
            when {
                isCurrent && hasWords -> {
                    displayLayout = curLayout ?: line.layout
                    mode = LrcLineMode.Styled
                }

                // 唱完到变成普通已唱行: 字号随切行进度连续回落到原字号
                isLast && hasWords -> {
                    displayLayout = measureStyledLrc(
                        line, textMeasurer, LRC_TEXT_STYLE, availableW,
                        bodyColor = secondaryColor,
                        litColor = litColor,
                        litChars = litChars,
                        sizeScaleAt = { t ->
                            val sung = 1f + CHAR_GROW_GAIN * t
                            sung + (PAST_FONT_SCALE - sung) * lastProgress.coerceIn(0f, 1f)
                        },
                        glow = glowShadow,
                        alpha = alpha,
                    )
                    mode = LrcLineMode.Styled
                }

                // 无字标签的上一行: 整行字号从唱完时的大小连续回落到原字号
                isLast -> {
                    val sung = 1f + CHAR_GROW_GAIN
                    displayLayout = measureUniformLrc(
                        line, textMeasurer, LRC_TEXT_STYLE, availableW,
                        scale = sung + (PAST_FONT_SCALE - sung) * lastProgress.coerceIn(0f, 1f),
                    )
                    mode = LrcLineMode.WholeLine
                }

                // 唱过的行: 原字号
                i < currentIndex -> {
                    displayLayout = line.layout
                    mode = LrcLineMode.Plain
                }

                isCurrent -> {
                    displayLayout = curLayout ?: line.layout
                    mode = LrcLineMode.WholeLine
                }

                else -> {
                    displayLayout = line.layout
                    mode = LrcLineMode.Plain
                }
            }

            val displayHeight = displayLayout.size.height + marginPxInt
            // 折行过渡: 当前行与淡出中的上一行都要跟踪 (行数变多或变少都算)
            val trackWrap = isCurrent || isLast
            val wrapPositions = if (trackWrap) {
                FloatArray(line.text.length * 3).also { pos ->
                    for (index in line.text.indices) {
                        val row = displayLayout.getLineForOffset(index)
                        // 笔位 + 行基线: 笔位定横向, 基线定纵向
                        pos[index * 3] = displayLayout.getHorizontalPosition(
                            index, usePrimaryDirection = true,
                        )
                        pos[index * 3 + 1] = if (row < displayLayout.lineCount) {
                            displayLayout.getLineBaseline(row)
                        } else {
                            0f
                        }
                        pos[index * 3 + 2] = row.toFloat()
                    }
                }
            } else {
                null
            }
            // 行高过渡 (推动下方各行) 与逐字飞行是两套进度: 前者按行数变化, 后者按每个字自己
            val wrapProgress = if (wrapPositions != null) {
                lrc.trackWrap(i, displayLayout.lineCount, displayHeight.toFloat())
            } else {
                1f
            }
            // 逐字飞行: 只有"自己的落点变了"的字才起飞, 起点是它当前真正画在的位置
            val charRuns = if (wrapPositions != null) lrc.trackCharRuns(i, wrapPositions) else null
            val textTop = lineTop + marginPxInt / 2f
            val topLeft = Offset(hPaddingPx, textTop)

            val style = LrcLineStyle(
                bodyColor = secondaryColor,
                litColor = litColor,
                alpha = alpha,
                shadow = glowShadow,
                mode = mode,
            )
            if (charRuns != null) {
                drawSlideChars(
                    line = line,
                    drawn = charRuns,
                    topLeft = topLeft,
                    bodyColor = secondaryColor,
                    litColor = litColor,
                    litChars = litChars,
                    perChar = hasWords,
                    sizeScaleAt = when {
                        // 淡出中的上一行: 字号按"唱完 → 已唱"回落, 与它自己的排版一致
                        isLast && hasWords -> { t ->
                            val sung = 1f + CHAR_GROW_GAIN * t
                            sung + (PAST_FONT_SCALE - sung) * lastProgress.coerceIn(0f, 1f)
                        }

                        isLast -> { _ ->
                            val sung = 1f + CHAR_GROW_GAIN
                            sung + (PAST_FONT_SCALE - sung) * lastProgress.coerceIn(0f, 1f)
                        }

                        // 当前行的逐字行: 每个字按自己的进度
                        hasWords -> { t -> 1f + CHAR_GROW_GAIN * MOTION_ENTER.transform(t) }

                        // 当前行的非逐字行: 整行统一字号 (取该行排版所用的那个值)
                        // 整行统一字号: progress 已是 Enter 曲线, 不再二次缓动
                        else -> { _ -> 1f + CHAR_GROW_GAIN * progress }
                    },
                    alpha = alpha,
                    glow = glowShadow,
                    textMeasurer = textMeasurer,
                )
            } else {
                drawLrcLine(displayLayout, topLeft, style)
            }
            line.displayHeight = displayHeight
            // 折行过渡期间下方各行跟着平滑下推, 而不是一步到位
            val wrapFromHeight = lrc.wrapFromHeightOf(i)
            val heightForShift = if (wrapProgress < 1f && wrapFromHeight > 0f) {
                wrapFromHeight + (displayHeight - wrapFromHeight) * wrapProgress
            } else {
                displayHeight.toFloat()
            }
            shift += heightForShift - line.height
        }
        lrc.retainWrapSlides(currentIndex, lastIndex)
    }
}

/**
 * 当前高亮行 (自适应精准延时调度派生, 喂给 [LrcViewShared] 的 lrcProgress)。
 *
 * 对齐原版 AudioPlayService.upPlayProgressForLrc 的节能策略:
 * 1. 响应式驱动: 状态变化 (换歌/切章/seek/换速/暂停/播放) 立即重算定位;
 * 2. 精确 delay: 播放时按下一句歌词时间差精准挂起休眠, 一首歌仅在切行时刻唤醒 N 次,
 *    杜绝每秒 60~120 次的死循环帧轮询; 跳转未落地期间按 [AudioPlayShared.SEEK_POLL_MS]
 *    短间隔重算, 位置真源反映引擎真实位置后恢复按行精准休眠;
 * 3. 暂停休眠: 暂停/缓冲时完全挂起零开销, 拖动进度条或恢复播放时即时响应。
 *
 * 对外发布 (车载歌词) 是另一个消费者, 走 [io.legado.app.model.audio.LyricPublisher]:
 * 它需要息屏后台也推进, 共用 [AudioPlayShared.seekSignal] 与 [Lrc.indexAt] 同一份判定。
 */
@Composable
fun rememberLrcIndex(
    lrcData: Lrc?,
    isPlaying: Boolean = AudioPlayShared.status == Status.PLAY,
    playSpeed: Float = AudioPlayShared.playSpeed,
): Int {
    // 初值就地算出来 (不等 LaunchedEffect 的下一帧): 换歌那一帧 LrcViewShared 就能按正确的行定位,
    // 否则会先居中第一行, 下一帧再 snap 过去 —— 肉眼是闪一下
    val key = lrcData to AudioPlayShared.durChapterIndex
    var index by remember(key) { mutableIntStateOf(lrcData.indexNow()) }
    // 跳转目标追踪器: 只认新纪元, 跳转当帧按目标定位
    val seekTracker = remember { AudioPlayShared.SeekTargetTracker() }
    LaunchedEffect(key, isPlaying, playSpeed) {
        // 观察跳转信号 (StateFlow 直接收集): 进度跳转当帧立即打断 delay 并按目标位置重新校准
        AudioPlayShared.seekSignal.collectLatest { signal ->
            // 跳转当帧的首次取样用目标位置, 之后回读位置真源
            var pendingSeekMs = seekTracker.targetOf(signal)
            val lrc = lrcData ?: return@collectLatest
            if (!lrc.hasTimeline) return@collectLatest
            while (isActive) {
                val truth = positionNowMs()
                val now = (pendingSeekMs ?: truth) + Lrc.OFFSET_MS
                pendingSeekMs = null
                val targetIndex = lrc.indexAt(now)
                if (index != targetIndex) {
                    index = targetIndex
                }
                if (!isPlaying) {
                    // 暂停/非播放中: 停在当前行, 挂起等待状态或 seek 唤醒
                    break
                }
                val nextTime = lrc.timeAfter(targetIndex) ?: break
                val speed = playSpeed.coerceAtLeast(0.1f)
                val remainWallMs = AudioPlayShared.lyricWakeUpMs(
                    ((nextTime - now) / speed).toLong(),
                    truth,
                    signal.targetMs,
                )
                if (remainWallMs <= 0) {
                    // 临界时间微延时防紧密循环
                    delay(16)
                } else {
                    delay(remainWallMs)
                }
            }
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

// ---- 排版常数 (大字左对齐, 呼吸间距) ----
private val LRC_FONT_SIZE = 24.sp
private val LRC_LINE_MARGIN = 28.dp
private val LRC_H_PADDING = 16.dp

/**
 * 沉浸模式的文字样式。基础排版、淡出/放大排版与逐字 span 必须共用它,
 * 否则字号/行宽/折行会与各自算一套, 行高映射跟着错位。
 */
private val LRC_TEXT_STYLE = TextStyle(
    fontSize = LRC_FONT_SIZE,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Start,
)

// ---- 统一动效语言: 一套时长单位 + 四条 bezier ----
//
// 所有动画共享同一个基础单位 D, 时长取 D 的整数倍; 事件之间靠"错峰"而不是各自计时,
// 否则切行时滚动、逐字、折行、辉光会各走各的, 看起来就是糊。

/** 基础单位 (ms) */
private const val D = 160

/** 行滚动 (2.5D): 落位带轻微回弹 */
private const val SCROLL_MS = (2.5 * D).toInt()        // 400

/**
 * 新行点亮 (1.25D, 缓出) 与旧行退场 (1D, 线性): 退场对齐原版"立刻开始、匀速退完"的节奏。
 */
private const val LIT_IN_MS = (1.25 * D).toInt()       // 200
private const val LIT_OUT_DELAY_MS = 0
private const val LIT_OUT_MS = D                       // 160

/** 整个切行时间轴长度 */
private const val SWITCH_TOTAL_MS = SCROLL_MS.toLong()

/** 切行期间辉光的"收" (0.75D) 与幅度 */
private const val GLOW_DIP_MS = (0.75 * D).toInt()     // 120
private const val GLOW_DIP_AMOUNT = 0.18f

/**
 * 进入: easeOutCubic (0.33, 1.00, 0.68, 1.00)。
 * 全程都有可见位移 —— 早期的 (0.16,1,0.30,1) 在 200ms 里前 40ms 就走完 75%,
 * 肉眼等同"瞬间到位", 反而看不出缓动。
 */
private val MOTION_ENTER = CubicBezierEasing(0.33f, 1.00f, 0.68f, 1.00f)

/** 退出: 线性 (对齐原版 colorProgress 每帧匀速推进, 无缓动) */
private val MOTION_EXIT = CubicBezierEasing(0.00f, 0.00f, 1.00f, 1.00f)

/** 标准: 折行位移等中性运动 */
private val MOTION_STANDARD = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1.00f)

/** 落位回弹: 约 4% 过冲后落稳 (行滚动) */
private val MOTION_SETTLE = CubicBezierEasing(0.34f, 1.20f, 0.64f, 1.00f)

// 字号缩放一律用单调曲线: 过冲曲线会让字号先超过目标再回落,
// 期间排版行数可能瞬间多出一行 (触发一次不存在的折行) 且行高上下抖动, 所以字号不用带过冲的曲线。

/**
 * 真正的"无阴影"。**绝不能**用 [Shadow.None] 代替:
 * - Shadow.None 是 Shadow(黑, offset 0, blur 0), 而 Compose 的 correctBlurRadius() 会把
 *   半径 0 改写成 Float.MIN_VALUE, 于是字体引擎在字底下压一层**硬黑阴影**;
 * - 字形边缘是抗锯齿的半透明像素, 黑色从这里透出来 = 用户看到的"黑色描边";
 * - 透明色的阴影既能顶掉画笔上残留的阴影层 (setShadow 对 null 是空操作), 又不会真的画东西。
 */
private val NO_SHADOW = Shadow(
    color = Color.Transparent,
    offset = Offset.Zero,
    blurRadius = 0f,
)

// ---- 逐字放大 / 唱完回落 ----

/** 唱过的字放大的倍率 (只涨不落: 唱过就一直保持) */
private const val CHAR_GROW_GAIN = 0.15f

/**
 * 当前行的字号倍率: 每个字按它自己的已唱比例 t 从 1 连续长到 1 + [CHAR_GROW_GAIN]。
 * 字号进 span 就是真重排 —— 行变宽了会多折一行, 行高变化由绘制期累加, 后续行被推下去。
 */
private val CHAR_GROW_SCALE: (Float) -> Float = { t ->
    1f + CHAR_GROW_GAIN * MOTION_ENTER.transform(t)
}

/**
 * 唱过的行的字号倍率: 回到原字号。
 * 想让唱过的行比未唱的略小, 把这里改成小于 1 的值即可 (缩放会随之改变折行与行高)。
 */
private const val PAST_FONT_SCALE = 1f

/** 已唱进度允许跟随回退的阈值: 超过它视为真实回退 (倒放/回到句内前段), 低于它按时钟抖动忽略 */
private const val SUNK_PROGRESS_REWIND_EPS = 0.02f

// ---- 沉浸模式的排版/明暗常量 ----

// ---- 音频能量驱动的辉光 ----

/** 辉光幅度: 透明度 0.22 → 0.85, 半径 4dp → 18dp (半径走 0.75 次幂曲线, 拍点更抢眼) */
private const val GLOW_ALPHA_BASE = 0.22f
private const val GLOW_ALPHA_ENERGY = 0.63f
private val GLOW_MIN_RADIUS = 4.dp
private val GLOW_MAX_RADIUS = 18.dp

/** 半径响应的曲线指数: <1 让中间值更快变大, 拍点落下去时有"顶一下"的手感 */
private const val GLOW_RADIUS_CURVE = 0.75f

/** 点亮程度低于此值就不再挂阴影 (避免无谓的字形重栅格化) */
private const val GLOW_VISIBLE_MIN = 0.02f

/** 折行时长 (ms): 被挤走的字沿一条弧线从上行的位置跳到下行的位置 */
private const val WRAP_SLIDE_MS = 260L

/**
 * 跳跃时向右侧外扩的弧高 (px): 弧线走"先向右出列, 再回到行首"的横向弧,
 * 而不是向上拱 —— 向上拱会穿过上一行的字, 造成两行叠加。
 */
private const val WRAP_JUMP_BOW_PX = 48f

/** 判定"被重排到别处"的水平位移阈值 (px): 小于它只做纵向位移, 不加横向弧线 */
private const val WRAP_JUMP_MIN_DX_PX = 24f


// ---- 能量显示平滑 ----
//
// 自归一化 (快包络 / 慢均值) 已经在 PCM 侧完成, 这里绝不能再做第二遍窗口归一化 ——
// 两层自适应会互相拉扯: 同一首歌的副歌与主歌会得到不同亮度, 且还要等窗口重新收敛。
// 这里只做一层显示平滑, 把逐缓冲的驱动值化成连续呼吸。

/** 显示平滑时间常数 (ms): 上升快于下降, 拍子能看出来但不抽搽 */
private const val ENERGY_DISPLAY_ATTACK_MS = 60f
private const val ENERGY_DISPLAY_RELEASE_MS = 180f

/** 音频管道静默超过此间隔后才开始衰减 (ms): 正常派发间隔远小于它, 不会把阶梯锯成锯齿 */
private const val ENERGY_HOLD_MS = 120

/** 断供 (暂停/缓冲/跳转) 后的衰减时间常数 (ms) */
private const val ENERGY_DECAY_TAU_MS = 200f

/** 低于这个能量视为静音, 帧循环降为低频轮询 (省电) */
private const val ENERGY_SILENT_THRESHOLD = 0.01f

/** 静音段的时钟推进间隔 (ms) */
private const val ENERGY_IDLE_POLL_MS = 100L

/** 测量后的行 (复刻原版 LrcView.LrcLine); [words] 是逐字采样, 空表示这行整行高亮。 */
private class MeasuredLrcLine(
    val time: Int,
    val text: String,
    val layout: TextLayoutResult,
    val height: Int,
    val offset: Float,
    val words: List<LrcWord>,
    /** 正文的字符数 (译文是 `\n` 之后追加的)。 */
    val bodyChars: Int,
) {
    /** 最近一次绘制时这一行的实际高度 (含放大), 供下面各行定位与点击命中 */
    var displayHeight: Int = height
}

/** 正文字符数: 译文是 `\n` 之后追加的, 不参与逐字填充。 */
private fun LrcLine.bodyChars(): Int = text.indexOf('\n').let { if (it < 0) text.length else it }

/**
 * 逐字采样 + 一个收尾采样: Enhanced LRC 只标每个字的起点, 末字没有终点, 用下一行的时间戳补上。
 * 终点是 [bodyChars] (正文末尾), 译文不参与填充。
 * 末行无下一行时间戳时，根据本行字符节奏预估终点时间，确保播放完毕时末尾字符能被点亮。
 */
private fun LrcLine.fillSamples(nextTimeMs: Int?): List<LrcWord> {
    if (words.isEmpty()) return emptyList()
    val end = bodyChars()
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

/** 一行歌词的绘制模式: 把"当前行/上一行/有无字标签/沉浸与否"的组合收敛成 3 种画法 */
private enum class LrcLineMode {
    /** 整行底色: 未播放, 或已唱完且不再点亮 */
    Plain,
    /** 整行用亮色覆盖: 没有逐字标签的歌词 */
    WholeLine,
    /** 字号/颜色/辉光都写在字符 span 上 (逐字放大、唱完回落的字号), 画的时候不再改任何画笔参数 */
    Styled,
}

/** 一行歌词的绘制参数: 视觉状态全部在调用点算清, 这里不再做任何判断 */
private class LrcLineStyle(
    val bodyColor: Color,
    val litColor: Color,
    val alpha: Float,
    /** 辉光 (能量驱动的模糊); null = 无光晕 */
    val shadow: Shadow?,
    val mode: LrcLineMode,
)

/**
 * 画一行歌词。阴影只有两种写法, 不存在第三种:
 * - 要辉光 → [LrcLineStyle.shadow];
 * - 不要辉光 → [NO_SHADOW]。
 *
 * 绝不能写 null 或不传: `AndroidTextPaint.setShadow(null)` 开头就是 `if (shadow == null) return`,
 * 属于空操作, 画笔上残留的阴影会永久粘在这个 TextLayoutResult 上; 而 TextMeasurer 按
 * (文本+样式+约束) 缓存, 同一句歌词重复出现时共用一个 TextPaint —— 于是一句唱过,
 * 所有文字相同的行都跟着发亮, 且参数冻结不再变化。这就是"已播放行残留炫光"的成因。
 */
/**
 * 折行换行的绘制: 被挤下去的字先向行尾滑出并淡出, 再从下一行行首滑入并淡入;
 * 没换行的字原地按新字号重画。
 *
 * 位置用整行排版给出的字位 ([TextLayoutResult.getHorizontalPosition] 与
 * [TextLayoutResult.getLineBaseline]) 对齐, 过渡结束那一帧与正常整行绘制
 * 完全重合, 不会在结束处跳一下。
 */
private fun DrawScope.drawSlideChars(
    line: MeasuredLrcLine,
    drawn: FloatArray,
    topLeft: Offset,
    bodyColor: Color,
    litColor: Color,
    litChars: Float,
    perChar: Boolean,
    sizeScaleAt: (Float) -> Float,
    alpha: Float,
    glow: Shadow?,
    textMeasurer: TextMeasurer,
) {
    val text = line.text
    val bodyEnd = minOf(line.bodyChars, text.length)
    val baseSize = LRC_TEXT_STYLE.fontSize.value
    for (i in text.indices) {
        val ch = text[i]
        if (ch.isWhitespace()) continue
        val t = if (perChar && i < bodyEnd) (litChars - i).coerceIn(0f, 1f) else 0f
        // 位置已由逐字飞行算好 (含弧线), 这里只负责量字和落笔
        val baseX = drawn[i * 2]
        val baseY = drawn[i * 2 + 1]
        val charAlpha = alpha
        if (charAlpha <= 0f) continue
        val scale = sizeScaleAt(t)
        val charLayout = textMeasurer.measure(
            ch.toString(),
            style = LRC_TEXT_STYLE.copy(fontSize = (baseSize * scale).sp),
            constraints = Constraints(),
        )
        // 纵向按基线落位: 每个字的字号不同, 从行顶画会让大字的墨迹整体上飘,
        // 过渡结束时换回整行绘制就跳一下。基线对齐后与整行排版逐像素一致
        val charAscent = if (charLayout.lineCount > 0) charLayout.getLineBaseline(0) else 0f
        drawText(
            charLayout,
            if (perChar) lerp(bodyColor, litColor, t) else litColor,
            Offset(topLeft.x + baseX, topLeft.y + baseY - charAscent),
            alpha = charAlpha,
            shadow = if (!perChar || t > 0f) glow ?: NO_SHADOW else NO_SHADOW,
        )
    }
}

private fun DrawScope.drawLrcLine(
    layout: TextLayoutResult,
    topLeft: Offset,
    style: LrcLineStyle,
) {
    when (style.mode) {
        LrcLineMode.Plain -> drawText(
            layout, style.bodyColor, topLeft, alpha = style.alpha, shadow = NO_SHADOW,
        )

        LrcLineMode.WholeLine -> drawText(
            layout, style.litColor, topLeft, alpha = style.alpha,
            shadow = style.shadow ?: NO_SHADOW,
        )

        // 颜色与辉光都在字符 span 里 (透明度也已包在里面), 画笔层只负责把残留阴影顶掉
        LrcLineMode.Styled -> drawText(
            layout, Color.Unspecified, topLeft, shadow = NO_SHADOW,
        )
    }
}

/**
 * 逐字写的排版: 字号、颜色、辉光全部写进字符 span, 一次测量一次绘制。
 *
 * 字号写进 span 就会触发真正的重排: 唱过的字变大后行宽变宽, 这一行可能多折出一行。
 * 行高变化由调用点用 [TextLayoutResult.size.height] 累加，后面的行因此被推下去
 * (刚唱完的行回落则是反方向, 行高变小、后面的行上移) —— 不靠裁剪也不靠逐字重画。
 *
 * 字号、颜色、辉光、透明度全部连续插值, 不引入任何档位 (因此每帧都会重测这两行)。
 *
 * @param sizeScaleAt 每个字的字号倍率 (入参是该字自己的已唱比例 0..1):
 *   当前行 = 1 + [CHAR_GROW_GAIN] × t; 刚唱完的行按切行进度从"唱完时的大小"连续过渡到 [PAST_FONT_SCALE]
 */
private fun measureStyledLrc(
    line: MeasuredLrcLine,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    maxWidth: Int,
    bodyColor: Color,
    litColor: Color,
    litChars: Float,
    sizeScaleAt: (Float) -> Float,
    glow: Shadow?,
    alpha: Float,
): TextLayoutResult {
    val text = line.text
    if (text.isEmpty()) return line.layout
    val bodyEnd = minOf(line.bodyChars, text.length)
    val baseSize = textStyle.fontSize.value
    val spanAlpha = alpha.coerceIn(0f, 1f)

    fun styleAt(i: Int): SpanStyle {
        val raw = if (i < bodyEnd) (litChars - i).coerceIn(0f, 1f) else 0f
        // 颜色走"进入"曲线: 字被点亮时起步快、收尾慢; 字号由调用方按"弹出"曲线给
        val t = MOTION_ENTER.transform(raw)
        val color = lerp(bodyColor, litColor, t).copy(alpha = spanAlpha)
        val shadow = if (t > 0f && glow != null) {
            glow.copy(color = glow.color.copy(alpha = glow.color.alpha * spanAlpha))
        } else {
            NO_SHADOW
        }
        return SpanStyle(
            color = color,
            fontSize = (baseSize * sizeScaleAt(raw)).sp,
            shadow = shadow,
        )
    }

    val annotated = buildAnnotatedString {
        append(text)
        var runStart = 0
        var runStyle = styleAt(0)
        for (i in 1 until text.length) {
            val style = styleAt(i)
            if (style != runStyle) {
                addStyle(runStyle, runStart, i)
                runStart = i
                runStyle = style
            }
        }
        addStyle(runStyle, runStart, text.length)
    }
    return measurer.measure(annotated, textStyle, constraints = Constraints(maxWidth = maxWidth))
}

/**
 * 整行统一字号的排版 (没有逐字时间标签的歌词用): span 只带字号, 颜色/辉光留给画笔,
 * 这样整行高亮、淡出、能量辉光与原来一致, 只有行高会随字号变化。
 */
private fun measureUniformLrc(
    line: MeasuredLrcLine,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    maxWidth: Int,
    scale: Float,
): TextLayoutResult {
    if (line.text.isEmpty()) return line.layout
    val size = (textStyle.fontSize.value * scale).sp
    val annotated = buildAnnotatedString {
        append(line.text)
        addStyle(SpanStyle(fontSize = size), 0, line.text.length)
    }
    return measurer.measure(annotated, textStyle, constraints = Constraints(maxWidth = maxWidth))
}

/**
 * 歌词运行态 (复刻原版 LrcView 的可变字段): 滚动/高亮/动画。滚动与颜色进度只被绘制、手势与
 * 协程读写, 组合里不读 —— 所以每帧只失效绘制不重组。单实例, 数据变化经 [reset] 整体复位
 * (复刻原版 setLrcData 的重置), 不以换实例代替复位。
 */
private class LrcRuntime {
    /** 测量结果; 只在组合期由 [layout] 整体替换, 所以绘制直接用它的返回值即可 */
    var lines: List<MeasuredLrcLine> = emptyList()
    var currentIndex by mutableIntStateOf(-1)
    var lastIndex by mutableIntStateOf(-1)
    /** 新行点亮进度 (0→1, Enter 曲线) */
    var colorProgress by mutableFloatStateOf(1f)

    /** 上一行退场进度 (0→1, Exit 曲线, 相对切行错峰开始) */
    var lastProgress by mutableFloatStateOf(1f)

    /**
     * 切行时间轴: 一次切行内所有"跟切行有关"的效果都由它统一给出进度。
     * 新行点亮先起, 旧行退场错峰 0.25D 后开始, 两条曲线不同 (进入 vs 退出)。
     */
    /**
     * 切行进度 = 当前位置相对本行起点的时间差 (纯函数, 与墙钟无关)。
     *
     * 因为只是位置的函数, 往前走是正常播放, 往回拖就是同一段动画的倒放 ——
     * 不存在"只能正放"的单向时间轴。
     */
    fun applySwitchTimeline(elapsedMs: Long) {
        val elapsed = elapsedMs.toFloat()
        colorProgress = MOTION_ENTER.transform((elapsed / LIT_IN_MS).coerceIn(0f, 1f))
        lastProgress = MOTION_EXIT.transform(
            ((elapsed - LIT_OUT_DELAY_MS) / LIT_OUT_MS).coerceIn(0f, 1f),
        )
    }
    /** 逐字填充用的时钟采样; 只有当前行带字标签时才逐帧刷新, 其余时候是陈旧值且无人读 */
    var fillClockMs by mutableIntStateOf(0)
    /**
     * 当前行已唱进度的单调滤波器 (同一句内只增不减, 杜绝时钟抖动闪烁)。
     * 只在绘制期读写, 用普通字段: 走快照状态会在绘制中途 invalidate 自己。
     */
    var maxSungProgress: Float = 0f
    /** 切行时快照的逐字进度: 上一行淡出期间沿用, 不再整行提亮 (同样只在绘制期访问) */
    var prevSungProgress: Float = 0f

    // ---- 折行滑移 (只在绘制期访问) ----
    /** 每行一份折行过渡状态: 行数变化 (变多或变少) 都要过渡, 当前行与淡出的上一行都要跟 */
    private class WrapSlide {
        var lastRows: Int = 0
        var lastHeight: Float = 0f
        var fromHeight: Float = 0f
        var start: TimeMark? = null
        /** 每个字一份位移记录 (数量随文本长度, 文本变了整体重建) */
        var runs: MutableList<CharRun> = ArrayList()
        /** 上一帧每一行有几个字: 用来判定"这一行发生过重排" (有字进/出) */
        var rowCounts: IntArray? = null
        /** 每行一条挤压时间轴 (行内被挤开的字共用一个进度) */
        var rowSlides: MutableList<RowSlide> = ArrayList()
    }

    /**
     * 一整行的挤压过渡: 行内"没换行、只是被挤开"的字共用一个进度与一份位移量。
     *
     * 与被挤的字各自跑一条过渡相比, 共用进度保证了它们与飞进来的那个字是同一个动作 ——
     * 不存在谁追谁, 落地就是排版位置, 也就不会出现追上再回的顿挫。
     */
    private class RowSlide {
        var start: TimeMark? = null
        var dx: FloatArray = FloatArray(0)
        var dy: FloatArray = FloatArray(0)
    }

    /**
     * 一个字的折行位移 (相对排版原点, 纵向是行基线)。
     *
     * 独立于行级过渡: 字号继续变大时排版可能连续重排多次 (先折一个字, 再折第二个),
     * 若按"行数变化"整体重置, 先折下去的字会被当成"没动过"而瞬移。这里每个字各自起飞、
     * 各自落地, 中途重排只是把已经飞了一半的字的起点改成它当前画在的位置。
     */
    private class CharRun {
        var drawnX = 0f
        var drawnY = 0f
        var fromX = 0f
        var fromY = 0f
        /** 目标行号: 只有行号变了 (真的换行) 才起飞, 见 [trackCharRuns] */
        var toRow = -1f
        var start: TimeMark? = null
        /** 这一趟是否需要横向弧线 (水平位置真变了才加, 否则整行下移会整体右鼓一下) */
        var jumped = false
    }

    private val wrapSlides = HashMap<Int, WrapSlide>()

    /**
     * 更新该行每个字的落点并返回"当前该画在哪" (x,y 交替, 纵向是行基线)。
     *
     * 返回 null = 这一行没有任何字在飞, 调用方走整行排版绘制 (更快, 且与静止态逐像素一致)。
     */
    fun trackCharRuns(index: Int, positions: FloatArray): FloatArray? {
        val slide = wrapSlides.getOrPut(index) { WrapSlide() }
        val count = positions.size / 3
        if (slide.runs.size != count) {
            // 文本/排版列表换代: 直接就位, 不做过渡
            slide.runs = ArrayList(count)
            repeat(count) { k ->
                slide.runs.add(
                    CharRun().apply {
                        drawnX = positions[k * 3]
                        drawnY = positions[k * 3 + 1]
                        toRow = positions[k * 3 + 2]
                    }
                )
            }
            // 同时记下当前行分布: 下一帧据此判定"是否发生重排"。
            // 不记的话每行都会被当成新增行, 即使一个字都没动也会空跑一整段逐字过渡。
            val rows = IntArray(count) { positions[it * 3 + 2].toInt().coerceAtLeast(0) }
            slide.rowCounts = IntArray((rows.maxOrNull() ?: 0) + 1).also { counts ->
                for (row in rows) counts[row]++
            }
            return null
        }
        // 这一帧哪些行发生过重排: 某行的字数与上一帧不同, 说明有字进出该行 ——
        // 该行原有的字会被新字挤开/让位, 必须和飞进来的字走同一条时间轴, 否则是瞬移
        val rows = IntArray(count) { positions[it * 3 + 2].toInt().coerceAtLeast(0) }
        val rowTotal = (rows.maxOrNull() ?: 0) + 1
        val counts = IntArray(rowTotal)
        for (row in rows) counts[row]++
        val prevCounts = slide.rowCounts
        val reflowed = BooleanArray(rowTotal)
        for (row in 0 until rowTotal) {
            // 行数本身变了 (多折/少折一行) 时整行都算重排
            reflowed[row] = prevCounts == null || row >= prevCounts.size ||
                prevCounts[row] != counts[row]
        }
        slide.rowCounts = counts

        val now = TimeSource.Monotonic.markNow()
        // 重排的行各起一条时间轴, 并记下每个被挤字这一趟要走的位移 (只捕获一次)
        while (slide.rowSlides.size < rowTotal) slide.rowSlides.add(RowSlide())
        for (row in 0 until rowTotal) {
            if (!reflowed[row]) continue
            val rowSlide = slide.rowSlides[row]
            if (rowSlide.dx.size != count) {
                rowSlide.dx = FloatArray(count)
                rowSlide.dy = FloatArray(count)
            }
            for (k in 0 until count) {
                if (rows[k] != row) continue
                val run = slide.runs[k]
                rowSlide.dx[k] = positions[k * 3] - run.drawnX
                rowSlide.dy[k] = positions[k * 3 + 1] - run.drawnY
            }
            rowSlide.start = now
        }

        var flying = false
        for (k in 0 until count) {
            val run = slide.runs[k]
            val tx = positions[k * 3]
            val ty = positions[k * 3 + 1]
            val row = positions[k * 3 + 2]
            val rowChanged = row != run.toRow
            run.toRow = row
            // 当前排版的落点直接参与插值 (飞行中排版缩小/放大都跟), 落地即与整行排版重合

            // 换行的字: 自己从"当前画在哪"飞到排版位置
            if (rowChanged) {
                var startMark = run.start
                if (startMark == null) {
                    run.fromX = run.drawnX
                    run.fromY = run.drawnY
                    run.jumped = abs(tx - run.drawnX) > WRAP_JUMP_MIN_DX_PX
                    startMark = now
                    run.start = startMark
                }
                val fraction = startMark.elapsedNow().inWholeMilliseconds.toFloat() /
                    WRAP_SLIDE_MS
                if (fraction >= 1f) {
                    run.drawnX = tx
                    run.drawnY = ty
                    run.start = null
                    continue
                }
                val p = MOTION_STANDARD.transform(fraction.coerceIn(0f, 1f))
                run.drawnX = run.fromX + (tx - run.fromX) * p
                run.drawnY = run.fromY + (ty - run.fromY) * p
                flying = true
                continue
            }

            // 没换行: 只在"这一行被重排"时跟着整行走同一段位移
            val rowSlide = slide.rowSlides.getOrNull(rows[k])
            val rowStart = rowSlide?.start
            if (rowSlide != null && rowStart != null) {
                val fraction = rowStart.elapsedNow().inWholeMilliseconds.toFloat() /
                    WRAP_SLIDE_MS
                if (fraction >= 1f) {
                    rowSlide.start = null
                    run.drawnX = tx
                    run.drawnY = ty
                    continue
                }
                val p = MOTION_STANDARD.transform(fraction.coerceIn(0f, 1f))
                // 从旧位置（= 排版位置 − 位移量）连续走到排版位置, 落地即精确重合
                run.drawnX = tx - rowSlide.dx[k] * (1f - p)
                run.drawnY = ty - rowSlide.dy[k] * (1f - p)
                flying = true
                continue
            }

            // 没有重排: 直接坐在排版给的精确位置, 不引入任何延迟
            // (字号连续缩放导致的位置漂移本身是平滑的, 不该再套一层过渡)
            run.drawnX = tx
            run.drawnY = ty
        }
        if (!flying) return null
        val out = FloatArray(count * 2)
        for (k in 0 until count) {
            val run = slide.runs[k]
            // 横向弧线: 起点在左、目标在右时向右鼓, 全程 y 单调, 不与上下行文字重叠
            out[k * 2] = run.drawnX + if (run.start != null && run.jumped) {
                sin(Math.PI.toFloat() * runProgress(run)) * WRAP_JUMP_BOW_PX
            } else {
                0f
            }
            out[k * 2 + 1] = run.drawnY
        }
        return out
    }

    /** 单字飞行进度 0~1 (只用于弧线形状; 与位移同一条时间轴) */
    private fun runProgress(run: CharRun): Float {
        val start = run.start ?: return 1f
        return (start.elapsedNow().inWholeMilliseconds.toFloat() / WRAP_SLIDE_MS)
            .coerceIn(0f, 1f)
    }

    /** 取该行过渡起点的行高 */
    fun wrapFromHeightOf(index: Int): Float = wrapSlides[index]?.fromHeight ?: 0f
    /** 音频物理能量平滑值 (直接存内部运行态, 绝不触发视图重组) */
    var energy by mutableFloatStateOf(0f)
    /** [energy] 的采样时刻 (播放位置 ms), 供绘制期判断管道是否断供 */
    var energyAtMs by mutableIntStateOf(0)
    /**
     * 最终展示的能量 (0~1)。
     * 绘制期只读这一个值: 它变化 -> 只失效重绘。
     */
    var smoothEnergy by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    // 手动滚动计时信号 (每次 +1 重启 5s 回中; 复刻 removeCallbacks+postDelayed)
    var manualTick by mutableIntStateOf(0)
    var autoScroll = true
    /** 拖动中: 原版只在 ACTION_UP 才 postDelayed(autoResetRunnable), 故长拖期间不会中途回中 */
    var dragging = false
    var scrollJob: Job? = null
    var colorJob: Job? = null

    /**
     * 绘制期取用当前驱动值: 正常播放时 PCM 处理器每 10~40ms 派发一次, 直接用最新值;
     * 只有静默超过 [ENERGY_HOLD_MS] (暂停/缓冲/跳转断供) 才开始指数衰减到 0,
     * 不会把逐次派发的阶梯锯成锯齿。
     */
    fun decayedEnergy(nowMs: Int): Float {
        val base = energy
        if (base <= 0f) return 0f
        val idleMs = nowMs - energyAtMs - ENERGY_HOLD_MS
        return if (idleMs <= 0) base else base * exp(-idleMs / ENERGY_DECAY_TAU_MS)
    }

    /** 静音/暂停: 显示值归零, 避免恢复播放瞬间从陈旧的亮度跳下来 */
    fun resetEnergyEnvelope() {
        smoothEnergy = 0f
    }

    /**
     * 帧循环逐帧推进显示平滑: 只一层非对称一阶平滑 (上升 90ms / 下降 380ms)。
     * 自归一化与拍点包络已经在 PCM 侧完成, 这里绝不能再做窗口归一化。
     */
    fun advanceEnergy(dtMs: Float, target: Float) {
        val tau = if (target > smoothEnergy) ENERGY_DISPLAY_ATTACK_MS else ENERGY_DISPLAY_RELEASE_MS
        smoothEnergy += (target - smoothEnergy) * (1f - exp(-dtMs / tau))
    }

    private fun resetWrapSlide() {
        wrapSlides.clear()
    }

    /**
     * 行高过渡: **行数发生变化** (变多或变少) 时启动, 返回进度 0~1 (1 = 不需要过渡)。
     *
     * 只管"这一行占多高" —— 下方各行据此被平滑推开/拉回。逐字位移不走这条时间轴,
     * 见 [trackCharRuns]: 字号连续变大时排版可能重排多次, 行级过渡跟着重排重启没问题
     * (高度是单调的), 但字的位置必须各自接着飞。
     */
    fun trackWrap(index: Int, rows: Int, height: Float): Float {
        val slide = wrapSlides.getOrPut(index) { WrapSlide() }
        if (slide.lastRows != 0 && rows != slide.lastRows) {
            slide.fromHeight = slide.lastHeight
            slide.start = TimeSource.Monotonic.markNow()
        }
        slide.lastRows = rows
        slide.lastHeight = height
        val start = slide.start ?: return 1f
        val fraction = start.elapsedNow().inWholeMilliseconds.toFloat() / WRAP_SLIDE_MS
        if (fraction >= 1f) {
            slide.start = null
            return 1f
        }
        return MOTION_STANDARD.transform(fraction.coerceIn(0f, 1f))
    }

    /** 只保留还在用的行状态 (当前行与上一行), 其余清掉 */
    fun retainWrapSlides(current: Int, last: Int) {
        val it = wrapSlides.keys.iterator()
        while (it.hasNext()) {
            val index = it.next()
            if (index != current && index != last) it.remove()
        }
    }

    /**
     * 行中心 (滚动定位基准, 复刻 scrollYOffset = 当前行中心)。
     * 放大只向下长, 所以用基础行高算中心: 唱到一半时不会因为行变高而产生漂移。
     */
    fun centerOf(index: Int): Float = lines[index].let { it.offset + it.height / 2f }

    /**
     * 内容坐标 y 落在哪一行: 放大会改变行高, 所以按各行的实际高度逐行累加定位。
     * 高度用绘制期记下的值 (静止行就是基础高度, 当前行与淡出行是放大后的高度)。
     */
    fun lineIndexAt(contentY: Float): Int {
        var shift = 0f
        for (i in lines.indices) {
            val line = lines[i]
            val height = if (line.displayHeight > 0) line.displayHeight else line.height
            val top = line.offset + shift
            if (contentY < top) return -1
            if (contentY < top + height) return i
            shift += height - line.height
        }
        return -1
    }

    /** 平滑滚动到目标: 时长取 [SCROLL_MS] (与切行时间轴同长), 曲线按落位缓动。 */
    fun scrollTo(target: Float, scope: CoroutineScope) {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            animate(
                scrollY,
                target,
                animationSpec = tween(SCROLL_MS, easing = MOTION_SETTLE),
            ) { v, _ ->
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
        lastProgress = 1f
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
        lastProgress = 1f
        fillClockMs = 0
        maxSungProgress = 0f
        prevSungProgress = 0f
        resetWrapSlide()
        energy = 0f
        energyAtMs = 0
        smoothEnergy = 0f
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
    style: TextStyle,
): List<MeasuredLrcLine> {
    val src = data?.lines
    if (availableW <= 0 || src.isNullOrEmpty()) return emptyList()
    var offset = 0f
    return src.mapIndexed { i, line ->
        val layout = measurer.measure(
            line.text, style, constraints = Constraints(maxWidth = availableW)
        )
        MeasuredLrcLine(
            line.timeMs,
            line.text,
            layout,
            layout.size.height + marginPx.roundToInt(),
            offset,
            line.fillSamples(src.getOrNull(i + 1)?.timeMs),
            line.bodyChars(),
        ).also { offset += it.height }
    }
}

/** 复刻 android.view.animation.DecelerateInterpolator: f(t) = 1 - (1-t)^2 */
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

