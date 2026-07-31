package io.legado.desktop.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.globalExecutor
import io.legado.app.utils.AsyncFileHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

// Debug 日志开关: -Dlegado.desktop.debug=true 开启 stdout 调试输出
// 生产环境静默, 减少 println 同步 flush 开销 (与 Main.kt 同一开关模式)
private val desktopDebug = System.getProperty("legado.desktop.debug")?.toBoolean() == true

private fun debugLog(msg: String) {
    if (desktopDebug) println(msg)
}

private const val LOG_TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"

private val logTimeFormat by lazy { SimpleDateFormat(LOG_TIME_PATTERN, Locale.US) }

private fun currentDateStr(): String = synchronized(logTimeFormat) { logTimeFormat.format(Date()) }

private val logger: Logger by lazy {
    // 桌面端关掉父 handler, 否则 java.util.logging 默认把每条日志再打到 stderr
    Logger.getLogger("Legado").apply { useParentHandlers = false }
}

private val logLock = Any()

@Volatile
private var fileHandler: FileHandler? = null

/**
 * 落盘句柄, 首次需要写入时创建 (注册本 host 早于 [AppFilesDirs], 目录未就绪时返回 null 下次再试)。
 * [FileHandler] 常驻持有文件流, 不会每条日志开关句柄。
 */
private fun fileHandlerOrNull(): FileHandler? {
    fileHandler?.let { return it }
    return synchronized(logLock) {
        fileHandler ?: createFileHandler()?.also {
            logger.addHandler(it)
            fileHandler = it
        }
    }
}

/** 对齐 app 端 LogUtils.createFileHandler: `{cacheDir}/logs/appLog-{时间}.txt` + 清理 7 天前旧日志。 */
private fun createFileHandler(): FileHandler? = try {
    val dirs = AppFilesDirs.get()
    // 桌面端无外部缓存, 回退到 cacheDir (AppFilesDir 文档约定的回退路径)
    val logFolder = File(dirs.externalCacheDir ?: dirs.cacheDir, "logs").apply { mkdirs() }
    globalExecutor.execute {
        val expiredTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
        logFolder.listFiles()?.forEach {
            if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) {
                it.delete()
            }
        }
    }
    val date = currentDateStr().replace(" ", "_").replace(":", "-")
    // FileHandler 的 pattern 里 % 是转义符 (Windows 临时目录可能含 %), 需成对转义
    val pattern = File(logFolder, "appLog-$date.txt").absolutePath.replace("%", "%%")
    AsyncFileHandler(pattern).apply {
        formatter = object : Formatter() {
            override fun format(record: LogRecord): String =
                currentDateStr() + ": " + record.message + "\n"
        }
        level = Level.INFO
    }
} catch (e: Exception) {
    e.printStackTrace()
    null
}

/**
 * 桌面端 [AppLogHost] 实现: 落盘到 `{cacheDir}/logs`, 调试输出到 stdout,
 * 不依赖 Android 的 LogUtils/toastOnUi/logcat。
 *
 * - [write] → 开启"记录日志"时经 [AsyncFileHandler] 异步落盘 (对齐 app 端 LogUtils.d)
 * - [toast] → println (桌面端无 Android Toast, 用 stdout 替代, 行为透明)
 * - [debugPrint] → println (DEBUG 模式下输出, 替代 Android logcat)
 * - [recordLog] → 读 PreferKey.recordLog 配置 (与 app 端 AppConfig.recordLog 一致, 默认 false)
 * - [currentTimeMillis] → System.currentTimeMillis()
 *
 * 注册时机: desktop main 入口早期, 任何 shared commonMain 调用 AppLog.put 之前。
 * 模式参考 app 端 `registerAndroidAppLogHost` (AppLogAndroid.kt)。
 *
 * 未注册时 AppLog 仍可用 (纯环形列表), 但副作用 (write/toast/debugPrint) 静默 no-op,
 * 表现为 shared commonMain 的异常/报错完全无输出, 排查困难。本注册让桌面端日志可见。
 */
private val desktopAppLogHost = object : AppLogHost {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun timeZoneOffsetMillis(): Long =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

    // 对齐 app 端 AppConfig.recordLog (默认 false)。本 host 早于 registerDesktopConfig 注册,
    // PreferenceProviders 未就绪时按默认值 false 处理。
    override val recordLog: Boolean
        get() = runCatching {
            PreferenceProviders.get().getBoolean(PreferKey.recordLog, false)
        }.getOrDefault(false)

    override fun write(tag: String, message: String) {
        debugLog("[$tag] $message")
        // app 端用 handler.level 做门控, 这里每次读 pref, 开关立即生效且不需要 upLevel()
        if (!recordLog) return
        runCatching {
            if (fileHandlerOrNull() != null) logger.log(Level.INFO, "$tag $message")
        }
    }

    override fun toast(message: String) {
        // 桌面端无 Android Toast, 用 println 替代 (UI 层另有 DesktopToaster 弹通知)
        debugLog("[AppLog.toast] $message")
    }

    override fun debugPrint(tag: String, message: String, throwable: Throwable?) {
        // DEBUG 模式下输出 (替代 Android logcat Log.e)
        debugLog("[$tag] $message")
        throwable?.printStackTrace()
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopAppLogHost() {
    AppLog.registerHost(desktopAppLogHost)
}
