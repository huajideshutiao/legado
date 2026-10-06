package io.legado.app.help

/**
 * 关键词高亮匹配 (纯函数, 无平台依赖)。
 *
 * 输入文本口径与渲染一致 (净化后排版输入文本, 即 `TextPage.text` 顺序拼接),
 * 输出章内半开区间可直接映射为阅读层 HighlightOverlay。
 *
 * 函数签名按"规则集→区间列表"设计: 将来升级正则匹配只改本对象内部 (含超时保护),
 * 调用方不动。字面量匹配为线性 indexOf 推进, 无超时保护。
 *
 * 输出按区间起点升序: 不同规则的区间可互相重叠 (每条规则内部才是不重叠推进),
 * 重叠处按输出序叠色, 故叠色次序不再取决于规则表顺序。
 */
object KeywordMatcher {

    /** 一条命中: 章内半开区间 [start, endExclusive) + 来源规则 + 上色/线型 */
    data class Match(
        val start: Int,
        val endExclusive: Int,
        val ruleId: Long,
        val color: Int?,
        val lineStyle: Int,
    )

    /** 由实体映射而来的纯规则 (与存储解耦, 便于测试与后续正则升级) */
    data class Rule(
        val id: Long,
        val word: String,
        val color: Int?,
        val lineStyle: Int,
    )

    /** 逐规则不重叠推进: 每次命中后从命中终点继续搜, 同词重叠处只取靠前一处 */
    fun match(text: String, rules: List<Rule>): List<Match> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (rule in rules) {
            val word = rule.word
            if (word.isEmpty()) continue
            var from = 0
            while (from <= text.length) {
                val index = text.indexOf(word, from)
                if (index < 0) break
                out.add(Match(index, index + word.length, rule.id, rule.color, rule.lineStyle))
                from = index + word.length
            }
        }
        if (out.size <= 1) return out
        // 同起点保持规则表次序 (sortedBy 为稳定排序): 叠色次序可预期
        out.sortBy { it.start }
        return out
    }
}
