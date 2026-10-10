package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.utils.InputStream

/**
 * [FileBookAccessor] 用途校验装饰器: 本地书的文件访问入口先经 [AndroidDirAuthorizations.ensureBookFileUsage]
 * (只有书籍用途选定的目录才允许作为书籍文件访问), 其余行为逐字委托 [FileBookAccessorImpl] ——
 * IO 实现只有一份, 本类不读写字节。
 *
 * 覆写范围: 读文件 ([getBookInputStream] / [getHandler] / [getLastModified])、删原文件 ([deleteBook])
 * 与导入源文件 ([importLocalFile] / [importFromArchive])。写书籍文件的落地目录就是"书籍保存位置",
 * 不参与本校验。
 */
class AndroidAuthorizedFileBookAccessor(
    private val delegate: FileBookAccessor = FileBookAccessorImpl,
) : FileBookAccessor by delegate {

    override fun getBookInputStream(book: Book): InputStream {
        AndroidDirAuthorizations.ensureBookFileUsage(book)
        return delegate.getBookInputStream(book)
    }

    override fun getLastModified(book: Book): Result<Long> = runCatching {
        AndroidDirAuthorizations.ensureBookFileUsage(book)
        delegate.getLastModified(book).getOrThrow()
    }

    override fun getHandler(book: Book): BaseFileBook {
        AndroidDirAuthorizations.ensureBookFileUsage(book)
        return delegate.getHandler(book)
    }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 只有删原文件要校验: 章节缓存与封面在应用私有区
        if (deleteOriginal) AndroidDirAuthorizations.ensureBookFileUsage(book)
        delegate.deleteBook(book, deleteOriginal)
    }

    override fun importLocalFile(uriStr: String): Book {
        AndroidDirAuthorizations.ensureBookFileUsage(uriStr)
        return delegate.importLocalFile(uriStr)
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        AndroidDirAuthorizations.ensureBookFileUsage(archiveFileUri)
        return delegate.importFromArchive(archiveFileUri, saveFileName, filter)
    }
}

/**
 * [ZipFileWrapperFactory] 用途校验装饰器: cbz/zip 容器按书籍文件引用直接开容器, 不经 [FileBookAccessor],
 * 故此处同样先校验用途再委托 [ZipFileWrapperFactoryImpl]。
 */
class AndroidAuthorizedZipFileWrapperFactory(
    private val delegate: ZipFileWrapperFactory = ZipFileWrapperFactoryImpl,
) : ZipFileWrapperFactory {

    override fun create(book: Book): ZipFileWrapperFactory.CreateResult? {
        AndroidDirAuthorizations.ensureBookFileUsage(book)
        return delegate.create(book)
    }
}

/**
 * 安卓宿主注册本地书访问器与 zip 容器工厂: 平台实现 ([FileBookAccessorImpl] /
 * [ZipFileWrapperFactoryImpl]) 外裹用途校验装饰器。
 *
 * 须在 [registerAndroidFileBookProviders] 与 [registerAndroidWebBookProviders] (登记平台实现)
 * 之后调用, 任何 FileBook 调用之前。
 */
fun registerAndroidAuthorizedFileBookAccessor() {
    FileBookProviders.register(AndroidAuthorizedFileBookAccessor())
    ZipFileWrapperFactoryProviders.register(AndroidAuthorizedZipFileWrapperFactory())
}
