package io.legado.app.help.log

import io.legado.app.constant.LOG_DIR_NAME
import io.legado.app.help.crash.NativeCrashLogWriter
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * native (iOS/鸿蒙) 端运行日志落盘: `{filesDir}/log/appLog-<epochMillis>.txt`。
 *
 * 只用 [io.legado.app.utils.File] + [AppFilesDirs], 无平台专属 API, 故上提 nativeMain
 * 供 [NativeAppLogHost] 落盘用 (原实现在 ohosMain); host 侧只保留 toast 这个真正的平台差异。
 */
object NativeAppLogStore {

    private val lock = SynchronizedObject()

    /** 运行日志目录 (filesDir 计算 getter, 晚注入也自愈; 供应端打包日志取源)。 */
    val logDir: String get() = AppFilesDirs.get().filesDir + "/" + LOG_DIR_NAME

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
        val dir = File(logDir).apply { mkdirs() }
        val name = currentFileName ?: "appLog-${systemCurrentTimeMillis()}.txt".also {
            currentFileName = it
        }
        return File(dir, name)
    }
}

/**
 * 崩溃日志存储 (两端 `CrashLogProvider` 的数据源)。
 *
 * 只收 `{filesDir}/log/crash-*.log` —— 真正的崩溃现场, 由
 * [io.legado.app.help.crash.NativeCrashLogWriter] 经未捕获异常钩子落盘
 * (不受"记录日志"开关门控, 默认就在写)。
 *
 * 运行日志 (`{filesDir}/log/appLog-*.txt`) 与崩溃日志同目录, 靠文件名前缀区分, 不列入本列表
 * (对照 app 端 `CrashLogsDialog.CrashViewModel.initData` 只收 crash- 前缀; 运行日志走
 * [io.legado.app.ui.about.AppLogDialog] 这条独立通道)。
 */
object NativeCrashLogs {

    /** 单个崩溃日志文件绝对路径 (供分享用)。 */
    fun logPath(name: String): String = NativeCrashLogWriter.crashPath(name)

    /** 列出崩溃日志文件名 (按修改时间倒序)。 */
    fun listLogs(): List<String> = NativeCrashLogWriter.listCrashLogs()

    /** 读取单个崩溃日志内容; 不存在返回 null。 */
    fun readLog(name: String): String? = NativeCrashLogWriter.readCrashLog(name)

    /** 清空全部崩溃日志 (对照 CrashViewModel.clearCrashLog 只删 crash 目录)。 */
    fun clearLogs() = NativeCrashLogWriter.clearCrashLogs()
}
