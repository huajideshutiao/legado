package io.legado.desktop.audio

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/**
 * [AudioPlayController] 的 desktop 实现, 包装 [DesktopAudioPlayer] (mediamp-mpv)。
 *
 * 对标 app 端 [io.legado.app.model.audio.ExoPlayerAudioPlayController] 包装 ExoPlayer,
 * 供 shared commonMain [io.legado.app.model.audio.AudioPlaySession] 注入使用。
 *
 * # 与 ExoPlayer 行为差异
 * - playWhenReady / 起播位置: 随装载一并交给引擎的开源契约, 由它在 loadfile 时原生应用
 *   (mediamp spec §3: 起播位置属于"打开"本身, 不是一次 seek), 语义与 Media3 一致 ——
 *   缓冲期按过暂停, 就绪后不自动起播
 * - release: 桌面会话结束只 stop 不 release (mpv 实例贵, 下一轮复用), 退出/换源才释放
 *
 * # 装载的所有权
 * [prepare] 是挂起函数, 直接 await [DesktopAudioPlayer.prepare] —— 装载跑在调用方
 * (会话的起播 job) 里, 随它一起取消。本类不另开作用域装媒体, 否则会话终结时装载无人能撤。
 */
class DesktopAudioPlayController(private val player: DesktopAudioPlayer) :
    AudioPlayController, DesktopAudioPlayer.Listener {

    override var listener: AudioPlayControllerListener? = null

    /**
     * 本轮装载是否已报过错。
     *
     * mediamp 对同一次 open 失败会同时 commit MediaStatus.Error **并** 让 setMediaData 抛出
     * (AbstractMediampPlayer.runOpen 用的是同一个 PlaybackException), [DesktopAudioPlayer]
     * 两条都接, 于是一次失败来两次 onError。上层的"首错静默重试一次"会被第二条顶掉,
     * 表现为"报错了但播放照旧"(静默重试其实已经成功), 故在此收敛成一轮一次。
     */
    private var errorReported = false

    /** 本会话的起播位置 (随装载一并交给引擎的开源契约)。 */
    private var startPosMs = 0L

    /**
     * 装载代次: 每次 [setSource]/[prepare]/[stop] 自增。
     *
     * 用于丢弃已被顶掉的旧装载的迟到上报 (实测: stop() 下发后, 上一轮 prepare 仍可能跑完并
     * 回调 onReady, 把共享播放状态写成 READY/PLAY, 界面据此跳过重新起播)。
     */
    @Volatile
    private var loadGeneration = 0

    /** 最后一轮"已装载完成、可上报 READY"的代次 (由 [prepare] 先落值, [onReady] 比对)。 */
    @Volatile
    private var readyGeneration = -1

    /**
     * 播放/暂停意图 (Media3 `playWhenReady` 语义)。
     *
     * 设值即下发 (引擎已创建时): 缓冲中按的暂停必须在引擎就绪前就落下去, 否则 mpv 会按
     * 打开时给的意图直接出声, 而界面显示已暂停。引擎未创建时只存值, 由 [prepare] 随
     * 装载一并交给 mediamp 的开源契约。
     */
    override var playWhenReady = false
        set(value) {
            field = value
            player.setPlayIntent(value)
        }

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

    /**
     * 生命周期状态直读引擎 ([DesktopAudioPlayer.playbackState] 映射 mediamp [MediaStatus])。
     *
     * 不再保留本地镜像: 镜像会与引擎真实状态漂移 —— 实测被取消的 prepare 会把镜像永久
     * 留在 BUFFERING, 而引擎早已回 Idle, 会话据此误判"引擎没闲着"而跳过重播。
     */
    override val playbackState: Int
        get() = player.playbackState

    /** 设置播放源 (直链 + 请求头), [startPosMs] 随装载一并生效。 */
    fun setSource(url: String, headers: Map<String, String>, startPosMs: Long) {
        loadGeneration++
        this.startPosMs = startPosMs
        player.setUrl(url, headers)
    }

    override suspend fun prepare() {
        errorReported = false
        // 先落代次再装载: [DesktopAudioPlayer.prepare] 会在返回前同步回调 onReady,
        // 那里拿本字段与 loadGeneration 比对
        val generation = ++loadGeneration
        readyGeneration = generation
        try {
            withTimeout(PREPARE_TIMEOUT_MS) { player.prepare(playWhenReady, startPosMs) }
        } catch (e: TimeoutCancellationException) {
            // setMediaData 挂起即收掉加载态, 避免 LOADING 永久残留 (外层取消不是本异常, 会照常上抛)
            onError("prepare timeout")
        }
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() {
        loadGeneration++
        player.stop()
    }

    override fun seekTo(position: Long) = player.seekTo(position)

    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)

    // 换源/退出时释放: 停线程/关流/关音频设备 (DesktopAudioPlayer.release 幂等)
    override fun release() = player.release()

    // region DesktopAudioPlayer.Listener -> AudioPlayControllerListener 适配

    override fun onReady(durationMs: Long) {
        errorReported = false
        // 本轮装载已被 stop()/新装载顶掉则不上报 (引擎已在跑新的一轮)
        if (readyGeneration != loadGeneration) return
        listener?.onPlaybackStateChanged(AudioPlayController.STATE_READY)
    }

    override fun onEndOfMedia() {
        // 本轮装载已被 stop()/新装载顶掉则不上报 (迟到 eof 不得误触发自动换章)
        if (readyGeneration != loadGeneration) return
        listener?.onPlaybackStateChanged(AudioPlayController.STATE_ENDED)
    }

    override fun onError(message: String?) {
        if (errorReported) return
        errorReported = true
        listener?.onPlayerError(RuntimeException(message ?: "play error"))
    }

    // endregion

    private companion object {
        /** 装载超时: setMediaData 挂起即报错, 防止直链挂起转圈永久残留 */
        private const val PREPARE_TIMEOUT_MS = 30_000L
    }
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
