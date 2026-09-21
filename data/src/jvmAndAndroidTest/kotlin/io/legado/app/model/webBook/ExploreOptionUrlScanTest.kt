package io.legado.app.model.webBook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [parseExploreOptionsFromUrl] / [replaceExploreOptionsInUrl] 手写括号扫描器**现状快照**。
 *
 * 期望值来源: 逐字读 ExploreOption.kt 的 `tryParseSegment`(name 扫描 + depth 定位收尾 `)`)、
 * `buildExploreOption`(depth-0 切逗号 + prefix/suffix + pairs)、其内联冒号定位（原 `firstColonAtDepth0`，
 * 已收拢为 `BalanceScan.indexOfTopLevel`）、
 * `scanExploreOptions`(`i = if (end > lt) end else lt + 1`) 并手工模拟每个下标得出。
 * 本文件不声明"应该是什么", 只钉"现在产出什么": 扫描器任何偏移/静默行为变动都会打破这里。
 */
class ExploreOptionUrlScanTest {

    // ─────────────────────────── 1. 基础形态 + 同名去重 ───────────────────────────

    @Test
    fun `基础形态 name 标签 值 逐项字面量`() {
        val opts = parseExploreOptionsFromUrl("https://x.com/list<type(男:1,女:2)>")
        assertEquals(1, opts.size)
        val opt = opts[0]
        assertEquals("type", opt.name)
        assertEquals(listOf("男" to "1", "女" to "2"), opt.options)
        assertEquals(false, opt.multiSelect)
        assertEquals("", opt.separator)
        assertEquals("", opt.prefix)
        assertEquals("", opt.suffix)
        // 单选默认选中 pairs[0].second
        assertEquals("1", opt.selectedValue)
        assertEquals("1", opt.resolvedValue)
        assertTrue(opt.selectedValues.isEmpty())
    }

    @Test
    fun `同名键第二次出现被忽略 后段整体丢弃`() {
        // parseExploreOptionsFromUrl: if (out.none { it.name == option.name }) out.add(option)
        val opts = parseExploreOptionsFromUrl("<a(x:1,y:2)><a(z:3)>")
        assertEquals(1, opts.size)
        assertEquals("a", opts[0].name)
        assertEquals(listOf("x" to "1", "y" to "2"), opts[0].options)
        assertEquals("1", opts[0].selectedValue)
    }

    @Test
    fun `不同名各留一段 顺序按出现次序`() {
        val opts = parseExploreOptionsFromUrl("<b(p:1)><a(q:2)>")
        assertEquals(listOf("b", "a"), opts.map { it.name })
        assertEquals(listOf("1", "2"), opts.map { it.resolvedValue })
    }

    // ─────────────────── 2. tag label 内含成对括号 / 深度感知切分 ───────────────────

    @Test
    fun `label 内含成对括号 逗号切分不越过 括号内`() {
        // content = "动作(热血):action,科幻:scifi"; 收尾 ) 由 depth 定位, 内层 () 被算进 content
        val opt = parseExploreOptionsFromUrl("<type(动作(热血):action,科幻:scifi)>")[0]
        assertEquals("type", opt.name)
        assertEquals(listOf("动作(热血)" to "action", "科幻" to "scifi"), opt.options)
        assertEquals("action", opt.selectedValue)
        assertEquals("action", opt.resolvedValue)
    }

    @Test
    fun `label 内含成对括号且含逗号 逗号不切`() {
        // content = "a(x,y):v,b:z"; 第一个逗号在 depth 1, 只有 index 11 的逗号切得开
        val opt = parseExploreOptionsFromUrl("<t(a(x,y):v,b:z)>")[0]
        assertEquals(listOf("a(x,y)" to "v", "b" to "z"), opt.options)
        assertEquals("v", opt.selectedValue)
    }

    @Test
    fun `depth0 冒号决定 pair 切点 内层冒号不作数`() {
        // content = "(b:c)"(收尾 ) 前一位), token 内冒号在 depth 1 → depth-0 冒号定位返回 -1 → key == value
        val opt = parseExploreOptionsFromUrl("<a((b:c))>")[0]
        assertEquals("a", opt.name)
        assertEquals(listOf("(b:c)" to "(b:c)"), opt.options)
        assertEquals("(b:c)", opt.selectedValue)
        assertEquals("(b:c)", opt.resolvedValue)
    }

