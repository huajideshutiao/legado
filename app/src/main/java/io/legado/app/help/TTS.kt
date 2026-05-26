package io.legado.app.help

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppLog
import io.legado.app.help.tts.TextToSpeechEngine
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 一次性朗读单段文本(选词朗读、RSS 朗读)。
 *
 * 与 [io.legado.app.service.TTSReadAloudService] 的区别:
 * - 不进入前台服务、不接管 MediaSession 与通知
 * - 一分钟内没有朗读自动释放底层 TextToSpeech 资源
 * - 仅广播 onStart / onDone 用于切换菜单按钮状态
 */
class TTS {

    private val handler by lazy { buildMainHandler() }
    private val clearRunnable = Runnable { clearTts() }

    private var pendingText: String? = null
    private var stateListener: SpeakStateListener? = null

    private val engine: TextToSpeechEngine = TextToSpeechEngine()

    init {
        engine.progressListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.removeCallbacks(clearRunnable)
                stateListener?.onStart()
            }

            override fun onDone(utteranceId: String?) {
                handler.postDelayed(clearRunnable, IDLE_TIMEOUT_MS)
                stateListener?.onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?, errorCode: Int) = Unit
        }
    }

    val isSpeaking: Boolean get() = engine.isSpeaking

    fun setSpeakStateListener(listener: SpeakStateListener) {
        stateListener = listener
    }

    @Synchronized
    fun speak(text: String) {
        handler.removeCallbacks(clearRunnable)
        pendingText = text
        engine.ensureReady { emitPending() }
    }

    fun stop() = engine.stop()

    @Synchronized
    fun clearTts() = engine.shutdown()

    private fun emitPending() {
        val text = pendingText ?: return
        runCatching {
            // 先 FLUSH 一个空串清空队列;若直接返回 ERROR 说明实例被系统释放了,重启一次
            if (engine.speak("") == TextToSpeech.ERROR) {
                clearTts()
                engine.ensureReady { emitPending() }
                return
            }
            text.splitNotBlank("\n").forEachIndexed { i, segment ->
                if (engine.enqueue(segment, "$TAG_PREFIX$i") == TextToSpeech.ERROR) {
                    AppLog.put("tts朗读出错:$text")
                }
            }
        }.onFailure {
            AppLog.put("tts朗读出错", it)
            appCtx.toastOnUi(it.localizedMessage)
        }
    }

    interface SpeakStateListener {
        fun onStart()
        fun onDone()
    }

    companion object {
        private const val TAG_PREFIX = "legado_tts"
        private const val IDLE_TIMEOUT_MS = 60_000L
    }
}
