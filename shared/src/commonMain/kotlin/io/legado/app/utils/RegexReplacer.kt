package io.legado.app.utils

/**
 * 带超时的正则替换 provider 接口。
 *
 * 原 app 端 [CharSequence.replace] 扩展 (io.legado.app.utils.RegexExtensions.kt) 的
 * 核心实现已下沉 jvmAndAndroidMain 的 [RegexReplacerImpl] (Matcher/Coroutine.async/
 * JsEngines/JsBindings/runBlockingInScope 全部在 shared 可用),
 * Android 专属的 longToastOnUi / CrashHandler / appCtx.restart 经 [RegexErrorHandler]
 * (app 端注册) 注入。
 *
 * app 端 [CharSequence.replace] 扩展改为薄壳委托 [RegexReplacerImpl.replace];
 * WebBookProvidersImpl 的 RegexReplacer 实现亦经扩展调用本 impl。
 * 桌面端注册简化版 DesktopRegexReplacer (不走 JS/超时, 见 registerDesktopWebBookProviders)。
 *
 * 模式参考 AppDbProviders / SourceDebugLoggers / AppConfigProviders。
 */
interface RegexReplacer {
    /**
     * 带超时检测的正则替换 (对应 app 端 CharSequence.replace 扩展)。
     *
     * @param source     待替换文本
     * @param regex      正则
     * @param replacement 替换串 (app 端实现支持 @js: 前缀动态替换)
     * @param timeout    超时毫秒
     */
    fun replace(
        source: CharSequence,
        regex: Regex,
        replacement: String,
        timeout: Long
    ): String
}

/**
 * RegexReplacer provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `RegexReplacers.get().replace(...)` 替代原
 * `source.replace(regex, replacement, timeout)`, 行为完全一致,
 * 仅多一层 provider 间接。
 *
 * Android 端经 [io.legado.app.model.webBook.registerAndroidWebBookProviders]
 * 注册 WebBookProvidersImpl (其 replace 委托 [RegexReplacerImpl]);
 * 桌面端注册 DesktopRegexReplacer (简化版, 不走 JS/超时)。
 */
object RegexReplacers {
    @Volatile
    private var impl: RegexReplacer? = null

    /** 宿主启动早期注册一次(任何 webBook 调用之前)。 */
    fun register(impl: RegexReplacer) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): RegexReplacer = impl ?: error("RegexReplacers not registered")
}
