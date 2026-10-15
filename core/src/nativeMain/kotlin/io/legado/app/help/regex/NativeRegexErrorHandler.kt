package io.legado.app.help.regex

import io.legado.app.constant.AppLog
import io.legado.app.help.crash.NativeCrashLogWriter
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.RegexErrorHandler
import io.legado.app.utils.RegexErrorHandlers
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * 正则替换超时/崩溃副作用的 native (iOS/鸿蒙) 实现, 供下沉的
 * [io.legado.app.utils.NativeRegexReplacer] 在超时分支调用。
 *
 * 对照桌面 [io.legado.desktop.help.DesktopRegexErrorHandler]:
 * - [onTimeoutToast] → [Toasters] 长提示 (替代 appCtx.longToastOnUi)
 * - [saveCrashInfo] → 崩溃日志落盘 ([NativeCrashLogWriter.writeException]) + [AppLog] 内存日志
 * - [restartApp] → 见下
 *
 * # restartApp 的 native 平台约束
 * native 端**无法**真正重启进程: iOS 不允许应用以编程方式重启自身 (只能引导用户手动重启),
 * 鸿蒙也没有等价的现成能力; 桌面端的 ProcessBuilder 拉起新进程方案在两端都不可用。
 * 故降级为 "可见告警 + 日志": 失控线程仍在占 CPU, 只能提示用户手动重启, 不静默。
 *
 * # 注册时机
 * 由宿主在 [registerIosProviders] / [registerOhosProviders] 早期调用
 * [registerNativeRegexErrorHandler] 注册一次, 须在任何正则替换 (RegexReplacers.replace) 之前 ——
 * 未注册时 [RegexErrorHandlers.getOrNull] 返回 null, 超时提示/落盘/告警全部静默跳过。
 */
private object NativeRegexErrorHandler : RegexErrorHandler {

    /** 失控告警只弹一次, 避免一批规则同时超时时刷屏 (与桌面端 runawayNotified 同口径)。 */
    private val notifyLock = SynchronizedObject()
    private var runawayNotified = false

    override fun onTimeoutToast(message: String) {
        // Toaster 未注册时 (极早期) 不能因提示失败再抛异常, 与 NativeVerificationUiProvider 同款 runCatching
        runCatching { Toasters.get().toastLong(message) }
    }

    override fun saveCrashInfo(exception: Throwable) {
        // 落盘走崩溃日志目录 (与 CrashHandler.saveCrashInfo2File 同目的地:
        // {filesDir}/log, 用户可经系统"文件"App 取出), 同时进 AppLog 内存通道
        runCatching {
            NativeCrashLogWriter.writeException(
                kind = "regex-timeout",
                detail = exception.stackTraceToString(),
            )
        }
        AppLog.put("正则替换崩溃", exception)
    }

    override fun restartApp() {
        // 到这里说明超时后又等了 3 秒线程仍未结束 = 灾难性回溯, Matcher 不响应中断, 该线程会一直吃满一核。
        // native 无法程序化重启进程 (iOS 平台约束 / 鸿蒙无等价能力), 降级为可见告警 + 日志。
        AppLog.put("正则替换超时 3 秒后线程仍未结束(灾难性回溯), 该线程将持续占用 CPU", null)
        val first = synchronized(notifyLock) {
            if (runawayNotified) false else { runawayNotified = true; true }
        }
        if (first) {
            runCatching {
                Toasters.get().toastLong(
                    "有替换规则/书源正则陷入回溯死循环, 本次替换已中止且该规则已被禁用。" +
                        "该后台线程无法中止, 请手动重启应用以释放 CPU。"
                )
            }
        }
    }
}

/** iOS/鸿蒙宿主启动早期注册一次 (任何正则替换之前)。 */
fun registerNativeRegexErrorHandler() {
    RegexErrorHandlers.register(NativeRegexErrorHandler)
}
