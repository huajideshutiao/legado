package io.legado.desktop

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.help.coroutine.registerJvmDebugState
import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.ui.OpenUrlProvider
import io.legado.app.utils.toBrowseUri
import io.legado.app.web.WebServerManager
import io.legado.desktop.cli.resolveExplicitDataDir
import io.legado.desktop.help.DataRootLock
import io.legado.desktop.help.DesktopCrashHandler
import io.legado.desktop.help.DesktopCrashLogDirs
import io.legado.desktop.ui.DesktopWindowHandle
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val TAG = "legado-desktop"

/**
 * 桌面端纯命令行模式 (`--server`): 不建窗口、不初始化 AWT/Compose/mediamp, 启动 Web 服务并常驻。
 *
 * 与桌面 GUI 共用 [main] 之前同一段初始化 (加密 provider / 运行时环境 / 崩溃处理), provider
 * 注册也走同一份序列 ([registerDesktopProviderGraph] / [registerDesktopSecondaryProviders]) ——
 * 命令行模式不裁剪能力, 差异只在"不执行窗口相关动作"。
 *
 * 跑法:
 * - 便携包: 程序目录下 `legado-server.cmd` (无参数时数据落 exe 同级 data/)
 * - 开发期: `./gradlew :desktop:run -Plegado.cli`
 * - 任意产物: `java -cp "<app 目录内全部 jar>" io.legado.desktop.MainKt --server [--data-dir=<path>]`
 *
 * 启动失败即非零退出: 数据目录已被其他实例占用 = 2, Web 服务无法启动 = 1。成功时把访问地址
 * 打到 stdout, 供部署脚本/面板取用。
 */
fun runServerMode(args: Array<String>) {
    // 无窗口进程: 必须在任何 AWT 使用之前置位, 否则无 DISPLAY 的服务器会在离屏图形库初始化时失败
    System.setProperty("java.awt.headless", "true")
    registerJvmDebugState(System.getProperty("legado.debug")?.toBoolean() == true)
    // 数据根: --data-dir / -Dlegado.data.dir 优先, 其余与 GUI 同一套 (portable.txt / 系统目录)
    initDesktopRuntimeEnvironment(resolveExplicitDataDir(args))
    // 重启链复现启动参数时模式与数据根都要在 (launchRestartProcess 只用主类 + startupArgs)
    startupArgs = args
    // 与 GUI 互斥: 同一数据目录同时只允许一个进程 (GUI 那一侧在同名锁上做对称判定)
    if (!DataRootLock.tryAcquire()) {
        System.err.println(
            "启动失败: 数据目录已被另一个实例占用 (${desktopAppRootDir()}), 请先关闭它"
        )
        exitProcess(2)
    }
    if (DesktopCrashLogDirs.collectJvmFatalErrorLogs()) {
        runCatching { CrashMarkerProviders.get().mark() }
        println("已收集上次运行的 JVM 致命错误现场")
    }
    DesktopCore.registerEarlyProviders()
    DesktopCore.registerRestProviders()
    registerDesktopProviderGraph(DesktopWindowHandle())
    runBlocking {
        // 命令行模式没有界面: 书源 openUrl 无从确认, 也不该在服务器上拉起浏览器 (一律拒绝并记日志)
        registerDesktopSecondaryProviders(ServerModeOpenUrlProvider)
    }
    val result = WebServerManager.startWithResult()
    if (result.addresses.isEmpty()) {
        System.err.println(
            "启动失败: Web 服务无法启动 " + (result.errorMsg ?: "(无可用网络地址)")
        )
        DataRootLock.release()
        exitProcess(1)
    }
    println("legado server ready: ${result.addresses.joinToString(", ")}")
    Runtime.getRuntime().addShutdownHook(
        Thread({
            runCatching { WebServerManager.stop() }
            // 桌面 GUI 的退出链 (exitProgram) 不关库, 命令行模式必须显式收尾:
            // 被 SIGTERM 打断时 Room 的 -wal 需要正常关闭才会合并
            runCatching { AppDatabaseProviders.get().appDb.close() }
                .onFailure { AppLog.put("数据库关闭失败: ${it.message}", it) }
            DataRootLock.release()
            println("legado server stopped")
        }, "legado-server-shutdown")
    )
    CountDownLatch(1).await()
}

/**
 * 命令行模式的外部链接策略: 一律拒绝。
 *
 * GUI 走 [io.legado.desktop.help.ui.DesktopOpenUrlProviderImpl] 的确认框 (用户确认后才交给系统
 * 浏览器); 服务器上没有可确认的人, 直开浏览器也无意义, 故拒绝并留日志便于排查书源行为。
 */
private object ServerModeOpenUrlProvider : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int,
    ) {
        // toBrowseUri 只用于区分"非法 URL"与"合法但被拒绝"的日志, 两者都不放行
        val scheme = runCatching { url.toBrowseUri()?.scheme?.lowercase() }.getOrNull()
        AppLog.put(
            "命令行模式拒绝打开外部链接 (scheme=${scheme ?: "非法"}): $url",
            tag = TAG,
        )
    }
}
