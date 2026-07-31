package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.BookNameAuthorAnalyzer
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookAccessor
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.fileBook.RangedSource
import io.legado.app.model.fileBook.RemoteZipWrapper
import io.legado.app.model.fileBook.TextFile
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.UrlUtil
import io.legado.desktop.help.archive.DesktopArchiveCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Paths
import java.util.Locale

/**
 * 桌面端 [FileBookAccessor] 最小化实现。
 *
 * # 背景
 * [EpubFile] 构造时调用 `upBookCover` → `FileBook.getCoverPath` → [FileBookProviders.get],
 * 若 [FileBookProviders] 未注册会抛 IllegalStateException (被 EpubFile.upBookCover 的
 * try-catch 捕获, 封面加载失败但不崩溃)。为了让 desktop 端 EpubFile 能正常加载封面,
 * 需注册本最小化实现。
 *
 * # 与 app 端行为差异
 * - 压缩包: 无 native libarchive, 走 [DesktopArchiveCodec] (commons-compress + junrar);
 *   7z 的 BCJ2 编码不支持 (commons-compress 限制), 其余格式对齐
 * - PDF: [DesktopPdfFile] (PDFBox) 替代 app 端 `PdfFile` (android PdfRenderer)
 * - 文件保存: 无 SAF, 落 `{desktopAppRootDir}/books`
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor]。
 */
class DesktopFileBookAccessor : FileBookAccessor {

    /** 解压临时目录名 (对齐 app 端 `ArchiveUtils.TEMP_FOLDER_NAME`)。 */
    private val archiveTempFolderName = "ArchiveTemp"

    /** 封面缓存目录: `{desktopAppRootDir}/covers` (对齐 app 端 `externalFiles/covers`)。 */
    private val coversDir: String = Paths.get(desktopAppRootDir(), "covers").toString()

