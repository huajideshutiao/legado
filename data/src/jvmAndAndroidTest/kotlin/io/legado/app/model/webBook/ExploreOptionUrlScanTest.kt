package io.legado.app.model.webBook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [parseExploreOptionsFromUrl] / [replaceExploreOptionsInUrl] /
 * [replaceExploreOptionsWithSelection] 的扫描与选择契约。
 *
 * 覆盖: 声明形态、同名一致性、每段包装共用原始选择、重复取值别名、空值多选标题、
 * 单选空值省略段、畸形声明的位置诊断, 以及定义更新时的有效选择沿用。
 */
class ExploreOptionUrlScanTest {

    // ─────────────────────────── 1. 基础形态 + 同名定义 ───────────────────────────

    @Test
    fun `基础形态与默认单选`() {
        val opt = parseExploreOptionsFromUrl("https://x.com/list<type(男:1,女:2)>").single()
        assertEquals("type", opt.name)
        assertEquals(listOf("男" to "1", "女" to "2"), opt.options)
        assertEquals(false, opt.multiSelect)
        assertEquals("", opt.separator)
        assertEquals("", opt.prefix)
        assertEquals("", opt.suffix)
        assertEquals("1", opt.selectedValue)
        assertEquals("1", opt.resolvedValue)
        assertTrue(opt.selectedValues.isEmpty())
    }

    @Test
    fun `同名且定义一致时只保留一份`() {
        val opts = parseExploreOptionsFromUrl("<a(x:1,y:2)><a(x:1,y:2)>")
        assertEquals(1, opts.size)
        assertEquals(listOf("x" to "1", "y" to "2"), opts[0].options)
    }

    @Test
    fun `同名但可选项不同时报位置诊断`() {
        val error = assertFailsWith<ExploreOptionSyntaxException> {
            parseExploreOptionsFromUrl("<a(x:1,y:2)><a(z:3)>")
        }
        assertEquals(12, error.position)
        assertTrue(error.message!!.contains("a"))
    }

    @Test
    fun `同名但单选多选模式不同时报错`() {
        assertFailsWith<ExploreOptionSyntaxException> {
            parseExploreOptionsFromUrl("<a(x:1)|,><a(x:1)>")
        }
    }

    @Test
    fun `同名声明每段包装由替换阶段各取各的`() {
        val opts = parseExploreOptionsFromUrl("<a(P,x:1,y:2)><a(Q,x:1,y:2)>")
        assertEquals(1, opts.size)
        assertEquals("P", opts[0].prefix)
    }

    @Test
    fun `不同名各留一段 顺序按出现次序`() {
        val opts = parseExploreOptionsFromUrl("<b(p:1)><a(q:2)>")
        assertEquals(listOf("b", "a"), opts.map { it.name })
        assertEquals(listOf("1", "2"), opts.map { it.resolvedValue })
    }

    // ─────────────────── 2. 标签括号 / 深度感知切分 ───────────────────

    @Test
    fun `label 内含成对括号 逗号不切`() {
        val opt = parseExploreOptionsFromUrl("<type(动作(热血):action,科幻:scifi)>").single()
        assertEquals(listOf("动作(热血)" to "action", "科幻" to "scifi"), opt.options)
        assertEquals("action", opt.selectedValue)
    }

    @Test
    fun `depth0 冒号决定 pair 切点`() {
        val opt = parseExploreOptionsFromUrl("<a((b:c))>").single()
        assertEquals(listOf("(b:c)" to "(b:c)"), opt.options)
        assertEquals("(b:c)", opt.resolvedValue)
    }

