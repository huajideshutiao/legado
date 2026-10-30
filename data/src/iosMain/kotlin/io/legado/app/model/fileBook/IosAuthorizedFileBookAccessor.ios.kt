package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.writeBytesAtomically
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.requireBookSaveDir
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.IosSecurityScopeLease
import io.legado.app.utils.IosSecurityScopedStorage
import io.legado.app.utils.atomicWriteTempPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager

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
 *   书文件读不到时按压缩包名找回原压缩包重新解压后打开 (见 [reopenFromArchive]);
 * - [getHandler]: TXT/EPUB/CBZ 解析器在 `getChapterList`/`getContent`/`getImage` 内自行读文件
 *   (EPUB 整文件解析、CBZ 随机读 zip 中央目录), 无法从外部按流配对, 故对整个解析调用持租约,
 *   调用返回即释放;
 * - [getLastModified] / 其它属性读取: 单次调用内起停;
 * - [importLocalFile] / [importFromArchive]: 源文件在外部目录的导入, 整个动作期间持租约
 *   (导入浏览器压缩包上架 / 文件关联导入); 压缩包分支内部还会把解出的书写进"书籍保存位置",
 *   故同时持落地目录租约;
 * - [deleteBook]: 源文件在外部目录的删原文件, 整个动作期间持租约 (书架删原文件走这里);
 *   源文件本就不存在视为删除已达成 (见该方法注释)。
 *
 * 读取一律在 NSFileCoordinator 协调块内、用块内给出的地址 (系统可能在块内给替代位置):
 * 打开书文件/取属性/导入复制/解包都这样。写入目标是尚不存在的新文件, 协调器会对新建目标报错,
 * 落盘只取 security-scoped 租约 (见 `saveBookFile` 系列)。
 *
 * 远程书导入 / 合并在线信息 / 远端下载的编排在 [FileBookRemoteImportShared] (四端共用),
 * 它每次都经 [FileBookProviders.get] 取实现落盘, 故写盘点全部回到本装饰器的租约入口。
 *
 * 沙盒内文件 (`{filesDir}/books` 导入副本) 由授权层自动放行, 不产生额外 scope。
 */
