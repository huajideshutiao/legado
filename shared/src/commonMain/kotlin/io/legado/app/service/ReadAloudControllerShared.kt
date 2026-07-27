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
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * 跨平台朗读控制器（KMP commonMain 版, KP2-D P0-8 新增）。
 *
 * # 设计动机
 *
 * app 端朗读编排链路为 `ReadAloud` (静态门面) + `BaseReadAloudService` (Service 编排)
 * + `TTSReadAloudService` / `HttpReadAloudService` (actual 引擎), 强依赖 Android
 * Service / Notification / MediaSession / PhoneStateListener, 无法直接下沉到 commonMain。
 *
 * commonMain 已有 [io.legado.app.help.tts.ReadAloudController] 做段级协调 (队列推进 +
 * 选择哪路 TTS + 状态广播), 但**缺章节联动** —— 它的 onComplete 由调用方驱动, 没把
 * "当前章节朗读完 → 自动切下一章" 这一层抽象出来。
 *
 * 本类在 [io.legado.app.help.tts.ReadAloudController] 基础上补章节联动:
 * - 持有当前 [chapterIndex] / [paragraphIndex]
 * - 通过 [ReadAloudChapterNavigator] 接口注入章节内容获取 + 章节切换能力
 *   (实际由调用方桥接到 [io.legado.app.ui.book.read.ReadBookViewModelShared])
 * - 通过 [TtsEngineProvider] 取 [SystemTtsEngine] 实例 (默认查 Provider, 支持测试注入)
 * - 通过 [TtsProgressListener] 监听引擎 onDone 触发段级推进
 * - 段落末尾 → [ReadAloudChapterNavigator.moveToNextChapter] 切下一章并续读
 *
 * # 与既有抽象的关系
 *
 * - **不修改** [io.legado.app.help.tts.ReadAloudController]: 该类是 KP2-H 留下的
 *   段级协调器, 行为稳定且不含章节概念, 不应在本任务中改动其 API
 * - **不替换**: 本类与 [io.legado.app.help.tts.ReadAloudController] 并存, 后者留给
 *   需要细粒度段级控制但不需章节联动的场景 (如 HttpTTS 自管 URL 拼装)
 * - **复用** [ReadAloudQueue]: 纯状态机, 章节无关, 本类直接组合使用
 *
 * # 章节联动流程 (与 app 端 `TTSReadAloudService` 对照)
 *
 * ```
 *   start(chapterIndex=3)
 *     ↓
 *   navigator.loadChapterParagraphs(3) → ["第一段", "第二段", ...]
 *     ↓
 *   queue.contentList = paragraphs; queue.nowSpeak = 0
 *     ↓
 *   engine.speak("第一段", "0")  ← 异步, 立即返回
 *     ↓
 *   (引擎朗读完毕)
 *     ↓
 *   progressListener.onDone("0")  ← 在引擎后台线程触发
 *     ↓
 *   queue.stepNextOrEnd() → true (还有段落)
 *     ↓
 *   engine.speak("第二段", "1")
 *     ↓
 *   (...)
 *     ↓
 *   progressListener.onDone("lastIndex")
 *     ↓
 *   queue.stepNextOrEnd() → false (本章节末段)
 *     ↓
 *   navigator.moveToNextChapter()  ← 调 viewModel.moveToNextChapter()
 *     ↓
 *   start(chapterIndex=4)  ← 递归续读下一章
 * ```
 *
 * 与 app 端 `TTSReadAloudService.TTSUtteranceListener.nextParagraph()` +
 * `BaseReadAloudService.nextChapter()` 行为对齐。
 *
 * # 线程模型
 *
 * - 本类自身方法在调用方线程执行 (通常 Compose 主线程)
 * - [TtsProgressListener] 回调由引擎在工作线程触发, 本类内部用 `@Volatile` 状态字段
 *   + synchronized 块保护段级推进, 避免竞态 (stop 时 onDone 仍触发)
 * - [ReadAloudChapterNavigator] 实现方需自行处理跨线程 (如 Compose 主线程调度)
 *
 * # 简化项
 *
 * - 不支持暂停后从段落中间位置续读 (与桌面命令行 TTS 限制一致); pause 实际等于 stop,
 *   resume 重新从头朗读当前段
 * - 不支持 HttpTTS (本类专注系统 TTS, HttpTTS 走 [io.legado.app.help.tts.ReadAloudController])
 * - 不实现睡眠定时 / 通知 / 媒体会话 / 音频焦点 (这些是 app 端 Service 职责)
 *
 * @param navigator 章节导航器, 由调用方桥接到 ViewModel
 * @param engineProvider TTS 引擎提供者, 默认查 [TtsEngineProvider]; 测试时可注入 mock
 * @param httpTtsPlayerFactory HttpTTS 播放器工厂, 默认查 [TtsEngineProvider.getHttpTtsPlayer]
 * @param ttsEngineConfigProvider TTS 引擎配置 (Book.ttsEngine 优先, 否则 AppConfig.ttsEngine),
 *                                 返回数字串表示 HttpTTS id, 空/非数字走系统 TTS
 * @param httpTtsConfigLoader HttpTTS 源配置加载器, 用 id 从 DAO 查 [HttpTTS]
 */
