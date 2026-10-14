@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.legado.app.help.crash

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getcwd

/**
 * 鸿蒙端崩溃捕获 (对照 iOS 端 `IosCrashHandler` / 桌面端 `DesktopCrashHandler`)。
 *
 * 只有一个入口: Kotlin/Native 未捕获异常钩子 ([installNativeUnhandledHook]), 写的是带函数名
 * 的调用栈。
 *
 * # 为什么不再自己捕获原生信号
 * 信号处理器内不允许取调用栈 (展开器均不在 async-signal-safe 清单内), 落盘的现场只有裸地址
 * 与参数头, 对定位零增量, 反而把用户引向一份读不懂的日志。原生崩溃与 ArkTS 崩溃统一改由
 * **系统崩溃事件**承载: ArkTS 侧订阅 `hiAppEvent` 的 `APP_CRASH` (同时覆盖 JsError 与
 * NativeCrash), 系统给的现场含全量线程调用栈与故障日志文件路径, 已符号化。
 */
object OhosCrashHandler {

    private var installed = false

    /** 宿主启动最早处调用一次 (见 `registerOhosProviders` 首行)。 */
    fun install() {
        if (installed) return
        installed = true
        installNativeUnhandledHook()
    }
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
