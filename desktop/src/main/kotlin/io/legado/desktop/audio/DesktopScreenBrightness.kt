package io.legado.desktop.audio

import com.sun.jna.Function
import com.sun.jna.Platform
import com.sun.jna.ptr.FloatByReference
import io.legado.app.constant.AppLog
import io.legado.desktop.audio.DesktopScreenBrightness.get
import io.legado.desktop.audio.DesktopScreenBrightness.pendingLock
import io.legado.desktop.audio.DesktopScreenBrightness.set
import io.legado.desktop.help.DesktopCommandRunner
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 桌面端屏幕亮度控制 (跨平台支持: Windows / macOS / Linux):
 * 视频手势左半竖滑调"系统亮度" (对照 app 端 window.attributes.screenBrightness)。
 *
 * # 各平台机制 (均零外部第三方依赖):
 * - **Windows**: WMI CIM 命令 (Get-CimInstance / Invoke-CimMethod, PowerShell 调用)
 * - **macOS**: JNA 原生加载 DisplayServices 框架 (微秒级直调)
 * - **Linux**: 读取 /sys/class/backlight sysfs 接口; 写入走直接写 / systemd-logind D-Bus / GNOME D-Bus
 *
 * # 线程模型 (单线程 executor 串行, 防并发乱序)
 * - [get]: 进模式读取一次
 * - [set]: 异步 fire-and-forget + 最新值合并 (latest-wins)
 */
internal object DesktopScreenBrightness {

    private const val TIMEOUT_MS = 3000L

    /** 单线程 executor: 读写串行 (防并发乱序)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-brightness").apply { isDaemon = true }
    }

    /** 待写亮度 (latest-wins 合并槽), 仅 [pendingLock] 保护下访问。 */
    private var pendingValue: Int? = null

    /** 是否已有 drain 循环在跑 (避免重复排队)。 */
    private var drainScheduled = false

    private val pendingLock = Any()

    /** 当前亮度 0..100; 失败 (非笔记本/权限/超时) 返回 null (调用方回落)。 */
    fun get(): Int? {
        return when {
            Platform.isWindows() -> submit {
                runPowerShell(
                    "(Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness).CurrentBrightness"
                )
            }?.let { parseBrightness(it) }

            Platform.isMac() -> MacScreenBrightness.getBrightness()
            Platform.isLinux() -> LinuxScreenBrightness.getBrightness()
            else -> null
        }
    }

    /**
     * 设置亮度 0..100 (异步串行 + 最新值合并; 失败仅日志, UI 不崩)。
     * 调用方无需节流: 合并槽保证高频拖动只产生至多一条在途 + 一条待写。
     */
    fun set(value: Int) {
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
                when {
                    Platform.isWindows() -> {
                        runPowerShell(
                            "Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=1; Brightness=$v}"
                        )
                    }

                    Platform.isMac() -> {
                        MacScreenBrightness.setBrightness(v)
                    }

                    Platform.isLinux() -> {
                        LinuxScreenBrightness.setBrightness(v)
                    }
                }
            }.onFailure {
                AppLog.putDebug("屏幕亮度设置失败: ${it.message}")
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

    // ==================== macOS 原生实现 (DisplayServices JNA 直调) ====================

    private object MacScreenBrightness {
        fun getBrightness(): Int? = runCatching {
            val getFn = Function.getFunction(
                "/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices",
                "DisplayServicesGetBrightness"
            )
            val mainDisplayFn = Function.getFunction("CoreGraphics", "CGMainDisplayID")
            val displayId = mainDisplayFn.invokeInt(emptyArray())
            val brightness = FloatByReference()
            val res = getFn.invokeInt(arrayOf(displayId, brightness))
            if (res == 0) (brightness.value * 100).toInt().coerceIn(0, 100) else null
        }.getOrNull()

        fun setBrightness(value: Int): Boolean = runCatching {
            val setFn = Function.getFunction(
                "/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices",
                "DisplayServicesSetBrightness"
            )
            val mainDisplayFn = Function.getFunction("CoreGraphics", "CGMainDisplayID")
            val displayId = mainDisplayFn.invokeInt(emptyArray())
            val v = value.coerceIn(0, 100) / 100f
            val res = setFn.invokeInt(arrayOf<Any>(displayId, v))
            res == 0
        }.getOrDefault(false)
    }

    // ==================== Linux 原生实现 (sysfs + systemd D-Bus) ====================

    private object LinuxScreenBrightness {
        private fun findBacklightDir(): File? = runCatching {
            File("/sys/class/backlight").listFiles { f ->
                File(f, "brightness").isFile && File(f, "max_brightness").isFile
            }?.firstOrNull()
        }.getOrNull()

        fun getBrightness(): Int? = runCatching {
            val dir = findBacklightDir() ?: return@runCatching null
            val cur =
                File(dir, "brightness").readText().trim().toFloatOrNull() ?: return@runCatching null
            val max = File(dir, "max_brightness").readText().trim().toFloatOrNull()
                ?: return@runCatching null
            if (max > 0) ((cur / max) * 100).toInt().coerceIn(0, 100) else null
        }.getOrNull()

        fun setBrightness(value: Int): Boolean = runCatching {
            val dir = findBacklightDir()
            val v = value.coerceIn(0, 100)
            // 1. 若直接可写 sysfs 文件 (例如已配置 udev 规则)，直接写入
            if (dir != null) {
                val max = File(dir, "max_brightness").readText().trim().toIntOrNull() ?: 100
                val targetRaw = ((v / 100f) * max).toInt().coerceIn(0, max)
                val brightnessFile = File(dir, "brightness")
                val directWrite = runCatching {
                    brightnessFile.writeText(targetRaw.toString())
                    true
                }.getOrDefault(false)
                if (directWrite) return@runCatching true

                // 2. 通过 systemd-logind D-Bus 设置背光 (免 root 权限，systemd 自带，无需外部工具)
                val busResult = DesktopCommandRunner.run(
                    listOf(
                        "busctl", "call",
                        "org.freedesktop.login1",
                        "/org/freedesktop/login1/session/auto",
                        "org.freedesktop.login1.Session",
                        "SetBrightness",
                        "ssu", "backlight", dir.name, targetRaw.toString()
                    ),
                    TIMEOUT_MS
                )
                if (busResult.isOk) return@runCatching true
            }

            // 3. 通过 GNOME SettingsDaemon D-Bus 设置 (GNOME 桌面环境原生内置)
            val gnomeResult = DesktopCommandRunner.run(
                listOf(
                    "gdbus", "call", "--session",
                    "--dest", "org.gnome.SettingsDaemon.Power",
                    "--object-path", "/org/gnome/SettingsDaemon/Power",
                    "--method", "org.freedesktop.DBus.Properties.Set",
                    "org.gnome.SettingsDaemon.Power.Screen",
                    "Brightness", "<int32 $v>"
                ),
                TIMEOUT_MS
            )
            if (gnomeResult.isOk) return@runCatching true

            // 4. 机会性尝试已安装的 brightnessctl (若系统自带)
            val bctlResult = DesktopCommandRunner.run(
                listOf("brightnessctl", "set", "$v%"),
                TIMEOUT_MS
            )
            bctlResult.isOk
        }.getOrDefault(false)
    }
}

