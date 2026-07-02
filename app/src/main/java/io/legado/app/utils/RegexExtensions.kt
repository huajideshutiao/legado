package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.CrashHandler
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx
import java.util.regex.Matcher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 带有超时检测的正则替换
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CharSequence.replace(regex: Regex, replacement: String, timeout: Long): String {
    val charSequence = this@replace
    val isJs = replacement.startsWith("@js:")
    val replacement1 = if (isJs) replacement.substring(4) else replacement
    return runBlocking {
        suspendCancellableCoroutine { block ->
            Coroutine.async(executeContext = IO) {
                val job = launch {
                    try {
                        val pattern = regex.toPattern()
                        val matcher = pattern.matcher(charSequence)
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
                            "替换超时,3秒后还未结束将重启应用\n替换规则$regex\n替换内容:$charSequence"
                        val exception = RegexTimeoutException(timeoutMsg)
                        block.cancel(exception)
                        appCtx.longToastOnUi(timeoutMsg)
                        CrashHandler.saveCrashInfo2File(exception)
                        select {
                            job.onJoin {}
                            onTimeout(3000) {
                                appCtx.restart()
                            }
                        }
                    }
                }
            }
        }
    }
}

