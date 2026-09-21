package io.legado.app.utils.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

/**
 * [TagScan.forEachNormalizedImgSrc] 的对拍金样。
 *
 * EPUB 导出侧原先是两份内联 `Pattern.compile("<img src=\"([^\"]+)\"")`，
 * 收拢后改走 TagScan。这里不把期望值手写死（人推期望值不可靠），而是**直接与
 * java.util.regex 的实际匹配结果逐项比对**：只要共用件偏离原正则口径，本用例立即变红。
 *
 * 口径要点（两侧都必须满足）：要求 `src="` 紧跟在 `<img ` 之后（恰好一个空格）、
 * 值非空且不含双引号、大小写不敏感、命中后从右引号之后继续找。
 */
class TagScanNormalizedImgGoldenTest {

    private val oracle = Pattern.compile("<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)

    private fun oracleSrcs(content: String): List<String> {
        val matcher = oracle.matcher(content)
        val out = ArrayList<String>()
        while (matcher.find()) {
            matcher.group(1)?.let { out.add(it) }
        }
        return out
    }

    private fun scanSrcs(content: String): List<String> {
        val out = ArrayList<String>()
        TagScan.forEachNormalizedImgSrc(content) { out.add(it) }
        return out
    }

    /** 对拍：两侧提取到的 src 序列必须逐项相同。 */
    private fun assertParity(content: String) {
        assertEquals(
            "正则口径与 TagScan 口径结果不符, input=<$content>",
            oracleSrcs(content), scanSrcs(content)
        )
    }

    @Test
    fun `清洗产物 带 style 与 onclick 的归一标签`() {
        assertParity("<img src=\"a.jpg\" style=\"width:1px\" onclick=\"go()\">")
    }

    @Test
    fun `cbz 自拼的自闭合标签`() {
        assertParity("<img src=\"0001.jpg\"/>")
    }

    @Test
    fun `同一段内多张按出现顺序取回`() {
        assertParity("前<img src=\"a.png\">中<img src=\"b.webp\">后")
    }

    @Test
    fun `大小写混写的标签与属性`() {
        assertParity("<IMG SRC=\"q.jpg\"> 与 <Img Src=\"w.jpg\">")
    }

    @Test
    fun `src 值含空格与中文路径`() {
        assertParity("<img src=\"图片 1/a b.jpg\">")
    }

    @Test
    fun `单引号值不属于归一形态 两侧都不取`() {
        assertParity("<img src='y.jpg'>")
    }

    @Test
    fun `src 不是紧跟 img 的第一个属性 两侧都不取`() {
        assertParity("<img foo=\"1\" src=\"z.jpg\">")
    }

    @Test
    fun `img 与 src 之间两个空格 两侧都不取`() {
        assertParity("<img  src=\"w.jpg\">")
    }

    @Test
    fun `空 src 值不满足非空要求 两侧都不取`() {
        assertParity("<img src=\"\">")
    }

    @Test
    fun `缺右引号的残缺标签 两侧都不取`() {
        assertParity("<img src=\"a.jpg")
    }

    @Test
    fun `标签名前缀重叠不误命中`() {
        assertParity("<imgx src=\"c.jpg\"><image src=\"d.jpg\"><pimg src=\"e.jpg\">")
    }

    @Test
    fun `嵌套小于号内层才是要找的标签`() {
        assertParity("<img <img src=\"a.jpg\"><img src=\"b.jpg\">")
    }

    @Test
    fun `同一标签内重复 src 属性只取第一个`() {
        assertParity("<img src=\"first.jpg\" src=\"second.jpg\">")
    }

    @Test
    fun `正文里没有 img 时不产出任何 src`() {
        assertParity("纯文本正文，没有任何标签。")
    }

    @Test
    fun `src 值里带转义形态的百分号编码`() {
        assertParity("<img src=\"http://x/a%22b.jpg\">")
    }

    @Test
    fun `空串输入不产出结果也不越界`() {
        assertParity("")
    }

    @Test
    fun `只有左半截前缀时不越界`() {
        assertParity("<img ")
        assertParity("<img src=")
        assertParity("<img src=\"")
    }

    @Test
    fun `书源 option 形态 src 带逗号花括号`() {
        assertParity("<img src=\"b.webp\",{\"headers\":{\"referer\":\"x\"}}>")
    }

    @Test
    fun `混合命中与不命中若干形态的对拍`() {
        listOf(
            "<img src=\"\" onclick=\"go()\">",
            "文字<img src=\"a.png\" style=\"width:1px\">尾巴",
            "<img src = \"spaced.jpg\">",
            "<img src=\"tail.jpg\"> <img src=\"\">",
        ).forEach { assertParity(it) }
    }

    /**
     * `attr` 是宽容口径，与上面的严格口径**故意不同**：它必须能取到未清洗内容里的值。
     * 这两条把"两种口径不许互相冒充"钉住，防止后来者把它们合并成一个。
     */
    @Test
    fun `宽容口径能取到严格口径取不到的单引号与裸值`() {
        assertEquals("y.jpg", TagScan.attr("<img src='y.jpg'>", "src"))
        assertEquals("d.jpg", TagScan.attr("<img src=d.jpg>", "src"))
        assertEquals(emptyList<String>(), scanSrcs("<img src='y.jpg'>"))
        assertEquals(emptyList<String>(), scanSrcs("<img src=d.jpg>"))
    }

    @Test
    fun `两种口径都不把 data-src 当成 src`() {
        assertNull(TagScan.attr("<img data-src=\"x.jpg\">", "src"))
        assertEquals(emptyList<String>(), scanSrcs("<img data-src=\"x.jpg\">"))
    }
}
