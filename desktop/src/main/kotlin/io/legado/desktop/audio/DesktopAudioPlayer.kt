package io.legado.desktop.audio

import io.legado.app.constant.AppLog
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.desktop.media.DesktopMediaRuntime
import io.legado.desktop.media.bufferedEndPositionMsOrZero
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.UriMediaData
import kotlin.concurrent.Volatile

/**
 * 桌面端音频播放器 (mediamp-mpv 引擎, mpv 内核 = FFmpeg 全格式)。
 *
 * 对应 app 端 [io.legado.app.service.AudioPlayService] 的 ExoPlayer 部分。
 * 自 mediamp-mpv 迁移后不再用 jlayer (仅 MP3 + 自研进度/结束检测), 改为复用桌面
 * 视频端同一套 open-ani/mediamp + mpv 后端:
 * - 格式: FFmpeg 全格式 (MP3/M4A/AAC/FLAC/WAV/OGG/OPUS 等), 不再局限 MP3
 * - 结束检测: mpv 原生 eof-reached → MediaStatus.Ended (根治 jlayer play()
 *   返回值语义反噬导致的"播完不切下一首")
 * - 时长/进度: mpv time-pos/duration 属性 (精确, 不再墙钟估算)
 * - seek: mpv 原生 seek absolute+exact (无需重新下载跳帧)
 * - 倍速: mpv speed 属性 (保音高, 不再 Sonic PCM 层变速)
 * - 防盗链: UriMediaData headers → mpv user-agent/referrer/http-header-fields
 *
 * # 线程模型
 * - mediamp 播放控制 (play/pause/seekTo/stopPlayback/close) 契约要求主线程 (本端 = AWT EDT),
 *   统一经 [controlScope] (Dispatchers.Main) 串行派发
 * - 状态 (playing/duration/position) 由 StateFlow 采集缓存为 volatile 字段, 任意线程读
 *
 * # 装载所有权 (会话内联)
 * [prepare] 是挂起函数, 在**调用方协程**里 await 装载完成 —— 装载不再挂到播放器自己的
 * 作用域上。这样会话的起播 job 被取消时, 装载随之取消, mediamp 的 setMediaData 取消路径
 * 会把播放器收回 Idle 并释放媒体 (AbstractMediampPlayer.abortSetMediaData), 不会留下
 * "无人认领但仍在出声"的装载。
 *
 * 播放态与生命周期一律直读 mediamp 的 [MediampPlayer.state] (单一真源), 本类不另存
 * prepared/loaded/playing 之类的镜像标志 —— 镜像标志会与引擎真实状态漂移, 而
 * mediamp 的状态机已保证: Idle 上的 play() 是 no-op、stopPlayback() 把状态收回 Idle。
 */
class DesktopAudioPlayer {

    /** 播放事件回调, 由 [DesktopAudioPlayProvider] 注册接收 */
    interface Listener {
        /** 装载完成, 可以开始 play; [durationMs] 为总时长, -1 表示未知 (流式可能后到) */
        fun onReady(durationMs: Long) {}

        /** 流自然播放完毕 (对应 ExoPlayer STATE_ENDED / mpv eof-reached) */
        fun onEndOfMedia() {}

        /** 播放出错 */
        fun onError(message: String?) {}
    }

    // ===== 状态字段 (volatile 供跨线程读) =====

    @Volatile private var url: String? = null
    @Volatile private var headers: Map<String, String> = emptyMap()
    @Volatile private var listenerField: Listener? = null

    @Volatile private var released: Boolean = false

    /** 播放速率 (resume 时重设; mpv speed 属性跨会话保留, 这里兜底记录) */
    @Volatile
    private var speed: Float = 1f

    /** 总时长 (mpv duration 属性缓存; -1 未知) */
    @Volatile
    private var durationMs: Long = -1L

    /** mpv time-pos 缓存 (供任意线程读) */
    @Volatile
    private var currentPositionMs: Long = 0L

    // ===== 引擎 =====

    /** mediamp 底层播放器 (ServiceLoader 经 mediamp-mpv 解析; 首次 prepare 惰性创建) */
    @Volatile
    private var engine: MediampPlayer? = null

