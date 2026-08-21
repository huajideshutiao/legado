package io.legado.app.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.currentLocalOffsetMillis
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * native (iOS/鸿蒙) 端 [AppLogHost] 实现 (两端共用, 无平台差异)。
 *
 * # 与桌面端 registerDesktopAppLogHost 对照
 * - [AppLogHost.write] → 开启"记录日志" (PreferKey.recordLog) 时经 [NativeAppLogStore] 追加到
 *   `{filesDir}/logs/appLog-<epochMillis>.txt`, 即 [NativeCrashLogs] 的崩溃日志来源
 * - [AppLogHost.toast] → [Toasters] (与 app/desktop 端同口径, 两端 registry 已把 toaster
 *   提前到本 host 注册之前)
 * - [AppLogHost.debugPrint] → println (iOS: Xcode 控制台; 鸿蒙: K/N stdout 由 runtime 重定向到 hilog)
 * - [AppLogHost.recordLog] → 读 PreferKey.recordLog (与 app/desktop 端同 key, 默认 false)
 * - [AppLogHost.timeZoneOffsetMillis] → POSIX localtime_r 换算 (对齐 desktop 的
 *   TimeZone.getDefault().getOffset, 日志 UI 按本地时间显示)
 *
 * 注册时机: [registerNativeAppLogHost] 在 registerIosProviders / registerOhosProviders 早期
 * (AppFilesDirs + toaster 之后、任何 AppLog.put 之前)。
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
            // 走统一 Toasters 出口; 注册顺序万一再变时 runCatching 兜底不崩
            runCatching { Toasters.get().toast(message) }
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

/** 宿主启动早期注册一次 (toaster 之后、任何 AppLog.put 之前)。 */
fun registerNativeAppLogHost() {
    NativeAppLogHost.register()
}
