package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.utils.InputStream
import kotlin.concurrent.Volatile

/**
 * PDF 渲染后端跨平台端口: 打开/关闭文档、按页渲染为 JPEG 字节、页图缓存与封面文件读写。
 *
 * 编排骨架 ([PdfFileShared] / PdfBookFile) 经 [PdfRenderProviders] 取本端口实现;
 * 宽度选择 (屏幕宽)、白底、JPEG 质量 90、渲染互斥均由实现内部决定, 骨架不感知。
 * app 端 `android.graphics.pdf.PdfRenderer` / 桌面端 Apache PDFBox 各自实现;
 * iOS/鸿蒙无渲染后端 (分派走 UnsupportedFileBook), 不注册。
 */
interface PdfRenderPort {

    /**
     * 打开 [book] 对应的 PDF 文档; 已打开时先关旧文档再开新文档。
     * 打开失败返回 false (骨架按"书籍导入异常"处理)。
     */
    fun openDocument(book: Book): Boolean

    /** 关闭文档 (幂等)。 */
    fun closeDocument()

    /** 文档页数 (未打开时返回 0)。 */
    fun pageCount(): Int

    /**
     * 渲染第 [index] 页为 JPEG 字节 (质量 90, 白底, 宽度取本端屏幕宽)。
     * 页码越界/渲染失败返回 null。实现内部负责渲染互斥。
     */
    fun renderPageJpegBytes(index: Int): ByteArray?

    /**
     * 读第 [index] 页的缓存图 (缓存 key 由实现派生, 如带渲染宽度)。
     * app 端页图缓存由 ImageProvider 外层编排, 本钩子返回 null 即可。
     */
    fun readCachedPage(book: Book, index: Int): InputStream?

    /** 写第 [index] 页的缓存图 (与 [readCachedPage] 同 key 规则; 无缓存机制时 no-op)。 */
    fun writeCachedPage(book: Book, index: Int, bytes: ByteArray)

    /** 封面文件是否已存在 (fastCheck 用; coverUrl 为落库引用, 由实现解析为本地路径)。 */
    fun coverFileExists(book: Book): Boolean

    /** 把封面 JPEG 字节写入 [book].coverUrl 指向的文件 (实现负责引用解析与建父目录)。 */
    fun writeCoverFile(book: Book, bytes: ByteArray)
}

/**
 * [PdfRenderPort] provider 容器。宿主启动早期注册一次。
 */
object PdfRenderProviders {
    @Volatile
    private var impl: PdfRenderPort? = null

    /** 宿主启动早期注册一次 (任何 PDF 书解析之前)。 */
    fun register(impl: PdfRenderPort) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): PdfRenderPort = impl ?: error("PdfRenderProviders not registered")
}
