package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 带超时检测的正则替换 native (iOS/鸿蒙) 实现, 逐语义对照 jvmAndAndroidMain [RegexReplacerImpl]。
 *
 * # 与 jvm 版差异 (java.util.regex.Matcher 在 K/N 不可用)
 * - 非 JS 替换: `Regex.replace(source, replacement)` 替代 Matcher.appendReplacement 循环,
 *   `$1`/`${name}` 组引用语义一致 (Kotlin 替换串同样支持组引用与 `\` 转义);
 * - `@js:` 分支: `Regex.replace(source) { match -> jsResult }` 逐匹配求值, transform 返回值
 *   按字面量插入 (等价 Matcher.quoteReplacement); 共享 scope + 预编译 + 逐匹配 injectBindings
 *   与 jvm 版完全一致, JS 求值走已注册的 [JsEngines] (NativeJsEngine/quickjs);
 * - 超时/重启骨架 (Coroutine.async + select/onTimeout + RegexErrorHandlers) 与 jvm 版一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NativeRegexReplacer : RegexReplacer {

    override fun replace(
        source: CharSequence,
        regex: Regex,
        replacement: String,
        timeout: Long
    ): String {
        val isJs = replacement.startsWith("@js:")
        val replacement1 = if (isJs) replacement.substring(4) else replacement
        return runBlockingInScope(EmptyCoroutineContext) {
            suspendCancellableCoroutine { block ->
                Coroutine.async(executeContext = Dispatchers.IO) {
                    val job = launch {
                        try {
                            val result = if (isJs) {
                                // isJs 路径: 循环外创建共享 scope + 预编译, 逐匹配注入 result 求值
                                val bindings = JsBindings().apply { this["result"] = "" }
                                val scope = JsEngines.get().getRuntimeScope(bindings)
                                val compiled = JsEngines.get().compile(
                                    JsEngines.get().wrapJsForEval(replacement1), scope
                                )
                                try {
                                    regex.replace(source) { match ->
                                        val b = JsBindings().apply {
                                            this["result"] = match.value
                                            dangerousApi = false
                                        }
                                        JsEngines.get().injectBindings(scope, b)
                                        // transform 返回值按字面量插入 (等价 Matcher.quoteReplacement)
                                        compiled.eval(scope, null)?.toString() ?: ""
                                    }
                                } finally {
                                    scope.close()
                                }
                            } else {
                                // Kotlin Regex 替换串同样支持 $1/${name} 组引用 (对齐 appendReplacement)
                                regex.replace(source, replacement1)
                            }
                            block.resume(result)
                        } catch (e: Exception) {
                            block.resumeWithException(e)
                        }
                    }
                    select {
                        job.onJoin {}
                        onTimeout(timeout) {
                            val timeoutMsg =
                                "替换超时,3秒后还未结束将重启应用\n替换规则$regex\n替换内容:$source"
                            val exception = RegexTimeoutException(timeoutMsg)
                            block.cancel(exception)
                            // Android 专属副作用经 RegexErrorHandler 注入; native 端未注册时静默跳过
                            val handler = RegexErrorHandlers.getOrNull()
                            handler?.onTimeoutToast(timeoutMsg)
                            handler?.saveCrashInfo(exception)
                            select {
                                job.onJoin {}
                                onTimeout(3000) {
                                    handler?.restartApp()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
