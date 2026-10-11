package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import kotlinx.atomicfu.atomic
import io.legado.app.help.casUpdate
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.regex.Matcher
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 带超时检测的正则替换 Android 实现。
 *
 * 与 JVM 侧 [JvmRegexReplacer] 的差异只在超时手段: 输入必须是纯 [String], 不能包装
 * CharSequence。原因在 libcore (android10 ~ main 各分支一致):
 *
 * ```
 * private Matcher reset(CharSequence input, int start, int end) {
 *     this.text = input.toString();   // 包装对象被拆成字符串
 *     this.to   = end;                // region 上界仍是 input.length()
 * }
 * ```
 *
 * 正则引擎 (ICU) 拿到的是 `toString()` 结果, 却按 `input.length()` 划定扫描范围。传入
 * 包装对象时两者长度不一致, ICU 会越界读到包装对象相邻的堆内存: 实测正文里的内容一个
 * 都匹配不到 (静默不替换), 并会随机抛出下标越界异常。
 *
 * 因此这里用原版 archive 的协程看门狗方案 (`Coroutine.async` + `select/onTimeout`) 做超时,
 * 匹配跑在 IO 线程上、输入为纯 String, 引擎行为与直接 `Regex.replace` 一致。
 *
 * 已知代价: ICU 的 time limit 未在 Android 的 `java.util.regex` 暴露, 超时后无法真正中止
 * 已陷入灾难性回溯的匹配线程; 该线程会持续占用一核, 直到 [RegexErrorHandler.restartApp]
 * 兜底重启进程 (与原版 archive 行为一致)。
 */
object AndroidRegexReplacer : RegexReplacer {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        replacement: String,
        timeout: Long
    ): String {
        val regex = compileCached(pattern, multiline)
        val isJs = replacement.startsWith("@js:")
        val replacement1 = if (isJs) replacement.substring(4) else replacement
        // 关键: 先落地成 String。libcore 的 Matcher 内部同样会 toString(),
        // 提前落地保证 region 上界与引擎实际扫描的文本长度一致。
        val text = source.toString()
        return runBlocking(EmptyCoroutineContext) {
            suspendCancellableCoroutine { block ->
                Coroutine.async(executeContext = Dispatchers.IO) {
                    val job = launch {
                        try {
                            val javaPattern = regex.toPattern()
                            val matcher = javaPattern.matcher(text)
                            val stringBuffer = StringBuffer()
                            // isJs 路径: 循环外创建共享 scope + 预编译 bytecode,
                            // 避免每次匹配都重新初始化 bootstrap (性能优化, 应对长文本大量匹配)
                            val jsScope = if (isJs) {
                                val bindings = JsBindings().apply { this["result"] = "" }
                                val scope = JsEngines.get().getRuntimeScope(bindings)
                                val compiled = JsEngines.get().compile(
                                    JsEngines.get().wrapJsForEval(replacement1), scope
                                )
                                Pair(scope, compiled)
                            } else null
                            try {
                                while (matcher.find()) {
                                    if (isJs) {
                                        val (scope, compiled) = jsScope!!
                                        val bindings = JsBindings().apply {
                                            this["result"] = matcher.group()
                                            dangerousApi = false
                                        }
                                        JsEngines.get().injectBindings(scope, bindings)
                                        val jsResult = compiled.eval(scope, null)?.toString() ?: ""
                                        matcher.appendReplacement(
                                            stringBuffer, Matcher.quoteReplacement(jsResult)
                                        )
                                    } else {
                                        matcher.appendReplacement(stringBuffer, replacement1)
                                    }
                                }
                            } finally {
                                jsScope?.first?.close()
                            }
                            matcher.appendTail(stringBuffer)
                            block.resume(stringBuffer.toString())
                        } catch (e: Exception) {
                            block.resumeWithException(e)
                        }
                    }
                    select {
                        job.onJoin {}
                        onTimeout(timeout) {
                            val timeoutMsg =
                                "替换超时,3秒后还未结束将重启应用\n替换规则$pattern\n替换内容:$text"
                            val exception = RegexTimeoutException(timeoutMsg)
                            block.cancel(exception)
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
     * 编译缓存: 调用方传的是模式串, 而阅读热路径上每章×每规则都会调用本实现,
     * 不缓存则每次重新编译 (原 `ReplaceRule.regex` 是挂实例的懒编译)。
     *
     * 用不可变快照 (读侧一次原子取值即返回, 不加锁): 本表读远多于写, 且命中后不再写。
     * 并发未命中时同一模式串最多重复编译一次 (结果等价, 无副作用)。
     */
    private fun compileCached(pattern: String, multiline: Boolean): Regex {
        val key = (if (multiline) 1 else 0) to pattern
        cacheRef.value[key]?.let { return it }
        val regex = if (multiline) Regex(pattern, RegexOption.MULTILINE) else Regex(pattern)
        cacheRef.casUpdate { cur ->
            if (!cur.containsKey(key) && cur.size >= CACHE_LIMIT) {
                val oldest = cur.keys.firstOrNull()
                (if (oldest == null) cur else cur - oldest) + (key to regex)
            } else {
                cur + (key to regex)
            }
        }
        return regex
    }

    /** key → 编译结果; 不可变快照, 写时整体替换。 */
    private val cacheRef = atomic<Map<Pair<Int, String>, Regex>>(emptyMap())

    private const val CACHE_LIMIT = 32
}
