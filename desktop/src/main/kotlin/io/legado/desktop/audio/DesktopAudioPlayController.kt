package io.legado.desktop.audio

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import kotlin.coroutines.CoroutineContext

/**
 * [AudioPlayController] 的 desktop 实现, 包装 [DesktopAudioPlayer] (mediamp-mpv)。
 *
 * 对标 app 端 [io.legado.app.model.audio.ExoPlayerAudioPlayController] 包装 ExoPlayer,
 * 供 shared commonMain [io.legado.app.model.audio.AudioPlaySession] 注入使用。
 *
 * # 与 ExoPlayer 行为差异
 * - playWhenReady: 存到 [onReady] 才应用 (mpv 在 prepare 阶段 play() 无效), 语义与 Media3
 *   一致 —— 缓冲期按过暂停, 就绪后不自动起播
 * - release: 桌面会话结束只 stop 不 release (mpv 实例贵, 下一轮复用), 退出/换源才释放
 */
class DesktopAudioPlayController(private val player: DesktopAudioPlayer) :
    AudioPlayController, DesktopAudioPlayer.Listener {

    override var listener: AudioPlayControllerListener? = null

    private var state = AudioPlayController.STATE_IDLE

    /** prepare 就绪后再应用的起播位置 (同 iOS / 鸿蒙 controller)。 */
    private var pendingSeekMs = 0L

    /**
     * 本轮 prepare 是否已报过错。
     *
     * mediamp 对同一次 open 失败会同时 commit MediaStatus.Error **并** 让 setMediaData 抛出
     * (AbstractMediampPlayer.runOpen 用的是同一个 PlaybackException), [DesktopAudioPlayer]
     * 两条都接, 于是一次失败来两次 onError。上层的"首错静默重试一次"会被第二条顶掉,
     * 表现为"报错了但播放照旧"(静默重试其实已经成功), 故在此收敛成一轮一次。
     */
    private var errorReported = false

    override var playWhenReady = false

    init {
        player.listener = this
    }

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val duration: Long
        get() = player.duration

    override val currentPosition: Long
        get() = player.currentPosition

    override val bufferedPosition: Long
        get() = player.bufferedPosition

    override val playbackState: Int
        get() = state

    /** 设置播放源 (直链 + 请求头), [startPosMs] 就绪后生效。 */
    fun setSource(url: String, headers: Map<String, String>, startPosMs: Long) {
        pendingSeekMs = startPosMs
        state = AudioPlayController.STATE_IDLE
        player.setUrl(url, headers)
    }

    override fun prepare() {
        errorReported = false
        state = AudioPlayController.STATE_BUFFERING
        player.prepare()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() {
        state = AudioPlayController.STATE_IDLE
        player.stop()
    }

    override fun seekTo(position: Long) {
        if (state == AudioPlayController.STATE_READY) {
            player.seekTo(position)
        } else {
            // 未就绪时暂存, 就绪后统一应用
            pendingSeekMs = position
        }
    }

    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)

    // 换源/退出时释放: 停线程/关流/关音频设备 (DesktopAudioPlayer.release 幂等)
    override fun release() = player.release()

    // region DesktopAudioPlayer.Listener -> AudioPlayControllerListener 适配

    override fun onReady(durationMs: Long) {
        errorReported = false
        // READY 未起播时 mpv 无法 seek, player.seekTo 会暂存到 play() 发起 loadfile 后应用
        if (pendingSeekMs > 0) {
            player.seekTo(pendingSeekMs)
            pendingSeekMs = 0
        }
        state = AudioPlayController.STATE_READY
        if (playWhenReady) player.play()
        listener?.onPlaybackStateChanged(AudioPlayController.STATE_READY)
    }

    override fun onEndOfMedia() {
        state = AudioPlayController.STATE_ENDED
        listener?.onPlaybackStateChanged(AudioPlayController.STATE_ENDED)
    }

    override fun onError(message: String?) {
        if (errorReported) return
        errorReported = true
        state = AudioPlayController.STATE_IDLE
        listener?.onPlayerError(RuntimeException(message ?: "play error"))
    }

    // endregion
}

/**
 * [AudioPlayAnalyzeRuleFactory] 的 desktop 实现。
 *
 * 经 [AnalyzeRuleFactories] 创建 [AnalyzeRuleCore] 实例 (desktop 端注册的是 DesktopAnalyzeRule,
 * 具备完整 JS 扩展面); JS 引擎 / 网络 (ajax) 经 desktop Main.kt 已注册的 JsEngines /
 * SourceNetworkProviders 走通。
 *
 * 供 shared [io.legado.app.model.audio.AudioPlayManager] 的 loadCoverUrl / loadLrcData
 * 经工厂创建 AnalyzeRuleCore, 与 app 端 AudioPlayAnalyzeRuleFactoryImpl 行为对齐。
 */
object DesktopAudioPlayAnalyzeRuleFactory : AudioPlayAnalyzeRuleFactory {

    override fun create(
        book: Book,
        bookSource: BookSource,
        chapter: BookChapter,
        coroutineContext: CoroutineContext,
    ): AnalyzeRuleCore {
        return AnalyzeRuleFactories.create(book, bookSource).apply {
            this.coroutineContext = coroutineContext
            setBaseUrl(chapter.url)
            this.chapter = chapter
        }
    }
}
