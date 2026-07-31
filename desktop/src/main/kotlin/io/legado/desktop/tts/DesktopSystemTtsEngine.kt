package io.legado.desktop.tts

import io.legado.app.help.tts.SystemTtsEngine
import io.legado.app.help.tts.TtsProgressListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SystemTtsEngine] 的桌面端 (JVM) actual 实现。
 *
 * 按平台包装系统自带 TTS, 不引入合成器:
 *
 * | 平台    | 后端                        | 暂停 | 词边界进度 | 音色枚举 |
 * |---------|-----------------------------|------|------------|----------|
 * | Windows | SAPI COM (JNA 直调 SpVoice) | 真暂停 | 有       | 有       |
 * | macOS   | `say`                       | 停止重读 | 无     | `say -v ?` |
 * | Linux   | `spd-say` → `espeak-ng`     | 停止重读 | 无     | `-L` / `--voices` |
 *
 * 后端在启动时由后台线程预热 (见 init), 结果缓存; 探测不到任何后端时 [isReady] 为 false,
 * [unsupportedMessage] 给出安装提示。
 */
class DesktopSystemTtsEngine : SystemTtsEngine {

    private val osName: String = System.getProperty("os.name", "").lowercase()

    /** 探测只做一次, 结果 (含"没有后端") 都缓存。 */
    private val backendHolder: Lazy<DesktopTtsBackend?> = lazy { detectBackend() }

    private val backend: DesktopTtsBackend? get() = backendHolder.value

    init {
        // 后端探测会阻塞 (Windows 走 SAPI COM 初始化, 最长等 5s; Linux/mac 起探测子进程),
        // 而朗读按钮的点击路径是在 EDT 上同步调进来的, 所以启动时就在后台线程预热。
        Thread({ runCatching { backendHolder.value } }, "desktop-tts-warmup").apply {
            isDaemon = true
            start()
        }
    }

    @Volatile private var speakingField: Boolean = false

    @Volatile private var pausedField: Boolean = false

    @Volatile private var rateMultiplier: Float = 1.0f

    @Volatile private var listenerField: TtsProgressListener? = null

    /** 最后一次朗读的文本与 id, 供不支持真暂停的后端 resume 时重读。 */
    @Volatile private var lastText: String? = null

    @Volatile private var lastUtteranceId: String? = null

    private val shutdown = AtomicBoolean(false)

    // ===== SystemTtsEngine contract =====

    override val isReady: Boolean
        get() = !shutdown.get() && backend != null

    override val isSpeaking: Boolean
        get() = speakingField

    override val isPaused: Boolean
        get() = pausedField

    override var speechRate: Float
        get() = rateMultiplier
        set(value) {
            rateMultiplier = value.coerceIn(0.5f, 2.0f)
        }

    override var progressListener: TtsProgressListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    override fun speak(text: String, utteranceId: String) {
        if (shutdown.get()) return
        val engine = backend
        if (engine == null) {
            listenerField?.onError(utteranceId, ERROR_NO_BACKEND)
            return
        }
        lastText = text
        lastUtteranceId = utteranceId
        pausedField = false
        speakingField = true
        engine.speak(text, rateMultiplier, utteranceId, backendListener)
    }

    /** 上层 [ReadAloudControllerShared] 靠 onDone 串行推进段落, 不会并发入队。 */
    override fun enqueue(text: String, utteranceId: String) = speak(text, utteranceId)

    override fun pause() {
        if (shutdown.get()) return
        pausedField = true
        val engine = backend ?: return
        // 后端不支持真暂停时降级为停止, resume 时从本段开头重读
        if (!engine.pause()) {
            engine.stop()
            speakingField = false
        }
    }

    override fun resume() {
        if (shutdown.get()) return
        if (!pausedField) return
        pausedField = false
        val engine = backend ?: return
        if (engine.resume()) {
            speakingField = true
            return
        }
        // 降级路径: 重读本段
        val text = lastText ?: return
        val id = lastUtteranceId ?: return
        speakingField = true
        engine.speak(text, rateMultiplier, id, backendListener)
    }

    override fun stop() {
        if (shutdown.get()) return
        pausedField = false
        speakingField = false
        backend?.stop()
    }

    override fun shutdown() {
        if (!shutdown.compareAndSet(false, true)) return
        speakingField = false
        pausedField = false
        // 没探测过就别为了关闭去启动后端
        if (backendHolder.isInitialized()) backendHolder.value?.shutdown()
    }

    // ===== 音色 (供设置界面) =====

    /** 当前平台可选音色; 无后端或后端不支持时为空。 */
    fun availableVoices(): List<DesktopTtsVoice> = backend?.voices().orEmpty()

    /** 选择音色, null 恢复系统默认。 */
    fun selectVoice(voiceId: String?): Boolean = backend?.selectVoice(voiceId) ?: false

    /** 当前音色 id, null 表示系统默认。 */
    fun currentVoiceId(): String? = backend?.currentVoiceId

    /** 无可用后端时的安装提示, 有后端时为 null。 */
    fun unsupportedMessage(): String? {
        if (backend != null) return null
        return when {
            osName.contains("nux") || osName.contains("nix") || osName.contains("aix") ->
                "未检测到系统 TTS 后端。请安装 speech-dispatcher (推荐, 如 " +
                    "`sudo apt install speech-dispatcher`) 或 espeak-ng " +
                    "(`sudo apt install espeak-ng`), 并确认音频服务正常。"
            osName.contains("win") -> "未检测到 Windows 语音 (SAPI) 组件, 请在系统设置中安装语音包。"
            osName.contains("mac") || osName.contains("darwin") ->
                "未检测到 macOS 语音合成 (say), 请在系统设置 → 辅助功能 → 朗读内容中下载语音。"
            else -> "当前系统 ($osName) 暂不支持系统 TTS, 可改用 HttpTTS 朗读引擎。"
        }
    }

    // ===== 内部 =====

    /** 把后端事件适配到 [TtsProgressListener]; 后端保证每段恰好一次终止事件。 */
    private val backendListener = object : TtsBackendListener {
        override fun onStart(utteranceId: String) {
            speakingField = true
            listenerField?.onStart(utteranceId)
        }

        override fun onWord(utteranceId: String, start: Int, end: Int) {
            listenerField?.onRangeStart(utteranceId, start, end, 0)
        }

        override fun onDone(utteranceId: String) {
            speakingField = false
            listenerField?.onDone(utteranceId)
        }

        override fun onError(utteranceId: String, code: Int) {
            speakingField = false
            listenerField?.onError(utteranceId, code)
        }
    }

    private fun detectBackend(): DesktopTtsBackend? = runCatching {
        when {
            osName.contains("win") -> WindowsSapiTtsBackend().let { sapi ->
                if (sapi.isAvailable()) sapi else { sapi.shutdown(); null }
            }
            osName.contains("mac") || osName.contains("darwin") ->
                if (MacSayTtsBackend.isAvailable()) MacSayTtsBackend() else null
            osName.contains("nux") || osName.contains("nix") || osName.contains("aix") ->
                // speech-dispatcher 统一了各合成器接口, 优先; 没有再退 espeak-ng
                if (LinuxSpeechDispatcherBackend.isAvailable()) LinuxSpeechDispatcherBackend()
                else LinuxEspeakNgBackend.detect()
            else -> null
        }
    }.getOrNull()

    private companion object {
        /** 无可用后端 (与 app 端 TextToSpeech.ERROR = -1 区分)。 */
        const val ERROR_NO_BACKEND = -5
    }
}
