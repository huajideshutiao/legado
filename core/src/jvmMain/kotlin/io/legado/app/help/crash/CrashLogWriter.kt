package io.legado.app.help.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃日志落盘 (app 端 `io.legado.app.help.CrashHandler.saveCrashInfo2File` 的纯逻辑下沉)。
 *
 * 与 app 端一致的部分: 参数头 (`key=value` 行) + 异常栈 + cause 链逐层 printStackTrace、
 * 文件名 `crash-{yyyy-MM-dd-HH-mm-ss}-{timestamp}.log`、写两份、清理 7 天前旧日志。
 *
 * 平台差异经参数注入: 参数头内容由调用方给 ([jvmRuntimeParams] 提供 JVM 通用项),
 * 落盘目录由调用方给 (Android=外部缓存+SAF 备份路径, 桌面=缓存目录+用户导出目录)。
 */
object CrashLogWriter {

    /** 与 app 端 CrashHandler.format 一致。 */
    private val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    /** 崩溃日志文件名前缀, 供列表侧过滤。 */
    const val FILE_PREFIX = "crash-"

    private const val KEEP_DAYS = 7L

    /**
     * 组装崩溃日志文本: 参数头 + 异常栈 + cause 链 (对齐 app 端 saveCrashInfo2File 的 StringBuilder)。
     */
    fun buildCrashLog(ex: Throwable, params: Map<String, String>): String {
        val sb = StringBuilder()
        for ((key, value) in params) {
            sb.append(key).append("=").append(value).append("\n")
        }
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        var cause: Throwable? = ex.cause
        while (cause != null) {
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }
        printWriter.close()
        sb.append(writer.toString())
        return sb.toString()
    }

    /** 文件名 (与 app 端一致: 时间串 + 毫秒时间戳, 保证同秒多次崩溃不互相覆盖)。 */
    fun crashFileName(now: Long = System.currentTimeMillis()): String {
        val time = synchronized(format) { format.format(Date(now)) }
        return "$FILE_PREFIX$time-$now.log"
    }

    /**
     * 写崩溃日志到 [dirs] 每个目录各一份 (对齐 app 端"备份路径 + 外部缓存"两份)。
     *
     * 单个目录失败不影响其他目录 (崩溃路径上不能再抛异常)。
     *
     * @return 实际写成功的文件列表
     */
    fun save(ex: Throwable, params: Map<String, String>, dirs: List<File>): List<File> {
        val crashLog = runCatching { buildCrashLog(ex, params) }
            .getOrElse { ex.stackTraceToString() }
        val fileName = crashFileName()
        val written = ArrayList<File>(dirs.size)
        for (dir in dirs) {
            runCatching {
                dir.mkdirs()
                cleanupExpired(dir)
                File(dir, fileName).apply { writeText(crashLog) }
            }.onSuccess { written += it }
        }
        return written
    }

    /** 删除 [dir] 下 7 天前的崩溃日志 (对齐 app 端 exceedTimeMillis 清理)。 */
    fun cleanupExpired(dir: File) {
        runCatching {
            val exceedTimeMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
            dir.listFiles()?.forEach {
                if (it.isFile && it.lastModified() < exceedTimeMillis) it.delete()
            }
        }
    }

    /**
     * JVM 通用运行时信息 (对照 app 端 paramsMap 的机型/SDK/heapSize 项)。
     * 桌面端另行补 versionName/versionCode。
     */
    fun jvmRuntimeParams(): Map<String, String> = buildMap {
        runCatching {
            put("OS", System.getProperty("os.name").orEmpty())
            put("OS_VERSION", System.getProperty("os.version").orEmpty())
            put("ARCH", System.getProperty("os.arch").orEmpty())
            put("JAVA_VERSION", System.getProperty("java.version").orEmpty())
            put("JAVA_VENDOR", System.getProperty("java.vendor").orEmpty())
            val runtime = Runtime.getRuntime()
            put("heapSize", runtime.maxMemory().toString())
            put("heapUsed", (runtime.totalMemory() - runtime.freeMemory()).toString())
            put("processors", runtime.availableProcessors().toString())
        }
    }
}
