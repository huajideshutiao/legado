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
import io.legado.app.exception.SecurityException
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.addType
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.requireBookSaveDir
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.inputStream
import io.legado.app.utils.toInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import io.legado.app.utils.File
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * native 端 (iOS/鸿蒙) [FileBookAccessor] 实现, 对照 desktop DesktopFileBookAccessor。
 *
 * # 与 desktop 端差异
 * - EPUB 走 nativeMain [EpubFile] (纯 Kotlin EpubParser); TXT 走 nativeMain [TextFile]
 *   (commonMain TextFileCore); PDF/CBZ 解析器未下沉, 走 [UnsupportedFileBook] 抛明确异常, 不静默空返回
 * - zip 解析用 commonMain [RemoteZipCore] + [ZipEntryReader] (无 java.util.zip)
 * - bookUrl 用 `file://` + 绝对路径 (与 NativeLocalBookLocator.parseLocalPath 对齐)
 * - saveBookFile(str) 的 webDav 分支与 "下载到本地" / "刷新远端更新" 链路共用同一流式入口
 *   [saveBookFileFromWebDav] (内部走既有 WebDav.downloadTo 直接写盘, 不经 InputStream 整份读入内存);
 *   网络路径用 AnalyzeUrlCore.getByteArrayAwait
 *   (native 响应体本就整块进内存, 走 getInputStreamAwait 无额外收益)
 */
class NativeFileBookAccessor : FileBookAccessor {

    /** 封面缓存目录: `{filesDir}/covers` (对齐 app 端 `externalFiles/covers`)。 */
    private val coversDir: String get() = AppFilesDirs.get().filesDir + "/covers"

    /**
     * 有效书籍保存目录 (用户在"其它设置 → 书籍保存位置"选定)。
     * 未设置时 [requireBookSaveDir] 抛原版同款异常, 由调用链既有的"去设置目录"出口
     * 拉起目录选择器。
     */
    private fun bookSaveDir(): String = requireBookSaveDir()

