package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 视频控制层宿主状态: 进度轮询 / 自动隐藏 / 拖动暂停。
 *
 * 替代 app 端 AppVideoControlsHost 自管的 positionMs/bufferedMs/durationMs/seeking
 * + 进度轮询 LaunchedEffect + 自动隐藏 LaunchedEffect。
 * 可见性 (controlsVisible) 由调用方管理, 经 [rememberVideoControlsController] 的
 * controlsVisible lambda 注入; 超时后回调 onAutoHide 由调用方落盘 (如 activity.controlsVisible = false)。
 */
class VideoControlsController internal constructor(
    private val player: PlatformPlayer,
    private val interval: Long,
    private val autoHideTimeout: Long,
) {
    // Compose 编译器对 _seeking backing field 会生成 mangled accessor <set-seeking>,
    // 与显式 setSeeking 在 JVM 平台签名冲突, 改用 by 委托消除冲突
    var seeking by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
        internal set
    var bufferedMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var playingOrBuffering by mutableStateOf(false)
        internal set
}

/**
 * 创建控制层宿主控制器: 内部挂进度轮询 + 自动隐藏 LaunchedEffect。
 *
 * @param player 平台播放器 (null 时返回 null, 不挂任何 Effect)
 * @param controlsVisible 可见性读取 (调用方持有源真值, 如 activity.controlsVisible)
 * @param onAutoHide 自动隐藏触发回调 (调用方落盘, 如 activity.controlsVisible = false)
 * @param interval 进度轮询间隔 (默认 500ms, 对齐 app 原 ExoPlayer 轮询)
 * @param autoHideTimeout 自动隐藏超时 (默认 5000ms, 对齐原 controllerShowTimeoutMs)
 */
@Composable
fun rememberVideoControlsController(
    player: PlatformPlayer?,
    controlsVisible: () -> Boolean,
    onAutoHide: () -> Unit,
    interval: Long = 500L,
    autoHideTimeout: Long = 5000L,
): VideoControlsController? {
    val onAutoHideLatest = rememberUpdatedState(onAutoHide)
    if (player == null) return null
    val controller = remember(player, interval, autoHideTimeout) {
        VideoControlsController(player, interval, autoHideTimeout)
    }
    val visible = controlsVisible()
    // 进度轮询: 仅控制层可见时
    LaunchedEffect(player, visible) {
        while (visible) {
            controller.positionMs = player.currentPosition.coerceAtLeast(0L)
            controller.bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            controller.durationMs = player.duration.coerceAtLeast(0L)
            controller.playingOrBuffering = player.isPlaying ||
                (player.playWhenReady && player.playbackState == PlatformPlayer.STATE_BUFFERING)
            delay(interval)
        }
    }
    // 自动隐藏: 播放/缓冲中且非拖动, 超时后回调
    LaunchedEffect(visible, controller.playingOrBuffering, controller.seeking) {
        if (visible && controller.playingOrBuffering && !controller.seeking) {
            delay(autoHideTimeout)
            onAutoHideLatest.value()
        }
    }
    return controller
}
