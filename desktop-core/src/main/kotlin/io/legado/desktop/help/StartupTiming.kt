package io.legado.desktop.help

import io.legado.app.help.file.desktopAppCacheDir
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端启动阶段计时打点 (诊断用, 不改任何行为)。
 *
 * 为什么要它: 3.26.09141544 实测冷启动到主窗口 2.84~3.02s, 而外部只能观测"进程起→窗口出现"
 * 这一个数, 分不清是平台税 (同 runtime 起一个纯 AWT 窗口实测 298ms)、阶段0/1 的 provider 注册,
 * 还是首屏组合本身。之前靠 `-Xlog:class+load` 的类加载时间戳反推阶段边界, 只能定位到
 * "闪屏 0.96s 才出现 / 阶段1 占 0.4s / 首屏 UI 类铺加载占 0.8s / 书架组合到出窗占 0.7s" 这个粒度,
 * 再往下必须应用内打点。
 *
 * 落盘: `{cacheDir}/startup-timing.log`, 每条 mark 立即追加 —— 诊断时常用 taskkill 强杀进程,
 * 走 shutdownHook/延迟写会丢数据, 所以刻意不缓冲。
 * 同时恒打 stderr: 便携版由 `legado.exe` 启动时 stderr 不显示, 但用 `runtime/bin/java.exe`
 * 直起 (排查用的常规路子) 能当场看到。
 *
 * 用法: [begin] 在 `main()` 首行调一次, 之后 [mark] 打阶段名; 名称自带相对耗时, 读日志无需计算器。
 */
object StartupTiming {

    private val t0 = AtomicLong(0L)
    private val last = AtomicLong(0L)

    @Volatile
    private var logFile: File? = null

    private fun nowNanos(): Long = System.nanoTime()

    /** `main()` 首行调用 (便携根目录已定位后调 [attachLog] 才能落盘)。 */
    fun begin() {
        val n = nowNanos()
        t0.set(n)
        last.set(n)
    }

    /**
     * 便携/缓存目录就绪后调用一次, 让后续 mark 落盘。
     * 未调用也不影响计时 (只少一份文件)。
     */
    fun attachLog() {
        if (logFile != null) return
        logFile = runCatching { File(desktopAppCacheDir(), "startup-timing.log") }.getOrNull()
    }

    /**
     * 打一个阶段点。
     *
     * @param name 阶段名 (进日志)
     */
    fun mark(name: String) {
        val base = t0.get()
        if (base == 0L) return
        val n = nowNanos()
        val rel = (n - base) / 1_000_000L
        val seg = (n - last.get()) / 1_000_000L
        last.set(n)
        val line = "startup +${rel}ms (本段 ${seg}ms) $name"
        runCatching { System.err.println(line) }
        runCatching { logFile?.appendText(line + System.lineSeparator()) }
    }
}
