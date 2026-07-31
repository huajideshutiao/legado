package io.legado.desktop.tts

/**
 * 一个可选音色。
 *
 * @param id 平台内稳定的音色标识 (Windows 为 SAPI token 注册表路径, mac/linux 为音色名)
 * @param name 展示名
 * @param locale 语言标记 (如 `zh-CN`), 无法判定时为 null
 */
data class DesktopTtsVoice(
    val id: String,
    val name: String,
    val locale: String? = null,
)

/**
 * 后端朗读事件, 每次 [DesktopTtsBackend.speak] 必须恰好触发一次终止事件 (done/error)。
 */
internal interface TtsBackendListener {
    fun onStart(utteranceId: String)

    /** 词边界进度, 偏移基于传入 speak 的文本; 仅 Windows SAPI 提供。 */
    fun onWord(utteranceId: String, start: Int, end: Int)

    fun onDone(utteranceId: String)

    fun onError(utteranceId: String, code: Int)
}

/**
 * 平台 TTS 后端: 探测可用性 → 枚举音色 → 异步朗读。
 *
 * [speak] 立即返回, 朗读在后端自有线程进行。
 */
internal interface DesktopTtsBackend {

    /** 后端标识, 用于日志与错误提示。 */
    val id: String

    /** 是否支持真暂停 (Windows SAPI 支持; 命令行后端只能停止后重读)。 */
    val supportsPause: Boolean

    /** 是否提供词边界进度。 */
    val supportsWordProgress: Boolean

    /** 可选音色列表, 结果由实现缓存。 */
    fun voices(): List<DesktopTtsVoice>

    /** 选择音色, null 表示恢复系统默认; 返回是否生效。 */
    fun selectVoice(voiceId: String?): Boolean

    /** 当前音色 id, 未显式选择时为 null (即系统默认)。 */
    val currentVoiceId: String?

    /**
     * 异步朗读一段文本, 内部会先中止上一段。
     *
     * @param rate 语速倍率 (1.0 = 正常), 由实现映射到平台参数
     */
    fun speak(text: String, rate: Float, utteranceId: String, listener: TtsBackendListener)

    /** 暂停, 返回 false 表示本后端不支持 (调用方应降级为 stop)。 */
    fun pause(): Boolean

    /** 恢复, 返回 false 表示本后端不支持。 */
    fun resume(): Boolean

    /** 立即停止, 不触发终止回调。 */
    fun stop()

    /** 释放资源。 */
    fun shutdown()
}
