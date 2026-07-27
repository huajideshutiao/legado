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
 * @param onTogglePlayPause 空格触发播放/暂停切换
 * @param onSeekDelta 视频用, 左右方向键 seek (传入 ±10000ms); null 时方向键降级为上/下一章
 * @param onPrev 上一章/上一页 (方向键左/上)
 * @param onNext 下一章/下一页 (方向键右/下)
 * @param onSpeedChange 视频用, 长按右方向键倍速 (2.0f), 松开恢复 (1.0f)
 * @param onBack ESC/Backspace 返回
 * @param onPrevChapter 独立上下章节占位, 暂不绑定按键
 * @param onNextChapter 独立上下章节占位, 暂不绑定按键
 * @param scope 由调用方传入 rememberCoroutineScope(), 用于管理长按 timer
 */
fun Modifier.handleMediaKeys(
    onTogglePlayPause: () -> Unit = {},
    onSeekDelta: ((Long) -> Unit)? = null,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onBack: () -> Unit = {},
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    scope: CoroutineScope,
): Modifier = composed {
    // 长按倍速 timer + 按住防抖状态, remember 保证跨重组持久化
    val state = remember { MediaKeyState() }
    onPreviewKeyEvent { event ->
        when (event.key) {
            Key.DirectionRight -> {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        // 自动重复的 KeyDown 只消费不重复触发
                        // (否则重复 seek + 重启 timer, 长按倍速永远到不了 400ms 阈值)
                        if (state.heldKeys.add(Key.DirectionRight)) {
                            // 短按立即触发 seek/next (长按则由 timer 接管倍速)
                            if (onSeekDelta != null) onSeekDelta(10_000L) else onNext()
                            // 启动长按倍速 timer (400ms 阈值)
                            state.speedJob?.cancel()
                            state.longPressActive = false
                            state.speedJob = scope.launch {
                                delay(400)
                                state.longPressActive = true
                                onSpeedChange(2.0f)
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        // 取消长按 timer, 若已触发倍速则恢复正常
                        state.heldKeys.remove(Key.DirectionRight)
                        state.speedJob?.cancel()
                        state.speedJob = null
                        if (state.longPressActive) {
                            state.longPressActive = false
                            onSpeedChange(1.0f)
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

// 长按倍速 timer 状态 + 按住键防抖标记
private class MediaKeyState {
    var speedJob: Job? = null
    var longPressActive: Boolean = false

    /** 当前按住未松开的键 (吞掉自动重复 KeyDown, 一次按压只触发一次动作) */
    val heldKeys = mutableSetOf<Key>()
}
