package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.casUpdate
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.tts.HttpTtsPlayer
import io.legado.app.help.tts.HttpTtsPlayerListener
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.help.tts.SystemTtsEngine
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.help.tts.TtsProgressListener
import io.legado.app.help.tts.TtsProgressListenerToken
import io.legado.app.help.tts.resolveHttpTtsChannel
import io.legado.app.model.ActiveReadAloudHostPorts
import io.legado.app.model.ActiveReadBookRegistry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile

/**
 * 跨平台朗读控制器 (commonMain): 段级协调 (队列推进 + 选路 TTS + 状态广播) 与章节联动
 * ——“当前章节朗读完 → 自动切下一章”。
 * 对照 app 端 `TTSReadAloudService` 编排 (Service/Notification/MediaSession 强依赖 Android,
 * 无法下沉), 行为对齐 `TTSUtteranceListener.nextParagraph()` + `BaseReadAloudService.nextChapter()`。
 *
 * - [ReadAloudChapterDataPort] 注入章节规模与可播放段落计划, [ReadAloudChapterNavigationPort]
 *   注入切章 (调用方桥接 ReadBookViewModelShared)
 * - 复用 [ReadAloudQueue] 纯状态机
 * - 线程: 本类方法在调用方线程执行; 引擎 onDone 在引擎回调线程执行 (stop 时仍触发)。跨线程共享的
 *   段级状态各存为单份不可变快照 (队列 / 系统路由 / HttpTTS 路由 / 播放轮次), 读方一次取值即自洽,
 *   写方 CAS 发布; 端口实现方自行处理跨线程
 * - 简化项: 引擎无真暂停能力时 pause 退化为 stop (resume 从当前段起点重读);
 *   不实现通知/媒体会话/音频焦点 (由各端朗读宿主负责)
 *
 * @param chapterData 章节数据端口 (规模 + 段落计划), 由调用方桥接到阅读页
 * @param chapterNavigation 章节导航端口, 由调用方桥接到 ViewModel
 * @param playbackPort 平台播放器端口; 非空时由它承担播放外壳 (Android Service)
 * @param engineProvider TTS 引擎提供者, 默认查 [TtsEngineProvider]; 测试时可注入 mock
 * @param httpTtsPlayerFactory HttpTTS 播放器工厂, 默认查 [TtsEngineProvider.getHttpTtsPlayer]
 * @param ttsEngineConfigProvider TTS 引擎配置, 默认 [defaultTtsEngineConfig]
 *                                 (返回数字串表示 HttpTTS id, 空/非数字走系统 TTS)
 * @param httpTtsConfigLoader HttpTTS 源配置加载器, 用 id 从 DAO 查 [HttpTTS]
 */
