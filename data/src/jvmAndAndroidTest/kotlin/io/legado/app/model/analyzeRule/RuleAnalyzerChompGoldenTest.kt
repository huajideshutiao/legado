package io.legado.app.model.analyzeRule

import io.legado.app.utils.scan.BalanceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RuleAnalyzer` 两个平衡组函数改走 [BalanceScan] 后的对拍金样。
 *
 * 期望值不由人推：[Legacy] 是**改动前 HEAD 版 `RuleAnalyzer` 扫描语句的逐字副本**
 * （只把「返回 Boolean + 回写字段」改成「返回新 pos / -1」，判断链一字未动），
 * 本用例断言的是「共用件与原实现结果完全一致」。
 *
 * 唯一被允许的偏离见 [代码模式尾部反斜杠不再越界崩溃]：原实现在「反斜杠把游标顶出串尾且
 * 仍未平衡」时会在下一轮 `text[pos++]` 抛越界，这里把该情形显式记为 [CRASH] 并要求共用件
 * 返回 -1（未平衡）；其余情形要求逐位一致。
 */
class RuleAnalyzerChompGoldenTest {

    private object Legacy {

        private const val ESC = '\\'

        /** 原 chompCodeBalanced：`[]` 恒计层，传入对仅在主对归零时计层，反斜杠在引号内外都生效。 */
        fun code(text: String, from: Int, open: Char, close: Char): Int {
            var pos = from
            var depth = 0
            var otherDepth = 0
            var inSingleQuote = false
            var inDoubleQuote = false
            do {
                if (pos == text.length) break
                val c = text[pos++]
                if (c != ESC) {
                    if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
                    else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
                    if (inSingleQuote || inDoubleQuote) continue
                    if (c == '[') depth++
                    else if (c == ']') depth--
                    else if (depth == 0) {
                        if (c == open) otherDepth++
                        else if (c == close) otherDepth--
                    }
                } else pos++
            } while (depth > 0 || otherDepth > 0)
            return if (depth > 0 || otherDepth > 0) UNBALANCED else pos
        }

        /** 原 chompRuleBalanced：只计传入那一对，反斜杠仅引号外生效。 */
        fun rule(text: String, from: Int, open: Char, close: Char): Int {
            var pos = from
            var depth = 0
            var inSingleQuote = false
            var inDoubleQuote = false
            do {
                if (pos == text.length) break
                val c = text[pos++]
                if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
                else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
                if (inSingleQuote || inDoubleQuote) continue
                else if (c == '\\') {
                    pos++
                    continue
                }
                if (c == open) depth++
                else if (c == close) depth--
            } while (depth > 0)
            return if (depth > 0) UNBALANCED else pos
        }
    }

    private fun runNew(text: String, from: Int, open: Char, close: Char, code: Boolean): Int =
        if (code) {
            BalanceScan.chomp(
                text, from, open, close,
                primaryOpen = '[', primaryClose = ']',
                escape = BalanceScan.Escape.ALWAYS
            )
        } else {
            BalanceScan.chomp(
                text, from, open, close,
                escape = BalanceScan.Escape.OUTSIDE_QUOTES
            )
        }

    /** 跑原实现，把越界崩溃单独归类，便于区分「行为差异」与「崩溃被消除」。 */
    private fun runLegacy(text: String, from: Int, open: Char, close: Char, code: Boolean): Int =
        try {
            if (code) Legacy.code(text, from, open, close) else Legacy.rule(text, from, open, close)
        } catch (e: IndexOutOfBoundsException) {
            CRASH
        }

    /**
     * 语料覆盖：嵌套、引号内符号与分隔符、转义（引号内外）、未闭合、多余闭合符、
     * `[]` 与 `()`/`{}` 混叠、反斜杠顶出串尾、真实书源规则形态。
     */
    private val corpus = listOf(
        "[a]",
        "[a][b]",
        "[[[a]]]",
        "[(a)]",
        "[a[(b)c]d]",
        "(a)",
        "((a)(b))",
        "(a[b]c)",
        "(a[)",
        "[a)",
        "{a}",
        "{$.name}",
        "{{$.a{b}}}",
        "{a[b]}",
        "[title=\"a&&b\"]",
        "(text()=\"a||b\")",
        "[@id='x)y']",
        "[@id=\"a\\\"b\"]",
        "[a\\]b]",
        "(a\\)b)",
        "{a\\}b}",
        "[a']b\\']c]",
        "(a\"]b\")",
        "[a\\",
        "(a\\",
        "{a\\",
        "\\\\[a]",
        "[]",
        "()",
        "{}",
        "[",
        "(",
        "{",
        "]",
        "[a]]",
        "[(]",
        "(])",
        "[a[b]]",
        "@css:.tab&&@text",
        "$.data.list[0].name",
        "$[?(@.id==1&&@.n=='x')].t",
        "text.0<js>var a = [1,2];</js>@text",
        "{{$.a}}&&{{$.b}}",
        "[a\\'b]",
        "(a\\'b)",
        "{a\"b'c\"d}",
        "[a'\\''b]",
    )

    /** 原实现里 chomp 只会以 `[`/`(` 起（splitRule）与 `{` 起（innerRule），三种全覆盖。 */
    private val pairs = listOf('[' to ']', '(' to ')', '{' to '}')

    private fun assertEqualOnCorpus(code: Boolean) {
        var checked = 0
        var crashed = 0
        for (text in corpus) {
            for ((open, close) in pairs) {
                for (from in text.indices.filter { text[it] == open }) {
                    val old = runLegacy(text, from, open, close, code)
                    val new = runNew(text, from, open, close, code)
                    val label = "code=$code text=<$text> from=$from pair=<$open><$close>"
                    when {
                        old == CRASH -> {
                            crashed++
                            assertEquals(
                                "$label 原实现越界崩溃, 共用件应按未平衡返回",
                                UNBALANCED, new
                            )
                        }

                        else -> assertEquals(label, old, new)
                    }
                    checked++
                }
            }
        }
        //阀值故意放低：它只防“语料空跑”，不是性能断言
        assertTrue("语料未覆盖到足够多起点, 用例写坏了", checked > 60)
        assertTrue("语料里应至少一例触发原实现越界崩溃", crashed >= 1)
    }

    @Test
    fun `代码模式与原实现逐位一致`() {
        assertEqualOnCorpus(code = true)
    }

    @Test
    fun `规则模式与原实现逐位一致`() {
        assertEqualOnCorpus(code = false)
    }

    /** 先确认崩溃前提真实存在，否则上一条里的 CRASH 分支是空跑的。 */
    @Test
    fun `语料确认原实现存在越界崩溃路径`() {
        val text = "[a\\"
        assertEquals(CRASH, runLegacy(text, 0, '[', ']', code = true))
        assertEquals(UNBALANCED, runNew(text, 0, '[', ']', code = true))
    }

    /**
     * 未平衡时不许推进调用方游标：`chompBalanced` 是公开成员（AnalyzeByJSoup/XPath 直接调），
     * 反复调用结果必须稳定。
     */
    @Test
    fun `未平衡返回 false 且反复调用结果稳定`() {
        val ra = RuleAnalyzer("[a", code = true)
        assertFalse(ra.chompBalanced('[', ']'))
        assertFalse(ra.chompBalanced('[', ']'))
    }

    @Test
    fun `平衡返回 true 后游标停在组结束符之后`() {
        val ra = RuleAnalyzer("[a]&&b", code = true)
        assertTrue(ra.chompBalanced('[', ']'))
        //游标已越过 `]`，从这里继续切能拿到后半段
        assertEquals(listOf("[a]", "b"), ra.splitRule("&&", "||", "%%").toList())
    }

    /**
     * 引号保护：选择器里的 `&&` 不得被当成分隔符。这条直接钉住"引号内符号不计层"的语义，
     * 是本次收拢最容易改坏书源规则的地方。
     */
    @Test
    fun `选择器引号内的分隔符不参与切分`() {
        val rules = RuleAnalyzer("[title=\"a&&b\"]&&c").splitRule("&&", "||", "%%")
        assertEquals(listOf("[title=\"a&&b\"]", "c"), rules.toList())
    }

    @Test
    fun `未闭合的选择器在切分时抛错`() {
        val ra = RuleAnalyzer("a[b&&c")
        val err = runCatching { ra.splitRule("&&", "||", "%%") }.exceptionOrNull()
        assertTrue("未平衡应抛 Error", err is Error)
        assertEquals("后未平衡", err?.message)
    }

    @Test
    fun `内嵌规则未平衡时按普通字串跳过`() {
        //fr 一律返回 null：innerRule 走 `pos += inner.length` 分支，整体返回空串（startX 仍为 0）
        val ra = RuleAnalyzer("pre{$.a", code = true)
        assertEquals("", ra.innerRule("{$.") { null })
    }

    @Test
    fun `内嵌规则命中时替换并保留前后文`() {
        val ra = RuleAnalyzer("pre{$.a}post", code = true)
        assertEquals("preXpost", ra.innerRule("{$.") { "X" })
    }
}

/** 与 `RuleAnalyzer` 原实现「未平衡」对应的返回值；共用件同样用 -1 表示未平衡。 */
private const val UNBALANCED = -1

/** 原实现越界崩溃的归类值，不可能与合法下标相撞。 */
private const val CRASH = Int.MIN_VALUE