    @Test
    fun `中间无冒号的项 键值取同一字符串`() {
        // 首末项都含 ':', 所以 "中" 既不是 prefix 也不是 suffix, 落到 pairs 的 colon<0 分支
        val opt = parseExploreOptionsFromUrl("<t(a:1,中,b:2)>")[0]
        assertEquals(listOf("a" to "1", "中" to "中", "b" to "2"), opt.options)
        assertEquals("1", opt.selectedValue)
    }

    // ──────────────────────── 3. prefix / suffix 与 resolvedValue ────────────────────────

    @Test
    fun `首项无冒号归 prefix 未选中时整段为空`() {
        val opt = parseExploreOptionsFromUrl("<t(全部,a:1,b:2)>")[0]
        assertEquals("全部", opt.prefix)
        assertEquals("", opt.suffix)
        assertEquals(listOf("a" to "1", "b" to "2"), opt.options)
        assertEquals("全部1", opt.resolvedValue)
        // 未选中(单选 selectedValue 置空) → prefix/suffix 一起丢掉
        opt.selectedValue = ""
        assertEquals("", opt.resolvedValue)
        opt.selectedValue = "2"
        assertEquals("全部2", opt.resolvedValue)
    }

    @Test
    fun `末项无冒号归 suffix`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2,全部)>")[0]
        assertEquals("", opt.prefix)
        assertEquals("全部", opt.suffix)
        assertEquals(listOf("a" to "1", "b" to "2"), opt.options)
        assertEquals("1全部", opt.resolvedValue)
    }

    @Test
    fun `首末同时无冒号 prefix 与 suffix 都拼上`() {
        val opt = parseExploreOptionsFromUrl("<t(-,a:1,b:2,-)>")[0]
        assertEquals("-", opt.prefix)
        assertEquals("-", opt.suffix)
        assertEquals("1", opt.selectedValue)
        assertEquals("-1-", opt.resolvedValue)
    }

    @Test
    fun `单选选中值不做合法性校验 任意字符串原样拼接`() {
        val opt = parseExploreOptionsFromUrl("<t(全部,a:1)>")[0]
        opt.selectedValue = "9"
        assertEquals("全部9", opt.resolvedValue)
    }

    @Test
    fun `值的空串形态 默认选中项值为空则 resolvedValue 为空`() {
        // token "全部:" → colon@2, first="全部" 非空, second="" → pair 值为空串
        val opt = parseExploreOptionsFromUrl("<t(全部:,a:1)>")[0]
        assertEquals(listOf("全部" to "", "a" to "1"), opt.options)
        assertEquals("", opt.selectedValue)
        assertEquals("", opt.resolvedValue)
    }

    // ─────────────────────── 4. |sep 多选 与 无 |sep 单选 ───────────────────────

    @Test
    fun `末尾 sep 多选 未选中为空串 选中按 separator 拼接`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>")[0]
        assertEquals(true, opt.multiSelect)
        assertEquals(",", opt.separator)
        assertEquals("", opt.selectedValue)
        assertEquals("", opt.resolvedValue)
        opt.selectedValues.addAll(listOf("1", "2"))
        assertEquals("1,2", opt.resolvedValue)
    }

    @Test
    fun `多选拼接顺序取 options 顺序 与勾选先后无关`() {
        val m = parseExploreOptionsFromUrl("<t(前,a:1,b:2,后)|/>")[0]
        assertEquals("前", m.prefix)
        assertEquals("后", m.suffix)
        assertEquals("/", m.separator)
        assertEquals(true, m.multiSelect)
        assertTrue(m.selectedValues.isEmpty())
        // 先勾 2 再勾 1, 输出仍按 options 顺序
        m.selectedValues.add("2")
        m.selectedValues.add("1")
        assertEquals("前1/2后", m.resolvedValue)
        // 不属于 options 的选中值被 filter 丢掉
        m.selectedValues.add("zzz")
        assertEquals("前1/2后", m.resolvedValue)
    }

    @Test
    fun `separator 不做 trim 多字符原样保留`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)| & >")[0]
        assertEquals(true, opt.multiSelect)
        assertEquals(" & ", opt.separator)
        opt.selectedValues.addAll(listOf("1", "2"))
        assertEquals("1 & 2", opt.resolvedValue)
    }

    @Test
    fun `单空格 sep 也算多选`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)| >")[0]
        assertEquals(true, opt.multiSelect)
        assertEquals(" ", opt.separator)
        opt.selectedValues.addAll(listOf("1", "2"))
        assertEquals("1 2", opt.resolvedValue)
    }

    @Test
    fun `无 sep 即单选 默认取 pairs 首项值`() {
        val opt = parseExploreOptionsFromUrl("<t(a:1,b:2)>")[0]
        assertEquals(false, opt.multiSelect)
        assertEquals("", opt.separator)
        assertEquals("1", opt.selectedValue)
    }

    // ─────────────────── 5. 失败路径的静默行为 (整段丢弃) ───────────────────

    @Test
    fun `未闭合括号 depth 不为零 返回 -1 整段丢弃`() {
        assertTrue(parseExploreOptionsFromUrl("https://x/a(b:c").isEmpty())
    }

    @Test
    fun `括号内容为空 整段丢弃`() {
        assertTrue(parseExploreOptionsFromUrl("<a()>").isEmpty())
    }

    @Test
    fun `name 含空白 整段丢弃`() {
        // name 扫描在 ' ' 处 break, url[p] != '(' → -1
        assertTrue(parseExploreOptionsFromUrl("<a b(c:1)>").isEmpty())
    }

    @Test
    fun `name 为空 整段丢弃`() {
        // p == nameStart → -1
        assertTrue(parseExploreOptionsFromUrl("<(a:1)>").isEmpty())
    }

    @Test
    fun `缺右尖括号 整段丢弃`() {
        // contentEnd 之后 p >= n → -1
        assertTrue(parseExploreOptionsFromUrl("<a(b:c)").isEmpty())
    }

    @Test
    fun `竖线后紧跟右尖括号 整段丢弃`() {
        // gt == sepStart → -1
        assertTrue(parseExploreOptionsFromUrl("<a(b:c)|>").isEmpty())
    }

    @Test
    fun `竖线后没有右尖括号 整段丢弃`() {
        // indexOf('>') < 0 → -1
        assertTrue(parseExploreOptionsFromUrl("<a(b:c)|x").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a(b:c)|").isEmpty())
    }

    @Test
    fun `pairs 全空 冒号开头 整段丢弃`() {
        // depth-0 冒号定位 = 0 → first 为空 → continue → pairs.isEmpty() → null
        assertTrue(parseExploreOptionsFromUrl("<a(:b)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a(:b,:c)>").isEmpty())
    }

    @Test
    fun `pairs 全空 无冒号项被 prefix 与 suffix 吃光`() {
        assertTrue(parseExploreOptionsFromUrl("<a(b)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a(b,c)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a(中)>").isEmpty())
    }

    @Test
    fun `多括号提前收尾 depth 归零即停 后续字符非右尖则整段丢弃`() {
        // content 在第一个 depth-0 的 ')' (下标 4) 就收尾, url[5] == 'c' != '>' → -1
        assertTrue(parseExploreOptionsFromUrl("<a(b)c,d:e)>").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("<a(b))c>").isEmpty())
    }

    @Test
    fun `尖括号可以出现在 content 内 不作收尾`() {
        // content = "b:>c" → pair("b", ">c")
        val opt = parseExploreOptionsFromUrl("<a(b:>c)>")[0]
        assertEquals(listOf("b" to ">c"), opt.options)
        assertEquals(">c", opt.selectedValue)
        assertEquals(">c", opt.resolvedValue)
    }

    // ─────────── 5b. 失败后从 lt+1 继续扫 (不是跳过整段) 的精确偏移 ───────────

    @Test
    fun `失败段内含尖括号 从下一字符重试并命中内层段`() {
        // <a(:b<b(c:1)>)> : 首段 pairs 全空 → -1 → i = 1 → 在 index 5 重新命中 <b(c:1)>
        val url = "<a(:b<b(c:1)>)>"
        val opts = parseExploreOptionsFromUrl(url)
        assertEquals(1, opts.size)
        assertEquals("b", opts[0].name)
        assertEquals(listOf("c" to "1"), opts[0].options)
        assertEquals("1", opts[0].resolvedValue)
        // 替换时只跳过真正命中的 [5,13): 前缀 "<a(:b" 与尾巴 ")>" 原样保留
        assertEquals("<a(:b1)>", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `连续尖括号 首个尖号只消耗一个字符`() {
        val url = "<<x(y:1)>"
        val opts = parseExploreOptionsFromUrl(url)
        assertEquals(1, opts.size)
        assertEquals("x", opts[0].name)
        assertEquals("1", opts[0].resolvedValue)
        assertEquals("<1", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `失败段被丢弃后继续找下一个尖号 原文保留`() {
        val url = "<a(:b)><x(y:1)>"
        val opts = parseExploreOptionsFromUrl(url)
        assertEquals(1, opts.size)
        assertEquals("x", opts[0].name)
        assertEquals("<a(:b)>1", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `name 含尖号时失败重试命中内层段`() {
        val url = "<a<b(c:1)>"
        val opts = parseExploreOptionsFromUrl(url)
        assertEquals(1, opts.size)
        assertEquals("b", opts[0].name)
        assertEquals("<a1", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `多括号丢弃后仍能从下一字符续扫`() {
        val url = "<a(b)c,d:e)><x(y:1)>"
        val opts = parseExploreOptionsFromUrl(url)
        assertEquals(1, opts.size)
        assertEquals("x", opts[0].name)
        assertEquals("<a(b)c,d:e)>1", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `无尖号输入 空列表`() {
        assertTrue(parseExploreOptionsFromUrl("https://x.com/no/options").isEmpty())
        assertTrue(parseExploreOptionsFromUrl("").isEmpty())
    }

    // ───────────────────── 6. replaceExploreOptionsInUrl ─────────────────────

    @Test
    fun `selectedValue 返回 null 回落 resolvedValue`() {
        assertEquals("1", replaceExploreOptionsInUrl("<t(a:1,b:2)>") { null })
        assertEquals("全部2", replaceExploreOptionsInUrl("<t(全部,a:1,b:2)>") { "2" })
    }

    @Test
    fun `selectedValue 返回空串不回落 直接替换为空`() {
        // `?:` 只对 null 生效, 空串照旧写入
        assertEquals("", replaceExploreOptionsInUrl("<t(前,a:1)>") { "" })
    }

    @Test
    fun `按 name 取值的 lambda 只影响对应段`() {
        val url = "u/<a(p:1,q:2)>/v/<b(m:3,n:4)|,>/w"
        val out = replaceExploreOptionsInUrl(url) { name -> if (name == "a") "2" else null }
        // b 段是多选且新解析实例无选中态 → resolvedValue 为空串
        assertEquals("u/2/v//w", out)
    }

    @Test
    fun `替换用的是重新解析的实例 调用方选中态不生效`() {
        val url = "<t(a:1,b:2)|,>"
        val parsed = parseExploreOptionsFromUrl(url)[0]
        parsed.selectedValues.addAll(listOf("1", "2"))
        assertEquals("1,2", parsed.resolvedValue)
        assertEquals("", replaceExploreOptionsInUrl(url) { null })
        assertEquals("1,2", replaceExploreOptionsInUrl(url) { name -> if (name == "t") "1,2" else null })
    }

    @Test
    fun `无命中段时返回 url 自身实例 恒等`() {
        val url = "https://x.com/plain"
        assertSame(url, replaceExploreOptionsInUrl(url) { null })
        // 只有失败段时同样 sb == null → 返回入参实例
        val broken = "<a(b)c,d:e)>"
        assertSame(broken, replaceExploreOptionsInUrl(broken) { "zzz" })
    }

    @Test
    fun `多段替换后拼接结果`() {
        val url = "https://x.com/list<type(动作(热血):action,科幻:scifi)>/kind<t(a:1,b:2)|/>/p"
        val out = replaceExploreOptionsInUrl(url) { name ->
            when (name) {
                "type" -> "scifi"
                else -> null
            }
        }
        // type 段显式选中 scifi; t 段多选无选中 → 空串(注意尾巴只剩一个 '/')
        assertEquals("https://x.com/listscifi/kind/p", out)
    }

    @Test
    fun `同名两段都会被替换 去重只在解析侧`() {
        val url = "<a(x:1)><a(y:2)>"
        assertEquals(1, parseExploreOptionsFromUrl(url).size)
        assertEquals("12", replaceExploreOptionsInUrl(url) { null })
    }

    @Test
    fun `命中段之后的尾部原文保留`() {
        // pair("b","c") → 单选默认选中 "c", 多出来的 '>' 落在段外被原样抄回
        assertEquals("c>", replaceExploreOptionsInUrl("<a(b:c)>>") { null })
        assertEquals("<a(b:c)x>1", replaceExploreOptionsInUrl("<a(b:c)x><d(e:1)>") { null })
    }

    // ─────────────────── 附: ExploreOption data class 等值语义现状 ───────────────────

    @Test
    fun `data class 等值不含 selectedValues`() {
        // selectedValues 声明在类体里, 不进 equals; 只有构造参数(含 var selectedValue)参与比较
        val a = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>")[0]
        val b = parseExploreOptionsFromUrl("<t(a:1,b:2)|,>")[0]
        a.selectedValues.add("1")
        assertEquals("", b.selectedValue)
        assertEquals(a, b)
        b.selectedValue = "1"
        assertTrue(a != b, "构造参数 selectedValue 不同才打破等值")
    }
}
