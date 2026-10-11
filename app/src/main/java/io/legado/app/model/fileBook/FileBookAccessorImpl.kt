package io.legado.app.model.fileBook

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.legado.app.App
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.addType
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.i18n.appString
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.fileBook.FileBookAccessorImpl.importLocalFile
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
import io.legado.app.utils.parseToUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
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
 *   saveBookFile / analyzeNameAuthor 等) 委托
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
 * 远程书导入的编排 ([FileBookRemoteImportShared]) 原接收
 * [io.legado.app.model.remote.RemoteBook] (依赖 appDb 构造, 未下沉), 进共享层前
 * 拍平为 name/path/size/lastModify 基本类型参数, 不重建 RemoteBook。
 *
 * 注册时机: App.onCreate 经 [registerAndroidFileBookProviders]。
 * 模式参考 [io.legado.app.model.webBook.WebBookProvidersImpl] /
 * [io.legado.app.model.AudioPlayProvidersImpl]。
 */
object FileBookAccessorImpl : FileBookAccessor {

    /** SAF 保存位置下 WebDav 下载的中转目录名 (`{cacheDir}/webDav`, 临时文件用后即删)。 */
    private const val WEB_DAV_TEMP_DIR = "webDav"

    // ---------- BaseFileBook 默认实现 ----------

    /**
     * 获取书籍输入流 (原 BaseFileBook.getBookInputStream 默认实现)。
     *
     * 走 Uri.inputStream(appCtx), 失败回退 importFromArchive / downloadRemoteBook。
     */
    override fun getBookInputStream(book: Book): InputStream {
        val ref = LocalBookLocators.get().getReadableFileRef(book) ?: book.bookUrl
        val uri = ref.parseToUri()
        val inputStream = uri.inputStream(App.instance).getOrNull() ?: let {
            val localArchiveUri = book.getArchiveUri()
            val webDavUrl = book.getRemoteUrl()
            if (localArchiveUri != null) {
                // 重新导入对应的压缩包
                importFromArchive(localArchiveUri.toString(), book.originName) {
                    it.contains(book.originName)
                }.firstOrNull()?.let {
                    getBookInputStream(it)
                }
            } else if (webDavUrl != null && FileBook.downloadRemoteBook(book)) {
                // 下载远程链接
                getBookInputStream(book)
            } else {
                null
            }
        }
        if (inputStream != null) return inputStream
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    /** 获取书籍文件最后修改时间 (原 BaseFileBook.getLastModified 默认实现)。 */
    override fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = book.bookUrl.toUri()
            if (uri.isContentScheme()) {
                return@runCatching androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(App.instance, uri)!!.lastModified()
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
        val archiveFileDoc = FileDoc.fromUri(archiveFileUri.toUri(), false)
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
        return importLocalFile(FileDoc.fromUri(uriStr.toUri(), false))
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

    /**
     * 删除书籍 (原 `FileBook.deleteBook`)。
     *
     * 章节缓存与封面在应用私有区, 删不掉不影响用户的源文件, 尽力而为即可;
     * 源文件删不掉则抛出原因 —— 库里记录已删、文件还在磁盘上, 上层据此提示书名与原因。
     * 源文件本就不在时删除意图已达成, 不抛。
     */
    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        kotlin.runCatching {
            BookHelp.clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtils.delete(book.coverUrl!!)
            }
        }
        if (!deleteOriginal) return
        if (book.bookUrl.isContentScheme()) {
            deleteSourceDocument(book)
        } else {
            deleteSourceFile(book)
        }
    }

    /**
     * 删除 content scheme (SAF) 源文件。
     *
     * [androidx.documentfile.provider.DocumentFile.delete] 把失败与异常一律折成 false, 故删不掉时
     * 再看目标是否还在: 不在说明删除已达成, 还在则如实报错。
     */
    private fun deleteSourceDocument(book: Book) {
        val uri = book.bookUrl.toUri()
        if (FileDoc.fromUri(uri, false).asDocumentFile()?.delete() == true) return
        if (sourceDocumentGone(uri)) return
        throw IOException("未能删除源文件: $uri")
    }