class ReadAloudControllerShared(
    private val chapterData: ReadAloudChapterDataPort,
    private val chapterNavigation: ReadAloudChapterNavigationPort,
    private val playbackPort: ReadAloudPlaybackPort? = null,
    private val engineProvider: () -> SystemTtsEngine? = { TtsEngineProvider.get() },
    private val httpTtsPlayerFactory: (HttpTTS) -> HttpTtsPlayer? = { TtsEngineProvider.getHttpTtsPlayer(it) },
    private val ttsEngineConfigProvider: () -> String? = { defaultTtsEngineConfig() },
    private val httpTtsConfigLoader: suspend (Long) -> HttpTTS? = { AppDbProviders.get().httpTTSDao.get(it) },
) {

    /** 朗读队列 (段级状态机), Android Service 与其它平台共用同一实例。 */
    val playbackQueue = ReadAloudQueue()

    /**
     * 系统 TTS 一路的注册会话: 引擎实例、订阅令牌、utteranceId 前缀同生同灭。
     *
     * 引擎回调线程会经 [onParagraphDone] → [nextChapter] → [start] 重新注册, 调用方线程会
     * [stop] 注销, 二者都要读同一组值; 逐字段读会出现“用 A 引擎注销 B 令牌”的错配 (订阅泄漏,
     * 迟到回调照常触发), 故整份 CAS 发布。
     */
    private class SystemTtsRoute(
        val engine: SystemTtsEngine,
        val token: TtsProgressListenerToken,
        val utterancePrefix: String,
    )

    private val systemRoute = atomic<SystemTtsRoute?>(null)

    /** 系统路由序号: 每条路由的 utteranceId 前缀唯一, 否则并发注册的两条订阅会收到同一段回调。 */
    private val systemRunCount = atomic(0L)

    /**
     * 当前播放轮次: 令牌 + 本轮交给系统引擎的 utteranceId。
     *
     * [progressListener] 的 onDone/onError 在引擎线程先比对 utteranceId 再读令牌推进队列, 两个值
     * 分开读会出现“新令牌配旧 utteranceId”, 让上一段的迟到回调推进新一轮队列, 故整份发布。
     * 令牌单调递增 (见 [playbackToken]), 被取代的轮次其回调一律丢弃。
     */
    private class PlaybackRound(val token: Long, val expectedUtteranceId: String?)

    /** 播放令牌发号器, 只增不减。 */
    private val playbackToken = atomic(0L)

    private val playbackRound = atomic(PlaybackRound(0L, null))

    /** 正文尚未就绪时保留的请求；位置始终与目标章节绑定。 */
    private data class PendingChapterRequest(
        val chapterIndex: Int,
        val chapterPosition: Int,
        val fromLastSpeakable: Boolean,
    )

    private val pendingChapterRequest = atomic<PendingChapterRequest?>(null)

    /** 取走当前系统路由 (CAS 置空); 并发下只有一个调用者拿到非空结果, 不会重复注销。 */
    private fun takeSystemRoute(): SystemTtsRoute? {
        while (true) {
            val cur = systemRoute.value ?: return null
            if (systemRoute.compareAndSet(cur, null)) return cur
        }
    }

    /**
     * 发布播放轮次; 令牌小的轮次不得覆盖令牌大的 —— 迟到的旧轮次发布会把已作废的令牌又变回
     * 当前有效, 让 [invalidatePlayback] 丢掉的回调重新推进队列。
     */
    private fun publishPlaybackRound(round: PlaybackRound) {
        playbackRound.casUpdate { cur -> if (cur.token > round.token) cur else round }
    }

    // region HttpTTS 路由状态

    /**
     * HttpTTS 一路的注册会话: 源配置、播放器实例、speakSpeed 同生同灭。
     *
     * 播放线程 (prepare 回调 → play、段级推进) 与调用方线程 (start 解析、stop 释放) 都要读同一
     * 组值; 逐字段读会出现"新配置配旧播放器"的错配 (prepare 提交给刚被释放的播放器 → 误报
     * "未注册 HttpTtsPlayer" 并把朗读置为 ERROR), 故整份 CAS 发布。
     *
     * 未启用时整份为 null; 两条路由互斥 —— 本路由非空时 [systemRoute] 必为空 (start 先按旧路由
     * 停播/注销, 再解析新路由)。
     */
    private class HttpTtsRoute(
        val config: HttpTTS,
        val player: HttpTtsPlayer,
        val speechRate: Int,
    )

    private val httpRoute = atomic<HttpTtsRoute?>(null)

    /** 取走当前 HttpTTS 路由 (CAS 置空); 并发下只有一个调用者拿到非空结果, 不会重复释放。 */
    private fun takeHttpTtsRoute(): HttpTtsRoute? {
        while (true) {
            val cur = httpRoute.value ?: return null
            if (httpRoute.compareAndSet(cur, null)) return cur
        }
    }

    /** 整份发布本次会话的 HttpTTS 路由 (未走 HttpTTS 时整份为 null); 被替换的路由在这里释放。 */
    private fun publishHttpTtsRoute(route: HttpTtsRoute?) {
        while (true) {
            val cur = httpRoute.value
            if (cur === route) return
            if (!httpRoute.compareAndSet(cur, route)) continue
            cur?.let(::releaseHttpTtsPlayer)
            return
        }
    }

    /** 停止并释放整条路由的播放器; 引擎实例随路由一起离开原子字段, 后续读点取不到它。 */
    private fun releaseHttpTtsPlayer(route: HttpTtsRoute) {
        route.player.listener = null
        route.player.stop()
        route.player.release()
    }
    // endregion

    // region 状态流: 外部只读 StateFlow (适配 Compose 重组)

    /**
     * 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。
     *
     * 不能用 StateFlow: StateFlow 按值去重, 同一个错误串重复投递不会触发下游。
     */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 当前朗读状态。
     *
     * - [ReadAloudState.IDLE]: 未开始
     * - [ReadAloudState.PLAYING]: 正在朗读
     * - [ReadAloudState.PAUSED]: 已暂停 (桌面命令行 TTS 实际为 stop)
     * - [ReadAloudState.STOPPED]: 已停止
     * - [ReadAloudState.COMPLETED]: 已朗读到末章末段
     * - [ReadAloudState.ERROR]: 引擎不可用 / 章节内容为空等错误
     */
    private val _state = MutableStateFlow(ReadAloudState.IDLE)
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    /** 当前朗读章节索引, -1 = 未开始。 */
    private val _chapterIndex = MutableStateFlow(-1)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    /** 当前朗读段落下标, -1 = 未开始。 */
    private val _paragraphIndex = MutableStateFlow(-1)
    val paragraphIndex: StateFlow<Int> = _paragraphIndex.asStateFlow()

    /** 章节总数 (来自 navigator), 0 = 未知。 */
    private val _chapterSize = MutableStateFlow(0)
    val chapterSize: StateFlow<Int> = _chapterSize.asStateFlow()

    /** 当前段在章内的起始位置。 */
    private val _chapterPosition = MutableStateFlow(0)
    val chapterPosition: StateFlow<Int> = _chapterPosition.asStateFlow()

    /** 当前段落文本 (供 UI 显示"正在朗读"高亮)。 */
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    /**
     * 最近一次错误信息 (供 UI 显示 toast)。
     *
     * 事件语义: 用 SharedFlow 而非 StateFlow, 否则重试后错误串相同会被去重吞掉, toast 只弹一次。
     */
    private val _lastError = signalFlow<String>()
    val lastError: SharedFlow<String> = _lastError.asSharedFlow()

    /**
     * 朗读语速倍率。
     *
     * - 默认 1.0x (正常语速)
     * - 范围 0.5x ~ 2.0x (与桌面端各平台命令速度参数范围对齐, 见 [SystemTtsEngine.speechRate])
     * - UI 滑杆拖动时调 [setSpeechRate] 实时更新, 下一段 speak 生效
     * - start/resume 时同步到引擎, 详见 [start] / [resume]
     */
    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()
    // endregion

    /**
     * 引擎是否已通过 [TtsEngineProvider] 注册可用。
     *
     * UI 调用 [start] 前应先检查, 为 false 时提示用户平台不支持。
     */
    val isEngineAvailable: Boolean
        get() = engineProvider() != null

    /**
     * 引擎进度监听器, 通过本类构造时挂接到引擎。
     *
     * 关键: 引擎的 [SystemTtsEngine.speak] 是异步的, 朗读完成后会触发本监听器的
     * [onDone], 由本类推进段落或章节。这样保证:
     * - 上一段真的朗读完了才放下一段 (不会"叠加朗读")
     * - 章节末段朗读完自动 [navigator.moveToNextChapter] 并续读
     */
    private val progressListener = object : TtsProgressListener {
        override fun onStart(utteranceId: String) {
            // 引擎开始朗读 utteranceId 对应的段落 (下标)
            // 这里不改 state (state 已在 playCurrent 设为 PLAYING)
        }

        override fun onDone(utteranceId: String) {
            // 一次取整轮: utteranceId 与令牌同源, 否则上一段的迟到回调会推进新一轮队列
            val round = playbackRound.value
            if (utteranceId == round.expectedUtteranceId) {
                onParagraphDone(round.token)
            }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (utteranceId != playbackRound.value.expectedUtteranceId) return
            _lastError.tryEmit("TTS 朗读出错 (code=$errorCode, utteranceId=$utteranceId)")
            _state.value = ReadAloudState.ERROR
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            // 范围进度: 桌面命令行 TTS 不提供, no-op
        }
    }

    /**
     * HttpTTS 播放器回调桥接器。
     *
     * 播放器实例随监听器一起传入: 回调只作用在发起本轮播放的那台播放器上, 不再回读路由字段。
     *
     * - onReady: prepare 完成 → play (仅当当前 state 为 PLAYING, 与系统 TTS onDone 用同一判断对齐)
     * - onEndOfMedia: 本段播完 → [onParagraphDone] (复用系统 TTS 的段级推进逻辑)
     * - onError: 错误信息写入 [_lastError] + state 置 [ReadAloudState.ERROR]
     */
    private fun httpTtsProgressListener(token: Long, player: HttpTtsPlayer) = object : HttpTtsPlayerListener {
        override fun onReady() {
            if (_state.value == ReadAloudState.PLAYING && token == playbackRound.value.token) {
                player.play()
            }
        }

        override fun onEndOfMedia() = onParagraphDone(token)

        override fun onError(message: String) {
            if (token != playbackRound.value.token) return
            _lastError.tryEmit("HttpTTS 播放出错: $message")
            _state.value = ReadAloudState.ERROR
        }

        override fun onBufferingUpdate(percent: Int) = Unit
    }

    /**
     * 开始朗读指定章节。
     *
     * 本章无可朗读内容时按顺序顺延到下一个有内容的章 (见 [openSpeakableChapter]), 到末章仍置
     * [ReadAloudState.COMPLETED]。
     *
     * @param chapterIndex 章节序号 (0-based); 越界时 state 置 ERROR
     */
    fun start(
        chapterIndex: Int,
        chapterPosition: Int = 0,
        fromLastSpeakable: Boolean = false,
    ) {
        val request = PendingChapterRequest(
            chapterIndex = chapterIndex,
            chapterPosition = chapterPosition.coerceAtLeast(0),
            fromLastSpeakable = fromLastSpeakable,
        )
        pendingChapterRequest.value = request
        // 对应原版 newReadAloud 开头的 pageIndex 重置: 每次新会话翻页联动游标回到起点
        ActiveReadAloudHostPorts.resetReadAloudPageCursor(request.chapterPosition)
        if (chapterIndex !in 0 until chapterData.chapterCount) {
            pendingChapterRequest.value = null
            _lastError.tryEmit("章节索引越界: $chapterIndex")
            _state.value = ReadAloudState.ERROR
            return
        }

        // 必须先按旧路由停播/注销，再读取配置解析新路由；否则切换系统/HttpTTS 会停错实例。
        _state.value = ReadAloudState.STOPPED
        stopInternal(clearState = false)
        val http = if (playbackPort == null) resolveHttpTtsRoute() else null
        publishHttpTtsRoute(http)

        if (playbackPort == null && http == null) {
            val existing = systemRoute.value
            if (existing != null) {
                existing.engine.speechRate = _speechRate.value
            } else {
                val engine = engineProvider()
                if (engine == null) {
                    _lastError.tryEmit("未注册 TTS 引擎, 无法朗读")
                    _state.value = ReadAloudState.ERROR
                    return
                }
                val prefix = "legado_read_${systemRunCount.incrementAndGet()}_"
                val route = SystemTtsRoute(
                    engine = engine,
                    token = engine.registerProgressListener(progressListener, prefix),
                    utterancePrefix = prefix,
                )
                if (systemRoute.compareAndSet(null, route)) {
                    engine.speechRate = _speechRate.value
                } else {
                    // 并发 start 已注册路由: 撤销本次多余订阅 (前缀唯一, 只会泄漏不会串回调)
                    engine.unregisterProgressListener(route.token)
                    systemRoute.value?.engine?.speechRate = _speechRate.value
                }
            }
        }

        // 载入起点所在章节, 本章无内容时顺着往下找到第一个有内容的章 (旧段在循环内先清空)
        if (!openSpeakableChapter(chapterIndex, request.chapterPosition, request.fromLastSpeakable)) {
            return
        }
        _state.value = ReadAloudState.PLAYING
        playCurrent()
    }

    /**
     * 从 [chapterIndex] 起载入第一个有可朗读内容的章, 并把队列停在可朗读段上。
     *
     * 本章无可朗读段不是错误, 按原版顺延下一章 (对照 app 端 `TTSReadAloudService.play`: 整章
     * 没念出任何一段时 nextChapter 继续下一章); 顺延用循环而不是重新走一遍完整启动 —— 连续
     * 多章无内容时不再叠调用栈, 也不再按章重建/释放朗读引擎。
     *
     * @param chapterPosition 朗读起点在章内的字符位置; 顺延到下一章时为 0
     * @param fromLastSpeakable 是否从章内最后一个可朗读段起读; 顺延到下一章时为 false
     * @return true = 队列已停在可朗读段; false = 已置终态 (WAITING / COMPLETED / ERROR)
     */
    private fun openSpeakableChapter(
        chapterIndex: Int,
        chapterPosition: Int = 0,
        fromLastSpeakable: Boolean = false,
    ): Boolean {
        var index = chapterIndex
        var position = chapterPosition
        var lastSpeakable = fromLastSpeakable
        var skipped = 0
        while (true) {
            // 新请求先隔离旧队列: 正文未就绪时任何旧段都不能被 resume/迟到回调重新播放
            clearQueue()
            val request = PendingChapterRequest(index, position, lastSpeakable)
            pendingChapterRequest.value = request
            _chapterIndex.value = index
            _paragraphIndex.value = -1
            _chapterPosition.value = position
            _currentText.value = ""
            chapterNavigation.moveToChapter(index)
            _chapterSize.value = chapterData.chapterCount

            val plan = runCatching {
                chapterData.loadChapterPlan(index, position, lastSpeakable)
            }.getOrElse {
                pendingChapterRequest.value = null
                _lastError.tryEmit("加载章节内容失败: ${it.message}")
                _state.value = ReadAloudState.ERROR
                return false
            }
            if (plan == null) {
                // WAITING 只表示章节正文未就绪, 就绪后由 retryWaiting 重试; 起点之后无内容不属此类
                // (stopInternal 内取走并释放 HttpTTS 路由, 就绪重试时再按最新配置解析)
                stopInternal(clearState = false)
                _state.value = ReadAloudState.WAITING
                return false
            }

            // 下标钳制在队列内收口; 段表为空 (起点在章末之后 / 整章无段落) 时按"章内已无可朗读段"顺延
            playbackQueue.reset(
                contentList = plan.paragraphs,
                nowSpeak = plan.paragraphIndex,
                readAloudNumber = plan.chapterPosition,
                paragraphStartPos = plan.paragraphOffset,
            )
            if (playbackQueue.seekSpeakable().currentParagraph() != null) {
                if (skipped > 0) {
                    AppLog.put("连续 $skipped 章无可朗读内容, 已顺延到第 ${index + 1} 章")
                }
                pendingChapterRequest.compareAndSet(request, null)
                return true
            }

            // 章内已无可朗读段: 先作废本轮 (旧回调不得推进下一章的队列), 再顺延
            invalidatePlayback()
            index++
            position = 0
            lastSpeakable = false
            skipped++
            if (index >= chapterData.chapterCount) {
                // 到末章仍置 COMPLETED (对照原版: 末章无内容时 moveToNextChapter 失败即结束朗读)
                stopEngine()
                _state.value = ReadAloudState.COMPLETED
                return false
            }
            if (skipped >= MAX_CHAPTER_SKIPS) {
                val message = "连续 $skipped 章无可朗读内容, 已停止朗读"
                AppLog.put(message)
                _lastError.tryEmit(message)
                _state.value = ReadAloudState.ERROR
                return false
            }
        }
    }

    /** 章节更新事件只可完成与其绑定的 pending 请求；成功建队后请求由 [start] 清理。 */
    fun retryWaiting(chapterIndex: Int): Boolean {
        val request =
            pendingChapterRequest.value?.takeIf { it.chapterIndex == chapterIndex } ?: return false
        start(request.chapterIndex, request.chapterPosition, request.fromLastSpeakable)
        return _state.value != ReadAloudState.WAITING
    }

    /**
     * 决定本次会话的 HttpTTS 路由。
     *
     * 读 [ttsEngineConfigProvider] 取配置 (Book.ttsEngine 优先, 否则 AppConfig.ttsEngine),
     * 交 [resolveHttpTtsChannel] 判定 (与一次性朗读同一份): 配置缺失 / 非数字 / DAO 查不到源 /
     * 平台未注册在线播放器时降级走系统 TTS。
     *
     * @return 非 null 表示走 HttpTTS (整份快照); null 表示走系统 TTS
     */
    private fun resolveHttpTtsRoute(): HttpTtsRoute? {
        val channel = runBlocking {
            resolveHttpTtsChannel(
                ttsEngineConfigProvider(),
                httpTtsConfigLoader,
                httpTtsPlayerFactory,
            )
        } ?: return null

        return HttpTtsRoute(
            config = channel.config,
            player = channel.player,
            // HttpTTS speakSpeed (Int, 对标原版 AppConfig.ttsSpeechRate)
            speechRate = AppConfigProviders.get().ttsSpeechRate,
        )
    }

    /**
     * 暂停朗读。
     *
     * 桌面命令行 TTS (SAPI/espeak/say) 无真暂停能力, 实际等于 stop;
     * 各平台 actual 若支持真暂停 (如 Android TextToSpeech), 由引擎自行实现。
     *
     * 暂停后 [resume] 续读: 引擎支持真暂停时从中断处继续, 否则从当前段落起点重读。
     */
    fun pause() {
        if (_state.value != ReadAloudState.PLAYING && _state.value != ReadAloudState.WAITING) return
        // 一次取本次会话的路由: 暂停落在发起本轮播放的那台播放器上
        val http = httpRoute.value
        if (playbackPort != null) {
            playbackPort.onPause()
        } else if (http != null) {
            // HttpTTS 的 stop/prepare 语义比平台 pause 一致，resume 会按当前段重新 prepare。
            http.player.stop()
        } else {
            val engine = systemRoute.value?.engine
            engine?.pause()
            // 引擎无真暂停能力时退化为 stop；订阅与路由保留，resume 可重放当前段。
            // 真暂停引擎 (Windows SAPI) 保留位置, resume 走 engine.resume() 续读
            if (engine?.isPaused != true) engine?.stop()
        }
        _state.value = ReadAloudState.PAUSED
    }

    /**
     * 恢复朗读。
     *
     * 引擎真暂停时从中断处续读, 否则从当前段落起点重读 (与 [pause] 配对)。
     */
    fun resume() {
        if (_state.value != ReadAloudState.PAUSED) return
        if (playbackQueue.snapshot.contentList.isEmpty()) {
            val request = pendingChapterRequest.value
            if (request != null) {
                start(request.chapterIndex, request.chapterPosition, request.fromLastSpeakable)
            } else {
                _state.value = ReadAloudState.WAITING
            }
            return
        }
        if (playbackPort != null) {
            _state.value = ReadAloudState.PLAYING
            if (!playbackPort.onResume()) playCurrent()
        } else if (httpRoute.value != null) {
            _state.value = ReadAloudState.PLAYING
            playCurrent()
        } else {
            // 恢复时同步语速到本轮绑定的引擎；真暂停才原位 continue，否则重放当前段。
            val engine = systemRoute.value?.engine
            engine?.speechRate = _speechRate.value
            _state.value = ReadAloudState.PLAYING
            if (engine?.isPaused == true) engine.resume() else playCurrent()
        }
    }

    /**
     * 停止朗读并清空状态。
     *
     * 调用方退出阅读 / 用户主动停止时调用。
     */
    fun stop() {
        stopInternal(clearState = true)
        _state.value = ReadAloudState.STOPPED
    }

    /**
     * 设置朗读语速。
     *
     * UI 滑杆拖动时调用, 实时同步到引擎; 朗读中下一段 speak 即生效新速度。
     *
     * - 限制范围 0.5f ~ 2.0f (与桌面端 SAPI/espeak/say 速度参数范围对齐)
     * - 同步写入 [SystemTtsEngine.speechRate] (引擎 var 属性, 立即生效)
     * - 更新 [_speechRate] StateFlow, 触发 UI 滑杆重组
     *
     * @param rate 目标语速倍率 (会被 coerceIn 到 0.5..5.0, 对齐原版
     *   `(AppConfig.ttsSpeechRate + 5) / 10f` 与 seekBar 0..45 的取值域)
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 5.0f)
        _speechRate.value = clamped
        // 同步到当前引擎实例 (朗读中下一段 speak 即生效, 无需等 start/resume)
        systemRoute.value?.engine?.speechRate = clamped
        playbackPort?.onSpeechRateChanged(clamped)
    }

    /**
     * 恢复上次持久化的朗读章节索引。
     *
     * 仅供启动时从 PreferenceStore 恢复使用 —— 仅设 [_chapterIndex] StateFlow
     * 用于 UI 显示"上次朗读 X 章", **不触发切章 / loadChapter** (避免与
     * ReaderScreen 的 viewModel.loadChapter 冲突)。
     *
     * 用户点"播放"时 [TtsControlPanel] 用此 chapterIndex 作为 startIdx;
     * 若章节内容未加载, start 会按既定路径走 emptyList → ERROR, 用户重试即可。
     *
     * @param index 上次朗读章节索引 (0-based); < 0 视为无效, no-op
     */
    fun restoreChapterIndex(index: Int) {
        if (index < 0) return
        _chapterIndex.value = index
    }

    /**
     * 跳到下一段并朗读。
     *
     * 已是末段时触发 [nextChapter]。
     */
    fun nextParagraph() {
        if (_state.value != ReadAloudState.PLAYING || playbackQueue.snapshot.contentList.isEmpty()) return
        invalidatePlayback()
        stopEngine()
        if (!playbackQueue.stepNextOrEnd()) {
            nextChapter()
            return
        }
        playCurrent()
    }

    /**
     * 跳到上一段并朗读。
     *
     * 已是首段时触发 [prevChapter] (从上一章末段续读)。
     */
    fun prevParagraph() {
        if (_state.value != ReadAloudState.PLAYING || playbackQueue.snapshot.contentList.isEmpty()) return
        invalidatePlayback()
        stopEngine()
        if (!playbackQueue.retreatToPrevSpeakable()) {
            // “上一句”跨章时落到上一章最后一个可朗读段，和显式“上一章”语义分离。
            val previous = _chapterIndex.value - 1
            if (previous >= 0) start(previous, fromLastSpeakable = true)
            return
        }
        playCurrent()
    }

    /**
     * 跳到下一章并续读。
     *
     * 已是末章时 [state] 置 COMPLETED。
     */
    fun nextChapter() {
        val cur = _chapterIndex.value
        if (cur < 0) return
        if (cur + 1 >= chapterData.chapterCount) {
            // 已是末章
            stopEngine()
            _state.value = ReadAloudState.COMPLETED
            return
        }
        // start 通过导航端口切章并同步新队列。
        start(cur + 1)
    }

    /**
     * 跳到上一章并续读。
     *
     * 已是首章时 no-op。
     */
    fun prevChapter() {
        val cur = _chapterIndex.value
        if (cur <= 0) return
        // 显式“上一章”始终从章首开始，不借 Int.MAX_VALUE 伪造末尾位置。
        start(cur - 1, chapterPosition = 0)
    }

    // region 内部实现

    /**
     * 停止当前播放引擎 (平台播放外壳 / HttpTTS / 系统 TTS), 不清队列 (切段/切章前调用)。
     *
     * [route] 供已经从 [systemRoute] 取走路由的调用点传入: 引擎实例随路由一起离开原子字段,
     * 不把它带进来就读不到实例, 停止不会落到引擎上 (当前段念完才停)。
     */
    private fun stopEngine(route: SystemTtsRoute? = null) {
        // 两条路由各整份取值: HttpTTS 路由非空说明本轮在在线播放器上, 系统引擎不属本轮
        val http = httpRoute.value
        when {
            playbackPort != null -> playbackPort.onStop()
            http != null -> http.player.stop()
            route != null -> route.engine.stop()
            else -> systemRoute.value?.engine?.stop()
        }
    }

    /** 停在可朗读段上的队列快照 + 同一份快照里的段文本 (两者必须同源)。 */
    private class SpeakableParagraph(
        val playback: ReadAloudQueue.Snapshot,
        val text: String,
    )

    /**
     * 取停在可朗读段上的段落快照; 章内已无可朗读段时先顺延到下一个有内容的章。
     *
     * 顺延在 [openSpeakableChapter] 内循环 (不在本调用栈上叠加), 到末章仍置 COMPLETED。
     *
     * @return null = 已置终态 (WAITING / COMPLETED / ERROR), 调用方直接返回
     */
    private fun seekSpeakableParagraph(): SpeakableParagraph? {
        while (true) {
            val playback = playbackQueue.seekSpeakable()
            val text = playback.currentParagraph()
            if (text != null) return SpeakableParagraph(playback, text)
            if (!openSpeakableChapter(_chapterIndex.value + 1)) return null
        }
    }

    /**
     * 播放当前段落 (调用引擎 speak 或 HttpTTS setUrl+prepare, 立即返回)。
     *
     * 朗读完毕后由 [progressListener.onDone] (系统 TTS) 或
     * [HttpTtsPlayerListener.onEndOfMedia] (HttpTTS) 推进。
     */
    private fun playCurrent() {
        // 对照原版 buildSpeakPlan / TTSUtteranceListener.onStart: 纯标点/空白段不交给引擎。
        // SAPI 对这类文本瞬间完成, 轮询观察不到"朗读中"状态, 直接朗读会卡住推进;
        // 原版也是跳过 notReadAloudRegex 段 (buildSpeakPlan continue), 行为对齐。
        // 一次取停在可朗读段上的快照: 段文本与段内偏移/段下标必须同源 (分开读会跨队列版本)
        val speakable = seekSpeakableParagraph() ?: return
        val playback = speakable.playback
        val text = speakable.text
        _paragraphIndex.value = playback.nowSpeak
        _chapterPosition.value = playback.readAloudNumber
        _currentText.value = text
        val playbackText = text.substring(playback.paragraphStartPos.coerceIn(0, text.length))
        val token = playbackToken.incrementAndGet()
        // 先占轮次 (旧回调立即失效); 系统路再整份补上 utteranceId
        publishPlaybackRound(PlaybackRound(token, null))
        val http = httpRoute.value
        if (playbackPort != null) {
            playbackPort.onPlay(playbackText, _paragraphIndex.value, token)
        } else if (http != null) {
            http.player.listener = httpTtsProgressListener(token, http.player)
            playHttpTtsCurrent(http, playbackText)
        } else {
            val route = systemRoute.value ?: run {
                _lastError.tryEmit("未注册 TTS 引擎")
                _state.value = ReadAloudState.ERROR
                return
            }
            val utteranceId = route.utterancePrefix + playback.nowSpeak + "_" + token
            publishPlaybackRound(PlaybackRound(token, utteranceId))
            if (!route.engine.speak(playbackText, utteranceId)) {
                // 提交失败: 本轮没有在途 utterance, 清掉期望值 (令牌照旧生效, 旧回调仍被丢弃)
                publishPlaybackRound(PlaybackRound(token, null))
                _lastError.tryEmit("TTS 朗读提交失败")
                _state.value = ReadAloudState.ERROR
            }
        }
    }

    /**
     * HttpTTS 路径播放当前段。
     *
     * 完整源配置及 speakText/speakSpeed 经 setRequest 交给平台，prepare 后经
     * [httpTtsProgressListener] 触发 play。
     *
     * [route] 由调用点从 [httpRoute] 一次取来: 源配置/播放器/语速必须来自同一份快照, 否则会拿
     * 新配置去配旧播放器 (或反之), 提交失败被误报成"未注册 HttpTTS 播放器"。
     */
    /** 请求代次；异步 URL 求值完成时只允许当前代次提交给播放器。 */
    private val requestGeneration = atomic(0L)

    private fun playHttpTtsCurrent(route: HttpTtsRoute, text: String) {
        val generation = requestGeneration.incrementAndGet()
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        runCatching {
            if (generation != requestGeneration.value || _state.value != ReadAloudState.PLAYING) {
                return
            }
            route.player.setRequest(route.config, speakText, route.speechRate)
        }.onSuccess {
            if (generation != requestGeneration.value || _state.value != ReadAloudState.PLAYING) {
                return@onSuccess
            }
            route.player.prepare()
        }.onFailure {
            if (generation != requestGeneration.value) return@onFailure
            _lastError.tryEmit("HttpTTS url 求值失败: ${it.message}")
            _state.value = ReadAloudState.ERROR
        }
    }

    /**
     * 引擎朗读完一段后触发推进。
     *
     * 在引擎回调线程触发, 需注意与 [stop] / [nextParagraph] 的竞态:
     * - 用 [state] 校验: 仅 PLAYING 时推进
     * - 段级推进是队列快照的原子转换, 令牌比对在同一份读数上完成
     * - 切章不在队列推进内调 (对照原版 TTSUtteranceListener.nextParagraph: 段表推进到末尾才
     *   nextChapter; 这里不能无条件 nextChapter, 否则每段读完都切章,
     *   表现为"每章只念标题")
     */
    /** 平台播放器完成当前段时回调；令牌不匹配说明它属于已停止的旧播放。 */
    fun paragraphCompleted(token: Long) = onParagraphDone(token)

    private fun onParagraphDone(token: Long) {
        if (_state.value != ReadAloudState.PLAYING || token != playbackRound.value.token) return
        invalidatePlayback()
        if (playbackQueue.stepNextOrEnd()) {
            // 还有段落, 朗读下一段
            playCurrent()
            return
        }
        // 章末: 下一章正文已经能建出计划时直接换队列续读, 不拆会话重来。
        // 每次换章都 stop 引擎 + 重新注册 + 重新开始, 既不必要, 也让章末变成最容易卡住的地方。
        if (continueIntoNextChapter()) return
        // 本章节末段已朗读完 → 切下一章并续读 (不在队列推进内调 navigator)
        nextChapter()
    }

    /**
     * 无缝续读下一章: 换队列继续念, 不走 [start] 的整场重建。
     *
     * 只在下一章正文已就绪 (能同步建出段落计划且含有可朗读段) 时接手; 否则返回 false, 由
     * [nextChapter] 走原路径 (置 WAITING 等装载, 由等章看门狗兜底)。
     *
     * 与 [start] 的差别只有"不拆会话": 引擎路由、订阅令牌、播放轮次、状态全部保留, 章末只是
     * 换一份队列再提交下一段 —— 和段与段之间没有区别, 因此不会出现"引擎停了却迟迟不开口"。
     */
    private fun continueIntoNextChapter(): Boolean {
        val next = _chapterIndex.value + 1
        if (next < 0 || next >= chapterData.chapterCount) return false
        val plan = runCatching {
            chapterData.loadChapterPlan(next, 0, false)
        }.getOrNull() ?: return false
        // 整章没有可朗读段 (纯空章/纯标点) 时不接: 交回 nextChapter 的顺延逻辑
        if (plan.paragraphs.none { !it.matches(AppPattern.notReadAloudRegex) }) return false

        val request = PendingChapterRequest(next, 0, false)
        pendingChapterRequest.value = request
        // 对照 [start] 的新会话起点登记: 换章后翻页联动从章首重新起算
        ActiveReadAloudHostPorts.resetReadAloudPageCursor(0)
        _chapterIndex.value = next
        _paragraphIndex.value = -1
        _chapterPosition.value = 0
        _currentText.value = ""
        chapterNavigation.moveToChapter(next)
        _chapterSize.value = chapterData.chapterCount
        playbackQueue.reset(
            contentList = plan.paragraphs,
            nowSpeak = plan.paragraphIndex,
            readAloudNumber = plan.chapterPosition,
            paragraphStartPos = plan.paragraphOffset,
        )
        // 上面已保证章内有可朗读段, 这里必然停在某一段上
        playbackQueue.seekSpeakable()
        pendingChapterRequest.compareAndSet(request, null)
        playCurrent()
        return true
    }

    /**
     * 内部停止: 停引擎 + (可选) 清队列状态。
     *
     * @param clearState true=完全清空 (调用方 stop); false=保留状态供切章续读
     */
    private fun stopInternal(clearState: Boolean) {
        invalidatePlayback()
        // 一次取走整条 HttpTTS 路由: 源配置与播放器同源, 释放后不再被任何读点取到
        val http = takeHttpTtsRoute()
        if (http != null) {
            releaseHttpTtsPlayer(http)
        } else if (playbackPort == null) {
            // 取走整条路由再停/注销: 引擎实例与令牌同源, 不会拿错实例; 停止必须用取走的那份实例,
            // 只注销不动引擎会让当前段念完才停
            val preservePausedSystemRoute = !clearState && _state.value == ReadAloudState.PAUSED
            val route = if (preservePausedSystemRoute) null else takeSystemRoute()
            stopEngine(route)
            route?.engine?.unregisterProgressListener(route.token)
        } else {
            stopEngine()
        }
        if (clearState) {
            pendingChapterRequest.value = null
            clearQueue()
            _chapterIndex.value = -1
            _paragraphIndex.value = -1
            _chapterPosition.value = 0
            _currentText.value = ""
        }
    }

    /**
     * 作废当前轮次: 发新令牌并整份发布为"无期望 utterance", 旧轮次的迟到回调全部丢弃
     * ([invalidatePlayback] 之后到下一次 speak 之间不接受任何完成上报)。
     */
    private fun invalidatePlayback() {
        publishPlaybackRound(PlaybackRound(playbackToken.incrementAndGet(), null))
        requestGeneration.incrementAndGet()
    }

    private fun clearQueue() = playbackQueue.reset(emptyList())
    // endregion

    /** 外部报告错误（如异步加载章节失败）。 */
    fun reportError(message: String) {
        _lastError.tryEmit(message)
        _state.value = ReadAloudState.ERROR
    }

    /**
     * 朗读状态枚举。
     */
    enum class ReadAloudState {
        IDLE,
        PLAYING,
        PAUSED,
        STOPPED,
        COMPLETED,
        ERROR,
        WAITING,
    }

    private companion object {
        /**
         * 单次顺延最多跨过的无内容章数。
         *
         * 章节表正常时远达不到该值 (顺延到末章就会置 COMPLETED); 撞上它说明连续几百章都无可
         * 朗读内容, 属章节表异常, 置 ERROR 并记日志, 不让一次朗读无限扫章。
         */
        private const val MAX_CHAPTER_SKIPS = 500
    }
}

