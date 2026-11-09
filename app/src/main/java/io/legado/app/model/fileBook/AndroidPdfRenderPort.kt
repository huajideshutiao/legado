package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.parseToUri
import io.legado.app.utils.printOnDebug
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * [PdfRenderPort] 安卓实现: `android.graphics.pdf.PdfRenderer` 后端。
 *
 * 渲染宽度取屏幕宽 (`SystemUtils.screenWidthPx`), 高度按页面比例, 白底;
 * 页图磁盘缓存经 [BookImageStorageProviders] 落到 AndroidBookImageStorage,
 * 缓存 key 带渲染宽度。
 * 注册: App.onCreate 经 [PdfRenderProviders.register] 注入。
 */
object AndroidPdfRenderPort : PdfRenderPort {

    @Volatile
    private var fileDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var pdfRenderer: PdfRenderer? = null

    override fun openDocument(book: Book): Boolean = synchronized(this) {
        // 端口契约: 已打开时先关旧文档, 失败必须回到未打开状态 (不清旧文档 + 旧渲染器残留时,
        // 骨架会把旧文档的渲染结果当成新书内容, 并因返回 true 而不标记本书导入异常)
        closeDocument()
        val descriptorResult = runCatching { openFileDescriptor(book) }
        val descriptor = descriptorResult.getOrNull()
        if (descriptor == null) {
            descriptorResult.exceptionOrNull()?.printOnDebug()
            return false
        }
        val renderer = runCatching { PdfRenderer(descriptor) }.getOrElse { e ->
            // 渲染器建不起来时描述符不能留; 两个字段都没发布, 仍停在未打开状态
            runCatching { descriptor.close() }
            e.printOnDebug()
            return false
        }
        fileDescriptor = descriptor
        pdfRenderer = renderer
        true
    }

    override fun closeDocument() {
        runCatching { pdfRenderer?.close() }
        runCatching { fileDescriptor?.close() }
        pdfRenderer = null
        fileDescriptor = null
    }

    override fun pageCount(): Int = pdfRenderer?.pageCount ?: 0

    override fun renderPageJpegBytes(index: Int): ByteArray? = synchronized(this) {
        val renderer = pdfRenderer ?: return null
        try {
            val bitmap = openPdfPage(renderer, index) ?: return null
            try {
                ByteArrayOutputStream().use { bos ->
                    // compress 返回 false 即未成功压缩, 按失败处理
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)) {
                        return null
                    }
                    bos.toByteArray()
                }
            } finally {
                // 端口独占的临时位图在编码终态释放像素
                bitmap.recycle()
            }
        } catch (e: Exception) {
            e.printOnDebug()
            null
        }
    }

    /** 读第 [index] 页缓存图: 缓存 key 与渲染同源取屏幕宽。 */
    override fun readCachedPage(book: Book, index: Int): InputStream? {
        val chapter = BookChapter(url = index.toString(), bookUrl = book.bookUrl)
        val path = BookImageStorageProviders.get()
            .getImagePath(book, chapter, "pdf_${index}_${SystemUtils.screenWidthPx}.jpg")
            ?: return null
        return File(path).takeIf { it.isFile }?.inputStream()
    }

    /** 写第 [index] 页缓存图: 缓存 key 与渲染同源取屏幕宽; 存储失败按未缓存处理并记录。 */
    override fun writeCachedPage(book: Book, index: Int, bytes: ByteArray) {
        val chapter = BookChapter(url = index.toString(), bookUrl = book.bookUrl)
        val saved = BookImageStorageProviders.get()
            .saveImage(book, chapter, "pdf_${index}_${SystemUtils.screenWidthPx}.jpg", bytes)
        if (saved == null) {
            AppLog.put("PDF 页图写入缓存失败")
        }
    }

    override fun coverFilePath(book: Book): String? =
        book.coverUrl?.takeIf { it.isNotEmpty() }

    /**
     * 打开书籍文件描述符: 内容 URI 与本地路径两种引用各自解析, 打不开返回 null
     * (由 [openDocument] 统一转成打开失败)。
     */
    private fun openFileDescriptor(book: Book): ParcelFileDescriptor? {
        val ref = LocalBookLocators.get().getReadableFileRef(book) ?: book.bookUrl
        val uri = ref.parseToUri()
        return if (uri.isContentScheme()) {
            App.instance.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    /**
     * 渲染PDF页面: 根据index打开pdf页面, 并渲染到Bitmap (宽度取屏幕宽, 白底)。
     */
    private fun openPdfPage(renderer: PdfRenderer, index: Int): Bitmap? {
        if (index >= renderer.pageCount) {
            return null
        }
        return renderer.openPage(index).use { page ->
            val bitmap = createBitmap(
                SystemUtils.screenWidthPx,
                (SystemUtils.screenWidthPx.toDouble() * page.height / page.width).toInt()
            )
            try {
                bitmap.apply {
                    this.eraseColor(Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } catch (e: Throwable) {
                // 渲染抛出时位图所有权尚未交出, 释放后原样上抛
                bitmap.recycle()
                throw e
            }
        }
    }
}
