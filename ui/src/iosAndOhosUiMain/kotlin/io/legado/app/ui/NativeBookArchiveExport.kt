package io.legado.app.ui

import io.documentnode.epub4kmp.domain.Author
import io.documentnode.epub4kmp.domain.Book as EpubBook
import io.documentnode.epub4kmp.domain.Date as EpubDate
import io.documentnode.epub4kmp.domain.MediaType
import io.documentnode.epub4kmp.domain.MediaTypes
import io.documentnode.epub4kmp.domain.Resource
import io.documentnode.epub4kmp.domain.TOCReference
import io.documentnode.epub4kmp.epub.EpubWriter
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.ExportBookUtils
import io.legado.app.utils.File
import io.legado.app.utils.scan.TagScan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.random.Random
import kotlin.time.Clock

/** iOS/OHOS archive export uses the same cached chapters and images as the reader. */
internal object NativeBookArchiveExport {
    suspend fun epub(book: Book, chapters: List<BookChapter>, target: File, useReplace: Boolean) {
        val epubBook = EpubBook()
        val author = book.getRealAuthor().trim()
        val intro = book.getDisplayIntro().orEmpty().trim()
        epubBook.metadata.apply {
            addTitle(book.name)
            addAuthor(Author(author))
            language = "zh"
            addDate(EpubDate(Clock.System.now()))
            addPublisher("Legado")
            addDescription(intro)
        }

        val cover = loadCover(book)
        if (cover != null) {
            epubBook.coverImage = Resource("cover-image", cover, "Images/cover.png")
        }
        epubBook.addResource(Resource("style", epubCss.encodeToByteArray(), "Styles/main.css"))
        val coverPage = Resource(
            "cover-page",
            """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh"><head><title>${xml(book.name)}</title><link rel="stylesheet" type="text/css" href="../Styles/main.css"/></head><body><div class="cover">${if (cover != null) "<img src=\"../Images/cover.png\" alt=\"封面\"/>" else ""}<h1>${xml(book.name)}</h1>${if (author.isNotEmpty()) "<div class=\"author\">${xml(author)}</div>" else ""}</div></body></html>""".encodeToByteArray(),
            "Text/cover.xhtml",
        )
        epubBook.addSection("封面", coverPage)
        epubBook.coverPage = coverPage
        epubBook.addSection(
            "内容简介",
            Resource(
                "intro",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh"><head><title>内容简介</title><link rel="stylesheet" type="text/css" href="../Styles/main.css"/></head><body><h1>内容简介</h1>${intro.lines().filter { it.isNotBlank() }.joinToString("") { "<p>${xml(it.trim())}</p>" }}</body></html>""".encodeToByteArray(),
                "Text/intro.xhtml",
            ),
        )

        val imageHrefs = linkedMapOf<String, String>()
        val replaceRules = ContentProcessorProviders.get().getTitleReplaceRules(book)
        var parentSection: TOCReference? = null
        chapters.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            // 与 Android missingChapterContent 一致：缺失缓存时保留章节，正文写空并留痕。
            val source = BookHelpProviders.get().getContent(book, chapter) ?: run {
                if (!chapter.isVolume) AppLog.put("导出 EPUB: 章节正文为空, 已写空正文 (${book.name} / ${chapter.title})")
                ""
            }
            val exportChapter = chapter.copy(isVip = false)
            val content = ContentProcessorProviders.get().getBookContent(
                book = book,
                chapter = exportChapter,
                content = source,
                includeTitle = false,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false,
            ).toString()
            val title = exportChapter.getDisplayTitle(replaceRules, useReplace = useReplace)
            // Android 不导出 VIP 状态，章节页标题会去掉锁标记；目录也去掉该标记。
            val exportTitle = title.replace("🔒", "")
            val body = content.lines().filter { it.isNotBlank() }.joinToString("\n") { rawLine ->
                // 对齐 Android StringUtil.formatHtml: 缓存中的段首空白先去掉，再由 CSS 缩进。
                val line = rawLine.trim()
                val html = StringBuilder()
                var start = 0
                imageRegex.findAll(line).forEach { match ->
                    html.append(xml(line.substring(start, match.range.first)))
                    val src = match.groupValues[1]
                    val imagePath = BookImageStorageProviders.get().getImagePath(book, chapter, src)
                    val image = imagePath?.let(::File)?.takeIf { it.isFile }
                    val href = if (image != null) {
                        imageHrefs.getOrPut(image.absolutePath) {
                            val extension = image.name.substringAfterLast('.', "jpg").lowercase()
                            val imageHref = "Images/image_${imageHrefs.size}.$extension"
                            val resource = object : Resource("image${imageHrefs.size}", ByteArray(0), imageHref) {
                                override fun bytes(): ByteArray = image.readBytes()
                            }
                            resource.mediaType = imageMediaType(extension)
                            epubBook.addResource(resource)
                            imageHref
                        }
                    } else {
                        // Android fixPic 在图片缓存缺失时保留原始 src，不中断导出。
                        src
                    }
                    html.append("<img src=\"")
                        .append(xml(if (image != null) "../$href" else href))
                        .append("\" alt=\"\"/>")
                    start = match.range.last + 1
                }
                html.append(xml(line.substring(start)))
                if (line.matches(imageRegex)) "<div class=\"image\">$html</div>" else "<p>$html</p>"
            }
            val chapterResource = Resource(
                "chapter$index",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh"><head><title>${xml(exportTitle)}</title><link rel="stylesheet" type="text/css" href="../Styles/main.css"/></head><body><h1>${xml(exportTitle)}</h1>$body</body></html>""".encodeToByteArray(),
                "Text/chapter_$index.xhtml",
            )
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(exportTitle, chapterResource)
            } else {
                val parent = parentSection
                if (parent == null) epubBook.addSection(exportTitle, chapterResource)
                else epubBook.addSection(parent, exportTitle, chapterResource)
            }
        }
        currentCoroutineContext().ensureActive()
        EpubWriter().write(epubBook, FileSystem.SYSTEM.sink(target.path.toPath()))
    }

    private suspend fun loadCover(book: Book): ByteArray? {
        val url = book.getDisplayCover()?.takeIf { it.isNotBlank() } ?: return null
        // 书架已加载的封面优先复用持久缓存，离线时也能导出。
        val cached = BookImageLoaders.getOrNull()?.loadDiskCachedBytes(url, book.origin)
        val bytes = cached ?: run {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            ImageBitmapLoader().loadBytes(url, book, source, isCover = true)
        } ?: return null
        // EPUB 2 阅读器普遍支持 PNG，统一编码也保证扩展名与真实格式一致。
        return runCatching { Image.makeFromEncoded(bytes).encodeToData(EncodedImageFormat.PNG, 90)?.bytes }
            .getOrNull()
    }

    private fun imageMediaType(extension: String): MediaType = when (extension) {
        "png" -> MediaTypes.PNG
        "jpg", "jpeg" -> MediaTypes.JPG
        "gif" -> MediaTypes.GIF
        "svg" -> MediaTypes.SVG
        else -> MediaType("image/$extension", ".$extension")
    }

    private val epubCss = """body { line-height: 1.3; }
p { text-indent: 2em; margin: 0 0 0.5em; }
.image { text-align: center; margin: 0.5em 0; }
.image img { max-width: 100%; height: auto; }
.cover { text-align: center; }
.cover img { max-width: 100%; max-height: 80vh; }
.author { text-align: center; }"""

    suspend fun cbz(book: Book, chapters: List<BookChapter>, target: File) {
        val work = File(target.parentFile ?: error("导出路径无效"), ".cbz-${Random.nextLong().toString(16)}")
        check(work.mkdirs()) { "无法创建 CBZ 临时目录" }
        try {
            val entries = mutableListOf<NativeArchiveZip.Entry>()
            chapters.forEachIndexed { chapterIndex, chapter ->
                currentCoroutineContext().ensureActive()
                val content = BookHelpProviders.get().getContent(book, chapter)
                    ?: return@forEachIndexed
                var page = 0
                TagScan.forEachNormalizedImgSrc(content) { src ->
                    val path = BookImageStorageProviders.get().getImagePath(book, chapter, src)
                        ?: return@forEachNormalizedImgSrc
                    val file = File(path)
                    if (!file.isFile) return@forEachNormalizedImgSrc
                    val ext = file.name.substringAfterLast('.', "jpg").lowercase()
                    val name = "${(chapterIndex + 1).toString().padStart(4, '0')}/${(++page).toString().padStart(4, '0')}.$ext"
                    entries.add(NativeArchiveZip.Entry(name, file))
                }
            }
            check(entries.isNotEmpty()) { "没有已缓存的图片" }
            val comicInfo = File(work, "ComicInfo.xml")
            comicInfo.writeText(ExportBookUtils.buildComicInfo(book, entries.size))
            entries.add(NativeArchiveZip.Entry("ComicInfo.xml", comicInfo))
            val context = currentCoroutineContext()
            NativeArchiveZip.write(target, entries) { context.ensureActive() }
        } finally {
            work.deleteRecursively()
        }
    }

    private val imageRegex = Regex("<img src=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)

    private fun xml(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
