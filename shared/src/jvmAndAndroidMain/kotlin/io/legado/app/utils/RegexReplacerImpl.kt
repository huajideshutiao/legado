package io.legado.app.utils

import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import java.util.regex.Matcher

/**
 * 带超时检测的正则替换 shared 实现 (jvmAndAndroidMain)。
 *
 * 原 app 端 [CharSequence.replace] 扩展 (io.legado.app.utils.RegexExtensions.kt) 的纯逻辑下沉:
 * - JsEngines / JsBindings / RegexTimeoutException 已下沉 commonMain
 * - java.util.regex.Matcher 在 jvmAndAndroidMain 可用 (Kotlin common Regex 无 appendReplacement/quoteReplacement)
 * - Android 专属的 longToastOnUi / CrashHandler.saveCrashInfo2File
 *   经 [RegexErrorHandlers] 注入 (app 端注册 Android 实现, 桌面端注册 DesktopRegexErrorHandler)
 *
 * # 超时机制
 *
 * Java 正则不响应中断, 只能在引擎读字符时把它打断: 输入经 [DeadlineCharSequence] 包一层,
 * 取字符时比对截止时间, 到点抛异常中止匹配。匹配因此同步跑在调用线程上——
 * 零协程调度、零线程池、超时后不会留下吃满一核的僵尸线程 (旧看门狗方案的固有缺陷)。
 *
 * app 端 [RegexExtensions.kt] 改为薄壳, CharSequence.replace 扩展委托本实现,
 * WebBookProvidersImpl 的 RegexReplacer 实现亦经本类完成替换。
 */
object RegexReplacerImpl : RegexReplacer {

    /** 毫秒转纳秒的上界, 超过视为不限时 (避免乘法溢出成负数导致立即超时)。 */
    private const val MAX_TIMEOUT_MS = Long.MAX_VALUE / 1_000_000L

    override fun replace(
        source: CharSequence,
        regex: Regex,
        replacement: String,
        timeout: Long
    ): String {
        val isJs = replacement.startsWith("@js:")
        val replacement1 = if (isJs) replacement.substring(4) else replacement
        val timeoutNanos = if (timeout > MAX_TIMEOUT_MS) Long.MAX_VALUE else timeout * 1_000_000L
        val matcher = regex.toPattern().matcher(DeadlineCharSequence(source, timeoutNanos))
        try {
            if (!matcher.find()) {
                // 无匹配: 不必逐字重建整章字符串
                return source.toString()
            }
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
                do {
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
                } while (matcher.find())
            } finally {
                jsScope?.first?.close()
            }
            matcher.appendTail(stringBuffer)
            return stringBuffer.toString()
        } catch (_: RegexDeadlineException) {
            val timeoutMsg = "替换超时\n替换规则$regex\n替换内容:$source"
            val exception = RegexTimeoutException(timeoutMsg)
            // Android 专属副作用经 RegexErrorHandler 注入; 桌面端 null 静默跳过
            val handler = RegexErrorHandlers.getOrNull()
            handler?.onTimeoutToast(timeoutMsg)
            handler?.saveCrashInfo(exception)
            throw exception
        }
    }
}

/** 中止匹配用的内部信号, 由 [RegexReplacerImpl] 转成对外的 [RegexTimeoutException]。 */
private class RegexDeadlineException : NoStackTraceException("regex deadline")

/**
 * 带截止时间的输入包装: 每 4096 次取字符比对一次 [System.nanoTime], 到点抛 [RegexDeadlineException]。
 * Matcher 的 group / appendReplacement / appendTail 都会走 [subSequence] 与 [get], 两者必须完整代理。
 */
private class DeadlineCharSequence(
    private val source: CharSequence,
    private val timeoutNanos: Long,
) : CharSequence {

    private val startNanos = System.nanoTime()
    private var readCount = 0

    override val length: Int get() = source.length

    override fun get(index: Int): Char {
        if ((readCount++ and CHECK_MASK) == 0 && System.nanoTime() - startNanos >= timeoutNanos) {
            throw RegexDeadlineException()
        }
        return source[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        source.subSequence(startIndex, endIndex)

    private companion object {
        const val CHECK_MASK = 0xFFF
    }
}
