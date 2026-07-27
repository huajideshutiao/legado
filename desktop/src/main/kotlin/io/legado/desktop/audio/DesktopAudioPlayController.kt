package io.legado.desktop.audio

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import kotlin.coroutines.CoroutineContext

/**
 * [AudioPlayController] 的 desktop 实现, 包装 [DesktopAudioPlayer] (jlayer)。
 *
 * 对标 app 端 [io.legado.app.model.audio.ExoPlayerAudioPlayController] 包装 ExoPlayer,
 * 供 shared commonMain [io.legado.app.model.audio.AudioPlayManager] 注入使用。
 *
 * # 与 ExoPlayer 行为差异
 * - bufferedPosition: jlayer 流式解码无独立缓冲概念, 恒返回 0
 *   (desktop 原 startProgressReport 不 post AUDIO_BUFFER_PROGRESS, 缓冲条恒 0;
 *   此处保持一致避免 UI 视觉变化)
 * - playbackState: jlayer 无显式状态机, 由 isPlaying 派生
 *   (STATE_READY=播放中, STATE_IDLE=暂停/停止/未启动);
 *   shared upPlayProgressForLrc 用 STATE_IDLE 守卫切歌中场景
 * - playWhenReady: jlayer 无对应概念, desktop 显式调 play(), 恒 false
 * - release: DesktopAudioPlayer 无 release API, no-op
 * - listener: shared AudioPlayManager 不经 controller.listener 感知状态
 *   (app 端由 ExoPlayerAudioPlayController 转发 ExoPlayer 回调),
 *   desktop 状态由 DesktopAudioPlayProvider 直接订阅 DesktopAudioPlayer.Listener,
 *   故 controller.listener 不使用
 */
class DesktopAudioPlayController(private val player: DesktopAudioPlayer) : AudioPlayController {

    override var listener: AudioPlayControllerListener? = null

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val duration: Long
        get() = player.duration

    override val currentPosition: Long
        get() = player.currentPosition

    // jlayer 流式解码无独立缓冲概念; 返回 0 保持 desktop 原 UI 行为 (缓冲条不显示)
    override val bufferedPosition: Long
        get() = 0L

    // jlayer 无显式状态机; 播放中=READY, 否则=IDLE (upPlayProgressForLrc 用 IDLE 守卫切歌)
    override val playbackState: Int
        get() = if (player.isPlaying) AudioPlayController.STATE_READY else AudioPlayController.STATE_IDLE

    // jlayer 无 playWhenReady 概念, desktop 显式调 play()
    override var playWhenReady: Boolean
        get() = false
        set(value) { /* no-op */ }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun seekTo(position: Long) = player.seekTo(position)
    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)
    override fun prepare() = player.prepare()
    override fun release() { /* DesktopAudioPlayer 无 release API, no-op */ }
}

/**
 * [AudioPlayAnalyzeRuleFactory] 的 desktop 实现。
 *
 * 直接创建 commonMain [AnalyzeRuleCore] (与 desktop 原 loadLrcData 一致),
 * 不依赖 app 端 AnalyzeRule 的 android-only JsExtensions; JS 引擎 / 网络 (ajax) 经
 * desktop Main.kt 已注册的 JsEngines / SourceNetworkProviders 走通。
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
        return AnalyzeRuleCore(book, bookSource).apply {
            this.coroutineContext = coroutineContext
            setBaseUrl(chapter.url)
            this.chapter = chapter
        }
    }
}
