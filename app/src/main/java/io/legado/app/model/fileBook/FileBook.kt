package io.legado.app.model.fileBook

import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale.getDefault
import java.util.regex.Pattern

/**
 * 书籍文件导入 目录正文解析
 * 支持在线文件(txt epub umd 压缩文件 本地文件
 */
object FileBook : BaseFileBook {

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    fun Book.getHandler(): BaseFileBook {
        val originName = originName.lowercase(getDefault())
        return when {
            isPdf -> PdfFile
            isLocal && (originName.endsWith(".mobi") || originName.endsWith(".azw3") || originName.endsWith(
                ".azw"
            )) -> MobiFile

            isEpub -> EpubFile
            isLocal && originName.endsWith(".umd") -> UmdFile
            isLocal && (originName.endsWith(".cbz") || originName.endsWith(".zip") && isImage) -> CbzFile

            else -> TextFile
        }
    }

    fun isBookFile(fileName: String): Boolean = AppPattern.bookFileRegex.matches(fileName)

    @Throws(TocEmptyException::class)
    override fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = book.getHandler().getChapterList(book)
        if (chapters.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        val list = ArrayList(LinkedHashSet(chapters))
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        val useReplaceRule = book.getUseReplaceRule()
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            if (bookChapter.title.isEmpty()) {
                bookChapter.title = "无标题章节"
            }
        }
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(replaceRules, useReplaceRule)
        book.latestChapterTitle = list.last().getDisplayTitle(replaceRules, useReplaceRule)
        book.totalChapterNum = list.size
        book.latestChapterTime = System.currentTimeMillis()
        return list
    }

    override fun getContent(book: Book, chapter: BookChapter): String? {
        return try {
            book.getHandler().getContent(book, chapter)
        } catch (e: Exception) {
            "获取本地书籍内容失败\n${e.localizedMessage}".also { AppLog.put(it, e) }
        }
    }

    fun getCoverPath(bookUrl: String) =
        FileUtils.getPath(appCtx.externalFiles, "covers", "${MD5Utils.md5Encode16(bookUrl)}.jpg")


    /**
     * 统一核心导入逻辑
     */
    private fun importBook(
        book: Book
    ): Book {
        val dbBook = appDb.bookDao.getBook(book.bookUrl)
        return if (dbBook == null) {
            book.apply {
                upBookInfo(this)
                appDb.bookDao.insert(this)
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
                upBookInfo(this)
                appDb.bookDao.update(this)
            }
        }
    }

    override fun upBookInfo(book: Book) = book.getHandler().upBookInfo(book)
    override fun getImage(book: Book, href: String) = book.getHandler().getImage(book, href)

    /* 导入压缩包内的书籍 */
    fun importFromArchive(
        archiveFileUri: Uri, saveFileName: String? = null, filter: ((String) -> Boolean)? = null
    ): List<Book> {
        val archiveFileDoc = FileDoc.fromUri(archiveFileUri, false)
        val files = ArchiveUtils.deCompress(archiveFileDoc, filter = filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
        }
        return files.map {
            saveBookFile(FileInputStream(it), saveFileName ?: it.name).let { uri ->
                importLocalFile(uri).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFileDoc.name}"
                    addType(BookType.archive)
                    save()
                }
            }
        }
    }

    fun importLocalFile(uri: Uri) = importLocalFile(FileDoc.fromUri(uri, false))
    fun importLocalFile(fileDoc: FileDoc): Book {
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
                    return importFromArchive(fileDoc.uri) { isBookFile(it) }.firstOrNull()
                        ?: throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
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
                order = appDb.bookDao.minOrder - 1,
                origin = fileDoc.uri.toString(),
                type = type
            )
        )
    }

    suspend fun importRemoteBook(
        webDav: WebDav, serverID: Long?, remoteBook: RemoteBook, downloadFile: Boolean = false
    ): Book {
        val (name, path, size, lastModify) = remoteBook
        val origin = BookType.webDavTag + CustomUrl(path).putAttribute(
            "serverID", serverID
        ).toString()

        suspend fun importAsImage(): Book {
            val bookUrl = if (downloadFile && size <= 30 * 1024 * 1024) {
                saveBookFile(origin, name).toString()
            } else origin
            return importBook(
                Book(
                    bookUrl = bookUrl,
                    name = name.substringBeforeLast("."),
                    author = "",
                    originName = name,
                    latestChapterTime = lastModify,
                    order = appDb.bookDao.minOrder - 1,
                    origin = origin,
                    type = BookType.image or BookType.local
                ).apply {
                    variable = "cbz:${0L},${0L},${size}"
                })
        }

        return when {
            name.endsWith(".cbz", true) -> importAsImage()
            name.endsWith(".zip", true) -> {
                val remoteZip = RemoteZipWrapper(webDav, name, size)
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
                        val uri = saveBookFile(
                            remoteZip.getInputStream(entry)
                                ?: throw NoStackTraceException("获取流失败"), entry.name
                        )
                        importLocalFile(uri).apply {
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

    /**
     * 从文件分析书籍必要信息（书名 作者等）
     */
    private fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name = ""
        var author = ""
        AppConfig.bookImportFileName?.takeIf { it.isNotBlank() }?.let { jsCode ->
            try {
                //在用户脚本后添加捕获author、name的代码，只要脚本中author、name有值就会被捕获
                val js = "$jsCode\nJSON.stringify({author:author,name:name})"
                //在脚本中定义如何分解文件名成书名、作者名
                val jsonStr = RhinoScriptEngine.run {
                    val bindings = ScriptBindings().apply { put("src", tempFileName) }
                    eval(js, bindings)
                }.toString()
                val bookMess = GSON.fromJsonObject<Map<String, String>>(jsonStr).getOrNull()
                name = bookMess?.get("name") ?: ""
                author = bookMess?.get("author")?.takeIf { it.length != tempFileName.length } ?: ""
            } catch (e: Exception) {
                AppLog.put("执行导入文件名规则出错\n${e.localizedMessage}", e)
            }
        }
        if (name.isBlank()) {
            for (pattern in nameAuthorPatterns) {
                pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                    name = group(2)!!
                    author = BookHelp.formatBookAuthor((group(1) ?: "") + (group(3) ?: ""))
                    return Pair(name, author)
                }
            }
            name = tempFileName
                .replace(AppPattern.nameRegex, "")
                .trim()
            author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
                .takeIf { it.length != tempFileName.length } ?: ""
        }
        return Pair(name, author)
    }

    fun deleteBook(book: Book, deleteOriginal: Boolean) {
        kotlin.runCatching {
            BookHelp.clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtils.delete(book.coverUrl!!)
            }
            if (deleteOriginal) {
                if (book.bookUrl.isContentScheme()) {
                    val uri = book.bookUrl.toUri()
                    DocumentFile.fromSingleUri(appCtx, uri)?.delete()
                } else {
                    FileUtils.delete(book.bookUrl)
                }
            }
        }
    }

    /**
     * 下载在线的文件 (支持HTTP URL和WebDAV URL)
     */
    suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Uri {
        AppConfig.defaultBookTreeUri ?: throw NoBooksDirException()
        val inputStream = if (!str.startsWith(BookType.webDavTag)) AnalyzeUrl(
            str, source = source, callTimeout = 0, coroutineContext = currentCoroutineContext()
        ).getInputStreamAwait()
        else WebDav.fromPath(str.substring(BookType.webDavTag.length)).downloadInputStream()
        return saveBookFile(inputStream, fileName)
    }

    @Throws(SecurityException::class)
    fun saveBookFile(
        inputStream: InputStream, fileName: String
    ): Uri {
        inputStream.use {
            val treeUri = AppConfig.defaultBookTreeUri?.toUri() ?: throw NoBooksDirException()
            return if (treeUri.isContentScheme()) {
                val treeDoc = DocumentFile.fromTreeUri(appCtx, treeUri)
                val doc = treeDoc?.findFile(fileName) ?: treeDoc?.createFile(
                    FileUtils.getMimeType(fileName), fileName
                ) ?: throw SecurityException("请重新设置书籍保存位置\nPermission Denial")
                appCtx.contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                    it.copyTo(oStream)
                }
                doc.uri
            } else {
                try {
                    val treeFile = File(treeUri.path!!)
                    val file = treeFile.getFile(fileName)
                    FileOutputStream(file).use { oStream ->
                        it.copyTo(oStream)
                    }
                    Uri.fromFile(file)
                } catch (e: Exception) {
                    throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                        addSuppressed(e)
                    }
                }
            }
        }
    }

    //文件类书源 合并在线书籍信息 在线 > 本地
    fun mergeBook(localBook: Book, onLineBook: Book?): Book {
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

    //下载book对应的远程文件 并更新Book
    fun downloadRemoteBook(book: Book): Boolean {
        val webDavUrl = book.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        val fileName = if (book.isArchive) book.archiveName else book.originName
        val fileUri = runBlocking { saveBookFile(webDavUrl, fileName) }
        if (book.isArchive) {
            val newBook = importFromArchive(fileUri, book.originName) { name ->
                name.contains(book.originName)
            }.firstOrNull() ?: throw NoStackTraceException("Archive contains no matching book file")
            book.origin = newBook.origin
            book.bookUrl = newBook.bookUrl
        } else {
            book.bookUrl = FileDoc.fromUri(fileUri, false).toString()
        }
        book.save()
        return true
    }

    data class WebFile(
        val url: String,
        val name: String,
    ) {
        override fun toString(): String = name

        val suffix: String = UrlUtil.getSuffix(name)

        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}