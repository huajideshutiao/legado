package io.legado.desktop.help

import java.util.concurrent.TimeUnit

/**
 * 外部命令一次执行的结果。
 *
 * @param exitCode 进程退出码; 超时被强杀时为 null ([timedOut])
 * @param output stdout+stderr 合并输出 (ProcessBuilder redirectErrorStream)
 */
class DesktopCommandResult internal constructor(
    val exitCode: Int?,
    val output: String,
) {
    /** 是否超时被强杀。 */
    val timedOut: Boolean get() = exitCode == null

    /** 是否正常退出且退出码为 0。 */
    val isOk: Boolean get() = exitCode == 0
}

/**
 * 外部进程执行样板收敛: 超时保护 + 输出收集 + 超时强杀。
 *
 * 各调用点原先各写一份 `ProcessBuilder(...).redirectErrorStream(true).start()` +
 * waitFor(timeout) + destroyForcibly 样板 (WMI 亮度 / URL protocol 注册 /
 * 命令行 TTS / macOS 电量), 超时时长与退出码/错误处理策略各不相同,
 * 差异留给调用方, 本类只收敛共同的进程编排:
 *
 * - stdin 立即关闭 (子进程等 stdin 的场景如 say/espeak 不悬挂)
 * - stdout+stderr 合并读尽, 防缓冲塞满死锁 (不关心输出的调用方也受益)
 * - 超时 destroyForcibly, 以 [DesktopCommandResult.exitCode] = null 区分
 * - 进程启动失败抛 IOException, 由调用方按原有策略 runCatching 处理
 *
 * 注意: 只适合「跑完就退出」的短命令; 常驻进程 (keep-awake 抑制子进程 /
 * 重启拉起 / 打开系统设置页) 不等待退出, 不适用本类。
 */
object DesktopCommandRunner {

    /** 运行外部命令并等待结束 (超时强杀)。 */
    fun run(command: List<String>, timeoutMs: Long): DesktopCommandResult {
        val proc = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        proc.outputStream.close()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            return DesktopCommandResult(null, output)
        }
        return DesktopCommandResult(proc.exitValue(), output)
    }
}
