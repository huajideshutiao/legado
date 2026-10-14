@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crash

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSBundle
import platform.Foundation.NSException
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSThread
import platform.UIKit.UIDevice

/**
 * iOS 端崩溃捕获 (对照 app 端 `io.legado.app.help.CrashHandler` / 桌面端 `DesktopCrashHandler`)。
 * 两个入口都汇到 [NativeCrashLogWriter], 写的都是**带函数名的调用栈**:
 *
 * 1. `NSSetUncaughtExceptionHandler` —— ObjC 未捕获异常 (`NSException`), 典型如未注册的
 *    `BGTaskScheduler` 标识、KVC 失败、UIKit 断言。系统已给出符号化好的调用栈。
 * 2. Kotlin/Native 未捕获异常钩子 —— 由 [installNativeUnhandledHook] 安装 (与鸿蒙同源),
 *    K/N 从自身元数据解析出函数名与行号。
 *
 * 3. POSIX `signal()` —— SIGSEGV/SIGABRT/SIGBUS 这类**纯原生崩溃**。处理器内不允许取调用栈
 *    (展开器均不在 async-signal-safe 清单内), 故它**只置异常退出标记** ([IosSignalMarker]),
 *    不写现场: 现场由系统报告承载, 用户经“设置 → 隐私与安全性 → 分析与改进 → 分析数据”取
 *    `.ips` (含完整调用栈)。置标记的作用是让下次启动能提示"上次崩了"并指向那份报告。
 *
 * # 系统报告需要符号表
 * `.ips` 里本应用自身的帧是裸地址, 要用与安装包**同一次构建**产出的 dSYM 才能翻译成函数名。
 * 本仓不归档 dSYM, 故那份报告只能读到模块与偏移; 但那已比自捕的裸地址有用 ——
 * 至少能看出崩在哪个模块。
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
        // 原生信号崩溃只置异常退出标记 (现场由系统报告承载), 见 [IosSignalMarker]
        IosSignalMarker.install(NativeCrashLogWriter.crashCandidateDirs())
    }
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
