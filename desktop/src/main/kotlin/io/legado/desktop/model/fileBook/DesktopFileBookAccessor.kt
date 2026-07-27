package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.AppDbProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookAccessor
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipWrapper
import io.legado.app.model.fileBook.TextFile
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * 桌面端 [FileBookAccessor] 最小化实现。
 *
 * # 背景
 * [EpubFile] 构造时调用 `upBookCover` → `FileBook.getCoverPath` → [FileBookProviders.get],
 * 若 [FileBookProviders] 未注册会抛 IllegalStateException (被 EpubFile.upBookCover 的
 * try-catch 捕获, 封面加载失败但不崩溃)。为了让 desktop 端 EpubFile 能正常加载封面,
 * 需注册本最小化实现。
 *
 * # 已实现方法
 * - [getCoverPath]: `{desktopAppRootDir}/covers/{md516(bookUrl)}.jpg` (对齐 app 端路径结构)
 * - [getHandler]: 依据 [Book.isEpub] 分派到 [EpubFile] / [TextFile]
 * - [getBookInputStream]: [java.io.FileInputStream] 打开本地文件
 * - [getLastModified]: [File.lastModified]
 * - [getUrlSuffix]: 取文件名后缀
 * - [analyzeNameAuthor]: 简单解析文件名 (与 app 端行为一致)
 *
 * # 未实现方法 (抛 UnsupportedOperationException)
 * - [importFromArchive] / [importLocalFile] / [deleteBook] / [saveBookFile] /
 *   [downloadRemoteBook] / [mergeBook] / [importRemoteBook]: 依赖 Android 专属组件
 *   (ArchiveUtils / DocumentFile / RemoteBook 等), 待后续子代理补全
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor]。
 */
class DesktopFileBookAccessor : FileBookAccessor {

