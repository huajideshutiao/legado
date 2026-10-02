package io.legado.app.format.epub

import io.legado.app.data.entities.BookChapter
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.utils.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import okio.FileSystem

/**
 * [EpubParser] 全链回归 (nativeMain: iOS/鸿蒙)。
 *
 * 原先位于 jvmAndAndroidTest, 解析器下沉 nativeMain 后该源集看不到它, 故随之下沉到
 * nativeTest (dependsOn nativeMain)。zip 字节用 [NativeZipCodec] 构造, 不再依赖
 * java.util.zip, 使同一份用例在 iOS/鸿蒙 target 上可运行。
 */
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
        for (image in listOf(
            """<img src="../Images/cover.jpg"/>""",
            """<svg><image xlink:href="../Images/cover.jpg"/></svg>""",
        )) {
            val book = parse(
                manifest = """
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml" properties="cover-image"/>
                """.trimIndent(),
                files = mapOf(
                    "OEBPS/Text/cover.xhtml" to """<html><body>$image</body></html>""",
                    "OEBPS/Images/cover.jpg" to "jpg",
                ),
            )
            assertEquals("OEBPS/Images/cover.jpg", book.coverImage?.href)
        }
    }

    @Test
    fun urlEncodedManifestHrefDecodesToZipEntryPath() {
        val book = parse(
            manifest = """<item id="chapter" href="Text/%E7%AC%AC%E4%B8%80%20%E7%AB%A0.xhtml" media-type="application/xhtml+xml"/>""",
            nav = """<nav epub:type="toc"><ol><li><a href="Text/%E7%AC%AC%E4%B8%80%20%E7%AB%A0.xhtml#%E6%AD%A3%E6%96%87">第一章</a></li></ol></nav>""",
            files = mapOf(
                "OEBPS/Text/第一 章.xhtml" to """<html><body><p id="正文">可以阅读的正文</p></body></html>""",
            ),
        )
        assertEquals("OEBPS/Text/第一 章.xhtml", book.spine.single().href)
        assertEquals("正文", book.toc.single().fragmentId)
        assertEquals(book.spine.single(), book.toc.single().resource)
        assertContains(read(book, book.toc.single()), "可以阅读的正文")
    }

    @Test
    fun spineOrderDecidesChapterOrderNotManifestOrder() {
        val book = parse(
            manifest = """
                <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
                <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
            """.trimIndent(),
            spine = """<spine toc="toc"><itemref idref="first"/><itemref idref="second"/></spine>""",
            nav = """<nav epub:type="toc"><ol><li><a href="first.xhtml">第一章</a></li></ol></nav>""",
            files = mapOf(
                "OEBPS/first.xhtml" to """<html><body><p>可以阅读的正文</p></body></html>""",
                "OEBPS/second.xhtml" to """<html><body><p>第二章正文</p></body></html>""",
            ),
        )
        assertEquals(listOf("OEBPS/first.xhtml", "OEBPS/second.xhtml"), book.spine.map { it.href })
    }

    @Test
    fun epub3NavProducesTocWithResources() {
        val book = parse()
        assertEquals("第一章", book.toc.single().title)
        assertNotNull(book.toc.single().resource)
    }

    @Test
    fun nestedNavListItemsBecomeNestedTocChapters() {
        val book = parse(
            nav = """
                <nav epub:type="toc"><ol>
                  <li><span>第一卷</span><ol><li><a href="chapter.xhtml">第一章</a></li></ol></li>
                </ol></nav>
            """.trimIndent(),
        )
        assertEquals("第一卷", book.toc.single().title)
        assertEquals("第一章", book.toc.single().children.single().title)
        assertNotNull(book.toc.single().children.single().resource)
    }

    @Test
    fun epub3WithNcxUsesNcxParser() {
        val book = parse(
            nav = "", tocType = "application/x-dtbncx+xml", tocProperties = "",
            files = mapOf(
                "OEBPS/nav.xhtml" to """
                    <ncx><navMap><navPoint id="one"><navLabel><text>第一章</text></navLabel>
                    <content src="chapter.xhtml"/></navPoint></navMap></ncx>
                """.trimIndent(),
            ),
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
            } else {
                parse()
            }
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
        val book = parse(
            files = mapOf(
                "OEBPS/chapter.xhtml" to """
                    <html><body><p>正文</p><img src="Images/cover%20one.jpg"/>
                    <img src="https://example.com/remote.jpg"/></body></html>
                """.trimIndent(),
            ),
        )
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
        return EpubParser.parse(zipBytes(entries))
    }

    /**
     * 用 [NativeZipCodec] 把 entry 映射打成 zip 字节。
     *
     * 该编解码器的公共接口是"文件路径 → zip 文件", 且目录会被冠上目录名作 entry 前缀,
     * 故把内容先写到名为 `OEBPS`/`META-INF` 的目录下再分别打包 —— 这样 entry 名与
     * EPUB 规范要求的 zip 内路径一致。
     */
    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "legado-epub-parser-test"
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        FileSystem.SYSTEM.createDirectories(root)
        val srcPaths = mutableListOf<String>()
        // 按顶层目录名分组: 每组作为一次 zipFiles 的源目录 (entry 前缀即该目录名)
        entries.entries.groupBy { it.key.substringBefore('/', "") }.forEach { (top, items) ->
            if (top.isEmpty()) return@forEach
            val topDir = root / top
            FileSystem.SYSTEM.createDirectories(topDir)
            items.forEach { (path, content) ->
                val relative = path.removePrefix("$top/")
                val target = if (relative.isEmpty()) topDir else topDir / relative
                FileSystem.SYSTEM.createDirectories(target.parent ?: topDir)
                FileSystem.SYSTEM.write(target) { writeUtf8(content) }
            }
            srcPaths += topDir.toString()
        }
        val zipPath = root / "book.epub"
        val ok = NativeZipCodec.zipFiles(srcPaths, zipPath.toString())
        check(ok) { "NativeZipCodec.zipFiles 失败" }
        return File(zipPath.toString()).readBytes()
    }
}
