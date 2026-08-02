package io.legado.desktop.tts

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMLateBindingObject
import com.sun.jna.platform.win32.COM.IDispatch
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.OaIdl
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.OleAuto
import com.sun.jna.platform.win32.Variant
import com.sun.jna.platform.win32.Variant.VARIANT
import com.sun.jna.platform.win32.WinDef
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows 系统 TTS 后端: JNA 直调 SAPI 自动化接口 (`SAPI.SpVoice` / ISpeechVoice)。
 *
 * 相比原先每句起一个 powershell 进程, 这里全程复用一个 COM 对象: 无进程启动开销、
 * 能真暂停、能用 Status.InputWordPosition 拿词边界进度。
 *
 * SAPI 的 SpVoice 是 `[local]` 对象, 不能跨 apartment 调用, 所以所有 COM 调用都串行
 * 派发到本类自己的 [comThread] 上执行。
 */
internal class WindowsSapiTtsBackend : DesktopTtsBackend {

    override val id: String = "sapi"
    override val supportsPause: Boolean = true
    override val supportsWordProgress: Boolean = true

    /** COM 调用队列, 每个任务都在 [comThread] 上跑。 */
    private val tasks = ArrayBlockingQueue<Runnable>(64)

    private val alive = AtomicBoolean(true)

    /** SpVoice 包装, 只允许在 [comThread] 上访问。 */
    private var voice: SpVoice? = null

    /** 初始化结果, null 表示 COM 创建失败 (无 SAPI)。 */
    @Volatile private var initFailed: Boolean = false

    @Volatile private var voiceCache: List<DesktopTtsVoice>? = null

    @Volatile private var selectedVoiceId: String? = null

    /** 启动时的默认音色 token, 供 selectVoice(null) 还原。 */
    @Volatile private var defaultVoiceId: String? = null

    override val currentVoiceId: String? get() = selectedVoiceId

    /** 当前朗读代号, 每次 speak 自增; 轮询线程用它判断自己是否已过期。 */
    private val generation = AtomicLong(0)

    private val comThread = Thread({ comLoop() }, "sapi-tts-com").apply {
        isDaemon = true
        start()
    }

    private val readyLatch = CountDownLatch(1)

