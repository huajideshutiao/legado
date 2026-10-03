package io.legado.app.utils

import kotlin.concurrent.Volatile

/**
 * 带超时的正则替换 provider 接口。
 *
 * 原 app 端 [CharSequence.replace] 扩展 (io.legado.app.utils.RegexExtensions.kt) 的
 * 实现分两份: JVM 宿主注册 [io.legado.app.utils.JvmRegexReplacer] (超时靠输入包装),
 * Android 宿主注册 [io.legado.app.utils.AndroidRegexReplacer] (超时靠协程看门狗)。
 * libcore 的 Matcher 会把 CharSequence 拆成 String 而 region 上界仍取原 length,
 * 包装对象在 Android 上不可用, 故必须分叉; 详见两份实现的类注释。
 *
 * app 端 [CharSequence.replace] 扩展改为薄壳委托 [io.legado.app.utils.AndroidRegexReplacer.replace];
 * WebBookProvidersImpl 的 RegexReplacer 实现亦经扩展调用本 impl。
 * 桌面端直接注册 [io.legado.app.utils.JvmRegexReplacer] (已注册 QuickJs 引擎与 RegexErrorHandler)。
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
 * 注册 WebBookProvidersImpl (其 replace 委托 [io.legado.app.utils.AndroidRegexReplacer]);
 * 桌面端直接注册 [io.legado.app.utils.JvmRegexReplacer]。
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
