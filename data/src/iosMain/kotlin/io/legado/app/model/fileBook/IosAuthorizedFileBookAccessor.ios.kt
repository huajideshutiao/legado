package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.utils.InputStream
import io.legado.app.utils.IosSecurityScopeLease
import io.legado.app.utils.IosSecurityScopedStorage

/**
 * iOS [FileBookAccessor] 授权装饰器: 本地书的文件访问包一层 security-scoped 授权租约,
 * 其余行为逐字委托 [NativeFileBookAccessor]。
 *
 * # 为什么需要
 * 用户经"文件"面板授权的书籍目录在应用容器之外, 直接按 POSIX 路径读会因缺少 security scope
 * 失败。授权层 ([IosSecurityScopedStorage]) 只负责持久书签与租约, 真正的文件 IO 仍由
 * [NativeFileBookAccessor] (即 foundation 的 [io.legado.app.utils.File]/`File.inputStream`) 唯一实现 ——
 * 本类只决定"什么时候持有授权", 不读写字节。
 *
 * # 租约生命周期 (按访问粒度配对 start/stop)
 * - [getBookInputStream]: 租约随返回的输入流 [InputStream.close] 释放 (TXT 分章按流读, 流活多久授权就多久);
 * - [getHandler]: TXT/EPUB/CBZ 解析器在 `getChapterList`/`getContent`/`getImage` 内自行读文件
 *   (EPUB 整文件解析、CBZ 随机读 zip 中央目录), 无法从外部按流配对, 故对整个解析调用持租约,
 *   调用返回即释放;
 * - [getLastModified] / 其它属性读取: 单次调用内起停;
 * - [importLocalFile] / [importFromArchive] / [deleteBook]: 源文件在外部目录的导入与删原文件,
 *   整个动作期间持租约 (导入浏览器压缩包上架 / 文件关联导入 / 书架删原文件都走这里)。
 *
 * 沙盒内文件 (`{filesDir}/books` 导入副本) 由授权层自动放行, 不产生额外 scope。
 */
class IosAuthorizedFileBookAccessor(
    // concrete 类型: 复用其 file:// 路径解析给授权层定位文件 (不另写第二套解析)
    private val delegate: NativeFileBookAccessor = NativeFileBookAccessor(),
) : FileBookAccessor by delegate {

    override fun getBookInputStream(book: Book): InputStream {
        val path = localPath(book)
        if (path == null) return delegate.getBookInputStream(book)
        val lease = IosSecurityScopedStorage.acquireLease(path)
        val stream = try {
            IosSecurityScopedStorage.withAccess(path) {
                delegate.getBookInputStream(book)
            }
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
        return LeaseHoldingInputStream(stream, lease)
    }

    override fun getLastModified(book: Book): Result<Long> {
        val path = localPath(book) ?: return delegate.getLastModified(book)
        return runCatching {
            IosSecurityScopedStorage.withAccess(path) {
                delegate.getLastModified(book).getOrThrow()
            }
        }
    }

    override fun getHandler(book: Book): BaseFileBook {
        val path = localPath(book) ?: return delegate.getHandler(book)
        // 解析器内部读文件: 租约覆盖整个解析调用, 返回即释放
        return LeasedFileBook(delegate.getHandler(book), path)
    }

    override fun importLocalFile(uriStr: String): Book {
        return leaseAround(delegate.resolveLocalFile(uriStr).path) {
            delegate.importLocalFile(uriStr)
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        return leaseAround(delegate.resolveLocalFile(archiveFileUri).path) {
            delegate.importFromArchive(archiveFileUri, saveFileName, filter)
        }
    }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 只有删原文件需要授权 (封面/章节缓存都在沙盒内)
        if (!deleteOriginal || !book.bookUrl.startsWith("file:")) {
            delegate.deleteBook(book, deleteOriginal)
            return
        }
        leaseAround(delegate.resolveLocalFile(book.bookUrl).path, write = true) {
            delegate.deleteBook(book, deleteOriginal)
        }
    }

    private fun <T> leaseAround(path: String, write: Boolean = false, block: () -> T): T =
        IosSecurityScopedStorage.withAccess(path, write) { block() }

    /** 本地书文件路径 (非本地书返回 null, 走无授权路径)。 */
    private fun localPath(book: Book): String? {
        if (!book.bookUrl.startsWith("file:")) return null
        return LocalBookLocators.get().getLocalPath(book)
            ?: book.bookUrl.removePrefix("file://")
    }
}

/**
 * 持租约的输入流: 所有读写语义逐字转发给 [delegate] (IO 实现只有一份),
 * 只在 [close] 时先关流再释放 security-scoped 授权。
 */
private class LeaseHoldingInputStream(
    private val delegate: InputStream,
    private val lease: IosSecurityScopeLease,
) : InputStream() {

    override fun read(): Int = delegate.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

    override fun skip(n: Long): Long = delegate.skip(n)

    override fun available(): Int = delegate.available()

    override fun close() {
        try {
            delegate.close()
        } finally {
            lease.close()
        }
    }
}

/**
 * 持租约的解析器: 解析方法在租约内执行 (start → 解析 → stop), 解析结果与 IO 全部由 [delegate] 产生。
 */
private class LeasedFileBook(
    private val delegate: BaseFileBook,
    private val path: String,
) : BaseFileBook {

    override fun upBookInfo(book: Book) = withLease { delegate.upBookInfo(book) }

    override fun getChapterList(book: Book): ArrayList<BookChapter> =
        withLease { delegate.getChapterList(book) }

    override fun getContent(book: Book, chapter: BookChapter): String? =
        withLease { delegate.getContent(book, chapter) }

    override fun getImage(book: Book, href: String): InputStream? {
        // 返回的流由调用方消费: 租约随流 close 释放 (与 getBookInputStream 同契约)
        val lease = IosSecurityScopedStorage.acquireLease(path)
        val stream = try {
            IosSecurityScopedStorage.withAccess(path) {
                delegate.getImage(book, href)
            }
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
        return stream?.let { LeaseHoldingInputStream(it, lease) } ?: run {
            lease.close()
            null
        }
    }

    override fun clear() = delegate.clear()

    private fun <T> withLease(block: () -> T): T =
        IosSecurityScopedStorage.withAccess(path) { block() }
}

/**
 * iOS 宿主注册本地书访问器: [NativeFileBookAccessor] 外裹 security-scoped 授权装饰器,
 * 并同时注册 cbz 容器工厂 (与 [registerNativeFileBookAccessor] 同样的两件事, 只是换了
 * FileBookAccessor 实现; 鸿蒙仍走 nativeMain 原函数)。
 *
 * 须在 BookStorage/LocalBookLocator/BitmapProviders 之后, 任何 FileBook 调用之前。
 */
fun registerIosFileBookAccessor() {
    FileBookProviders.register(IosAuthorizedFileBookAccessor())
    ZipFileWrapperFactoryProviders.register(IosZipFileWrapperFactory())
}
