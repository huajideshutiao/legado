@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.utils.File
import io.legado.app.utils.IosSecurityScopeLease
import io.legado.app.utils.IosSecurityScopedStorage
import io.legado.app.utils.InputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Foundation.fileURLWithPath
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import kotlin.concurrent.Volatile

/**
 * [PdfRenderPort] iOS 实现: 系统 PDFKit 后端 (PDFDocument/PDFPage, 不引第三方引擎)。
 *
 * 渲染宽 = 注册期主线程捕获的屏幕物理像素宽; 白底; JPEG 质量 90; 显示区取 cropBox
 * (PDFKit 官方定义的"可见区域", 页面缺省该 box 时官方按 MediaBox 兜底)。页图缓存走
 * [BookImageStorageProviders], key = `pdf_${index}_$width.jpg`。封面 coverUrl 是沙盒内
 * 明文路径 (NativeFileBookAccessor.getCoverPath), 直接返回。
 *
 * # 授权与寿命
 * openDocument 持 security-scoped 租约 ([IosSecurityScopeLease]) 直到 closeDocument:
 * 章节列表/逐章取图/缓存回读都在打开之后, 文档与授权须在渲染期持续有效; 换书/清理与
 * 加载失败经 closeDocument 释放, 不留半开状态。
 *
 * # 线程与调用方向
 * UIImage 官方 Note: 不可变图片对象 "safe to create and use from any thread"; 渲染器在
 * drawing block 内自建 CG 上下文并设为当前 context。PDFKit 无线程安全承诺, 其访问经端口
 * 互斥串行 (骨架锁/端口锁内的后台线程直接完成, 锁内不回调阅读层); UIScreen 是
 * @MainActor, 宽度只能在注册主线程读一次随实例缓存。
 */
class IosPdfRenderPort(initialScreenWidthPx: Int) : PdfRenderPort {

    private val lock = SynchronizedObject()

    @Volatile
    private var document: PDFDocument? = null

    /** openDocument 取得、closeDocument 释放的授权租约; null = 未打开。 */
    @Volatile
    private var lease: IosSecurityScopeLease? = null

    /** 渲染目标宽度 (注册主线程捕获的屏幕物理像素宽)。 */
    private val screenWidthPx: Int = initialScreenWidthPx

    /**
     * 打开 [book] 对应的 PDF 文档; 失败返回 false 时文档与租约都不发布, 仍停在未打开状态
     * (打不开/损坏/加密的 PDF 按真实失败上报, 骨架显示"书籍导入异常")。
     */
    override fun openDocument(book: Book): Boolean = synchronized(lock) {
        closeDocument()
        runCatching {
            val path = resolvePdfPath(book)
            val held = IosSecurityScopedStorage.acquireLease(path, DirUsage.BookTree)
            // 可失败初始化 (静态非空): 打不开时运行时异常, 捕获即真实失败
            val doc = try {
                PDFDocument(NSURL.fileURLWithPath(path))
            } catch (e: Throwable) {
                held.close()
                throw e
            }
            if (doc.isLocked) {
                // 加密文档: 无密码交互能力, 按真实失败上报
                held.close()
                throw NoStackTraceException("PDF 文档已加密: $path")
            }
            document = doc
            lease = held
        }.onFailure {
            AppLog.put("读取PDF文件失败\n${it.message}", it)
        }.isSuccess
    }

    /** 关闭文档 (幂等): 文档引用与授权租约一并释放。 */
    override fun closeDocument() {
        document = null
        lease?.close()
        lease = null
    }

    override fun pageCount(): Int = document?.pageCount()?.toInt() ?: 0

    override fun renderPageJpegBytes(index: Int): ByteArray? = synchronized(lock) {
        val doc = document ?: return@synchronized null
        if (index < 0 || index >= doc.pageCount().toInt()) return@synchronized null
        runCatching {
            // 缺页按页面失败处理 (不造空白页)
            val page = doc.pageAtIndex(index.toULong()) ?: return@runCatching null
            // bounds(for:) 在页空间且不含页面旋转; 尺寸只在借用作用域内读为 Double,
            // 借出的结构视图不出 useContents 作用域
            val (boxWidth, boxHeight) = page.boundsForBox(kPDFDisplayBoxCropBox)
                .useContents { size.width to size.height }
            val angle = page.rotation()
            val rotated = angle == 90L || angle == 270L
            val displayWidth = if (rotated) boxHeight else boxWidth
            val displayHeight = if (rotated) boxWidth else boxHeight
            if (displayWidth <= 0.0 || displayHeight <= 0.0) return@runCatching null
            val width = screenWidthPx.toDouble()
            val height = width * displayHeight / displayWidth
            val thumbnail = page.thumbnailOfSize(
                size = CGSizeMake(width, height),
                forBox = kPDFDisplayBoxCropBox,
            )
            // 白底合成: JPEG 无透明通道, 缩略图的透明区域直接编码会变黑底
            val format = UIGraphicsImageRendererFormat().apply {
                scale = 1.0
                opaque = true
            }
            var drew = false
            val data = UIGraphicsImageRenderer(
                size = CGSizeMake(width, height),
                format = format,
            ).JPEGDataWithCompressionQuality(0.9) { context ->
                // 上下文由渲染器必给; 缺失属契约破坏, 按渲染失败上报 (不产出白页假成功)
                val c = context ?: return@JPEGDataWithCompressionQuality
                UIColor.whiteColor.setFill()
                c.fillRect(CGRectMake(0.0, 0.0, width, height))
                // drawInRect 是 UIKit 绘制法, 自带 y 轴处理
                thumbnail.drawInRect(CGRectMake(0.0, 0.0, width, height))
                drew = true
            }
            if (!drew) return@runCatching null
            val bytes = data.bytes ?: return@runCatching null
            val size = data.length.toInt()
            if (size <= 0) return@runCatching null
            bytes.readBytes(size)
        }.onFailure {
            AppLog.put("渲染PDF页失败\n第 $index 页: ${it.message}", it)
        }.getOrNull()
    }

    /** 缓存 miss 返回 null; 存储真实异常按真实失败上抛 (已在注册序列成立的依赖不吞错)。 */
    override fun readCachedPage(book: Book, index: Int): InputStream? {
        val storage = BookImageStorageProviders.get()
        val cacheKey = "pdf_${index}_${screenWidthPx}.jpg"
        val path = storage.getImagePath(book, BookChapter(url = index.toString(), bookUrl = book.bookUrl), cacheKey)
        return path?.let { File(it).takeIf { f -> f.isFile }?.inputStream() }
    }

    override fun writeCachedPage(book: Book, index: Int, bytes: ByteArray) {
        val storage = BookImageStorageProviders.get()
        val cacheKey = "pdf_${index}_${screenWidthPx}.jpg"
        storage.saveImage(book, BookChapter(url = index.toString(), bookUrl = book.bookUrl), cacheKey, bytes)
    }

    override fun coverFilePath(book: Book): String? =
        // iOS 封面引用是沙盒内明文路径 (getCoverPath: coversDir/md516(bookUrl).jpg), 直接返回
        book.coverUrl?.takeIf { it.isNotEmpty() }

    /** bookUrl → 本地文件 (路径优先级与 accessor 同链: 协调旁路 → 定位器 → bookUrl 原始路径)。 */
    private fun resolvePdfPath(book: Book): String =
        FileBookOperationPath.current()
            ?: LocalBookLocators.get().getReadableFileRef(book)
            ?: book.bookUrl.removePrefix("file://")
}
