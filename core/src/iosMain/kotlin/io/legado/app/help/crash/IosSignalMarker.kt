@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crash

import io.legado.app.platform.signalopen.legado_signal_open
import io.legado.app.utils.File
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGHUP
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGSYS
import platform.posix.SIGTRAP
import platform.posix.close
import platform.posix.signal
import platform.posix.write

/**
 * iOS 原生信号崩溃的"异常退出标记"通道 (只置标记, 不写现场)。
 *
 * 应用内拿不到原生崩溃的调用栈 (系统报告 `.ips` 才有), 故本通道不记录现场, 只做一件事:
 * 让下一次启动知道"上次是崩掉的", 从而弹出查崩溃日志的提示 (读取侧见
 * [CrashMarkerProviders], 消费点见 `notifyAppCrash`)。
 *
 * # 为什么只写标记是安全的
 * 处理器内只走 POSIX 的 async-signal-safe 调用: 路径与内容都在**安装期**备好 (堆上常驻,
 * 处理器内不分配、不拼接 C 字符串、不加锁), 处理器内只有 `open`/`write`/`close` 与 `signal`。
 * 标记文件只在崩溃时创建 —— 安装期预创建会让"文件存在"变成常态, 下次启动会被误判成崩溃。
 *
 * # 与系统报告的关系
 * 处理器写完标记后把该信号的处置交还 `SIG_DFL` 再返回: 返回时内核重新执行出错指令、再次触发
 * 同一信号, 这次走默认处置终止进程 —— 系统 ReportCrash 照常生成 `.ips`。
 */
internal object IosSignalMarker {

    private var installed = false

    /** 标记文件路径 (安装期备好, 处理器内直接用)。 */
    var markerPaths: List<CPointer<ByteVar>> = emptyList()

    /** 标记内容 "1\n" (与 Android native 侧、[CrashMarkerFile] 同值)。 */
    val markerText: ByteArray = MARKER_TEXT.encodeToByteArray()

    private const val MARKER_TEXT = "1\n"

    /**
     * 安装信号处理器 (宿主启动最早处调用一次, 见 `IosCrashHandler.install`)。
     *
     * @param dirs 标记文件候选目录 (与崩溃日志同目录): 处理器按顺序写能打开的那个, 读取侧同样按
     *   候选目录逐个查。
     */
    fun install(dirs: List<String>) {
        if (installed) return
        installed = true
        prepare(dirs)
        for (sig in HANDLED_SIGNALS) {
            signal(sig, staticCFunction(::onSignal))
        }
    }

    private fun prepare(dirs: List<String>) {
        markerPaths = dirs.map { dir ->
            File(dir).mkdirs()
            (dir.trimEnd('/', '\\') + "/" + CrashMarkerFile.FILE_NAME)
                .encodeToByteArray()
                .toNativeHeapCString()
        }
    }

    private val HANDLED_SIGNALS =
        listOf(SIGHUP, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV, SIGSYS)

    /** 在堆上常驻一份 NUL 结尾的 C 字符串 (安装期调用, 处理器内只读)。 */
    private fun ByteArray.toNativeHeapCString(): CPointer<ByteVar> =
        nativeHeap.allocArrayOf(this + byteArrayOf(0))
}

/** 标记文件模式 0644。 */
private const val FILE_MODE = 0x1A4

/** `SIG_DFL` 的 C 表示 (宏 `((__sighandler_t)0)`, cinterop 不为该宏生成常量)。 */
private val SigDfl: CPointer<CFunction<(Int) -> Unit>>? = null

/**
 * 信号处理器 (顶层无捕获函数, 见 `staticCFunction` 约束)。
 *
 * 只写标记文件后交还处置并返回; 任一步失败静默 (崩溃路径上不能再出错)。
 */
private fun onSignal(sig: Int) {
    val text = IosSignalMarker.markerText
    for (path in IosSignalMarker.markerPaths) {
        // 不用 platform.posix.open: 其路径参数是 String, 原因见 signalopen.def
        val fd = legado_signal_open(path, O_WRONLY or O_CREAT or O_TRUNC, FILE_MODE.toUShort())
        if (fd < 0) continue
        write(fd, text.refTo(0), text.size.toULong())
        close(fd)
    }
    signal(sig, SigDfl)
}
