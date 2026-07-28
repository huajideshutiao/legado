package io.legado.desktop.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost

// Debug 日志开关: -Dlegado.desktop.debug=true 开启 stdout 调试输出
// 生产环境静默, 减少 println 同步 flush 开销 (与 Main.kt 同一开关模式)
private val desktopDebug = System.getProperty("legado.desktop.debug")?.toBoolean() == true

private fun debugLog(msg: String) {
    if (desktopDebug) println(msg)
}

/**
 * 桌面端 [AppLogHost] 实现: 桥接到 stdout (println), 不依赖 Android 的 LogUtils/toastOnUi/logcat。
 *
 * - [write] → println 输出到控制台 (供运行期日志查看)
 * - [toast] → println (桌面端无 Android Toast, 用 stdout 替代, 行为透明)
 * - [debugPrint] → println (DEBUG 模式下输出, 替代 Android logcat)
 * - [recordLog] → true (与 app 端 AppConfig.recordLog 默认值一致, 让 putDebug 真正落盘)
 * - [currentTimeMillis] → System.currentTimeMillis()
 *
 * 注册时机: desktop main 入口早期, 任何 shared commonMain 调用 AppLog.put 之前。
 * 模式参考 app 端 `registerAndroidAppLogHost` (AppLogAndroid.kt)。
 *
 * 未注册时 AppLog 仍可用 (纯环形列表), 但副作用 (write/toast/debugPrint) 静默 no-op,
 * 表现为 shared commonMain 的异常/报错完全无输出, 排查困难。本注册让桌面端日志可见。
 */
private val desktopAppLogHost = object : AppLogHost {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun timeZoneOffsetMillis(): Long =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

    override val recordLog: Boolean get() = true

    override fun write(tag: String, message: String) {
        // 桌面端无文件落盘 LogUtils, 直接 println 到控制台
        debugLog("[$tag] $message")
    }

    override fun toast(message: String) {
        // 桌面端无 Android Toast, 用 println 替代 (UI 层另有 DesktopToaster 弹通知)
        debugLog("[AppLog.toast] $message")
    }

    override fun debugPrint(tag: String, message: String, throwable: Throwable?) {
        // DEBUG 模式下输出 (替代 Android logcat Log.e)
        debugLog("[$tag] $message")
        throwable?.printStackTrace()
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopAppLogHost() {
    AppLog.registerHost(desktopAppLogHost)
}
