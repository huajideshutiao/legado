package io.legado.app.utils

import io.legado.app.help.casUpdate
import kotlinx.atomicfu.atomic
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 桌面端规则正则实现: `java.util.regex` 并补 `UNICODE_CHARACTER_CLASS`。
 *
 * # 为什么要补这个标志
 *
 * 安卓与苹果两端的字符类**恒为 Unicode 语义**: 安卓官方 `Pattern` 文档写明自 2.3 起 ICU4C
 * 即正则后端, 且该端不支持该标志 ("Unicode character classes are always used"); 苹果侧走
 * 系统 NSRegularExpression (同为 ICU)。而 JDK 默认的 `\w`/`\d`/`\s` 是 ASCII 的 ——
 * 同一条书源规则用 `\w` 取正文时, 安卓与苹果能取到中文、桌面取不到。
 * 该标志还隐含 `UNICODE_CASE` (JDK 文档原文), 大小写不敏感的语义一并对齐。
 *
 * 同端替换规则路径的口径也由此统一: [JvmRegexReplacer] 早已补了同一个标志, 而书源规则
 * 此前走 [DefaultRuleRegex] (`kotlin.text.Regex` 直调, 无标志), 形成"同一端两套字符类语义"。
 *
 * # 失败口径
 *
 * 与 [RuleRegex] 的契约一致: 模式串无法编译时向上抛 (不静默当"没匹配到")。
 * 不提供时间上限 —— 与其余端的默认实现同口径, 替换路径上的超时由 [RegexReplacers] 另行提供。
 *
 * 编译结果带缓存: 调用方传的是模式串, 阅读热路径上每章×每规则都会调用本实现。
 */
object JvmRuleRegex : RuleRegex {

    override fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        replacement: String,
        replaceFirst: Boolean,
        timeout: Long
    ): String {
        val compiled = compileOrThrow(pattern, multiline)
        val text = source.toString()
        if (replaceFirst) {
            // 与 [DefaultRuleRegex] 同语义: 取首个匹配的文本, 再在其中替换首个
            val outer = compiled.matcher(text)
            if (!outer.find()) return ""
            val matched = outer.group().orEmpty()
            val inner = compiled.matcher(matched)
            if (!inner.find()) return matched
            val single = StringBuilder(matched.length)
            inner.appendReplacement(single, replacement)
            inner.appendTail(single)
            return single.toString()
        }
        val matcher = compiled.matcher(text)
        val out = StringBuilder(text.length)
        while (matcher.find()) {
            matcher.appendReplacement(out, replacement)
        }
        matcher.appendTail(out)
        return out.toString()
    }

    override fun find(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): RuleMatchSpans? {
        val matcher = compileOrThrow(pattern, multiline).matcher(source.toString())
        if (!matcher.find()) return null
        return matcher.toRuleMatchSpans()
    }

    override fun findAll(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): List<RuleMatchSpans> {
        val matcher = compileOrThrow(pattern, multiline).matcher(source.toString())
        val result = ArrayList<RuleMatchSpans>()
        while (matcher.find()) {
            result.add(matcher.toRuleMatchSpans())
        }
        return result
    }

    override fun validate(pattern: String, multiline: Boolean): String? = try {
        compileOrThrow(pattern, multiline)
        null
    } catch (e: Exception) {
        e.message
    }

    override fun matchesEntire(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): Boolean {
        // 与 [DefaultRuleRegex] 同口径: 用首尾锚点把整串约束交给引擎, 不比较匹配区间
        val matcher = compileOrThrow("\\A(?:$pattern)\\z", multiline).matcher(source.toString())
        return matcher.find()
    }

    /** 编译并缓存; 编译失败抛异常, 由调用方按自己的容忍度处理。 */
    private fun compileOrThrow(pattern: String, multiline: Boolean): Pattern {
        val key = (if (multiline) 1 else 0) to pattern
        cacheRef.value[key]?.let { return it }
        val flags = Pattern.UNICODE_CHARACTER_CLASS or (if (multiline) Pattern.MULTILINE else 0)
        val compiled = Pattern.compile(pattern, flags)
        cacheRef.casUpdate { cur ->
            // 淘汰按插入序取最早的键 (先进先出): 已有键被覆盖不改变其位置
            if (!cur.containsKey(key) && cur.size >= CACHE_LIMIT) {
                val oldest = cur.keys.firstOrNull()
                (if (oldest == null) cur else cur - oldest) + (key to compiled)
            } else {
                cur + (key to compiled)
            }
        }
        return compiled
    }

    /** key → 编译结果; 不可变快照, 写时整体替换。 */
    private val cacheRef = atomic<Map<Pair<Int, String>, Pattern>>(emptyMap())

    private const val CACHE_LIMIT = 32
}

/**
 * [Matcher] → [RuleMatchSpans]。
 *
 * 区间口径与 [RuleMatchSpans] 一致 (闭区间, UTF-16 code unit): `Matcher` 给的是
 * `[start, end)`, 转成 `start..end-1`; 分组未参与匹配时 `start` 为 -1, 转成 null。
 */
private fun Matcher.toRuleMatchSpans(): RuleMatchSpans {
    val count = groupCount()
    val ranges = ArrayList<IntRange?>(count + 1)
    for (i in 0..count) {
        val from = start(i)
        val to = end(i)
        ranges.add(if (from < 0 || to < 0) null else from..(to - 1))
    }
    return RuleMatchSpans(ranges[0] ?: 0 until 0, ranges)
}
