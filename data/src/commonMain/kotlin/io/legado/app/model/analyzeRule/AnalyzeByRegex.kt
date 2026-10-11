package io.legado.app.model.analyzeRule

import io.legado.app.exception.RegexTimeoutException
import io.legado.app.utils.RuleMatchSpans
import io.legado.app.utils.RuleRegexes

/**
 * 正则规则解析 (`:regex` 与 `##` 分组提取)。
 *
 * 经 [RuleRegexes] 以**模式串**执行: 这些模式串来自书源规则, native 端 `kotlin.text.Regex`
 * 的编译与匹配都会递归 (编译无栈保护, 匹配按迭代次数递归), 复杂规则可把线程栈写穿直接闪退
 * 且无法中止; iOS 端实现换 ICU 引擎并以进度回调做时间上限。详见 [io.legado.app.utils.RuleRegex]。
 *
 * 编译结果由 [RuleRegexes] 的实现自行缓存 (原实现每次调用都 `Regex(...)` 重新编译)。
 */
object AnalyzeByRegex {

    fun getElement(res: String, regs: Array<String>, index: Int = 0): List<String>? {
        var vIndex = index
        val match = findFirst(res, regs[vIndex]) ?: return null
        // 判断索引的规则是最后一个规则
        return if (vIndex + 1 == regs.size) {
            // 新建容器: groupValueOrThrow 对齐 Matcher.group(n)!! (未参与分组同样抛异常)
            val info = arrayListOf<String>()
            for (groupIndex in 0 until match.groupRanges.size) {
                info.add(match.groupValueOrThrow(res, groupIndex))
            }
            info
        } else {
            val result = StringBuilder()
            for (m in findAll(res, regs[vIndex])) {
                result.append(m.value(res))
            }
            getElement(result.toString(), regs, ++vIndex)
        }
    }

    fun getElements(res: String, regs: Array<String>, index: Int = 0): List<List<String>> {
        var vIndex = index
        val first = findFirst(res, regs[vIndex]) ?: return arrayListOf()
        // 判断索引的规则是最后一个规则
        if (vIndex + 1 == regs.size) {
            // 创建书息缓存数组
            val books = ArrayList<List<String>>()
            for (m in findAll(res, regs[vIndex])) {
                // 新建容器: groups[n]?.value 对齐 Matcher.group(n) (未参与分组落空串)
                val info = arrayListOf<String>()
                for (groupIndex in 0 until m.groupRanges.size) {
                    info.add(m.groupValue(res, groupIndex) ?: "")
                }
                books.add(info)
            }
            return books
        } else {
            val result = StringBuilder()
            for (m in findAll(res, regs[vIndex])) {
                result.append(m.value(res))
            }
            return getElements(result.toString(), regs, ++vIndex)
        }
    }

    /**
     * 取首个匹配。
     *
     * 编译失败 (规则语法错误) 向上抛: 与原实现 `Regex(...)` 抛异常一致, 由调用方日志通道暴露;
     * 不能静默当“无匹配”, 否则“规则写错”与“页面无该字段”不可区分。
     * 超时则按无匹配处理 (匹配已中止, 取不到值)。
     */
    private fun findFirst(res: String, pattern: String): RuleMatchSpans? = try {
        RuleRegexes.get().find(res, pattern, multiline = false, timeout = RULE_REGEX_TIMEOUT)
    } catch (e: RegexTimeoutException) {
        null
    }

    private fun findAll(res: String, pattern: String): List<RuleMatchSpans> = try {
        RuleRegexes.get().findAll(res, pattern, multiline = false, timeout = RULE_REGEX_TIMEOUT)
    } catch (e: RegexTimeoutException) {
        emptyList()
    }

    /** 规则正则的单次执行时间上限 (毫秒), 与 [AnalyzeRuleCore] 同口径。 */
    private const val RULE_REGEX_TIMEOUT = 3000L
}
