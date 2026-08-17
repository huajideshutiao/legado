package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.ScreenInfoProviders
import io.legado.desktop.model.fileBook.DesktopPdfFile.Companion.PAGE_SIZE
import io.legado.desktop.model.webBook.placeholderImageChapter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil

/**
 * PDF 本地书解析 (桌面 JVM 版, 对齐 app 端 [io.legado.app.model.fileBook.PdfFile])。
 *
 * app 端用 `android.graphics.pdf.PdfRenderer`, 桌面端换 Apache PDFBox; 分章 (每 [PAGE_SIZE]
 * 页一章 `分段_N`)、正文 (`<img src="页码">`)、渲染宽度 (屏幕宽) 与封面 (首页 JPEG 90) 逐项对齐。
 *
 * 两处差异: app 端由 `ImageProvider.cacheImage` 把 [getImage] 的流写进图片缓存, 桌面端无对应
 * 编排层, 故直接走 [BookImageStorageProviders]; PDF outline app 端本就不解析, 此处也不加。
 */
class DesktopPdfFile(var book: Book) {

    companion object : BaseFileBook {

        private var pFile: DesktopPdfFile? = null

        /** pdf 分页尺寸 (每章页数, 对齐 app 端 `PdfFile.PAGE_SIZE`)。 */
        const val PAGE_SIZE = 10

        @Synchronized
        private fun getPFile(book: Book): DesktopPdfFile {
            if (pFile == null || pFile?.book?.bookUrl != book.bookUrl) {
                pFile?.closePdf()
                pFile = DesktopPdfFile(book)
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

    @Volatile
    private var document: PDDocument? = null

    @Volatile
    private var renderer: PDFRenderer? = null

    init {
        upBookCover(true)
    }

    /** 渲染目标宽度 (对齐 app 端 `SystemUtils.screenWidthPx`; provider 未注册时回退 1080)。 */
    private val targetWidth: Int
        get() = runCatching { ScreenInfoProviders.get().screenWidthPx }.getOrNull()
            ?.takeIf { it > 0 } ?: 1080

    /**
     * 懒加载 PDF (对齐 app 端 `pdfRenderer` 的 @Volatile + synchronized 双检)。
     * 读取失败返回 null, 由调用方按 "书籍导入异常" 处理。
     */
    private fun getRenderer(): PDFRenderer? {
        renderer?.let { return it }
        return synchronized(this) {
            renderer ?: runCatching {
                val doc = Loader.loadPDF(resolvePdfFile())
                document = doc
                PDFRenderer(doc).also { renderer = it }
            }.onFailure {
                AppLog.put("读取PDF文件失败\n${it.localizedMessage}", it)
            }.getOrNull()
        }
    }

    /** bookUrl → 本地文件 (先走 [LocalBookLocators] 路径缓存, 对齐 accessor.openLocalFile)。 */
    private fun resolvePdfFile(): File {
        val path = runCatching { LocalBookLocators.get().getLocalPath(book) }.getOrNull()
        return path?.let { File(it) }?.takeIf { it.isFile } ?: resolveLocalBookFile(book.bookUrl)
    }

    /** 关闭 pdf 文件。 */
    fun closePdf() {
        runCatching { document?.close() }
        document = null
        renderer = null
    }

    /**
     * 渲染第 [index] 页 (对齐 app 端 `openPdfPage`: 宽度取屏幕宽, 高度按页面比例, 白底)。
     *
     * [ImageType.RGB] 为不透明位图, PDFBox 渲染前填白, 等价 app 端 `eraseColor(Color.WHITE)`。
     */
    private fun openPdfPage(index: Int): BufferedImage? {
        val renderer = getRenderer() ?: return null
        val doc = document ?: return null
        if (index < 0 || index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        val box = page.cropBox
        // 旋转 90/270 时输出宽高互换, 用旋转后的宽算缩放比才能得到 targetWidth 宽的图
        val rotated = page.rotation == 90 || page.rotation == 270
        val pageWidth = if (rotated) box.height else box.width
        if (pageWidth <= 0f) return null
        return renderer.renderImage(index, targetWidth / pageWidth, ImageType.RGB)
    }

    /** BufferedImage → JPEG 字节 (质量 90, 对齐 app 端 `Bitmap.compress(JPEG, 90)`)。 */
    private fun toJpegBytes(image: BufferedImage): ByteArray? = runCatching {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.9f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        out.toByteArray()
    }.getOrNull()

    /**
     * 取第 [href] 页图片 (href 为页码, 对应 [getContent] 生成的 `<img src="N">`)。
     *
     * 命中图片缓存直接读文件, 未命中才渲染并写入缓存 (缓存 key 带渲染宽度, 换屏幕后自然失效)。
     */
    private fun getImage(href: String): InputStream? {
        val index = href.toIntOrNull() ?: return null
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull()
        // 占位章节仅参与缓存路径签名 (同 DesktopImageControllerProvider 的 getImg)
        val chapter = placeholderImageChapter(href, book.bookUrl)
        val cacheKey = "pdf_${index}_$targetWidth.jpg"
        storage?.let { s ->
            runCatching { s.getImagePath(book, chapter, cacheKey) }.getOrNull()?.let { path ->
                File(path).takeIf { it.isFile }?.let { return it.inputStream() }
            }
        }
        val bytes = synchronized(this) { openPdfPage(index)?.let { toJpegBytes(it) } } ?: return null
        storage?.let { runCatching { it.saveImage(book, chapter, cacheKey, bytes) } }
        return ByteArrayInputStream(bytes)
    }

    /** 章节列表: 每 [PAGE_SIZE] 页一章 (app 端不解析 PDF outline, 此处一致)。 */
    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        getRenderer() ?: return chapterList
        val pageCount = document?.numberOfPages ?: 0
        if (pageCount > 0) {
            val chapterCount = ceil(pageCount.toDouble() / PAGE_SIZE).toInt()
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

    private fun getContent(chapter: BookChapter): String? {
        getRenderer() ?: return null
        val pageCount = document?.numberOfPages ?: return null
        return buildString {
            val start = chapter.index * PAGE_SIZE
            val end = ((chapter.index + 1) * PAGE_SIZE).coerceAtMost(pageCount)
            (start until end).forEach {
                append("<img src=").append('"').append(it).append('"').append(" >")
                    .append('\n')
            }
        }
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            getRenderer() ?: return
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = FileBook.getCoverPath(book.bookUrl)
            }
            val coverUrl = book.coverUrl ?: return
            if (fastCheck && File(coverUrl).exists()) {
                return
            }
            val bytes = synchronized(this) { openPdfPage(0)?.let { toJpegBytes(it) } } ?: return
            FileUtilsBase.createFileIfNotExist(coverUrl).writeBytes(bytes)
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
        }
    }

    private fun upBookInfo() {
        if (getRenderer() == null) {
            pFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".pdf", "")
            }
        }
    }
}
