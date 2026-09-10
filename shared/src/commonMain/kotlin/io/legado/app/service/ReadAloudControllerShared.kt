package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.tts.HttpTtsPlayer
import io.legado.app.help.tts.HttpTtsPlayerListener
import io.legado.app.help.tts.HttpTtsRequest
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.help.tts.SystemTtsEngine
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.help.tts.TtsProgressListener
import io.legado.app.help.tts.TtsProgressListenerToken
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
 * 跨平台朗读控制器 (commonMain): 在 [io.legado.app.help.tts.ReadAloudController] 段级协调
 * (队列推进 + 选路 TTS + 状态广播) 之上补章节联动——“当前章节朗读完 → 自动切下一章”。
 * 对照 app 端 `TTSReadAloudService` 编排 (Service/Notification/MediaSession 强依赖 Android,
 * 无法下沉), 行为对齐 `TTSUtteranceListener.nextParagraph()` + `BaseReadAloudService.nextChapter()`。
 *
 * - [ReadAloudChapterNavigator] 注入章节内容获取 + 切换 (调用方桥接 ReadBookViewModelShared)
 * - 不修改/不替换既有 [ReadAloudController], 二者并存 (后者留给细粒度段级控制场景);
 *   复用 [ReadAloudQueue] 纯状态机
 * - 线程: 本类方法在调用方线程执行; 引擎 onDone 在工作线程回调, 用 @Volatile + synchronized
 *   保护段级推进 (stop 时 onDone 仍触发); navigator 实现方自行处理跨线程
 * - 简化项: pause 实际等于 stop (桌面 TTS 限制, resume 从头读当前段); 不支持 HttpTTS
 *   (走 [ReadAloudController]); 不实现睡眠定时/通知/媒体会话/音频焦点 (app 端 Service 职责)
 *
 * @param navigator 章节导航器, 由调用方桥接到 ViewModel
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
    private val queue: ReadAloudQueue get() = playbackQueue

    // atomicfu 锁对象 (Native target 必须用 SynchronizedObject, 不能用普通类实例当锁)
    private val queueLock = SynchronizedObject()

    /** 本控制器持有的系统 TTS 监听订阅，只按 token 精确注销。 */
    private var progressListenerToken: TtsProgressListenerToken? = null
    private var progressListenerEngine: SystemTtsEngine? = null
    private var systemRunCount = 0L
    private var systemUtterancePrefix = ""
    private var expectedSystemUtteranceId: String? = null

    /** 每次实际下发播放都分配新令牌，迟到的完成回调不能推进新队列。 */
    private var playbackToken = 0L
    private var activePlaybackToken = 0L

    /** 正文尚未就绪时保留的请求；位置始终与目标章节绑定。 */
    private data class PendingChapterRequest(
        val chapterIndex: Int,
        val chapterPosition: Int,
        val fromLastSpeakable: Boolean,
    )

    private var pendingChapterRequest: PendingChapterRequest? = null

    // region HttpTTS 路由状态

    /** 本次朗读是否走 HttpTTS; start 时由 [resolveEngine] 决定。 */
    @Volatile
    private var useHttpTts: Boolean = false

    /** 当前 HttpTTS 源配置, 由 [resolveEngine] 加载; null 表示未走 HttpTTS。 */
    @Volatile
    private var httpTtsConfig: HttpTTS? = null

    /** 当前 HttpTTS 播放器实例, 由 [resolveEngine] 创建; 切章/start 重建, stop 释放。 */
    @Volatile
    private var httpTtsPlayer: HttpTtsPlayer? = null

    /** HttpTTS speakSpeed 变量值 (Int, 对标原版 AppConfig.ttsSpeechRate)。 */
    @Volatile
    private var httpTtsSpeechRate: Int = 0
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
            if (utteranceId == expectedSystemUtteranceId) {
                onParagraphDone(activePlaybackToken)
            }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (utteranceId != expectedSystemUtteranceId) return
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
     * 对标 [io.legado.app.help.tts.ReadAloudController] 的 init 块 listener:
     * - onReady: prepare 完成 → play (仅当当前 state 为 PLAYING, 与系统 TTS onDone 用同一判断对齐)
     * - onEndOfMedia: 本段播完 → [onParagraphDone] (复用系统 TTS 的段级推进逻辑)
     * - onError: 错误信息写入 [_lastError] + state 置 [ReadAloudState.ERROR]
     */
    private fun httpTtsProgressListener(token: Long) = object : HttpTtsPlayerListener {
        override fun onReady() {
            if (_state.value == ReadAloudState.PLAYING && token == activePlaybackToken) {
                httpTtsPlayer?.play()
            }
        }

        override fun onEndOfMedia() = onParagraphDone(token)

        override fun onError(message: String) {
            if (token != activePlaybackToken) return
            _lastError.tryEmit("HttpTTS 播放出错: $message")
            _state.value = ReadAloudState.ERROR
        }

        override fun onBufferingUpdate(percent: Int) = Unit
    }

    /**
     * 开始朗读指定章节。
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
        pendingChapterRequest = request
        if (chapterIndex !in 0 until chapterData.chapterCount) {
            pendingChapterRequest = null
            _lastError.tryEmit("章节索引越界: $chapterIndex")
            _state.value = ReadAloudState.ERROR
            return
        }

        // 必须先按旧路由停播/注销，再读取配置解析新路由；否则切换系统/HttpTTS 会停错实例。
        _state.value = ReadAloudState.STOPPED
        stopInternal(clearState = false)
        releaseHttpTtsPlayer()
        useHttpTts = playbackPort == null && resolveEngine()

        if (playbackPort == null && !useHttpTts) {
            val existingEngine = progressListenerEngine
            if (existingEngine != null && progressListenerToken != null) {
                existingEngine.speechRate = _speechRate.value
            } else {
                val engine = engineProvider()
                if (engine == null) {
                    _lastError.tryEmit("未注册 TTS 引擎, 无法朗读")
                    _state.value = ReadAloudState.ERROR
                    return
                }
                systemUtterancePrefix = "legado_read_${++systemRunCount}_"
                progressListenerEngine = engine
                progressListenerToken = engine.registerProgressListener(
                    progressListener,
                    systemUtterancePrefix,
                )
                engine.speechRate = _speechRate.value
            }
        }

        // 新请求先隔离旧队列。正文未就绪时任何旧段都不能被 resume/迟到回调重新播放。
        clearQueue()
        _chapterIndex.value = chapterIndex
        _paragraphIndex.value = -1
        _chapterPosition.value = request.chapterPosition
        _currentText.value = ""
        chapterNavigation.moveToChapter(chapterIndex)
        _chapterSize.value = chapterData.chapterCount

        val plan = runCatching {
            chapterData.loadChapterPlan(
                chapterIndex,
                request.chapterPosition,
                request.fromLastSpeakable,
            )
        }.getOrElse {
            pendingChapterRequest = null
            _lastError.tryEmit("加载章节内容失败: ${it.message}")
            _state.value = ReadAloudState.ERROR
            return
        }
        if (plan == null || plan.paragraphs.isEmpty()) {
            // WAITING 不持有新路由资源；就绪重试时再按最新配置解析并注册。
            stopInternal(clearState = false)
            releaseHttpTtsPlayer()
            _state.value = ReadAloudState.WAITING
            return
        }

        synchronized(queueLock) {
            queue.contentList = plan.paragraphs
            queue.nowSpeak = plan.paragraphIndex.coerceIn(0, plan.paragraphs.lastIndex)
            queue.readAloudNumber = plan.chapterPosition.coerceAtLeast(0)
            queue.paragraphStartPos = plan.paragraphOffset.coerceIn(
                0,
                plan.paragraphs[queue.nowSpeak].length,
            )
        }
        if (pendingChapterRequest == request) pendingChapterRequest = null
        _state.value = ReadAloudState.PLAYING
        playCurrent()
    }

    /** 章节更新事件只可完成与其绑定的 pending 请求；成功建队后请求由 [start] 清理。 */
    fun retryWaiting(chapterIndex: Int): Boolean {
        val request =
            pendingChapterRequest?.takeIf { it.chapterIndex == chapterIndex } ?: return false
        start(request.chapterIndex, request.chapterPosition, request.fromLastSpeakable)
        return _state.value != ReadAloudState.WAITING
    }

    /**
     * 决定本次朗读走 HttpTTS 还是系统 TTS。
     *
     * 读 [ttsEngineConfigProvider] 取配置 (Book.ttsEngine 优先, 否则 AppConfig.ttsEngine),
     * isNumeric → 用 [httpTtsConfigLoader] 查 DAO 拿 [HttpTTS] → 用 [httpTtsPlayerFactory] 造 player。
     * 配置缺失 / DAO 查不到源 / 工厂未注册时降级走系统 TTS。
     *
     * @return true 表示走 HttpTTS, false 表示走系统 TTS
     */
    private fun resolveEngine(): Boolean {
        httpTtsConfig = null

        val ttsEngine = ttsEngineConfigProvider()?.takeIf { it.isNotBlank() } ?: return false
        // isNumeric 判断 (替代 StringUtils.isNumeric, 不引入 apache commons 依赖)
        if (ttsEngine.any { !it.isDigit() }) return false

        val id = ttsEngine.toLong()
        // 源被删掉时回落系统 TTS (对照原版 ReadAloud.getReadAloudClass: httpTTS == null 走 TTSReadAloudService)
        val config = runBlocking { httpTtsConfigLoader(id) } ?: return false

        val player = httpTtsPlayerFactory(config) ?: return false

        httpTtsConfig = config
        httpTtsPlayer = player
        // HttpTTS speakSpeed (Int, 对标原版 AppConfig.ttsSpeechRate)
        httpTtsSpeechRate = AppConfigProviders.get().ttsSpeechRate
        return true
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
        if (playbackPort != null) {
            playbackPort.onPause()
        } else if (useHttpTts) {
            // HttpTTS 的 stop/prepare 语义比平台 pause 一致，resume 会按当前段重新 prepare。
            httpTtsPlayer?.stop()
        } else {
            val engine = progressListenerEngine
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
        if (queue.contentList.isEmpty()) {
            val request = pendingChapterRequest
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
        } else if (useHttpTts) {
            _state.value = ReadAloudState.PLAYING
            playCurrent()
        } else {
            // 恢复时同步语速到本轮绑定的引擎；真暂停才原位 continue，否则重放当前段。
            val engine = progressListenerEngine
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
        progressListenerEngine?.speechRate = clamped
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
        if (_state.value != ReadAloudState.PLAYING || queue.contentList.isEmpty()) return
        invalidatePlayback()
        stopEngine()
        val hasNext = synchronized(queueLock) {
            queue.stepNextOrEnd()
        }
        if (!hasNext) {
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
        if (_state.value != ReadAloudState.PLAYING || queue.contentList.isEmpty()) return
        invalidatePlayback()
        stopEngine()
        val shouldCrossChapter = synchronized(queueLock) {
            if (queue.nowSpeak <= 0) true
            else {
                queue.retreatToPrevSpeakable()
                false
            }
        }
        if (shouldCrossChapter) {
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
     * 停止当前播放引擎 (HttpTTS 或系统 TTS), 不清队列 (切段/切章前调用)。
     */
    private fun stopEngine() {
        if (playbackPort != null) {
            playbackPort.onStop()
        } else if (useHttpTts) {
            httpTtsPlayer?.stop()
        } else {
            progressListenerEngine?.stop()
        }
    }

    /**
     * 播放当前段落 (调用引擎 speak 或 HttpTTS setUrl+prepare, 立即返回)。
     *
     * 朗读完毕后由 [progressListener.onDone] (系统 TTS) 或
     * [HttpTtsPlayerListener.onEndOfMedia] (HttpTTS) 推进。
     */
    private fun playCurrent() {
        val text: String? = synchronized(queueLock) {
            // 对照原版 buildSpeakPlan / TTSUtteranceListener.onStart: 纯标点/空白段不交给引擎。
            // SAPI 对这类文本瞬间完成, 轮询观察不到"朗读中"状态, 直接朗读会卡住推进;
            // 原版也是跳过 notReadAloudRegex 段 (buildSpeakPlan continue), 行为对齐。
            var current: String? = queue.contentList.getOrNull(queue.nowSpeak)
            while (current?.matches(AppPattern.notReadAloudRegex) == true) {
                if (!queue.advanceToNextSpeakable()) {
                    // 章内已无可朗读段
                    current = null
                    break
                }
                current = queue.contentList.getOrNull(queue.nowSpeak)
            }
            current?.let {
                _paragraphIndex.value = queue.nowSpeak
                _chapterPosition.value = queue.readAloudNumber
                _currentText.value = it
            }
            current
        }
        if (text == null) {
            // 章内已无可朗读段 → 切下一章 (对照原版 play() 的 contentList.isEmpty → nextChapter)
            nextChapter()
            return
        }
        val playbackText = synchronized(queueLock) {
            val offset = queue.paragraphStartPos.coerceIn(0, text.length)
            text.substring(offset)
        }
        val token = ++playbackToken
        activePlaybackToken = token
        if (playbackPort != null) {
            playbackPort.onPlay(playbackText, _paragraphIndex.value, token)
        } else if (useHttpTts) {
            httpTtsPlayer?.listener = httpTtsProgressListener(token)
            playHttpTtsCurrent(playbackText)
        } else {
            val engine = progressListenerEngine ?: run {
                _lastError.tryEmit("未注册 TTS 引擎")
                _state.value = ReadAloudState.ERROR
                return
            }
            val utteranceId = systemUtterancePrefix + _paragraphIndex.value + "_" + token
            expectedSystemUtteranceId = utteranceId
            if (!engine.speak(playbackText, utteranceId)) {
                expectedSystemUtteranceId = null
                _lastError.tryEmit("TTS 朗读提交失败")
                _state.value = ReadAloudState.ERROR
            }
        }
    }

    /**
     * HttpTTS 路径播放当前段。
     *
     * 对标 [io.legado.app.help.tts.ReadAloudController.playHttpTts]:
     * [AnalyzeUrlFactories.create] 求值源 url 模板 (注入 speakText/speakSpeed 变量,
     * 含源级 headers/cookie) 得到 url+headers → setUrl → prepare, onReady 后经
     * [httpTtsProgressListener] 触发 play。
     *
     * POST/body 型源首版接受降级 (只支持 GET 流式, 由各平台 actual 兜底)。
     */
    /** 请求代次；异步 URL 求值完成时只允许当前代次提交给播放器。 */
    private var requestGeneration = 0L

    private fun playHttpTtsCurrent(text: String) {
        val generation = ++requestGeneration
        val player = httpTtsPlayer ?: run {
            _lastError.tryEmit("未注册 HttpTTS 播放器")
            _state.value = ReadAloudState.ERROR
            return
        }
        val config = httpTtsConfig ?: run {
            _lastError.tryEmit("未设置 HttpTTS 源配置")
            _state.value = ReadAloudState.ERROR
            return
        }
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        runCatching {
            AnalyzeUrlFactories.create(
                config.url,
                source = config,
                readTimeout = HttpTtsRequest.READ_TIMEOUT_MS,
                variables = HttpTtsRequest.speakVariables(speakText, httpTtsSpeechRate),
            )
        }.onSuccess { analyzeUrl ->
            if (generation != requestGeneration || _state.value != ReadAloudState.PLAYING) {
                return@onSuccess
            }
            player.setUrl(analyzeUrl.url, analyzeUrl.headerMap)
            player.prepare()
        }.onFailure {
            if (generation != requestGeneration) return@onFailure
            _lastError.tryEmit("HttpTTS url 求值失败: ${it.message}")
            _state.value = ReadAloudState.ERROR
        }
    }

    /**
     * 引擎朗读完一段后触发推进。
     *
     * 在引擎后台线程触发, 需注意与 [stop] / [nextParagraph] 的竞态:
     * - 用 [state] 校验: 仅 PLAYING 时推进
     * - 用 [queue] 锁保护段级状态
     * - 切章在锁外调 (对照原版 TTSUtteranceListener.nextParagraph: advanceToNextSpeakable
     *   返回 false 才 nextChapter; 这里不能无条件 nextChapter, 否则每段读完都切章,
     *   表现为"每章只念标题")
     */
    /** 平台播放器完成当前段时回调；令牌不匹配说明它属于已停止的旧播放。 */
    fun paragraphCompleted(token: Long) = onParagraphDone(token)

    private fun onParagraphDone(token: Long) {
        if (_state.value != ReadAloudState.PLAYING || token != activePlaybackToken) return
        invalidatePlayback()
        val hasNext = synchronized(queueLock) {
            queue.stepNextOrEnd()
        }
        if (hasNext) {
            // 还有段落, 朗读下一段 (playCurrent 内自持锁)
            playCurrent()
        } else {
            // 本章节末段已朗读完 → 切下一章并续读 (锁外调, 避免 navigator 回调持锁)
            nextChapter()
        }
    }

    /**
     * 内部停止: 停引擎 + (可选) 清队列状态。
     *
     * @param clearState true=完全清空 (调用方 stop); false=保留状态供切章续读
     */
    private fun stopInternal(clearState: Boolean) {
        invalidatePlayback()
        if (useHttpTts) {
            releaseHttpTtsPlayer()
        } else if (playbackPort == null) {
            val preservePausedSystemRoute = !clearState && _state.value == ReadAloudState.PAUSED
            if (!preservePausedSystemRoute) {
                progressListenerToken?.let { token ->
                    progressListenerEngine?.unregisterProgressListener(token)
                }
                progressListenerToken = null
                progressListenerEngine = null
                systemUtterancePrefix = ""
            }
        }
        expectedSystemUtteranceId = null
        stopEngine()
        if (clearState) {
            pendingChapterRequest = null
            releaseHttpTtsPlayer()
            clearQueue()
            _chapterIndex.value = -1
            _paragraphIndex.value = -1
            _chapterPosition.value = 0
            _currentText.value = ""
        }
    }

    private fun invalidatePlayback() {
        activePlaybackToken = ++playbackToken
        requestGeneration++
        expectedSystemUtteranceId = null
    }

    private fun clearQueue() = synchronized(queueLock) {
        queue.contentList = emptyList()
        queue.nowSpeak = 0
        queue.readAloudNumber = 0
        queue.paragraphStartPos = 0
    }

    /**
     * 释放 HttpTTS 播放器资源。
     *
     * stop + release + 清空字段; 切章 (start 重建) 与退出 ReaderScreen (stop) 时调用。
     */
    private fun releaseHttpTtsPlayer() {
        httpTtsPlayer?.let {
            it.listener = null
            it.stop()
            it.release()
        }
        httpTtsPlayer = null
        httpTtsConfig = null
    }
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
}

/**
 * 章节导航器接口 (调用方实现, 桥接到 ViewModel)。
 *
 * 抽象出 [ReadAloudControllerShared] 需要的章节能力, 避免 commonMain 直接依赖
 * [io.legado.app.ui.book.read.ReadBookViewModelShared] (UI 层)。
 *
 * 桌面端 actual 实现示例 (放在 desktop 模块):
 * ```
 * class DesktopReadAloudNavigator(
 *     private val viewModel: ReadBookViewModelShared,
 * ) : ReadAloudChapterNavigator {
 *     override val chapterCount: Int
 *         get() = viewModel.readBook.chapterSize
 *
 *     override fun loadChapterParagraphs(chapterIndex: Int): List<String> {
 *         // 桥接到 BookStorageProviders.get().getContent + WebBook
 *         // 简化: 用 ReadBookShared.curTextChapter 已加载的正文切段
 *         val chapter = viewModel.readBook.chapterList.value.getOrNull(chapterIndex) ?: return emptyList()
 *         val book = viewModel.readBook.book.value ?: return emptyList()
 *         val content = BookStorageProviders.get().getContent(book, chapter) ?: return emptyList()
 *         return ReadAloudQueue.splitParagraphs(content)
 *     }
 *
 *     override fun moveToChapter(chapterIndex: Int) {
 *         // 调 viewModel.loadChapter (会触发联网拉取与排版)
 *         viewModel.loadChapter(chapterIndex)
 *     }
 *
 *     override fun moveToNextChapter() = viewModel.moveToNextChapter()
 *     override fun moveToPrevChapter() = viewModel.moveToPrevChapter()
 * }
 * ```
 */
data class ReadAloudChapterPlan(
    val paragraphs: List<String>,
    val paragraphIndex: Int = 0,
    val paragraphOffset: Int = 0,
    val chapterPosition: Int = 0,
)

/** 章节数据访问端口：只负责提供章节规模与可播放计划。 */
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

/** 兼容旧调用方的组合端口，新代码应分别注入数据与导航端口。 */
@Deprecated("Inject ReadAloudChapterDataPort and ReadAloudChapterNavigationPort separately")
interface ReadAloudChapterNavigator : ReadAloudChapterDataPort, ReadAloudChapterNavigationPort {
    fun loadChapterParagraphs(chapterIndex: Int): List<String>
    override fun loadChapterPlan(
        chapterIndex: Int,
        chapterPosition: Int,
        fromLastSpeakable: Boolean,
    ): ReadAloudChapterPlan? = ReadAloudChapterPlan(loadChapterParagraphs(chapterIndex))

    fun moveToNextChapter() = Unit
    fun moveToPrevChapter() = Unit
}

/**
 * 当前生效的 TTS 引擎配置: Book.ttsEngine 优先, 否则 AppConfig.ttsEngine
 * (对照原版 `ReadAloud.ttsEngine`)。桌面 / iOS / 鸿蒙三端曾各传一份逐字相同的 lambda。
 */
fun defaultTtsEngineConfig(): String? =
    ActiveReadBookRegistry.current?.bookValue?.config?.ttsEngine?.takeIf { it.isNotBlank() }
        ?: AppConfigProviders.get().ttsEngine.takeIf { it.isNotBlank() }
