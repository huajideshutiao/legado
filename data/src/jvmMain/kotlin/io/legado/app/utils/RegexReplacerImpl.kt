package io.legado.app.utils

import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.model.script.JsBindings
import io.legado.app.model.script.JsEngines
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 带超时检测的正则替换 JVM 实现。
 *
 * 原 app 端 [CharSequence.replace] 扩展 (io.legado.app.utils.RegexExtensions.kt) 的纯逻辑下沉:
 * - JsEngines / JsBindings / RegexTimeoutException 已下沉 commonMain
 * - java.util.regex.Matcher 可用 (Kotlin common Regex 无 appendReplacement/quoteReplacement)
 * - 超时提示 / 崩溃落盘 / 重启经 [RegexErrorHandlers] 注入 (桌面端注册 DesktopRegexErrorHandler)
 *
 * 仅 JVM 宿主注册; Android 的 libcore Matcher 语义不同, 见 [AndroidRegexReplacer]。
 *
 * # 超时机制
 *
 * 正则不响应中断, 只能在引擎读字符时把它打断: 输入经 [DeadlineCharSequence] 包一层,
 * 取字符时比对截止时间, 到点抛异常中止匹配。匹配因此同步跑在调用线程上——
 * 零协程调度、零线程池、超时后不会留下吃满一核的僵尸线程 (旧看门狗方案的固有缺陷)。
 *
 * 该包装依赖「正则引擎按需回调查询 CharSequence」这一 JDK 行为。Android 的 libcore
 * Matcher 在 reset(CharSequence) 里做 text = input.toString(), 而 region 上界仍取
 * input.length(), 包装对象一旦进入 ICU 就退化成“按正文长度去读身份串所在内存”:
 * 实测正文静默不替换, 并会随机抛出下标越界异常。故包装只能在 JVM 使用。
 *
 * # 字符类语义
 *
 * 编译时补 `UNICODE_CHARACTER_CLASS` 以对齐 Android (ICU) 的 `\w` 语义, 详见 [replace] 内注释。
 * iOS/鸿蒙的 Kotlin/Native stdlib `\w` 为 ASCII-only 且无法等价补齐, 该差异保留,
 * 见 [io.legado.app.utils.NativeRegexReplacer] 的“已知字符类差异”节。
 */
object JvmRegexReplacer : RegexReplacer {

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
        // 补齐 ICU 字符类语义: JDK 默认 \w 是 ASCII-only 不含中文, 而 Android (ICU) 的 \w
        // 含中文。净化规则大量使用 [^\n\w…]{4,} 这类“非文字符号串”写法, 不补齐则桌面端
        // 把整段中文当符号串误替换 (实测同一条规则 JVM 11 处 / Android 0 处)。
        // Android 的 Pattern 已是 Unicode 语义且不支持该标志, 故只在此处补。
        // 必须带上 regex 自身已有的 flags (toRegex(RegexOption...) 传入的选项), 不能只取 pattern 文本。
        val matcher = Pattern.compile(
            regex.pattern,
            regex.toPattern().flags() or Pattern.UNICODE_CHARACTER_CLASS
        ).matcher(DeadlineCharSequence(source, timeoutNanos))
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

/** 中止匹配用的内部信号, 由 [JvmRegexReplacer] 转成对外的 [RegexTimeoutException]。 */
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
