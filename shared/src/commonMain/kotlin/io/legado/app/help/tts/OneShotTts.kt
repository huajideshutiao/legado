package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.splitNotBlank

/**
 * 一次性朗读单段文本 (选中文字朗读、RSS 朗读), 下沉自 app 端原 `io.legado.app.help.TTS`。
 *
 * 与 [io.legado.app.service.ReadAloudControllerShared] 的区别: 不进前台服务、不接管
 * MediaSession 与通知, 只按 `\n` 切段丢进引擎队列, 并把 onStart/onDone 上报给调用方切菜单按钮。
 *
 * 引擎是 [TtsEngineProvider] 注册的进程级单例, 多数端与章节朗读共用同一个, 故这里只 [stop]
 * 不 shutdown; "一分钟无朗读释放底层实例" 由各端引擎自己按私有程度决定 (Android 适配器内做)。
 */
class OneShotTts {

    private var stateListener: ((playing: Boolean) -> Unit)? = null
    private var listenerAttached = false

    /** 当前是否在朗读。 */
    val isSpeaking: Boolean get() = TtsEngineProvider.get()?.isSpeaking == true

    /** 朗读状态变化回调 (true=开始, false=结束)。 */
    fun setStateListener(listener: (playing: Boolean) -> Unit) {
        stateListener = listener
    }

    /** 立即朗读: 先 flush 清队列, 再按 `\n` 逐段 enqueue (与原版 emitPending 一致)。 */
    fun speak(text: String) {
        val engine = TtsEngineProvider.get() ?: run {
            Toasters.get().toast("当前平台暂不支持：朗读")
            return
        }
        attachListener(engine)
        runCatching {
            engine.init {
                engine.speak("", "$TAG_PREFIX-flush")
                text.splitNotBlank("\n").forEachIndexed { i, segment ->
                    engine.enqueue(segment, "$TAG_PREFIX$i")
                }
            }
        }.onFailure {
            AppLog.put("tts朗读出错", it)
            Toasters.get().toast(it.message ?: "tts朗读出错")
        }
    }

    fun stop() {
        TtsEngineProvider.get()?.stop()
        stateListener?.invoke(false)
    }

    /**
     * 接上引擎的进度回调。引擎是共享单例, 覆盖 progressListener 会顶掉阅读朗读自己的监听,
     * 故只认自己发出的 utteranceId, 并把其余 id 转回原监听。
     */
    private fun attachListener(engine: SystemTtsEngine) {
        if (listenerAttached) return
        listenerAttached = true
        val previous = engine.progressListener
        engine.progressListener = object : TtsProgressListener {
            override fun onStart(utteranceId: String) {
                if (utteranceId.startsWith(TAG_PREFIX)) stateListener?.invoke(true)
                else previous?.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId.startsWith(TAG_PREFIX)) stateListener?.invoke(false)
                else previous?.onDone(utteranceId)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId.startsWith(TAG_PREFIX)) stateListener?.invoke(false)
                else previous?.onError(utteranceId, errorCode)
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                if (!utteranceId.startsWith(TAG_PREFIX)) {
                    previous?.onRangeStart(utteranceId, start, end, frame)
                }
            }
        }
    }

    private companion object {
        const val TAG_PREFIX = "legado_tts"
    }
}
