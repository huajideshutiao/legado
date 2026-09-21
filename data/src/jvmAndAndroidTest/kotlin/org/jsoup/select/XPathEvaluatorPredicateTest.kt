package org.jsoup.select

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
 * 谓词落到 `evaluatePredicate`(private) 后若整串不命中任何已实现形式, 按规则层统一口径
 * 直接 `throw NoStackTraceException("XPath 谓词不支持: …")`（异常类型必须是 Exception 系: 书源入口 `AnalyzeByXPath.getResult` 兜的是 `catch (Exception)`, 抛 Error 会穿出规则层）。
 * 旧实现是末尾 `return true`（等于不过滤）, 会把整个标签集合静默吐给上层。
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
    fun `谓词为裸名字 不支持 按规则层口径抛错`() {
        // segment "div[a]": predicate="a" 不属于任何已实现形式
        // 旧行为: 落到末尾 `return true` → 不过滤 → 把 d1/d2/d3 全量吐出去（静默多选）
        // 现行为: 跟 `RuleAnalyzer.splitRule` 对 `[…后未平衡` 一样直接抛错, 不把坏规则伪装成"没数据"
        assertFailsWith<NoStackTraceException> { ids("//div[a]") }
    }

    @Test
    fun `属性值内含方括号 末位收尾 反而正确`() {
        // segment "div[@class='[x]']": bracketStart=3, bracketEnd=16(最后一个]) → predicate="@class='[x]'"
        // 正则 (.+?) 拉到 "[x]" → attr("class") == "[x]" 命中 d3
        assertEquals(listOf("d3"), ids("//div[@class='[x]']"))
    }

    // ─────────────────── 多谓词 / 嵌套谓词(疑似缺陷, 待重构修正) ───────────────────

    @Test
    fun `两个不支持谓词求交前就抛错`() {
        // segment "div[a][b]": 计数式切分后拿到两个谓词 "a"/"b", 均不支持
        // （旧行为: 两个谓词都被 `return true` 放过 → 全量返回）
        assertFailsWith<NoStackTraceException> { ids("//div[a][b]") }
    }

    @Test
    fun `嵌套非属性谓词不支持 抛错`() {
        // segment "div[a[b=1]]": 计数式取到外层配对收尾 → 谓词 = "a[b=1]", 整串不等于任何已实现形式
        assertFailsWith<NoStackTraceException> { ids("//div[a[b=1]]") }
    }

    @Test
    fun `嵌套属性谓词不再降级为自身属性匹配`() {
        // segment "div[a[@id='d3']]": 谓词 = "a[@id='d3']"
        // 旧行为: 非锚定正则在串内 find 到 @id='d3' → 变成“div 自身 id=d3”→ 误选 d3
        // 现行为: 谓词形式整串匹配不成立 → 不支持 → 抛错（不把错语义伪装成正常工作）
        assertFailsWith<NoStackTraceException> { ids("//div[a[@id='d3']]") }
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
        // Element.selectXpath → XPathEvaluator.evaluateElements, 走同一谓词切分
        assertFailsWith<NoStackTraceException> { doc.selectXpath("//div[a]") } //不支持谓词: 两个入口同样报错, 不静默多选
        assertEquals(listOf("d2"), doc.body().selectXpath("./div[@id='d2']").map { it.attr("id") })
    }

    @Test
    fun `AnalyzeByXPath 书源入口同样吃到该谓词切分结果`() {
        // AnalyzeByXPath(html).getElements("//div[a]") → RuleAnalyzer.splitRule("&&","||","%%")
        // 该串无分隔符 → rules.size == 1 → baseElement.selectXpath(rule, Node::class)
        val nodes = runCatching {
            AnalyzeByXPath(html).getElements("//div[a]")
                ?.filterIsInstance<Element>()
                .orEmpty()
                .map { it.attr("id") }
        }
        //书源真实入口: 坏谓词要么报错, 要么被上层吃掉, 但不得静默变成“全量返回”
        assertTrue(nodes.isFailure || nodes.getOrThrow().isEmpty())
    }

    // ─────────────────── 序号口径与引号边界（XPath 1.0 应然行为） ───────────────────

    private val deepHtml = """
        <html><body>
        <div id="b1"><div id="n1"></div></div>
        <span id="s2"></span>
        <div id="b2"><a id="l1" href="/b/c">L1</a></div>
        <p id="p1" data-src="x.png" class="article novel">P1</p>
        </body></html>
    """.trimIndent()

    private fun idsOf(html: String, xpath: String): List<String> =
        XPathEvaluator.evaluateElements(xpath, Ksoup.parse(html)).map { it.attr("id") }

    @Test
    fun `纯数字与 position()=n 与 last() 共用同名兄弟 1 基序号`() {
        val body = """<body><div id="a"></div><span id="s"></span><div id="b"></div><div id="c"></div></body>"""
        // 同名兄弟计数: span 不得占位（旧实现 [n] 按全部子元素下标取）
        assertEquals(listOf("a"), idsOf(body, "//div[1]"))
        assertEquals(listOf("b"), idsOf(body, "//div[2]"))
        assertEquals(listOf("c"), idsOf(body, "//div[3]"))
        assertEquals(idsOf(body, "//div[1]"), idsOf(body, "//div[position()=1]"))
        assertEquals(idsOf(body, "//div[3]"), idsOf(body, "//div[last()]"))
        assertEquals(idsOf(body, "//div[3]"), idsOf(body, "//div[position()=last()]"))
        // 不存在的序号与 0 基写法一律空结果, 不绕回首位
        assertTrue(idsOf(body, "//div[4]").isEmpty())
        assertTrue(idsOf(body, "//div[0]").isEmpty())
    }

    @Test
    fun `contains() 不被属性存在分支抢先`() {
        val body = """
            <body><p id="hit" class="article novel">H</p><p id="miss" class="article">M</p><p id="none">N</p></body>
        """.trimIndent()
        // 旧顺序下 exists 分支先 return hasAttr("class") → 三个 p 全命中
        assertEquals(listOf("hit"), idsOf(body, "//p[contains(@class,'novel')]"))
        assertEquals(listOf("hit", "miss"), idsOf(body, "//p[contains(@class,'article')]"))
        //存在性与等值仍各自成立
        assertEquals(listOf("hit", "miss", "none"), idsOf(body, "//p"))
        assertEquals(listOf("hit", "miss"), idsOf(body, "//p[@class]"))
    }

    @Test
    fun `引号内的斜杠不作层级分隔符`() {
        // 旧实现 path.split("/") 把 `a[@href='/b/c']` 切成三截 → getElementsByTag("a[@href='") 空
        assertEquals(listOf("l1"), idsOf(deepHtml, "//a[@href='/b/c']"))
        // `/` 与 `//` 两种层级仍按原口径工作（getElementsByTag 本身含全部后代）
        assertEquals(listOf("n1"), idsOf(deepHtml, "//div//div"))
        assertEquals(listOf("s2"), idsOf(deepHtml, "//body/span"))
    }

    @Test
    fun `属性名可含连字符`() {
        assertEquals(listOf("p1"), idsOf(deepHtml, "//p[@data-src='x.png']"))
        //旧实现的 @(\w+) 不认 '-', 该谓词落到兜底 → 全量返回
        assertEquals(listOf("p1"), idsOf(deepHtml, "//p[@data-src]"))
    }

    @Test
    fun `谓词两侧空白与属性值内含引号边界`() {
        assertEquals(listOf("b2"), idsOf(deepHtml, "//div[ @id = 'b2' ]"))
        assertEquals(listOf("p1"), idsOf(deepHtml, "//p[ contains(@class, 'novel') ]"))
    }

    @Test
    fun `不支持的 text 类谓词不静默多选`() {
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//p[text()='P1']") }
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//div[contains(text(),'x')]") }
    }

    // ─────────────────── 比较符 / and-or / not / starts-with（本轮补齐的合法形态） ───────────────────

    @Test
    fun `不等与大小比较`() {
        val body = """<body><i id="i1" v="1"></i><i id="i2" v="9"></i><i id="i3" v=""></i><i id="i4"></i></body>"""
        // 无 v 属性的 i4 是空节点集: 参与相等/关系比较恒 false (规范 §3.1)
        assertEquals(listOf("i2", "i3"), idsOf(body, "//i[@v!='1']"))
        assertEquals(listOf("i1"), idsOf(body, "//i[@v<5]"))
        assertEquals(listOf("i2"), idsOf(body, "//i[@v>=5]"))
        // 属性缺失 ≠ 属性值为空串: 旧实现用 attr() 比, 会把 i4 也当命中
        assertEquals(listOf("i3"), idsOf(body, "//i[@v='']"))
    }

    @Test
    fun `and 与 or 的优先级与分组`() {
        val body = """<body><r id="a" x="1" y="1"></r><r id="b" x="1"></r><r id="c" y="1"></r><r id="d"></r></body>"""
        assertEquals(listOf("a"), idsOf(body, "//r[@x and @y]"))
        assertEquals(listOf("a", "b", "c"), idsOf(body, "//r[@x or @y]"))
        // or 优先级低于 and: `@x or @y and @nope` == `@x or (@y and @nope)`
        assertEquals(listOf("a", "b"), idsOf(body, "//r[@x or @y and @nope]"))
        assertEquals(listOf("c"), idsOf(body, "//r[(@x or @y) and not(@x)]"))
    }

    @Test
    fun `not 与 starts-with`() {
        val body = """<body><s id="h1" href="http://a"></s><s id="h2" href="ftp://b"></s><s id="h3"></s></body>"""
        assertEquals(listOf("h1"), idsOf(body, "//s[starts-with(@href,'http')]"))
        assertEquals(listOf("h2", "h3"), idsOf(body, "//s[not(starts-with(@href,'http'))]"))
        assertEquals(listOf("h3"), idsOf(body, "//s[not(@href)]"))
        assertEquals(listOf("h1", "h2"), idsOf(body, "//s[@href and not(@id='h3')]"))
    }

    @Test
    fun `位置比较与布尔字面量`() {
        val body = """<body><div id="a"></div><div id="b"></div><div id="c"></div></body>"""
        assertEquals(listOf("b", "c"), idsOf(body, "//div[position()>1]"))
        assertEquals(listOf("a", "b"), idsOf(body, "//div[position()<last()]"))
        assertEquals(listOf("b"), idsOf(body, "//div[position()>=2 and position()<=2]"))
        assertEquals(listOf("a", "b", "c"), idsOf(body, "//div[true()]"))
        assertTrue(idsOf(body, "//div[false()]").isEmpty())
    }

    @Test
    fun `未支持形态与坏语法仍抛错`() {
        // 节点测试/轴/未实现函数: 都是合法 XPath 1.0, 只是本件子集没做 → 抛错而不是静默多选
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//div[*]") }
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//div[string-length(@id)>1]") }
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//div[@id='b1' extra]") }
        assertFailsWith<NoStackTraceException> { idsOf(deepHtml, "//div[not(@id='b1']") }
    }
}