class IosAuthorizedFileBookAccessor(
    // concrete 类型: 复用其 file:// 路径解析给授权层定位文件 (不另写第二套解析)
    private val delegate: NativeFileBookAccessor = NativeFileBookAccessor(),
) : FileBookAccessor by delegate {

    override fun getBookInputStream(book: Book, parent: Job?): InputStream {
        val path = localPath(book)
        if (path == null) return delegate.getBookInputStream(book, parent)
        val opened = runCatching {
            val lease = IosSecurityScopedStorage.acquireLease(path, DirUsage.BookTree)
            try {
                LeaseHoldingInputStream(
                    IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree) { scopedPath ->
                        delegate.openLocalFile(scopedPath)
                    },
                    lease,
                )
            } catch (e: Throwable) {
                lease.close()
                throw e
            }
        }
        opened.getOrNull()?.let { return it }
        // 书文件读不到 (文件被删/授权失效): 按压缩包名找回原压缩包重新解压 (对照原版, 见 [reopenFromArchive])
        reopenFromArchive(book, parent)?.let { return it }
        throw opened.exceptionOrNull()!!
    }

    override fun getLastModified(book: Book): Result<Long> {
        val path = localPath(book) ?: return delegate.getLastModified(book)
        return runCatching {
            IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree) { scopedPath ->
                File(scopedPath).lastModified()
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
        // 本条不写盘, 只在其已设置时纳入租约 (写入方报未设置)。
        // delegate 收到的是协调器给出的源文件地址。
        return withSaveDirLease {
            IosSecurityScopedStorage.withAccess(
                delegate.resolveLocalFile(uriStr).path, DirUsage.BookTree,
            ) { scopedPath ->
                delegate.importLocalFile(scopedPath)
            }
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        // 源压缩包 (可能在别的授权目录) 与解出书籍的落地目录 ("书籍保存位置") 都要持租约;
        // 解包读的是协调器给出的压缩包地址
        return withSaveDirLease {
            IosSecurityScopedStorage.withAccess(
                delegate.resolveLocalFile(archiveFileUri).path, DirUsage.BookTree,
            ) { scopedPath ->
                delegate.importFromArchive(scopedPath, saveFileName, filter)
            }
        }
    }

    override fun importLocalFile(uriStr: String, parent: kotlinx.coroutines.Job?): Book =
        withSaveDirLease(parent) {
            IosSecurityScopedStorage.withAccessCaller(
                delegate.resolveLocalFile(uriStr).path, DirUsage.BookTree, caller = parent,
            ) { scopedPath -> delegate.importLocalFile(scopedPath, parent) }
        }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        parent: kotlinx.coroutines.Job?,
        filter: ((String) -> Boolean)?,
    ): List<Book> = withSaveDirLease(parent) {
        IosSecurityScopedStorage.withAccessCaller(
            delegate.resolveLocalFile(archiveFileUri).path, DirUsage.BookTree, caller = parent,
        ) { scopedPath -> delegate.importFromArchive(scopedPath, saveFileName, parent, filter) }
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
        IosSecurityScopedStorage.withAccess(
            path, DirUsage.BookTree, write = true,
            writeOptions = NSFileCoordinatorWritingForDeleting,
        ) { scopedPath ->
            // 删除也用协调器给出的实际地址: 源文件可能已被协调到替代位置
            FileBookOperationPath.withPath(scopedPath) {
                delegate.deleteBook(book, deleteOriginal)
            }
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
        val lease = IosSecurityScopedStorage.acquireLease(path, DirUsage.BookTree)
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
     * 存在性判定在授权租约内用真实 metadata; 写入按“已存在→协调目标文件 forReplacing,
     * 新建→协调父目录一般写入”选择对象与选项, 写入与返回引用都用协调后的实际地址。
     * 沙盒内路径 (导入副本) 无需 scope, 租约为空操作。
     *
     * 覆写所有能直接或间接触发写入的入口 ([importLocalFile] / [importFromArchive] /
     * `saveBookFile` 两个重载 / [saveBookFileFromWebDav]):
     * `by delegate` 只转发未被覆写的方法, delegate 内部再调 `saveBookFile` 不会回到本类,
     * 故每个能触达落盘的入口都要单独包一层。
     *
     * `saveBookFileFromPath` (web 上传落盘) 不覆写: iOS 没有网页服务, 入口不可达; 该端保持
     * [FileBookAccessor] 的 default 实现 (抛 UnsupportedOperationException), 不产生无授权的写入点。
     */
    override fun saveBookFile(
        inputStream: InputStream,
        fileName: String,
        parent: Job?,
    ): String {
        // 父门与关闭都放进同一个保护范围: 已创建输入时也必须按保存入口的原契约关闭它
        val bytes = try {
            parent?.ensureActive()
            inputStream.readFully(parent)
        } finally {
            inputStream.close()
        }
        // 进入正式提交 (协调替换) 之前核真实父资格
        parent?.ensureActive()
        return "file://" + writeSaveFile(fileName, bytes, parent)
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        // 合法 WebDav 入口: 复用流式保存链, 不能被 AnalyzeUrlCore 吞掉
        if (str.startsWith(BookType.webDavTag)) {
            return saveBookFileFromWebDav(
                WebDav.fromPath(str.substring(BookType.webDavTag.length)), fileName
            )
        }
        // 网络准备在协调之外完成, 拿到整份字节后才进入协调写入。
        // 本请求 Job 是父门: 取字节后、落盘前核资格, 已撤销不得动正式件
        val parent = currentCoroutineContext()[Job]
        val bytes = AnalyzeUrlCore(
            str, source = source, callTimeout = 0,
            coroutineContext = currentCoroutineContext(),
        ).getByteArrayAwait()
        parent?.ensureActive()
        return "file://" + writeSaveFile(fileName, bytes, parent)
    }

    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        val dir = requireBookSaveDir()
        val target = "$dir/$fileName"
        val tempPath = atomicWriteTempPath(target)
        // 本请求 Job 是父门: 下载前与最终协调落位前各核一次, 已撤销不得动正式件
        val parent = currentCoroutineContext()[Job]
        // 下载/最终协调落位/清理都归本次准备件所有权; 下载成功后切 dispatcher 被取消也不能漏清
        return try {
            // 下载持授权租约但不持协调 (不跨协调等网络), 完成后才协调落位
            val lease = IosSecurityScopedStorage.acquireLease(dir, DirUsage.BookTree)
            try {
                webDav.downloadTo(tempPath, true)
            } finally {
                lease.close()
            }
            parent?.ensureActive()
            // 最终协调落位绑定真实父 (caller): 等协调期间本请求被取消, 协调器对尚未起跑的
            // 写块 cancel() 不执行; 真起跑后的写触点再核一次, 已撤销不得继续动正式件
            val landed = if (saveTargetExists(dir, fileName)) {
                IosSecurityScopedStorage.withAccessCaller(
                    target, DirUsage.BookTree, write = true,
                    writeOptions = NSFileCoordinatorWritingForReplacing,
                    caller = parent,
                ) { coordinated ->
                    parent?.ensureActive()
                    BackupFileOps.copyFile(tempPath, coordinated, parent)
                    coordinated
                }
            } else {
                IosSecurityScopedStorage.withAccessCaller(
                    dir, DirUsage.BookTree, write = true, caller = parent,
                ) { coordinatedDir ->
                    val path = "$coordinatedDir/$fileName"
                    parent?.ensureActive()
                    BackupFileOps.copyFile(tempPath, path, parent)
                    path
                }
            }
            "file://$landed"
        } finally {
            // 临时件在授权目录内: 清理也持同一目录租约, 且不吞删除失败
            val cleanupLease = IosSecurityScopedStorage.acquireLease(dir, DirUsage.BookTree)
            try {
                if (!File(tempPath).delete()) {
                    AppLog.put("临时件清理失败: $tempPath")
                }
            } finally {
                cleanupLease.close()
            }
        }
    }

    /**
     * 按“已存在替换/新建目录写”协调并落盘, 返回实际落点。
     *
     * 协调绑定真实父 ([IosSecurityScopedStorage.withAccessCaller] 的 caller): 等协调期间本请求
     * 被取消, 协调器对尚未起跑的写块 cancel() 不执行; 真起跑后的写触点再核一次父,
     * 已撤销不得继续动正式件 ([writeBytesAtomically] 内部替换前还会再核同一父)。
     */
    private fun writeSaveFile(fileName: String, bytes: ByteArray, parent: Job?): String {
        val dir = requireBookSaveDir()
        return if (saveTargetExists(dir, fileName)) {
            IosSecurityScopedStorage.withAccessCaller(
                "$dir/$fileName", DirUsage.BookTree, write = true,
                writeOptions = NSFileCoordinatorWritingForReplacing,
                caller = parent,
            ) { coordinated ->
                parent?.ensureActive()
                if (!writeBytesAtomically(coordinated, bytes, parent)) {
                    throw NoStackTraceException("写入书籍文件失败: $coordinated")
                }
                coordinated
            }
        } else {
            IosSecurityScopedStorage.withAccessCaller(
                dir, DirUsage.BookTree, write = true, caller = parent,
            ) { coordinatedDir ->
                val path = "$coordinatedDir/$fileName"
                parent?.ensureActive()
                if (!writeBytesAtomically(path, bytes, parent)) {
                    throw NoStackTraceException("写入书籍文件失败: $path")
                }
                path
            }
        }
    }

    /** 在真实授权范围内判定目标是否存在 (容器外不可达不能当不存在)。 */
    private fun saveTargetExists(dir: String, fileName: String): Boolean {
        val lease = IosSecurityScopedStorage.acquireLease(dir, DirUsage.BookTree)
        return try {
            // 真实 metadata: null 才是确定不存在; 现存非普通文件按真实失败上报, 不当缺席
            val metadata = FileSystem.SYSTEM.metadataOrNull("$dir/$fileName".toPath())
                ?: return false
            if (!metadata.isRegularFile) {
                throw NoStackTraceException("目标已存在但不是普通文件: $dir/$fileName")
            }
            true
        } finally {
            lease.close()
        }
    }

    private fun <T> leaseAround(path: String, write: Boolean = false, block: () -> T): T =
        IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree, write) { block() }

    /**
     * 在"有效书籍保存位置"的授权内执行 [block] (未设置保存位置时不持租约: 写入方报未设置)。
     *
     * 解出书籍的落地目录与源文件目录可能不同 (源文件在别的授权目录)。
     */
    private fun <T> withSaveDirLease(
        parent: kotlinx.coroutines.Job? = null,
        block: () -> T,
    ): T {
        val dir = effectiveBookSaveDir() ?: return block()
        return IosSecurityScopedStorage.withAccessCaller(
            dir, DirUsage.BookTree, caller = parent,
        ) { block() }
    }

    /**
     * 本地书文件路径 (非本地书返回 null, 走无授权路径)。
     *
     * 与 delegate 打开文件时用的是同一份解析结果 (引用失效时按文件名在书籍保存位置/导入目录找回),
     * 租约/授权挂在本次读取真正会打开的那个路径上。
     */
    private fun localPath(book: Book): String? {
        if (!book.bookUrl.startsWith("file:")) return null
        return delegate.localFilePathOf(book)
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

    override fun upBookInfo(book: Book) = withLease(book) { delegate.upBookInfo(it) }

    override fun getChapterList(book: Book): ArrayList<BookChapter> =
        withLease(book) { delegate.getChapterList(it) }

    override fun getContent(book: Book, chapter: BookChapter): String? =
        withLease(book) { delegate.getContent(it, chapter) }

    override fun getImage(book: Book, href: String): InputStream? {
        // 返回的流由调用方消费: 租约随流 close 释放 (与 getBookInputStream 同契约)
        val lease = IosSecurityScopedStorage.acquireLease(path, DirUsage.BookTree)
        val stream = try {
            IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree) { scopedPath ->
                FileBookOperationPath.withPath(scopedPath) {
                    delegate.getImage(book, href)
                }
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

    private fun <T> withLease(book: Book, block: (Book) -> T): T =
        IosSecurityScopedStorage.withAccess(path, DirUsage.BookTree) { scopedPath ->
            FileBookOperationPath.withPath(scopedPath) { block(book) }
        }
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
