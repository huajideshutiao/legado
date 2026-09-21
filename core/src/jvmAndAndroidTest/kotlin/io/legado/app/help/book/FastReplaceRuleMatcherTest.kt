package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FastReplaceRuleMatcher 短路探测金样（保守版契约）。
 *
 * 契约：只有「不含正则元字符的纯字面量 pattern」才允许 indexOf 短路；pattern 里出现任何元字符
 * （`\ [ ] ( ) { } | ? * + . ^ $`）就一律不得短路，让正则引擎照跑 ——
 * 漏一次替换（False Negative）比多跑一次正则严重得多。
 *
 * 后面几组按具体形状分开写：它们是历史上真实漏判过的 pattern（字符类里的 `)`、
 * 被可空量词修饰的代理对、`\u`/`\x`/`\p` 转义序列），谁将来再把提取器做复杂，
 * 这几条必须仍然不短路。
 */
class FastReplaceRuleMatcherTest {

    /** 反斜杠按码点造：源码里的 `\` 在多层工具间容易被当成转义前缀吞掉。 */
    private val bs = Char(92).toString()

    private fun regexRule(pattern: String) = ReplaceRule(pattern = pattern, isRegex = true)

    private fun plainRule(pattern: String) = ReplaceRule(pattern = pattern, isRegex = false)

    private fun skip(content: String, pattern: String) =
        FastReplaceRuleMatcher.shouldSkip(content, regexRule(pattern))

    /** 空 pattern 没有可替换内容，跳过是唯一正确答案。 */
    @Test
    fun `空 pattern 直接跳过`() {
        assertTrue(skip("任意正文", ""))
        assertTrue(FastReplaceRuleMatcher.shouldSkip("任意正文", plainRule("")))
    }

    /** 纯字面量正则：命中不跳过、未命中跳过。这是整个短路优化唯一被允许生效的场景。 */
    @Test
    fun `纯字面量正则按 indexOf 结论短路`() {
        assertFalse(skip("这里有广告内容", "广告"))
        assertTrue(skip("完全无关的正文", "广告"))
    }

    /**
     * 非正则规则不做探测：`String.replace(oldValue, newValue)` 内部已先 indexOf，
     * 未命中直接返回原串且不分配，外面再扫一遍是纯开销。
     */
    @Test
    fun `非正则规则一律不探测`() {
        assertFalse(FastReplaceRuleMatcher.shouldSkip("完全无关的正文", plainRule("广告")))
    }

    /**
     * 含元字符的 pattern 一律不得短路。
     *
     * 逐条都是「正文里确实没有提取器算出来的那个字面量、但正则未必不命中」的形状，
     * 只要还想从元字符 pattern 里抠字面量，就永远存在下一个反例。
     */
    @Test
    fun `含元字符的 pattern 一律不得短路`() {
        val patterns = listOf(
            "广告.*结束",
            "第[一二三]章",
            "(广告)推广",
            "广告?词",
            "^广告",
            "广告{2}",
            "广告|推广",
            bs + "Q广告" + bs + "E",
            bs + "d{4}年",
        )
        for (pattern in patterns) {
            assertFalse(pattern, skip("完全无关的正文", pattern))
        }
    }

    /**
     * 字符类里的 `)` 不是分组结束符：把它当结束符会让 `关键字` 前面粘上字符类里的 `]`，
     * 提出「]关键字」这种正文里绝不存在的候选串 → 错误跳过。
     */
    @Test
    fun `字符类内的右括号不得让候选串越过分组边界`() {
        assertFalse(skip("前文)关键字后文", "(?:[)])关键字"))
        assertFalse(skip("前文X关键字后文", "[^)]关键字"))
        // 类内转义的 ] 不是类边界
        assertFalse(skip("前文]关键字后文", "[" + bs + "]]关键字"))
    }

    /**
     * 可空量词修饰代理对时必须按码点整体回退：只回退一个 Char 会留下孤立高位代理，
     * 拼出的候选串在任何正文里都匹配不到 → 错误跳过。
     */
    @Test
    fun `代理对被可空量词修饰时不得留下孤立高位代理`() {
        assertFalse(skip("前置文本短", "前置文本😀?短"))
        assertFalse(skip("前置文本短", "前置文本😀{0,2}短"))
        assertFalse(skip("前置文本短", "前置文本😀*短"))
    }

    /**
     * 码点 / 属性转义序列必须整段跳过：只跳 `\x` 两个字符会把后面的十六进制数字
     * 当成正文必含字面量（`本广告` → `672c广告`），正文里永远没有 → 错误跳过。
     */
    @Test
    fun `码点与属性转义序列必须整段跳过`() {
        assertFalse(skip("这是一本广告词", bs + "u672c广告"))
        assertFalse(skip("这是一本广告词", bs + "x{672c}广告"))
        assertFalse(skip("看 A广告 了", bs + "x41广告"))
        assertFalse(skip("这字广告在这", bs + "pL广告"))
        assertFalse(skip("这字广告在这", bs + "p{L}广告"))
    }

    /** 顶层分支选择无法确定哪一支命中，一律不短路。 */
    @Test
    fun `顶层分支一律不短路`() {
        assertFalse(skip("完全无关的正文", "广告|推广"))
        assertFalse(skip("完全无关的正文", "^广告|结束$"))
    }
}
