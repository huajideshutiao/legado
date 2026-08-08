package io.legado.app.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 鸿蒙端 [AppLogHost] 实现: 落盘到 `{filesDir}/logs/appLog-*.txt` + toast 走 napi 桥。
 *
 * # 与桌面端 registerDesktopAppLogHost 对照
 * - [write] → 开启"记录日志" (PreferKey.recordLog) 时把日志追加到当日文件
 *   `{filesDir}/logs/appLog-<epochMillis>.txt` (native File 无 appendText, 用内存缓冲整体覆盖写;
 *   recordLog 门默认 false, 实际落盘日志量有界)
 * - [toast] → 复用 [OhosNativeBridge.showToast] (toast 桥就绪时弹真实 toast)
 * - [debugPrint] → println (K/N stdout 由鸿蒙 runtime 重定向到 hilog)
 * - [recordLog] → 读 PreferKey.recordLog (与 app/desktop 端同 key, 默认 false)
 * - [currentTimeMillis] → [systemCurrentTimeMillis]
 * - [timeZoneOffsetMillis] → 0 (鸿蒙无轻量 TZ 偏移查询, 日志 UI 按 UTC 显示, 与时间戳同源)
 *
 * # 崩溃日志
 * 日志文件即崩溃日志来源: [OhosCrashLogs] 列出 `{filesDir}/logs` 下的 appLog-*.txt 作为
 * CrashLogProvider 条目 (见 OhosPlatformServices.crashLogs)。
 *
 * 注册时机: [registerOhosAppLogHost] 在 registerOhosProviders 早期 (任何 AppLog.put 之前)。
 */
object OhosAppLogHost {

    private val lock = SynchronizedObject()

    /** 当日日志缓冲 (native File 无 append, 覆盖写整文件; recordLog 门控下有界)。 */
    private val buffer = StringBuilder()

    /** 日志目录: `{filesDir}/logs` (filesDir 计算 getter, 晚注入也自愈)。 */
    private val logDir: String get() = AppFilesDirs.get().filesDir + "/logs"

    /** 当前日志文件名 (按首次写入时间戳生成, 进程内稳定)。 */
    @Volatile
    private var currentFileName: String? = null

    private fun appendLog(tag: String, message: String) {
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

    private val host = object : AppLogHost {
        override fun currentTimeMillis(): Long = systemCurrentTimeMillis()

        override fun timeZoneOffsetMillis(): Long = 0L

        override val recordLog: Boolean
            get() = runCatching {
                PreferenceProviders.get().getBoolean(PreferKey.recordLog, false)
            }.getOrDefault(false)

        override fun write(tag: String, message: String) {
            // recordLog 门控 (与 app/desktop 端 AppLog.write 一致, 默认 false 不落盘)
            if (!recordLog) return
            appendLog(tag, message)
        }

        override fun toast(message: String) {
            // 复用 toast napi 桥 (就绪时真实 toast; 未就绪 OhosNativeBridge 内部降级 println)
            OhosNativeBridge.showToast(message, 2000)
        }

        override fun debugPrint(tag: String, message: String, throwable: Throwable?) {
            println("[$tag] $message")
            throwable?.printStackTrace()
        }
    }

    /** 注册一次 (幂等)。 */
    fun register() {
        AppLog.registerHost(host)
    }
}

/**
 * 崩溃日志存储: 列出/读取/清空 `{filesDir}/logs` 下的 appLog-*.txt。
 *
 * 对照 app 端 AndroidCrashLogProvider / desktop DesktopCrashLogProvider 的接口语义
 * (CrashViewModel.initData/readFile/clearCrashLog), 鸿蒙端日志即崩溃日志来源
 * (由 [OhosAppLogHost] 在 recordLog 开启时落盘)。
 */
object OhosCrashLogs {

    /** 日志目录 (与 [OhosAppLogHost.logDir] 同源)。 */
    private val logDir: String get() = AppFilesDirs.get().filesDir + "/logs"

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

/** 鸿蒙宿主启动早期注册一次 (任何 AppLog.put 之前, 见 registerOhosProviders 0.8 步)。 */
fun registerOhosAppLogHost() {
    OhosAppLogHost.register()
}
