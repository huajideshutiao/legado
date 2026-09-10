package io.legado.app.model.audio

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import kotlin.coroutines.CoroutineContext

/**
 * [AudioPlayController] 的 Android ExoPlayer 实现。
 *
 * 包装 Media3 [ExoPlayer], 实现 [Player.Listener] 将 ExoPlayer 回调
 * 透传到 [AudioPlayControllerListener]。
 *
 * # 设计说明
 * - 状态常量值与 Media3 `Player.STATE_*` 对齐 (1/2/3/4), `playbackState` 直接透传
 * - `setMediaItem` 不在本接口 (MediaItem 构造依赖 AnalyzeUrl.getMediaItem, app 专属),
 *   由 `AudioPlayService.startPlayback` 直接调 `exoPlayer.setMediaItem(...)` 完成
 * - 本控制器的 `listener` 由 [io.legado.app.model.audio.AudioPlaySession] 构造时接上,
 *   会话在 `onPlaybackStateChanged` / `onPlayerError` 里驱动状态机, 平台副作用经
 *   `AudioPlaySessionHost` 回调到 Service (通知/MediaSession/封面)
 *
 * @param exoPlayer 被包装的 ExoPlayer 实例 (Service 持有, setMediaItem 仍由 Service 直接调)
 */
class ExoPlayerAudioPlayController(
    private val exoPlayer: ExoPlayer
) : AudioPlayController, Player.Listener {

    override var listener: AudioPlayControllerListener? = null

    init {
        exoPlayer.addListener(this)
    }

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override val duration: Long
        get() = exoPlayer.duration

    override val currentPosition: Long
        get() = exoPlayer.currentPosition

    override val bufferedPosition: Long
        get() = exoPlayer.bufferedPosition

    override val playbackState: Int
        get() = exoPlayer.playbackState

    override var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun stop() = exoPlayer.stop()
    override fun seekTo(position: Long) = exoPlayer.seekTo(position)
    override fun setPlaybackSpeed(speed: Float) = exoPlayer.setPlaybackSpeed(speed)
    override fun prepare() = exoPlayer.prepare()
    override fun release() = exoPlayer.release()

    // region Player.Listener -> AudioPlayControllerListener 适配

    override fun onPlaybackStateChanged(playbackState: Int) {
        listener?.onPlaybackStateChanged(playbackState)
    }

    override fun onPlayerError(error: PlaybackException) {
        listener?.onPlayerError(error)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        listener?.onIsPlayingChanged(isPlaying)
    }

    // endregion
}

/**
 * [AudioPlayAnalyzeRuleFactory] 的 Android 实现。
 *
 * 创建 app 端 [AnalyzeRule] (继承 commonMain [AnalyzeRuleCore], 额外实现 JsExtensions),
 * 配置 chapter / baseUrl / coroutineContext 后返回为 [AnalyzeRuleCore] 类型。
 *
 * JS bindings 中 `java` 变量可见 JsExtensions 方法 (实际对象是 AnalyzeRule),
 * 与原 app 端 `AudioPlayService.loadCoverUrl` / `loadLrcData` 中 `AnalyzeRule(book, bookSource)`
 * 行为完全一致。
 */
object AudioPlayAnalyzeRuleFactoryImpl : AudioPlayAnalyzeRuleFactory {

    override fun create(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        coroutineContext: CoroutineContext,
    ): AnalyzeRuleCore {
        return AnalyzeRule(book, bookSource).apply {
            this.coroutineContext = coroutineContext
            setBaseUrl(chapter.url)
            this.chapter = chapter
        }
    }
}
