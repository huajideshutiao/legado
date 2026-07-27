@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.tts

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.Foundation.NSRange
import platform.darwin.NSObject

/**
 * iOS 端 [SystemTtsEngine] 实现: 基于 AVFoundation [AVSpeechSynthesizer]。
 *
 * # 选型理由 (KP3 补完)
 *
 * - **系统自带**: AVSpeechSynthesizer 是 iOS/macOS 系统自带 framework, 通过 Kotlin/Native
 *   `platform.AVFAudio.*` 直接访问, 不引入任何第三方库;
 * - **官方维护**: Apple 维护, 性能稳定, 支持多语言/多声音;
 * - **对应 desktop**: desktop 用 Windows SAPI / Linux espeak / macOS say 命令行,
 *   iOS 用 AVSpeechSynthesizer API, 各平台用各自原生 TTS 能力。
 *
 * # 与 [io.legado.desktop.tts.DesktopSystemTtsEngine] 区别
 *
 * | 维度 | DesktopSystemTtsEngine (JVM) | IosSystemTtsEngine (iOS) |
 * |------|------------------------------|--------------------------|
 * | 引擎 | ProcessBuilder 调原生命令 (SAPI/espeak/say) | AVSpeechSynthesizer API |
 * | 异步 | daemon Thread 同步等进程退出 | AVSpeechSynthesizer 内部异步, delegate 回调 |
 * | 暂停 | 不支持 (stop + 标记) | 支持 (pauseSpeakingAtBoundary + continueSpeaking) |
 * | 语速 | 命令参数 (-Rate/-s/-r) | AVSpeechUtterance.rate (0.0~1.0) |
 * | 取消 | Process.destroy() | stopSpeakingAtBoundary |
 *
 * # 语速映射
 *
 * AVSpeechUtterance.rate 范围 [AVSpeechUtteranceMinimumSpeechRate (0.0),
 * AVSpeechUtteranceMaximumSpeechRate (1.0)], 默认 [AVSpeechUtteranceDefaultSpeechRate (0.5)]。
 *
 * 业务层 [SystemTtsEngine.speechRate] 范围 [0.5, 2.0] (与 [ReadAloudControllerShared] 对齐),
 * 映射公式: `avRate = speechRate * 0.5` (1.0x → 0.5 defaultRate, 2.0x → 1.0 maxRate)。
 *
 * # 调用时机
 *
 * iOS 宿主启动早期经 [io.legado.app.help.config.registerIosProviders] 调用
 * [registerIosSystemTtsEngine] 注册到 [TtsEngineProvider]。
 *
 * # 线程模型
 *
 * AVSpeechSynthesizer 内部异步执行, delegate 回调在主线程 (AVSpeechSynthesizer 文档保证);
 * speak/stop/pause/resume 可在任意线程调用 (AVSpeechSynthesizer 内部会切到主线程派发)。
 *
 * 注: 与 desktop 命令行 TTS 不同, iOS 不需要工作线程包裹 (AVSpeechSynthesizer 自身异步)。
 *
 * 模式参考 desktop `DesktopSystemTtsEngine` 实现。
 *
 * # macOS 编译验证
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:compileKotlinIosSimulatorArm64
 * ```
 */
class IosSystemTtsEngine : SystemTtsEngine {

    /** AVSpeechSynthesizer 单例 (整个引擎生命周期复用)。 */
    private val synthesizer: AVSpeechSynthesizer = AVSpeechSynthesizer()

    /** 当前朗读的 utteranceId (由 speak/enqueue 传入, delegate 回调时用)。 */
    @Volatile
    private var currentUtteranceId: String = ""

    /** 是否正在朗读 (speak 时 true, didFinish/didCancel 时 false)。 */
    @Volatile
    private var speakingState: Boolean = false

    /** 是否暂停中 (pause 时 true, resume/stop 时 false)。 */
    @Volatile
    private var pausedState: Boolean = false

    /** 语速倍率 (1.0 = 正常), 映射为 AVSpeechUtterance.rate。 */
    @Volatile
    private var rateMultiplier: Float = 1.0f

    /** 朗读进度回调 (由 [ReadAloudControllerShared] 注入)。 */
    @Volatile
    private var listenerField: TtsProgressListener? = null

