package io.legado.app.model.fileBook

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.writeBytesAtomically
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
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.atomicWriteTempPath
import io.legado.app.utils.inputStream
import io.legado.app.utils.toInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
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

    override fun getCoverPath(bookUrl: String): String {
        // 与 app 端 FileBookAccessorImpl.getCoverPath 一致: md516(bookUrl).jpg
        return "$coversDir/${MD5Utils.md5Encode16(bookUrl)}.jpg"
    }

    override fun getHandler(book: Book): BaseFileBook {
        // 逐字对齐 app 端 FileBookAccessorImpl.getHandler (即 archive 原版 Book.getHandler):
        // isPdf -> UnsupportedFileBook (PDF 解析器未下沉) / isEpub -> EpubFile /
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

    // 本端读文件不走网络: 没有可归属失败回退的远端下载/解包, [parent] 不收 (签名与接口一致)
    override fun getBookInputStream(book: Book, parent: Job?): InputStream {
        // 流式打开 (不整书读入内存); 调用方负责 close。
        // 打开失败抛 okio IOException 族 (ENOENT 为 FileNotFoundException, 其余含权限不足为普通 IOException)。
        return openLocalFile(localFilePathOf(book))
    }

    /**
     * 本地书文件当前的读取路径: 引用取当前可用的那个 (引用失效时按文件名在书籍保存位置/导入目录找回,
     * 与桌面端 openLocalFile 同口径), 解析不到时按 bookUrl 原样给出, 由打开方按异常处理。
     *
     * internal 供 iOS 授权装饰器在同一路径上取授权与协调 —— 装饰器不另写一套路径解析。
     */
    internal fun localFilePathOf(book: Book): String =
        // 本次协调访问有旁路地址时优先 (书籍身份 bookUrl 不变)
        FileBookOperationPath.current()
            ?: LocalBookLocators.get().getReadableFileRef(book)
            ?: resolveLocalFile(book.bookUrl).path

    /** 按路径打开本地书文件流 (只做打开, 不另做定位)。 */
    internal fun openLocalFile(path: String): InputStream = File(path).inputStream()

    override fun getLastModified(book: Book): Result<Long> {
        return runCatching {
            File(localFilePathOf(book)).lastModified()
        }
    }

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        filter: ((String) -> Boolean)?,
    ): List<Book> = importFromArchive(archiveFileUri, saveFileName, null, filter)

    override fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String?,
        parent: kotlinx.coroutines.Job?,
        filter: ((String) -> Boolean)?,
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
                parent?.ensureActive()
                val savedUri = saveBookFile(
                    zip.readDecompressed(meta).toInputStream(), entryName, parent,
                )
                val imported = importLocalFile(savedUri, parent).apply {
                    origin = "${BookType.localTag}::${archiveFile.name}"
                    addType(BookType.archive)
                    runBlocking(parent ?: kotlin.coroutines.EmptyCoroutineContext) {
                        AppDbProviders.get().bookDao.update(this@apply)
                    }
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

    /**
     * 导入本地文件 (原 `FileBook.importLocalFile(FileDoc)`)。
     *
     * 判型 / 构造 Book / 落库在 [FileBookLocalImportShared] (四端唯一一份), 此处只取平台文件信息
     * 与压缩包能力 ([archiveHasBookFile])。
     */
    override fun importLocalFile(uriStr: String): Book = importLocalFile(uriStr, null)

    override fun importLocalFile(uriStr: String, parent: kotlinx.coroutines.Job?): Book =
        runBlocking(parent ?: kotlin.coroutines.EmptyCoroutineContext) {
        val file = resolveLocalFile(uriStr)
        FileBookLocalImportShared.importLocalFile(
            accessor = this@NativeFileBookAccessor,
            ref = uriStr,
            fileName = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            storedRef = "file://" + file.absolutePath,
            archiveHasBookFile = ::archiveHasBookFile,
            parent = parent,
        )
    }

    /**
     * 压缩包内是否含书籍文件: native 端无 libarchive, 只有 zip 解包器, 其余压缩包格式在此报明确异常
     * (不静默当作文本书导入)。cbz 已在判型里归为图片书, 不到此处。
     */
    private fun archiveHasBookFile(archiveRef: String): Boolean {
        val archiveFile = resolveLocalFile(archiveRef)
        if (!archiveFile.name.endsWith(".zip", true)) {
            throw NoStackTraceException("iOS/鸿蒙端仅支持 zip/cbz 压缩包, 不支持 ${archiveFile.name}")
        }
        val bytes = archiveFile.readBytes()
        val zip = RemoteZipCore(bytesRangedSource(bytes), archiveFile.name, bytes.size.toLong())
        return try {
            zip.ensureMeta().values.any {
                !it.entry.name.endsWith("/") && FileBook.isBookFile(it.entry.name)
            }
        } finally {
            zip.close()
        }
    }

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
        // 与读取同一路径解析: 本次协调地址优先, 不回退到旧引用
        val file = File(localFilePathOf(book))
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
        return saveBookFile(bytes.toInputStream(), fileName, currentCoroutineContext()[Job])
    }

    /**
     * 流式保存 WebDav 远端书籍文件 ("下载到本地" / "刷新远端更新" 链路)。
     *
     * native 端无 SAF: 交给既有的 [WebDav.downloadTo] (savedPath, replaceExisting)
     * 边下边写有效书籍保存目录下的 `{fileName}` (用户在"书籍保存位置"选定),
     * 不再经 [saveBookFile] (inputStream 重载) 的 `readFully()` 把整份文件读进内存 ——
     * 峰值内存与文件大小无关。
     *
     * replaceExisting=true: 保持 [saveBookFile] (inputStream 重载) 的覆盖写语义
     * (downloadTo 在 replaceExisting=false 且文件已存在时直接返回, 不覆盖)。
     *
     * 传输中途失败 (含取消) 时目标名上不会留下半截书: downloadTo 边下边写的是目标同目录的
     * 临时件 (共享整份写入契约), 整份写完才改名就位 —— 目标要么是原有的完整内容, 要么是
     * 完整的新内容 (或不存在)。
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
        // 口径给出权限类提示, 后者原样透传 (与 desktop 端同一口径)。落盘要在目标目录里建临时件
        // 再改名, 所以探的是"能否在目标目录里建出临时件" (名字用共享契约生成, 探完即删);
        // 探目标文件本身会在下载失败时留下 0 字节的同名文件。
        val probe = File(atomicWriteTempPath(file.path))
        try {
            File(dir).mkdirs()
            FileSystem.SYSTEM.write(probe.path.toPath()) { }
        } catch (e: Exception) {
            throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                addSuppressed(e)
            }
        } finally {
            probe.delete()
        }
        val tempPath = atomicWriteTempPath(file.path)
        val temp = File(tempPath)
        // 本请求 Job 是父门: 下载后的正式落位 (复制 + 最终替换) 核同一真实父, 已撤销不得动正式件
        val parent = currentCoroutineContext()[Job]
        try {
            // 先下到同目录临时件 (下载内部逐块核父), 完整后再原子替换正式名
            webDav.downloadTo(tempPath, true)
            parent?.ensureActive()
            BackupFileOps.copyFile(tempPath, file.path, parent)
        } finally {
            runCatching { temp.delete() }
        }
        return "file://" + file.absolutePath
    }

    override fun saveBookFile(
        inputStream: InputStream,
        fileName: String,
        parent: Job?,
    ): String {
        // native 端无 SAF, 写入有效书籍保存目录 (用户在"书籍保存位置"选定;
        // app 端走 defaultBookTreeUri + DocumentFile; 未设置时 requireBookSaveDir 抛异常,
        // 由调用链既有的"去设置目录"出口拉起目录选择器)
        //
        // 异常口径与 app 端 FileBook.saveBookFile 文件路径分支逐字一致: 写不进去 (目录被删/
        // 无写权限/磁盘满) 转 `SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e")`,
        // 让 `Throwable.isSecurityException()` (native actual 匹配 io.legado.app.exception.SecurityException)
        // 的调用方 (RemoteBookViewModelShared 的 onPermissionDenied / ReaderScreenModel 的权限请求链)
        // 拉起"去设置目录"出口; 原版该分支同样 `catch (e: Exception)` 包住整段写。
        // 目录解析 / mkdirs / 读流 / 落位整段在同一关闭保护内: 未设置保存位置
        // (InvalidBooksDirException)、目录不可建 (SecurityException)、物化失败或请求取消,
        // 每条退出路径都先按保存入口契约关闭输入 (真实流 close 后再 read 会抛 Stream closed)
        return try {
            val dir = bookSaveDir()
            val file = File("$dir/$fileName")
            try {
                File(dir).mkdirs()
            } catch (e: Exception) {
                // 只有目录不可建/不可写才是权限问题
                throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                    addSuppressed(e)
                }
            }
            // 进入物化前核父资格, 并在读完每一块后继续核: 撤销不必等整份读完
            parent?.ensureActive()
            val bytes = inputStream.readFully(parent)
            // 整份临时件就位后再替换正式名 (替换前由原语再核一次父): 写途中失败保留旧完整内容
            if (!writeBytesAtomically(file.path, bytes, parent)) {
                throw NoStackTraceException("写入书籍文件失败: ${file.path}")
            }
            "file://" + file.absolutePath
        } finally {
            inputStream.close()
        }
    }

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 与 desktop 一致: 委托下沉的 BookNameAuthorAnalyzer, importFileNameJs 读同一 PreferKey
        val importFileNameJs = runCatching {
            PreferenceProviders.get().getString(PreferKey.bookImportFileName)
        }.getOrNull()
        return BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, importFileNameJs)
    }

    override fun getUrlSuffix(name: String): String = io.legado.app.utils.getUrlSuffix(name, null)

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

}

/**
 * 读尽 [InputStream] 全部字节 (expect InputStream 无 readBytes, 手写累积)。
 *
 * [parent] 非空时每块前核资格: 真实父门贯通物化准备循环, 取消不必等整份读完。
 */
internal fun InputStream.readFully(parent: Job? = null): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buf = ByteArray(8192)
    while (true) {
        parent?.ensureActive()
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
internal fun FileBookAccessor.reopenFromArchive(book: Book, parent: Job? = null): InputStream? {
    val archiveRef = LocalBookLocators.get().getReadableArchiveRef(book) ?: return null
    return importFromArchive(archiveRef, book.originName, parent) { it.contains(book.originName) }
        .firstOrNull()?.let { getBookInputStream(it, parent) }
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