    /** 书籍保存目录: `{desktopAppRootDir}/books` (对齐 app 端 `AppConfig.defaultBookTreeUri`)。 */
    private val booksDir: File
        get() = Paths.get(desktopAppRootDir(), "books").toFile().apply { mkdirs() }

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return Paths.get(coversDir, "${MD5Utils.md5Encode16(bookUrl)}.jpg").toString()
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 分派逻辑对齐 app 端 FileBookAccessorImpl.getHandler (PdfFile → PDFBox 版 DesktopPdfFile)
        val originName = book.originName.lowercase(Locale.getDefault())
        return when {
            book.isPdf -> DesktopPdfFile
            book.isEpub -> EpubFile
            book.isLocal && (originName.endsWith(".cbz") || originName.endsWith(".zip") && book.isImage) -> CbzFile
            else -> TextFile
        }
    }

    override fun getBookInputStream(book: Book): InputStream {
        // 对齐 app 端 FileBookAccessorImpl.getBookInputStream: 文件失效时回退
        // "重新解压压缩包" → "下载远程文件", 全失败才抛 FileNotFoundException
        val inputStream = openLocalFile(book) ?: run {
            LocalBookLocators.get().removeLocalPathCache(book)
            val archiveFile = findArchiveFile(book)
            val webDavUrl = book.getRemoteUrl()
            if (archiveFile != null) {
                importFromArchive(archiveFile.toURI().toString(), book.originName) {
                    it.contains(book.originName)
                }.firstOrNull()?.let { getBookInputStream(it) }
            } else if (webDavUrl != null && downloadRemoteBook(book)) {
                getBookInputStream(book)
            } else {
                null
            }
        }
        if (inputStream != null) return inputStream
        LocalBookLocators.get().removeLocalPathCache(book)
        throw FileNotFoundException("${book.bookUrl} 文件不存在")
    }

    /**
     * 打开 bookUrl 指向的本地文件, 不存在时在 books 目录按 originName 兜底查找。
     *
     * 兜底对齐 app 端 `Book.getLocalUri()` 的"书籍保存目录搜索"(设备间路径不一致时用),
     * 命中后写入 [LocalBookLocators] 路径缓存, 不改 bookUrl (bookUrl 是 Book 主键)。
     */
    private fun openLocalFile(book: Book): InputStream? {
        val locator = LocalBookLocators.get()
        val path = runCatching { locator.getLocalPath(book) }.getOrNull()
        val file = if (path != null) File(path) else resolveLocalFile(book.bookUrl)
        if (file.isFile) return file.inputStream()
        val fallback = File(booksDir, book.originName)
        if (fallback.isFile) {
            locator.cacheLocalPath(book, fallback.absolutePath)
            return fallback.inputStream()
        }
        return null
    }

    /** 压缩包子书: 在 books 目录按 archiveName 找回原压缩包 (对齐 app 端 `Book.getArchiveUri`)。 */
    private fun findArchiveFile(book: Book): File? {
        if (!book.isArchive) return null
        val name = runCatching { book.archiveName }.getOrNull() ?: return null
        return File(booksDir, name).takeIf { it.isFile }
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
        val archiveFile = resolveLocalFile(archiveFileUri)
        // 对齐 app 端 ArchiveUtils.deCompress: 按 filter 过滤条目, 解到目标目录, 返回文件列表
        val files = deCompress(archiveFileUri, archiveFile, filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
        }
        return files.map { file ->
            saveBookFile(file.inputStream(), saveFileName ?: file.name).let { uriStr ->
                importLocalFile(uriStr).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFile.name}"
                    addType(BookType.archive)
                    runBlocking { AppDbProviders.get().bookDao.update(this@apply) }
                }
            }
        }
    }

    /**
     * 解压到 `{desktopAppCacheDir}/ArchiveTemp/{md5_16(uri)}` 并返回解出的文件。
     *
     * 对齐 app 端 `ArchiveUtils.deCompress` + `LibArchiveUtils.unArchive` 语义
     * (md5(uri) 命名工作目录 / 路径穿越校验 / filter 过滤条目), 底层换
     * [DesktopArchiveCodec] (commons-compress + junrar) 替代 native libarchive。
     */
    private fun deCompress(
        archiveUri: String,
        archiveFile: File,
        filter: ((String) -> Boolean)?
    ): List<File> {
        DesktopArchiveCodec.checkArchive(archiveFile.name)
        val destDir = Paths.get(
            desktopAppCacheDir(), archiveTempFolderName, MD5Utils.md5Encode16(archiveUri)
        ).toFile().apply { mkdirs() }
        return DesktopArchiveCodec.unArchive(archiveFile, destDir, filter)
    }

    override fun importLocalFile(uriStr: String): Book {
        val file = resolveLocalFile(uriStr)
        val fileName = file.name
        if (file.length() == 0L) throw EmptyFileException("Unexpected empty File")
        val (name, author) = analyzeNameAuthor(fileName)
        var type = BookType.text or BookType.local
        when {
            fileName.endsWith(".cbz", true) -> type = BookType.image or BookType.local
            AppPattern.archiveFileRegex.matches(fileName) -> {
                // 对齐 app 端: 压缩包内含书籍文件则解压导入, 否则按普通文件继续
                val hasBookFile = getArchiveFilesName(file).any { FileBook.isBookFile(it) }
                if (hasBookFile) {
                    return importFromArchive(uriStr) { FileBook.isBookFile(it) }.firstOrNull()
                        ?: throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
                }
            }

            else -> {}
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

    /**
     * 遍历压缩包获取条目名 (读取失败返回空列表)。
     *
     * 注: app 端 `LibArchiveUtils.getFilesName` 在 filter 为 null 时恒返回空列表
     * (`filter != null && filter(name)`), 桌面端保持"无 filter 即全量"的既有行为,
     * 否则 [importLocalFile] 的压缩包内选书会失效。
     */
    private fun getArchiveFilesName(archiveFile: File): List<String> {
        DesktopArchiveCodec.checkArchive(archiveFile.name)
        return runCatching {
            DesktopArchiveCodec.getFilesName(archiveFile, null)
        }.getOrElse { emptyList() }
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
        // 对齐 app 端 FileBook.deleteBook: 先清章节缓存, 再删封面 / 原文件 (无 SAF, 统一走 File)
        runCatching {
            BookStorageProviders.get().clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtilsBase.delete(book.coverUrl!!)
            }
            if (deleteOriginal) {
                FileUtilsBase.delete(resolveLocalFile(book.bookUrl).absolutePath)
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
        val file = File(booksDir, fileName)
        inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.toURI().toString()
    }

    /** web 上传临时文件落盘 (对齐 app 端: 委托 inputStream 重载)。 */
    override fun saveBookFileFromPath(filePath: String, fileName: String): String =
        saveBookFile(File(filePath).inputStream(), fileName)

    override fun downloadRemoteBook(book: Book): Boolean {
        // app 端走 SAF + FileDoc; desktop 走 saveBookFile(str) + importFromArchive + bookDao.update
        val webDavUrl = book.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        val fileName = if (book.isArchive) book.archiveName else book.originName
        val fileUriStr = runBlocking { saveBookFile(webDavUrl, fileName, null) }
        if (book.isArchive) {
            val newBook = importFromArchive(fileUriStr, book.originName) { name ->
                name.contains(book.originName)
            }.firstOrNull() ?: throw NoStackTraceException("Archive contains no matching book file")
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
        // 纯逻辑走下沉的 BookNameAuthorAnalyzer; app 端 AppConfig.bookImportFileName 是
        // Android 扩展, 桌面端读同一 PreferKey (DesktopPreferenceProvider)
        val importFileNameJs = runCatching {
            PreferenceProviders.get().getString(PreferKey.bookImportFileName)
        }.getOrNull()
        return BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, importFileNameJs)
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
                                remoteZip.getInputStream(entry),
                                entry.name
                            )
                            importLocalFile(uriStr).apply {
                                this.origin = origin
                                addType(BookType.archive)
                                runBlocking { bookDao.update(this@apply) }
                            }
                        }
                        else -> throw NoStackTraceException("不支持的压缩包格式")
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

    /** 获取合法的文件后缀 (对齐 app 端: 复用 [UrlUtil.getSuffix], 含 fileSuffixRegex 校验 + "ext" 兜底)。 */
    override fun getUrlSuffix(name: String): String = UrlUtil.getSuffix(name)

    /** 解析 bookUrl 为 [File] (与 LocalEpubResource.jvm.kt 的 resolveLocalFile 逻辑一致)。 */
    private fun resolveLocalFile(bookUrl: String): File = resolveLocalBookFile(bookUrl)
}

/** 解析 bookUrl 为 [File] (file: URI 或裸路径), 供本包内 accessor / [DesktopPdfFile] 共用。 */
internal fun resolveLocalBookFile(bookUrl: String): File {
    return when {
        bookUrl.startsWith("file:") -> {
            val path = java.net.URI(bookUrl).path ?: bookUrl.removePrefix("file:")
            File(path)
        }
        else -> File(bookUrl)
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
