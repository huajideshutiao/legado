package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.constant.LOG_DIR_NAME
import io.legado.app.help.book.bookLogDirOrNull
import io.legado.app.help.crash.CrashLogWriter
import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.tts.DesktopReadAloudHost
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.LogManager

/**
 * 桌面端日志落盘目录 (写入方 [DesktopCrashHandler] 与读取方 DesktopCrashLogProvider 共用)。
 *
 * 对照 app 端 CrashHandler.saveCrashInfo2File 的两份落盘:
 * - [cacheCrashDir] ← app 端 `externalCacheDir/log`, 应用私有, 不依赖任何 provider 注册,
 *   崩溃发生在启动早期也能写进去; 运行日志 (appLog-) 与崩溃日志 (crash-) 同目录, 靠文件名前缀区分
 * - [exportCrashDir] ← 用户选定的书籍保存位置下的 log 目录, 用户可见 (未设置保存位置时为 null)
 */
object DesktopCrashLogDirs {

    /** 应用缓存目录下的日志子目录 (desktopAppCacheDir 为纯函数, 无需注册)。 */
    val cacheCrashDir: File get() = File(desktopAppCacheDir(), LOG_DIR_NAME)

    /** 书籍保存位置下的日志目录 (未设置保存位置或 provider 未就绪时 null)。 */
    val exportCrashDir: File?
        get() = runCatching { bookLogDirOrNull()?.let(::File) }.getOrNull()

    /** 落盘目标 (写两份), 顺序 = 优先级。 */
    fun writeDirs(): List<File> = listOfNotNull(cacheCrashDir, exportCrashDir)

    /** 读取目标 (含历史版本只写导出目录的日志)。 */
    fun readDirs(): List<File> = writeDirs()

    /**
     * 启动时把**属于本应用**的 JVM 致命错误现场收进崩溃日志目录, 返回是否收到。
     *
     * # 为什么是"收进来"而不是"读时跨目录扫"
     * HotSpot 在进程崩溃时写 `hs_err_pid<pid>.log`, 落点由 JVM 自身决定 (启动参数 `-XX:ErrorFile`
     * 用不了: 落点要打包期定死, 而数据目录是运行期按便携/系统两种形态解析的), 顺序为
     * "当前工作目录 → 系统临时目录 → 用户主目录"。这三个位置是**多进程共享**的 ——
     * 直接把里面的 `hs_err_pid*.log` 当本应用的崩溃日志列出、清空时删掉, 会误删用户机器上
     * 其它 JVM 的现场。故改为启动时只认领**内容属于本应用**的那几份, 搬进崩溃目录, 之后
     * 列表/读取/清空都只在这个目录里操作, 与其它进程的文件再无交集。
     *
     * # 收到即代表"上次运行发生了致命错误"
     * 搬进来的文件必然产自上一次运行 (本次运行若也崩了, 进程已不在, 不会再走到这里),
     * 故调用方据此置"异常退出"标记, 让启动期提示能弹出来 —— 这条路径上 JVM 直接终止,
     * 各异常处理器都不会被调用, 只有这里能留下崩溃证据。
     */
    fun collectJvmFatalErrorLogs(): Boolean {
        val candidates = listOfNotNull(
            // 当前工作目录 (HotSpot 的首选落点)
            File("").absoluteFile,
            System.getProperty("java.io.tmpdir")?.let(::File),
            System.getProperty("user.home")?.let(::File),
        )
        val target = cacheCrashDir.apply { mkdirs() }
        var collected = false
        for (dir in candidates.distinct()) {
            val found = runCatching {
                dir.listFiles { f -> f.isFile && JVM_FATAL_ERROR_NAME.matches(f.name) }
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
            for (file in found) {
                if (file.absolutePath == File(target, file.name).absolutePath) continue
                if (!belongsToThisApp(file)) continue
                val ok = runCatching {
                    file.copyTo(File(target, file.name), overwrite = true)
                }.isSuccess
                if (!ok) continue
                file.delete()
                collected = true
            }
        }
        return collected
    }

    /**
     * 归属判定: 文件头部 `Command Line` 段出现本应用入口类才算本应用的现场。
     *
     * 只读头部有限字节 —— `Command Line` 在 SUMMARY 段 (文件前几十行), 不读全文
     * (hs_err 可达数 MB)。
     */
    private fun belongsToThisApp(file: File): Boolean = runCatching {
        val head = ByteArray(JVM_FATAL_ERROR_HEAD_BYTES)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) false
        else {
            val text = String(head, 0, read, Charsets.ISO_8859_1)
            JVM_FATAL_ERROR_OWNER_MARKERS.any { text.contains(it) }
        }
    }.getOrDefault(false)


    /** HotSpot 致命错误日志文件名 (固定前缀 + pid)。 */
    private val JVM_FATAL_ERROR_NAME = Regex("hs_err_pid\\d+\\.log")

    /** 归属判定读取的头部字节数 (Command Line 在摘要段, 前几十行足够)。 */
    private const val JVM_FATAL_ERROR_HEAD_BYTES = 16 * 1024

    /** 本应用入口类 (桌面便携/安装版与无头分发版各一个)。 */
    private val JVM_FATAL_ERROR_OWNER_MARKERS = listOf(
        "io.legado.desktop.MainKt",
        "io.legado.headless.MainKt",
    )

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
        // 异常退出标记: 下次启动提示用户查看崩溃日志 (对照 app 端 CrashHandler.handleException
        // 的 LocalConfig.appCrash = true)。写偏好存储无需 async-signal-safe, 可正常分配
        runCatching { CrashMarkerProviders.get().mark() }
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
