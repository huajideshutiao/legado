package io.legado.app.utils

import com.github.jershell.rjpath.RegexMatcher
import com.github.jershell.rjpath.RegexMatchers
import io.legado.app.exception.RegexTimeoutException

/**
 * 把 [RuleRegexes] 注册的规则正则实现接到 rjpath 的 `match()` 函数。
 *
 * rjpath 是内嵌第三方模块 (只依赖 kotlinx-serialization), 不能反向依赖本模块, 故经
 * [RegexMatchers] 注入。JSONPath 的 `match(值, 模式)` 里的模式串来自书源规则, 与
 * `##匹配##替换` 同类: native 端 `kotlin.text.Regex` 的回溯按迭代次数递归, 病态规则可把
 * 线程栈写穿直接闪退且无法中止。
 *
 * 各端宿主启动早期调用一次 (须在任何 JSONPath 解析之前); 未调用时 rjpath 退回 `Regex` 直调。
 */
fun registerRuleRegexForJsonPath() {
    RegexMatchers.register(object : RegexMatcher {
        override fun matchesEntire(input: String, pattern: String): Boolean =
            ruleRegexMatch {
                RuleRegexes.get()
                    .matchesEntire(input, pattern, multiline = false, timeout = JSONPATH_MATCH_TIMEOUT)
            }

        override fun containsMatch(input: String, pattern: String): Boolean =
            ruleRegexMatch {
                RuleRegexes.get()
                    .find(input, pattern, multiline = false, timeout = JSONPATH_MATCH_TIMEOUT) != null
            }
    })
}

/**
 * 执行一次匹配, 只把超时当“不匹配”。
 *
 * 超时意味着匹配已被中止, 取不到结果, 与 `false` 等价; 而模式串无法编译必须向上抛 ——
 * 原实现 `Regex(pattern)` 对非法模式串同样抛异常, 静默当“不匹配”会让“规则写错”与
 * “页面无该字段”不可区分。
 */
private inline fun ruleRegexMatch(block: () -> Boolean): Boolean = try {
    block()
} catch (_: RegexTimeoutException) {
    false
}

/** JSONPath `match()` 的单次匹配上限 (毫秒), 与替换规则默认超时同口径。 */
private const val JSONPATH_MATCH_TIMEOUT = 3000L
