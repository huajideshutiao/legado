package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.RegexErrorHandler
import io.legado.app.utils.RegexErrorHandlers

/**
 * [RegexErrorHandler] 桌面 JVM 实现。
 *
 * 对照 app 端 AndroidRegexErrorHandler:
 * - [onTimeoutToast] → [Toasters.get] 长通知 (替代 appCtx.longToastOnUi)
 * - [saveCrashInfo] → [AppLog.put] (桌面无 CrashHandler 落盘, 走统一日志通道)
 * - [restartApp] → 桌面无 appCtx.restart() 等价语义, 仅记录日志 (避免误杀用户数据)
 *
 * 在 desktop Main.kt 经 [registerDesktopRegexErrorHandler] 注入, 供 shared RegexReplacerImpl 在
 * 正则替换超时分支调用。须在任何 webBook 编排层触发 RegexReplacers.get().replace 之前注册。
 */
private object DesktopRegexErrorHandler : RegexErrorHandler {

    override fun onTimeoutToast(message: String) {
        // 桌面端经 SystemTray + TrayIcon 显示长通知 (Toasters.jvm.kt)
        Toasters.get().toastLong(message)
    }

    override fun saveCrashInfo(exception: Throwable) {
        // 桌面无 CrashHandler 落盘, 走 AppLog 统一记录
        AppLog.put("Regex replace crash", exception)
    }

    override fun restartApp() {
        // 桌面端无 appCtx.restart() 等价语义, 仅记录日志 (避免误杀用户数据)
        AppLog.put("Regex replace timeout, restartApp requested (desktop no-op)", null)
    }
}

/** 桌面端 main 入口注册 [RegexErrorHandler], 须在任何正则替换之前。 */
fun registerDesktopRegexErrorHandler() {
    RegexErrorHandlers.register(DesktopRegexErrorHandler)
}
