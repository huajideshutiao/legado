package io.legado.app.help.crash

import com.script.quickjs.QuickJsNative
import io.legado.app.constant.AppLog

/**
 * Android 端原生崩溃捕获安装入口 (对照 iOS/鸿蒙的系统崩溃报告通道)。
 *
 * # 为什么需要
 * Kotlin 的 `Thread.setDefaultUncaughtExceptionHandler` (见 app 端
 * [io.legado.app.help.CrashHandler]) 只覆盖 JVM 异常; NDK/JNI 侧的越界、野指针、栈溢出等
 * 直接以 POSIX 信号终止进程, 一条日志都不留。Android 因 debuggerd 会在现场保留模块与内存映射表,
 * 裸地址仍可定位, 故保留自捕; iOS/鸿蒙无此机制, 改由系统报告承担 (自捕只有裸地址)。
 *
 * # 实现位置
 * 信号处理器在 `legado_quickjs.so` 内 (modules/quickjs-android-native 的
 * `native_crash_handler.cpp`): 它是 Android 端唯一常驻的进程级 native 库且已有 JNI 入口
 * (`QuickJsNative`), 无需为捕获另引第三方崩溃库或新建 native 模块。
 *
 * # 与 ART/debuggerd 的关系
 * ART 的 sigchain 把用户 handler 记进链尾, 先跑 ART fault handler 与 debuggerd, 再链到
 * 本处理器; 处理器只记录, 返回后信号按原语义继续 ⇒ 系统 tombstone 照常生成,
 * ART 的隐式空指针检查不受影响 (依据见 `native_crash_handler.cpp` 顶部注释)。
 *
 * # 目录由调用方给
 * 与 app 端 [io.legado.app.help.CrashHandler.saveCrashInfo2File] 的外部缓存那份同处
 * (`{externalCacheDir}/crash`), 故原生崩溃与 Java 崩溃在"关于 → 崩溃日志"里一并可见;
 * 标记文件同目录, 供启动期提示读取。
 * 目录参数经调用方传入而非本模块自取: `:core` 看不到 `:app` 的 Application 与 Context 扩展。
 */
object AndroidNativeCrashHandler {

    /**
     * 安装一次 (幂等)。
     *
     * 时机: 越早越能覆盖启动期崩溃, 但 native 库随 `QuickJsNative` 的 init 块加载,
     * 早于它的调用会 UnsatisfiedLinkError —— 由调用方保证在 JS 引擎首次使用之后。
     *
     * @param crashDir 崩溃日志目录绝对路径 (与 [io.legado.app.help.CrashHandler] 同一处)
     */
    fun install(crashDir: String) {
        runCatching {
            QuickJsNative.nativeInstallCrashHandler(crashDir)
        }.onFailure {
            AppLog.put("原生崩溃捕获安装失败\n${it.localizedMessage}", it)
        }
    }
}
