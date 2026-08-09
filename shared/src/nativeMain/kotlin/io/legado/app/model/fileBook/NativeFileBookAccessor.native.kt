package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.formatNative
import io.legado.app.utils.toInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import io.legado.app.utils.File

/**
 * native 端 (iOS/鸿蒙) [FileBookAccessor] 实现, 对照 desktop DesktopFileBookAccessor。
 *
 * # 与 desktop 端差异
 * - EPUB 走 nativeMain [EpubFile] (纯 Kotlin EpubParser); TXT 走 nativeMain [TextFile]
 *   (commonMain TextFileCore); PDF/CBZ 解析器未下沉, 走 [UnsupportedFileBook] 抛明确异常, 不静默空返回
 * - zip 解析用 commonMain [RemoteZipCore] + [ZipEntryReader] (无 java.util.zip)
 * - bookUrl 用 `file://` + 绝对路径 (与 NativeLocalBookLocator.parseLocalPath 对齐)
 * - saveBookFile(str) 网络路径用 AnalyzeUrlCore.getByteArrayAwait (native 响应体本就整块进内存,
 *   走 getInputStreamAwait 无额外收益)
 * - importRemoteBook 的 zip 分支用 WebDav.readRange + RemoteZipCore (与 desktop RemoteZipWrapper 语义一致)
 */
class NativeFileBookAccessor : FileBookAccessor {

    /** 封面缓存目录: `{filesDir}/covers` (对齐 app 端 `externalFiles/covers`)。 */
    private val coversDir: String get() = AppFilesDirs.get().filesDir + "/covers"

    /** 本地书保存目录: `{filesDir}/books` (对齐 desktop `{root}/books`)。 */
    private val booksDir: String get() = AppFilesDirs.get().filesDir + "/books"

