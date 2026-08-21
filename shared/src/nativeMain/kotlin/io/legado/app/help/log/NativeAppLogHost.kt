package io.legado.app.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.currentLocalOffsetMillis
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * native (iOS/鸿蒙) 端 [AppLogHost] 实现 (两端原本逐份重复, 唯一差异是 toast 出口)。
 *
 * # 与桌面端 registerDesktopAppLogHost 对照
 * - [AppLogHost.write] → 开启"记录日志" (PreferKey.recordLog) 时经 [NativeAppLogStore] 追加到
 *   `{filesDir}/logs/appLog-<epochMillis>.txt`, 即 [NativeCrashLogs] 的崩溃日志来源
 * - [AppLogHost.toast] → 唯一平台差异, 见 [nativeAppLogToast]
 * - [AppLogHost.debugPrint] → println (iOS: Xcode 控制台; 鸿蒙: K/N stdout 由 runtime 重定向到 hilog)
 * - [AppLogHost.recordLog] → 读 PreferKey.recordLog (与 app/desktop 端同 key, 默认 false)
 * - [AppLogHost.timeZoneOffsetMillis] → POSIX localtime_r 换算 (对齐 desktop 的
 *   TimeZone.getDefault().getOffset, 日志 UI 按本地时间显示)
 *
 * 注册时机: [registerNativeAppLogHost] 在 registerIosProviders / registerOhosProviders 早期
 * (AppFilesDirs 之后、任何 AppLog.put 之前)。
 */
object NativeAppLogHost {

    private val host = object : AppLogHost {
        override fun currentTimeMillis(): Long = systemCurrentTimeMillis()

        override fun timeZoneOffsetMillis(): Long = currentLocalOffsetMillis()

        override val recordLog: Boolean
            get() = runCatching {
                PreferenceProviders.get().getBoolean(PreferKey.recordLog, false)
            }.getOrDefault(false)

        override fun write(tag: String, message: String) {
            // recordLog 门控 (与 app/desktop 端 AppLog.write 一致, 默认 false 不落盘)
            if (!recordLog) return
            NativeAppLogStore.append(tag, message)
        }

        override fun toast(message: String) {
            nativeAppLogToast(message)
        }

        override fun debugPrint(tag: String, message: String, throwable: Throwable?) {
            println("[$tag] $message")
            throwable?.printStackTrace()
        }
    }

    /** 注册一次 (幂等)。 */
    fun register() {
        AppLog.registerHost(host)
    }
}

/** 宿主启动早期注册一次 (任何 AppLog.put 之前)。 */
fun registerNativeAppLogHost() {
    NativeAppLogHost.register()
}

/**
 * AppLog 的 toast 出口: 本 host 注册远早于各端 Toaster, 故不走 [io.legado.app.help.toast.Toasters]
 * 统一口径 —— iOS 端未就绪时静默, 鸿蒙端直接走 napi 桥 (桥未注入时内部降级 println)。
 */
internal expect fun nativeAppLogToast(message: String)