    /** 引擎创建失败原因 (惰性创建失败后不再重试, 直接 onError) */
    @Volatile
    private var engineError: String? = null

    /** 上一次失败是否仅因媒体播放组件未安装 (下载完成后要能重新起播, 不能当粘性错误留着) */
    private var runtimePending = false

    private val engineLock = Any()

    /** mediamp 播放控制必须走 UI 线程 (mediamp 契约), 统一在此串行派发 */
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 关闭协程 (独立 scope: release 后 controlScope 已取消, close 需要自己的调度器) */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ===== 公开属性 =====

    val isPlaying: Boolean
        get() = engine?.state?.value?.isPlaying == true

    /**
     * 播放生命周期状态 (映射 mediamp [MediaStatus], 供 [io.legado.app.model.audio.AudioPlayController.playbackState] 直读)。
     *
     * 单一真源: 本类不另存 prepared/loaded/playing 之类的镜像标志 —— 镜像会与引擎真实状态
     * 漂移 (实测: 已取消的装载会让镜像永久停在 BUFFERING, 而引擎早已回 Idle)。
     */
    val playbackState: Int
        get() = when (val status = engine?.state?.value?.mediaStatus) {
            null,
            MediaStatus.Idle,
            MediaStatus.Released,
            is MediaStatus.Error -> AudioPlayController.STATE_IDLE

            MediaStatus.Opening -> AudioPlayController.STATE_BUFFERING
            MediaStatus.Ready -> AudioPlayController.STATE_READY
            MediaStatus.Ended -> AudioPlayController.STATE_ENDED
        }

    val duration: Long
        get() = durationMs

    val currentPosition: Long
        get() = currentPositionMs

    /**
     * 已缓冲到的时间点 ms (进度条缓冲层用); 取不到给 0。
     *
     * 读 mpv `demuxer-cache-time` = 解复用缓存里最后一帧的时间戳。不用 mediamp 的
     * [org.openani.mediamp.features.Buffering.bufferedPercentage]: 它取自
     * `cache-buffering-state` (起播缓冲进度, 缓冲完成后恒 100), 折算成时长就是一条
     * 永远铺满的假缓冲条。经公开的 [MediampPlayer.impl] 取句柄 (mediamp 的 `handle`
     * 字段是 internal); released 后不取, MPVHandle 已 close, 取指针会抛异常。
     */
    val bufferedPosition: Long
        get() {
            if (released) return 0L
            val mpv = engine?.impl as? MPVHandle ?: return 0L
            return mpv.bufferedEndPositionMsOrZero()
        }

    var listener: Listener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== 控制方法 =====

    /**
     * 设置播放源 (URL + headers)。切换源前会清空上一次会话。
     * 不立即拉流, 等待 [prepare] 触发。
     */
    fun setUrl(url: String, headers: Map<String, String>) {
        if (released) return
        this.url = url
        this.headers = headers
        durationMs = -1L
        currentPositionMs = 0L
        val engine = engineOrNull()
        if (engine != null) {
            enqueueCommand { engine.stopPlayback() }
        }
    }

    /** 已下发到 [controlScope] 的最后一个引擎命令 (装载前要等它落定, 见 [prepare])。 */
    @Volatile
    private var lastCommandJob: Job? = null

