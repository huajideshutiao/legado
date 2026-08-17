package io.legado.desktop.tts

import io.legado.desktop.help.DesktopCommandRunner
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 命令行 TTS 后端基类 (macOS `say` / Linux `spd-say` / `espeak-ng`)。
 *
 * 三者都在朗读完成后才退出进程, 所以"进程结束 = 本段读完"; 词边界进度均无法从命令行
 * 拿到, [supportsWordProgress] 恒 false。
 *
 * 注意文本一律作为独立 argv 元素或走 stdin 传递, 不做 shell 引号包裹 ——
 * ProcessBuilder 不经过 shell, 手动加引号会把引号本身读出来。
 */
internal abstract class CommandLineTtsBackend : DesktopTtsBackend {

    override val supportsPause: Boolean = false
    override val supportsWordProgress: Boolean = false

    private val activeProcess = AtomicReference<Process?>(null)

    /** 朗读代号, stop 后自增使在跑的等待线程失效。 */
    private val generation = AtomicLong(0)

    @Volatile private var voiceCache: List<DesktopTtsVoice>? = null

    @Volatile private var selectedVoiceField: String? = null

    /** 已选音色, 子类构造命令时用。 */
    protected val selectedVoice: String? get() = selectedVoiceField

    override val currentVoiceId: String? get() = selectedVoiceField

    /** 构造朗读命令; 返回 null 表示构造失败。 */
    protected abstract fun buildSpeakCommand(text: String, rate: Float): List<String>?

    /** 文本是否通过 stdin 传给子进程 (say / espeak-ng 需要, spd-say 走 argv)。 */
    protected open val textViaStdin: Boolean = false

    /** 枚举音色, 默认无。 */
    protected open fun loadVoices(): List<DesktopTtsVoice> = emptyList()

    /** 停止时的额外动作 (如 spd-say 需要另起 `-C` 取消 daemon 队列)。 */
    protected open fun onStop() = Unit

    override fun voices(): List<DesktopTtsVoice> {
        voiceCache?.let { return it }
        val list = runCatching { loadVoices() }.getOrDefault(emptyList())
        voiceCache = list
        return list
    }

    override fun selectVoice(voiceId: String?): Boolean {
        selectedVoiceField = voiceId
        return true
    }

