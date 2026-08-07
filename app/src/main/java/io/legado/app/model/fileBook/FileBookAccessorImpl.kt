package io.legado.app.model.fileBook

import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.i18n.appString
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.FileBookAccessorImpl.importLocalFile
import io.legado.app.model.fileBook.FileBookAccessorImpl.importRemoteBook
import io.legado.app.model.fileBook.FileBookAccessorImpl.saveBookFile
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * [FileBookAccessor] 的 Android 实现。
 *
 * 原 app 端 `object FileBook` / `interface BaseFileBook` 的重 Android 依赖逻辑
 * (android.net.Uri / DocumentFile / FileDoc / ArchiveUtils / AnalyzeUrl / WebDav /
 * RemoteBook / appCtx / FileUtils 等)
 * 全部集中在本文件, 由 [FileBookAccessor] 接口暴露给 shared/commonMain 调用。
 *
 * # 与 shared/commonMain 的关系
 * - [FileBook] (shared) 的平台专属方法 (importFromArchive / importLocalFile /
 *   saveBookFile / downloadRemoteBook / mergeBook / analyzeNameAuthor 等) 委托
 *   [FileBookProviders.get()], 最终走到本实现。
 * - [BaseFileBook] (shared) 的 getBookInputStream / getLastModified 默认实现
 *   (原 app 端接口默认方法) 在本实现以 override 形式提供, 供 shared 端经
 *   accessor 调用; app 端调用方仍可通过 [BaseFileBookExt] 扩展函数访问
 *   (扩展委托本 accessor, 行为一致)。
 *
 * # Uri ↔ String 转换
 * accessor 接口约定 Uri 以 String (Uri.toString()) 传递, 本实现内部用
 * [Uri.parse] / [toString] 转换, 行为与原 app 端直接传 Uri 完全一致。
 *
 * # RemoteBook 拍平
 * [importRemoteBook] 原接收 [io.legado.app.model.remote.RemoteBook] (依赖 appDb
 * 构造, 未下沉), accessor 接口拍平为 name/path/size/lastModify 基本类型参数。
 * 本实现内部用这些参数直接走原逻辑, 不重建 RemoteBook。
 *
 * 注册时机: App.onCreate 经 [registerAndroidFileBookProviders]。
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl] /
 * [io.legado.app.model.AudioPlayProvidersImpl]。
 */
object FileBookAccessorImpl : FileBookAccessor {

    // ---------- BaseFileBook 默认实现 ----------

