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
 * 供 shared commonMain [io.legado.app.model.audio.AudioPlayManager] 注入使用。
 *
 * # 与 ExoPlayer 行为差异
 * - bufferedPosition: mpv 缓冲细节未暴露, 返回 [DesktopAudioPlayer.currentPosition]
 *   作近似, 让音频页缓冲条不恒空
 * - playbackState: 由 isPlaying 派生
 *   (STATE_READY=播放中, STATE_IDLE=暂停/停止/未启动);
 *   shared upPlayProgressForLrc 用 STATE_IDLE 守卫切歌中场景
 * - playWhenReady: 映射到实际播放态 (isPlaying); setter 转 play()/pause()
 * - release: 转发 [DesktopAudioPlayer.release] 释放 mpv 实例
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

    // mpv 缓冲细节未暴露, 用已播位置近似, 缓冲条至少不恒空
    override val bufferedPosition: Long
        get() = player.currentPosition

    // 由 isPlaying 派生; 播放中=READY, 否则=IDLE (upPlayProgressForLrc 用 IDLE 守卫切歌)
    override val playbackState: Int
        get() = if (player.isPlaying) AudioPlayController.STATE_READY else AudioPlayController.STATE_IDLE

    // 映射到实际播放态; 写值转 play()/pause()
    override var playWhenReady: Boolean
        get() = player.isPlaying
        set(value) {
            if (value) player.play() else player.pause()
        }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun seekTo(position: Long) = player.seekTo(position)
    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)
    override fun prepare() = player.prepare()

    // 换源/退出时释放: 停线程/关流/关音频设备 (DesktopAudioPlayer.release 幂等)
    override fun release() = player.release()
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
