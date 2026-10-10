package io.legado.app.model.fileBook

import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.requireBookSaveDir
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.inputStream

/**
 * 鸿蒙 [FileBookAccessor] 授权装饰器: 本地书的文件访问入口先经目录授权激活
 * ([OhosDirAuthorizations.ensureActivatedFor]), 其余行为逐字委托 [NativeFileBookAccessor]
 * —— IO 实现只有一份, 本类不读写字节。
 *
 * 授权是持久态 (activatePermission 后进程内一直有效), 各入口激活一次即可,
 * 解析器/输入流的后续读取无需再包 (与 iOS 的按调用配对租约不同)。
 * 沙盒内文件 ({filesDir}/books 导入副本) 不在授权集合内, 直接放行。
 */
class OhosAuthorizedFileBookAccessor(
    private val delegate: NativeFileBookAccessor = NativeFileBookAccessor(),
) : FileBookAccessor by delegate {

    override fun getBookInputStream(book: Book): InputStream =
        withActivation(book) { delegate.getBookInputStream(book) }

    override fun getLastModified(book: Book): Result<Long> = runCatching {
        withActivation(book) { delegate.getLastModified(book).getOrThrow() }
    }

    override fun getHandler(book: Book): BaseFileBook =
        withActivation(book) { delegate.getHandler(book) }

    override fun importLocalFile(uriStr: String): Book =
        // 源文件 (可能在别的授权目录) 与解出书籍的落地目录 ("书籍保存位置") 都要激活授权;
        // 落地目录只有压缩包分支会写 (由 delegate 内部调 saveBookFile), 未设置保存位置时
        // 本条不写盘, 故只在其已设置时激活 (写入方报未设置)
        withActivation(delegate.resolveLocalFile(uriStr).path) {
            withSaveDirActivation { delegate.importLocalFile(uriStr) }
        }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> =
        // 源压缩包 (可能在别的授权目录) 与解出书籍的落地目录 ("书籍保存位置") 都要激活授权
        withActivation(delegate.resolveLocalFile(archiveFileUri).path) {
            withSaveDirActivation {
                delegate.importFromArchive(archiveFileUri, saveFileName, filter)
            }
        }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 只有删原文件需要授权 (封面/章节缓存都在沙盒内)
        if (!deleteOriginal || !book.bookUrl.startsWith("file:")) {
            delegate.deleteBook(book, deleteOriginal)
            return
        }
        withActivation(delegate.resolveLocalFile(book.bookUrl).path) {
            delegate.deleteBook(book, deleteOriginal)
        }
    }

    /**
     * 保存新书到"有效书籍保存位置" (用户在"书籍保存位置"选定):
     * 写入前确保目录授权已激活 ([OhosDirAuthorizations.ensureActivatedFor]; 授权是持久态, 激活一次即可)。
     *
     * 覆写所有能直接或间接触发写入的入口 ([importLocalFile] / [importFromArchive] /
     * `saveBookFile` 两个重载 / [saveBookFileFromWebDav] / [saveBookFileFromPath] /
     * [downloadRemoteBook] / [importRemoteBook])
     * 的原因: `by delegate` 只转发未被覆写的方法, delegate 内部再调 `saveBookFile` 不会回到本类,
     * 故每个能触达落盘的入口都要单独包一层。
     */
    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.saveBookFile(inputStream, fileName)
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.saveBookFile(str, fileName, source)
    }

    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.saveBookFileFromWebDav(webDav, fileName)
    }

    /**
     * 保存 web 上传的临时文件为书籍 (用户在"书籍保存位置"选定)。
     *
     * 写入前先激活落地目录已记住的授权 (重启后授权需重新 activate); 未设置保存位置时
     * [requireBookSaveDir] 报"没有设置书籍保存位置", 授权失效时报明确异常 —— 两者都由调用方
     * 写进网页响应体。不弹任何界面: 上传请求来自浏览器 (可能不在同一台设备上), 拿不到目录选择器。
     */
    override fun saveBookFileFromPath(filePath: String, fileName: String): String {
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.saveBookFile(File(filePath).inputStream(), fileName)
    }

    override fun downloadRemoteBook(book: Book): Boolean {
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.downloadRemoteBook(book)
    }

    /**
     * 导入远程书 (WebDav 浏览导入): delegate 内部经 `saveBookFile` 把书落到"有效书籍保存位置",
     * 该调用不回到本类, 故写入前先激活落地目录授权 (内部还可能再走 importFromArchive/importLocalFile,
     * 它们的落地目录与本授权同一份)。
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
        OhosDirAuthorizations.ensureActivatedFor(requireBookSaveDir())
        return delegate.importRemoteBook(webDav, serverID, name, path, size, lastModify, downloadFile)
    }

    private fun <T> withActivation(path: String, block: () -> T): T {
        OhosDirAuthorizations.ensureActivatedFor(path)
        return block()
    }

    /** 保存位置已设置时先激活其授权 (未设置 = 本条不写盘, 无需授权)。 */
    private fun <T> withSaveDirActivation(block: () -> T): T =
        effectiveBookSaveDir()?.let { withActivation(it, block) } ?: block()

    private fun <T> withActivation(book: Book, block: () -> T): T {
        val path = localPath(book) ?: return block()
        return withActivation(path, block)
    }

    /** 本地书文件路径 (非本地书返回 null, 走无授权路径)。 */
    private fun localPath(book: Book): String? {
        if (!book.bookUrl.startsWith("file:")) return null
        return LocalBookLocators.get().getLocalPath(book)
            ?: book.bookUrl.removePrefix("file://")
    }
}

/**
 * 鸿蒙 [ZipFileWrapperFactory]: 远程分支复用 nativeMain [NativeZipFileWrapperFactory];
 * 本地路径分支先激活授权再委托 —— 授权是持久态, wrapper 后续区间读无需再挂点,
 * wrapper 本体不重复实现。
 */
class OhosZipFileWrapperFactory : ZipFileWrapperFactory {

    private val remote = NativeZipFileWrapperFactory()

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        val url = book.bookUrl
        if (!url.startsWith("http") && !url.startsWith(BookType.webDavTag)) {
            val path = LocalBookLocators.get().getLocalPath(book)
                ?: url.removePrefix("file://")
            OhosDirAuthorizations.ensureActivatedFor(path)
        }
        return remote.create(book)
    }
}

/**
 * 鸿蒙宿主注册本地书访问器: [NativeFileBookAccessor] 外裹目录授权装饰器, cbz 容器工厂
 * 在本地路径分支激活授权后复用 nativeMain 实现。须在 BookStorage/LocalBookLocator/
 * BitmapProviders 之后, 任何 FileBook 调用之前。
 */
fun registerOhosFileBookAccessor() {
    FileBookProviders.register(OhosAuthorizedFileBookAccessor())
    ZipFileWrapperFactoryProviders.register(OhosZipFileWrapperFactory())
}
