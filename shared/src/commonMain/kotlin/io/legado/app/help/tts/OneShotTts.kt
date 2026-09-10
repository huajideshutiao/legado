package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.splitNotBlank
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** 一次性朗读单段文本（选中文字朗读、RSS 朗读）。 */
class OneShotTts {

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
            TtsEngineProvider.get()?.isSpeaking == true

    fun setStateListener(listener: (playing: Boolean) -> Unit) {
        stateListener = listener
    }

    fun speak(text: String) {
        val engine = TtsEngineProvider.get() ?: run {
            Toasters.get().toast("当前平台暂不支持：朗读")
            return
        }
        val segments = text.splitNotBlank("\n").toList()
        if (segments.isEmpty()) return

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

    fun stop() {
        var notifyStopped = false
        synchronized(engineOperationLock) {
            val stopped = synchronized(sessionLock) {
                generation++
                session.also { session = null }
            }
            if (stopped != null) {
                disposeRun(stopped, stopEngine = true)
                notifyStopped = true
            }
        }
        if (notifyStopped) stateListener?.invoke(false)
    }

    /** 初始化成功后发首段；队列平台在同一轮中一次性追加其余段。 */
    private fun startReadyRun(engine: SystemTtsEngine, runGeneration: Long) {
        var notifyStopped = false
        var failedId: String? = null
        var failure: Throwable? = null
        synchronized(engineOperationLock) {
            val current = currentRun(runGeneration, engine) ?: return@synchronized
            try {
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

    private companion object {
        const val TAG_PREFIX = "legado_tts"
        private var instanceCount = 0

        fun nextTagPrefix(): String = "$TAG_PREFIX${++instanceCount}_"
    }
}
