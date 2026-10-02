package io.legado.app.ui.compose.component.code

import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTreeTest {

    private val html = """
        <!DOCTYPE html>
        <html>
        <head><title>t</title></head>
        <body>
            <!-- page body -->
            <div id="main" class="content wrap">
                <p>first</p>
                <p>second</p>
                <span>tail</span>
            </div>
        </body>
        </html>
    """.trimIndent()

    private fun parse(): List<HtmlNode> = buildTreeRoots(Ksoup.parse(html))

    private fun parseBody(html: String): HtmlNode.Element =
        buildTreeRoots(Ksoup.parse(html))
            .filterIsInstance<HtmlNode.Element>()
            .first { it.tagName == "html" }
            .children.filterIsInstance<HtmlNode.Element>()
            .first { it.tagName == "body" }

    @Test
    fun `roots keep doctype and skip blank text nodes`() {
        val roots = parse()
        assertTrue(roots[0] is HtmlNode.Doctype)
        val htmlNode = roots.filterIsInstance<HtmlNode.Element>().first()
        assertEquals("html", htmlNode.tagName)
        // 空白文本节点全部跳过: html 的可见子节点是 head/body 两个元素
        assertEquals(
            listOf("head", "body"),
            htmlNode.children.filterIsInstance<HtmlNode.Element>().map { it.tagName },
        )
    }

    @Test
    fun `element carries id class and inline text`() {
        val body = parseBody(html)
        val div = body.children.filterIsInstance<HtmlNode.Element>().first()
        assertEquals("main", div.idAttr)
        assertEquals(listOf("content", "wrap"), div.classNames)
        // 单文本子元素内联 (DevTools 同款 <p>first</p> 单行), 不再单独占行
        val firstP = div.children.filterIsInstance<HtmlNode.Element>().first()
        assertEquals("first", firstP.inlineText)
        assertTrue(firstP.children.isEmpty())
        assertFalse(firstP.isCollapsible)
        // 注释行保留在 body 下
        assertTrue(body.children.any { it is HtmlNode.Comment && it.full == " page body " })
    }

    @Test
    fun `void and empty elements are not collapsible`() {
        val body = parseBody("<html><body><br><div></div></body></html>")
        val br = body.children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "br" }
        val div = body.children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "div" }

        assertTrue(br.isVoid)
        assertFalse(br.isCollapsible)
        assertTrue(div.children.isEmpty())
        assertFalse(div.isCollapsible)
    }

    @Test
    fun `flatten shows first level collapsed below`() {
        val roots = parse()
        val expanded = roots.filterIsInstance<HtmlNode.Element>().mapTo(mutableSetOf()) { it.id }
        val rows = flatten(roots, expanded)
        // 第一层可见: doctype + html + html 的子层 (head/body)
        assertTrue(rows.any { it is HtmlNode.Element && it.tagName == "body" })
        // 第二层不可见: div 还没展开
        assertFalse(rows.any { it is HtmlNode.Element && it.tagName == "div" })
    }

    @Test
    fun `expanded element ends with its own end tag row`() {
        val roots = parse()
        val expanded = roots.filterIsInstance<HtmlNode.Element>().mapTo(mutableSetOf()) { it.id }
        // 必须取同一棵树实例 (引用断言 element === body 跨两次 parse 不成立)
        val body = roots.filterIsInstance<HtmlNode.Element>()
            .first { it.tagName == "html" }
            .children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "body" }

        expanded.add(body.id)
        val rows = flatten(roots, expanded)
        val bodyIndex = rows.indexOfFirst { it is HtmlNode.Element && it.tagName == "body" }
        // 闭合标签行在 body 的所有子行之后, 与起始行同层
        val lastChildIndex = rows.indexOfLast { it is HtmlNode.EndTag && it.element === body }
        assertTrue(lastChildIndex > bodyIndex)
        assertEquals(body.level, (rows[lastChildIndex] as HtmlNode.EndTag).level)
        // 行 id 为负, 与元素行互斥
        assertEquals(-body.id, rows[lastChildIndex].id)
    }

    @Test
    fun `end tag ids never collide with element ids`() {
        // 回归: 无 DOCTYPE 片段规范化后 html 元素拿到首 id, 若从 0 起步则
        // EndTag 的负 id (-0L==0L) 与元素行撞 LazyColumn key 必崩
        val body = parseBody("<div>hello</div>")
        val htmlEl = body.parent!!
        val rows = flatten(listOf(htmlEl), setOf(htmlEl.id))
        val keys = rows.map { it.id }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it != 0L })
    }

    @Test
    fun `toggle expands and collapses subtree`() {
        val roots = parse()
        val expanded = roots.filterIsInstance<HtmlNode.Element>().mapTo(mutableSetOf()) { it.id }
        val body = parseBody(html)

        expanded.add(body.id)
        val expandedRows = flatten(roots, expanded)
        assertTrue(expandedRows.any { it is HtmlNode.Element && it.tagName == "div" })

        expanded.remove(body.id)
        assertFalse(flatten(roots, expanded).any { it is HtmlNode.Element && it.tagName == "div" })
    }

    @Test
    fun `css selector uses id and nth-of-type`() {
        val htmlEl = parse().filterIsInstance<HtmlNode.Element>().first { it.tagName == "html" }
        val body = htmlEl.children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "body" }
        val div = body.children.filterIsInstance<HtmlNode.Element>().first()

        assertEquals("html > body > div#main.content", div.cssSelector())
        // html/body 无 id 无 class 且同层唯一: 不补 nth-of-type
        assertEquals("html > body", body.cssSelector())
    }

    @Test
    fun `css selector disambiguates same-name siblings`() {
        // 整文档解析会补全 html/head/body, 需从 body 下取 div
        val div = parseBody("<html><body><div><p>a</p><p>b</p></div></body></html>")
            .children.filterIsInstance<HtmlNode.Element>().first()
        val secondP = div.children.filterIsInstance<HtmlNode.Element>().last()
        // 整路径语义: 从文档根 html 起拼
        assertEquals("html > body > div > p:nth-of-type(2)", secondP.cssSelector())
    }

    @Test
    fun `raw text elements keep content as collapsible text row`() {
        val body = parseBody("<html><body><script>var a=1;\nif (x) {y()}</script></body></html>")
        val script = body.children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "script" }

        // DataNode (script/style 等 raw text 内容) 映射为 Text 行: 可折叠展开、原样不折叠空白、
        // 不内联 (否则超长脚本不可达), DevTools 同款
        assertTrue(script.isCollapsible)
        assertNull(script.inlineText)
        val content = script.children.filterIsInstance<HtmlNode.Text>().single()
        assertEquals("var a=1;\nif (x) {y()}", content.fullText)
    }

    @Test
    fun `parent chain backs real path from root`() {
        val htmlEl = parse().filterIsInstance<HtmlNode.Element>().first { it.tagName == "html" }
        val body = htmlEl.children.filterIsInstance<HtmlNode.Element>().first { it.tagName == "body" }
        val div = body.children.filterIsInstance<HtmlNode.Element>().first()

        assertEquals(listOf("html", "body", "div"), div.chain().map { it.tagName })
        // 顶层元素 parent 为 null, 链到自身即止
        assertEquals(listOf("html"), htmlEl.chain().map { it.tagName })
        assertNull(htmlEl.parent)
    }

    private fun parser(): HtmlTreeParser = HtmlTreeParser(
        html = html,
        defaultDispatcher = Dispatchers.Unconfined,
        mainDispatcher = Dispatchers.Unconfined,
    )

    /** 展开到 body 之下的 div 可见 (初始 expanded 只含顶层根, 深层需逐层展开) */
    private fun readyWithDiv(p: HtmlTreeParser): Pair<HtmlTreeParserState.Ready, HtmlNode.Element> {
        var ready = p.state.value as HtmlTreeParserState.Ready
        val body = ready.visibleRows.filterIsInstance<HtmlNode.Element>().first { it.tagName == "body" }
        p.toggle(body)
        ready = p.state.value as HtmlTreeParserState.Ready
        val div = ready.visibleRows.filterIsInstance<HtmlNode.Element>().first { it.tagName == "div" }
        return ready to div
    }

    @Test
    fun `parser init keeps only root level expanded`() = runBlocking {
        val p = parser()
        p.init()
        val ready = p.state.value as HtmlTreeParserState.Ready
        // 初始态: 整份文档作显示根, 只展开顶层根 (body 之下不可见)
        assertNull(ready.displayRootLevel)
        // 同源赋值: 两字段必须是同一实例 (显示根未聚焦时即全量树根)
        assertSame(ready.treeRoots, ready.displayRoots)
        assertTrue(ready.visibleRows.any { it is HtmlNode.Element && it.tagName == "body" })
        assertFalse(ready.visibleRows.any { it is HtmlNode.Element && it.tagName == "div" })
    }

    @Test
    fun `parser toggle collapses then restores subtree`() = runBlocking {
        val p = parser()
        p.init()
        val (before, div) = readyWithDiv(p)
        assertTrue(div.isCollapsible)
        // 展开 body 后 div 作为折叠行可见, 但自身尚未展开
        assertFalse(div.id in before.expanded)
        assertFalse(before.visibleRows.any { it is HtmlNode.Element && it.tagName == "span" })

        p.toggle(div)
        val expandedState = p.state.value as HtmlTreeParserState.Ready
        assertTrue(div.id in expandedState.expanded)
        assertTrue(expandedState.visibleRows.any { it is HtmlNode.Element && it.tagName == "span" })

        p.toggle(div)
        val collapsed = p.state.value as HtmlTreeParserState.Ready
        assertFalse(div.id in collapsed.expanded)
        assertFalse(collapsed.visibleRows.any { it is HtmlNode.Element && it.tagName == "span" })
    }

    @Test
    fun `parser focus switches display root and restores`() = runBlocking {
        val p = parser()
        p.init()
        val (_, div) = readyWithDiv(p)

        p.focus(div)
        val focused = p.state.value as HtmlTreeParserState.Ready
        assertEquals(listOf<HtmlNode>(div), focused.displayRoots)
        assertEquals(div.level, focused.displayRootLevel)
        assertTrue(div.id in focused.expanded)

        p.focus(null)
        val back = p.state.value as HtmlTreeParserState.Ready
        assertSame(back.treeRoots, back.displayRoots)
        assertNull(back.displayRootLevel)
    }
}
