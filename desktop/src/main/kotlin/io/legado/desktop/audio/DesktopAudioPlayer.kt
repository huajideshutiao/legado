package io.legado.desktop.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlayerState
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.UriMediaData
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

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
 *   (当前 provider 传空 headers, 属已知限制; 传入即生效)
 *
 * # 线程模型
 * - mediamp 播放控制 (resume/pause/seekTo/stopPlayback/close) 契约要求 UI 线程,
 *   统一经 [controlScope] (Dispatchers.Main = AWT EDT) 串行派发
 * - setMediaData (prepare) 可任意线程, 走 [prepareScope] (Dispatchers.Default)
 * - 状态 (playing/duration/position) 由 StateFlow 采集缓存为 volatile 字段, 任意线程读
 *
 * # 对外接口保持 (provider 零改动)
 * - Listener.onReady/onEndOfMedia/onError: 语义同 jlayer 版
 * - isPlaying/duration/currentPosition: 缓存值
 * - setUrl/prepare/play/pause/stop/seekTo/setSpeed/release
 *
 * 参考 [io.legado.desktop.ui.platform.MediampVideoPlayerController] 的 mediamp 用法。
 */
class DesktopAudioPlayer {

    /** 播放事件回调, 由 [DesktopAudioPlayProvider] 注册接收 */
    interface Listener {
        /** prepare 完成, 可以开始 play; [durationMs] 为总时长, -1 表示未知 (流式可能后到) */
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

    /** 是否正在播放 (仅 PLAYING 态; 暂停/缓冲/停止均 false) */
    @Volatile private var playing: Boolean = false
    @Volatile private var released: Boolean = false

    /** 已 setMediaData 且 READY (可 resume) */
    @Volatile
    private var prepared: Boolean = false

    /** 是否已 prepare (setMediaData 完成, 可 resume); 供 DesktopAudioPlayController.playbackState 派生用 */
    val isPrepared: Boolean
        get() = prepared

    /** 已发起 loadfile (resume), seek 可直接下发; 否则 seek 暂存 [pendingSeekMs] */
    @Volatile
    private var loaded: Boolean = false

    /** 当前播放会话是否活跃播放中; false 时 FINISHED 视为人工停止, 抑制上报 */
    @Volatile
    private var sessionActive: Boolean = false

    /** loadfile 前下达的 seek 目标 (mpv 未加载文件时 seek 命令无效) */
    @Volatile
    private var pendingSeekMs: Long = 0L

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

    private val engineLock = Any()

    /** prepare 协程 (超时/取消可控) */
    private val prepareScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** mediamp 播放控制必须走 UI 线程 (mediamp 契约), 统一在此串行派发 */
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 关闭协程 (独立 scope: release 后 controlScope 已取消, close 需要自己的调度器) */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** prepare 代次: 新 prepare 使旧 job 的迟到 onError 失效 (避免误杀新章节的加载态) */
    private val prepareGeneration = AtomicInteger(0)

    @Volatile
    private var prepareJob: Job? = null

    // ===== 公开属性 =====

    val isPlaying: Boolean
        get() = playing

    val duration: Long
        get() = durationMs

    val currentPosition: Long
        get() = currentPositionMs

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
        // 先同步复位会话 (抑制 FINISHED), 再异步停掉上一段播放
        resetSession()
        durationMs = -1L
        val engine = engineOrNull()
        if (engine != null) {
            controlScope.launch { runCatching { engine.stopPlayback() } }
        }
    }