/**
 * 章节朗读计划 (由 [ReadAloudChapterDataPort.loadChapterPlan] 产出)。
 *
 * 段落表已被请求起点裁剪过: paragraphs[0] 就是从起点那段 (段内已读字符已截掉)。
 *
 * @param paragraphs 待朗读段落表 (已按起点裁剪); 空表 = 起点之后无内容 (调用方跳过)
 * @param paragraphIndex 先读的段下标 (fromLastSpeakable 时指向最后一个可读段, 否则 0)
 * @param paragraphOffset 先读段内的起始偏移 (承 paragraphs 已裁剪, 建队后为 0)
 * @param chapterPosition 朗读起点在章内的真实字符位置 (段起点 + 段内偏移, 与
 *   [ReadAloudQueue.Snapshot.readAloudNumber] 同一累加空间)
 */
data class ReadAloudChapterPlan(
    val paragraphs: List<String>,
    val paragraphIndex: Int = 0,
    val paragraphOffset: Int = 0,
    val chapterPosition: Int = 0,
)

/**
 * 章节数据访问端口：只负责提供章节规模与可播放计划。
 *
 * 抽象出 [ReadAloudControllerShared] 需要的章节能力, 避免 commonMain 直接依赖
 * [io.legado.app.ui.book.read.ReadBookViewModelShared] (UI 层); 调用方接阅读页的已排版
 * 章节 (Android: BaseReadAloudService 的 androidChapterData; 其余端: ReadAloudHostShared)。
 *
 * 章节未就绪时返回 null, 控制器置 WAITING, 就绪后由
 * [ReadAloudControllerShared.retryWaiting] 重试；正文已就绪但起点之后无内容时返回空段表,
 * 控制器按"章内已无可朗读段"同一条路径跳过 (不置 WAITING 等一个永不到来的更新)。
 */
