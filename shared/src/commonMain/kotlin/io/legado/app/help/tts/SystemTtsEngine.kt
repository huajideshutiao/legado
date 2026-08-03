package io.legado.app.help.tts

/**
 * 系统 TTS 引擎抽象接口（KMP 版）。
 *
 * 各平台 actual 实现:
 * - app actual: Android `TextToSpeech` 封装（见 `app/.../help/tts/TextToSpeechEngine.kt`）
 * - desktop actual: Windows SAPI / PowerShell, Linux espeak, macOS say
 * - ios actual: AVSpeechSynthesizer
 * - ohos actual: 鸿蒙 @ohos.textToSpeech
 *
 * 设计原则:
 * - 仅描述"播一段文本"所需的最小能力,不暴露平台特定类型
 * - 同步/异步初始化由各 actual 内部处理,对外只暴露 [isReady]
 * - 进度回调统一通过 [TtsProgressListener],避免平台 listener 类型泄漏
 */
interface SystemTtsEngine {

    /** 引擎是否就绪（异步初始化完成后为 true）。 */
    val isReady: Boolean

    /** 是否正在朗读。 */
    val isSpeaking: Boolean

    /**
     * 是否暂停中。
     *
     * 配合 [pause] / [resume] 状态机, 让 [ReadAloudControllerShared]
     * 能区分"真停止"与"暂停恢复"。
     */
    val isPaused: Boolean

    /** 语速倍率,1.0 = 正常。 */
    var speechRate: Float

    /** 朗读进度回调,可在任意时刻赋值。 */
    var progressListener: TtsProgressListener?

    /**
     * 显式初始化引擎。
     *
     * 用于引擎需要异步 init 的平台 (如 Android TextToSpeech 需等待
     * onInit 回调)。桌面端 SAPI 在首次 speak 时按需 init, 此方法可 no-op。
     *
     * 默认空实现, 各 actual 按需重写。
     *
     * @param onReady 初始化完成的回调 (可在任意线程触发), 为 null 表示同步初始化
     */
    fun init(onReady: (() -> Unit)? = null) {
        // 默认空实现: 引擎在首次 speak 时按需 init
        onReady?.invoke()
    }

    /** 立即播放（清空已有队列）。 */
    fun speak(text: String, utteranceId: String)

    /** 追加到队列尾部。 */
    fun enqueue(text: String, utteranceId: String)

    /**
     * 暂停当前朗读 (保留朗读位置)。
     *
     * 与 [stop] 区分 —— pause 后可 [resume] 从中断处继续,
     * stop 则清空状态。
     *
     * 默认空实现: 桌面 SAPI / Linux espeak / macOS say 等命令行驱动无法暂停,
     * 实际仅能 stop 后从段落起点重播, 由各 actual 按平台能力重写。
     */
    fun pause() {
        // 默认空实现: 不支持暂停的引擎 no-op
    }

    /**
     * 恢复暂停后的朗读。
     *
     * 默认空实现, 与 [pause] 配对。
     */
    fun resume() {
        // 默认空实现: 不支持暂停的引擎 no-op
    }

    /** 停止当前朗读但保留引擎实例。 */
    fun stop()

    /** 释放引擎资源,之后不可再用。 */
    fun shutdown()

    /**
     * 合成文本到内存 Buffer (不播放)。
     *
     * 用于预渲染 / 离线 TTS / HttpTTS 上传等场景。
     * 返回 PCM/WAV 字节数据, 调用方可写入文件或交给播放器。
     *
     * 默认返回 null: 表示该平台不支持合成到 Buffer, 调用方应降级为直接 [speak]。
     * 各 actual 按平台能力重写 (如 Windows SAPI 可 SetOutputToWaveFile 后读回)。
     *
     * @param text 待合成文本
     * @param utteranceId 任务标识, 用于在 [TtsProgressListener] 中关联
     * @return PCM/WAV 字节数据; null 表示不支持
     */
    fun synthesizeToBuffer(text: String, utteranceId: String): ByteArray? {
        // 默认 null: 不支持合成到 Buffer
        return null
    }
}

/**
 * TTS 朗读进度回调（KMP 版,对标 Android `UtteranceProgressListener`）。
 *
 * 各 actual 负责把平台 listener 适配为此接口回调。
 */
interface TtsProgressListener {

    fun onStart(utteranceId: String)

    fun onDone(utteranceId: String)

    fun onError(utteranceId: String, errorCode: Int)

    fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int)
}
