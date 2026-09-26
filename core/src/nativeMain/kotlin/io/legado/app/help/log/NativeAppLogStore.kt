package io.legado.app.help.log

import io.legado.app.help.crash.NativeCrashLogWriter
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * native (iOS/鸿蒙) 端日志落盘: `{filesDir}/logs/appLog-<epochMillis>.txt`。
 *
 * 只用 [io.legado.app.utils.File] + [AppFilesDirs], 无平台专属 API, 故上提 nativeMain
 * 供 [NativeAppLogHost] 落盘用 (原实现在 ohosMain); host 侧只保留 toast 这个真正的平台差异。
 */
object NativeAppLogStore {

    private val lock = SynchronizedObject()

    /** 当日日志缓冲 (native File 无 appendText, 覆盖写整文件; recordLog 门控下有界)。 */
    private val buffer = StringBuilder()

    /** 当前日志文件名 (按首次写入时间戳生成, 进程内稳定)。 */
    @Volatile
    private var currentFileName: String? = null

    /** 追加一条日志并落盘; 失败静默 (日志写入不应影响业务链路)。 */
    fun append(tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = currentFile()
                buffer.append("[${systemCurrentTimeMillis()}] [$tag] $message\n")
                file.writeText(buffer.toString())
            }
        }
    }

    private fun currentFile(): File {
        val dir = File(NativeCrashLogs.logDir).apply { mkdirs() }
        val name = currentFileName ?: "appLog-${systemCurrentTimeMillis()}.txt".also {
            currentFileName = it
        }
        return File(dir, name)
    }
}

/**
 * 崩溃日志存储: 汇总两类文件供两端 `CrashLogProvider` 读取。
 *
 * - `{filesDir}/logs/crash/crash-*.log` —— 真正的崩溃现场, 由 [io.legado.app.help.crash.NativeCrashLogWriter]
 *   经未捕获异常钩子 / 信号处理器落盘 (不受"记录日志"开关门控, 默认就在写)
 * - `{filesDir}/logs/appLog-*.txt` —— 运行日志, 由 [NativeAppLogStore] 在 recordLog 开启时落盘
 *
 * 对照 app 端 AndroidCrashLogProvider / desktop DesktopCrashLogProvider 的接口语义
 * (CrashViewModel.initData/readFile/clearCrashLog)。
 */
object NativeCrashLogs {

    /** 运行日志目录 (filesDir 计算 getter, 晚注入也自愈)。 */
    internal val logDir: String get() = AppFilesDirs.get().filesDir + "/logs"

    /** 单个日志文件绝对路径 (供分享用)。按前缀分流到崩溃目录或运行日志目录。 */
    fun logPath(name: String): String =
        if (isCrashFile(name)) NativeCrashLogWriter.crashPath(name) else "$logDir/$name"

    /** 列出日志文件名 (崩溃日志 + 运行日志, 按修改时间倒序)。 */
    fun listLogs(): List<String> {
        val dir = File(logDir)
        val appLogs = if (dir.isDirectory) {
            dir.listFiles { it.isFile && it.name.startsWith(APP_LOG_PREFIX) && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name }
                .orEmpty()
        } else {
            emptyList()
        }
        // 崩溃日志排在前: 排查现场时它才是要看的那一份
        return NativeCrashLogWriter.listCrashLogs() + appLogs
    }

    /** 读取单个日志文件内容; 不存在返回 null。 */
    fun readLog(name: String): String? {
        if (isCrashFile(name)) return NativeCrashLogWriter.readCrashLog(name)
        val file = File(logDir, name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** 清空所有日志文件 (两类都清)。 */
    fun clearLogs() {
        NativeCrashLogWriter.clearCrashLogs()
        val dir = File(logDir)
        if (!dir.isDirectory) return
        dir.listFiles { it.isFile && it.name.startsWith(APP_LOG_PREFIX) && it.name.endsWith(".txt") }
            ?.forEach { it.delete() }
    }

    private fun isCrashFile(name: String): Boolean =
        name.startsWith(NativeCrashLogWriter.FILE_PREFIX)

    private const val APP_LOG_PREFIX = "appLog-"
}
