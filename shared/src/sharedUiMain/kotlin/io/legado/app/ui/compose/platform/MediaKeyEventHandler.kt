package io.legado.app.ui.compose.platform

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通用媒体键处理 (视频/音频/漫画播放器复用)。
 * 捕获阶段拦截, 桌面端无系统媒体键, 键盘等价替代。
 *
 * 空格/上/下/右带按住防抖 (KeyEventType 无 KeyRepeat, 用 heldKeys 自管, 一次按压只触发一次);
 * 左方向键不防抖, 按住连续后退 seek 是预期行为。
 *
 * 右方向键三段式: 300ms 死区 (不触发任何操作) → seek +10s → 再 500ms (总计 800ms) → 2x 倍速
 * 模拟 Android 触摸手势的 touch slop / long press 机制。
 *
 * @param onTogglePlayPause 空格触发播放/暂停切换
 * @param onSeekDelta 视频用, 左右方向键 seek (传入 ±10000ms); null 时方向键降级为上/下一章
 * @param onPrev 上一章/上一页 (方向键左/上)
 * @param onNext 下一章/下一页 (方向键右/下)
 * @param onSpeedChange 视频用, 长按右方向键倍速 (2.0f), 松开恢复 (1.0f)
 * @param onGestureText 手势/按键反馈文字回调 (如 "2.0X"), null 时隐藏; 由调用方渲染提示 UI
 * @param onBack ESC/Backspace 返回
 * @param scope 由调用方传入 rememberCoroutineScope(), 用于管理长按 timer
 */
fun Modifier.handleMediaKeys(
    onTogglePlayPause: () -> Unit = {},
    onSeekDelta: ((Long) -> Unit)? = null,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onGestureText: ((String?) -> Unit)? = null,
    onBack: () -> Unit = {},
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    scope: CoroutineScope,
): Modifier = composed {
    val state = remember { MediaKeyState() }
    onPreviewKeyEvent { event ->
        when (event.key) {
            Key.DirectionRight -> {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (state.heldKeys.add(Key.DirectionRight)) {
                            state.speedJob?.cancel()
                            state.longPressActive = false
                            // 长短按判定窗口: 按下后 500ms 内松开 = 短按 (seek +10s);
                            // 按住 ≥500ms = 长按 (2x 倍速, 不 seek)。按下时不立即动作,
                            // 否则无法区分短/长按 (用户拍板 2026-08)。
                            state.speedJob = scope.launch {
                                delay(LONG_PRESS_WINDOW_MS)
                                state.longPressActive = true
                                onSpeedChange(2.0f)
                                onGestureText?.invoke("2.0X")
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        state.heldKeys.remove(Key.DirectionRight)
                        state.speedJob?.cancel()
                        state.speedJob = null
                        if (state.longPressActive) {
                            // 长按松开: 恢复倍速, 不 seek
                            state.longPressActive = false
                            onSpeedChange(1.0f)
                            onGestureText?.invoke(null)
                        } else {
                            // 判定窗口内松开 = 短按: seek +10s (或下一章), 与左键对称
                            if (onSeekDelta != null) onSeekDelta(10_000L) else onNext()
                        }
                        false
                    }
                    else -> false
                }
            }
            Key.DirectionLeft -> {
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (onSeekDelta != null) onSeekDelta(-10_000L) else onPrev()
                true
            }
            Key.DirectionUp -> when (event.type) {
                KeyEventType.KeyDown -> {
                    if (state.heldKeys.add(Key.DirectionUp)) onPrev()
                    true
                }
                KeyEventType.KeyUp -> {
                    state.heldKeys.remove(Key.DirectionUp)
                    false
                }
                else -> false
            }
            Key.DirectionDown -> when (event.type) {
                KeyEventType.KeyDown -> {
                    if (state.heldKeys.add(Key.DirectionDown)) onNext()
                    true
                }
                KeyEventType.KeyUp -> {
                    state.heldKeys.remove(Key.DirectionDown)
                    false
                }
                else -> false
            }
            Key.Spacebar -> when (event.type) {
                KeyEventType.KeyDown -> {
                    if (state.heldKeys.add(Key.Spacebar)) onTogglePlayPause()
                    true
                }
                KeyEventType.KeyUp -> {
                    state.heldKeys.remove(Key.Spacebar)
                    false
                }
                else -> false
            }
            Key.Escape, Key.Backspace -> {
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                onBack()
                true
            }
            else -> false
        }
    }
}

// ---- Compose 节点级状态 ----
private class MediaKeyState {
    var speedJob: Job? = null
    var longPressActive: Boolean = false

    /** 当前按住未松开的键 (吞掉自动重复 KeyDown, 一次按压只触发一次动作) */
    val heldKeys = mutableSetOf<Key>()
}

/** 右方向键长短按判定窗口: 500ms 内松开=短按 (seek), 超过=长按 (2x 倍速)。 */
private const val LONG_PRESS_WINDOW_MS = 500L
