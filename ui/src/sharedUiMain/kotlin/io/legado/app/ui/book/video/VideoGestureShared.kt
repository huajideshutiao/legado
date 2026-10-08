package io.legado.app.ui.book.video

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import io.legado.app.ui.compose.component.zoomMaxPanX
import io.legado.app.ui.compose.component.zoomMaxPanY
import io.legado.app.utils.format
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** 画面缩放倍率下限/上限 (相对 FIT 显示的倍数)。 */
const val VideoZoomMinScale = 0.5f
const val VideoZoomMaxScale = 4f

/** Ctrl+滚轮每格的缩放倍率 (指数步进: 等格数上/下滚互为逆操作)。 */
private const val VideoWheelZoomStep = 1.15f

/** 滚轮步进小于该值视为无变化 (触控板微量滚动不产生缩放帧)。 */
private const val VideoWheelZoomMinStep = 0.05f

/**
 * 画面缩放接入点 (平台注入)。
 *
 * @param current 读当前缩放状态
 * @param set 写缩放状态 (已按容器与画面尺寸钳制)
 * @param aspectRatio 读画面宽高比 (宽/高); null = 读不到, 边界按铺满容器兜底
 */
class VideoZoomHandle(
    val current: () -> VideoZoom,
    val set: (VideoZoom) -> Unit,
    val aspectRatio: () -> Float? = { null },
)

/**
 * 视频手势 (单击切控制层 / 双击播放暂停 / 长按 2x 倍速松手恢复 /
 * 横滑进度 / 左半竖滑亮度 / 右半竖滑音量) 共享实现。
 *
 * 原 app AndroidVideoGestureHandler 与 desktop DesktopVideoGestureHandler 各写一份且
 * 行为分化, 收拢为单一状态机, 平台仅注入读写槽 (用户拍板 2026-08):
 * - 方向判定死区统一 15dp (原 app 15dp / desktop 4px)
 * - 竖滑响应高度 = 实际容器半高 (原 app 350dp 硬编码 / desktop 整高)
 * - 横滑进度映射用实际容器宽 (原 app screenWidth), 范围恒 ±3 分钟
 * - 拖动结束仅原在播才续播 (原 app 无条件 play() / desktop 恒续播)
 * - 长按反馈文字格式统一 "%.1fX" (去掉 app Locale 显式格式差异)
 * - seek 落点仍走平台注入的 seekTo (两端一致); 控制栏前进/后退按钮保留各平台跳转函数
 *
 * 画面缩放 (双指捏合 + Ctrl+滚轮) 经 [VideoZoomHandle] 接入: 未注入的端不响应缩放,
 * 单指手势语义完全不变。
 */
