package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.crash.CrashLogWriter
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.app.help.storage.DataStorageProviders
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.tts.DesktopReadAloudHost
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.LogManager

/**
 * 桌面端崩溃日志落盘目录 (写入方 [DesktopCrashHandler] 与读取方 DesktopCrashLogProvider 共用)。
 *
 * 对照 app 端 CrashHandler.saveCrashInfo2File 的两份落盘:
 * - [cacheCrashDir] ← app 端 `externalCacheDir/crash`, 应用私有, 不依赖任何 provider 注册,
 *   崩溃发生在启动早期也能写进去
 * - [exportCrashDir] ← app 端 `备份路径/crash`, 用户可见 (桌面 legado 目录), provider 未就绪时为 null
 */
object DesktopCrashLogDirs {

    /** 应用缓存目录下的 crash 子目录 (desktopAppCacheDir 为纯函数, 无需注册)。 */
    val cacheCrashDir: File get() = File(desktopAppCacheDir(), "crash")

    /** 用户可见导出目录下的 logs/crash (DataStorage 未注册时 null)。 */
    val exportCrashDir: File?
        get() = runCatching {
            File(DataStorageProviders.get().userExportDir, "logs/crash")
        }.getOrNull()

    /** 落盘目标 (写两份), 顺序 = 优先级。 */
    fun writeDirs(): List<File> = listOfNotNull(cacheCrashDir, exportCrashDir)

    /** 读取目标 (含历史版本只写导出目录的日志)。 */
    fun readDirs(): List<File> = writeDirs()
}

/**
 * 桌面端全局崩溃处理 (app 端 `io.legado.app.help.CrashHandler` 的桌面等价)。
 *
 * app 端靠 `Thread.setDefaultUncaughtExceptionHandler` + `saveCrashInfo2File`;
 * 桌面端相同思路, 三个入口都汇到 [handleCrash]:
 * 1. [Thread.setDefaultUncaughtExceptionHandler] —— 覆盖所有线程, 含协程未捕获异常
 *    (kotlinx.coroutines 默认兜底就是当前线程的 uncaughtExceptionHandler)
 * 2. Compose 窗口事件分发异常 —— 见 Main.kt 的 LocalWindowExceptionHandlerFactory
 *    (EDT 上的异常不走 default handler, 必须单独接)
 * 3. JVM shutdown hook —— 退出前 flush 日志句柄, 防止异步写盘的最后几条丢失
 *
 * 日志格式与文件名由 shared [CrashLogWriter] 统一 (与 app 端一致)。
 */
object DesktopCrashHandler {

    /** 系统默认 handler, 记录后继续交还 (对照 app 端 mDefaultHandler)。 */
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    private val installed = AtomicBoolean(false)

    /**
     * 崩溃参数头 (对照 app 端 CrashHandler.paramsMap 的机型/版本项)。
     * app 端取 Build.MANUFACTURER/MODEL/SDK_INT + appInfo, 桌面端取 JVM/OS 信息 + DesktopAppInfo。
     */
    private val paramsMap: Map<String, String> by lazy {
        buildMap {
            putAll(CrashLogWriter.jvmRuntimeParams())
            runCatching {
                put("versionName", DesktopAppInfo.versionName)
                put("versionCode", DesktopAppInfo.versionCode.toString())
            }
        }
    }

    /** main() 入口尽早调用一次 (越早越能覆盖启动期崩溃)。 */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            handleCrash(ex, "线程 ${thread.name} 未捕获异常")
            defaultHandler?.uncaughtException(thread, ex)
        }
        Runtime.getRuntime().addShutdownHook(Thread(::flushLogHandlers, "legado-log-flush"))
    }

    /**
     * 记录一次崩溃: 落盘 + 记 AppLog。任何一步失败都不再抛出 (崩溃路径上不能二次崩溃)。
     *
     * @param source 崩溃来源描述, 进 AppLog 便于区分线程崩溃 / Compose 分发崩溃
     */
    fun handleCrash(ex: Throwable, source: String) {
        // 对照 app 端 CrashHandler.uncaughtException 先 ReadAloud.stop(context);
        // isRun 判空后再调, 避免为一次崩溃反而触发朗读宿主的惰性初始化
        runCatching { if (DesktopReadAloudHost.isRun) DesktopReadAloudHost.stop() }
        val files = runCatching {
            CrashLogWriter.save(ex, paramsMap, DesktopCrashLogDirs.writeDirs())
        }.getOrDefault(emptyList())
        runCatching {
            val where = files.firstOrNull()?.absolutePath ?: "(落盘失败)"
            AppLog.put("$source, 崩溃日志: $where", ex)
        }
        runCatching { flushLogHandlers() }
    }

    /**
     * flush java.util.logging 句柄。桌面 AppLog 走 [io.legado.app.utils.AsyncFileHandler]
     * (globalExecutor 异步写), 进程立刻退出时最后几条日志还在队列里, 崩溃现场最关键的那几条最容易丢。
     */
    private fun flushLogHandlers() {
        runCatching {
            LogManager.getLogManager().getLogger("Legado")?.handlers?.forEach { it.flush() }
        }
    }
}
