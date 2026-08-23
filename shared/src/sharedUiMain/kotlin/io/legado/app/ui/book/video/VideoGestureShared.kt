package io.legado.app.ui.book.video

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.utils.format
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.delay
import kotlin.math.abs

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
 */
class VideoGestureController(
    private val isPlaying: () -> Boolean,
    private val positionMs: () -> Long,
    private val durationMs: () -> Long,
    private val speed: () -> Float,
    private val setSpeed: (Float) -> Unit,
    private val onPlayPause: () -> Unit,
    private val seekTo: (Long) -> Unit,
    private val readBrightness: () -> Float,
    private val writeBrightness: (Float) -> Unit,
    private val readVolume: () -> Float,
    private val writeVolume: (Float) -> Unit,
    private val onToggleControls: () -> Unit,
    private val onGestureText: (String?) -> Unit,
    /** 音量调节上限 (原始单位): Android=AudioManager maxVolume, desktop=1 (0..1 归一)。 */
    private val volumeMax: Float = 1f,
    /** 音量量化粒度: Android=1f (AudioManager 整数步进), desktop=0f (连续)。 */
    private val volumeStep: Float = 0f,
) {
    /** 长按倍速中 (拖动层据此跳过滑动, 对照原版) */
    var speedBoosted = false
        private set

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
        onGestureText("%.1fX".format(target))
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
                onGestureText(
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
                onGestureText("亮度: %d%%".format((delta * 100).toInt()))
            }

            VideoGestureMode.VOLUME -> {
                // 相对调节 + 碰顶/底重置 (共享状态机); 按平台范围/粒度落盘
                val delta = volumeAdjuster.onGestureMove(y, responseHeight, step = volumeStep)
                writeVolume(delta)
                onGestureText("音量: %d%%".format((delta / volumeMax * 100).toInt()))
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
        onGestureText(null)
    }
}

private enum class VideoGestureMode { NONE, PROGRESS, BRIGHTNESS, VOLUME }

/**
 * 手势层 (原 app AndroidVideoGestureOverlay / desktop DesktopVideoGestureOverlay 各写一份,
 * 收拢为共享): 单击/双击/长按 + 滑动/抬手, 锁定态旁路。
 * 拖动越过 touchSlop 才消费事件 (同时打断 tap 层); 长按倍速期间不响应滑动 (对照原版)。
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
    Box(
        modifier
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
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
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
