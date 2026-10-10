package io.legado.app.model.fileBook

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.requireBookSaveDir
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.File
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
 * - [importLocalFile] / [importFromArchive]: 源文件在外部目录的导入, 整个动作期间持租约
 *   (导入浏览器压缩包上架 / 文件关联导入); 压缩包分支内部还会把解出的书写进"书籍保存位置",
 *   故同时持落地目录租约;
 * - [importRemoteBook]: 整段持落地目录租约 (内部用 [NativeFileBookAccessor.saveBookFile] 落盘);
 * - [deleteBook]: 源文件在外部目录的删原文件, 整个动作期间持租约 (书架删原文件走这里);
 *   源文件本就不存在视为删除已达成 (见该方法注释)。
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
        // 源文件 (可能在别的授权目录) 与解出书籍的落地目录 ("书籍保存位置") 都要持租约;
        // 落地目录只有压缩包分支会写 (由 delegate 内部调 saveBookFile), 未设置保存位置时
        // 本条不写盘, 故只在其已设置时纳入租约 (写入方报未设置)
        return withAccesses(
            listOfNotNull(delegate.resolveLocalFile(uriStr).path, effectiveBookSaveDir())
        ) {
            delegate.importLocalFile(uriStr)
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        // 源压缩包 (可能在别的授权目录) 与解出书籍的落地目录 ("书籍保存位置") 都要持租约
        return withAccesses(
            listOfNotNull(delegate.resolveLocalFile(archiveFileUri).path, effectiveBookSaveDir())
        ) {
            delegate.importFromArchive(archiveFileUri, saveFileName, filter)
        }
    }

    /**
     * 删本地书 (清章节缓存 + 删封面 + 按 [deleteOriginal] 删源文件); 源文件在授权目录时整段持租约。
     *
     * 源文件本就不存在时按删除已达成处理: 不进删除协调, 也不因源文件缺席而走进失败分支 ——
     * 系统删除协调按 `forDeleting` 通知的是"被删除的那个条目", 条目不存在时报错, 会被上层当成
     * "源文件删不掉"提示给用户 (库里已删、文件本来也不在)。缓存与封面照旧清理。
     */
    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 只有删原文件需要授权 (封面/章节缓存都在沙盒内)
        if (!deleteOriginal || !book.bookUrl.startsWith("file:")) {
            delegate.deleteBook(book, deleteOriginal)
            return
        }
        val path = delegate.resolveLocalFile(book.bookUrl).path
        if (!sourceFileExists(path)) {
            delegate.deleteBook(book, deleteOriginal = false)
            return
        }
        leaseAround(path, write = true) {
            delegate.deleteBook(book, deleteOriginal)
        }
    }

    /**
     * 在 security scope 内判断源文件是否存在 (沙盒内路径同样适用)。
     *
     * 授权之外还要的是"文件在不在", 故直接持租约取值, 不走
     * [IosSecurityScopedStorage.withAccess] 的读协调: 读协调对不存在的目标报错, 拿不到一个干净的
     * "不存在"结论。授权失效时 [IosSecurityScopedStorage.acquireLease] 抛 SecurityException ——
     * 此源文件是否存在无法判定, 按授权失败如实上报。
     */
    private fun sourceFileExists(path: String): Boolean {
        val lease = IosSecurityScopedStorage.acquireLease(path)
        return try {
            File(path).exists()
        } finally {
            lease.close()
        }
    }

    /**
     * 保存新书到"有效书籍保存位置" (用户在"书籍保存位置"选定):
     * 写入前取一次 security-scoped 租约, 写完即释放。
     *
     * 直接持租约 ([IosSecurityScopedStorage.acquireLease]) 而不走
     * [IosSecurityScopedStorage.withAccess] 的 NSFileCoordinator: 新建文件时协调器会对尚不存在的
     * 目标路径报错, 而这里需要的只是授权本身; 沙盒内路径 (导入副本) 无需 scope, 租约为空操作。
     *
     * 覆写所有能直接或间接触发写入的入口 ([importLocalFile] / [importFromArchive] /
     * `saveBookFile` 两个重载 / [saveBookFileFromWebDav] / [downloadRemoteBook] / [importRemoteBook])
     * 的原因: `by delegate` 只转发未被覆写的方法, delegate 内部再调 `saveBookFile` 不会回到本类,
     * 故每个能触达落盘的入口都要单独包一层。
     */
    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        val lease = IosSecurityScopedStorage.acquireLease(requireBookSaveDir())
        return try {
            delegate.saveBookFile(inputStream, fileName)
        } finally {
            lease.close()
        }
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        val lease = IosSecurityScopedStorage.acquireLease(requireBookSaveDir())
        return try {
            delegate.saveBookFile(str, fileName, source)
        } finally {
            lease.close()
        }
    }

    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        val lease = IosSecurityScopedStorage.acquireLease(requireBookSaveDir())
        return try {
            delegate.saveBookFileFromWebDav(webDav, fileName)
        } finally {
            lease.close()
        }
    }

    override fun downloadRemoteBook(book: Book): Boolean {
        val lease = IosSecurityScopedStorage.acquireLease(requireBookSaveDir())
        return try {
            delegate.downloadRemoteBook(book)
        } finally {
            lease.close()
        }
    }

    /**
     * 导入远程书 (WebDav 浏览导入): delegate 内部经 `saveBookFile` 把书落到"有效书籍保存位置",
     * 该调用不回到本类, 故整段持落地目录租约 (内部还可能再走 importFromArchive/importLocalFile,
     * 它们的落地目录与本租约同一份)。
     */
    override suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean
    ): Book {
        val lease = IosSecurityScopedStorage.acquireLease(requireBookSaveDir())
        return try {
            delegate.importRemoteBook(webDav, serverID, name, path, size, lastModify, downloadFile)
        } finally {
            lease.close()
        }
    }

    private fun <T> leaseAround(path: String, write: Boolean = false, block: () -> T): T =
        IosSecurityScopedStorage.withAccess(path, write) { block() }

    /**
     * 依次在多个路径的 security scope 内执行 [block] (同一授权根重复取租约安全, 沙盒路径自动放行)。
     * 用于一次操作同时读写多个授权目录 (如从授权目录的压缩包解出书, 落到"书籍保存位置")。
     */
    private fun <T> withAccesses(paths: List<String>, block: () -> T): T {
        var action: () -> T = block
        for (path in paths.asReversed()) {
            val inner = action
            action = { IosSecurityScopedStorage.withAccess(path) { inner() } }
        }
        return action()
    }

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
