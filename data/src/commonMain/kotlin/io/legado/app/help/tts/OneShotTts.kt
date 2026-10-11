package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.service.defaultTtsEngineConfig
import io.legado.app.utils.splitNotBlank
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 一次性朗读单段文本（选中文字朗读、RSS 朗读、朗读试听）。
 *
 * 跟随正式朗读的引擎配置（[defaultTtsEngineConfig]: 书籍优先→全局设置，对照原版
 * `ReadAloud.ttsEngine`）：配置串经 [resolveHttpTtsChannel] 判定通道（不进前台服务，
 * 与正式朗读共用同一份判定）；空白/非数字/查不到源/平台未注册在线播放器 → 系统 TTS。
 * 系统通道按正式朗读同一口径下发语速（[configuredTtsSpeechRate] / [ttsSpeechRateScale]）。
 * 试听入口 [speakWith] 强制指定引擎（null = 系统默认），不走配置串解析。
 */
class OneShotTts(
    private val ttsEngineConfigProvider: () -> String? = { defaultTtsEngineConfig() },
    private val httpTtsPlayerFactory: (HttpTTS) -> HttpTtsPlayer? = { TtsEngineProvider.getHttpTtsPlayer(it) },
    private val httpTtsConfigLoader: suspend (Long) -> HttpTTS? = { AppDbProviders.get().httpTTSDao.get(it) },
) {

    /** 配置解析需查库 (suspend)，后台解析后落到系统/在线通道；代次防止旧解析落地 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 一次性请求代次 (speak/stop 递增)：未决解析完成后比对，过期即丢弃 */
    private var requestGeneration = 0L

    /** 只保护会话状态；任何引擎调用都不得持有此锁。 */
    private val sessionLock = SynchronizedObject()

    /**
     * 串行化本实例对系统引擎的操作。每次 speak/enqueue/stop 前都在本锁内复核代次，
     * 因而迟到 init 和旧回调既不能向新轮提交，也不能 stop 新轮。
     */
    private val engineOperationLock = SynchronizedObject()
    private val tagPrefix = nextTagPrefix()

    private var stateListener: ((playing: Boolean) -> Unit)? = null
    private var generation = 0L
    private var session: RunSession? = null

    /** 在线通道状态 (httpLock 保护): 一次性播放器 + 代次，迟到回调不得触碰新轮 */
    private val httpLock = SynchronizedObject()
    private var httpPlayer: HttpTtsPlayer? = null
    private var httpGeneration = 0L

    /** 一轮朗读的全部可变状态，只能在 [sessionLock] 内访问。 */
    private class RunSession(
        val generation: Long,
        val tag: String,
        val engine: SystemTtsEngine,
        val segments: List<String>,
        val queued: Boolean,
        val listenerToken: TtsProgressListenerToken,
        var nextSegmentIndex: Int = 1,
        var remaining: Int = if (queued) segments.size else 0,
        val settledUtteranceIds: MutableSet<String> = mutableSetOf(),
    )

    val isSpeaking: Boolean
        get() = synchronized(sessionLock) { session != null } ||
            synchronized(httpLock) { httpPlayer != null } ||
            TtsEngineProvider.get()?.isSpeaking == true

    fun setStateListener(listener: (playing: Boolean) -> Unit) {
        stateListener = listener
    }

    /**
     * 一次性朗读: 跟随正式朗读的引擎配置。
     * 空白/非数字/查不到源/平台未注册在线播放器 → 系统通道 (判定与正式朗读共用
     * [resolveHttpTtsChannel])。
     */
    fun speak(text: String) {
        if (text.splitNotBlank("\n").isEmpty()) return
        resetChannels()
        val resolveGeneration = ++requestGeneration
        scope.launch {
            val channel = runCatching {
                resolveHttpTtsChannel(
                    ttsEngineConfigProvider(),
                    httpTtsConfigLoader,
                    httpTtsPlayerFactory,
                )
            }.getOrNull()
            if (resolveGeneration != requestGeneration) return@launch
            playChannel(channel, text)
        }
    }

    /** 一次性朗读: 强制指定引擎 (null = 系统默认)，不走配置串解析 (朗读试听入口)。 */
    fun speakWith(config: HttpTTS?, text: String) {
        if (text.splitNotBlank("\n").isEmpty()) return
        resetChannels()
        playChannel(openHttpTtsChannel(config, httpTtsPlayerFactory), text)
    }

    /** 通道落地: 无在线通道 (含平台未注册播放器) 与正式朗读同一分支, 回落系统通道。 */
    private fun playChannel(channel: HttpTtsChannel?, text: String) {
        if (channel != null) {
            startHttp(channel, text)
        } else {
            startSystem(text)
        }
    }

    /** 停止全部通道 (系统会话 + 在线播放器) 并作废未决解析；停播时通知状态回调。 */
    fun stop() {
        requestGeneration++
        val stoppedSystem = stopSystem()
        val stoppedHttp = stopHttp()
        if (stoppedSystem || stoppedHttp) stateListener?.invoke(false)
    }

    /** 停两通道 (不嵌套持锁)，不发状态通知 (与原停旧起新不通知的语义一致)。 */
    private fun resetChannels() {
        requestGeneration++
        stopSystem()
        stopHttp()
    }

    /** 停在线播放器; true = 确有在线轮被停。 */
    private fun stopHttp(): Boolean {
        val stopped: Boolean
        synchronized(httpLock) {
            httpGeneration++
            stopped = httpPlayer != null
            httpPlayer?.let { p ->
                httpPlayer = null
                runCatching {
                    p.stop()
                    p.release()
                }
            }
        }
        return stopped
    }

    /** 在线通道: 一次性播放器播完/出错即释放；状态回调与系统通道同语义 (起播 true/结束 false)。 */
    private fun startHttp(channel: HttpTtsChannel, text: String) {
        val player = channel.player
        val runGeneration: Long
        synchronized(httpLock) {
            httpPlayer?.let { old ->
                httpPlayer = null
                runCatching {
                    old.stop()
                    old.release()
                }
            }
            runGeneration = ++httpGeneration
            httpPlayer = player
        }
        player.listener = object : HttpTtsPlayerListener {
            override fun onReady() {
                val isCurrent = synchronized(httpLock) {
                    httpGeneration == runGeneration && httpPlayer === player
                }
                if (isCurrent) {
                    player.play()
                    stateListener?.invoke(true)
                }
            }

            override fun onEndOfMedia() {
                clearHttpRun(runGeneration, player)
                stateListener?.invoke(false)
            }

            override fun onError(message: String) {
                AppLog.put("一次性朗读出错: $message")
                Toasters.get().toast("一次性朗读出错\n$message")
                clearHttpRun(runGeneration, player)
                stateListener?.invoke(false)
            }

            override fun onBufferingUpdate(percent: Int) = Unit
        }
        runCatching {
            player.setRequest(channel.config, text, AppConfigProviders.get().ttsSpeechRate)
            player.prepare()
        }.onFailure {
            AppLog.put("一次性朗读 url 求值失败", it)
            Toasters.get().toast(it.message ?: "一次性朗读出错")
            clearHttpRun(runGeneration, player)
            stateListener?.invoke(false)
        }
    }

    /** 迟到回调防串扰: 仅当代次与实例都仍是当前在线轮才释放。 */
    private fun clearHttpRun(runGeneration: Long, player: HttpTtsPlayer) {
        synchronized(httpLock) {
            if (httpGeneration != runGeneration || httpPlayer !== player) return
            httpPlayer = null
        }
        runCatching { player.release() }
    }

    /** 系统通道: 按换行拆段提交 (队列平台一次入队, 非平台逐段推进), 语速在 init 就绪后按下发口径生效。 */
    private fun startSystem(text: String) {
        val engine = TtsEngineProvider.get() ?: run {
            Toasters.get().toast("当前平台暂不支持：朗读")
            return
        }
        val segments = text.splitNotBlank("\n").toList()

        var failure: Throwable? = null
        synchronized(engineOperationLock) {
            val old = synchronized(sessionLock) {
                generation++
                session.also { session = null }
            }
            old?.let { disposeRun(it, stopEngine = true) }

            // provider 可能在旧轮期间换过实例；新实例也必须以空队列开始。
            if (old?.engine !== engine) engine.stop()

            val runGeneration = synchronized(sessionLock) { generation }
            try {
                val listenerToken = engine.registerProgressListener(progressListener, tagPrefix)
                val created = RunSession(
                    generation = runGeneration,
                    tag = "$tagPrefix$runGeneration-",
                    engine = engine,
                    segments = segments,
                    queued = engine.supportsQueue,
                    listenerToken = listenerToken,
                )
                val installed = synchronized(sessionLock) {
                    if (generation != runGeneration || session != null) false
                    else {
                        session = created
                        true
                    }
                }
                if (!installed) {
                    engine.unregisterProgressListener(listenerToken)
                    return@synchronized
                }
                engine.init(
                    onReady = { startReadyRun(engine, runGeneration) },
                    onError = { errorCode -> onInitFailed(engine, runGeneration, errorCode) },
                )
            } catch (error: Throwable) {
                val removed = takeRun(runGeneration, engine)
                if (removed != null) disposeRun(removed, stopEngine = true)
                failure = error
            }
        }
        failure?.let {
            stateListener?.invoke(false)
            AppLog.put("tts朗读出错", it)
            Toasters.get().toast(it.message ?: "tts朗读出错")
        }
    }

    /** 停系统会话; true = 确有会话被停 (通知由调用方统一处理)。 */
    private fun stopSystem(): Boolean {
        var stopped = false
        synchronized(engineOperationLock) {
            val old = synchronized(sessionLock) {
                generation++
                session.also { session = null }
            }
            if (old != null) {
                disposeRun(old, stopEngine = true)
                stopped = true
            }
        }
        return stopped
    }

    /** 初始化成功后发首段；队列平台在同一轮中一次性追加其余段。 */
    private fun startReadyRun(engine: SystemTtsEngine, runGeneration: Long) {
        var notifyStopped = false
        var failedId: String? = null
        var failure: Throwable? = null
        synchronized(engineOperationLock) {
            val current = currentRun(runGeneration, engine) ?: return@synchronized
            try {
                // 语速: 与正式朗读同一口径 (原版 TTSReadAloudService.upSpeechRate)
                engine.speechRate = ttsSpeechRateScale(configuredTtsSpeechRate())
                val firstId = current.tag + 0
                if (!engine.speak(current.segments.first(), firstId)) {
                    failedId = firstId
                } else if (current.queued) {
                    for (index in 1 until current.segments.size) {
                        // 每次引擎操作紧邻代次复核，旧 init 永远无权碰新轮。
                        if (currentRun(runGeneration, engine) == null) return@synchronized
                        val id = current.tag + index
                        if (!engine.enqueue(current.segments[index], id)) {
                            failedId = id
                            break
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }

            if (failedId != null || failure != null) {
                // 只有成功移除的当前轮才拥有 stop 权；迟到旧轮绝不 stop 新轮。
                val removed = takeRun(runGeneration, engine)
                if (removed != null) {
                    disposeRun(removed, stopEngine = true)
                    notifyStopped = true
                }
            }
        }
        if (notifyStopped) stateListener?.invoke(false)
        failedId?.let { AppLog.put("tts朗读提交失败: utteranceId=$it") }
        failure?.let {
            AppLog.put("tts朗读出错", it)
            Toasters.get().toast(it.message ?: "tts朗读出错")
        }
    }

    private fun onInitFailed(engine: SystemTtsEngine, runGeneration: Long, errorCode: Int) {
        var notifyStopped = false
        synchronized(engineOperationLock) {
            val removed = takeRun(runGeneration, engine)
            if (removed != null) {
                disposeRun(removed, stopEngine = true)
                notifyStopped = true
            }
        }
        if (!notifyStopped) return
        stateListener?.invoke(false)
        AppLog.put("tts初始化失败: code=$errorCode")
    }

    /** 非队列平台在当前段结束后串行提交下一段；错误会终止整轮。 */
    private fun onSegmentSettled(utteranceId: String, failed: Boolean, errorCode: Int = 0) {
        var notifyStopped = false
        var failureLog: String? = null
        var thrown: Throwable? = null
        synchronized(engineOperationLock) {
            val current = synchronized(sessionLock) {
                session?.takeIf { utteranceId.startsWith(it.tag) }
            } ?: return@synchronized

            var next: Pair<String, String>? = null
            var finish = false
            synchronized(sessionLock) {
                val active = session
                if (active !== current || !active.settledUtteranceIds.add(utteranceId)) {
                    return@synchronized
                }
                if (failed) {
                    finish = true
                    failureLog = "tts朗读出错: utteranceId=$utteranceId code=$errorCode"
                } else if (active.queued) {
                    if (active.remaining > 0) active.remaining--
                    finish = active.remaining == 0
                } else if (active.nextSegmentIndex >= active.segments.size) {
                    finish = true
                } else {
                    val index = active.nextSegmentIndex++
                    next = active.segments[index] to (active.tag + index)
                }
            }

            if (finish) {
                val removed = takeRun(current.generation, current.engine)
                if (removed != null) {
                    disposeRun(removed, stopEngine = failed)
                    notifyStopped = true
                }
                return@synchronized
            }

            next?.let { (nextText, nextId) ->
                // engineOperationLock 让此复核与 speak 成为同一串行操作。
                if (currentRun(current.generation, current.engine) == null) return@let
                try {
                    if (!current.engine.speak(nextText, nextId)) {
                        failureLog = "tts朗读提交失败: utteranceId=$nextId"
                        val removed = takeRun(current.generation, current.engine)
                        if (removed != null) {
                            disposeRun(removed, stopEngine = true)
                            notifyStopped = true
                        }
                    }
                } catch (error: Throwable) {
                    thrown = error
                    val removed = takeRun(current.generation, current.engine)
                    if (removed != null) {
                        disposeRun(removed, stopEngine = true)
                        notifyStopped = true
                    }
                }
            }
        }
        failureLog?.let(AppLog::put)
        thrown?.let { AppLog.put("tts朗读出错", it) }
        if (notifyStopped) stateListener?.invoke(false)
    }

    /** 仅作状态读取，不调用引擎。 */
    private fun currentRun(runGeneration: Long, engine: SystemTtsEngine): RunSession? =
        synchronized(sessionLock) {
            session?.takeIf { it.generation == runGeneration && it.engine === engine }
        }

    /** 成功返回表示调用者取得该轮的清理权。 */
    private fun takeRun(runGeneration: Long, engine: SystemTtsEngine): RunSession? =
        synchronized(sessionLock) {
            val current = session
            if (current?.generation != runGeneration || current.engine !== engine) null
            else current.also { session = null }
        }

    /** 只能在 [engineOperationLock] 内调用，且调用时不持有 [sessionLock]。 */
    private fun disposeRun(run: RunSession, stopEngine: Boolean) {
        run.engine.unregisterProgressListener(run.listenerToken)
        if (stopEngine) run.engine.stop()
    }

    private val progressListener = object : TtsProgressListener {
        override fun onStart(utteranceId: String) {
            val isCurrent = synchronized(sessionLock) {
                session?.let { utteranceId.startsWith(it.tag) } == true
            }
            if (isCurrent) stateListener?.invoke(true)
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId.startsWith(tagPrefix)) onSegmentSettled(utteranceId, failed = false)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            if (utteranceId.startsWith(tagPrefix)) {
                onSegmentSettled(utteranceId, failed = true, errorCode = errorCode)
            }
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) = Unit
    }

    companion object {
        /**
         * 试听文本: 固定中文+英文关于页标语, 先中文后英文, 不随应用语言
         * (2026-10-12 用户拍板: 一次试听同时检验引擎中英文覆盖)。
         */
        const val TEST_TEXT = "这是一款使用 Kotlin 全新开发的开源的阅读软件，欢迎您的加入。" +
            " This is an open source reading software newly developed by Kotlin, welcome to join us."

        const val TAG_PREFIX = "legado_tts"
        private var instanceCount = 0

        fun nextTagPrefix(): String = "$TAG_PREFIX${++instanceCount}_"
    }
}