    /** 把引擎命令排到 [controlScope] (mediamp 要求主线程) 并记账。 */
    private fun enqueueCommand(block: suspend () -> Unit): Job =
        controlScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("音频引擎命令失败: ${e.message}", e, tag = "音频播放")
            }
        }.also { lastCommandJob = it }

    /**
     * 装载播放源 (mediamp setMediaData → Ready), 完成后回调 [Listener.onReady]。
     *
     * 挂起直到装载落定, 且随调用方协程取消 —— 这是"会话终结即终止装载"的唯一入口
     * (mediamp 的取消路径会把播放器收回 Idle 并释放媒体, 见类注释)。
     *
     * 装载前先等已排队的引擎命令落定: 那些命令走 [controlScope] (EDT), 而 setMediaData
     * 可从任意线程调 (mediamp 契约), 不等就会与刚下发的 stopPlayback 竞态 —— 后者晚到会
     * 把本次装载当作"被更新的一次调用顶掉"而中止 (MediaLoadCancellationException),
     * 表现为点播放没反应。
     *
     * [playWhenReady] 与 [startPositionMs] 一并交给 mediamp 的开源契约, 由它在 loadfile
     * 时原生应用 (mediamp spec §3: 起播位置属于"打开"本身, 不是一次 seek); 本类不另存待应用位置。
     */
    suspend fun prepare(playWhenReady: Boolean, startPositionMs: Long) {
        if (released) return
        val theUrl = url ?: run {
            listener?.onError("no url set")
            return
        }
        val engine = ensureEngine() ?: run {
            listener?.onError(engineError)
            return
        }
        lastCommandJob?.join()
        if (released) return
        try {
            engine.setMediaData(
                data = UriMediaData(theUrl, headers),
                playWhenReady = playWhenReady,
                startPositionMillis = startPositionMs.coerceAtLeast(0),
            )
        } catch (e: CancellationException) {
            // 装载被取消 (换章/停播/会话终结): 由新流程或终结路径接管, 不报错
            throw e
        } catch (e: Throwable) {
            if (!released) listener?.onError("prepare failed: ${e.message}")
            return
        }
        currentCoroutineContext().ensureActive()
        if (released) return
        listener?.onReady(durationMs)
    }

    /**
     * 下发播放/暂停意图 (Media3 `playWhenReady` 语义)。
     *
     * 与 [play]/[pause] 的区别: 本方法只在引擎已存在时下发, 不创建引擎 —— 意图可能在任何
     * 时候被设 (如会话起播前置真), 而创建引擎会带来"按需下载媒体组件"的副作用。
     * 引擎尚未创建时无需下发: [prepare] 会把当时的意图一并交给 mediamp 的开源契约。
     *
     * 必须真的下发而不能只存字段: mediamp 在 Opening 期收到 pause() 会把本次打开的
     * 起播意图改成 false, 从而"缓冲中按的暂停"在就绪后仍然有效 (只存字段则 mpv 会按
     * 打开时给的意图直接出声, 界面却显示已暂停)。
     */
    fun setPlayIntent(play: Boolean) {
        if (released) return
        val engine = engine ?: return
        enqueueCommand { if (play) engine.play() else engine.pause() }
    }

    /**
     * 开始播放。
     *
     * 直接转发给引擎: mediamp 的 play() 自带完整语义 (Opening 期更新本次打开的起播意图,
     * Ready 期真正起播, Ended 期从头重放, Idle/Error/Released 上是 no-op), 本类不另造
     * "已就绪才允许" 的门 —— 那会堵住"缓冲期按过暂停再恢复" 的路径。
     */
    fun play() {
        if (released) return
        val engine = engineOrNull() ?: return
        enqueueCommand { engine.play() }
    }

    /** 暂停播放。对应 app 端 ExoPlayer.pause()。 */
    fun pause() {
        if (released) return
        val engine = engineOrNull() ?: return
        enqueueCommand { engine.pause() }
    }

    /** 停止播放 (保留 url, 可重新 setUrl+prepare)。对应 app 端 ExoPlayer.stop()。 */
    fun stop() {
        if (released) return
        val engine = engineOrNull() ?: return
        // mediamp 的 stopPlayback 会把在途装载一并中止并回到 Idle, 不需要额外的作废标志
        enqueueCommand { engine.stopPlayback() }
    }

    /**
     * 跳转到指定位置 (毫秒)。对应 app 端 ExoPlayer.seekTo()。
     *
     * 不做"未就绪就先存着"的镜像: mediamp 自己在各生命周期上的语义是完备的 ——
     * Opening 期调 seekTo 会改写本次打开的起播位置, Ready/Ended 才真正下发 seek,
     * 其余状态 (Idle/Error/Released) 是 no-op。
     */
    fun seekTo(positionMs: Long) {
        if (released) return
        val engine = engineOrNull() ?: return
        val target = positionMs.coerceAtLeast(0)
        enqueueCommand { engine.seekTo(target) }
    }

    /** 设置播放速率 (mpv speed 属性, 保音高)。对应 app 端 ExoPlayer.setPlaybackParameters。 */
    fun setSpeed(rate: Float) {
        // 对齐 app 端倍速滑杆 (0..30 → 0.0x..3.0x); 下限避免 mpv 异常
        speed = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        val engine = engineOrNull() ?: return
        enqueueCommand { applySpeed(engine) }
    }

    /** 释放所有资源 (mediamp close 必须 UI 线程, 经 closeScope 派发)。幂等。 */
    fun release() {
        released = true
        engine?.let { e ->
            closeScope.launch { runCatching { e.close() } }
        }
        controlScope.cancel()
    }

    // ===== 内部辅助 =====

    /** 惰性创建 mediamp 引擎; 失败一次后不再重试, 返回 null 并置 [engineError] */
    private fun ensureEngine(): MediampPlayer? = synchronized(engineLock) {
        engine?.let { return it }
        // 媒体组件未装不算"引擎失败": 下完还要能直接播, 所以走可清的临时态而不是粘性的 engineError
        if (runtimePending && DesktopMediaRuntime.isReady()) {
            runtimePending = false
            engineError = null
        }
        engineError?.let { return null }
        if (!DesktopMediaRuntime.ensureReady()) {
            runtimePending = true
            engineError = syncGetString("media_runtime_not_installed")
            AppLog.put("音频播放: 媒体播放组件未就绪, 已转按需下载", tag = "媒体组件")
            return null
        }
        return try {
            MediampPlayer(Unit, controlScope.coroutineContext).also {
                engine = it
                startStateCollectors(it)
            }
        } catch (e: Throwable) {
            engineError = "mediamp 初始化失败: ${e.message}"
            null
        }
    }

    private fun engineOrNull(): MediampPlayer? {
        engine?.let { return it }
        if (engineError != null) return null
        return ensureEngine()
    }

    /**
     * 状态采集: state (PlayerState v2)/currentPositionMillis/mediaProperties → 缓存字段。
     *
     * 订阅先于任何命令下发 (构造时即起), 因为 mediamp 的 events 没有重放 (其 KDoc 要求
     * "在发命令前订阅")。
     */
    private fun startStateCollectors(engine: MediampPlayer) {
        controlScope.launch {
            engine.state.collect { onStateChanged(it) }
        }
        controlScope.launch {
            engine.currentPositionMillis.collect { currentPositionMs = it }
        }
        controlScope.launch {
            engine.mediaProperties.collect { p ->
                val duration = p?.durationMillis
                if (duration != null && duration > 0) durationMs = duration
            }
        }
        // 自然播完取 events 的边沿: state 是合并态, 慢收集者可能看不到 Ended 那一帧
        // (mediamp 的 events KDoc 明确要求"推进会话"的反应走 events)
        controlScope.launch {
            engine.events.collect { event ->
                if (event is PlaybackEvent.MediaEnded && !released) {
                    listener?.onEndOfMedia()
                }
            }
        }
    }

    /**
     * v2 状态模型 (PlayerState/MediaStatus, 替代废弃的 PlaybackState):
     * - Ready: 播放态直读引擎 (state.isPlaying), 本类不再镜像 playing 字段
     * - Ended: 自然播完由 events 的 MediaEnded 上报 (见 [startStateCollectors])
     * - Error: 致命错误 (等价旧 ERROR), 原因经 errorOrNull 携带
     */
    private fun onStateChanged(state: PlayerState) {
        if (state.mediaStatus is MediaStatus.Error && !released) {
            listener?.onError(state.errorOrNull?.message ?: "play error")
        }
    }

    private fun applySpeed(engine: MediampPlayer) {
        runCatching { engine.features[PlaybackSpeed.Key]?.set(speed) }
    }

    private companion object {
        /** 倍速上下限 (对齐 app 端倍速滑杆 0.0x..3.0x); 下限避免 mpv 变速异常 */
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 3f
    }
}
