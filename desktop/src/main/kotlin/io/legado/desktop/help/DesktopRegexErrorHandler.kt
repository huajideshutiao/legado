package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.RegexErrorHandler
import io.legado.app.utils.RegexErrorHandlers
import io.legado.desktop.help.DesktopRegexErrorHandler.onTimeoutToast
import io.legado.desktop.help.DesktopRegexErrorHandler.restartApp
import io.legado.desktop.help.DesktopRegexErrorHandler.saveCrashInfo
import io.legado.desktop.startupArgs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * [RegexErrorHandler] 桌面 JVM 实现。
 *
 * 对照 app 端 AndroidRegexErrorHandler:
 * - [onTimeoutToast] → [Toasters.get] 长通知 (替代 appCtx.longToastOnUi)
 * - [saveCrashInfo] → [AppLog.put] (桌面无 CrashHandler 落盘, 走统一日志通道)
 * - [restartApp] → 用 ProcessBuilder 重新拉起当前 JVM (java -cp classpath 主类) 后退出;
 *   新进程带 `--legado-restart-wait=<pid>` 等到本进程退出释放单实例锁后再接管
 *   (见 Main.kt [waitForOldProcessIfRestart]), 避免与 SingleInstanceGuard 竞争
 *
 * 在 desktop Main.kt 经 [registerDesktopRegexErrorHandler] 注入, 供 shared RegexReplacerImpl 在
 * 正则替换超时分支调用。须在任何 webBook 编排层触发 RegexReplacers.get().replace 之前注册。
 */
private object DesktopRegexErrorHandler : RegexErrorHandler {

    /** 失控告警只弹一次, 避免一批规则同时超时时刷屏。 */
    private val runawayNotified = AtomicBoolean(false)

    override fun onTimeoutToast(message: String) {
        // 桌面端经 SystemTray + TrayIcon 显示长通知 (Toasters.jvm.kt)
        Toasters.get().toastLong(message)
    }

    override fun saveCrashInfo(exception: Throwable) {
        // 桌面无 CrashHandler 落盘, 走 AppLog 统一记录
        AppLog.put("Regex replace crash", exception)
    }

    override fun restartApp() {
        // 到这里说明超时后又等了 3 秒线程仍未结束 = 灾难性回溯, Matcher 不响应中断, 该线程会一直吃满一核。
        // 桌面端真正重启: 用 ProcessBuilder 以当前 java + classpath + 启动参数拉起新进程, 再退出当前进程。
        // 新进程凭 --legado-restart-wait=<pid> 等到本进程退出 (shutdown hook 释放单实例锁) 后才接管,
        // 避免新进程把参数转发给正在退出的旧进程后自杀。启动失败 (classpath 不可用等) 退回可见告警。
        if (launchRestartProcess()) {
            // 立即退出: shutdown hook 会关闭单实例监听并删 lock, 新进程等待后接管
            exitProcess(0)
            return
        }
        AppLog.put("正则替换超时 3 秒后线程仍未结束(灾难性回溯), 该线程将持续占用 CPU", null)
        if (runawayNotified.compareAndSet(false, true)) {
            Toasters.get().toastLong(
                "有替换规则/书源正则陷入回溯死循环, 本次替换已中止且该规则已被禁用。" +
                    "该后台线程无法中止, 建议重启应用释放 CPU。"
            )
        }
    }

    /** 主类名 (Compose Desktop application 配置, 开发/打包一致)。 */
    private const val MAIN_CLASS = "io.legado.desktop.MainKt"

    /**
     * 拉起新进程: java -cp {java.class.path} {主类} {原启动参数} --legado-restart-wait=<pid>。
     *
     * - java.home/bin/java 在开发 (gradle run) 与 jpackage 打包 (自带 runtime) 下均存在;
     * - java.class.path 开发期是完整依赖 classpath, 打包期指向应用 jar, 两条路都可用;
     * - 原启动参数经 Main.kt 保存的 [startupArgs] 恢复 (deep link 等参数不丢);
     * - jpackage 的 -Xmx 等 JVM 参数不恢复 (新进程用默认堆, 可接受)。
     */
    private fun launchRestartProcess(): Boolean = runCatching {
        val javaBin = File(
            System.getProperty("java.home"),
            "bin" + File.separator + (if (isWindows()) "java.exe" else "java")
        )
        if (!javaBin.isFile) return false
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }
            ?: return false
        val command = arrayListOf(javaBin.absolutePath, "-cp", classpath, MAIN_CLASS)
        command += startupArgs
        command += "--legado-restart-wait=${ProcessHandle.current().pid()}"
        ProcessBuilder(command).apply {
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        true
    }.getOrDefault(false)

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")
}

/** 桌面端 main 入口注册 [RegexErrorHandler], 须在任何正则替换之前。 */
fun registerDesktopRegexErrorHandler() {
    RegexErrorHandlers.register(DesktopRegexErrorHandler)
}