class VideoGestureController(
    private val isPlaying: () -> Boolean,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val speed: () -> Float,
    private val setSpeed: (Float) -> Unit,
    private val onPlayPause: () -> Unit,
    private val seekTo: (Long) -> Unit,
    /** 读当前亮度/音量 (原始单位); null = 读不到。禁止各端拿固定值 (如 0.5f) 把
     *  “读不到”伪装成“真的读到 50%”—— 那会让手势起手第一帧就把系统亮度/音量硬拉到一半。 */
    private val readBrightness: () -> Float?,
    private val writeBrightness: (Float) -> Unit,
    private val readVolume: () -> Float?,
    private val writeVolume: (Float) -> Unit,
    private val onToggleControls: () -> Unit,
    private val onGestureText: (String?) -> Unit,
    /** 音量调节上限 (原始单位): Android=AudioManager maxVolume, desktop=1 (0..1 归一)。 */
    private val volumeMax: Float = 1f,
    /** 音量量化粒度: Android=1f (AudioManager 整数步进), desktop=0f (连续)。 */
    private val volumeStep: Float = 0f,
    /** 画面缩放接入点; null = 该端未接入 (双指与 Ctrl+滚轮不改变画面, 行为同接入前) */
    private val zoom: VideoZoomHandle? = null,
) {
    /** 长按倍速中 (拖动层据此跳过滑动, 对照原版) */
    var speedBoosted = false
        private set

    /** 是否接入画面缩放 (手势层据此决定双指是否进入缩放手势) */
    val supportsZoom: Boolean get() = zoom != null

    private var originalSpeed = 1f
    private var position = 0L
    private var gestureMode = VideoGestureMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var wasPlayingAtDown = false
    private var lastScrollTime = 0L
    private val scrollThrottleInterval = 32L // ms, 对照原版
    private val brightnessAdjuster = VideoGestureAdjuster()
    private val volumeAdjuster = VideoGestureAdjuster(max = volumeMax)

    /** 手势区当前尺寸 (容器变化时缓存): 视频几何变化后按它重钳位置 */
    private var areaWidth = 0f
    private var areaHeight = 0f

    /** 最后一次写入的反馈文字: 滚轮提示按值比对, 只清自己写的那条 */
    private var lastGestureText: String? = null
    private var wheelGestureText: String? = null

    fun onSingleTap() = onToggleControls()

    fun onDoubleTap() = onPlayPause()

    fun onDown(x: Float, y: Float) {
        startX = x
        startY = y
        wasPlayingAtDown = isPlaying()
    }

    /** 长按 → 当前倍速 ×2 (对照原版 onLongPress), 松手恢复。 */
    fun onLongPress() {
        val current = speed()
        originalSpeed = current
        speedBoosted = true
        val target = current * 2f
        setSpeed(target)
        setGestureText("%.1fX".format(target))
    }

    /**
     * 滑动 (拖动层已越过 touchSlop; 容器实际宽高)。
     *
     * @param deadZonePx 方向判定死区 (统一 15dp 折算 px, 原 desktop 4px 已对齐)
     */
    fun onScroll(x: Float, y: Float, width: Float, height: Float, deadZonePx: Float) {
        val currentTime = systemCurrentTimeMillis()
        if (currentTime - lastScrollTime < scrollThrottleInterval) {
            return
        }
        lastScrollTime = currentTime
        if (gestureMode == VideoGestureMode.NONE) {
            val deltaX = abs(x - startX)
            val deltaY = abs(y - startY)
            if (deltaX < deadZonePx && deltaY < deadZonePx) return
            gestureMode = when {
                // 主轴横滑 → 进度
                deltaX > deltaY -> VideoGestureMode.PROGRESS
                // 对照原版: 按下点 x < 宽/2 → 左半屏亮度; 否则右半屏音量
                startX < width / 2 -> {
                    // 进模式读一次当前亮度 (0..1; 平台 lambda 内自行回落)
                    brightnessAdjuster.onGestureStart(startY) { readBrightness() }
                    VideoGestureMode.BRIGHTNESS
                }

                else -> {
                    // 进模式读一次当前音量 (0..volumeMax 原始单位; 平台 lambda 内自行回落)
                    volumeAdjuster.onGestureStart(startY) { readVolume() }
                    VideoGestureMode.VOLUME
                }
            }
        }
        // 竖滑响应高度 = 实际容器半高 (用户拍板 2026-08; 原 app 350dp / desktop 整高已统一)
        val responseHeight = height / 2f
        when (gestureMode) {
            VideoGestureMode.PROGRESS -> {
                // 整宽映射 ±3 分钟, 相对当前进度 (实际容器宽, 原 app screenWidth 已对齐)
                position = (positionMs() + (x - startX) / width * 180_000).toLong()
                    .coerceIn(0L, durationMs())
                setGestureText(
                    "%s / %s".format(
                        position.toDurationTime(),
                        durationMs().toDurationTime(),
                    )
                )
            }

            VideoGestureMode.BRIGHTNESS -> {
                // 相对调节 + 碰顶/底重置 (共享状态机); 亮度 0..1 连续
                val delta = brightnessAdjuster.onGestureMove(y, responseHeight)
                writeBrightness(delta)
                setGestureText("亮度: %d%%".format((delta * 100).toInt()))
            }

            VideoGestureMode.VOLUME -> {
                // 相对调节 + 碰顶/底重置 (共享状态机); 按平台范围/粒度落盘
                val delta = volumeAdjuster.onGestureMove(y, responseHeight, step = volumeStep)
                writeVolume(delta)
                setGestureText("音量: %d%%".format((delta / volumeMax * 100).toInt()))
            }

            VideoGestureMode.NONE -> {}
        }
    }

    fun onUp() {
        if (speedBoosted) {
            setSpeed(originalSpeed)
            speedBoosted = false
        }
        when (gestureMode) {
            VideoGestureMode.PROGRESS -> {
                seekTo(position)
                // 仅原在播才续播 (用户拍板 2026-08; 拖动前已暂停则不强制播放)
                if (wasPlayingAtDown && !isPlaying()) onPlayPause()
            }

            else -> {}
        }
        gestureMode = VideoGestureMode.NONE
        setGestureText(null)
    }

    /**
     * 双指介入画面缩放: 单指手势作废 (未提交的 seek 不提交, 长按倍速恢复, 反馈文字清空)。
     */
    fun onZoomStart() {
        if (speedBoosted) {
            setSpeed(originalSpeed)
            speedBoosted = false
        }
        gestureMode = VideoGestureMode.NONE
        position = 0L
        setGestureText(null)
    }

    /**
     * 双指变换每帧调用: 以两指质心为锚缩放 + 平移, 结果按容器与画面尺寸钳制。
     *
     * 与图片查看 [io.legado.app.ui.compose.component.zoomable] 同一套公式:
     * “以质心缩放” = “以容器中心缩放 + 质心位移补偿”, 再叠加本帧平移量。
     *
     * @param zoomFactor 本帧捏合倍率 (1f = 无变化)
     * @param panDeltaX 本帧质心横向位移 (px)
     * @param panDeltaY 本帧质心纵向位移 (px)
     * @param centroidX 质心横向位置 (容器坐标)
     * @param centroidY 质心纵向位置 (容器坐标)
     * @param width 手势区宽度
     * @param height 手势区高度
     */
    fun onZoomGesture(
        zoomFactor: Float,
        panDeltaX: Float,
        panDeltaY: Float,
        centroidX: Float,
        centroidY: Float,
        width: Float,
        height: Float,
    ) {
        val access = zoom ?: return
        if (width <= 0f || height <= 0f) return
        val current = access.current()
        val oldScale = current.scale
        val newScale = (oldScale * zoomFactor).coerceIn(VideoZoomMinScale, VideoZoomMaxScale)
        val k = newScale / oldScale
        val anchorDx = (centroidX - width / 2f) * (1f - k)
        val anchorDy = (centroidY - height / 2f) * (1f - k)
        val aspectRatio = access.aspectRatio()
        access.set(
            clampZoom(
                scale = newScale,
                panXPx = current.panX * zoomMaxPanX(width, height, aspectRatio, oldScale) * k +
                    anchorDx + panDeltaX,
                panYPx = current.panY * zoomMaxPanY(width, height, aspectRatio, oldScale) * k +
                    anchorDy + panDeltaY,
                width = width,
                height = height,
                aspectRatio = aspectRatio,
            )
        )
        setGestureText("%d%%".format((newScale * 100f).roundToInt()))
    }

    /**
     * Ctrl+滚轮缩放 (鼠标/触控板; 与 mpv 默认绑定同语义: 以指针位置为锚)。
     *
     * @param scrollDeltaY 滚轮原始纵向滚动量 (负值 = 向上滚 = 放大, 同 Compose 约定)
     */
    fun onWheelZoom(
        scrollDeltaY: Float,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        if (zoom == null) return
        // 各端滚轮步进单位不一 (行数/像素), 夹到 ±3 格防一次滚动跳变到极限
        val steps = (-scrollDeltaY).coerceIn(-3f, 3f)
        if (abs(steps) < VideoWheelZoomMinStep) return
        onZoomGesture(
            zoomFactor = VideoWheelZoomStep.pow(steps),
            panDeltaX = 0f,
            panDeltaY = 0f,
            centroidX = x,
            centroidY = y,
            width = width,
            height = height,
        )
        wheelGestureText = lastGestureText
    }

    /**
     * 手势区尺寸变化 (窗口缩放/横竖屏): 记下尺寸; 位置是“可平移范围的比例”, 容器变化本身无需换算,
     * 只有某轴不再溢出 (画面不比容器大) 时才把该轴位置归零 —— 否则残留比例会把画面推出去。
     */
    fun onContainerSizeChanged(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        areaWidth = width
        areaHeight = height
        reclamp()
    }

    /**
     * 视频显示几何变化 (装载完成 / 换分辨率, 容器尺寸不变): 按当前手势区尺寸重钳位置。
     * 桌面 mpv 的 align 不会自愈, 只有状态侧收敛才能保证四端表现一致。
     */
    fun onVideoGeometryChanged() = reclamp()

    private fun reclamp() {
        val access = zoom ?: return
        if (areaWidth <= 0f || areaHeight <= 0f) return
        val current = access.current()
        if (current.isDefault) return
        val aspectRatio = access.aspectRatio()
        access.set(
            current.copy(
                panX = if (zoomMaxPanX(areaWidth, areaHeight, aspectRatio, current.scale) > 0f) {
                    current.panX
                } else 0f,
                panY = if (zoomMaxPanY(areaWidth, areaHeight, aspectRatio, current.scale) > 0f) {
                    current.panY
                } else 0f,
            )
        )
    }

    /**
     * 手势反馈文字单一写入点 ([lastGestureText] 与之一致); 滚轮提示按值比对, 只清自己写的那条 ——
     * 期间开始了别的手势 (进度/亮度/音量/捏合) 就保留那个手势的提示。
     */
    private fun setGestureText(text: String?) {
        lastGestureText = text
        onGestureText(text)
    }

    /** 滚轮缩放没有“抬手”清除时机, 由手势层计时收掉。 */
    fun hideGestureText() {
        if (lastGestureText == wheelGestureText) setGestureText(null)
    }

    /** 像素平移 → 边界钳制 → 折成“可平移范围”的比例 (内容 FIT 后超出容器的部分才可平移)。 */
    private fun clampZoom(
        scale: Float,
        panXPx: Float,
        panYPx: Float,
        width: Float,
        height: Float,
        aspectRatio: Float?,
    ): VideoZoom {
        val maxX = zoomMaxPanX(width, height, aspectRatio, scale)
        val maxY = zoomMaxPanY(width, height, aspectRatio, scale)
        // 非有限值一律归零: 质心取不到时上游会给占位值, 但 NaN 一旦落进状态就再也不会自愈
        val px = if (panXPx.isFinite()) panXPx else 0f
        val py = if (panYPx.isFinite()) panYPx else 0f
        return VideoZoom(
            scale = scale,
            panX = if (maxX > 0f) px.coerceIn(-maxX, maxX) / maxX else 0f,
            panY = if (maxY > 0f) py.coerceIn(-maxY, maxY) / maxY else 0f,
        )
    }
}

