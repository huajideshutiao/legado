package io.legado.app.help.tts

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import kotlin.concurrent.Volatile

/**
 * 朗读控制器（KMP 版,替代 app 端 `BaseReadAloudService` 的协调层职责）。
 *
 * 统一管理系统 TTS 和 HttpTTS 两路播放,管理朗读队列、睡眠定时、段落推进。
 * 不绑定平台 Service: 桌面端用单例 + 协程, app 端 actual 可包装 Service 生命周期。
 *
 * 设计原则:
 * - 本类只做"段级"协调（队列推进 + 选择哪路 TTS + 状态广播）, 不做页/章跟踪
 * - 章节切换由调用方在 [ReadAloudCallback.onComplete] / onParagraphChanged 后驱动
 * - HttpTTS 路径由本类经 [AnalyzeUrlCore] 求值 [httpTtsConfig] 的 url 模板得到 url+headers,
 *   再 setUrl → prepare → (onReady) → play, 对标原版 HttpReadAloudService 请求语义
 *
 * @param systemTts 系统 TTS 引擎, 为 null 表示该平台无系统 TTS
 * @param httpTts 在线 HttpTTS 播放器, 为 null 表示不支持在线 TTS
 */
class ReadAloudController(
    private val systemTts: SystemTtsEngine?,
    private val httpTts: HttpTtsPlayer?,
) {
    /** 朗读队列, 段级状态机。 */
    val queue = ReadAloudQueue()

    /** 睡眠定时（毫秒）, null = 无定时。具体计时由调用方按平台机制驱动。 */
    var sleepTimerMillis: Long? = null

    /** 朗读状态回调。 */
    var callback: ReadAloudCallback? = null

    /** HttpTTS 源配置, useHttpTts=true 时必须注入（对标原版 ReadAloud.httpTTS）。 */
    var httpTtsConfig: HttpTTS? = null

    /** 朗读语速, 注入 url 模板 speakSpeed 变量（对标原版 AppConfig.speechRatePlay + 5）。 */
    var speechRate: Int = 0

    /** true = 用 HttpTTS, false = 用系统 TTS。 */
    private var useHttpTts: Boolean = false

    /** 当前是否暂停中（用于 resume 判断; 平台播放器回调线程可见性靠 Volatile）。 */
    @Volatile
    private var paused: Boolean = false

    init {
        // 对标原版 ExoPlayer 事件桥: STATE_READY→play / STATE_ENDED→推进 / onPlayerError→上报
        val player = httpTts
        if (player != null) {
            player.listener = object : HttpTtsPlayerListener {
                override fun onReady() {
                    if (!paused) player.play()
                }

                override fun onEndOfMedia() = onMediaEnded()

                override fun onError(message: String) {
                    this@ReadAloudController.onError(message)
                }

                override fun onBufferingUpdate(percent: Int) = Unit
            }
        }
    }

    /**
     * 开始朗读一组段落。
     *
     * @param content 段落列表（已按 \n 分段并过滤空段）
     * @param useHttpTts true 走 HttpTTS, false 走系统 TTS
     */
    fun start(content: List<String>, useHttpTts: Boolean) {
        this.useHttpTts = useHttpTts
        // ReadAloudQueue 无 setContent 方法, 直接重置四个状态字段
        queue.contentList = content
        queue.nowSpeak = 0
        queue.readAloudNumber = 0
        queue.paragraphStartPos = 0
        paused = false
        playCurrent()
    }

    /** 暂停朗读。 */
    fun pause() {
        paused = true
        if (useHttpTts && httpTts != null) {
            httpTts.pause()
        } else {
            systemTts?.stop()
        }
    }

    /** 恢复朗读。 */
    fun resume() {
        if (!paused) return
        paused = false
        if (useHttpTts && httpTts != null) {
            httpTts.play()
        } else {
            playCurrent()
        }
    }

    /** 停止朗读并清空状态。 */
    fun stop() {
        paused = false
        if (useHttpTts && httpTts != null) {
            httpTts.stop()
        } else {
            systemTts?.stop()
        }
    }

    /** 朗读下一段。 */
    fun nextParagraph() {
        queue.stepNext()
        playCurrent()
    }

    /** 朗读上一段。 */
    fun prevParagraph() {
        queue.retreatToPrevSpeakable()
        playCurrent()
    }

    /**
     * 播放当前段落: 应用起始段偏移 → 跳过不可读段 → 按路由分发。
     */
    private fun playCurrent() {
        var text = queue.contentList.getOrNull(queue.nowSpeak) ?: return
        // 起始段偏移: 仅当前段生效, stepNext 会清零（对标原版 substring(paragraphStartPos)）
        if (queue.paragraphStartPos > 0 && queue.paragraphStartPos < text.length) {
            text = text.substring(queue.paragraphStartPos)
        }
        // 纯空白/标点段跳过, 复用队列推进逻辑（对标原版 notReadAloudRegex 过滤）
        if (text.matches(AppPattern.notReadAloudRegex)) {
            if (queue.advanceToNextSpeakable()) {
                playCurrent()
            } else {
                callback?.onComplete()
            }
            return
        }
        callback?.onParagraphChanged(queue.nowSpeak)
        callback?.onSpeakStart(text)
        if (useHttpTts && httpTts != null) {
            playHttpTts(httpTts, text)
        } else {
            // 走系统 TTS: utteranceId 用段下标的字符串形式
            systemTts?.speak(text, queue.nowSpeak.toString())
        }
    }

    /**
     * HttpTTS 路径: [AnalyzeUrlCore] 求值源 url 模板（注入 speakText/speakSpeed 变量, 含源级
     * headers/cookie）得到 url+headers → setUrl → prepare, onReady 后经 listener 触发 play。
     *
     * 与原版差异: 原版先经 getSpeakStream 下载音频文件再播（POST/body 源也支持）,
     * 这里把求值后的 url 直连平台播放器, POST 型源由各平台下载路径自行兜底。
     */
    private fun playHttpTts(player: HttpTtsPlayer, text: String) {
        val config = httpTtsConfig ?: run {
            onError("未设置 HttpTTS 源配置")
            return
        }
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        runCatching {
            AnalyzeUrlFactories.create(
                config.url,
                source = config,
                readTimeout = HttpTtsRequest.READ_TIMEOUT_MS,
                variables = HttpTtsRequest.speakVariables(speakText, speechRate),
            )
        }.onSuccess { analyzeUrl ->
            player.setUrl(analyzeUrl.url, analyzeUrl.headerMap)
            player.prepare()
        }.onFailure {
            onError("HttpTTS url 求值失败: ${it.message}")
        }
    }

    /**
     * 由 HttpTTS actual 在 onEndOfMedia 时回调, 推进到下一段。
     */
    fun onMediaEnded() {
        callback?.onSpeakEnd(queue.contentList.getOrNull(queue.nowSpeak).orEmpty())
        if (queue.stepNextOrEnd()) {
            playCurrent()
        } else {
            callback?.onComplete()
        }
    }

    /**
     * 由 actual 在出错时回调。
     */
    fun onError(message: String) {
        callback?.onError(message)
    }
}

/**
 * 朗读状态回调（KMP 版,对标 app 端 ReadAloud 服务回调）。
 */
interface ReadAloudCallback {
    fun onParagraphChanged(index: Int)
    fun onSpeakStart(text: String)
    fun onSpeakEnd(text: String)
    fun onError(message: String)
    fun onComplete()
}
