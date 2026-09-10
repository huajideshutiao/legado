package io.legado.app.ui.book.read.page.provider

import io.legado.app.help.book.BookContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentParserSharedTest {

    @Test
    fun `保留 BookContent textList 项边界与空项`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(
                sameTitleRemoved = true,
                textList = listOf("  第一段  ", "", "第二段\n\n第三段"),
                effectiveReplaceRules = null,
            )
        )

        assertEquals(3, parsed.size)
        assertEquals("  第一段  ", parsed[0].text)
        assertEquals("", parsed[1].text)
        assertEquals("第二段\n\n第三段", parsed[2].text)
    }

    @Test
    fun `图片 br 与 HTML entity 按原版顺序解析`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(
                sameTitleRemoved = false,
                textList = listOf("甲&amp;乙<br><img src='a.jpg' style=FULL onclick=preview()>尾"),
                effectiveReplaceRules = null,
            )
        ).single()

        assertEquals("甲&乙\n${ChapterContentParserShared.srcReplaceChar}尾", parsed.text)
        assertEquals(1, parsed.images.size)
        assertEquals("a.jpg", parsed.images.single().src)
        assertEquals("FULL", parsed.images.single().style)
        assertEquals("preview()", parsed.images.single().onclick)
    }

    @Test
    fun `残缺标签作为普通文本保留`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(false, listOf("正文<img src='broken'"), null)
        ).single()

        assertEquals("正文<img src='broken'", parsed.text)
        assertTrue(parsed.images.isEmpty())
    }

    @Test
    fun `取不到 src 的 img 不产出占位符也不产出图`() {
        val parsed = ChapterContentParserShared.parse(
            BookContent(false, listOf("""前<img data-src="real">后"""), null)
        ).single()

        // 与 extractImages / 缓存侧同口径: 不兜成空串, 免得阅读页留一个永远加载不出的 ▩
        assertEquals("前后", parsed.text)
        assertTrue(parsed.images.isEmpty())
    }

    @Test
    fun `属性名边界判定 不取 style 内的 src`() {
        val tag = """<img style="background:url(x?src=y)" src="a.jpg">"""

        assertEquals("a.jpg", ChapterContentParserShared.getAttr(tag, "src"))
        assertEquals("background:url(x?src=y)", ChapterContentParserShared.getAttr(tag, "style"))
        assertEquals("a.jpg", ChapterContentParserShared.extractImages(tag).single().src)
    }

    @Test
    fun `getAttr 容忍等号前后空白`() {
        val tag = """<img src = "a.jpg">"""

        assertEquals("a.jpg", ChapterContentParserShared.getAttr(tag, "src"))
        assertEquals("a.jpg", ChapterContentParserShared.extractImages(tag).single().src)
    }

    @Test
    fun `无引号值在空白 右尖括号与斜杠处截断`() {
        assertEquals("a.jpg", ChapterContentParserShared.getAttr("<img src=a.jpg>", "src"))
        assertEquals("a.jpg", ChapterContentParserShared.getAttr("<img src=a.jpg />", "src"))
        assertEquals("a.jpg", ChapterContentParserShared.getAttr("<img src=a.jpg/>", "src"))
        assertEquals("a.jpg", ChapterContentParserShared.getAttr("<img src=a.jpg\n>", "src"))
        assertEquals(
            listOf("a.jpg", "a.jpg"),
            ChapterContentParserShared.extractImages("<img src=a.jpg\n><img src=a.jpg/>")
                .map { it.src },
        )
    }

    @Test
    fun `单引号值正常取到`() {
        assertEquals("a.jpg", ChapterContentParserShared.getAttr("<img src='a.jpg'>", "src"))
        assertEquals(
            "a.jpg",
            ChapterContentParserShared.extractImages("<img src='a.jpg'>").single().src,
        )
    }

    @Test
    fun `空 src 取到空串 且仍产出一张图`() {
        val tag = """<img src="">"""

        assertEquals("", ChapterContentParserShared.getAttr(tag, "src"))
        assertEquals(1, ChapterContentParserShared.extractImages(tag).size)
        assertEquals("", ChapterContentParserShared.extractImages(tag).single().src)
    }

    @Test
    fun `data 前缀属性不参与 src 取值`() {
        // 刻意保留的当前行为: 与 Android 端 BookHelp.flowImages 同口径, 只认 src。
        val both = """<img data-src="real" src="ph">"""
        val onlyDataSrc = """<img data-src="real">"""

        assertEquals("ph", ChapterContentParserShared.getAttr(both, "src"))
        assertEquals("ph", ChapterContentParserShared.extractImages(both).single().src)
        assertNull(ChapterContentParserShared.getAttr(onlyDataSrc, "src"))
        assertTrue(ChapterContentParserShared.extractImages(onlyDataSrc).isEmpty())
    }

    @Test
    fun `多张 img 按出现顺序保留且不去重`() {
        val images = ChapterContentParserShared.extractImages(
            """<img src="a.jpg">文字<img src="b.jpg"><p>段</p><img src="a.jpg">"""
        )

        assertEquals(listOf("a.jpg", "b.jpg", "a.jpg"), images.map { it.src })
    }

    @Test
    fun `无 img 的纯文本与缺少闭合尖括号的残缺标签都不产出图`() {
        assertTrue(ChapterContentParserShared.extractImages("纯文本没有任何标签").isEmpty())
        assertTrue(ChapterContentParserShared.extractImages("正文<img src='a.jpg'").isEmpty())
    }

    @Test
    fun `img 标签名与属性名大小写不敏感`() {
        val images = ChapterContentParserShared.extractImages(
            """<IMG SRC="a.jpg"><Img Src='b.jpg'>"""
        )

        assertEquals(listOf("a.jpg", "b.jpg"), images.map { it.src })
    }

    @Test
    fun `漫画侧 src 列表等价于 extractImages 过滤空 src`() {
        // 钉住 sharedFlowImages 的转换式, 与 Android 端 BookHelp.flowImages 同口径。
        val content = """<img src="p1.jpg"><img src=""><img data-src="real"><img src='p2.jpg'>"""

        assertEquals(3, ChapterContentParserShared.extractImages(content).size)
        assertEquals(
            listOf("p1.jpg", "p2.jpg"),
            ChapterContentParserShared.extractImages(content)
                .map { it.src }
                .filter { it.isNotBlank() },
        )
    }
}
