@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.utils

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.casUpdate
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMatchingReportProgress
import platform.Foundation.NSNotFound
import platform.Foundation.NSRegularExpression
import platform.Foundation.NSRegularExpressionAnchorsMatchLines
import platform.Foundation.NSError
import platform.Foundation.NSTextCheckingResult
import platform.Foundation._NSRange
import platform.Foundation.enumerateMatchesInString
import platform.Foundation.rangeAtIndex
import platform.Foundation.replacementStringForResult

/**
 * iOS 端规则正则实现: 走系统 [NSRegularExpression] (ICU 引擎), 替代 `kotlin.text.Regex`。
 *
 * # 为什么必须换引擎
 *
 * `kotlin.text.Regex` 在 native 端是 Harmony 派生实现, 两处都会失控:
 * - **编译**: 递归下降解析模式串 (`processExpression` → `processSequence` → `processSubExpression`),
 *   整个 stdlib 实现里没有任何栈保护或异常包装; JDK 同位置有 `catch (StackOverflowError)` 转语法错误。
 * - **匹配**: `GroupQuantifierSet.matches` 每消费一次分组体就递归一层 (`innerSet.next = this`),
 *   分组前的 lookbehind 又逐位 `findBack` 重试。
 *
 * 实测: 书源规则里 `##匹配##替换` 的匹配段递归 2969 帧后写穿线程栈保护页 → EXC_BAD_ACCESS/SIGILL
 * 闪退 (崩点距栈顶 95 字节, 系统报 "Could not determine thread index for stack guard region");
 * 且无中断检查点, 超时后线程持续占满一核。
 *
 * ICU 匹配器不成立这一失败模式, 官方文档 "Heap and Stack Usage" 节原文:
 * "ICU keeps its match backtracking state on the heap. ... Because ICU does not use program
 * recursion to maintain its backtracking state, stack usage during matching operations is
 * minimal, and does not increase with complex patterns or large amounts of backtracking state."
 * 即回溯状态在堆上, 栈用量不随模式复杂度增长; 堆上有默认 8MB 上限, 超限返回错误而非崩溃。
 *
 * # 超时
 *
 * Foundation 未暴露 ICU 的时限 API (`timeLimit`/`stackLimit` 在 NSRegularExpression 文档零匹配),
 * 故时间上限经 [NSMatchingReportProgress] 实现。该选项开启后, 长匹配期间 ICU 会周期性回调 block,
 * 且**进度回调以 `result == nil` 送达**(Apple 头文件原文: "If the NSMatchingReportProgress option
 * is specified, the block will also be called periodically during long-running match operations,
 * with nil result and NSMatchingProgress set in the flags, at which point the client may again
 * stop the operation by setting the contents of stop to YES.")。
 * 因此截止时间检查必须在取用匹配结果**之前**执行, 否则进度回调会被当成空结果丢弃,
 * 而“单个匹配永不结束”正是唯一需要超时的场景。
 *
 * 超时后抛 [RegexTimeoutException], 与 Android 侧 `AndroidRegexReplacer` 契约一致 ——
 * 调用方已有的 `catch (e: RegexTimeoutException)` 分支会据此禁用坏规则并落库, 不会每章重试。
 *
 * 时钟走 [systemNanoTime] (native 侧 `clock_gettime(CLOCK_MONOTONIC)`): Kotlin/Native stdlib
 * 无 `System.nanoTime()`, 也不存在 `kotlin.System` 类。
 *
 * # 与 Android 的已知差异 (接受)
 *
 * `\w`/`\s` 两端都为 ICU 语义 (`\w` 含中文, `\s` 含 U+3000), 换引擎后与 Android 一致;
 * 差异只剩 ICU 数据表版本不同导致的个别码点归属不同。
 *
 * # 注册
 *
 * 由 `registerIosProviders` 在任何规则解析之前注册一次。
 */
object IosRuleRegex : RuleRegex {

