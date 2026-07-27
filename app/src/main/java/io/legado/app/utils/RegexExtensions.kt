package io.legado.app.utils

import io.legado.app.help.CrashHandler
import splitties.init.appCtx

/**
 * 带有超时检测的正则替换 — app 端薄壳。
 *
 * 核心实现已下沉 shared jvmAndAndroidMain 的 [RegexReplacerImpl]
 * (Coroutine.async / JsEngines / JsBindings / Matcher / runBlockingInScope 全部在 shared 可用);
 * Android 专属的 longToastOnUi / CrashHandler.saveCrashInfo2File / appCtx.restart()
 * 经 [RegexErrorHandler] 注入, 由 [registerAndroidRegexErrorHandler] 注册。
 *
 * 本扩展保留以维持调用方零改动 (WebBookProvidersImpl.RegexReplacer.replace 直接调用本扩展)。
 */
fun CharSequence.replace(regex: Regex, replacement: String, timeout: Long): String =
    RegexReplacerImpl.replace(this, regex, replacement, timeout)

/**
 * 注册 app 端 Android 专属 [RegexErrorHandler], 供 shared [RegexReplacerImpl] 在
 * 替换超时分支调用 (longToastOnUi / saveCrashInfo2File / restart)。
 *
 * 调用时机: App.onCreate 早期, 须在 registerAndroidWebBookProviders 之前
 * (任何 webBook 编排层触发 RegexReplacers.get().replace 之前)。
 */
fun registerAndroidRegexErrorHandler() {
    RegexErrorHandlers.register(AndroidRegexErrorHandler)
}

private object AndroidRegexErrorHandler : RegexErrorHandler {
    override fun onTimeoutToast(message: String) {
        appCtx.longToastOnUi(message)
    }

    override fun saveCrashInfo(exception: Throwable) {
        CrashHandler.saveCrashInfo2File(exception)
    }

    override fun restartApp() {
        appCtx.restart()
    }
}
