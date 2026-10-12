package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.file.desktopResolveStoredRef
import io.legado.app.help.image.toSkiaImage
import io.legado.app.model.fileBook.PdfRenderPort
import io.legado.app.utils.ScreenInfoProviders
import io.legado.desktop.model.webBook.placeholderImageChapter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.impl.use
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * [PdfRenderPort] 桌面实现: Apache PDFBox 后端 (对齐原 DesktopPdfFile 的渲染行为)。
 *
 * 渲染宽度取屏幕宽 (provider 未注册时回退 1080), 白底 ([ImageType.RGB] 不透明位图
 * 渲染前填白), JPEG 质量 90; 页图缓存走 [BookImageStorageProviders] (缓存 key 带渲染
 * 宽度, 换屏幕后自然失效); 封面 coverUrl 是落库存储引用, 解析为本地路径后交骨架落盘。
 * 编排骨架在共享 [PdfFileShared], 注册: desktop main() 经 [PdfRenderProviders.register] 注入。
 */
object DesktopPdfRenderPort : PdfRenderPort {

    @Volatile
    private var document: PDDocument? = null

    @Volatile
    private var renderer: PDFRenderer? = null

    /** 渲染目标宽度 (对齐 app 端 `SystemUtils.screenWidthPx`; provider 未注册时回退 1080)。 */
    private val targetWidth: Int
        get() = runCatching { ScreenInfoProviders.get().screenWidthPx }.getOrNull()
            ?.takeIf { it > 0 } ?: 1080

    /**
     * 打开 [book] 对应的 PDF 文档; 失败返回 false 时文档与渲染器都不发布, 仍停在未打开状态
     * (骨架据此按"书籍导入异常"处理; 半开状态会让旧文档的渲染结果被当成新书内容)。
     */
    override fun openDocument(book: Book): Boolean = synchronized(this) {
        closeDocument()
        runCatching {
            val doc = Loader.loadPDF(resolvePdfFile(book))
            val pdfRenderer = runCatching { PDFRenderer(doc) }.getOrElse { e ->
                // 渲染器建不起来时文档不能留 (document 未发布, 关的是本地句柄)
                runCatching { doc.close() }
                throw e
            }
            document = doc
            renderer = pdfRenderer
        }.onFailure {
            AppLog.put("读取PDF文件失败\n${it.message}", it)
        }.isSuccess
    }

    override fun closeDocument() {
        runCatching { document?.close() }
        document = null
        renderer = null
    }

    override fun pageCount(): Int = document?.numberOfPages ?: 0

    /**
     * bookUrl → 本地文件 (取 [LocalBookLocators] 解析后的可用引用: 引用失效时按文件名在书籍保存位置/
     * 导入目录找回, 对齐 accessor.openLocalFile)。
     */
    private fun resolvePdfFile(book: Book): File {
        val path = runCatching { LocalBookLocators.get().getReadableFileRef(book) }.getOrNull()
        return path?.let { File(it) }?.takeIf { it.isFile } ?: resolveLocalBookFile(book.bookUrl)
    }

    override fun renderPageJpegBytes(index: Int): ByteArray? = synchronized(this) {
        val image = openPdfPage(index) ?: return null
        toJpegBytes(image)
    }

    /** 命中图片缓存直接读文件, 未命中才渲染并写缓存 (缓存 key 带渲染宽度)。 */
    override fun readCachedPage(book: Book, index: Int): InputStream? {
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull() ?: return null
        val chapter = placeholderImageChapter(index.toString(), book.bookUrl)
        val cacheKey = "pdf_${index}_$targetWidth.jpg"
        return runCatching { storage.getImagePath(book, chapter, cacheKey) }.getOrNull()?.let { path ->
            File(path).takeIf { it.isFile }?.inputStream()
        }
    }

    override fun writeCachedPage(book: Book, index: Int, bytes: ByteArray) {
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull() ?: return
        val chapter = placeholderImageChapter(index.toString(), book.bookUrl)
        val cacheKey = "pdf_${index}_$targetWidth.jpg"
        runCatching { storage.saveImage(book, chapter, cacheKey, bytes) }
    }

    override fun coverFilePath(book: Book): String? =
        book.coverUrl?.let { desktopResolveStoredRef(it).absolutePath }

    /**
     * 渲染第 [index] 页 (宽度取屏幕宽, 高度按页面比例, 白底)。
     *
     * [ImageType.RGB] 为不透明位图, PDFBox 渲染前填白, 等价 app 端 `eraseColor(Color.WHITE)`。
     */
    private fun openPdfPage(index: Int): BufferedImage? {
        val pdfRenderer = renderer ?: return null
        val doc = document ?: return null
        if (index < 0 || index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        val box = page.cropBox
        // 旋转 90/270 时输出宽高互换, 用旋转后的宽算缩放比才能得到 targetWidth 宽的图
        val rotated = page.rotation == 90 || page.rotation == 270
        val pageWidth = if (rotated) box.height else box.width
        if (pageWidth <= 0f) return null
        return pdfRenderer.renderImage(index, targetWidth / pageWidth, ImageType.RGB)
    }

    /** BufferedImage → JPEG 字节 (质量 90, 对齐 app 端 `Bitmap.compress(JPEG, 90)`)。 */
    private fun toJpegBytes(image: BufferedImage): ByteArray? = runCatching {
        image.toSkiaImage().use { it.encodeToData(EncodedImageFormat.JPEG, 90)?.bytes }
    }.getOrNull()
}