    /** 封面缓存目录: `{desktopAppRootDir}/covers` (对齐 app 端 `externalFiles/covers`)。 */
    private val coversDir: String = Paths.get(desktopAppRootDir(), "covers").toString()

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return Paths.get(coversDir, "${MD5Utils.md5Encode16(bookUrl)}.jpg").toString()
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 依据 originName 后缀分派 (与 app 端 BookExtensions.getHandler 一致)
        return when {
            book.isEpub -> EpubFile
            else -> TextFile
        }
    }

    override fun getBookInputStream(book: Book): InputStream {
        // desktop 端 bookUrl 为 file:// 或绝对路径, 解析为 File 后打开 FileInputStream
        // InputStream 是 java.io.InputStream 的 typealias (jvmAndAndroidMain), 无需包装
        val file = resolveLocalFile(book.bookUrl)
        return file.inputStream()
    }

    override fun getLastModified(book: Book): Result<Long> {
        return runCatching {
            resolveLocalFile(book.bookUrl).lastModified()
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        // desktop 无 libarchive, 仅支持 zip/cbz (JDK ZipInputStream); rar/7z 抛异常
        val archiveFile = resolveLocalFile(archiveFileUri)
        if (!archiveFile.name.endsWith(".zip", true) && !archiveFile.name.endsWith(".cbz", true)) {
            throw UnsupportedOperationException("desktop 仅支持 zip/cbz 压缩包导入, 不支持 ${archiveFile.name}")
        }
        val books = mutableListOf<Book>()
        ZipInputStream(archiveFile.inputStream()).use { zis ->
            val entries = generateSequence(zis::getNextEntry)
                .filter { !it.isDirectory }
                .filter { filter == null || filter!!(it.name) }
                .toList()
            for (entry in entries) {
                val bytes = zis.readBytes()
                val entryName = saveFileName ?: entry.name.substringAfterLast('/')
                if (!FileBook.isBookFile(entryName) && !AppPattern.archiveFileRegex.matches(entryName)) continue
                val savedUri = saveBookFile(ByteArrayInputStream(bytes), entryName)
                val imported = importLocalFile(savedUri).apply {
                    origin = "${BookType.localTag}::${archiveFile.name}"
                    addType(BookType.archive)
                    runBlocking { AppDbProviders.get().bookDao.update(this@apply) }
                }
                books.add(imported)
            }
        }
        if (books.isEmpty()) {
            throw UnsupportedOperationException("压缩包内未找到支持的书籍文件")
        }
        return books
    }

    override fun importLocalFile(uriStr: String): Book {
        val file = resolveLocalFile(uriStr)
        val fileName = file.name
        if (file.length() == 0L) throw UnsupportedOperationException("Unexpected empty File")
        val (name, author) = analyzeNameAuthor(fileName)
        var type = BookType.text or BookType.local
        when {
            fileName.endsWith(".cbz", true) -> type = BookType.image or BookType.local
            AppPattern.archiveFileRegex.matches(fileName) -> {
                // zip/cbz 压缩包: 检查内含书籍文件, 有则解压导入第一个
                if (fileName.endsWith(".zip", true) || fileName.endsWith(".cbz", true)) {
                    ZipInputStream(file.inputStream()).use { zis ->
                        val bookEntry = generateSequence(zis::getNextEntry)
                            .firstOrNull { !it.isDirectory && FileBook.isBookFile(it.name) }
                        if (bookEntry != null) {
                            val bytes = zis.readBytes()
                            val savedUri = saveBookFile(ByteArrayInputStream(bytes), bookEntry.name.substringAfterLast('/'))
                            return importLocalFile(savedUri)
                        }
                    }
                }
                throw UnsupportedOperationException("desktop 仅支持 zip/cbz 压缩包, 不支持 $fileName")
            }
        }
        return importBook(
            Book(
                bookUrl = file.toURI().toString(),
                name = name,
                author = author,
                originName = fileName,
                latestChapterTime = file.lastModified(),
                order = runBlocking { AppDbProviders.get().bookDao.minOrder() } - 1,
                origin = file.toURI().toString(),
                type = type
            )
        )
    }

    /** 统一导入逻辑 (对齐 app 端 FileBookAccessorImpl.importBook, runBlocking 适配 suspend DAO)。 */
    private fun importBook(book: Book): Book {
        val bookDao = AppDbProviders.get().bookDao
        val dbBook = runBlocking { bookDao.getBook(book.bookUrl) }
        return if (dbBook == null) {
            book.apply {
                FileBook.upBookInfo(this)
                runBlocking { bookDao.insert(this@apply) }
            }
        } else {
            deleteBook(dbBook, false)
            dbBook.apply {
                this.name = book.name
                this.author = book.author
                this.originName = book.originName
                this.origin = book.origin
                this.latestChapterTime = 0
                FileBook.upBookInfo(this)
                runBlocking { bookDao.update(this@apply) }
            }
        }
    }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // desktop 端 BookHelpAccessor 无 clearCache (接口未暴露), 仅删封面 + 原文件
        // app 端 BookHelp.clearCache(book) 在 desktop 走 BookHelpProviders 但接口无该方法, 跳过
        runCatching {
            book.coverUrl?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            if (deleteOriginal) {
                resolveLocalFile(book.bookUrl).delete()
            }
        }
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        // app 端走 SAF + defaultBookTreeUri; desktop 走 WebDav/AnalyzeUrl 取流 + saveBookFile(inputStream)
        val inputStream = if (!str.startsWith(BookType.webDavTag)) {
            AnalyzeUrlCore(
                str, source = source, callTimeout = 0,
                coroutineContext = currentCoroutineContext()
            ).getInputStreamAwait()
        } else {
            WebDav.fromPath(str.substring(BookType.webDavTag.length)).downloadInputStream()
        }
        return saveBookFile(inputStream, fileName)
    }

    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        // desktop 无 SAF, 用 {desktopAppRootDir}/books + java.io.File (app 端走 defaultBookTreeUri + DocumentFile)
        val booksDir = Paths.get(desktopAppRootDir(), "books").toFile().apply { mkdirs() }
        val file = File(booksDir, fileName)
        inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.toURI().toString()
    }

    override fun downloadRemoteBook(book: Book): Boolean {
        // app 端走 SAF + FileDoc; desktop 走 saveBookFile(str) + importFromArchive + bookDao.update
        val webDavUrl = book.getRemoteUrl()
            ?: throw UnsupportedOperationException("Book file is not webDav File")
        val fileName = if (book.isArchive) book.archiveName else book.originName
        val fileUriStr = runBlocking { saveBookFile(webDavUrl, fileName, null) }
        if (book.isArchive) {
            val newBook = importFromArchive(fileUriStr, book.originName) { name ->
                name.contains(book.originName)
            }.firstOrNull() ?: throw UnsupportedOperationException("Archive contains no matching book file")
            book.origin = newBook.origin
            book.bookUrl = newBook.bookUrl
        } else {
            book.bookUrl = fileUriStr
        }
        runBlocking { AppDbProviders.get().bookDao.update(book) }
        return true
    }

    override fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        // 与 app 端一致: onLineBook 为 null 时返回 localBook; 字段合并 + bookDao.update 替代 app 端 Book.save()
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        if (!onLineBook.intro.isNullOrBlank()) {
            localBook.intro = onLineBook.intro
        }
        // app 端 Book.save() 用 runBlocking + has/update/insert; desktop 同模式 (mergeBook 非 suspend)
        val bookDao = AppDbProviders.get().bookDao
        runBlocking {
            if (bookDao.has(localBook.bookUrl)) bookDao.update(localBook) else bookDao.insert(localBook)
        }
        return localBook
    }

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 简单解析: 去后缀, 按 " - " 或 " - " 分割书名与作者
        val name = fileName.substringBeforeLast(".")
        val parts = name.split(" - ", " - ", "——", "_")
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            name to ""
        }
    }

    override suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean
    ): Book {
        val bookDao = AppDbProviders.get().bookDao
        val origin = BookType.webDavTag + CustomUrl(path).putAttribute("serverID", serverID).toString()

        suspend fun importAsImage(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) saveBookFile(origin, name, null) else origin
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
                ).apply { variable = "cbz:${0L},${0L},${size}" }
            )
        }

        suspend fun importAsEpub(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) saveBookFile(origin, name, null) else origin
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
                ).apply { variable = "epub:${0L},${0L},${size},0" }
            )
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
                val hasBookFile = entries.any { !it.isDirectory && FileBook.isBookFile(it.name) }
                val hasImageFile = entries.any { !it.isDirectory && AppPattern.imgFileRegex.matches(it.name) }
                try {
                    when {
                        hasImageFile -> importAsImage()
                        hasBookFile -> {
                            val entry = entries.first { !it.isDirectory && FileBook.isBookFile(it.name) }
                            val uriStr = saveBookFile(
                                remoteZip.getInputStream(entry) ?: throw UnsupportedOperationException("获取流失败"),
                                entry.name
                            )
                            importLocalFile(uriStr).apply {
                                this.origin = origin
                                addType(BookType.archive)
                                runBlocking { bookDao.update(this@apply) }
                            }
                        }
                        else -> throw UnsupportedOperationException("不支持的压缩包格式")
                    }
                } finally {
                    remoteZip.close()
                }
            }
            else -> importLocalFile(saveBookFile(origin, name, null)).apply {
                this.origin = origin
                runBlocking { bookDao.update(this@apply) }
            }
        }
    }

    override fun getUrlSuffix(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx >= 0) name.substring(idx) else ""
    }

    /** 解析 bookUrl 为 [File] (与 LocalEpubResource.jvm.kt 的 resolveLocalFile 逻辑一致)。 */
    private fun resolveLocalFile(bookUrl: String): File {
        return when {
            bookUrl.startsWith("file:") -> {
                val path = java.net.URI(bookUrl).path ?: bookUrl.removePrefix("file:")
                File(path)
            }
            else -> File(bookUrl)
        }
    }
}

/**
 * 桌面端启动早期注册 [FileBookProviders]。
 *
 * 调用时机: desktop `main()` 中, 在任何 EpubFile / FileBook 调用之前。
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor] 的注册方式。
 */
fun registerDesktopFileBookAccessor() {
    FileBookProviders.register(DesktopFileBookAccessor())
}
