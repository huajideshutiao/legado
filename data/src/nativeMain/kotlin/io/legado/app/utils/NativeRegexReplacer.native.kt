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
 * 带超时检测的正则替换 native (iOS/鸿蒙) 实现。
 *
 * 超时/重启骨架与 [io.legado.app.utils.AndroidRegexReplacer] 同型 (协程看门狗, 输入不包装),
 * 替换本体经 [RuleRegexes] 的平台实现执行:
 *
 * - iOS: 注册 [IosRuleRegex] 后走系统 ICU 引擎 (回溯状态在堆上, 栈用量不随模式复杂度增长),
 *   时间上限经进度回调实现 —— 不再有"超时后线程持续占一核"的后果;
 * - 鸿蒙: 尚无等价能力, 回退 [DefaultRuleRegex] (`kotlin.text.Regex`), 该端仍存在爆栈风险。
 *
 * 故这里的看门狗是**兜底**: 平台实现已能自中止时它不会触发; 若实现本身无法中止 (如鸿蒙),
 * 看门狗仍按原逻辑提示 + 落盘 + 3 秒后告警。
 *
 * # `@js:` 分支
 *
 * 逐匹配求值: 先经 [RuleRegexes] 取全部匹配区间, 再逐段拼结果, JS 求值返回值按字面量插入
 * (等价 Matcher.quoteReplacement); 共享 scope + 预编译 + 逐匹配 injectBindings 与原实现一致,
 * JS 求值走已注册的 [JsEngines] (NativeJsEngine/quickjs)。
 *
 * # 已知字符类差异 (未对齐, 有意保留)
 *
 * Kotlin/Native stdlib 的 `\w` 定义为 `[a-zA-Z0-9_]` (ASCII-only, 见 stdlib
 * nativeWasmMain/text/regex/AbstractCharClass.kt 的 CachedWord), 不含中文; 而 Android 侧 ICU
 * 的 `\w` 含中文。净化规则大量使用 `[^\n\w…]{4,}` 这类“非文字符号串”写法, 故**未换引擎的平台**
 * (当前为鸿蒙) 上仍会把整段中文当符号串误替换。
 *
 * JVM 侧经 `Pattern.UNICODE_CHARACTER_CLASS` 补齐了 ICU 语义; iOS 侧换 ICU 引擎后自然对齐。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NativeRegexReplacer : RegexReplacer {

    override fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
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
                                    replaceWithJsEvaluator(
                                        source, pattern, multiline, timeout
                                    ) { matchValue ->
                                        val b = JsBindings().apply {
                                            this["result"] = matchValue
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
                                // 编译与匹配都交给 [RuleRegexes] 的平台实现 (iOS 走 ICU 引擎,
                                // 编译与匹配都不递归); 未注册时回退 `kotlin.text.Regex`
                                RuleRegexes.get().replace(
                                    source, pattern, multiline, replacement1, false, timeout
                                )
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
                                "替换超时,3秒后还未结束将重启应用\n替换规则$pattern\n替换内容:$source"
                            val exception = RegexTimeoutException(timeoutMsg)
                            block.cancel(exception)
                            // 平台副作用经 RegexErrorHandler 注入 (native 端由 NativeRegexErrorHandler
                            // 提供 Toasters 长提示 + 崩溃落盘 + 手动重启告警; 未注册时静默跳过)
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

    /**
     * `@js:` 逐匹配求值替换。
     *
     * 先取全部匹配区间 (经 [RuleRegexes], 带时间上限), 再逐段拼结果 —— 不经引擎的替换模板, 因为
     * JS 求值结果可能含 `$`/`\`, 必须按字面量插入 (对应 Matcher.quoteReplacement)。
     */
    private fun replaceWithJsEvaluator(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long,
        evaluator: (String) -> String
    ): String {
        val matches = RuleRegexes.get().findAll(source, pattern, multiline, timeout)
        if (matches.isEmpty()) return source.toString()
        val text = source.toString()
        val out = StringBuilder(text.length)
        var lastEnd = 0
        for (match in matches) {
            out.append(text, lastEnd, match.range.first)
            out.append(evaluator(match.value(text)))
            lastEnd = match.range.last + 1
        }
        out.append(text, lastEnd, text.length)
        return out.toString()
    }
}
