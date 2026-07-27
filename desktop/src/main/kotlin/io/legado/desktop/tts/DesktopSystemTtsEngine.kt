package io.legado.desktop.tts

import io.legado.app.help.tts.SystemTtsEngine
import io.legado.app.help.tts.TtsProgressListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [SystemTtsEngine] 的桌面端 (JVM) actual 实现 (KP2-D P0-7)。
 *
 * # 跨平台策略
 *
 * 桌面端无统一系统 TTS API, 按操作系统调用各自的原生命令:
 *
 * | 平台    | 命令                                | 速度参数       | 速度范围      |
 * |---------|-------------------------------------|----------------|---------------|
 * | Windows | PowerShell + System.Speech.Synthesis | -Rate N        | -10 ~ 10      |
 * | Linux   | espeak                              | -s WPM         | 80 ~ 350      |
 * | macOS   | say                                 | -r WPM         | 100 ~ 400     |
 *
 * 通过 [osName] 自动选择当前平台的 [EngineBackend], 调用方无需关心平台细节。
 *
 * # 实现策略
 *
 * - [speak] / [enqueue] 启动 daemon 后台线程调 ProcessBuilder 同步执行原生命令
 * - 朗读完成后调 [TtsProgressListener.onDone] (与 app 端 UtteranceProgressListener.onDone 对应)
 * - [stop] 通过 Process.destroy() 杀进程, 中断后台线程
 * - [pause] / [resume] 桌面命令行 TTS 无真暂停能力, pause 等价于 stop (清队列) +
 *   保留 utteranceId; resume 由调用方 [ReadAloudControllerShared] 重新调 speak 当前段
 * - [synthesizeToBuffer] 桌面端 SAPI 可通过 SetOutputToWaveStream 拿到 WAV,
 *   但跨平台实现复杂度高, P0-7 暂返回 null (默认实现)
 *
 * # 线程模型
 *
 * - speak/enqueue 在调用方线程返回 (非阻塞)
 * - 实际朗读在 `DesktopSystemTtsEngine-worker-N` daemon 线程执行
 * - listener.onDone/onError 在工作线程触发, 调用方如需 Compose 主线程访问需自行 invokeLater
 * - stop/shutdown 通过 [activeProcess] AtomicReference 引用 Process, destroy 后线程自然退出
 *
 * # 文本转义
 *
 * - Windows PowerShell: 用单引号包裹文本, 内部单引号转义为 `''`
 * - Linux/macOS shell: 用单引号包裹, 内部单引号转义为 `'\''`
 *
 * 这避免了双引号场景下变量展开 (`$`) / 命令替换 (`` ` ``) 注入风险。
 *
 * # 参考实现
 *
 * - app 端: `app/.../help/tts/TextToSpeechEngine.kt` 用 android TextToSpeech
 * - 跨平台 fallback 思路: Mozilla Firefox / Calibre 等 JVM 桌面应用的 TTS 调用方式
 *
 * KP2-D P0-7 新增。
 */
class DesktopSystemTtsEngine : SystemTtsEngine {

    /**
     * 当前操作系统检测 (一次确定, 后续不变)。
     *
     * 用 `os.name` 系统属性判定:
     * - 包含 "win" → Windows
     * - 包含 "mac" / "darwin" → macOS
     * - 包含 "nux" / "nix" / "aix" → Linux/Unix
     */
    private val osName: String = System.getProperty("os.name", "").lowercase()

    /** 当前平台的命令后端 (按 [osName] 选择, 在 init 时确定)。 */
    private val backend: EngineBackend = when {
        osName.contains("win") -> WindowsSapiBackend
        osName.contains("mac") || osName.contains("darwin") -> MacSayBackend
        osName.contains("nux") || osName.contains("nix") || osName.contains("aix") -> LinuxEspeakBackend
        else -> NoOpBackend
    }

    /** 引擎是否就绪 (桌面命令行引擎无异步 init, 始终 true)。 */
    @Volatile private var ready: Boolean = true

    /** 是否正在朗读 (speak 启动后台线程后置 true, onDone/stop 后置 false)。 */
    @Volatile private var speaking: Boolean = false

    /** 是否暂停中 (桌面命令行 TTS 无真暂停, pause 等价于 stop + 标记)。 */
    @Volatile private var paused: Boolean = false

    /**
     * 语速倍率 (1.0 = 正常), 桌面端映射为各平台原生速度参数。
     *
     * KP2-D P1: 范围调整为 0.5x ~ 2.0x (与 ReadAloudControllerShared.speechRate 对齐,
     * 适配滑杆 UI step 0.1, 避免平台命令速度参数越界)。
     */
    @Volatile private var rateMultiplier: Float = 1.0f

    /** 朗读进度回调 (由 [ReadAloudControllerShared] 注入)。 */
    @Volatile private var listenerField: TtsProgressListener? = null

    /**
     * 当前活跃子进程 (用于 stop 时 destroy)。
     *
     * AtomicReference 保证多线程可见性: speak 在工作线程 set, stop 在调用方线程 get+destroy。
     */
    private val activeProcess: AtomicReference<Process?> = AtomicReference(null)

    /** 是否已 shutdown, shutdown 后所有操作 no-op。 */
    private val shutdown: AtomicBoolean = AtomicBoolean(false)

    /** 是否已 stop (用于让 speak 工作线程主动退出)。 */
    private val stopped: AtomicBoolean = AtomicBoolean(false)

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
            // KP2-D P1: 限制在 0.5x ~ 2.0x (与 ReadAloudControllerShared.speechRate 范围对齐)
            rateMultiplier = value.coerceIn(0.5f, 2.0f)
        }

    override var progressListener: TtsProgressListener?
        get() = listenerField
        set(value) {
            listenerField = value
        }

    // ===== speak / enqueue =====

    /**
     * 立即播放 (清空已有队列 → 启动后台朗读线程)。
     *
     * 实现: 先 stop 当前进程, 再启动新线程; utteranceId 由调用方传入 (本类不维护队列)。
     */
    override fun speak(text: String, utteranceId: String) {
        if (shutdown.get()) return
        // 清空已有朗读
        stopCurrentProcess()
        stopped.set(false)
        paused = false
        startSpeakThread(text, utteranceId)
    }

    /**
     * 追加到队列尾部。
     *
     * 桌面命令行 TTS 无真队列概念, 简化: 若正在朗读, 等其完成后再朗读本段;
     * 若不在朗读, 直接启动朗读。多个 enqueue 会顺序触发 (上一个 onDone 后下一个开始)。
     *
     * 简化: 实际等价于 speak (因 ReadAloudControllerShared 通过 onDone 串行驱动段级推进,
     * 不会同时多段 enqueue)。
     */
    override fun enqueue(text: String, utteranceId: String) {
        // 等价于 speak: 由 ReadAloudControllerShared.onParagraphDone 串行触发, 不会并发
        speak(text, utteranceId)
    }

    // ===== pause / resume / stop / shutdown =====

    /**
     * 暂停朗读。
     *
     * 桌面命令行 TTS 无真暂停能力, 这里:
     * 1. 标记 paused = true
     * 2. 杀当前朗读进程 (后台朗读线程会自然退出)
     *
     * 调用方 [ReadAloudControllerShared.resume] 后会重新 speak 当前段 (从头重读)。
     */
    override fun pause() {
        if (shutdown.get()) return
        paused = true
        stopCurrentProcess()
    }

    /**
     * 恢复朗读。
     *
     * 桌面端不主动重启朗读 —— 调用方 [ReadAloudControllerShared.resume] 会重新调 speak
     * 当前段落。这里仅清 paused 标记。
     */
    override fun resume() {
        if (shutdown.get()) return
        paused = false
    }

    /**
     * 停止当前朗读并清空状态 (保留引擎实例, 后续可再 speak)。
     */
    override fun stop() {
        if (shutdown.get()) return
        stopped.set(true)
        paused = false
        speaking = false
        stopCurrentProcess()
    }

    /**
     * 释放引擎资源。
     *
     * 桌面命令行引擎无持久资源, 仅停止当前进程 + 标记 shutdown, 后续所有调用 no-op。
     */
    override fun shutdown() {
        if (!shutdown.compareAndSet(false, true)) return
        stopped.set(true)
        speaking = false
        paused = false
        stopCurrentProcess()
    }

    // ===== 内部实现 =====

    /**
     * 启动后台朗读线程。
     *
     * 线程内:
     * 1. 触发 listener.onStart(utteranceId)
     * 2. 构造命令 + 启动 ProcessBuilder
     * 3. 等待进程退出 (同步阻塞, 但在工作线程不阻塞调用方)
     * 4. 退出码 0 → listener.onDone(utteranceId); 非零 → listener.onError
     *
     * @param text 待朗读文本 (已转义前)
     * @param utteranceId 任务标识
     */
    private fun startSpeakThread(text: String, utteranceId: String) {
        speaking = true
        Thread({
            try {
                runSpeak(text, utteranceId)
            } catch (e: Exception) {
                if (!shutdown.get()) {
                    listenerField?.onError(utteranceId, -1)
                }
            } finally {
                speaking = false
            }
        }, "DesktopSystemTtsEngine-worker-${utteranceId}").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 实际执行原生命令朗读 (工作线程同步)。
     */
    private fun runSpeak(text: String, utteranceId: String) {
        // 状态校验: stop 后不再执行
        if (stopped.get()) return

        // 触发 onStart (与 Android UtteranceProgressListener.onStart 对齐)
        listenerField?.onStart(utteranceId)

        // 构造命令
        val command = backend.buildCommand(text, rateMultiplier)
        if (command.isEmpty()) {
            // NoOpBackend 或命令构造失败, 直接当作完成 (避免阻塞调用方)
            listenerField?.onDone(utteranceId)
            return
        }

        // 启动子进程
        val process: Process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true) // 合并 stderr 到 stdout, 避免子进程 stderr 缓冲塞满阻塞
                .start()
        } catch (e: IOException) {
            listenerField?.onError(utteranceId, ERROR_LAUNCH_FAILED)
            return
        }
        activeProcess.set(process)

        // 朗读期间持续读 stdout 防止缓冲塞满 (PowerShell / say 在长文本时可能输出诊断信息)
        // 简化: 用 drain 子线程读 stdout, 主线程等进程退出
        val drainThread = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { /* 丢弃, 仅消耗缓冲 */ }
                }
            } catch (_: IOException) {
                // 进程被 destroy 后读会抛 IOException, 忽略
            }
        }, "DesktopSystemTtsEngine-drain-${utteranceId}").apply {
            isDaemon = true
            start()
        }

        // 等待进程退出 (stop 会 destroy 进程, 这里 wait 返回)
        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            // 工作线程被 interrupt, 视为 stop
            Thread.currentThread().interrupt()
            stopped.set(true)
            -1
        }

        // 清理引用
        activeProcess.compareAndSet(process, null)

        // 等 drain 线程退出 (避免线程泄漏)
        try {
            drainThread.join(500) // 最多等 500ms, 防止卡死
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 状态判定: stop 后不触发 onDone (避免误推进段落)
        if (stopped.get()) return

        // 进程退出码判定
        if (exitCode == 0) {
            listenerField?.onDone(utteranceId)
        } else {
            // 非零退出码视为错误 (进程被 destroy 时 waitFor 仍返回非零)
            // 仅在未被 stop 的情况下报错, 否则会误触发 listener.onError
            if (!stopped.get()) {
                listenerField?.onError(utteranceId, exitCode)
            }
        }
    }

    /**
     * 停止当前活跃子进程 (若有)。
     *
     * 调用 Process.destroy() 杀进程; 在 Windows 上仅杀 powershell.exe 父进程,
     * 子进程 (SAPI 内部) 通常随父进程退出, 但偶有残留 (已知限制, 后续可引入 taskkill /T)。
     */
    private fun stopCurrentProcess() {
        val proc = activeProcess.getAndSet(null) ?: return
        if (proc.isAlive) {
            try {
                proc.destroy()
            } catch (_: Exception) {
                // destroy 失败不报错 (避免 stop 路径抛异常)
            }
            // 强杀兜底: destroy 后 100ms 仍存活则 destroyForcibly
            try {
                if (!proc.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    // ===== 引擎后端抽象 =====

    /**
     * 平台特定的命令构造后端。
     */
    private interface EngineBackend {

        /**
         * 构造朗读命令。
         *
         * @param text 待朗读文本 (调用方应保证非空)
         * @param rateMultiplier 语速倍率 (1.0 = 正常)
         * @return 命令列表 (List<String> 传给 ProcessBuilder); 空列表表示该平台不支持
         */
        fun buildCommand(text: String, rateMultiplier: Float): List<String>
    }

    /**
     * Windows SAPI 后端: 通过 PowerShell 调用 System.Speech.Synthesis.SpeechSynthesizer。
     *
     * 命令示例:
     * ```
     * powershell -NoProfile -Command "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Rate = 0; $s.Speak('text')"
     * ```
     *
     * KP2-D P1 速度映射: rateMultiplier ∈ [0.5, 2.0] → SAPI Rate ∈ [-5, 10]
     * 公式: `Rate = (speechRate - 1.0) * 10` (SAPI Rate 范围 [-10, 10], 0 = 正常)
     * - 0.5x → -5 (慢)
     * - 1.0x → 0 (正常)
     * - 2.0x → 10 (快)
     */
    private object WindowsSapiBackend : EngineBackend {

        override fun buildCommand(text: String, rateMultiplier: Float): List<String> {
            // SAPI Rate: (speechRate - 1.0) * 10, 范围 [-10, 10]
            // 0.5 → -5, 1.0 → 0, 2.0 → 10
            val sapiRate = ((rateMultiplier - 1.0f) * 10f).toInt().coerceIn(-10, 10)
            val escaped = escapePowerShellSingleQuote(text)
            val psScript = buildString {
                append("Add-Type -AssemblyName System.Speech; ")
                append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
                append("\$s.Rate = $sapiRate; ")
                append("\$s.Speak('")
                append(escaped)
                append("')")
            }
            return listOf(
                "powershell",
                "-NoProfile", // 跳过用户 PS profile 加载, 加速启动
                "-NonInteractive",
                "-Command",
                psScript,
            )
        }

        /**
         * PowerShell 单引号字符串转义: 内部单引号 → `''`。
         *
         * 不用双引号字符串, 避免变量展开 (`$`) / 命令替换 (`` ` ``) 注入风险。
         */
        private fun escapePowerShellSingleQuote(text: String): String {
            return text.replace("'", "''")
        }
    }

    /**
     * Linux espeak 后端。
     *
     * 命令: `espeak -s <WPM> '<text>'`
     *
     * KP2-D P1 速度映射: rateMultiplier ∈ [0.5, 2.0] → WPM ∈ [180, 480]
     * 公式: `WPM = 80 + speechRate * 200` (espeak WPM 范围 [80, 350], 默认 175)
     * - 0.5x → 180 WPM
     * - 1.0x → 280 WPM
     * - 2.0x → 480 → 350 (coerce 上限)
     */
    private object LinuxEspeakBackend : EngineBackend {

        override fun buildCommand(text: String, rateMultiplier: Float): List<String> {
            // WPM = 80 + speechRate * 200, 限制在 espeak 有效范围 [80, 350]
            val wpm = (80 + rateMultiplier * 200f).toInt().coerceIn(80, 350)
            val escaped = escapeShellSingleQuote(text)
            return listOf("espeak", "-s", wpm.toString(), escaped)
        }

        /** POSIX shell 单引号转义: 内部单引号 → `'\''`。 */
        private fun escapeShellSingleQuote(text: String): String {
            return "'" + text.replace("'", "'\\''") + "'"
        }
    }

    /**
     * macOS say 后端。
     *
     * 命令: `say -r <WPM> '<text>'`
     *
     * KP2-D P1 速度映射: rateMultiplier ∈ [0.5, 2.0] → WPM ∈ [100, 400]
     * 公式: `WPM = speechRate * 200` (say 默认约 175-200 WPM, 范围 [50, 500])
     * - 0.5x → 100 WPM
     * - 1.0x → 200 WPM
     * - 2.0x → 400 WPM
     */
    private object MacSayBackend : EngineBackend {

        override fun buildCommand(text: String, rateMultiplier: Float): List<String> {
            // WPM = speechRate * 200, 限制在 say 有效范围 [50, 500]
            val wpm = (rateMultiplier * 200f).toInt().coerceIn(50, 500)
            val escaped = escapeShellSingleQuote(text)
            return listOf("say", "-r", wpm.toString(), escaped)
        }

        /** POSIX shell 单引号转义 (与 LinuxEspeakBackend 共用逻辑)。 */
        private fun escapeShellSingleQuote(text: String): String {
            return "'" + text.replace("'", "'\\''") + "'"
        }
    }

    /**
     * 不支持的后端 (其他 Unix / 未知平台)。
     *
     * buildCommand 返回空列表, runSpeak 直接当作朗读完成 (避免阻塞调用方)。
     */
    private object NoOpBackend : EngineBackend {
        override fun buildCommand(text: String, rateMultiplier: Float): List<String> = emptyList()
    }

    private companion object {
        /** 进程启动失败的错误码 (与 app 端 TextToSpeech.ERROR 对应值为 -1)。 */
        private const val ERROR_LAUNCH_FAILED = -2
    }
}