    /**
     * 设置播放源并加载 (setMediaData → READY), 完成后回调 [Listener.onReady]。
     * mpv 尚未 loadfile, duration 未知 (-1); 播放中由 provider 心跳补发 AUDIO_SIZE。
     */
    fun prepare() {
        if (released) return
        val theUrl = url ?: run {
            listener?.onError("no url set")
            return
        }
        prepareJob?.cancel()
        val gen = prepareGeneration.incrementAndGet()
        prepareJob = prepareScope.launch {
            try {
                withTimeout(PREPARE_TIMEOUT_MS.milliseconds) { prepareInternal(theUrl) }
            } catch (_: TimeoutCancellationException) {
                // setMediaData 挂起即收掉加载态, 避免 LOADING 永久残留
                if (!released && gen == prepareGeneration.get()) {
                    listener?.onError("prepare timeout")
                }
            } catch (e: CancellationException) {
                throw e // 主动取消 (换章/停止): 由新流程接管, 不报错
            } catch (e: Exception) {
                if (!released && gen == prepareGeneration.get()) {
                    listener?.onError("prepare failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun prepareInternal(theUrl: String) {
        val engine = ensureEngine() ?: run {
            listener?.onError(engineError)
            return
        }
        // 新一轮会话: 0.3.0 起 stopPlayback 只回 Idle (不再发 FINISHED), Ended 仅来自自然播完
        resetSession()
        currentCoroutineContext().ensureActive()
        engine.setMediaData(UriMediaData(theUrl, headers))
        prepared = true
        currentCoroutineContext().ensureActive()
        listener?.onReady(durationMs)
    }

    /**
     * 开始播放。若处于 READY/PAUSED 态则 resume (位置由 mpv 保留);
     * 若未 prepare 则触发 prepare (防御, 正常路径 provider 先 prepare)。
     */
    fun play() {
        if (released) return
        if (playing) return
        val engine = engineOrNull() ?: run {
            prepare()
            return
        }
        controlScope.launch {
            if (!prepared) {
                // 防御: 正常路径 provider 先 prepare (onReady 后由 provider 续播)
                prepare()
                return@launch
            }
            val playerState = engine.state.value
            if (playerState.mediaStatus == MediaStatus.Ready) {
                if (!playerState.isPlaying) {
                    // READY/PAUSED/PAUSED_BUFFERING → resume (位置由 mpv 保留)
                    engine.play()
                    // loadfile 发起后再 apply 暂存 seek (mpv 加载中即可 seek)
                    applyPendingSeek(engine)
                    applySpeed(engine)
                }
                sessionActive = true
                loaded = true
                playing = true
            }
        }
    }

    /** 暂停播放。对应 app 端 ExoPlayer.pause()。 */
    fun pause() {
        if (released) return
        val engine = engineOrNull() ?: return
        controlScope.launch {
            playing = false
            runCatching { engine.pause() }
        }
    }

    /** 停止播放 (保留 url, 可重新 setUrl+prepare)。对应 app 端 ExoPlayer.stop()。 */
    fun stop() {
        if (released) return
        val engine = engineOrNull()
        // 同步复位会话再异步停, 旧 FINISHED 不误报自然结束
        resetSession()
        if (engine != null) {
            controlScope.launch { runCatching { engine.stopPlayback() } }
        }
    }

    /** 跳转到指定位置 (毫秒)。对应 app 端 ExoPlayer.seekTo()。 */
    fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0)
        val engine = engineOrNull() ?: run {
            pendingSeekMs = target
            return
        }
        controlScope.launch {
            if (loaded) {
                runCatching { engine.seekTo(target) }
            } else {
                pendingSeekMs = target
            }
        }
    }

    /** 设置播放速率 (mpv speed 属性, 保音高)。对应 app 端 ExoPlayer.setPlaybackParameters。 */
    fun setSpeed(rate: Float) {
        // 对齐 app 端倍速滑杆 (0..30 → 0.0x..3.0x); 下限避免 mpv 异常
        speed = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        val engine = engineOrNull() ?: return
        controlScope.launch { applySpeed(engine) }
    }

    /** 释放所有资源 (mediamp close 必须 UI 线程, 经 closeScope 派发)。幂等。 */
    fun release() {
        released = true
        prepareJob?.cancel()
        prepareJob = null
        engine?.let { e ->
            closeScope.launch { runCatching { e.close() } }
        }
        controlScope.cancel()
        prepareScope.cancel()
    }

    // ===== 内部辅助 =====

    /** 复位播放会话: 抑制 FINISHED + 清空中间状态 (供 setUrl/stop/prepare 切换源时调用) */
    private fun resetSession() {
        sessionActive = false
        prepared = false
        loaded = false
        playing = false
        pendingSeekMs = 0L
    }

    /** 惰性创建 mediamp 引擎; 失败一次后不再重试, 返回 null 并置 [engineError] */
    private fun ensureEngine(): MediampPlayer? = synchronized(engineLock) {
        engine?.let { return it }
        engineError?.let { return null }
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

    /** 状态采集: state (PlayerState v2)/currentPositionMillis/mediaProperties → 缓存字段 */
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
    }

    /**
     * v2 状态模型 (PlayerState/MediaStatus, 替代废弃的 PlaybackState):
     * - Ready: 按 playWhenReady×isBuffering 派生 playing (等价旧 PLAYING/PAUSED/PAUSED_BUFFERING)
     * - Ended: 自然播完 (等价旧 FINISHED; 0.3.0 起 stopPlayback 只回 Idle, 不再产生 FINISHED)
     * - Error: 致命错误 (等价旧 ERROR), 原因经 errorOrNull 携带
     */
    private fun onStateChanged(state: PlayerState) {
        when (state.mediaStatus) {
            MediaStatus.Ready -> playing = state.isPlaying

            MediaStatus.Ended -> {
                if (sessionActive) {
                    // 自然播完 (mpv eof-reached) → 切下一首由上层处理
                    sessionActive = false
                    loaded = false
                    playing = false
                    listener?.onEndOfMedia()
                }
            }

            is MediaStatus.Error -> {
                if (!released) {
                    loaded = false
                    playing = false
                    listener?.onError(state.errorOrNull?.message ?: "play error")
                }
            }

            MediaStatus.Idle, MediaStatus.Opening, MediaStatus.Released -> {
                // 等价旧 CREATED/DESTROYED: 旧代码 else 分支同样不做处理
            }

        }
    }

    /** loadfile 发起后补 apply 暂存 seek (READY 未起播时 mpv 无法 seek) */
    private fun applyPendingSeek(engine: MediampPlayer) {
        val target = pendingSeekMs
        if (target > 0) {
            pendingSeekMs = 0L
            runCatching { engine.seekTo(target) }
        }
    }

    private fun applySpeed(engine: MediampPlayer) {
        runCatching { engine.features[PlaybackSpeed.Key]?.set(speed) }
    }

    private companion object {
        /** prepare 超时 (毫秒): setMediaData 挂起即 onError, 防止直链挂起转圈永久残留 */
        private const val PREPARE_TIMEOUT_MS = 30_000L

        /** 倍速上下限 (对齐 app 端倍速滑杆 0.0x..3.0x); 下限避免 mpv 变速异常 */
        private const val MIN_SPEED = 0.1f
        private const val MAX_SPEED = 3f
    }
}