    /**
     * AVSpeechSynthesizer delegate 实现, 监听朗读事件转 [TtsProgressListener]。
     *
     * Kotlin/Native 中实现 ObjC 协议用 `NSObject() + Protocol` 形式,
     * 方法签名与 AVSpeechSynthesizerDelegate 协议方法一致 (Kotlin/Native 自动映射 ObjC 方法名)。
     */
    // 类型留给编译器推断 (NSObject ∩ 协议), 显式标 NSObject 会让 delegate 赋值不过协议类型检查
    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didStartSpeechUtterance: AVSpeechUtterance
        ) {
            // 朗读开始: 触发 onStart (与 Android UtteranceProgressListener.onStart 对齐)
            listenerField?.onStart(currentUtteranceId)
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance
        ) {
            // 朗读完成: 清状态 + 触发 onDone
            speakingState = false
            pausedState = false
            listenerField?.onDone(currentUtteranceId)
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance
        ) {
            // 朗读取消 (stop 触发): 清状态 (不触发 onError, 与 desktop 行为一致, 避免 stop 路径误推进段落)
            // 仅在非主动 stop 的取消场景触发 (AVSpeechSynthesizer 自动取消极少见)
            speakingState = false
            pausedState = false
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didPauseSpeechUtterance: AVSpeechUtterance
        ) {
            // 朗读暂停: 仅更新状态 (listener 不通知, 调用方已知 pause)
            pausedState = true
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didContinueSpeechUtterance: AVSpeechUtterance
        ) {
            // 朗读恢复: 清暂停状态
            pausedState = false
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            willSpeakRangeOfSpeechString: CValue<NSRange>,
            utterance: AVSpeechUtterance
        ) {
            // 朗读进度高亮: NSRange → onRangeStart (对齐 Android onRangeStart; frame iOS 无对应, 传 0)
            // 主线程回调, 与其余 delegate 方法线程约定一致
            willSpeakRangeOfSpeechString.useContents {
                listenerField?.onRangeStart(
                    currentUtteranceId,
                    location.toInt(),
                    (location + length).toInt(),
                    0
                )
            }
        }
    }

    init {
        // 绑定 delegate (ARC 持有, synthesizer.delegate 是 weak 引用, delegate 实例需自行持有)
        synthesizer.delegate = delegate
    }

    // ===== SystemTtsEngine contract =====

    /** 引擎是否就绪 (AVSpeechSynthesizer 同步构造, 始终 true)。 */
    override val isReady: Boolean
        get() = true

    override val isSpeaking: Boolean
        get() = speakingState

    override val isPaused: Boolean
        get() = pausedState

    override var speechRate: Float
        get() = rateMultiplier
        set(value) {
            // 限制在 0.5x ~ 2.0x (与 ReadAloudControllerShared.speechRate 范围对齐)
            rateMultiplier = value.coerceIn(0.5f, 2.0f)
        }

    override var progressListener: TtsProgressListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== speak / enqueue =====

    /**
     * 立即播放 (清空已有队列 → 创建 AVSpeechUtterance → speakUtterance)。
     *
     * AVSpeechSynthesizer.speakUtterance 内部异步执行, 调用立即返回;
     * 朗读事件经 delegate 回调触发 listener.onStart / onDone。
     */
    override fun speak(text: String, utteranceId: String) {
        // 清空已有朗读 (与 desktop 行为一致)
        if (speakingState || pausedState) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        currentUtteranceId = utteranceId
        pausedState = false
        speakingState = true
        val utterance = AVSpeechUtterance(string = text).apply {
            // 语速映射: speechRate ∈ [0.5, 2.0] → avRate ∈ [0.25, 1.0]
            // 公式: avRate = speechRate * 0.5 (1.0x → 0.5 defaultRate, 2.0x → 1.0 maxRate)
            rate = (rateMultiplier * 0.5f).coerceIn(
                AVSpeechUtteranceMinimumSpeechRate.toFloat(),
                AVSpeechUtteranceMaximumSpeechRate.toFloat()
            )
        }
        synthesizer.speakUtterance(utterance)
    }

    /**
     * 追加到队列尾部。
     *
     * AVSpeechSynthesizer 内部维护 utterance 队列, 多次 speakUtterance 会顺序执行;
     * 但本引擎 P0 阶段简化为"立即播放" (与 desktop 行为一致), 由 [ReadAloudControllerShared]
     * 通过 onDone 串行驱动段级推进。
     */
    override fun enqueue(text: String, utteranceId: String) {
        // 等价于 speak: 由 ReadAloudControllerShared.onParagraphDone 串行触发, 不会并发
        speak(text, utteranceId)
    }

    // ===== pause / resume / stop / shutdown =====

    /**
     * 暂停朗读 (AVSpeechSynthesizer 支持真暂停, 比 desktop 命令行 TTS 更准确)。
     *
     * 调用 pauseSpeakingAtBoundary 后, 朗读在当前单词边界暂停;
     * [resume] 后从暂停处继续 (不重头读, 与 desktop stop + 重播 不同)。
     */
    override fun pause() {
        if (!speakingState) return
        synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        // pausedState 由 delegate.didPauseSpeechUtterance 回调设置 (主线程异步)
        // 此处先置 true, 让 isPaused 立即返回正确值 (避免 delegate 回调前的窗口期)
        pausedState = true
    }

    /**
     * 恢复暂停后的朗读 (从暂停处继续, 不重头读)。
     */
    override fun resume() {
        if (!pausedState) return
        synthesizer.continueSpeaking()
        // pausedState 由 delegate.didContinueSpeechUtterance 回调清零
        // 此处先置 false, 让 isPaused 立即返回正确值
        pausedState = false
    }

    /**
     * 停止当前朗读并清空状态 (保留引擎实例, 后续可再 speak)。
     *
     * stopSpeakingAtBoundary 会触发 delegate.didCancelSpeechUtterance,
     * 在 delegate 中清 speakingState/pausedState (不触发 onError, 与 desktop 行为一致)。
     */
    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        speakingState = false
        pausedState = false
    }

    /**
     * 释放引擎资源 (AVSpeechSynthesizer 由 ARC 管理, 此处仅停止朗读 + 清 delegate)。
     *
     * shutdown 后所有操作 no-op (与 desktop 行为一致)。
     */
    override fun shutdown() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        synthesizer.delegate = null
        speakingState = false
        pausedState = false
        listenerField = null
    }

    // ===== synthesizeToBuffer =====
    // 默认返回 null (SystemTtsEngine 接口默认实现), iOS P0 阶段不支持合成到 Buffer
    // 后续如需可用 AVAssetWriter + AVSpeechSynthesizer 写文件, 但实现复杂度高, 暂不补
}

/**
 * iOS 宿主启动早期注册 [SystemTtsEngine] 的入口。
 *
 * 模式参考 desktop `Main.kt` 中 `TtsEngineProvider.register(DesktopSystemTtsEngine())`。
 *
 * 调用时机: iOS 宿主启动早期经 [io.legado.app.help.config.registerIosProviders] 调用,
 * 在 [registerIosJsEngines] 之后 (与 desktop Main.kt 顺序一致)。
 */
fun registerIosSystemTtsEngine() {
    TtsEngineProvider.register(IosSystemTtsEngine())
}