    private fun comLoop() {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
        try {
            voice = runCatching { SpVoice() }.getOrElse {
                initFailed = true
                null
            }
            defaultVoiceId = voice?.let { sp -> runCatching { sp.currentVoiceToken() }.getOrNull() }
            readyLatch.countDown()
            while (alive.get()) {
                val task = tasks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                runCatching { task.run() }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            readyLatch.countDown()
            runCatching { voice?.release() }
            voice = null
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    /** 把任务派到 COM 线程并等结果; 失败或超时返回 null。 */
    private fun <T> onCom(timeoutMs: Long = 5000, block: (SpVoice) -> T): T? {
        if (!alive.get()) return null
        readyLatch.await(5, TimeUnit.SECONDS)
        if (initFailed) return null
        var result: T? = null
        val done = CountDownLatch(1)
        val ok = tasks.offer(Runnable {
            try {
                voice?.let { result = runCatching { block(it) }.getOrNull() }
            } finally {
                done.countDown()
            }
        })
        if (!ok) return null
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result
    }

    /** 不等结果地派发 (用于 stop/pause 这类要求低延迟的路径)。 */
    private fun postCom(block: (SpVoice) -> Unit) {
        if (!alive.get()) return
        tasks.offer(Runnable { voice?.let { runCatching { block(it) } } })
    }

    /** 探测: COM 能创建出来就算可用。 */
    // 最长阻塞 5s, 只应在后台线程调 (DesktopSystemTtsEngine 启动时预热, 不落 EDT)
    fun isAvailable(): Boolean {
        readyLatch.await(5, TimeUnit.SECONDS)
        return !initFailed && alive.get()
    }

    override fun voices(): List<DesktopTtsVoice> {
        voiceCache?.let { return it }
        val list = onCom { it.enumVoices() } ?: emptyList()
        voiceCache = list
        return list
    }

    override fun selectVoice(voiceId: String?): Boolean {
        // null 要真的切回系统默认音色, 所以用 init 时记下的默认 token
        val target = voiceId ?: defaultVoiceId ?: return false
        val applied = onCom { it.selectVoiceById(target) } ?: false
        if (applied) selectedVoiceId = voiceId
        return applied
    }

    override fun speak(text: String, rate: Float, utteranceId: String, listener: TtsBackendListener) {
        val gen = generation.incrementAndGet()
        postCom { sp ->
            if (generation.get() != gen) return@postCom
            // 对照原版 Android TTS: 段间串行推进用 QUEUE_ADD 追加队列, 只有主动跳段才 flush。
            // 这里同样不在 speak 里带 PURGEBEFORESPEAK —— 实测带 PURGE 时 SAPI 每次 speak
            // 都复用同一个 stream 号, Status.CurrentStreamNumber 在新段开读前短暂停留在上一段
            // 的 DONE 值, 新段的轮询线程会把"还没开始读"误判成"已读完"并提前 onDone,
            // 段被瞬间 purge 跳过 (现象: 只听到章节标题, 正文全部被静默消费)。
            // 需要打断当前段的路径 (stop / nextParagraph / prevParagraph / 切章重开) 都先经
            // [stop] 显式 purge, 串行推进的上一段已 DONE, 新段自然排在队尾且 stream 号递增。
            // IS_NOT_XML 避免正文里的尖括号被当标记
            sp.setRate(rateToSapi(rate))
            listener.onStart(utteranceId)
            val flags = SPF_ASYNC or SPF_IS_NOT_XML
            val stream = runCatching { sp.speak(text, flags) }.getOrElse {
                listener.onError(utteranceId, ERROR_SPEAK_FAILED)
                return@postCom
            }
            startProgressPoll(gen, stream, text.length, utteranceId, listener)
        }
    }

    /**
     * 轮询 Status 推进词边界并侦测朗读结束。
     *
     * SAPI 自动化接口没有回调事件可用 (事件走 ISpNotifySource, 需要窗口消息或原始 vtable),
     * 所以用 50ms 轮询: 对跟读高亮足够, 每次只读几个整型属性开销很低。
     *
     * RunningState 实测三态: 2=朗读中, 0=已暂停, 1=读完。必须判 DONE 位而非"非朗读中",
     * 否则暂停会被误判成读完, 上层直接跳去下一段。
     */
    private fun startProgressPoll(
        gen: Long,
        streamNumber: Int,
        textLength: Int,
        utteranceId: String,
        listener: TtsBackendListener,
    ) {
        Thread({
            var lastStart = -1
            // 本段是否已被观察到进入"朗读中"状态。实测 SAPI 段末有 IS_SPEAKING|DONE 同时置位
            // 的过渡态 (RunningState=3), 且段间切换时上一段的 DONE 残留可能被新段轮询读到 ——
            // 必须等本段真正开始朗读后才接受 DONE, 且 DONE 时须已离开朗读中状态, 否则段落会
            // 被提前判完 (表现: 第一段标题读完, 其余段落全部被瞬间跳过)。
            var startedSpeaking = false
            while (alive.get() && generation.get() == gen) {
                val st = onCom(timeoutMs = 2000) { it.status() } ?: break
                if (generation.get() != gen) return@Thread
                // 本段已被后来的 speak 顶掉 (stop/跳段 purge 或新段 stream 接管)
                if (st.currentStream > streamNumber) break
                // currentStream 追上本段才说明 SAPI 已经开始读它
                if (st.currentStream == streamNumber) {
                    if ((st.running and SPRS_IS_SPEAKING) != 0) {
                        startedSpeaking = true
                    }
                    if (st.wordLength > 0 && st.wordPos != lastStart) {
                        lastStart = st.wordPos
                        val end = (st.wordPos + st.wordLength).coerceAtMost(textLength)
                        listener.onWord(utteranceId, st.wordPos, end)
                    }
                    if (startedSpeaking && (st.running and SPRS_DONE) != 0 &&
                        (st.running and SPRS_IS_SPEAKING) == 0
                    ) {
                        if (generation.get() == gen) listener.onDone(utteranceId)
                        return@Thread
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "sapi-tts-poll-$utteranceId").apply {
            isDaemon = true
            start()
        }
    }

    override fun pause(): Boolean {
        postCom { it.pause() }
        return true
    }

    override fun resume(): Boolean {
        postCom { it.resume() }
        return true
    }

    override fun stop() {
        // 先让轮询线程失效, 再 purge, 保证不会漏出 onDone
        generation.incrementAndGet()
        postCom { it.purge() }
    }

    override fun shutdown() {
        if (!alive.compareAndSet(true, false)) return
        generation.incrementAndGet()
        tasks.offer(Runnable { voice?.let { runCatching { it.purge() } } })
        comThread.interrupt()
    }

    /**
     * 倍率 → SAPI Rate。
     *
     * SAPI Rate 是指数刻度 (实测本机 Rate 3≈1.42x, 6≈1.96x, 10≈3.03x, -6≈0.53x),
     * 所以按 `rate = log2(倍率) * 10 / log2(3.03)` 反解, 而非线性映射。
     */
    private fun rateToSapi(multiplier: Float): Int {
        val m = multiplier.coerceIn(0.1f, 4.0f)
        if (m == 1.0f) return 0
        val steps = (Math.log(m.toDouble()) / Math.log(2.0)) * (10.0 / (Math.log(3.03) / Math.log(2.0)))
        return Math.round(steps).toInt().coerceIn(-10, 10)
    }

    /** Status 快照 (在 COM 线程读出, 跨线程传值)。 */
    private class StatusSnapshot(
        val running: Int,
        val wordPos: Int,
        val wordLength: Int,
        val currentStream: Int,
    )

    /**
     * `SAPI.SpVoice` 的 IDispatch 晚绑定包装。
     */
    private class SpVoice : COMLateBindingObject("SAPI.SpVoice", false) {

        fun speak(text: String, flags: Int): Int =
            invoke("Speak", VARIANT(text), VARIANT(flags))?.intValue() ?: -1

        fun setRate(rate: Int) = setProperty("Rate", rate)

        /** 当前 Voice 的 token id。 */
        fun currentVoiceToken(): String? {
            val cur = Wrapped(getAutomationProperty("Voice") ?: return null)
            return try {
                cur.str("Id")
            } finally {
                runCatching { cur.release() }
            }
        }

        fun pause() = invokeNoReply("Pause")

        fun resume() = invokeNoReply("Resume")

        /** 空文本 + PURGEBEFORESPEAK 是 SAPI 的标准取消手法。 */
        fun purge() {
            invoke("Speak", VARIANT(""), VARIANT(SPF_ASYNC or SPF_PURGEBEFORESPEAK))
        }

        fun status(): StatusSnapshot {
            val st = getAutomationProperty("Status")
            val w = Wrapped(st)
            return try {
                StatusSnapshot(
                    running = w.int("RunningState"),
                    wordPos = w.int("InputWordPosition"),
                    wordLength = w.int("InputWordLength"),
                    currentStream = w.int("CurrentStreamNumber"),
                )
            } finally {
                runCatching { w.release() }
            }
        }

        fun enumVoices(): List<DesktopTtsVoice> {
            val col = Wrapped(getAutomationProperty("GetVoices"))
            return try {
                val n = col.int("Count")
                (0 until n).mapNotNull { i ->
                    val tok = Wrapped(col.item(i) ?: return@mapNotNull null)
                    try {
                        val tokenId = runCatching { tok.str("Id") }.getOrNull() ?: return@mapNotNull null
                        val desc = runCatching { tok.call("GetDescription")?.stringValue() }.getOrNull()
                        DesktopTtsVoice(
                            id = tokenId,
                            name = desc ?: tokenId.substringAfterLast('\\'),
                            locale = runCatching {
                                tok.call("GetAttribute", VARIANT("Language"))?.stringValue()
                            }.getOrNull()?.let { lcidToLocale(it) },
                        )
                    } finally {
                        runCatching { tok.release() }
                    }
                }
            } finally {
                runCatching { col.release() }
            }
        }

        fun selectVoiceById(voiceId: String): Boolean {
            val col = Wrapped(getAutomationProperty("GetVoices"))
            return try {
                val n = col.int("Count")
                for (i in 0 until n) {
                    val tok = Wrapped(col.item(i) ?: continue)
                    val matched = runCatching { tok.str("Id") }.getOrNull() == voiceId
                    if (matched) {
                        val hr = putRefVoice(tok.dispatch)
                        runCatching { tok.release() }
                        return hr
                    }
                    runCatching { tok.release() }
                }
                false
            } finally {
                runCatching { col.release() }
            }
        }

        /**
         * `Voice` 是 by-reference 属性, 必须用 DISPATCH_PROPERTYPUTREF 且带
         * DISPID_PROPERTYPUT(-3) 具名实参, 普通 setProperty 会报 0x80020003。
         */
        private fun putRefVoice(token: IDispatch): Boolean {
            val disp = getIDispatch() ?: return false
            val dispId = OaIdl.DISPIDByReference()
            val lcid = WinDef.LCID(0x0409L)
            val hr = disp.GetIDsOfNames(
                Guid.REFIID(Guid.IID_NULL),
                arrayOf(WString("Voice")),
                1,
                lcid,
                dispId,
            )
            if (hr.toInt() != 0) return false
            val arg = VARIANT().apply { setValue(Variant.VT_DISPATCH, token) }
            val params = OleAuto.DISPPARAMS.ByReference().apply {
                setArgs(arrayOf(arg))
                setRgdispidNamedArgs(arrayOf(OaIdl.DISPID(DISPID_PROPERTYPUT)))
                cArgs = WinDef.UINT(1L)
                cNamedArgs = WinDef.UINT(1L)
                write()
            }
            val invoked = disp.Invoke(
                dispId.value,
                Guid.REFIID(Guid.IID_NULL),
                lcid,
                WinDef.WORD(OleAuto.DISPATCH_PROPERTYPUTREF.toLong()),
                params,
                null,
                null,
                null,
            )
            return invoked.toInt() == 0
        }
    }

    /** 任意 IDispatch 的读属性/调方法包装 (Status / voice token / 集合共用)。 */
    private class Wrapped(val dispatch: IDispatch) : COMLateBindingObject(dispatch) {
        fun int(name: String): Int = getIntProperty(name)
        fun str(name: String): String = getStringProperty(name)
        fun item(index: Int): IDispatch? = getAutomationProperty("Item", VARIANT(index))
        fun call(name: String, vararg args: VARIANT): VARIANT? =
            if (args.isEmpty()) invoke(name) else invoke(name, arrayOf(*args))
    }

    private companion object {
        const val SPF_ASYNC = 0x0001
        const val SPF_PURGEBEFORESPEAK = 0x0002
        const val SPF_IS_NOT_XML = 0x0010

        /** RunningState 位: 1=读完, 2=朗读中; 暂停时两位都不置 (实测为 0);
         * 段末过渡时可能 3=朗读中|读完 同时置位。 */
        const val SPRS_DONE = 0x1
        const val SPRS_IS_SPEAKING = 0x2
        const val DISPID_PROPERTYPUT = -3
        const val POLL_INTERVAL_MS = 50L
        const val ERROR_SPEAK_FAILED = -3

        /** SAPI 的 Language 属性是十六进制 LCID 字符串, 只映射常见几个, 其余原样带出。 */
        fun lcidToLocale(hex: String): String? {
            val lcid = hex.trim().substringBefore(';').toIntOrNull(16) ?: return null
            return when (lcid) {
                0x804 -> "zh-CN"
                0x404 -> "zh-TW"
                0xC04 -> "zh-HK"
                0x409 -> "en-US"
                0x809 -> "en-GB"
                0x411 -> "ja-JP"
                0x412 -> "ko-KR"
                else -> null
            }
        }
    }
}
