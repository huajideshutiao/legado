package io.legado.app.model.audio

/**
 * 音频播放控制器抽象 (KMP commonMain)。
 *
 * 对标 [io.legado.app.help.tts.HttpTtsPlayer]: 仅描述"控制底层播放器 + 查询状态"所需
 * 的最小能力, 不暴露平台特定类型 (Media3 `Player` / ExoPlayer / AVPlayer)。
 *
 * # 各平台 actual 实现
 * - app: 包装 Media3 `ExoPlayer`, 见
 *   `app/src/main/java/io/legado/app/model/audio/ExoPlayerAudioPlayController.kt`
 * - desktop: `javax.sound.sampled` 直播 wav/pcm (待实现)
 * - ios: AVPlayer (待实现)
 * - ohos: 鸿蒙 AVPlayer (待实现)
 *
 * # 设计原则
 * - 仅描述播放/暂停/seek/速率/状态查询, 不暴露 `setMediaItem` —— MediaItem 构造
 *   依赖 Media3 / AnalyzeUrl.getMediaItem (app 专属), 由 Service 直接调 ExoPlayer
 *   完成, 不经本接口
 * - 状态变化统一通过 [AudioPlayControllerListener], 避免平台 listener 类型泄漏
 *   (Media3 `Player.Listener` → 本接口)
 * - 状态常量值与 Media3 `Player.STATE_*` 对齐, 便于 app actual 直接透传
 *
 * 模式参考 [io.legado.app.help.tts.HttpTtsPlayer]。
 */
interface AudioPlayController {

    /** 是否正在播放。 */
    val isPlaying: Boolean

    /** 总时长 (毫秒), 未知时为 0 (与 Media3 `Player.getDuration` 一致)。 */
    val duration: Long

    /** 当前播放位置 (毫秒)。 */
    val currentPosition: Long

    /** 已缓冲位置 (毫秒)。 */
    val bufferedPosition: Long

    /** 播放状态, 取值为 [STATE_IDLE] / [STATE_BUFFERING] / [STATE_READY] / [STATE_ENDED]。 */
    val playbackState: Int

    /** 播放/暂停就绪标志 (Media3 `playWhenReady` 语义)。 */
    var playWhenReady: Boolean

    /** 播放状态回调 (对标 Media3 `Player.Listener`)。 */
    var listener: AudioPlayControllerListener?

    /** 开始/恢复播放。 */
    fun play()

    /** 暂停播放。 */
    fun pause()

    /** 停止播放并清空状态 (但不释放资源)。 */
    fun stop()

    /** 跳转到指定位置 (毫秒)。 */
    fun seekTo(position: Long)

    /** 设置播放速率 (1.0 = 正常速度)。 */
    fun setPlaybackSpeed(speed: Float)

    /** 准备播放 (缓冲首帧), 完成后触发 [AudioPlayControllerListener.onPlaybackStateChanged]。 */
    fun prepare()

    /** 释放底层资源。 */
    fun release()

    companion object {
        /** 对应 Media3 `Player.STATE_IDLE`。 */
        const val STATE_IDLE = 1

        /** 对应 Media3 `Player.STATE_BUFFERING`。 */
        const val STATE_BUFFERING = 2

        /** 对应 Media3 `Player.STATE_READY`。 */
        const val STATE_READY = 3

        /** 对应 Media3 `Player.STATE_ENDED`。 */
        const val STATE_ENDED = 4
    }
}

/**
 * 音频播放状态回调 (KMP 版, 对标 Media3 `Player.Listener`)。
 *
 * 各平台 actual 负责把平台 listener 适配为此接口回调。
 * app 端 [ExoPlayerAudioPlayController] 实现 `Player.Listener`,
 * 在 `onPlaybackStateChanged` / `onPlayerError` / `onIsPlayingChanged` 中转发到本接口。
 */
interface AudioPlayControllerListener {

    /** 播放状态变化, [state] 取值见 [AudioPlayController] 的状态常量。 */
    fun onPlaybackStateChanged(state: Int) {}

    /** 播放或网络出错, [error] 为平台原始异常 (Media3 `PlaybackException` 等)。 */
    fun onPlayerError(error: Throwable) {}

    /** isPlaying 变化 (true 开始播放, false 暂停/缓冲/结束)。 */
    fun onIsPlayingChanged(isPlaying: Boolean) {}
}
