package io.legado.app.ui.book.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨平台视频播放器抽象。
 *
 * 由各平台 actual 实现 (Android = ExoPlayer, Desktop = mpv 外部进程, iOS/鸿蒙后续)。
 * 状态语义对齐 androidx.media3.common.Player。
 */
interface PlatformPlayer {

    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playbackState: Int
    val playbackSpeed: Float
    val currentPosition: Long
    val duration: Long
    val bufferedPosition: Long

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun seekToDefaultPosition()

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}

/**
 * 平台播放器事件回调, 由各平台 actual 监听器 (ExoPlayer.Listener / mpv IPC 事件流) 转发。
 */
interface PlatformPlayerEventListener {
    fun onPlayingChanged(isPlaying: Boolean)
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, playbackState: Int)
    fun onPlaybackStateChanged(playbackState: Int)
    fun onPlaybackParametersChanged(speed: Float)
    fun onPlayerError(error: Throwable)
    fun onPlaybackEnded()
    fun onPositionChanged(positionMs: Long, bufferedPosition: Long)
    fun onDurationChanged(durationMs: Long)
}

/**
 * 播放器状态持有者, 供 Compose `collectAsState` 订阅。
 *
 * 平台 Listener 调用 update* 方法回填, UI 通过只读 [StateFlow] 订阅。
 */
class PlayerStateHolder {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    private val _playbackState = MutableStateFlow(PlatformPlayer.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    fun updatePlaying(value: Boolean) {
        _isPlaying.value = value
    }

    fun updatePlayWhenReady(value: Boolean) {
        _playWhenReady.value = value
    }

    fun updatePlaybackState(value: Int) {
        _playbackState.value = value
    }

    fun updatePlaybackSpeed(value: Float) {
        _playbackSpeed.value = value
    }

    fun updateCurrentPosition(value: Long) {
        _currentPosition.value = value
    }

    fun updateDuration(value: Long) {
        _duration.value = value
    }

    fun updateBufferedPosition(value: Long) {
        _bufferedPosition.value = value
    }

    fun reset() {
        _isPlaying.value = false
        _playWhenReady.value = false
        _playbackState.value = PlatformPlayer.STATE_IDLE
        _playbackSpeed.value = 1f
        _currentPosition.value = 0L
        _duration.value = 0L
        _bufferedPosition.value = 0L
    }
}
