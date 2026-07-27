package io.legado.app.help.tts

/**
 * 朗读控制器（KMP 版,替代 app 端 `BaseReadAloudService` 的协调层职责）。
 *
 * 统一管理系统 TTS 和 HttpTTS 两路播放,管理朗读队列、睡眠定时、段落推进。
 * 不绑定平台 Service: 桌面端用单例 + 协程, app 端 actual 可包装 Service 生命周期。
 *
 * 设计原则:
 * - 本类只做"段级"协调（队列推进 + 选择哪路 TTS + 状态广播）, 不做页/章跟踪
 * - 章节切换由调用方在 [ReadAloudCallback.onComplete] / onParagraphChanged 后驱动
 * - HttpTTS 走在线流时, URL 拼装由调用方注入（依赖具体 HttpTTS 配置, 留 actual 补充）
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

    /** true = 用 HttpTTS, false = 用系统 TTS。 */
    private var useHttpTts: Boolean = false

    /** 当前是否暂停中（用于 resume 判断）。 */
    private var paused: Boolean = false

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
     * 播放当前段落。
     *
     * 任务伪代码原写 `queue.nowSpeak` 当文本用是错的: nowSpeak 是 Int 下标。
     * 正确做法: 从 contentList 取当前段。
     */
    private fun playCurrent() {
        val text = queue.contentList.getOrNull(queue.nowSpeak) ?: return
        callback?.onParagraphChanged(queue.nowSpeak)
        callback?.onSpeakStart(text)
        if (useHttpTts && httpTts != null) {
            // 走 HttpTTS: 需先通过 HttpTtsRequest 拼装 URL（依赖具体 HttpTTS 配置, 留 actual 补充）
            // 实际拼装由调用方在 setUrl 之前完成, 这里只触发播放
            httpTts.play()
        } else {
            // 走系统 TTS: utteranceId 用段下标的字符串形式
            systemTts?.speak(text, queue.nowSpeak.toString())
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
