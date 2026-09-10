package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.desktop.restartMainClass
import io.legado.desktop.startupArgs
import java.io.File

/**
 * 桌面端进程重启 (对照 app 端 `Context.restart()`)。
 *
 * 用当前 java + classpath + 原启动参数拉起新进程: java.home/bin/java 在开发 (gradle run)
 * 与 jpackage 打包 (自带 runtime) 下均存在; java.class.path 开发期是完整依赖 classpath,
 * 打包期指向应用 jar, 两条路都可用; 原启动参数经 Main.kt 保存的 [startupArgs] 恢复
 * (deep link 等参数不丢); jpackage 的 -Xmx 等 JVM 参数不恢复 (新进程用默认堆, 可接受)。
 *
 * 新进程带 `--legado-restart-wait=<pid>` 等到本进程退出释放单实例锁后再接管
 * (见 Main.kt waitForOldProcessIfRestart), 避免与 SingleInstanceGuard 竞争。
 *
 * 调用方拿到 true 后应立即 `exitProcess(0)` —— shutdown hook 会关闭单实例监听并删 lock,
 * 新进程等待后接管。返回 false 表示新进程没拉起来, 此时**不要**退出当前进程。
 *
 * 两个调用方: 切语言 ([io.legado.desktop.ui.DesktopPlatformCapabilities.applyAppLanguage])
 * 与正则回溯失控兜底 (DesktopRegexErrorHandler.restartApp)。
 *
 * 主类名经 [restartMainClass] 注入 (默认 io.legado.desktop.MainKt; headless 入口覆写为
 * io.legado.headless.MainKt, 避免无头进程重启时拉起桌面 UI)。
 */
fun launchRestartProcess(): Boolean = runCatching {
    val javaBin = File(
        System.getProperty("java.home"),
        "bin" + File.separator + (if (isWindowsOs()) "java.exe" else "java")
    )
    if (!javaBin.isFile) return false
    val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }
        ?: return false
    val command = arrayListOf(javaBin.absolutePath, "-cp", classpath, restartMainClass)
    command += startupArgs
    command += "--legado-restart-wait=${ProcessHandle.current().pid()}"
    ProcessBuilder(command).apply {
        redirectOutput(ProcessBuilder.Redirect.INHERIT)
        redirectError(ProcessBuilder.Redirect.INHERIT)
    }.start()
    true
}.onFailure {
    AppLog.put("拉起新进程失败: ${it.localizedMessage}", it)
}.getOrDefault(false)

private fun isWindowsOs(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("win")