interface ReadAloudChapterDataPort {
    val chapterCount: Int
    fun loadChapterPlan(
        chapterIndex: Int,
        chapterPosition: Int = 0,
        fromLastSpeakable: Boolean = false,
    ): ReadAloudChapterPlan?
}

/** 章节导航端口：只负责让阅读宿主切到目标章节。 */
fun interface ReadAloudChapterNavigationPort {
    fun moveToChapter(chapterIndex: Int)
}

/** 平台播放器端口；Android Service 用它保留系统播放外壳而复用本控制器业务状态机。 */
interface ReadAloudPlaybackPort {
    fun onPlay(text: String, paragraphIndex: Int, playbackToken: Long)
    fun onStop()
    fun onPause()
    fun onResume(): Boolean
    fun onSpeechRateChanged(rate: Float) = Unit
}

/**
 * 当前生效的 TTS 引擎配置: Book.ttsEngine 优先, 否则 AppConfig.ttsEngine
 * (对照原版 `ReadAloud.ttsEngine`)。桌面 / iOS / 鸿蒙三端曾各传一份逐字相同的 lambda。
 */
fun defaultTtsEngineConfig(): String? =
    ActiveReadBookRegistry.current?.bookValue?.config?.ttsEngine?.takeIf { it.isNotBlank() }
        ?: AppConfigProviders.get().ttsEngine.takeIf { it.isNotBlank() }