private enum class VideoGestureMode { NONE, PROGRESS, BRIGHTNESS, VOLUME }

/**
 * 手势层 (原 app AndroidVideoGestureOverlay / desktop DesktopVideoGestureOverlay 各写一份,
 * 收拢为共享): 单击/双击/长按 + 滑动/抬手, 锁定态旁路。
 * 拖动越过 touchSlop 才消费事件 (同时打断 tap 层); 长按倍速期间不响应滑动 (对照原版)。
 * 双指接入画面缩放后, 本手势内不再回到单指逻辑 (与图片查看 [io.legado.app.ui.compose.component.zoomable] 一致)。
 *
 * @param deadZone 方向判定死区 (统一 15dp, 用户拍板 2026-08)
 */
@Composable
fun VideoGestureOverlay(
    handler: VideoGestureController,
    locked: Boolean,
    modifier: Modifier,
    deadZone: Dp = 15.dp,
) {
    val haptic = LocalHapticFeedback.current
    // 滚轮缩放没有“抬手”清除时机: 反馈文字靠计时收掉 (连续滚动期间不断续期)
    var wheelZoomTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(wheelZoomTick) {
        if (wheelZoomTick > 0) {
            delay(1000)
            handler.hideGestureText()
        }
    }
    Box(
        modifier
            .onSizeChanged {
                handler.onContainerSizeChanged(it.width.toFloat(), it.height.toFloat())
            }
            .pointerInput(handler, locked) {
                if (locked) return@pointerInput
                detectTapGestures(
                    onTap = { handler.onSingleTap() },
                    onDoubleTap = { handler.onDoubleTap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler.onLongPress()
                    },
                )
            }
            .pointerInput(handler, locked, deadZone) {
                if (locked) return@pointerInput
                val deadZonePx = deadZone.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    handler.onDown(down.position.x, down.position.y)
                    var dragging = false
                    var zooming = false
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (handler.supportsZoom && pressedCount >= 2) {
                                // 双指进入画面缩放: 单指手势作废 (未提交的 seek 不提交, 长按倍速恢复)
                                if (!zooming) {
                                    zooming = true
                                    handler.onZoomStart()
                                }
                                // 两指同批落下时质心取不到 (框架返回 Unspecified), 退回容器中心
                                val centroid = event.calculateCentroid()
                                val pan = event.calculatePan()
                                handler.onZoomGesture(
                                    zoomFactor = event.calculateZoom(),
                                    panDeltaX = pan.x,
                                    panDeltaY = pan.y,
                                    centroidX = if (centroid.isSpecified) centroid.x else width / 2f,
                                    centroidY = if (centroid.isSpecified) centroid.y else height / 2f,
                                    width = width,
                                    height = height,
                                )
                                event.changes.fastForEach { it.consume() }
                                continue
                            }
                            if (zooming) {
                                // 双指抬起后本手势内不再回到单指逻辑; 只有指针真的全部抬起才结束 ——
                                // 鼠标 hover (Move) 与滚轮 (Scroll) 事件里不含按住的手指, 不能当成手势结束
                                event.changes.fastForEach { if (it.pressed) it.consume() }
                                if (pressedCount == 0 && event.type == PointerEventType.Release) break
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // 同上: 事件不含本手势指针时跳过, 只有抬起事件才算手势结束
                                if (event.type == PointerEventType.Release) break else continue
                            }
                            if (change.changedToUpIgnoreConsumed()) break
                            if (handler.speedBoosted) continue
                            if (!dragging) {
                                val slop = viewConfiguration.touchSlop
                                val delta = change.position - down.position
                                dragging = abs(delta.x) > slop || abs(delta.y) > slop
                            }
                            if (dragging) {
                                change.consume()
                                handler.onScroll(
                                    change.position.x,
                                    change.position.y,
                                    width,
                                    height,
                                    deadZonePx,
                                )
                            }
                        }
                    } finally {
                        handler.onUp()
                    }
                }
            }
            .pointerInput(handler, locked) {
                if (locked) return@pointerInput
                if (!handler.supportsZoom) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        // 只认 Ctrl+滚轮: 无修饰键的滚轮在视频区没有既有语义, 不作缩放占用
                        if (!event.keyboardModifiers.isCtrlPressed) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta.y
                        if (delta == 0f) continue
                        handler.onWheelZoom(
                            scrollDeltaY = delta,
                            x = change.position.x,
                            y = change.position.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                        )
                        change.consume()
                        wheelZoomTick++
                    }
                }
            }
    )
}

/**
 * 控制层回显轮询 (500ms) + 自动隐藏 (5s) 共享实现。
 *
 * 原 app/desktop 渲染层各写一份且分化 (app 缓冲中也计时, desktop 仅 isPlaying; desktop
 * 轮询顺带回写缓冲态到 UiState)。收拢后:
 * - [poll] 由平台注入 (读 position/duration/buffered + 可选回写 UiState)
 * - 自动隐藏条件统一为 [autoHideActive] (用户拍板: 缓冲中也自动隐藏)
 */
@Composable
fun VideoPlaybackPoller(
    controlsVisible: Boolean,
    autoHideActive: Boolean,
    seeking: Boolean,
    locked: Boolean,
    onAutoHide: () -> Unit,
    poll: suspend () -> Unit,
    pollIntervalMs: Long = 500,
    autoHideDelayMs: Long = 5000,
) {
    LaunchedEffect(controlsVisible) {
        while (controlsVisible) {
            poll()
            delay(pollIntervalMs)
        }
    }
    LaunchedEffect(controlsVisible, autoHideActive, seeking, locked) {
        if (controlsVisible && autoHideActive && !seeking && !locked) {
            delay(autoHideDelayMs)
            onAutoHide()
        }
    }
}