    /**
     * content scheme 目标是否已不在 (删除意图已达成)。
     *
     * 只有解析器明确答"没有这个文档"才算: 文档不在时返回空结果或报 [FileNotFoundException];
     * 拿不到结果 (返回 null) 与已无访问权限都不算 —— 授权失效时抛 [SecurityException] 让上层如实提示。
     * 判定不用 `DocumentFile.exists()`: 它把查询异常吞成 false, 分不出"文件不在"与"没有访问权限",
     * 会把授权失效当成已删除。
     */
    private fun sourceDocumentGone(uri: Uri): Boolean = try {
        App.instance.contentResolver
            .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count == 0 } ?: false
    } catch (_: FileNotFoundException) {
        true
    } catch (e: SecurityException) {
        throw SecurityException("没有访问权限: $uri", e)
    }

    /**
     * 删除文件路径源文件 (bookUrl 可能带 `file://` 前缀, 解析成真实路径再删)。
     *
     * 引用为空或解析结果落在文件系统根目录时拒绝删除: 空引用不指向任何文件 (定位器把它解析成
     * 进程工作目录), 根目录的删除是递归的 —— 两者都不是这本书的源文件。
     */
    private fun deleteSourceFile(book: Book) {
        val locator = LocalBookLocators.get()
        val ref = book.bookUrl
        val path = if (ref.isBlank()) "" else (locator.getLocalPath(book) ?: ref)
        // 已删除的书不留路径记忆 (解析会写回缓存)
        locator.removeLocalPathCache(book)
        if (path.isBlank()) throw IOException("未能删除源文件: 书籍文件引用为空")
        if (isFileSystemRoot(path)) throw IOException("未能删除源文件: $path 是文件系统根目录")
        if (FileUtils.delete(path)) return
        if (File(path).exists()) throw IOException("未能删除源文件: $path")
    }

    /** [path] 是否文件系统根目录 (`/` 这类没有父目录的路径): 对它的删除会波及整盘。 */
    private fun isFileSystemRoot(path: String): Boolean {
        val file = File(path)
        return runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile).parentFile == null
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

    /**
     * 流式保存 WebDav 远端书籍文件 ("下载到本地" / "刷新远端更新" 链路)。
     *
     * 复用既有的 [WebDav.downloadTo] (savedPath, replaceExisting) 边下边写, 不再经
     * `saveBookFile(inputStream, fileName)` 的 Stream→Stream 拷贝 (该重载本身已是流式,
     * 但无法直写文件路径)。
     *
     * 两种保存位置的处理:
     * - **非 SAF (文件路径)**: downloadTo 直写 `{defaultBookTreeDir}/{fileName}`, 失败时删净残缺文件;
     * - **SAF (`content://`)**: downloadTo 只能按文件路径写, 故先流式下到 `cacheDir/webDav` 下的
     *   临时文件, 再复用 [saveBookFile] (inputStream 重载) 的既有权通道拷进 SAF 文档
     *   (仍是流式, 内存有界; 临时文件无论成败都删)。
     * 返回引用形式与 [saveBookFile] (inputStream 重载) 完全一致 (content:// 或 file://)。
     *
     * 异常口径: 保存位置不可用 (目录被删/无写权限) 仍报改动前文件路径分支的权限类提示
     * (`SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e")`), 其余异常照旧透传。
     */
    @Throws(SecurityException::class)
    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        val treeUri = AppConfig.defaultBookTreeUri?.toUri() ?: throw InvalidBooksDirException(
            androidAppString("no_books_dir")
        )
        if (!treeUri.isContentScheme()) {
            val file = File(treeUri.path!!).getFile(fileName)
            // 分辨"保存位置不可用"与网络/传输异常: 前者按改动前 `saveBookFile(inputStream)`
            // 文件路径分支的口径给出权限类提示, 后者原样透传。append 模式探测只验"能否打开
            // 目标文件写", 不截断已有文件; 与 downloadTo 随后自己打开的是同一个文件, 仍是流式。
            try {
                FileOutputStream(file, true).close()
            } catch (e: Exception) {
                throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                    addSuppressed(e)
                }
            }
            try {
                webDav.downloadTo(file.absolutePath, true)
            } catch (e: Throwable) {
                // ⚠️ 这段删除是**对原版的偏离** (不是对齐原版): 原版 `FileBook.downloadRemoteBook`
                // 走"先读满再整份写", 中途失败时目标文件不会被创建也不留半截, 所以原版没这段清理;
                // 改成边下边写后中途失败会留下残缺文件, 不删干净的话它会被后续导入当成本地书读入
                // (原版不存在这个缺陷)。不复刻原版: 留一份能被当书读入的半截文件比多一次 delete 更坏。
                kotlin.runCatching { file.delete() }
                throw e
            }
            return Uri.fromFile(file).toString()
        }
        // SAF: downloadTo 写不了 content:// 文档, 先流式下到缓存临时文件再交给 SAF 通道拷入
        val tempFile = File(File(App.instance.cacheDir, WEB_DAV_TEMP_DIR).apply { mkdirs() }, fileName)
        try {
            webDav.downloadTo(tempFile.absolutePath, true)
            return saveBookFile(tempFile.inputStream(), fileName)
        } finally {
            kotlin.runCatching { tempFile.delete() }
        }
    }

    /** 获取封面缓存路径 (原 `FileBook.getCoverPath`)。 */
    override fun getCoverPath(bookUrl: String): String =
        FileUtils.getPath(
            App.instance.externalFiles,
            "covers",
            "${MD5Utils.md5Encode16(bookUrl)}.jpg"
        )

    /** 从文件名分析书名与作者 (原 `FileBook.analyzeNameAuthor`, 纯逻辑已下沉 [BookNameAuthorAnalyzer])。 */
    override fun analyzeNameAuthor(fileName: String): Pair<String, String> =
        BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, AppConfig.bookImportFileName)

    /** 获取合法的文件后缀 (对应 `UrlUtil.getSuffix(name)`)。 */
    override fun getUrlSuffix(name: String): String = UrlUtil.getSuffix(name)

    /** 保存引用 → bookUrl 存储引用 (非 SAF 的 file:// 去前缀, 原版 FileDoc 语义)。 */
    override fun normalizeBookRef(uriStr: String): String =
        FileDoc.fromUri(uriStr.toUri(), false).toString()

    /** 判断是否为支持的书籍文件 (原 `FileBook.isBookFile`)。 */
    private fun isBookFile(fileName: String): Boolean =
        AppPattern.bookFileRegex.matches(fileName)

    /**
     * 统一核心导入逻辑 (旧书删旧缓存重建 / 新书上架), 编排下沉
     * [FileBookRemoteImportShared.importBook]。
     */
    private fun importBook(book: Book): Book =
        runBlocking { FileBookRemoteImportShared.importBook(book) }

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
