package io.legado.app.model.fileBook

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.FileBook.isBookFile

/**
 * 远程书籍导入编排 (四端共用的唯一一份):
 * [importRemoteBook] / [importBook] / [mergeBook] / [downloadRemoteBook]。
 *
 * 按文件名判型: `.cbz` → 图片书 / `.epub` → 文本书 / `.zip` → 解包探查 (含图 → 图片书,
 * 含书 → 取书文件按本地导入), 其余直接下载导入。30MB 阈值内的远端文件先下载落盘再导入,
 * 超出或明确不下载时用 webDav 引用动态加载。
 *
 * 平台差异仅剩文件读写原语 (保存目录 / 引用形式), 经 [FileBookProviders.get] 注入;
 * 数据库访问统一走 [AppDbProviders]。写盘一律经 accessor, 故 iOS/鸿蒙的授权装饰器仍在
 * 每个落盘入口拿到自己的授权时机, 无需各自再包一层导入编排。
 */
object FileBookRemoteImportShared {

    /**
     * 导入远程书籍 (对应 `FileBook.importRemoteBook`)。
     */
    suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean
    ): Book {
        val accessor = FileBookProviders.get()
        val bookDao = AppDbProviders.get().bookDao
        val origin = BookType.webDavTag + CustomUrl(path).putAttribute(
            "serverID", serverID
        ).toString()

        suspend fun importAsImage(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) {
                accessor.saveBookFile(origin, name)
            } else origin
            return importBook(
                Book(
                    bookUrl = bookUrl,
                    name = name.substringBeforeLast("."),
                    author = "",
                    originName = name,
                    latestChapterTime = lastModify,
                    order = bookDao.minOrder() - 1,
                    origin = origin,
                    type = BookType.image or BookType.local
                ).apply {
                    variable = "cbz:${0L},${0L},${size}"
                })
        }

        // 远程EPUB处理：大于30MB不下载，使用动态加载
        suspend fun importAsEpub(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) {
                accessor.saveBookFile(origin, name)
            } else origin
            return importBook(
                Book(
                    bookUrl = bookUrl,
                    name = name.substringBeforeLast("."),
                    author = "",
                    originName = name,
                    latestChapterTime = lastModify,
                    order = bookDao.minOrder() - 1,
                    origin = origin,
                    type = BookType.text or BookType.local
                ).apply {
                    variable = "epub:${0L},${0L},${size},0"
                })
        }

        return when {
            name.endsWith(".cbz", true) -> importAsImage()
            name.endsWith(".epub", true) -> importAsEpub()
            name.endsWith(".zip", true) -> {
                val remoteZip = RemoteZipWrapper(
                    RangedSource { offset, length, fileSize -> webDav.readRange(offset, length, fileSize) },
                    name, size
                )
                val entries = remoteZip.entries().toList()
                val hasBookFile = entries.any { !it.isDirectory && isBookFile(it.name) }
                val hasImageFile =
                    entries.any { !it.isDirectory && AppPattern.imgFileRegex.matches(it.name) }
                if (hasImageFile) {
                    importAsImage()
                } else if (hasBookFile) {
                    try {
                        val entry = remoteZip.entries().asSequence()
                            .first { !it.isDirectory && isBookFile(it.name) }
                        val uriStr = accessor.saveBookFile(remoteZip.getInputStream(entry), entry.name)
                        accessor.importLocalFile(uriStr).apply {
                            this.origin = origin
                            addType(BookType.archive)
                            save()
                        }
                    } finally {
                        remoteZip.close()
                    }
                } else throw NoStackTraceException("不支持的压缩包格式")
            }

            else -> accessor.importLocalFile(accessor.saveBookFile(origin, name)).apply {
                this.origin = origin
                save()
            }
        }
    }

    /**
     * 统一核心导入逻辑 (对应 `FileBook.importBook` private 方法)。
     *
     * 同名书已存在时删旧缓存重建: 复制基本字段并把最新章节时间归零以触发重新解析,
     * 图片书直接使用文件时间 (归零仅影响文本解析触发)。
     */
    suspend fun importBook(book: Book): Book {
        val accessor = FileBookProviders.get()
        val bookDao = AppDbProviders.get().bookDao
        val dbBook = bookDao.getBook(book.bookUrl)
        return if (dbBook == null) {
            book.apply {
                FileBook.upBookInfo(this)
                bookDao.insert(this@apply)
            }
        } else {
            accessor.deleteBook(dbBook, false)
            dbBook.apply {
                this.name = book.name
                this.author = book.author
                this.originName = book.originName
                this.origin = book.origin
                // 文本书籍更新重置时间以触发重新解析，图片书直接使用文件时间
                this.latestChapterTime = 0
                FileBook.upBookInfo(this)
                bookDao.update(this@apply)
            }
        }
    }

    /**
     * 文件类书源合并在线书籍信息 (对应 `FileBook.mergeBook`), 在线信息优先。
     */
    suspend fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        if (!onLineBook.intro.isNullOrBlank()) {
            localBook.intro = onLineBook.intro
        }
        localBook.save()
        return localBook
    }

    /**
     * 下载 book 对应的远程文件并更新 Book (对应 `FileBook.downloadRemoteBook`)。
     */
    suspend fun downloadRemoteBook(book: Book): Boolean {
        val accessor = FileBookProviders.get()
        val webDavUrl = book.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        val fileName = if (book.isArchive) book.archiveName else book.originName
        val fileUriStr = accessor.saveBookFile(webDavUrl, fileName)
        if (book.isArchive) {
            val newBook = accessor.importFromArchive(fileUriStr, book.originName) { name ->
                name.contains(book.originName)
            }.firstOrNull() ?: throw NoStackTraceException("Archive contains no matching book file")
            book.origin = newBook.origin
            book.bookUrl = newBook.bookUrl
        } else {
            book.bookUrl = accessor.normalizeBookRef(fileUriStr)
        }
        book.save()
        return true
    }
}
