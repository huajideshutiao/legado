@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crash

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSBundle
import platform.Foundation.NSException
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.UIKit.UIDevice
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGHUP
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGTRAP
import platform.posix.signal

/**
 * iOS 端全局崩溃捕获 (对照 app 端 `io.legado.app.help.CrashHandler` /
 * 桌面端 `DesktopCrashHandler`)。三个入口都汇到 [NativeCrashLogWriter]:
 *
 * 1. `NSSetUncaughtExceptionHandler` —— ObjC 未捕获异常 (`NSException`), 典型如未注册的
 *    `BGTaskScheduler` 标识、KVC 失败、UIKit 断言。
 * 2. Kotlin/Native 未捕获异常钩子 —— 由 [installNativeUnhandledHook] 安装 (与鸿蒙同源)。
 * 3. POSIX `signal()` —— SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP 这类**纯原生崩溃**。
 *    Metal/Skia/ObjC 层的越界通常只走到这里, 前两个钩子都抓不到。
 *
 * # 为什么必须留信号兜底
 * "点开闪一下就没了"在 iOS 上最常见的形态就是原生信号崩溃, 只装前两个钩子会得到
 * "有钩子但一条日志都没有"的结果 —— 与不装无异。
 *
 * # 与系统 `.ips` 的关系
 * 处理器内写完日志直接 return, 不恢复 `SIG_DFL`、不重发信号: 内核按原信号语义继续处置,
 * 系统 ReportCrash 照常生成 `.ips`。本机制是**在沙盒里多留一份证据**, 不是替代系统报告 ——
 * 用户不必连电脑导日志, 直接经"文件" App 取 `Documents/logs/crash/` 即可。
 */
object IosCrashHandler {

    private var installed = false

    /**
     * 宿主启动最早处调用一次 (见 `IosProviderRegistry.registerIosProviders` 首行)。
     *
     * 装在最前是因为注册链本身就会抛: 任一 provider 注册失败会中断 `registerIosProviders`,
     * 后面未注册的 provider (如 ScreenInfoProviders) 后续会硬崩。捕获装在链首才能留下现场。
     */
    fun install() {
        if (installed) return
        installed = true
        installNativeUnhandledHook()
        NSSetUncaughtExceptionHandler(staticCFunction(::onUncaughtNsException))
        NativeCrashLogWriter.prepareSignalBuffer()
        for (sig in HANDLED_SIGNALS) {
            signal(sig, staticCFunction(::onSignal))
        }
    }

    private val HANDLED_SIGNALS = listOf(SIGHUP, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV)
}

/**
 * ObjC 未捕获异常处理器。
 *
 * 必须是**顶层无捕获函数** —— `staticCFunction` 禁止捕获环境, 写成 object 成员会隐式捕获
 * `this` 直接编译失败。故这里只把异常交给 writer, 不读任何实例状态。
 */
private fun onUncaughtNsException(exception: NSException?) {
    val detail = buildString {
        append("NSException\n")
        append("name=").append(exception?.name ?: "(null)").append('\n')
        append("reason=").append(exception?.reason ?: "(null)").append('\n')
        append("thread=").append(currentThreadName()).append('\n')
        val symbols = runCatching {
            exception?.callStackSymbols?.joinToString("\n") { it.toString() }
        }.getOrNull().orEmpty()
        if (symbols.isNotEmpty()) append("callStackSymbols:\n").append(symbols).append('\n')
        val userInfo = runCatching { exception?.userInfo?.toString() }.getOrNull()
        if (!userInfo.isNullOrEmpty()) append("userInfo=").append(userInfo).append('\n')
    }
    NativeCrashLogWriter.writeException("objc-uncaught", detail)
}

/** 信号处理器 (同为顶层无捕获函数, 原因见 [onUncaughtNsException])。 */
private fun onSignal(sig: Int) {
    NativeCrashLogWriter.writeSignal(sig)
}

private fun currentThreadName(): String =
    runCatching { NSThread.currentThread.name() ?: "unnamed" }.getOrDefault("unknown")

// ===== NativeCrashLogWriter 的 iOS actual =====

internal actual fun nativeCrashHomeDir(): String = NSHomeDirectory() + "/Documents"

internal actual fun nativeCrashPlatformParams(): Map<String, String> = buildMap {
    runCatching {
        val device = UIDevice.currentDevice
        put("SYSTEM", device.systemName + " " + device.systemVersion)
        put("MODEL", device.model)
        put("NAME", device.name)
        put("PROCESSOR_COUNT", NSProcessInfo.processInfo.processorCount.toString())
        put("PHYSICAL_MEMORY", NSProcessInfo.processInfo.physicalMemory.toString())
    }
}

internal actual fun nativeCrashAppVersionName(): String = bundleValue("CFBundleShortVersionString")

internal actual fun nativeCrashAppBuildNumber(): String = bundleValue("CFBundleVersion")

internal actual fun nativeCrashAppBundleId(): String = bundleValue("CFBundleIdentifier")

/**
 * 读主 Bundle 的一个字符串键 (崩溃参数头用)。
 *
 * 崩溃现场读 Bundle 有失败风险, 故整条包在 runCatching 里, 取不到给空串而不是抛。
 */
private fun bundleValue(key: String): String = runCatching {
    NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""
}.getOrDefault("")