    override fun speak(text: String, rate: Float, utteranceId: String, listener: TtsBackendListener) {
        val gen = generation.incrementAndGet()
        killActive()
        val command = buildSpeakCommand(text, rate)
        if (command == null) {
            listener.onError(utteranceId, ERROR_NO_COMMAND)
            return
        }
        Thread({
            listener.onStart(utteranceId)
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                if (generation.get() == gen) listener.onError(utteranceId, ERROR_LAUNCH_FAILED)
                return@Thread
            }
            activeProcess.set(process)
            if (textViaStdin) {
                runCatching {
                    process.outputStream.bufferedWriter().use { it.write(text) }
                }
            } else {
                runCatching { process.outputStream.close() }
            }
            // 消耗 stdout 防缓冲塞满 (合并了 stderr, 诊断信息可能不少)
            val drain = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { } }
                }
            }, "tts-drain-$utteranceId").apply {
                isDaemon = true
                start()
            }
            val exit = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            activeProcess.compareAndSet(process, null)
            runCatching { drain.join(300) }
            // 已被 stop 或被新的 speak 顶掉: 不报完成也不报错
            if (generation.get() != gen) return@Thread
            if (exit == 0) listener.onDone(utteranceId) else listener.onError(utteranceId, exit)
        }, "tts-speak-$utteranceId").apply {
            isDaemon = true
            start()
        }
    }

    override fun pause(): Boolean = false

    override fun resume(): Boolean = false

    override fun stop() {
        generation.incrementAndGet()
        killActive()
        runCatching { onStop() }
    }

    override fun shutdown() {
        stop()
    }

    private fun killActive() {
        val proc = activeProcess.getAndSet(null) ?: return
        if (!proc.isAlive) return
        runCatching { proc.destroy() }
        runCatching {
            if (!proc.waitFor(120, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
        }
    }

    protected companion object {
        const val ERROR_LAUNCH_FAILED = -2
        const val ERROR_NO_COMMAND = -4

        /** 跑一条辅助命令 (音色枚举 / 取消) 并取 stdout, 失败返回 null。 */
        fun runCapture(command: List<String>, timeoutMs: Long = 4000): String? = runCatching {
            val result = DesktopCommandRunner.run(command, timeoutMs)
            if (result.isOk) result.output else null
        }.getOrNull()

        /** 命令是否存在 (能跑起来即算存在, 退出码不看 —— 很多工具 --help 返回非零)。 */
        fun commandExists(name: String): Boolean = runCatching {
            DesktopCommandRunner.run(listOf(name, "--version"), 3000)
            true
        }.getOrDefault(false)
    }
}

/**
 * macOS 后端: `say`。
 *
 * - 文本走 stdin (`say` 无文本实参时读 stdin), 避免超长段落撑爆 argv 与转义问题
 * - `say -v '?'` 枚举音色, 输出形如 `Tingting  zh_CN  # 您好...`
 * - `-r` 为词/分钟, 未文档化范围, 以 200 WPM 作 1.0x 基准
 */
internal class MacSayTtsBackend : CommandLineTtsBackend() {

    override val id: String = "say"
    override val textViaStdin: Boolean = true

    override fun buildSpeakCommand(text: String, rate: Float): List<String> {
        val wpm = (rate * 200f).toInt().coerceIn(90, 500)
        return buildList {
            add("say")
            add("-r")
            add(wpm.toString())
            selectedVoice?.let {
                add("-v")
                add(it)
            }
        }
    }

    override fun loadVoices(): List<DesktopTtsVoice> {
        val out = runCapture(listOf("say", "-v", "?")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            // 先按 # 切掉示例句, 余下末尾 token 是 locale, 其前是音色名 (名字可能含空格)
            val head = line.substringBefore('#').trim()
            if (head.isEmpty()) return@mapNotNull null
            val locale = head.substringAfterLast(' ', "").trim()
            val name = head.substringBeforeLast(' ', head).trim()
            if (name.isEmpty()) return@mapNotNull null
            DesktopTtsVoice(
                id = name,
                name = name,
                locale = locale.ifEmpty { null }?.replace('_', '-'),
            )
        }.toList()
    }

    companion object {
        fun isAvailable(): Boolean = runCapture(listOf("say", "-v", "?")) != null
    }
}

/**
 * Linux 首选后端: speech-dispatcher 的 `spd-say`。
 *
 * - `-w` 让进程等到朗读结束才退出, 段级推进才有依据
 * - `-C` 取消 daemon 队列: 只 kill 进程不够, 声音会继续播完
 * - `-r` 为 -100..100 相对语速, 按倍率线性映射
 * - `-L` 枚举音色, 三列定宽 (NAME / LANGUAGE / VARIANT)
 */
internal class LinuxSpeechDispatcherBackend : CommandLineTtsBackend() {

    override val id: String = "spd-say"

    override fun buildSpeakCommand(text: String, rate: Float): List<String> {
        // 倍率 → -100..100: 1.0x → 0, 2.0x → +100, 0.5x → -100
        val r = if (rate >= 1f) ((rate - 1f) * 100f).toInt() else ((rate - 1f) * 200f).toInt()
        return buildList {
            add("spd-say")
            add("-w")
            add("-r")
            add(r.coerceIn(-100, 100).toString())
            selectedVoice?.let {
                add("-y")
                add(it)
            }
            add("--")
            add(text)
        }
    }

    override fun onStop() {
        runCapture(listOf("spd-say", "-C"), timeoutMs = 1500)
    }

    override fun loadVoices(): List<DesktopTtsVoice> {
        val out = runCapture(listOf("spd-say", "-L")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 2 || cols[0].equals("NAME", true)) return@mapNotNull null
            val name = cols.dropLast(2).joinToString(" ").ifEmpty { cols[0] }
            DesktopTtsVoice(id = name, name = name, locale = cols[cols.size - 2])
        }.toList()
    }

    companion object {
        /** 有 daemon 能应答才算可用: 光有二进制但 daemon 起不来会静默不出声。 */
        fun isAvailable(): Boolean = runCapture(listOf("spd-say", "-O"))?.isNotBlank() == true
    }
}

/**
 * Linux 回退后端: `espeak-ng` (无 speech-dispatcher 时用)。
 *
 * 文本走 `--stdin`, 进程阻塞直到读完才退出。
 */
internal class LinuxEspeakNgBackend(private val binary: String) : CommandLineTtsBackend() {

    override val id: String = binary
    override val textViaStdin: Boolean = true

    override fun buildSpeakCommand(text: String, rate: Float): List<String> {
        // espeak WPM 80..450, 默认 175; 以 175 作 1.0x 基准
        val wpm = (rate * 175f).toInt().coerceIn(80, 450)
        return buildList {
            add(binary)
            add("--stdin")
            add("-s")
            add(wpm.toString())
            selectedVoice?.let {
                add("-v")
                add(it)
            }
        }
    }

    override fun loadVoices(): List<DesktopTtsVoice> {
        val out = runCapture(listOf(binary, "--voices")) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            // Pty Language Age/Gender VoiceName File ...
            if (cols.size < 4 || cols[0].equals("Pty", true)) return@mapNotNull null
            val lang = cols[1]
            val name = cols[3]
            DesktopTtsVoice(id = name, name = name.replace('_', ' '), locale = lang)
        }.toList()
    }

    companion object {
        /** 优先 espeak-ng, 老发行版只有 espeak。 */
        fun detect(): LinuxEspeakNgBackend? = listOf("espeak-ng", "espeak")
            .firstOrNull { commandExists(it) }
            ?.let { LinuxEspeakNgBackend(it) }
    }
}
