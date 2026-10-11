package com.github.jershell.rjpath

// K/N 目标没有 JVM 默认导入的 kotlin.jvm.Volatile, commonMain 一律用 kotlin.concurrent.Volatile
import kotlin.concurrent.Volatile

/**
 * JSONPath 的 `match(值, 模式)` 匹配模式。
 *
 * 匹配本体委托 [RegexMatcher] (宿主注入), 避免在 native 端直接用 `kotlin.text.Regex`
 * —— 模式串来自书源规则, 其回溯在 native 端按迭代次数递归, 可把线程栈写穿直接闪退。
 */
enum class RegexMatchMode {
    /**
     * Entire input must match the regex.
     * Equivalent to Java/Kotlin `String.matches(Regex)`
     */
    ENTIRE {
        override fun matches(matcher: RegexMatcher, pattern: String, input: String): Boolean =
            matcher.matchesEntire(input, pattern)
    },

    /**
     * Checks if any part of the input matches the regex.
     * Equivalent to `Regex.containsMatchIn()`
     */
    CONTAINS {
        override fun matches(matcher: RegexMatcher, pattern: String, input: String): Boolean =
            matcher.containsMatch(input, pattern)
    };

    abstract fun matches(matcher: RegexMatcher, pattern: String, input: String): Boolean
}

/**
 * 默认匹配器: `kotlin.text.Regex` 直调, 与本参数引入前的行为逐字一致。
 *
 * 宿主 (本项目) 启动时经 [RegexMatchers.register] 换成带时间上限的实现。
 */
object DefaultRegexMatcher : RegexMatcher {

    override fun matchesEntire(input: String, pattern: String): Boolean =
        input.matches(Regex(pattern))

    override fun containsMatch(input: String, pattern: String): Boolean =
        Regex(pattern).containsMatchIn(input)
}

/** [RegexMatcher] 容器: 宿主启动早期注册一次 (任何 JSONPath 解析之前)。 */
object RegexMatchers {

    @Volatile
    private var impl: RegexMatcher? = null

    fun register(impl: RegexMatcher?) {
        this.impl = impl
    }

    fun get(): RegexMatcher = impl ?: DefaultRegexMatcher
}
