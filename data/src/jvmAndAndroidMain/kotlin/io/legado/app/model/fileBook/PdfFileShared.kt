package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.InputStream
import java.io.ByteArrayInputStream
import kotlin.concurrent.Volatile
import kotlin.math.ceil

/**
 * PDF 本地书编排骨架 (jvmAndAndroidMain, 对照 app 端 `PdfFile`)。
 *
 * 骨架只做编排: 每 [PAGE_SIZE] 页一章 (`分段_N`, url `pdf_N`)、正文按
 * `<img src="页码">` 拼装、首页渲染 JPEG 写封面、打开失败按"书籍导入异常"处理、
 * 按 bookUrl 换书关闭重建单例。渲染后端 (PdfRenderer / PDFBox) 经
 * [PdfRenderProviders] 注入 [PdfRenderPort]。
 *
 * 页图缓存差异: app 端由 ImageProvider 外层编排缓存 (端口缓存钩子为 no-op),
 * 桌面端走 BookImageStorage (缓存 key 带渲染宽度), 均由端口实现内聚。
 */
object PdfFileShared : BaseFileBook {

    private var pFile: PdfBookFile? = null

    /** pdf分页尺寸 */
    const val PAGE_SIZE = 10

    @Synchronized
    private fun getPFile(book: Book): PdfBookFile {
        if (pFile == null || pFile?.book?.bookUrl != book.bookUrl || pFile?.invalid == true) {
            pFile?.closePdf()
            pFile = PdfBookFile(book)
            return pFile!!
        }
        pFile?.book = book
        return pFile!!
    }

    @Synchronized
    override fun upBookInfo(book: Book) {
        getPFile(book).upBookInfo()
    }

    override fun getChapterList(book: Book): ArrayList<BookChapter> {
        return getPFile(book).getChapterList()
    }

    override fun getContent(book: Book, chapter: BookChapter): String? {
        return getPFile(book).getContent(chapter)
    }

    override fun getImage(book: Book, href: String): InputStream? {
        return getPFile(book).getImage(href)
    }

    override fun clear() {
        pFile?.closePdf()
        pFile = null
    }
}

/**
 * 单本书的 PDF 解析实例 (骨架): 持有书与打开状态, 渲染一律走 [PdfRenderPort]。
 */
class PdfBookFile(internal var book: Book) {

    private val port = PdfRenderProviders.get()

    /** 打开失败标记: [PdfFileShared.getPFile] 据此丢弃实例, 下次调用重建重试。 */
    @Volatile
    internal var invalid: Boolean = false
        private set

    @Volatile
    private var open = false

    init {
        upBookCover(true)
    }

    /** 懒打开 (对齐 app 端 `pdfRenderer` 的 @Volatile + synchronized 双检)。 */
    private fun ensureOpen(): Boolean {
        if (open) return true
        return synchronized(this) {
            open || port.openDocument(book).also {
                open = it
                if (!it) invalid = true
            }
        }
    }

    private fun pageCount(): Int = if (ensureOpen()) port.pageCount() else 0

    /** 关闭pdf文件 */
    fun closePdf() {
        runCatching { port.closeDocument() }
        open = false
    }

    internal fun getContent(chapter: BookChapter): String? {
        if (!ensureOpen()) return null
        val count = port.pageCount()
        return buildString {
            val start = chapter.index * PdfFileShared.PAGE_SIZE
            val end = ((chapter.index + 1) * PdfFileShared.PAGE_SIZE).coerceAtMost(count)
            (start until end).forEach {
                append("<img src=").append('"').append(it).append('"').append(" >")
                    .append('\n')
            }
        }
    }

    internal fun getImage(href: String): InputStream? {
        val index = href.toIntOrNull() ?: return null
        port.readCachedPage(book, index)?.let { return it }
        val bytes = port.renderPageJpegBytes(index) ?: return null
        port.writeCachedPage(book, index, bytes)
        return ByteArrayInputStream(bytes)
    }

    internal fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        val pageCount = pageCount()
        if (pageCount > 0) {
            val chapterCount = ceil(pageCount.toDouble() / PdfFileShared.PAGE_SIZE).toInt()
            (0 until chapterCount).forEach {
                val chapter = BookChapter()
                chapter.index = it
                chapter.bookUrl = book.bookUrl
                chapter.title = "分段_${it}"
                chapter.url = "pdf_${it}"
                chapterList.add(chapter)
            }
        }
        return chapterList
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            if (!ensureOpen()) return
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = FileBook.getCoverPath(book.bookUrl)
            }
            if (fastCheck && port.coverFileExists(book)) {
                return
            }
            val bytes = port.renderPageJpegBytes(0) ?: return
            port.writeCoverFile(book, bytes)
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.message}", e)
        }
    }

    fun upBookInfo() {
        if (!ensureOpen()) {
            closePdf()
            invalid = true
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".pdf", "")
            }
        }
    }
}