    /** 合法文件后缀 (与 UrlUtil.fileSuffixRegex 一致, 该 regex 未下沉 commonMain 故此处内联)。 */
    private val fileSuffixRegex = Regex("^[a-z\\d]+$", RegexOption.IGNORE_CASE)

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return "$coversDir/${MD5Utils.md5Encode16(bookUrl)}.jpg"
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 逐字对齐 app 端 FileBookAccessorImpl.getHandler (即 archive 原版 Book.getHandler):
        // isPdf -> PdfFile / isEpub -> EpubFile /
        // isLocal && (originName 以 .cbz 结尾 || (originName 以 .zip 结尾 && isImage)) -> CbzFile /
        // else -> TextFile。
        // 注意 .cbz 分支不要求 isImage —— 导入时已由 importLocalFile 按后缀置为 image 类型,
        // 但已入库的旧书 type 可能缺失, 按原版以后缀为准才不会误落 TextFile。
        // PDF 解析器在 native 端仍未下沉 (iOS 待接 CoreGraphics, 鸿蒙无渲染 API),
        // 故 isPdf 分支暂走 UnsupportedFileBook 抛明确异常。
        val originName = book.originName.lowercase()
        return when {
            book.isPdf -> UnsupportedFileBook
            book.isEpub -> EpubFile
            book.isLocal && (originName.endsWith(".cbz") || originName.endsWith(".zip") && book.isImage) -> CbzFile
            else -> TextFile
        }
    }

    override fun getBookInputStream(book: Book): InputStream {
        // 流式打开 (不整书读入内存); 调用方负责 close。
        // 打开失败抛 okio IOException 族 (ENOENT 为 FileNotFoundException, 其余含权限不足为普通 IOException)。
        // 引用取当前可用的那个 (引用失效时按文件名在书籍保存位置/导入目录找回, 与桌面端 openLocalFile 同口径);
        // 解析不到时按 bookUrl 原样打开, 由调用方按异常处理
        val path = LocalBookLocators.get().getReadableFileRef(book)
        return (if (path != null) File(path) else resolveLocalFile(book.bookUrl)).inputStream()
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

    /**
     * 统一导入逻辑 (对齐 app/桌面端: 编排在 [FileBookRemoteImportShared.importBook],
     * runBlocking 适配 suspend DAO)。
     */
    private fun importBook(book: Book): Book =
        runBlocking { FileBookRemoteImportShared.importBook(book) }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 与 app/desktop 同一口径: 章节缓存与封面在应用私有区, 删不掉不影响用户的源文件,
        // 尽力而为即可; 源文件删不掉则抛出原因, 由上层如实提示 (见 [deleteSourceFile])。
        runCatching {
            BookStorageProviders.get().clearCache(book)
            book.coverUrl?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        }
        if (!deleteOriginal) return
        deleteSourceFile(book)
    }

    /**
     * 删除本地书源文件, 删不掉时抛出原因: 库里的记录已删、文件还在磁盘上, 上层据此提示书名与原因。
     * 文件本就不在时删除意图已达成, 不抛。
     */
    private fun deleteSourceFile(book: Book) {
        val ref = book.bookUrl
        if (ref.isBlank()) throw IOException("未能删除源文件: 书籍文件引用为空")
        val file = resolveLocalFile(ref)
        if (file.delete()) return
        if (file.exists()) throw IOException("未能删除源文件: ${file.absolutePath}")
    }

    override suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource?
    ): String {
        // 网络 URL 走 AnalyzeUrlCore 取字节
        // (native 响应体本就整块进内存, getByteArrayAwait 与流式等价)
        if (str.startsWith(BookType.webDavTag)) {
            // webDav 整书下载与 "下载到本地" / "刷新远端更新" 链路共用同一流式入口
            // (见 [saveBookFileFromWebDav]; 旧实现直接内联 downloadTo, 两份等价实现归一)
            return saveBookFileFromWebDav(
                WebDav.fromPath(str.substring(BookType.webDavTag.length)), fileName
            )
        }
        val bytes = AnalyzeUrlCore(
            str, source = source, callTimeout = 0,
            coroutineContext = currentCoroutineContext()
        ).getByteArrayAwait()
        return saveBookFile(bytes.toInputStream(), fileName)
    }

    /**
     * 流式保存 WebDav 远端书籍文件 ("下载到本地" / "刷新远端更新" 链路)。
     *
     * native 端无 SAF: 交给既有的 [WebDav.downloadTo] (savedPath, replaceExisting)
     * 边下边写有效书籍保存目录下的 `{fileName}` (用户在"书籍保存位置"选定);
     * 内部 mkdirs + okio 流式写, 见 WebDav.native.kt),
     * 不再经 [saveBookFile] (inputStream 重载) 的 `readFully()` 把整份文件读进内存 ——
     * 峰值内存与文件大小无关。
     *
     * replaceExisting=true: 保持 [saveBookFile] (inputStream 重载) 的覆盖写语义
     * (downloadTo 在 replaceExisting=false 且文件已存在时直接返回, 不覆盖)。
     *
     * ⚠️ 失败时删净残缺文件是**对原版的偏离** (不是对齐原版): 原版 `FileBook.downloadRemoteBook`
     * 走"先读满再整份写", 失败时目标文件不会被创建也不留半截, 所以原版没有这段清理; 改成边下边写后
     * 中途失败会留下半个文件, 不删掉的话它会被后续导入当成本地书读进去 (原版不存在这个缺陷)。
     * 之所以不复刻原版: 保留一份可被当书读入的半截文件比"多一次 delete"更坏。
     *
     * 异常口径与 app 端一致: 保存位置未设置时抛 [io.legado.app.exception.InvalidBooksDirException];
     * 已设置但写不进去 (目录被删/无写权限) 按 app 端文件路径分支的口径
     * 报 `SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e")` (喂给
     * `Throwable.isSecurityException()` 的"去设置目录"出口), 其余异常 (含网络/传输) 照旧透传。
     *
     * 返回引用形式 (`file://` + 绝对路径) 与 [saveBookFile] (inputStream 重载) 一致。
     */
    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        val dir = bookSaveDir()
        val file = File("$dir/$fileName")
        // 分辨"保存位置不可用"与网络/传输异常: 前者按 app 端 FileBook.saveBookFile 文件路径分支的
        // 口径给出权限类提示, 后者原样透传 (与 desktop 端同一口径)。append 模式探测只验
        // "能否打开目标文件写", 不截断已有文件; native File 无 append 流, 直接用 okio
        // appendingSink (mustExist=false, 目标不存在则新建), 与 downloadTo 随后自己打开的
        // 是同一个文件, 仍是流式。
        try {
            File(dir).mkdirs()
            FileSystem.SYSTEM.appendingSink(file.path.toPath()).close()
        } catch (e: Exception) {
            throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                addSuppressed(e)
            }
        }
        try {
            webDav.downloadTo(file.path, true)
        } catch (e: Throwable) {
            // 对原版的偏离 (见方法 KDoc): 边下边写失败会留半截文件, 删掉它而不是留着被当书读入
            runCatching { file.delete() }
            throw e
        }
        return "file://" + file.absolutePath
    }

    override fun saveBookFile(inputStream: InputStream, fileName: String): String {
        // native 端无 SAF, 写入有效书籍保存目录 (用户在"书籍保存位置"选定;
        // app 端走 defaultBookTreeUri + DocumentFile; 未设置时 requireBookSaveDir 抛异常,
        // 由调用链既有的"去设置目录"出口拉起目录选择器)
        //
        // 异常口径与 app 端 FileBook.saveBookFile 文件路径分支逐字一致: 写不进去 (目录被删/
        // 无写权限/磁盘满) 转 `SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e")`,
        // 让 `Throwable.isSecurityException()` (native actual 匹配 io.legado.app.exception.SecurityException)
        // 的调用方 (RemoteBookViewModelShared 的 onPermissionDenied / ReaderScreenModel 的权限请求链)
        // 拉起"去设置目录"出口; 原版该分支同样 `catch (e: Exception)` 包住整段写。
        val dir = bookSaveDir()
        val file = File("$dir/$fileName")
        try {
            File(dir).mkdirs()
            file.writeBytes(inputStream.readFully())
        } catch (e: Exception) {
            throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                addSuppressed(e)
            }
        } finally {
            inputStream.close()
        }
        return "file://" + file.absolutePath
    }

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 与 desktop 一致: 委托下沉的 BookNameAuthorAnalyzer, importFileNameJs 读同一 PreferKey
        val importFileNameJs = runCatching {
            PreferenceProviders.get().getString(PreferKey.bookImportFileName)
        }.getOrNull()
        return BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, importFileNameJs)
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

    /** 解析 bookUrl 为 [File] (与 NativeLocalBookLocator.parseLocalPath 剥离规则一致);
     *  internal 供同模块 iOS 授权装饰器取文件路径包租约。 */
    internal fun resolveLocalFile(bookUrl: String): File {
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
 * 压缩包子书回退: 子书文件读不到时按压缩包名找回原压缩包, 重新解压后打开同名书
 * (对照原版 `BaseFileBook.getBookInputStream` 的 `Book.getArchiveUri` 回退分支与桌面端
 * `DesktopFileBookAccessor.getBookInputStream`)。
 *
 * 由 iOS/鸿蒙两端的目录授权装饰器各自调用 —— 回退过程中的压缩包读取与再打开都要回到自己那份
 * 装饰器 (`importFromArchive` / `getBookInputStream`), 授权才挂在本次真正会读写的路径上。
 *
 * @return 打开成功返回输入流; 没有对应的原压缩包或包内没有同名书返回 null (调用方按原先的失败如实上报)
 */
internal fun FileBookAccessor.reopenFromArchive(book: Book): InputStream? {
    val archiveRef = LocalBookLocators.get().getReadableArchiveRef(book) ?: return null
    return importFromArchive(archiveRef, book.originName) { it.contains(book.originName) }
        .firstOrNull()?.let { getBookInputStream(it) }
}

/**
 * PDF/CBZ 等未下沉格式的占位解析器: 抛明确"此端暂不支持"异常, 不静默空返回。
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
    return NoStackTraceException(syncGetString("native_book_format_not_supported", suffix))
}

/**
 * iOS/鸿蒙宿主启动早期注册 [FileBookProviders] (两端共用)。
 *
 * 须在 BookStorage/LocalBookLocator/BitmapProviders 之后 (EpubFile 解析/封面依赖),
 * 任何 FileBook/EpubFile 调用之前。
 */
fun registerNativeFileBookAccessor() {
    FileBookProviders.register(NativeFileBookAccessor())
    // CbzFile 读 cbz 容器走工厂 (与 app/桌面同模式); native 实现用 commonMain RemoteZipCore
    // 做纯 Kotlin zip 解析。必须与 FileBookProviders 同时就绪: 否则打开 cbz 书时
    // ZipFileWrapperFactoryProviders.get() 抛未注册异常
    ZipFileWrapperFactoryProviders.register(NativeZipFileWrapperFactory())
}