class ReadAloudControllerShared(
    private val navigator: ReadAloudChapterNavigator,
    private val engineProvider: () -> SystemTtsEngine? = { TtsEngineProvider.get() },
    private val httpTtsPlayerFactory: (HttpTTS) -> HttpTtsPlayer? = { TtsEngineProvider.getHttpTtsPlayer(it) },
    private val ttsEngineConfigProvider: () -> String? = { null },
    private val httpTtsConfigLoader: suspend (Long) -> HttpTTS? = { AppDbProviders.get().httpTTSDao.get(it) },
) {

    /** 朗读队列 (段级状态机), 与 [io.legado.app.help.tts.ReadAloudController] 共用。 */
    private val queue = ReadAloudQueue()

    // atomicfu 锁对象 (Native target 必须用 SynchronizedObject, 不能用普通类实例当锁)
    private val queueLock = SynchronizedObject()

    // region HttpTTS 路由状态 (KP2-D P0-9 新增)

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

    /** 当前段落文本 (供 UI 显示"正在朗读"高亮)。 */
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    /** 最近一次错误信息 (供 UI 显示 toast)。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * 朗读语速倍率 (KP2-D P1 新增)。
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
            // 引擎朗读完一段, 触发推进
            onParagraphDone()
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            _lastError.value = "TTS 朗读出错 (code=$errorCode, utteranceId=$utteranceId)"
            _state.value = ReadAloudState.ERROR
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            // 范围进度: 桌面命令行 TTS 不提供, no-op
        }
    }

    /**
     * HttpTTS 播放器回调桥接器 (KP2-D P0-9 新增)。
     *
     * 对标 [io.legado.app.help.tts.ReadAloudController] 的 init 块 listener:
     * - onReady: prepare 完成 → play (仅当当前 state 为 PLAYING, 与系统 TTS onDone 用同一判断对齐)
     * - onEndOfMedia: 本段播完 → [onParagraphDone] (复用系统 TTS 的段级推进逻辑)
     * - onError: 错误信息写入 [_lastError] + state 置 [ReadAloudState.ERROR]
     */
    private val httpTtsProgressListener = object : HttpTtsPlayerListener {
        override fun onReady() {
            if (_state.value == ReadAloudState.PLAYING) httpTtsPlayer?.play()
        }

        override fun onEndOfMedia() = onParagraphDone()

        override fun onError(message: String) {
            _lastError.value = "HttpTTS 播放出错: $message"
            _state.value = ReadAloudState.ERROR
        }

        override fun onBufferingUpdate(percent: Int) = Unit
    }

    /**
     * 开始朗读指定章节。
     *
     * @param chapterIndex 章节序号 (0-based); 越界时 state 置 ERROR
     */
    fun start(chapterIndex: Int) {
        // KP2-D P0-9: 决定本次走 HttpTTS 还是系统 TTS (基于 ttsEngineConfigProvider)
        useHttpTts = resolveEngine()

        // 切章前先停掉当前朗读 (避免 onDone 触发推进与新章节竞态)
        stopInternal(clearState = false)

        if (useHttpTts) {
            // HttpTTS 路径: 注入 player listener (player 由 resolveEngine 创建)
            httpTtsPlayer?.listener = httpTtsProgressListener
        } else {
            // 系统 TTS 路径: 注入 progressListener (幂等, 引擎实例可能复用)
            val engine = engineProvider()
            if (engine == null) {
                _lastError.value = "未注册 TTS 引擎, 无法朗读"
                _state.value = ReadAloudState.ERROR
                return
            }
            engine.progressListener = progressListener
            // KP2-D P1: 同步朗读语速到引擎 (start 路径, 每次开始朗读都把当前 speechRate 灌进去)
            engine.speechRate = _speechRate.value
        }

        // 通知 navigator 切到目标章节 (调用方会触发 viewModel.loadChapter)
        navigator.moveToChapter(chapterIndex)

        // 同步状态: 章节大小
        _chapterSize.value = navigator.chapterCount

        // 取章节段落 (调用方通过 navigator 桥接 BookStorageProviders / WebBook)
        val paragraphs = runCatching {
            navigator.loadChapterParagraphs(chapterIndex)
        }.getOrElse {
            _lastError.value = "加载章节内容失败: ${it.message}"
            _state.value = ReadAloudState.ERROR
            return
        }
        if (paragraphs.isEmpty()) {
            _lastError.value = "章节内容为空"
            _state.value = ReadAloudState.ERROR
            return
        }

        // 重置队列 + 状态
        synchronized(queueLock) {
            queue.contentList = paragraphs
            queue.nowSpeak = 0
            queue.readAloudNumber = 0
            queue.paragraphStartPos = 0
        }
        _chapterIndex.value = chapterIndex
        _state.value = ReadAloudState.PLAYING
        playCurrent()
    }

    /**
     * 决定本次朗读走 HttpTTS 还是系统 TTS (KP2-D P0-9 新增)。
     *
     * 读 [ttsEngineConfigProvider] 取配置 (Book.ttsEngine 优先, 否则 AppConfig.ttsEngine),
     * isNumeric → 用 [httpTtsConfigLoader] 查 DAO 拿 [HttpTTS] → 用 [httpTtsPlayerFactory] 造 player。
     * 任何环节失败 (配置缺失/DAO 查不到/工厂未注册) 都降级走系统 TTS。
     *
     * @return true 表示走 HttpTTS, false 表示走系统 TTS
     */
    private fun resolveEngine(): Boolean {
        // 释放上次 HttpTTS player (避免状态残留, 与 app 端 HttpReadAloudService 重建 player 对齐)
        releaseHttpTtsPlayer()
        httpTtsConfig = null

        val ttsEngine = ttsEngineConfigProvider()?.takeIf { it.isNotBlank() } ?: return false
        // isNumeric 判断 (替代 StringUtils.isNumeric, 不引入 apache commons 依赖)
        if (ttsEngine.any { !it.isDigit() }) return false

        val id = ttsEngine.toLong()
        val config = runCatching {
            runBlocking { httpTtsConfigLoader(id) }
        }.getOrNull() ?: return false
        if (config == null) return false

        val player = httpTtsPlayerFactory(config) ?: return false

        httpTtsConfig = config
        httpTtsPlayer = player
        // HttpTTS speakSpeed (Int, 对标原版 AppConfig.ttsSpeechRate); runCatching 兜底未注册场景
        httpTtsSpeechRate = runCatching { AppConfigProviders.get().ttsSpeechRate }.getOrDefault(0)
        return true
    }

    /**
     * 暂停朗读。
     *
     * 桌面命令行 TTS (SAPI/espeak/say) 无真暂停能力, 实际等于 stop;
     * 各平台 actual 若支持真暂停 (如 Android TextToSpeech), 由引擎自行实现。
     *
     * 暂停后 [resume] 从当前段落起点重读 (而非中断位置)。
     */
    fun pause() {
        if (_state.value != ReadAloudState.PLAYING) return
        if (useHttpTts) {
            // HttpTTS 路径: 真暂停 (各平台 actual 支持)
            httpTtsPlayer?.pause()
        } else {
            engineProvider()?.pause()
            // 桌面命令行 TTS 不支持真暂停, 这里调 stop 让后台朗读线程退出
            engineProvider()?.stop()
        }
        _state.value = ReadAloudState.PAUSED
    }

    /**
     * 恢复朗读。
     *
     * 从当前段落起点重读 (与 [pause] 配对, 桌面端简化为重读当前段)。
     */
    fun resume() {
        if (_state.value != ReadAloudState.PAUSED) return
        if (useHttpTts) {
            // HttpTTS 路径: 直接 play (player 持有当前 url, 不重新求值)
            _state.value = ReadAloudState.PLAYING
            httpTtsPlayer?.play()
        } else {
            // KP2-D P1: 恢复时同步语速到引擎 (用户在 pause 期间拖了滑杆, resume 后生效新速度)
            engineProvider()?.speechRate = _speechRate.value
            _state.value = ReadAloudState.PLAYING
            playCurrent()
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
     * 设置朗读语速 (KP2-D P1 新增)。
     *
     * UI 滑杆拖动时调用, 实时同步到引擎; 朗读中下一段 speak 即生效新速度。
     *
     * - 限制范围 0.5f ~ 2.0f (与桌面端 SAPI/espeak/say 速度参数范围对齐)
     * - 同步写入 [SystemTtsEngine.speechRate] (引擎 var 属性, 立即生效)
     * - 更新 [_speechRate] StateFlow, 触发 UI 滑杆重组
     *
     * @param rate 目标语速倍率 (会被 coerceIn 到 0.5..2.0)
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        _speechRate.value = clamped
        // 同步到当前引擎实例 (朗读中下一段 speak 即生效, 无需等 start/resume)
        engineProvider()?.speechRate = clamped
    }

    /**
     * 恢复上次持久化的朗读章节索引 (KP2-D P1 新增)。
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
        if (_state.value == ReadAloudState.IDLE) return
        stopEngine()
        synchronized(queueLock) {
            if (!queue.stepNextOrEnd()) {
                // 末段, 切下一章
                nextChapter()
                return
            }
        }
        playCurrent()
    }

    /**
     * 跳到上一段并朗读。
     *
     * 已是首段时触发 [prevChapter] (从上一章末段续读)。
     */
    fun prevParagraph() {
        if (_state.value == ReadAloudState.IDLE) return
        stopEngine()
        synchronized(queueLock) {
            if (queue.nowSpeak <= 0) {
                // 首段, 切上一章 (navigator 自行决定是否跳到末段)
                prevChapter()
                return
            }
            queue.retreatToPrevSpeakable()
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
        if (cur + 1 >= navigator.chapterCount) {
            // 已是末章
            stopEngine()
            _state.value = ReadAloudState.COMPLETED
            return
        }
        // 通知 navigator 切章 (会触发 viewModel.moveToNextChapter → loadChapter)
        navigator.moveToNextChapter()
        // 续读下一章 (start 会同步 queue 与状态)
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
        navigator.moveToPrevChapter()
        start(cur - 1)
    }

    // region 内部实现

    /**
     * 停止当前播放引擎 (HttpTTS 或系统 TTS), 不清队列 (切段/切章前调用)。
     */
    private fun stopEngine() {
        if (useHttpTts) {
            httpTtsPlayer?.stop()
        } else {
            engineProvider()?.stop()
        }
    }

    /**
     * 播放当前段落 (调用引擎 speak 或 HttpTTS setUrl+prepare, 立即返回)。
     *
     * 朗读完毕后由 [progressListener.onDone] (系统 TTS) 或
     * [HttpTtsPlayerListener.onEndOfMedia] (HttpTTS) 推进。
     */
    private fun playCurrent() {
        val text: String = synchronized(queueLock) {
            val t = queue.contentList.getOrNull(queue.nowSpeak) ?: return
            _paragraphIndex.value = queue.nowSpeak
            _currentText.value = t
            t
        }
        if (useHttpTts) {
            playHttpTtsCurrent(text)
        } else {
            val engine = engineProvider() ?: run {
                _lastError.value = "未注册 TTS 引擎"
                _state.value = ReadAloudState.ERROR
                return
            }
            // utteranceId 用段下标字符串 (与 app 端 AppConst.APP_TAG + i 简化版一致)
            engine.speak(text, _paragraphIndex.value.toString())
        }
    }

    /**
     * HttpTTS 路径播放当前段 (KP2-D P0-9 新增)。
     *
     * 对标 [io.legado.app.help.tts.ReadAloudController.playHttpTts]:
     * [AnalyzeUrlFactories.create] 求值源 url 模板 (注入 speakText/speakSpeed 变量,
     * 含源级 headers/cookie) 得到 url+headers → setUrl → prepare, onReady 后经
     * [httpTtsProgressListener] 触发 play。
     *
     * POST/body 型源首版接受降级 (只支持 GET 流式, 由各平台 actual 兜底)。
     */
    private fun playHttpTtsCurrent(text: String) {
        val player = httpTtsPlayer ?: run {
            _lastError.value = "未注册 HttpTTS 播放器"
            _state.value = ReadAloudState.ERROR
            return
        }
        val config = httpTtsConfig ?: run {
            _lastError.value = "未设置 HttpTTS 源配置"
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
            player.setUrl(analyzeUrl.url, analyzeUrl.headerMap)
            player.prepare()
        }.onFailure {
            _lastError.value = "HttpTTS url 求值失败: ${it.message}"
            _state.value = ReadAloudState.ERROR
        }
    }

    /**
     * 引擎朗读完一段后触发推进。
     *
     * 在引擎后台线程触发, 需注意与 [stop] / [nextParagraph] 的竞态:
     * - 用 [state] 校验: 仅 PLAYING 时推进
     * - 用 [queue] 锁保护段级状态
     */
    private fun onParagraphDone() {
        if (_state.value != ReadAloudState.PLAYING) return
        synchronized(queueLock) {
            if (!queue.stepNextOrEnd()) {
                // 本章节末段已朗读完 → 切下一章并续读
                _state.value = _state.value // 保持 PLAYING 状态切到下一章
                // 通知主线程切章 (在 synchronized 块外调, 避免 navigator 回调持锁)
            } else {
                // 还有段落, 朗读下一段
                playCurrent()
                return@synchronized
            }
        }
        // 走到这里说明本章节已朗读完, 触发切章
        nextChapter()
    }

    /**
     * 内部停止: 停引擎 + (可选) 清队列状态。
     *
     * @param clearState true=完全清空 (调用方 stop); false=保留状态供切章续读
     */
    private fun stopInternal(clearState: Boolean) {
        stopEngine()
        if (clearState) {
            releaseHttpTtsPlayer()
            synchronized(queueLock) {
                queue.contentList = emptyList()
                queue.nowSpeak = 0
                queue.readAloudNumber = 0
                queue.paragraphStartPos = 0
            }
            _chapterIndex.value = -1
            _paragraphIndex.value = -1
            _currentText.value = ""
        }
    }

    /**
     * 释放 HttpTTS 播放器资源 (KP2-D P0-9 新增)。
     *
     * stop + release + 清空字段; 切章 (start 重建) 与退出 ReaderScreen (stop) 时调用。
     */
    private fun releaseHttpTtsPlayer() {
        httpTtsPlayer?.let {
            it.stop()
            it.release()
        }
        httpTtsPlayer = null
        httpTtsConfig = null
    }
    // endregion

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
interface ReadAloudChapterNavigator {

    /** 章节总数。 */
    val chapterCount: Int

    /**
     * 加载指定章节正文并按 \n 切段, 返回非空段落列表。
     *
     * 实现方应:
     * 1. 优先查本地缓存 ([io.legado.app.help.book.BookStorageProviders])
     * 2. 缓存未命中时联网拉取 ([io.legado.app.model.webBook.WebBook.getContentAwait])
     * 3. 用 [ReadAloudQueue.splitParagraphs] 切段
     *
     * 返回空列表表示章节内容不可用 (调用方会显示错误)。
     */
    fun loadChapterParagraphs(chapterIndex: Int): List<String>

    /** 切到指定章节 (触发 ViewModel.loadChapter, 排版 + 联网拉取)。 */
    fun moveToChapter(chapterIndex: Int)

    /** 切到下一章 (调用 viewModel.moveToNextChapter)。 */
    fun moveToNextChapter()

    /** 切到上一章 (调用 viewModel.moveToPrevChapter)。 */
    fun moveToPrevChapter()
}