    /**
     * 获取书籍输入流 (原 BaseFileBook.getBookInputStream 默认实现)。
     *
     * 走 Uri.inputStream(appCtx), 失败回退 importFromArchive / downloadRemoteBook。
     */
    override fun getBookInputStream(book: Book): InputStream {
        val uri = book.getLocalUri()
        val inputStream = uri.inputStream(appCtx).getOrNull() ?: let {
            book.removeLocalUriCache()
            val localArchiveUri = book.getArchiveUri()
            val webDavUrl = book.getRemoteUrl()
            if (localArchiveUri != null) {
                // 重新导入对应的压缩包
                importFromArchive(localArchiveUri.toString(), book.originName) {
                    it.contains(book.originName)
                }.firstOrNull()?.let {
                    getBookInputStream(it)
                }
            } else if (webDavUrl != null && downloadRemoteBook(book)) {
                // 下载远程链接
                getBookInputStream(book)
            } else {
                null
            }
        }
        if (inputStream != null) return inputStream
        book.removeLocalUriCache()
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    /** 获取书籍文件最后修改时间 (原 BaseFileBook.getLastModified 默认实现)。 */
    override fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = book.bookUrl.toUri()
            if (uri.isContentScheme()) {
                return@runCatching androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(appCtx, uri)!!.lastModified()
            }
            val file = File(uri.path!!)
            if (file.exists()) {
                return@runCatching file.lastModified()
            }
            throw FileNotFoundException("${uri.path} 文件不存在")
        }
    }

    // ---------- FileBook 平台专属方法 ----------

    /** 获取书籍对应的文件解析器 (原 `Book.getHandler()`)。 */
    override fun getHandler(book: Book): BaseFileBook {
        val originName = book.originName.lowercase(Locale.getDefault())
        return when {
            book.isPdf -> PdfFile
            book.isEpub -> EpubFile
            book.isLocal && (originName.endsWith(".cbz") || originName.endsWith(".zip") && book.isImage) -> CbzFile
            else -> TextFile
        }
    }

    /** 导入压缩包内的书籍 (原 `FileBook.importFromArchive`)。Uri 传 String 形式。 */
    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?
    ): List<Book> {
        val archiveFileDoc = FileDoc.fromUri(Uri.parse(archiveFileUri), false)
        val files = ArchiveUtils.deCompress(archiveFileDoc, filter = filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
        }
        return files.map {
            saveBookFile(FileInputStream(it), saveFileName ?: it.name).let { uriStr ->
                importLocalFile(uriStr).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFileDoc.name}"
                    addType(BookType.archive)
                    save()
                }
            }
        }
    }

    /** 导入本地文件 (原 `FileBook.importLocalFile(Uri)`)。Uri 传 String 形式。 */
    override fun importLocalFile(uriStr: String): Book {
        return importLocalFile(FileDoc.fromUri(Uri.parse(uriStr), false))
    }

    /**
     * 导入本地文件 (原 `FileBook.importLocalFile(FileDoc)`)。
     *
     * 原 importLocalFile(FileDoc) 私有重载, 保留完整逻辑供 [importLocalFile] (String)
     * 与 app 端 [FileBookExtensions.importLocalFile] (FileDoc 重载) 复用。
     */
    private fun importLocalFile(fileDoc: FileDoc): Book {
        val (fileName, _, _, updateTime, _) = fileDoc.apply {
            if (size == 0L) throw EmptyFileException("Unexpected empty File")
        }
        val (name, author) = analyzeNameAuthor(fileName)
        var type = BookType.text or BookType.local
        when {
            fileName.endsWith(".cbz", true) -> type = BookType.image or BookType.local
            AppPattern.archiveFileRegex.matches(fileName) -> {
                val names = ArchiveUtils.getArchiveFilesName(fileDoc.uri)
                val hasBookFile = names.any { isBookFile(it) }
                if (hasBookFile) {
                    return importFromArchive(fileDoc.uri.toString()) { isBookFile(it) }.firstOrNull()
                        ?: throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
                }
            }

            else -> {}
        }
        return importBook(
            Book(
                bookUrl = fileDoc.uri.toString(),
                name = name,
                author = author,
                originName = fileName,
                latestChapterTime = updateTime,
                order = runBlocking { appDb.bookDao.minOrder() } - 1,
                origin = fileDoc.uri.toString(),
                type = type
            )
        )
    }

    /** 删除书籍 (原 `FileBook.deleteBook`)。 */
    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        kotlin.runCatching {
            BookHelp.clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtils.delete(book.coverUrl!!)
            }
            if (deleteOriginal) {
                if (book.bookUrl.isContentScheme()) {
                    val uri = book.bookUrl.toUri()
                    FileDoc.fromUri(uri, false).delete()
                } else {
                    FileUtils.delete(book.bookUrl)
                }
            }
        }
    }

    /**
     * 下载并保存在线文件 (原 `FileBook.saveBookFile(str, fileName, source)`)。
     * 原 Uri 返回值改为 String (uri.toString())。
     */
    @Throws(SecurityException::class)
    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        AppConfig.defaultBookTreeUri ?: throw InvalidBooksDirException(
            androidAppString("no_books_dir")
        )
        val inputStream = if (!str.startsWith(BookType.webDavTag)) AnalyzeUrl(
            str, source = source, callTimeout = 0, coroutineContext = currentCoroutineContext()
        ).getInputStreamAwait()
        else WebDav.fromPath(str.substring(BookType.webDavTag.length)).downloadInputStream()
        return saveBookFile(inputStream, fileName)
    }

    /**
     * 保存输入流到文件 (原 `FileBook.saveBookFile(inputStream, fileName)`)。
     * 原 Uri 返回值改为 String (uri.toString())。
     */
    @Throws(SecurityException::class)
    override fun saveBookFile(
        inputStream: InputStream,
        fileName: String
    ): String {
        inputStream.use {
            val treeUri = AppConfig.defaultBookTreeUri?.toUri() ?: throw InvalidBooksDirException(
                androidAppString("no_books_dir")
            )
            return if (treeUri.isContentScheme()) {
                val doc = kotlin.runCatching {
                    FileDoc.fromDir(treeUri).createFileIfNotExist(
                        fileName, mimeType = FileUtils.getMimeType(fileName)
                    )
                }.getOrElse {
                    throw SecurityException("请重新设置书籍保存位置\nPermission Denial")
                }
                doc.openOutputStream().getOrThrow().use { oStream ->
                    it.copyTo(oStream)
                }
                doc.uri.toString()
            } else {
                try {
                    val treeFile = File(treeUri.path!!)
                    val file = treeFile.getFile(fileName)
                    FileOutputStream(file).use { oStream ->
                        it.copyTo(oStream)
                    }
                    Uri.fromFile(file).toString()
                } catch (e: Exception) {
                    throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                        addSuppressed(e)
                    }
                }
            }
        }
    }

    /**
     * 保存本地文件路径到书籍文件 (BookController.addLocalBook web API 下沉用)。
     *
     * 对应原 app 端 `FileBook.saveBookFile(File(fileData).inputStream(), fileName)`,
     * fileData 为 web 上传临时文件路径。内部委托 [saveBookFile] (inputStream 重载),
     * 行为与原 app 端 BookController.addLocalBook 完全一致。
     */
    @Throws(SecurityException::class)
    override fun saveBookFileFromPath(filePath: String, fileName: String): String =
        saveBookFile(File(filePath).inputStream(), fileName)

    /** 下载远程书籍文件并更新 Book (原 `FileBook.downloadRemoteBook`)。 */
    override fun downloadRemoteBook(book: Book): Boolean {
        val webDavUrl = book.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        val fileName = if (book.isArchive) book.archiveName else book.originName
        val fileUriStr = runBlocking { saveBookFile(webDavUrl, fileName) }
        if (book.isArchive) {
            val newBook = importFromArchive(fileUriStr, book.originName) { name ->
                name.contains(book.originName)
            }.firstOrNull() ?: throw NoStackTraceException("Archive contains no matching book file")
            book.origin = newBook.origin
            book.bookUrl = newBook.bookUrl
        } else {
            book.bookUrl = FileDoc.fromUri(Uri.parse(fileUriStr), false).toString()
        }
        book.save()
        return true
    }

    /** 获取封面缓存路径 (原 `FileBook.getCoverPath`)。 */
    override fun getCoverPath(bookUrl: String): String =
        FileUtils.getPath(appCtx.externalFiles, "covers", "${MD5Utils.md5Encode16(bookUrl)}.jpg")

    /** 合并在线书籍信息 (原 `FileBook.mergeBook`)。 */
    override fun mergeBook(localBook: Book, onLineBook: Book?): Book {
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

    /** 从文件名分析书名与作者 (原 `FileBook.analyzeNameAuthor`, 纯逻辑已下沉 [BookNameAuthorAnalyzer])。 */
    override fun analyzeNameAuthor(fileName: String): Pair<String, String> =
        BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, AppConfig.bookImportFileName)

    /**
     * 导入远程书籍 (原 `FileBook.importRemoteBook`)。
     *
     * 原 RemoteBook 参数拍平为 name/path/size/lastModify 基本类型
     * (RemoteBook 依赖 appDb 构造, 未下沉 commonMain)。
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
        val origin = BookType.webDavTag + CustomUrl(path).putAttribute(
            "serverID", serverID
        ).toString()

        suspend fun importAsImage(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) {
                saveBookFile(origin, name)
            } else origin
            return importBook(
                Book(
                    bookUrl = bookUrl,
                    name = name.substringBeforeLast("."),
                    author = "",
                    originName = name,
                    latestChapterTime = lastModify,
                    order = runBlocking { appDb.bookDao.minOrder() } - 1,
                    origin = origin,
                    type = BookType.image or BookType.local
                ).apply {
                    variable = "cbz:${0L},${0L},${size}"
                })
        }

        // 远程EPUB处理：大于30MB不下载，使用动态加载
        suspend fun importAsEpub(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) {
                saveBookFile(origin, name)
            } else origin
            return importBook(
                Book(
                    bookUrl = bookUrl,
                    name = name.substringBeforeLast("."),
                    author = "",
                    originName = name,
                    latestChapterTime = lastModify,
                    order = appDb.bookDao.minOrder() - 1,
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
                        val uriStr = saveBookFile(
                            remoteZip.getInputStream(entry)
                                ?: throw NoStackTraceException("获取流失败"), entry.name
                        )
                        importLocalFile(uriStr).apply {
                            this.origin = origin
                            addType(BookType.archive)
                            save()
                        }
                    } finally {
                        remoteZip.close()
                    }
                } else throw NoStackTraceException("不支持的压缩包格式")
            }

            else -> importLocalFile(saveBookFile(origin, name)).apply {
                this.origin = origin
                save()
            }
        }
    }

    /** 获取合法的文件后缀 (对应 `UrlUtil.getSuffix(name)`)。 */
    override fun getUrlSuffix(name: String): String = UrlUtil.getSuffix(name)

    // ---------- 内部辅助方法 ----------

    /** 判断是否为支持的书籍文件 (原 `FileBook.isBookFile`)。 */
    private fun isBookFile(fileName: String): Boolean =
        AppPattern.bookFileRegex.matches(fileName)

    /**
     * 统一核心导入逻辑 (原 `FileBook.importBook` private 方法)。
     *
     * Room KMP: DAO 方法已改为 suspend，importBook 保持同步语义用 runBlocking 适配。
     */
    private fun importBook(book: Book): Book {
        val dbBook = runBlocking { appDb.bookDao.getBook(book.bookUrl) }
        return if (dbBook == null) {
            book.apply {
                // 委托 commonMain FileBook.upBookInfo → accessor 分派到具体 handler
                FileBook.upBookInfo(this)
                runBlocking { appDb.bookDao.insert(this@apply) }
            }
        } else {
            deleteBook(dbBook, false)
            dbBook.apply {
                this.name = book.name
                this.author = book.author
                this.originName = book.originName
                this.origin = book.origin
                // 文本书籍更新重置时间以触发重新解析，图片书直接使用文件时间
                this.latestChapterTime = 0
                FileBook.upBookInfo(this)
                runBlocking { appDb.bookDao.update(this@apply) }
            }
        }
    }

}

/**
 * 安卓宿主启动早期注册 FileBook 平台 provider。
 *
 * 调用时机: App.onCreate, 在 [registerAndroidWebBookProviders] 之后
 * (FileBookAccessorImpl 依赖 appDb / BookHelp 等, 这些在 WebBookProviders
 * 注册后可用)。注册后 shared/commonMain 的 [FileBook]
 * object 才能经 [FileBookProviders.get] 调到本实现。
 *
 * 模式参考 [registerAndroidWebBookProviders] / [registerAndroidAudioPlayProviders]。
 */
fun registerAndroidFileBookProviders() {
    FileBookProviders.register(FileBookAccessorImpl)
}
