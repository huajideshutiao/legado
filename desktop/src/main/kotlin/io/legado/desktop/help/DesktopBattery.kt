package io.legado.desktop.help

import com.sun.jna.Function
import com.sun.jna.Platform
import com.sun.jna.Structure
import io.legado.desktop.help.DesktopBattery.FALLBACK_LEVEL
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 桌面端电量读取 (JVM 无标准电池 API)。
 *
 * # 可行性核实
 * `java.lang.management.OperatingSystemMXBean` / `com.sun.management.OperatingSystemMXBean`
 * 只暴露 CPU / 内存 / 负载, **没有电池字段** (JDK 从未提供电源状态 API), 直接套用无法取值;
 * jna-platform 的 Kernel32 接口也未声明 `GetSystemPowerStatus`。因此 Windows 用 JNA
 * [Function] 直调 `kernel32!GetSystemPowerStatus` (与 [WindowsFileDialogs] 调 user32
 * 同模式, 项目已有 JNA 依赖), 读 [SystemPowerStatus] 结构体的电池剩余百分比。
 *
 * # 平台渠道
 * - **Windows**: JNA 直调 `kernel32!GetSystemPowerStatus` (Win32 API);
 * - **macOS**: 执行 `pmset -g batt` (Runtime.exec), 解析输出中的 `XX%` (2s 超时防卡死);
 * - **Linux**: 读 sysfs 标准接口 `/sys/class/power_supply/` 下 BAT* 的 capacity (0..100);
 * - **其他平台 / 任一渠道失败 / 无电池**: 回落 [FALLBACK_LEVEL] —— 桌面端台式机/AC 供电
 *   无电池属常态, 阅读页电量槽位仍显示兜底值 (用户要求 2026-08-08: 桌面端电量恒显示)。
 *
 * 电量变化不频繁, 结果带 10s TTL 缓存, 避免热路径反复执行进程 / 读文件。
 *
 * `BatteryLifePercent` 取值 0..100, 255 (0xFF) 表示未知 (交流供电且无电池时常见)。
 */
object DesktopBattery {

    /** TTL 缓存时长 (毫秒): 电量无需实时刷新, 缓存避免频繁 spawn 进程 / 读 sysfs。 */
    private const val TTL_MS = 10_000L

    /** 无电池 / 读取失败时的兜底电量 (台式机 AC 供电恒满电, 阅读页信息条恒显示电量)。 */
    const val FALLBACK_LEVEL = 100

    @Volatile
    private var cachedAt = -1L

    @Volatile
    private var cachedLevel = -1

    /** 当前电池剩余百分比 0..100; 无电池 / 读取失败时回落 [FALLBACK_LEVEL] (信息条恒显示)。 */
    fun getBatteryLevel(): Int {
        val now = System.currentTimeMillis()
        if (cachedAt != -1L && now - cachedAt < TTL_MS) return cachedLevel
        val level = when {
            Platform.isWindows() -> readWindowsBattery()
            Platform.isMac() -> readMacBattery()
            Platform.isLinux() -> readLinuxBattery()
            else -> -1
        }
        cachedLevel = if (level in 0..100) level else FALLBACK_LEVEL
        cachedAt = now
        return cachedLevel
    }

    /** Windows: JNA 直调 `kernel32!GetSystemPowerStatus`, 读 [SystemPowerStatus] 的电池百分比。 */
    private fun readWindowsBattery(): Int {
        return runCatching {
            val fn =
                Function.getFunction("kernel32", "GetSystemPowerStatus", Function.ALT_CONVENTION)
            val status = SystemPowerStatus()
            if (fn.invokeInt(arrayOf(status)) == 0) return@runCatching -1
            val raw = status.batteryLifePercent.toInt()
            if (raw in 0..100) raw else -1
        }.getOrDefault(-1)
    }

    /**
     * macOS: 执行 `pmset -g batt`, 解析形如 `-InternalBattery-0 (id=...) 87%; ...` 中的 `XX%`。
     * 2s 超时 (超时强杀进程防卡死), 任何异常 / 解析失败返回 -1。
     */
    private fun readMacBattery(): Int {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("pmset", "-g", "batt"))
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1
            }
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val value =
                Regex("""(\d{1,3})%""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return -1
            if (value in 0..100) value else -1
        }.getOrDefault(-1)
    }

    /**
     * Linux: 读 sysfs 标准接口 `/sys/class/power_supply/` 下 BAT* 的 capacity (0..100)。
    * 无电池目录 (台式机 / 虚拟机) 或读取失败返回 -1。
    */
    private fun readLinuxBattery(): Int {
        return runCatching {
            val capacityFile = File("/sys/class/power_supply")
                .listFiles { f -> f.name.startsWith("BAT") && File(f, "capacity").isFile }
                ?.minByOrNull { it.name }
                ?.let { File(it, "capacity") }
                ?: return -1
            val value = capacityFile.readText().trim().toIntOrNull() ?: return -1
            if (value in 0..100) value else -1
        }.getOrDefault(-1)
    }

    /** Win32 SYSTEM_POWER_STATUS 结构 (4 BYTE + 2 DWORD = 12 字节, 布局见 MSDN)。 */
    class SystemPowerStatus : Structure() {
        @JvmField
        var aclineStatus: Byte = 0
        @JvmField
        var batteryFlag: Byte = 0
        @JvmField
        var batteryLifePercent: Byte = 0
        @JvmField
        var systemStatusFlag: Byte = 0
        @JvmField
        var batteryLifeTime: Int = 0
        @JvmField
        var batteryFullLifeTime: Int = 0

        override fun getFieldOrder(): List<String> = listOf(
            "aclineStatus", "batteryFlag", "batteryLifePercent",
            "systemStatusFlag", "batteryLifeTime", "batteryFullLifeTime",
        )
    }
}