    /** 合法文件后缀 (与 UrlUtil.fileSuffixRegex 一致, 该 regex 未下沉 commonMain 故此处内联)。 */
    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return "$coversDir/${MD5Utils.md5Encode16(bookUrl)}.jpg"
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 对齐 app 端 FileBook 分派: EPUB→EpubFile, TXT(else 兜底)→TextFile(TextFileCore);
        // pdf/cbz 解析器未下沉, 仍抛明确异常
        return when {
            book.isEpub -> EpubFile
            book.isPdf || book.isImage -> UnsupportedFileBook
            else -> TextFile
        }
    }

    override fun getBookInputStream(book: Book): InputStream {
        val file = resolveLocalFile(book.bookUrl)
        return file.readBytes().toInputStream()
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
        // native 端无 libarchive, 仅支持 zip/cbz (RemoteZipCore); rar/7z 抛异常
        val archiveFile = resolveLocalFile(archiveFileUri)
        if (!archiveFile.name.endsWith(".zip", true) && !archiveFile.name.endsWith(".cbz", true)) {
            throw NoStackTraceException("iOS/鸿蒙端仅支持 zip/cbz 压缩包导入, 不支持 ${archiveFile.name}")
        }
        val books = mutableListOf<Book>()
        val bytes = archiveFile.readBytes()
        val zip = RemoteZipCore(bytesRangedSource(bytes), archiveFile.name, bytes.size.toLong())
        try {
            for (meta in zip.ensureMeta().values) {
                val entry = meta.entry
                if (entry.name.endsWith("/")) continue
                if (filter != null && !filter(entry.name)) continue
                val entryName = saveFileName ?: entry.name.substringAfterLast('/')
                if (!FileBook.isBookFile(entryName) && !AppPattern.archiveFileRegex.matches(entryName)) continue
                val savedUri = saveBookFile(zip.readDecompressed(meta).toInputStream(), entryName)
                val imported = importLocalFile(savedUri).apply {
                    origin = "${BookType.localTag}::${archiveFile.name}"
                    addType(BookType.archive)
                    runBlocking { AppDbProviders.get().bookDao.update(this@apply) }
                }
                books.add(imported)
            }
        } finally {
            zip.close()
        }
        if (books.isEmpty()) {
            throw NoStackTraceException("压缩包内未找到支持的书籍文件")
        }
        return books
    }

    override fun importLocalFile(uriStr: String): Book {
        val file = resolveLocalFile(uriStr)
        val fileName = file.name
        if (file.length() == 0L) throw NoStackTraceException("Unexpected empty File")
        val (name, author) = analyzeNameAuthor(fileName)
        var type = BookType.text or BookType.local
        when {
            fileName.endsWith(".cbz", true) -> type = BookType.image or BookType.local
            AppPattern.archiveFileRegex.matches(fileName) -> {
                // zip 压缩包: 检查内含书籍文件, 有则解压导入第一个 (与 desktop 一致)
                if (fileName.endsWith(".zip", true)) {
                    val bytes = file.readBytes()
                    val zip = RemoteZipCore(bytesRangedSource(bytes), fileName, bytes.size.toLong())
                    try {
                        val meta = zip.ensureMeta().values.firstOrNull {
                            !it.entry.name.endsWith("/") && FileBook.isBookFile(it.entry.name)
                        }
                        if (meta != null) {
                            val savedUri = saveBookFile(
                                zip.readDecompressed(meta).toInputStream(),
                                meta.entry.name.substringAfterLast('/')
                            )
                            return importLocalFile(savedUri)
                        }
                    } finally {
                        zip.close()
                    }
                }
                throw NoStackTraceException("iOS/鸿蒙端仅支持 zip/cbz 压缩包, 不支持 $fileName")
            }
        }
        val bookUrl = "file://" + file.absolutePath
        return importBook(
            Book(
                bookUrl = bookUrl,
                name = name,
                author = author,
                originName = fileName,
                latestChapterTime = file.lastModified(),
                order = runBlocking { AppDbProviders.get().bookDao.minOrder() } - 1,
                origin = bookUrl,
                type = type
            )
        )
    }

    /** 统一导入逻辑 (对齐 app 端 FileBookAccessorImpl.importBook / desktop 版, runBlocking 适配 suspend DAO)。 */
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
        // 与 desktop 一致: 删封面 + (可选) 原文件
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
        // webDav 走 WebDav 下载流; 网络 URL 走 AnalyzeUrlCore 取字节
        // (native 响应体本就整块进内存, getByteArrayAwait 与流式等价)
        val inputStream = if (!str.startsWith(BookType.webDavTag)) {
            AnalyzeUrlCore(
                str, source = source, callTimeout = 0,
                coroutineContext = currentCoroutineContext()
            ).getByteArrayAwait().toInputStream()
        } else {
            WebDav.fromPath(str.substring(BookType.webDavTag.length)).downloadInputStream()
        }
        return saveBookFile(inputStream, fileName)
    }

    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        // native 端无 SAF, 写入 {filesDir}/books (app 端走 defaultBookTreeUri + DocumentFile)
        File(booksDir).mkdirs()
        val file = File("$booksDir/$fileName")
        try {
            file.writeBytes(inputStream.readFully())
        } finally {
            inputStream.close()
        }
        return "file://" + file.absolutePath
    }

    override fun downloadRemoteBook(book: Book): Boolean {
        // 与 desktop 一致: saveBookFile(str) + importFromArchive + bookDao.update
        val webDavUrl = book.getRemoteUrl()
            ?: throw NoStackTraceException("Book file is not webDav File")
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
        // 与 app 端一致: onLineBook 为 null 时返回 localBook; 字段合并 + bookDao.update 替代 Book.save()
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        if (!onLineBook.intro.isNullOrBlank()) {
            localBook.intro = onLineBook.intro
        }
        val bookDao = AppDbProviders.get().bookDao
        runBlocking {
            if (bookDao.has(localBook.bookUrl)) bookDao.update(localBook) else bookDao.insert(localBook)
        }
        return localBook
    }

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 与 desktop 一致: 委托下沉的 BookNameAuthorAnalyzer, importFileNameJs 读同一 PreferKey
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
                // Range 读远程 zip 目录 (与 desktop RemoteZipWrapper 语义一致, 复用 commonMain RemoteZipCore)
                val remoteZip = RemoteZipCore(
                    RangedSource { offset, length, fileSize -> webDav.readRange(offset, length, fileSize) },
                    name, size
                )
                try {
                    val metas = remoteZip.ensureMeta().values
                    val hasBookFile = metas.any { !it.entry.name.endsWith("/") && FileBook.isBookFile(it.entry.name) }
                    val hasImageFile = metas.any { !it.entry.name.endsWith("/") && AppPattern.imgFileRegex.matches(it.entry.name) }
                    when {
                        hasImageFile -> importAsImage()
                        hasBookFile -> {
                            val meta = metas.first { !it.entry.name.endsWith("/") && FileBook.isBookFile(it.entry.name) }
                            val uriStr = saveBookFile(
                                remoteZip.readDecompressed(meta).toInputStream(),
                                meta.entry.name.substringAfterLast('/')
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

    override fun getUrlSuffix(name: String): String {
        // 对齐 UrlUtil.getSuffix 原版语义: 不带点 + 合法性校验 + "ext" 兜底
        val suffix = CustomUrl(name).getUrl()
            .substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringAfterLast(".", "")
        return if (suffix.length > 5 || !suffix.matches(fileSuffixRegex)) {
            AppLog.put("Cannot find legal suffix:\n target: $name\n suffix: $suffix")
            "ext"
        } else {
            suffix
        }
    }

    /** 解析 bookUrl 为 [File] (与 NativeLocalBookLocator.parseLocalPath 剥离规则一致)。 */
    private fun resolveLocalFile(bookUrl: String): File {
        val path = when {
            bookUrl.startsWith("file:") -> {
                val afterScheme = bookUrl.substringAfter("file://")
                val slashIdx = afterScheme.indexOf('/')
                if (slashIdx > 0) afterScheme.substring(slashIdx) else afterScheme
            }
            else -> bookUrl
        }
        return File(path)
    }

    /** 把 [ByteArray] 包装为 [RangedSource] (供 RemoteZipCore 就地解析内存 zip)。 */
    private fun bytesRangedSource(bytes: ByteArray) = RangedSource { offset, length, _ ->
        val start = offset.toInt().coerceAtLeast(0)
        val end = (start + length).coerceAtMost(bytes.size)
        if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
    }

    /** 读尽 [InputStream] 全部字节 (expect InputStream 无 readBytes, 手写累积)。 */
    private fun InputStream.readFully(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n <= 0) break
            chunks.add(buf.copyOf(n))
        }
        val out = ByteArray(chunks.sumOf { it.size })
        var off = 0
        for (c in chunks) {
            c.copyInto(out, off)
            off += c.size
        }
        return out
    }
}

/**
 * PDF/CBZ 等未下沉格式的占位解析器: 抛明确"此端暂不支持"异常, 不静默空返回。
 *
 * 文案走 sharedStringTable key (未收录时用内置简中兜底, 与 iOS/鸿蒙端 i18n 现状一致)。
 */
private object UnsupportedFileBook : BaseFileBook {
    override fun upBookInfo(book: Book) = throw unsupportedFormatException(book.originName)
    override fun getChapterList(book: Book): ArrayList<BookChapter> =
        throw unsupportedFormatException(book.originName)

    override fun getContent(book: Book, chapter: BookChapter): String? =
        throw unsupportedFormatException(book.originName)

    override fun getImage(book: Book, href: String): InputStream? =
        throw unsupportedFormatException(book.originName)
}

private fun unsupportedFormatException(fileName: String): NoStackTraceException {
    val suffix = fileName.substringAfterLast('.', "").ifEmpty { fileName }
    val template = sharedStringTable["native_book_format_not_supported"]
        ?: "iOS/鸿蒙端暂不支持解析 %s 格式本地书 (pdf/cbz 解析器未下沉)"
    return NoStackTraceException(template.formatNative(suffix))
}

/**
 * iOS/鸿蒙宿主启动早期注册 [FileBookProviders] (两端共用)。
 *
 * 须在 BookStorage/LocalBookLocator/BitmapProviders 之后 (EpubFile 解析/封面依赖),
 * 任何 FileBook/EpubFile 调用之前。
 */
fun registerNativeFileBookAccessor() {
    FileBookProviders.register(NativeFileBookAccessor())
}
