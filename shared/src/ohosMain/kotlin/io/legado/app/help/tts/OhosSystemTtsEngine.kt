package io.legado.app.help.tts

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable

/**
 * 鸿蒙端 [SystemTtsEngine] 真实实现: 通过 napi 桥接 @ohos.textToSpeech。
 *
 * # 实现方式: tsfn 命令 + @CName 事件回调 (与 [OhosHttpTtsPlayer] media 桥模式一致)
 *
 * ## 命令链 (Kotlin → ArkTS, fire-and-forget via tsfn)
 * - init: 发 "createEngine" 命令, ArkTS 创建 textToSpeech 引擎
 * - speak: 发 "speak" 命令 (含 text/utteranceId/rate), ArkTS 调 engine.speak()
 * - pause/resume/stop/shutdown: 发同名命令, ArkTS 转发 engine 对应方法
 *
 * ## 事件链 (ArkTS → Kotlin, via @CName legado_tts_event)
 * - onStart(utteranceId) → [TtsProgressListener.onStart]
 * - onComplete(utteranceId) → speaking=false; [TtsProgressListener.onDone]
 * - onStop(utteranceId) → speaking=false; [TtsProgressListener.onDone]
 * - onError(utteranceId) → speaking=false; [TtsProgressListener.onError]
 *
 * # 降级策略 (桥接未就绪时)
 * [OhosNativeBridge.isTtsBridgeReady] 返回 false (tsfn 未注入) 时, speak 直接回调
 * [TtsProgressListener.onError] 置 ERROR 停止推进, 让用户可感知 "TTS 不可用"
 * (不再立即 onDone: 那会让整章被秒速静默"读完")。
 *
 * # 参考
 * - napi 桥接模式参考 [OhosHttpTtsPlayer] (media tsfn + MediaEventListener)
 * - 桌面端 [io.legado.desktop.tts.DesktopSystemTtsEngine]
 */
class OhosSystemTtsEngine : SystemTtsEngine, OhosNativeBridge.TtsEventListener {

    /** 引擎是否就绪 (桥接未就绪时 false, 如实上报; 真实引擎 createEngine 异步不影响该语义)。 */
    @Volatile private var ready: Boolean = true

    /** 是否正在朗读 (speak 后置 true, onComplete/onStop/onError 后置 false)。 */
    @Volatile private var speaking: Boolean = false

    /** 是否暂停中。 */
    @Volatile private var paused: Boolean = false

    /** 语速倍率 (1.0 = 正常), 随 speak 命令发送给 ArkTS。 */
    @Volatile private var rateMultiplier: Float = 1.0f

    /** 朗读进度回调 (由 [ReadAloudControllerShared] 注入)。 */
    @Volatile private var listenerField: TtsProgressListener? = null

    /** 是否已 shutdown, shutdown 后所有操作 no-op。 */
    @Volatile private var shutdown: Boolean = false

    init {
        OhosNativeBridge.setTtsEventListener(this)
        // 桥接就绪时尝试 createEngine; 未就绪则跳过 (tsfn 为 null 时 sendTtsCommand 本就是 no-op),
        // 不缓存就绪状态, 后续方法每次运行时检查
        if (OhosNativeBridge.isTtsBridgeReady()) {
            OhosNativeBridge.sendTtsCommand(action = "createEngine", lang = "zh-CN", rate = rateMultiplier)
            println("[ohos-stts] init: bridge ready, createEngine sent")
        } else {
            println("[ohos-stts] init: bridge not ready, speak will report error")
        }
    }

    // ===== SystemTtsEngine contract =====

    override val isReady: Boolean
        get() = ready

    override val isSpeaking: Boolean
        get() = speaking

    override val isPaused: Boolean
        get() = paused

    override var speechRate: Float
        get() = rateMultiplier
        set(value) {
            // 与 DesktopSystemTtsEngine 一致: 限制在 0.5x ~ 2.0x
            rateMultiplier = value.coerceIn(0.5f, 2.0f)
        }

    override var progressListener: TtsProgressListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== speak / enqueue =====

    /**
     * 立即播放 (清空已有队列)。
     *
     * 桥接就绪: 发 "speak" 命令, 设 speaking=true, 等 ArkTS onComplete/onStop 回调再触发 onDone。
     * 降级: 上报 onError 置 ERROR 停止推进 (不 onDone, 避免整章被秒速静默"读完")。
     */
    override fun speak(text: String, utteranceId: String) {
        if (shutdown) return
        if (OhosNativeBridge.isTtsBridgeReady()) {
            speaking = true
            paused = false
            OhosNativeBridge.sendTtsCommand(
                action = "speak",
                text = text,
                utteranceId = utteranceId,
                rate = rateMultiplier,
            )
            // onDone 由 ArkTS onComplete/onStop 回调触发, 不在此处立即触发
        } else {
            // 降级: napi 桥未就绪无法出声, 走错误上报通道让用户可感知
            speaking = false
            paused = false
            println("[ohos-stts] speak: tts bridge not ready, report error. utteranceId=$utteranceId")
            listenerField?.onError(utteranceId, ERROR_BRIDGE_NOT_READY)
        }
    }

