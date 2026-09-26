@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.legado.app.help.crash

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGHUP
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGTRAP
import platform.posix.getcwd
import platform.posix.signal

/**
 * 鸿蒙端全局崩溃捕获 (对照 iOS 端 `IosCrashHandler` / 桌面端 `DesktopCrashHandler`)。
 *
 * 本端只多一个 POSIX `signal()` 入口: 鸿蒙 UI 走 CPF 融合渲染 (ArkUI RenderNode),
 * 没有 iOS 的 ObjC 未捕获异常钩子; Kotlin/Native 未捕获异常钩子由
 * [installNativeUnhandledHook] 承担 (与 iOS 同源)。
 *
 * 覆盖的信号与 iOS 端逐条一致 (SIGHUP/SIGILL/SIGTRAP/SIGABRT/SIGBUS/SIGFPE/SIGSEGV):
 * 渲染库与 native 桥的越界崩溃同样只走到这里。
 */
object OhosCrashHandler {

    private var installed = false

    /** 宿主启动最早处调用一次 (见 `registerOhosProviders` 首行)。 */
    fun install() {
        if (installed) return
        installed = true
        installNativeUnhandledHook()
        NativeCrashLogWriter.prepareSignalBuffer()
        for (sig in HANDLED_SIGNALS) {
            signal(sig, staticCFunction(::onSignal))
        }
    }

    private val HANDLED_SIGNALS = listOf(SIGHUP, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV)
}

/** 信号处理器: 顶层无捕获函数 (`staticCFunction` 禁止捕获环境, 写成成员会隐式捕获 this)。 */
private fun onSignal(sig: Int) {
    NativeCrashLogWriter.writeSignal(sig)
}

/** 当前工作目录 (POSIX `getcwd`; 取不到回落 ".")。 */
private fun currentWorkingDir(): String = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    val result = getcwd(buffer, PATH_MAX.toULong())
    if (result == null) "." else result.toKString()
}

/** PATH_MAX 取 4096, 与 OhosAppFilesDir 回退路径同量级。 */
private const val PATH_MAX = 4096

// ===== NativeCrashLogWriter 的鸿蒙 actual =====

/**
 * 进程工作目录。
 *
 * 鸿蒙无 iOS 那样的固定沙盒家目录, 当前工作目录即 napi 未注入 filesDir 时的可用落点,
 * 与 `OhosAppFilesDir` 未注入时回退当前目录的策略同源。
 */
internal actual fun nativeCrashHomeDir(): String = currentWorkingDir()

internal actual fun nativeCrashPlatformParams(): Map<String, String> = buildMap {
    runCatching {
        put("KOTLIN_OS_FAMILY", kotlin.native.Platform.osFamily.name)
        put("ARCH", kotlin.native.Platform.cpuArchitecture.name)
        put("PROCESSOR_COUNT", kotlin.native.Platform.getAvailableProcessors().toString())
    }
}

/** 应用版本名 (ArkTS EntryAbility 经 napi 注入; 未注入为空串)。 */
internal actual fun nativeCrashAppVersionName(): String =
    io.legado.app.napi.OhosNativeBridge.getAppVersionName().orEmpty()

/**
 * 构建号: 鸿蒙侧未经桥接暴露 versionCode (ArkTS 只注入了 versionName), 留空而不猜 ——
 * 崩溃日志里给错版本号比不给更贵。
 */
internal actual fun nativeCrashAppBuildNumber(): String = ""

/** 应用包名: 鸿蒙侧未经桥接暴露 bundleName, 同上留空。 */
internal actual fun nativeCrashAppBundleId(): String = ""
