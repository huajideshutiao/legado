package io.legado.desktop.audio

import com.sun.jna.Platform
import io.legado.app.constant.AppLog
import io.legado.desktop.audio.DesktopScreenBrightness.get
import io.legado.desktop.audio.DesktopScreenBrightness.pendingLock
import io.legado.desktop.audio.DesktopScreenBrightness.set
import io.legado.desktop.help.DesktopCommandRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows 屏幕亮度 (WMI: WmiMonitorBrightness / WmiMonitorBrightnessMethods, PowerShell 调用):
 * 视频手势左半竖滑调"系统亮度" (对照 app 端 window.attributes.screenBrightness)。
 *
 * # 命令 (均带超时保护, 失败 destroyForcibly)
 * - 读: `powershell -NoProfile -Command "(Get-CimInstance -Namespace root/wmi -ClassName
 *   WmiMonitorBrightness).CurrentBrightness"` → stdout 0..100 (多显示器多行时取首个可解析行)
 * - 写: `powershell -NoProfile -Command "(Get-CimInstance -Namespace root/wmi -ClassName
 *   WmiMonitorBrightnessMethods).WmiSetBrightness(1,<n>)"` (timeout=1 立即生效)
 * - 仅笔记本 (ACPI 背光) 可用; 台式机/权限不足 → 读 null / 写静默失败, UI 不崩
 *
 * # 线程模型 (单线程 executor 串行, 防并发乱序)
 * - [get]: 同步等待结果 (进模式只需一次, 200~500ms 可接受)
 * - [set]: 异步 fire-and-forget + 最新值合并 (latest-wins): 拖动中手势 32ms 节流可达
 *   ~30 次/秒, 每次 PowerShell 200~500ms, 若逐条排队会在拖完后继续空转数分钟;
 *   合并后最多 1 条在途 + 1 条待写, 拖完 ~500ms 内落盘最终值。顺序性仍保证
 *   (同一线程, 后写覆盖先写)。若后续发现跟手性不足可再加时间节流。
 *
 * # 弹窗
 * ProcessBuilder 重定向了子进程输出 (redirectErrorStream), JDK 会以 CREATE_NO_WINDOW
 * 创建子进程, 不弹黑窗; 另加 -WindowStyle Hidden 双保险。
 */
internal object DesktopScreenBrightness {

    private const val TIMEOUT_MS = 3000L

    /** 单线程 executor: 读写串行 (防并发乱序)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-wmi").apply { isDaemon = true }
    }

    /** 待写亮度 (latest-wins 合并槽), 仅 [pendingLock] 保护下访问。 */
    private var pendingValue: Int? = null

    /** 是否已有 drain 循环在跑 (避免重复排队)。 */
    private var drainScheduled = false

    private val pendingLock = Any()

    /** 当前亮度 0..100; 失败 (非笔记本/权限/超时) 返回 null (调用方回落)。 */
    fun get(): Int? {
        if (!Platform.isWindows()) return null
        return submit {
            runPowerShell(
                "(Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness).CurrentBrightness"
            )
        }?.let { parseBrightness(it) }
    }

    /**
     * 设置亮度 0..100 (异步串行 + 最新值合并; 失败仅日志, UI 不崩)。
     * 调用方无需节流: 合并槽保证高频拖动只产生至多一条在途 + 一条待写。
     */
    fun set(value: Int) {
        if (!Platform.isWindows()) return
        val v = value.coerceIn(0, 100)
        var launch = false
        synchronized(pendingLock) {
            pendingValue = v
            if (!drainScheduled) {
                drainScheduled = true
                launch = true
            }
        }
        if (launch) {
            executor.execute { drainLoop() }
        }
    }

    // ==================== 内部实现 ====================

    /** 同步提交到 executor 并等待结果 (超时返回 null)。 */
    private fun <T> submit(block: () -> T?): T? = runCatching {
        executor.submit(Callable<T?> { runCatching { block() }.getOrNull() })
            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }.getOrNull()

    /** drain 循环: 每次取最新待写值执行一条命令, 无待写即退出 (串行 + latest-wins)。 */
    private fun drainLoop() {
        while (true) {
            val v: Int = synchronized(pendingLock) {
                val cur = pendingValue
                if (cur == null) {
                    drainScheduled = false
                    return
                }
                pendingValue = null
                cur
            }
            runCatching {
                runPowerShell(
                    "(Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods).WmiSetBrightness(1,$v)"
                )
            }.onFailure {
                AppLog.putDebug("WMI 亮度设置失败: ${it.message}")
            }
        }
    }

    /** 执行一条 PowerShell 命令并取 stdout; 失败/超时返回 null。 */
    private fun runPowerShell(command: String): String? = runCatching {
        val result = DesktopCommandRunner.run(
            listOf("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", command),
            TIMEOUT_MS,
        )
        when {
            result.exitCode == null -> {
                AppLog.putDebug("WMI 亮度命令超时: $command")
                null
            }

            result.exitCode != 0 -> {
                AppLog.putDebug("WMI 亮度命令失败 (exit=${result.exitCode}): $command")
                null
            }

            else -> result.output
        }
    }.getOrNull()

    /** 解析 stdout 为 0..100 (多显示器多行取首个可解析行; 解析失败返回 null)。 */
    private fun parseBrightness(output: String?): Int? = output
        ?.lineSequence()
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.firstOrNull()
        ?.coerceIn(0, 100)
}
