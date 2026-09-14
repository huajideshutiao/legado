package org.jsoup.select

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [XPathEvaluator] 谓词切分现状快照。切分已收拢到 `BalanceScan.chomp`（计数式 + 多谓词求交），
 * 本文件同时保留“旧 `indexOf('[')` + `lastIndexOf(']')` 下的可观测结果”作为等价基线：
 * 两者在单谓词/嵌套形态上结果相同，仅多谓词求交类用例（见`多谓词 *`）存在有意的行为差异。
 *
 * `parsePredicate` / `parsePathParts` 均为 private, 只能从对外可达入口观测:
 * - [XPathEvaluator.evaluateElements] / [XPathEvaluator.evaluate](companion, 本文件主用)
 * - `selectXpath`(Element.selectXpath 扩展, 同包)
 * - `AnalyzeByXPath.getElements`(书源规则真实入口: AnalyzeByXPath → RuleAnalyzer.splitRule → getResult → selectXpath)
 * 谓词落到 `evaluatePredicate`(private) 后若一条正则都不命中, 走末尾 `return true` 兜底(等于不过滤)。
 *
 * 观测口径: tag 决定 `getElementsByTag(tag)` 能捞到哪些标签, predicate 决定过滤结果,
 * 两者共同体现在"返回的 id 序列"上; 每条用例注释里给出 `parsePredicate` 的实际 tag / predicate 切片。
 *
 * DOM 见 [html]: d1(无子元素) / d2(含 a+b) / d3(class="[x]") / s1(span, 含 a)。
 */
class XPathEvaluatorPredicateTest {

    private val html = """
        <html><body>
        <div id="d1">T1</div>
        <div id="d2"><a>A</a><b>B</b></div>
        <div id="d3" class="[x]">T3</div>
        <span id="s1"><a>A</a></span>
        </body></html>
    """.trimIndent()

    private val doc = Ksoup.parse(html)

    private fun ids(xpath: String): List<String> =
        XPathEvaluator.evaluateElements(xpath, doc).map { it.attr("id") }

    // ───────────────────────── 简单谓词(现状正确/兜底) ─────────────────────────

    @Test
    fun `单谓词 属性等值 按自身属性过滤`() {
        // segment "div[@id='d2']": 单谓词 → tag="div", predicates=["@id='d2'"]（旧版切片相同）
        // attrPattern 命中 → attr("id") == "d2"
        assertEquals(listOf("d2"), ids("//div[@id='d2']"))
    }

    @Test
    fun `谓词为裸名字 正则全不命中 兜底 return true 不过滤`() {
        // segment "div[a]": tag="div", predicate="a"
        // 现状: 所有 div 都保留(d1/d2/d3), span 因 tag 过滤被排除 → 证明 tag 取的是第一个 '[' 之前
        assertEquals(listOf("d1", "d2", "d3"), ids("//div[a]"))
    }

    @Test
    fun `属性值内含方括号 末位收尾 反而正确`() {
        // segment "div[@class='[x]']": bracketStart=3, bracketEnd=16(最后一个]) → predicate="@class='[x]'"
        // 正则 (.+?) 拉到 "[x]" → attr("class") == "[x]" 命中 d3
        assertEquals(listOf("d3"), ids("//div[@class='[x]']"))
    }

    // ─────────────────── 多谓词 / 嵌套谓词(疑似缺陷, 待重构修正) ───────────────────

    @Test
    fun `两个谓词被并成一个垃圾谓词 疑似缺陷 待重构修正`() {
        // segment "div[a][b]": 现在拿到的 tag="div" + **两个**谓词 "a"/"b"(旧版切成垃圾谓词 `a][b`),
        // 但 "a"/"b" 不属于已支持的谓词形式 → evaluatePredicate 兜底 true → 两个谓词都不过滤,
        // 所有 div 仍被选中(与旧版 observable 相同;切分已正确, 缺的是谓词语言本身)
        assertEquals(listOf("d1", "d2", "d3"), ids("//div[a][b]"))
    }

    @Test
    fun `嵌套非属性谓词被压平后失效 疑似缺陷 待重构修正`() {
        // segment "div[a[b=1]]": 计数式取到配对收尾 → 谓词 = "a[b=1]"(旧版 lastIndexOf 巧合同结果)
        // 无 '@' → 全部正则不命中 → 兜底 true → "含子元素 a 且 a 的 b=1" 语义仍丢失
        assertEquals(listOf("d1", "d2", "d3"), ids("//div[a[b=1]]"))
    }

    @Test
    fun `嵌套属性谓词降级为自身属性匹配 疑似缺陷 待重构修正`() {
        // segment "div[a[@id='d3']]": 计数式取到外层配对收尾 → 谓词 = "a[@id='d3']"(与旧版同结果)
        // attrPattern 在串内仍找到 @id='d3' → 变成"div 自身 id=d3", 与"含子节点 a 且 a 的 id=d3"完全跑偏
        assertEquals(listOf("d3"), ids("//div[a[@id='d3']]"))
    }

    @Test
    fun `残缺左方括号 tag 里带上方括号 匹配不到任何元素`() {
        // segment "div[": 无配对 `]` → parsePredicates 原样返回 segment to emptyList()
        // → PathPart.TagWithPredicate(tag="div[", predicates=[]) → getElementsByTag("div[") 空
        assertTrue(ids("//div[").isEmpty())
    }

    @Test
    fun `残缺形态不影响其它段续扫`() {
        // "//div[" 段空结果, "//div[@id='d1']" 独立求值
        assertEquals(listOf("d1"), ids("//div[@id='d1']"))
        assertEquals(listOf<String>(), ids("//span["))
    }

    @Test
    fun `多谓词 互斥属性谓词求交后谁都不选`() {
        //收拢修正的可观测差异：旧版 lastIndexOf 把 `@id='d1'][@id='d2'` 当成**一个**谓词,
        //attrPattern 只认到第一个 → 误选 d1；现在两个谓词各自生效求交 → 空
        //(对齐 archive: 原版用 jsoup 原生 selectXpath, 本就要求交)
        assertEquals(0, ids("//div[@id='d1'][@id='d2']").size)
    }

    @Test
    fun `多谓词 两个条件都成立才选中`() {
        assertEquals(listOf("d3"), ids("//div[@id='d3'][@class='[x]']"))
    }

    @Test
    fun `多谓词 同一属性重复限定不改变结果`() {
        assertEquals(listOf("d2"), ids("//div[@id='d2'][@id='d2']"))
    }

    // ───────────────────────── 公开入口等价性(现状快照) ─────────────────────────

    @Test
    fun `selectXpath 扩展入口与 companion 入口结果一致`() {
        // Element.selectXpath → XPathEvaluator.evaluateElements, 走同一 parsePredicate
        assertEquals(listOf("d1", "d2", "d3"), doc.selectXpath("//div[a]").map { it.attr("id") })
        assertEquals(listOf("d2"), doc.body().selectXpath("./div[@id='d2']").map { it.attr("id") })
    }

    @Test
    fun `AnalyzeByXPath 书源入口同样吃到该谓词切分结果`() {
        // AnalyzeByXPath(html).getElements("//div[a]") → RuleAnalyzer.splitRule("&&","||","%%")
        // 该串无分隔符 → rules.size == 1 → baseElement.selectXpath(rule, Node::class)
        val nodes = AnalyzeByXPath(html).getElements("//div[a]")
            ?.filterIsInstance<Element>()
            .orEmpty()
        assertEquals(listOf("d1", "d2", "d3"), nodes.map { it.attr("id") })
    }
}
