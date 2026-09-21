package io.legado.app.help.log

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
 * 崩溃日志存储: 列出/读取/清空 `{filesDir}/logs` 下的 appLog-*.txt。
 *
 * 对照 app 端 AndroidCrashLogProvider / desktop DesktopCrashLogProvider 的接口语义
 * (CrashViewModel.initData/readFile/clearCrashLog), native 两端日志即崩溃日志来源
 * (由 [NativeAppLogStore] 在 recordLog 开启时落盘)。
 */
object NativeCrashLogs {

    /** 日志目录 (filesDir 计算 getter, 晚注入也自愈)。 */
    internal val logDir: String get() = AppFilesDirs.get().filesDir + "/logs"

    /** 单个日志文件绝对路径 (供分享用)。 */
    fun logPath(name: String): String = "$logDir/$name"

    /** 列出崩溃日志文件名 (appLog-*.txt, 按修改时间倒序)。 */
    fun listLogs(): List<String> {
        val dir = File(logDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { it.isFile && it.name.startsWith("appLog-") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** 读取单个日志文件内容; 不存在返回 null。 */
    fun readLog(name: String): String? {
        val file = File(File(logDir), name)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** 清空所有 appLog-*.txt。 */
    fun clearLogs() {
        val dir = File(logDir)
        if (!dir.isDirectory) return
        dir.listFiles { it.isFile && it.name.startsWith("appLog-") && it.name.endsWith(".txt") }
            ?.forEach { it.delete() }
    }
}
