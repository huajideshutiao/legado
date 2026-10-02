package io.legado.app.model.fileBook

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.utils.InputStream

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
        withActivation(delegate.resolveLocalFile(uriStr).path) {
            delegate.importLocalFile(uriStr)
        }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> =
        withActivation(delegate.resolveLocalFile(archiveFileUri).path) {
            delegate.importFromArchive(archiveFileUri, saveFileName, filter)
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

    private fun <T> withActivation(path: String, block: () -> T): T {
        OhosDirAuthorizations.ensureActivatedFor(path)
        return block()
    }

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
