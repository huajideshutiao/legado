package io.legado.app.format.epub

import io.legado.app.data.entities.BookChapter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EpubParserTest {
    @Test
    fun invalidNavigationFallsBackToReadableSpineChapters() {
        for (href in listOf("missing.xhtml", "nav.xhtml")) {
            val book = parse(nav = """<nav epub:type="toc"><ol><li><a href="$href">失效目录</a></li></ol></nav>""")
            val chapters = book.chapterList("file:///book.epub")
            assertEquals("OEBPS/chapter.xhtml", chapters.single().url)
            assertContains(EpubContentReader.read(book.spine, chapters.single(), 0L), "可以阅读的正文")
        }
    }

    @Test
    fun nativeChapterListLinksFragmentsAndReadsEachChapter() {
        val book = parse(
            nav = """<nav epub:type="toc"><ol><li><a href="chapter.xhtml#one">第一章</a></li><li><a href="chapter.xhtml#two">第二章</a></li></ol></nav>""",
            files = mapOf("OEBPS/chapter.xhtml" to "<html><body><h2 id=\"one\">第一章</h2><p>第一段正文</p><h2 id=\"two\">第二章</h2><p>第二段正文</p></body></html>"),
        )
        val chapters = book.chapterList("file:///book.epub")
        assertEquals(2, chapters.size)
        assertEquals("two", chapters.first().endFragmentId)
        assertEquals(chapters.last().url, chapters.first().getVariable("nextUrl"))
        val first = EpubContentReader.read(book.spine, chapters.first(), 0L)
        assertContains(first, "第一段正文")
        assertFalse(first.contains("第二段正文"))
        assertContains(EpubContentReader.read(book.spine, chapters.last(), 0L), "第二段正文")
    }

    @Test
    fun dotSegmentsInManifestResolveInsideAndOutsidePackageDirectory() {
        for (href in listOf("./Text/../chapter.xhtml", "../chapter.xhtml")) {
            val path = if (href.startsWith("../")) "chapter.xhtml" else "OEBPS/chapter.xhtml"
            val book = parse(
                manifest = """<item id="chapter" href="$href" media-type="application/xhtml+xml"/>""",
                nav = """<nav epub:type="toc"><ol><li><a href="$href">第一章</a></li></ol></nav>""",
                files = mapOf(path to chapter),
            )
            assertEquals(path, book.spine.single().href)
            assertEquals(book.spine.single(), book.toc.single().resource)
            assertContains(read(book, book.toc.single()), "可以阅读的正文")
        }
    }

    @Test
    fun coverPageResolvesHtmlAndSvgImagesRelativeToPage() {
        for (image in listOf("""<img src="../Images/cover.jpg"/>""",
            """<svg><image xlink:href="../Images/cover.jpg"/></svg>""")) {
            val book = parse(
                manifest = """<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover-image" href="Images/cover.jpg" media-type="image/jpeg"/>""",
                guide = """<guide><reference type="cover" href="Text/cover.xhtml"/></guide>""",
                files = mapOf("OEBPS/Text/cover.xhtml" to "<html><body>$image</body></html>",
                    "OEBPS/Images/cover.jpg" to "image bytes"),
            )
            assertEquals("OEBPS/Images/cover.jpg", assertNotNull(book.coverImage).href)
        }
    }

    @Test
    fun encodedManifestAndTocPathsResolveToActualChapter() {
        val book = parse(
            manifest = """<item id="chapter" href="Text/%E7%AC%AC%E4%B8%80%20%E7%AB%A0.xhtml" media-type="application/xhtml+xml"/>""",
            nav = """<nav epub:type="toc"><ol><li><a href="Text/%E7%AC%AC%E4%B8%80%20%E7%AB%A0.xhtml#%E6%AD%A3%E6%96%87">第一章</a></li></ol></nav>""",
            files = mapOf("OEBPS/Text/第一 章.xhtml" to chapter),
        )
        assertEquals("OEBPS/Text/第一 章.xhtml", book.spine.single().href)
        assertEquals("正文", book.toc.single().fragmentId)
        assertEquals(book.spine.single(), book.toc.single().resource)
        assertContains(read(book, book.toc.single()), "可以阅读的正文")
    }

    @Test
    fun prefixedPackageElementsStillProvideMetadataAndSpine() {
        val book = parse(
            manifest = """<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>""",
            nav = normalNav,
            prefix = "opf:",
        )
        assertEquals("测试书", book.metadata.firstTitle)
        assertEquals("作者", book.metadata.authors.single())
        assertEquals("OEBPS/chapter.xhtml", book.spine.single().href)
        assertContains(read(book, book.toc.single()), "可以阅读的正文")
    }

    @Test
    fun choosesTocInsteadOfFirstNavigationElement() {
        val book = parse(nav = """
            <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">封面</a></li></ol></nav>
            $normalNav
        """.trimIndent())
        assertEquals("第一章", book.toc.single().title)
        assertNotNull(book.toc.single().resource)
    }

    @Test
    fun retainsNavigationChildrenUnderUnlinkedVolumeHeading() {
        val book = parse(nav = """
            <nav epub:type="toc"><ol><li><span>第一卷</span><ol>
                <li><a href="chapter.xhtml">第一章</a></li>
            </ol></li></ol></nav>
        """.trimIndent())
        assertEquals("第一卷", book.toc.single().title)
        assertEquals("第一章", book.toc.single().children.single().title)
        assertNotNull(book.toc.single().children.single().resource)
    }

    @Test
    fun epub3WithNcxUsesNcxParser() {
        val book = parse(
            nav = "", tocType = "application/x-dtbncx+xml", tocProperties = "",
            files = mapOf("OEBPS/nav.xhtml" to """
                <ncx><navMap><navPoint id="one"><navLabel><text>第一章</text></navLabel>
                <content src="chapter.xhtml"/></navPoint></navMap></ncx>
            """.trimIndent()),
        )
        assertEquals("第一章", book.toc.single().title)
        assertNotNull(book.toc.single().resource)
    }

    @Test
    fun missingSpineFallsBackToXhtmlResourcesAsAndroidDoes() {
        val book = parse(spine = "")
        assertEquals("OEBPS/chapter.xhtml", book.spine.first().href)
    }

    @Test
    fun epub2NcxAndEpub3NavBothProduceReadableContent() {
        for (version in listOf("2.0", "3.0")) {
            val ncx = """<ncx><navMap><navPoint><navLabel><text>第一章</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap></ncx>"""
            val book = if (version == "2.0") {
                parse(version = version, tocType = "application/x-dtbncx+xml", tocProperties = "", files = mapOf("OEBPS/nav.xhtml" to ncx))
            } else parse()
            assertContains(read(book, book.toc.single()), "可以阅读的正文")
        }
    }

    @Test
    fun fragmentsSeparateChaptersWithinOneXhtml() {
        val book = parse(
            nav = """<nav epub:type="toc"><ol><li><a href="chapter.xhtml#one">第一章</a></li><li><a href="chapter.xhtml#two">第二章</a></li></ol></nav>""",
            files = mapOf("OEBPS/chapter.xhtml" to """<html><body><h2 id="one">第一章</h2><p>第一段正文</p><h2 id="two">第二章</h2><p>第二段正文</p></body></html>"""),
        )
        val first = read(book, book.toc[0], book.toc[1])
        assertContains(first, "第一段正文")
        assertFalse(first.contains("第二段正文"))
        val second = read(book, book.toc[1])
        assertContains(second, "第二段正文")
        assertFalse(second.contains("第一段正文"))
    }

    @Test
    fun resolvesImageUrisWithoutChangingRemoteUrlsOrLiteralPlus() {
        assertEquals("OEBPS/Images/封面+1.jpg", EpubParser.resolvePath("OEBPS/Text/ch.xhtml", "../Images/%E5%B0%81%E9%9D%A2+1.jpg"))
        assertEquals("OEBPS/ch.xhtml#one", EpubParser.resolvePath("OEBPS/ch.xhtml", "#one"))
        val remote = "https://example.com/image%20one.jpg"
        assertEquals(remote, EpubParser.resolvePath("OEBPS/ch.xhtml", remote))
        assertEquals("bad%xx", EpubParser.decodeHref("bad%xx"))
    }

    @Test
    fun recognizesSpaceSeparatedNavProperties() {
        val book = parse(tocProperties = "scripted nav", spine = """<spine><itemref idref="chapter"/></spine>""")
        assertEquals("第一章", book.toc.single().title)
    }

    @Test
    fun missingChapterResourceReportsFailureInsteadOfBlankContent() {
        assertFailsWith<IllegalStateException> {
            EpubContentReader.read(parse().spine, BookChapter(url = "missing.xhtml"), 0)
        }
    }

    @Test
    fun contentIncludesSpineSectionsUntilNextTocChapter() {
        val book = parse(
            manifest = """
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="middle" href="middle.xhtml" media-type="application/xhtml+xml"/>
                <item id="last" href="last.xhtml" media-type="application/xhtml+xml"/>
            """.trimIndent(),
            spine = """<spine><itemref idref="chapter"/><itemref idref="middle"/><itemref idref="last"/></spine>""",
            nav = """<nav epub:type="toc"><ol><li><a href="chapter.xhtml">第一章</a></li><li><a href="last.xhtml">第二章</a></li></ol></nav>""",
            files = mapOf(
                "OEBPS/middle.xhtml" to "<html><body><p>续页正文</p></body></html>",
                "OEBPS/last.xhtml" to "<html><body><p>第二章正文</p></body></html>",
            ),
        )
        val content = read(book, book.toc[0], book.toc[1])
        assertContains(content, "可以阅读的正文")
        assertContains(content, "续页正文")
        assertFalse(content.contains("第二章正文"))
    }

    @Test
    fun chapterImagesResolveAgainstXhtmlLocation() {
        val book = parse(files = mapOf("OEBPS/chapter.xhtml" to """
            <html><body><p>正文</p><img src="Images/cover%20one.jpg"/>
            <img src="https://example.com/remote.jpg"/></body></html>
        """.trimIndent()))
        val content = read(book, book.toc.single())
        assertContains(content, "OEBPS/Images/cover one.jpg")
        assertContains(content, "https://example.com/remote.jpg")
    }

    private fun read(book: EpubBook, ref: EpubChapter, next: EpubChapter? = null): String {
        val chapter = BookChapter(url = ref.completeHref).apply {
            startFragmentId = ref.fragmentId
            endFragmentId = next?.fragmentId
            next?.let { putVariable("nextUrl", it.completeHref) }
        }
        return EpubContentReader.read(book.spine, chapter, 0)
    }

    private val chapter = """<html><head><title>第一章</title></head><body><p id="正文">可以阅读的正文</p></body></html>"""
    private val normalNav = """<nav epub:type="toc"><ol><li><a href="chapter.xhtml">第一章</a></li></ol></nav>"""

    private fun parse(
        manifest: String = """<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>""",
        nav: String = normalNav,
        prefix: String = "",
        spine: String = """<spine toc="toc"><itemref idref="chapter"/></spine>""",
        tocType: String = "application/xhtml+xml",
        tocProperties: String = "nav",
        files: Map<String, String> = emptyMap(),
        version: String = "3.0",
        guide: String = "",
    ): EpubBook {
        var opf = """
            <package xmlns:opf="http://www.idpf.org/2007/opf" version="$version">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>测试书</dc:title><dc:creator>作者</dc:creator>
              </metadata>
              <manifest>$manifest<item id="toc" href="nav.xhtml" media-type="$tocType" properties="$tocProperties"/></manifest>
              $spine
              $guide
            </package>
        """.trimIndent()
        if (prefix.isNotEmpty()) {
            opf = opf.replace(Regex("<(\\/?)(package|metadata|manifest|item|spine|itemref)(?=[\\s/>])"), "<$1$prefix$2")
        }
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to opf,
            "OEBPS/chapter.xhtml" to chapter,
            "OEBPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>$nav</body></html>""",
        ).apply { putAll(files) }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return EpubParser.parse(output.toByteArray())
    }
}
