package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.regex.Matcher
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 带超时检测的正则替换 shared 实现 (jvmAndAndroidMain)。
 *
 * 原 app 端 [CharSequence.replace] 扩展 (io.legado.app.utils.RegexExtensions.kt) 的纯逻辑下沉:
 * - Coroutine.async / JsEngines / JsBindings / RegexTimeoutException 已下沉 commonMain
 * - java.util.regex.Matcher 在 jvmAndAndroidMain 可用 (Kotlin common Regex 无 appendReplacement/quoteReplacement)
 * - runBlocking 经 [runBlockingInScope] expect/actual 桥接 (commonMain 禁用 runBlocking)
 * - Android 专属的 longToastOnUi / CrashHandler.saveCrashInfo2File / appCtx.restart()
 *   经 [RegexErrorHandlers] 注入 (app 端注册), 桌面端不注册时静默跳过
 *
 * app 端 [RegexExtensions.kt] 改为薄壳, CharSequence.replace 扩展委托到本实现,
 * WebBookProvidersImpl 的 RegexReplacer 实现亦经本类完成替换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RegexReplacerImpl : RegexReplacer {

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
                            val pattern = regex.toPattern()
                            val matcher = pattern.matcher(source)
                            val stringBuffer = StringBuffer()
                            // isJs 路径: 循环外创建共享 scope + 预编译 bytecode,
                            // 避免每次匹配都重新初始化 bootstrap (性能优化,应对长文本大量匹配)
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
                                        // 更新 result 变量并注入到共享 scope
                                        val bindings = JsBindings().apply {
                                            this["result"] = matcher.group()
                                            dangerousApi = false
                                        }
                                        JsEngines.get().injectBindings(scope, bindings)
                                        val jsResult = compiled.eval(scope, null)?.toString() ?: ""
                                        val quotedResult = Matcher.quoteReplacement(jsResult)
                                        matcher.appendReplacement(stringBuffer, quotedResult)
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
                                "替换超时,3秒后还未结束将重启应用\n替换规则$regex\n替换内容:$source"
                            val exception = RegexTimeoutException(timeoutMsg)
                            block.cancel(exception)
                            // Android 专属副作用经 RegexErrorHandler 注入; 桌面端 null 静默跳过
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
