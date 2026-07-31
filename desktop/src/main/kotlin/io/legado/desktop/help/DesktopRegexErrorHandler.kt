package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.RegexErrorHandler
import io.legado.app.utils.RegexErrorHandlers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RegexErrorHandler] 桌面 JVM 实现。
 *
 * 对照 app 端 AndroidRegexErrorHandler:
 * - [onTimeoutToast] → [Toasters.get] 长通知 (替代 appCtx.longToastOnUi)
 * - [saveCrashInfo] → [AppLog.put] (桌面无 CrashHandler 落盘, 走统一日志通道)
 * - [restartApp] → 不照抄 appCtx.restart(), 改为可见告警 (理由见该方法注释)
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
        // 桌面不照抄 Android 的 appCtx.restart(): 自杀重启会丢掉下载/导入/缓存队列, 且与
        // SingleInstanceGuard 的 lock + shutdown hook 竞争 (新进程会把参数转发给正在退出的旧进程后自杀,
        // 表现为"应用直接消失")。调用链此时已被 block.cancel(RegexTimeoutException) 正常失败返回,
        // 上层 (getDisplayTitle / ContentProcessorShared) 也已禁用该规则, 故这里只做可见告警。
        AppLog.put("正则替换超时 3 秒后线程仍未结束(灾难性回溯), 该线程将持续占用 CPU", null)
        if (runawayNotified.compareAndSet(false, true)) {
            Toasters.get().toastLong(
                "有替换规则/书源正则陷入回溯死循环, 本次替换已中止且该规则已被禁用。" +
                    "该后台线程无法中止, 建议重启应用释放 CPU。"
            )
        }
    }
}

/** 桌面端 main 入口注册 [RegexErrorHandler], 须在任何正则替换之前。 */
fun registerDesktopRegexErrorHandler() {
    RegexErrorHandlers.register(DesktopRegexErrorHandler)
}