    @Test
    fun `中间无冒号的项 键值取同一字符串`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,中,b:2)>").single()
        assertEquals(listOf("a" to "1", "中" to "中", "b" to "2"), opt.options)
    }

    @Test
    fun `尖括号可以出现在 content 内`() {
        val opt = parseExploreOptionsFromUrl("<a(b:>c)>").single()
        assertEquals(listOf("b" to ">c"), opt.options)
    }

    // ──────────────────────── 3. prefix / suffix 与单选空值 ────────────────────────

    @Test
    fun `首末项无冒号归 prefix suffix 且有选中才拼`() {
        val opt = parseExploreOptionsFromUrl("<t(全部,a:1,b:2,全部)>").single()
        assertEquals("全部", opt.prefix)
        assertEquals("全部", opt.suffix)
        assertEquals("全部1全部", opt.resolvedValue)
        opt.selectedValue = ""
        assertEquals("", opt.resolvedValue)
        opt.selectedValue = "2"
        assertEquals("全部2全部", opt.resolvedValue)
    }

    @Test
    fun `单选空取值省略整段且不拼前后缀`() {
        val opt = parseExploreOptionsFromUrl("<t(全部:,a:1)>").single()
        assertEquals(listOf("全部" to "", "a" to "1"), opt.options)
        assertEquals("", opt.selectedValue)
        assertEquals("", opt.resolvedValue)
        opt.selectedValue = "1"
        assertEquals("1", opt.resolvedValue)
    }

    @Test
    fun `单选空白取值也省略整段`() {
        val opt = parseExploreOptionsFromUrl("<t(全部: ,a:1)>").single()
        assertEquals("", opt.resolvedValue)
    }

    // ─────────────────────── 4. 多选 / 取值身份 / 空值标题 ───────────────────────

    @Test
    fun `多选未选中 O(1) 早退 选中按声明顺序拼接`() {
        val opt = parseExploreOptionsFromUrl("<t(前,a:1,b:2,后)|/>").single()
        assertEquals("前", opt.prefix)
        assertEquals("后", opt.suffix)
        assertEquals("/", opt.separator)
        assertEquals("", opt.rawValue)
        opt.selectedValues.addAll(listOf("2", "1"))
        assertEquals("1/2", opt.rawValue)
        assertEquals("前1/2后", opt.resolvedValue)
    }

    @Test
    fun `重复取值别名联动且只提交一次`() {
        val opt = parseExploreOptionsFromUrl("<t(A:1,B:1,C:2)|,>").single()
        opt.selectValues(setOf("1"))
        assertEquals("1", opt.rawValue)
        val ui = opt.toUiState()
        assertEquals(setOf(0, 1), ui.selectedIndexes)
        assertTrue(2 !in ui.selectedIndexes)
    }

    @Test
    fun `多选空取值项是标题 不进选中集合也不提交`() {
        val opt = parseExploreOptionsFromUrl("<t(热血: ,动作:1,科幻:2)|,>").single()
        assertEquals(setOf(0), opt.toUiState().headerIndexes)
        opt.selectValues(setOf("", "1", "2"))
        assertEquals("1,2", opt.rawValue)
        assertEquals(setOf(1, 2), opt.toUiState().selectedIndexes)
    }

    @Test
    fun `separator 原样保留`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)| & >").single()
        assertEquals(" & ", opt.separator)
        opt.selectValues(setOf("1", "2"))
        assertEquals("1 & 2", opt.rawValue)
    }

    @Test
    fun `单空格 separator 也是多选`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)| >").single()
        assertEquals(" ", opt.separator)
        opt.selectValues(setOf("1", "2"))
        assertEquals("1 2", opt.rawValue)
    }

    @Test
    fun `空选择时 prefix suffix 都不拼`() {
        val opt = parseExploreOptionsFromUrl("<t(P,a:1,Q)|,>").single()
        assertEquals("", opt.resolvedValue)
        opt.selectValues(setOf("1"))
        assertEquals("P1Q", opt.resolvedValue)
    }

    // ─────────── 5. 共享原始选择 + 每段独立 prefix suffix separator ───────────

    @Test
    fun `同名两段共享原始值 各段保留自己的包装`() {
        val url = "u/<a(&x=,m:1,n:2)><a(&y=,m:1,n:2)>"
        val out = replaceExploreOptionsWithSelection(url) { name ->
            if (name == "a") ExploreOptionSelection(false, listOf("2")) else null
        }
        assertEquals("u/&x=2&y=2", out)
    }

    @Test
    fun `同名多选两段各自用不同分隔符`() {
        val url = "<a(P,m:1,n:2,Q)|,><a(R,m:1,n:2,S)|&>"
        val out = replaceExploreOptionsWithSelection(url) { name ->
            if (name == "a") ExploreOptionSelection(true, listOf("1", "2")) else null
        }
        assertEquals("P1,2QR1&2S", out)
    }

    @Test
    fun `缺省选择按每段声明默认值`() {
        assertEquals("11", replaceExploreOptionsWithSelection("<a(x:1)><a(x:1)>") { null })
    }

    @Test
    fun `空选择显式省略整段而不是回落默认`() {
        assertEquals("", replaceExploreOptionsWithSelection("<t(P,a:1,Q)>") {
            ExploreOptionSelection(false, emptyList())
        })
    }

    @Test
    fun `替换遇到同名定义不一致时报位置诊断`() {
        assertFailsWith<ExploreOptionSyntaxException> {
            replaceExploreOptionsWithSelection("<a(x:1)><a(y:2)>") { null }
        }
    }


    @Test
    fun `多选仅声明顺序变化不误判选中值失效`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
            .toUiState().copy(selectedValues = setOf("2", "1"))
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(b:2,a:1)|,>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals(setOf("2", "1"), update.options.single().selectedValues)
        assertEquals(false, update.selectionInvalidated)
    }

    @Test
    fun `多选仅标签或包装变化保留有效值`() {
        val old = parseExploreOptionsFromUrl("<t(P,a:1,b:2,Q)|,>").single()
            .toUiState().copy(selectedValues = setOf("2"))
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(R,a:1,b:2,S)|&>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals(setOf("2"), update.options.single().selectedValues)
        assertEquals(false, update.selectionInvalidated)
        assertEquals("&", update.options.single().separator)
        assertEquals("R", update.options.single().prefix)
    }

    @Test
    fun `多选值顺序变化按取值身份不变`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
            .toUiState().copy(selectedValues = setOf("2", "1"))
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(a:1,b:2)|,>"),
            mapOf("t" to ExploreOptionSelection(true, listOf("1", "2"))),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals(setOf("1", "2"), update.options.single().selectedValues)
        assertEquals(false, update.selectionInvalidated)
    }

    @Test
    fun `一次扫描的段边界可直接复用替换`() {
        val url = "u/<a(&x=,m:1,n:2)><b(&y=,m:1,n:2)>"
        val segments = scanExploreOptionsSegments(url)
        assertEquals(listOf("a", "b"), segments.map { it.option.name })
        val out = replaceExploreOptionsWithSelection(url, segments) {
            ExploreOptionSelection(false, listOf("2"))
        }
        assertEquals("u/&x=2&y=2", out)
    }

    // ───────────────────── 6. 旧最终文本回调保持直写 ─────────────────────

    @Test
    fun `最终文本回调原样写入 不再套本段前后缀`() {
        assertEquals("2", replaceExploreOptionsInUrl("<t(全部,a:1,b:2)>") { "2" })
        assertEquals("全部2", replaceExploreOptionsInUrl("<t(全部,a:1,b:2)>") { "全部2" })
    }

    @Test
    fun `回调返回空串不回落`() {
        assertEquals("", replaceExploreOptionsInUrl("<t(前,a:1)>") { "" })
    }

    @Test
    fun `回调按 name 只影响对应段`() {
        val url = "u/<a(p:1,q:2)>/v/<b(m:3,n:4)|,>/w"
        val out = replaceExploreOptionsInUrl(url) { name -> if (name == "a") "2" else null }
        assertEquals("u/2/v//w", out)
    }

    @Test
    fun `无命中段时返回 url 自身实例`() {
        val url = "https://x.com/plain"
        assertSame(url, replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `同名两段都会被替换`() {
        assertEquals("11", replaceExploreOptionsInUrl("<a(x:1)><a(x:1)>") { null })
    }

    @Test
    fun `命中段之后的尾部原文保留`() {
        assertEquals("1>", replaceExploreOptionsInUrl("<a(b:1)>>") { null })
    }

    // ─────────────────── 7. 畸形声明位置诊断 / 普通文本不误报 ───────────────────

    @Test
    fun `未闭合括号给出起始位置诊断`() {
        val e = assertFailsWith<ExploreOptionSyntaxException> {
            parseExploreOptionsFromUrl("https://x/list<a(b:c")
        }
        assertEquals(14, e.position)
    }

    @Test
    fun `重复未闭合声明在首个位置停止 不向内层重试`() {
        val url = "<a(".repeat(64)
        val e = assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl(url) }
        assertEquals(0, e.position)
    }

    @Test
    fun `嵌套空组合同样在首个位置停止`() {
        val url = "<a(:".repeat(32) + ")>".repeat(32)
        val e = assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl(url) }
        assertEquals(0, e.position)
    }

    @Test
    fun `括号内容为空报错`() {
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a()>") }
    }

    @Test
    fun `没有可选项报错`() {
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a(:b)>") }
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a(b)>") }
    }

    @Test
    fun `多选分隔符为空或缺右尖报错`() {
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a(b:c)|>") }
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a(b:c)|x") }
    }

    @Test
    fun `收尾后多出字符报错`() {
        assertFailsWith<ExploreOptionSyntaxException> { parseExploreOptionsFromUrl("<a(b)c,d:e)>") }
    }

    @Test
    fun `模板表达式里的同形文本不当声明`() {
        val url = "<t(a:1,b:2)>{{ '<x('.repeat(2) }}"
        assertEquals(listOf("t"), parseExploreOptionsFromUrl(url).map { it.name })
    }

    @Test
    fun `非声明形态普通文本不报错`() {
        assertTrue(parseExploreOptionsFromUrl("https://x/a(b:c").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a b(c:1)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<(a:1)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<div>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("").isEmpty())
    }

    // ─────────────────────── 8. 定义更新沿用有效选择 ───────────────────────

    @Test
    fun `重置单选回到首个默认值 重复重置不变化`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)> ").single()
        opt.selectedValue = "2"
        assertEquals(true, opt.resetToDefault())
        assertEquals("1", opt.selectedValue)
        assertEquals(false, opt.resetToDefault())
    }

    @Test
    fun `重置多选清空全部选择 重复重置不变化`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
        opt.selectValues(setOf("1", "2"))
        assertEquals(true, opt.resetToDefault())
        assertEquals("", opt.rawValue)
        assertEquals(false, opt.resetToDefault())
    }

    @Test
    fun `定义不变时保留旧实例与选择`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)>").single()
            .toUiState().copy(selectedValue = "2")
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(a:1,b:2)>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertSame(old, update.options.single())
        assertEquals("2", update.options.single().selectedValue)
        assertEquals(false, update.selectionInvalidated)
    }

    @Test
    fun `单选值失效回落新默认并标记失效`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)>").single()
            .toUiState().copy(selectedValue = "2")
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(a:3,b:4)>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals("3", update.options.single().selectedValue)
        assertEquals(true, update.selectionInvalidated)
    }

    @Test
    fun `多选取交集并标记失效`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
            .toUiState().copy(selectedValues = setOf("1", "2"))
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(a:1,c:3)|,>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals(listOf("1"), update.options.single().selectionSnapshot().values)
        assertEquals(true, update.selectionInvalidated)
    }

    @Test
    fun `模式变化重新初始化`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)>").single()
            .toUiState().copy(selectedValue = "2")
        val parsed = resolveExploreOptionSelections(
            parseExploreOptionsFromUrl("<t(a:1,b:2)|,>"),
            mapOf("t" to old.selectionSnapshot()),
        ).options
        val update = mergeExploreOptionDefinitions(listOf(old), parsed, replaceAll = true)
        assertEquals(emptyList<String>(), update.options.single().selectionSnapshot().values)
        assertEquals(true, update.selectionInvalidated)
    }

    @Test
    fun `上一页链接缺少声明时保留主模板定义`() {
        val old = parseExploreOptionsFromUrl("<t(a:1,b:2)>").map { it.toUiState() }
        val update = mergeExploreOptionDefinitions(old, emptyList(), replaceAll = false)
        assertEquals(1, update.options.size)
        assertEquals("t", update.options.single().name)
    }

    @Test
    fun `主模板完整更新时移除已删除的定义`() {
        val old = parseExploreOptionsFromUrl("<a(x:1)><b(y:2)>").map { it.toUiState() }
        val parsed = parseExploreOptionsFromUrl("<b(y:2)>")
        val update = mergeExploreOptionDefinitions(old, parsed, replaceAll = true)
        assertEquals(listOf("b"), update.options.map { it.name })
    }

    // ─────────────────── 附: data class 等值不含 selectedValues ───────────────────

    @Test
    fun `data class 等值不含 selectedValues`() {
        val a = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
        val b = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>").single()
        a.selectedValues.add("1")
        assertEquals("", b.selectedValue)
        assertEquals(a, b)
        b.selectedValue = "1"
        assertTrue(a != b)
    }
}