    /**
     * 追加到队列尾部。
     *
     * 与 [OhosHttpTtsPlayer] / DesktopSystemTtsEngine 一致: ReadAloudControllerShared
     * 通过 onDone 串行驱动段级推进, 不会并发 enqueue, 简化为 speak。
     */
    override fun enqueue(text: String, utteranceId: String) {
        speak(text, utteranceId)
    }

    // ===== pause / resume / stop / shutdown =====

    /** 暂停朗读。桥接就绪时发 "pause" 命令。 */
    override fun pause() {
        if (shutdown) return
        paused = true
        if (OhosNativeBridge.isTtsBridgeReady()) {
            OhosNativeBridge.sendTtsCommand(action = "pause")
        } else {
            println("[ohos-stts] pause (placeholder)")
        }
    }

    /** 恢复朗读。桥接就绪时发 "resume" 命令。 */
    override fun resume() {
        if (shutdown) return
        paused = false
        if (OhosNativeBridge.isTtsBridgeReady()) {
            OhosNativeBridge.sendTtsCommand(action = "resume")
        } else {
            println("[ohos-stts] resume (placeholder)")
        }
    }

    /** 停止当前朗读并清空状态 (保留引擎实例, 后续可再 speak)。 */
    override fun stop() {
        if (shutdown) return
        if (OhosNativeBridge.isTtsBridgeReady()) {
            OhosNativeBridge.sendTtsCommand(action = "stop")
        } else {
            println("[ohos-stts] stop (placeholder)")
        }
        speaking = false
        paused = false
    }

    /** 释放引擎资源。桥接就绪时发 "shutdown" 命令并注销事件监听。 */
    override fun shutdown() {
        if (shutdown) return
        shutdown = true
        OhosNativeBridge.shutdownTtsIfListener(this)
        speaking = false
        paused = false
    }

    // ===== OhosNativeBridge.TtsEventListener =====

    /**
     * 接收 ArkTS TTS 事件 (通过 @CName legado_tts_event → OhosNativeBridge.onTtsEvent 推送)。
     * 解析事件 JSON, 更新 speaking 状态 + 转发给 [TtsProgressListener]。
     */
    override fun onTtsEvent(eventJson: String) {
        if (shutdown) return
        val event = runCatching {
            KS_JSON.decodeFromString(TtsEvent.serializer(), eventJson)
        }.getOrNull() ?: return

        when (event.event) {
            "onStart" -> {
                event.utteranceId?.let { listenerField?.onStart(it) }
            }
            "onComplete" -> {
                speaking = false
                event.utteranceId?.let { listenerField?.onDone(it) }
            }
            "onStop" -> {
                speaking = false
                event.utteranceId?.let { listenerField?.onDone(it) }
            }
            "onError" -> {
                speaking = false
                event.utteranceId?.let { listenerField?.onError(it, event.errorCode ?: 0) }
            }
        }
    }

    // ===== 桥接 JSON 数据类 (与 ArkTS 侧协议对齐) =====

    /** TTS 事件 (ArkTS → Kotlin, via @CName legado_tts_event)。 */
    @Serializable
    private data class TtsEvent(
        val event: String,
        val utteranceId: String? = null,
        val message: String? = null,
        val errorCode: Int? = null,
    )

    companion object {
        /** 降级模式错误码: napi 桥未就绪 (对齐 Android TextToSpeech.ERROR = -1 语义)。 */
        private const val ERROR_BRIDGE_NOT_READY = -1
    }
}

/**
 * 鸿蒙宿主启动早期注册 [SystemTtsEngine] 的便捷函数。
 *
 * 在 `registerOhosJsEngines()` 之后调用 (顺序紧跟 JsEngines, 与桌面端 `Main.kt` 中
 * `TtsEngineProvider.register(DesktopSystemTtsEngine())` 位置一致),
 * 见 [io.legado.app.help.config.OhosProviderRegistry]。
 */
private val ttsRegistrationLock = SynchronizedObject()

fun registerOhosSystemTtsEngine() {
    synchronized(ttsRegistrationLock) {
        if (TtsEngineProvider.get() is OhosSystemTtsEngine) return
        TtsEngineProvider.register(OhosSystemTtsEngine())
    }
}
