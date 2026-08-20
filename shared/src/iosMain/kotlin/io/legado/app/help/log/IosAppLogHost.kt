package io.legado.app.help.log

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogHost
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.currentLocalOffsetMillis
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * iOS 端 [AppLogHost] 实现: 落盘走 [NativeAppLogStore] (与鸿蒙端同一份, 见 nativeMain)。
 *
 * # 与鸿蒙端 OhosAppLogHost / 桌面端 registerDesktopAppLogHost 对照
 * - [write] → 开启"记录日志" (PreferKey.recordLog) 时追加到
 *   `{filesDir}/logs/appLog-<epochMillis>.txt`, 即 [NativeCrashLogs] 的崩溃日志来源
 * - [toast] → 复用已注册的 [Toasters] (IosToaster, 已有防重入与 NSLog 兜底);
 *   本 host 早于 registerIosToaster 注册, 未就绪时 runCatching 吞掉
 * - [debugPrint] → println (Xcode 控制台可见)
 * - [recordLog] → 读 PreferKey.recordLog (与 app/desktop/鸿蒙端同 key, 默认 false)
 * - [timeZoneOffsetMillis] → nativeMain 的 POSIX localtime_r 换算 (对齐 desktop 的
 *   TimeZone.getDefault().getOffset, 日志 UI 按本地时间显示)
 *
 * 注册时机: [registerIosAppLogHost] 在 registerIosProviders 早期 (AppFilesDirs 之后、
 * 任何 AppLog.put 之前)。
 */
object IosAppLogHost {

    private val host = object : AppLogHost {
        override fun currentTimeMillis(): Long = systemCurrentTimeMillis()

        override fun timeZoneOffsetMillis(): Long = currentLocalOffsetMillis()

        override val recordLog: Boolean
            get() = runCatching {
                PreferenceProviders.get().getBoolean(PreferKey.recordLog, false)
            }.getOrDefault(false)

        override fun write(tag: String, message: String) {
            // recordLog 门控 (与 app/desktop/鸿蒙端 AppLog.write 一致, 默认 false 不落盘)
            if (!recordLog) return
            NativeAppLogStore.append(tag, message)
        }

        override fun toast(message: String) {
            // Toaster 未注册时静默 (本 host 注册更早)
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

/** iOS 宿主启动早期注册一次 (任何 AppLog.put 之前, 见 registerIosProviders 1.1 步)。 */
fun registerIosAppLogHost() {
    IosAppLogHost.register()
}
