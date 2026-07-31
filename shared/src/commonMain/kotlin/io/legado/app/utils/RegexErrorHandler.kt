package io.legado.app.utils

import kotlin.concurrent.Volatile

/**
 * 正则替换超时/异常的平台错误处理注入点。
 *
 * 原 app 端 RegexExtensions.kt 直接调用 appCtx.longToastOnUi /
 * CrashHandler.saveCrashInfo2File / appCtx.restart() 等 Android 专属 API,
 * 下沉 shared 后改由本接口经 [RegexErrorHandlers] 注入, app 端注册 Android 实现。
 *
 * 未注册的宿主 (如 native 端) 经 [RegexErrorHandlers.getOrNull] 拿到 null,
 * 下沉的替换流程静默跳过 toast/crash/restart。
 */
interface RegexErrorHandler {
    /** 替换超时提示 (Android: appCtx.longToastOnUi)。 */
    fun onTimeoutToast(message: String)

    /** 保存异常信息 (Android: CrashHandler.saveCrashInfo2File)。 */
    fun saveCrashInfo(exception: Throwable)

    /**
     * 超时后再等 3 秒仍未结束时的兜底 (Android: appCtx.restart())。
     * 桌面端不重启进程, 改为可见告警, 见 DesktopRegexErrorHandler.restartApp。
     */
    fun restartApp()
}

/**
 * [RegexErrorHandler] 容器。宿主启动早期注册一次 (任何正则替换之前)。
 *
 * 模式参考 [RegexReplacers] / AppDbProviders。app 端在 App.onCreate 经
 * `registerAndroidRegexErrorHandler` 注册, 桌面端在 Main.kt 经 `registerDesktopRegexErrorHandler` 注册。
 */
object RegexErrorHandlers {
    @Volatile
    private var impl: RegexErrorHandler? = null

    /** 宿主启动早期注册一次 (任何正则替换之前)。 */
    fun register(handler: RegexErrorHandler) {
        impl = handler
    }

    /** 已注册实现, 未注册返回 null (调用方静默跳过平台副作用)。 */
    fun getOrNull(): RegexErrorHandler? = impl
}
