package io.legado.app.help.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * Android TextToSpeech 引擎封装。
 *
 * 负责:
 * - 延迟构造 TextToSpeech 实例(首次 [ensureReady] 调用时才创建)
 * - 指定引擎名(为空表示系统默认)
 * - 把 onInit 异步信号包装成 [ensureReady] 回调
 * - 转发 [TextToSpeech.speak] 的 QUEUE_FLUSH / QUEUE_ADD 模式
 *
 * **不**负责:断句/分段/章节跟踪/音频焦点/通知。这些是调用方业务。
 *
 * 设计目标是同时服务两个场景:
 * 1. [io.legado.app.help.TTS] — 单段文本快速播报(选词朗读 / RSS 朗读)
 * 2. [io.legado.app.service.TTSReadAloudService] — 章节级朗读
 */
class TextToSpeechEngine(private val engineName: String? = null) {

    @Volatile
    var isReady: Boolean = false
        private set

    val isSpeaking: Boolean get() = tts?.isSpeaking ?: false

    /** init 失败时回调,默认弹 toast */
    var onInitFailed: () -> Unit = { appCtx.toastOnUi(R.string.tts_init_failed) }

    /** Utterance 进度回调,需要在 [ensureReady] 之前或之后设置均可 */
    var progressListener: UtteranceProgressListener? = null
        set(value) {
            field = value
            tts?.setOnUtteranceProgressListener(value)
        }

    private var tts: TextToSpeech? = null
    private var pendingOnReady: (() -> Unit)? = null

    /**
     * 确保引擎已初始化。已就绪时同步执行 [block];否则触发异步初始化,初始化成功后执行 [block]。
     *
     * 在初始化过程中重复调用,只保留最后一个 [block]。
     */
    @Synchronized
    fun ensureReady(block: () -> Unit) {
        if (isReady) {
            block()
            return
        }
        pendingOnReady = block
        if (tts != null) return // 初始化中
        val listener = TextToSpeech.OnInitListener { status -> onInit(status) }
        tts = if (engineName.isNullOrBlank()) {
            TextToSpeech(appCtx, listener)
        } else {
            TextToSpeech(appCtx, listener, engineName)
        }
    }

    @Synchronized
    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            pendingOnReady = null
            onInitFailed()
            return
        }
        progressListener?.let { tts?.setOnUtteranceProgressListener(it) }
        isReady = true
        val block = pendingOnReady
        pendingOnReady = null
        block?.invoke()
    }

    /** 立即播放(清空已有队列)。 */
    fun speak(text: String, utteranceId: String? = null): Int {
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            ?: TextToSpeech.ERROR
    }

    /** 追加到队列尾部。 */
    fun enqueue(text: String, utteranceId: String? = null): Int {
        return tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            ?: TextToSpeech.ERROR
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun stop() {
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isReady = false
        pendingOnReady = null
    }
}