    override fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        replacement: String,
        replaceFirst: Boolean,
        timeout: Long
    ): String {
        val text = source.toString()
        val ns = compileOrThrow(pattern, multiline)
        val out = StringBuilder(text.length)
        var lastEnd = 0
        val timedOut = ns.scan(text, timeout) { match ->
            val start = match.range.startIndex()
            val end = match.range.endIndex()
            out.append(text, lastEnd, start)
            out.append(ns.replacementStringForResult(match, text, 0, replacement))
            lastEnd = end
            // replaceFirst 语义: 首个匹配替换后即停
            !replaceFirst
        }
        if (timedOut) throw timeoutException(pattern, source)
        out.append(text, lastEnd, text.length)
        return out.toString()
    }

    override fun find(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): RuleMatchSpans? {
        val text = source.toString()
        val ns = compileOrThrow(pattern, multiline)
        var found: RuleMatchSpans? = null
        val timedOut = ns.scan(text, timeout) { match ->
            found = match.toRuleMatchSpans(ns)
            false // find 语义: 首个匹配即停
        }
        if (timedOut) throw timeoutException(pattern, source)
        return found
    }

    override fun findAll(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): List<RuleMatchSpans> {
        val text = source.toString()
        val ns = compileOrThrow(pattern, multiline)
        val list = ArrayList<RuleMatchSpans>()
        val timedOut = ns.scan(text, timeout) { match ->
            list.add(match.toRuleMatchSpans(ns))
            true
        }
        if (timedOut) throw timeoutException(pattern, source)
        return list
    }

    /**
     * 编译校验 (目录规则编辑保存前): 语法错误与 ICU 拒绝的形态 (如无界 lookbehind) 都经
     * [NSError] 返回说明文本供界面展示。编译在 ICU 里不递归, 不会爆栈。
     */
    override fun validate(pattern: String, multiline: Boolean): String? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ns = NSRegularExpression.regularExpressionWithPattern(
            pattern,
            if (multiline) NSRegularExpressionAnchorsMatchLines else 0uL,
            error.ptr
        )
        if (ns != null) return@memScoped null
        error.value?.localizedDescription ?: "正则表达式无效"
    }

    /**
     * 整串匹配: 用首尾锚点把约束交给 ICU, 而非比较 [find] 结果的区间。
     *
     * ICU 无“整串匹配”入口 (NSRegularExpression 只有逐匹配枚举), 故拼接 `\A(?:...)\z`:
     * `\A` 限定输入开头、`\z` 限定输入结尾, 用非捕获组包裹不改变模式串自身的分组编号。
     * 不可写成 `^(?:...)$`: 开启多行时 `^`/`$` 按行定位, 不开启时 `$` 也允许匹配到末尾换行之前。
     */
    override fun matchesEntire(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): Boolean {
        val text = source.toString()
        val ns = compileOrThrow("\\A(?:$pattern)\\z", multiline)
        var hit = false
        val timedOut = ns.scan(text, timeout) { _ ->
            hit = true
            false
        }
        if (timedOut) throw timeoutException(pattern, source)
        return hit
    }

    /**
     * 逐匹配扫描, 带截止时间检查。
     *
     * 截止时间检查必须早于结果判空: 开启 [NSMatchingReportProgress] 后, 进度上报回调正是以
     * `result == nil` 送达 (见类注释引用的 Apple 头文件原文), 先判空会把每次进度上报都丢掉,
     * 使“单个匹配永不结束”无法被中止。
     *
     * @param onMatch 处理一个匹配; 返回 false 表示无需继续 (首个匹配已够)
     * @return 是否因超时而中止
     */
    private fun NSRegularExpression.scan(
        text: String,
        timeout: Long,
        onMatch: (NSTextCheckingResult) -> Boolean
    ): Boolean {
        val deadline = if (timeout > 0) systemNanoTime() + timeout * 1_000_000L else 0L
        var timedOut = false
        enumerateMatchesInString(
            text,
            NSMatchingReportProgress,
            NSMakeRange(0u, text.length.toULong())
        ) { match, _, stop ->
            // 先查截止时间: 进度回调 (match == null) 也要能中止
            if (deadline != 0L && systemNanoTime() >= deadline) {
                timedOut = true
                stop?.pointed?.value = true
                return@enumerateMatchesInString
            }
            if (match == null) return@enumerateMatchesInString
            if (!onMatch(match)) {
                stop?.pointed?.value = true
            }
        }
        return timedOut
    }

    /**
     * 编译结果按 (多行标志, 模式串) 缓存, 同一规则跨章节复用; 上限 32 条。
     *
     * 用不可变快照 (读侧一次原子取值即返回, 不加锁): 本表读远多于写, 且命中后不再写;
     * [RuleRegexes] 是全局单例, 规则解析在多协程/多线程上并发执行, 可变 map 并发写会破坏结构。
     * NSRegularExpression 本身线程安全, 故只需保证快照替换是原子的。
     *
     * 编译失败抛异常: 与本接口的统一契约一致 (原写法 `Regex(pattern)` 同样抛异常)。
     * 不返回 null —— 那会把“规则写错”静默变成“取不到值”。
     */
    private fun compileOrThrow(pattern: String, multiline: Boolean): NSRegularExpression = memScoped {
        val key = (if (multiline) 1 else 0) to pattern
        cacheRef.value[key]?.let { return@memScoped it }
        val error = alloc<ObjCObjectVar<NSError?>>()
        val ns = NSRegularExpression.regularExpressionWithPattern(
            pattern,
            if (multiline) NSRegularExpressionAnchorsMatchLines else 0uL,
            error.ptr
        ) ?: throw IllegalArgumentException(error.value?.localizedDescription ?: "正则表达式无效")
        cacheRef.casUpdate { cur ->
            if (!cur.containsKey(key) && cur.size >= CACHE_LIMIT) {
                val oldest = cur.keys.firstOrNull()
                (if (oldest == null) cur else cur - oldest) + (key to ns)
            } else {
                cur + (key to ns)
            }
        }
        ns
    }

    /** key → 编译结果; 不可变快照, 写时整体替换。 */
    private val cacheRef = atomic<Map<Pair<Int, String>, NSRegularExpression>>(emptyMap())

    private fun timeoutException(pattern: String, source: CharSequence): RegexTimeoutException {
        val msg = "替换超时\n替换规则$pattern\n替换内容:${source.take(500)}"
        val exception = RegexTimeoutException(msg)
        // 平台副作用经 RegexErrorHandler 注入 (与 Android/native 侧同一出口: 长提示 + 崩溃落盘)
        val handler = RegexErrorHandlers.getOrNull()
        handler?.onTimeoutToast(msg)
        handler?.saveCrashInfo(exception)
        return exception
    }

    private const val CACHE_LIMIT = 32
}

/** [NSTextCheckingResult] → [RuleMatchSpans] (下标口径: UTF-16 code unit, 与 String.substring 一致)。 */
private fun NSTextCheckingResult.toRuleMatchSpans(ns: NSRegularExpression): RuleMatchSpans {
    val groupCount = ns.numberOfCaptureGroups.toInt() + 1
    val groupRanges = ArrayList<IntRange?>(groupCount)
    for (i in 0 until groupCount) {
        val r = rangeAtIndex(i.toULong())
        val location = r.useContents { location }
        val length = r.useContents { length }
        // 未参与匹配的分组: location 为 NSNotFound
        groupRanges.add(
            if (location.toLong() == NSNotFound) null
            else location.toInt() until (location.toInt() + length.toInt())
        )
    }
    return RuleMatchSpans(groupRanges[0] ?: 0 until 0, groupRanges)
}

private fun kotlinx.cinterop.CValue<_NSRange>.startIndex(): Int =
    useContents { location.toInt() }

private fun kotlinx.cinterop.CValue<_NSRange>.endIndex(): Int =
    useContents { (location + length).toInt() }
