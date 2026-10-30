package io.legado.desktop.model.fileBook

import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.requireBookSaveDir
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.OLD_COVERS_REF_SEGMENT
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.file.desktopAppCacheDir
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.file.desktopResolveStoredRef
import io.legado.app.help.file.desktopStoredLocalRef
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.BaseFileBook
import io.legado.app.model.fileBook.BookNameAuthorAnalyzer
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBookAccessor
import io.legado.app.model.fileBook.FileBookLocalImportShared
import io.legado.app.model.fileBook.FileBookProviders
import io.legado.app.model.fileBook.TextFile
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.InputStream
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.atomicWriteTempPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Paths
import java.util.Locale

/**
 * 压缩包解压能力三件套 (校验格式/解压/列条目)。
 *
 * :desktop 以 DesktopArchiveCodec (commons-compress + junrar) 实现; headless 不注入,
 * 相关操作显式报错 —— 保证 txt/epub/cbz 基础导入链路不背重依赖。
 */
interface DesktopArchiveSupport {
    fun checkArchive(name: String)
    fun unArchive(archiveFile: File, destDir: File, filter: ((String) -> Boolean)?): List<File>
    fun getFilesName(archiveFile: File, filter: ((String) -> Boolean)?): List<String>
}

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
 * - 压缩包: 无 native libarchive, 走 [DesktopArchiveSupport] 注入实现 (:desktop 为
 *   DesktopArchiveCodec, commons-compress + junrar); 7z 的 BCJ2 编码不支持
 *   (commons-compress 限制), 其余格式对齐。headless 不注入 → 压缩包导入/解压显式报错
 * - PDF: 注入 [BaseFileBook] 处理器 (:desktop 传共享 PdfFileShared 骨架, 渲染后端
 *   DesktopPdfRenderPort 经 PdfRenderProviders 注册) 替代。headless 不注入 → PDF 书读取显式报错
 * - 文件保存: 落用户在"书籍保存位置"选定的目录 (与 Android 端 `defaultBookTreeUri` 同语义;
 *   未设置时由调用链既有的"去设置目录"出口拉起目录选择器); 落库引用走
 *   [desktopStoredLocalRef] (外部目录存 `file:` URI, 数据根下的文件存相对引用)
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor]。
 */
class DesktopFileBookAccessor(
    /** 压缩包解压能力; null = 不支持 (headless, commons-compress/junrar 重依赖留 :desktop) */
    private val archiveSupport: DesktopArchiveSupport? = null,
    /** PDF 书处理器 ([BaseFileBook], :desktop 传共享 PdfFileShared); null = 不支持 */
    private val pdfHandler: BaseFileBook? = null,
) : FileBookAccessor {

    /** 解压临时目录名 (对齐 app 端 `ArchiveUtils.TEMP_FOLDER_NAME`)。 */
    private val archiveTempFolderName = "ArchiveTemp"

    /** 本地书与遗留封面目录: `{desktopAppRootDir}/covers` (删除旧封面等文件操作经 desktopResolveStoredRef 解析)。 */
    private val coversDir: String = Paths.get(desktopAppRootDir(), "covers").toString()

    /**
     * 有效书籍保存目录 (用户在"书籍保存位置"选定)。
     * 未设置时 [requireBookSaveDir] 抛原版同款异常, 由调用链既有的"去设置目录"出口
     * 拉起目录选择器; 写入前按需创建 (选定目录可能还不存在)。
     */
    private fun bookSaveDir(): File = File(requireBookSaveDir()).apply { mkdirs() }

    /**
     * 本地书提取封面相对引用 (`oldCovers/{md516}.jpg`, 物理目录 `{desktopAppRootDir}/covers`,
     * 对齐 app 端 externalFiles/covers; 经 [io.legado.app.help.config.resolveImagePath]
     * 解析, 便携移动后仍有效)。
     */
    override fun getCoverPath(bookUrl: String): String =
        "$OLD_COVERS_REF_SEGMENT/${MD5Utils.md5Encode16(bookUrl)}.jpg"

    override fun getHandler(book: Book): BaseFileBook {
        // 分派逻辑对齐 app 端 FileBookAccessorImpl.getHandler (PDF 书 → 共享 PdfFileShared 骨架)
        val originName = book.originName.lowercase(Locale.getDefault())
        return when {
            book.isPdf -> pdfHandler
                ?: throw UnsupportedOperationException("PDF 书读取需注入 pdfHandler (PDFBox 实现留 :desktop), headless 不支持")
            book.isEpub -> EpubFile
            book.isLocal && (originName.endsWith(".cbz") || originName.endsWith(".zip") && book.isImage) -> CbzFile
            else -> TextFile
        }
    }

    /**
     * 同步叶子的下载回退: 本方法要返回 InputStream, 只能阻塞等下载完成。
     *
     * 阻塞形态不变, 但**不**自建无父作用域: [parent] 是本页真实请求 Job (取消即停下这次回退),
     * 没有可归属的请求时 (纯解析入口) 传 null, 本叶自己当根, 不去借页面外的 Job。
     * [FileBook.downloadRemoteBook] 本身是挂起函数, 挂起链一律直接调它, 不走这条。
     */
    private fun downloadRemoteBookBlocking(book: Book, parent: Job?): Boolean =
        runBlocking(parent ?: kotlin.coroutines.EmptyCoroutineContext) {
            FileBook.downloadRemoteBook(book)
        }

    override fun getBookInputStream(book: Book, parent: Job?): InputStream {
        // 对齐 app 端 FileBookAccessorImpl.getBookInputStream: 文件失效时回退
        // "重新解压压缩包" → "下载远程文件", 全失败才抛 FileNotFoundException
        val inputStream = openLocalFile(book) ?: run {
            LocalBookLocators.get().removeLocalPathCache(book)
            val archiveFile = findArchiveFile(book)
            val webDavUrl = book.getRemoteUrl()
            if (archiveFile != null) {
                importFromArchive(archiveFile.toURI().toString(), book.originName, parent) {
                    it.contains(book.originName)
                }.firstOrNull()?.let { getBookInputStream(it, parent) }
            } else if (webDavUrl != null && downloadRemoteBookBlocking(book, parent)) {
                getBookInputStream(book, parent)
            } else {
                null
            }
        }
        if (inputStream != null) return inputStream
        LocalBookLocators.get().removeLocalPathCache(book)
        throw FileNotFoundException("${book.bookUrl} 文件不存在")
    }

    /**
     * 打开 bookUrl 指向的本地文件; 引用失效时由 [LocalBookLocators.get().getReadableFileRef]
     * 按文件名在有效书籍保存目录与导入目录里找回 (只定位, 不回写 bookUrl、不写路径缓存)。
     */
    private fun openLocalFile(book: Book): InputStream? {
        val path = runCatching { LocalBookLocators.get().getReadableFileRef(book) }.getOrNull()
            ?: return null
        return File(path).inputStream()
    }

    /** 压缩包子书: 在 books 目录按 archiveName 找回原压缩包 (对齐 app 端 `Book.getArchiveUri`)。 */
    private fun findArchiveFile(book: Book): File? {
        if (!book.isArchive) return null
        val name = runCatching { book.archiveName }.getOrNull() ?: return null
        val dir = effectiveBookSaveDir() ?: return null
        return File(dir, name).takeIf { it.isFile }
    }

    override fun getLastModified(book: Book): Result<Long> {
        return runCatching {
            resolveLocalFile(book.bookUrl).lastModified()
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
        val archiveFile = resolveLocalFile(archiveFileUri)
        // 每次导入独占工作目录; 不与同 URI 的并发/历史解包共享 md5 槽
        val workDir = File(
            desktopAppCacheDir(),
            archiveTempFolderName + "/req-" + java.util.UUID.randomUUID(),
        )
        try {
        // 对齐 app 端 ArchiveUtils.deCompress: 按 filter 过滤条目, 解到目标目录, 返回文件列表
        val files = deCompress(archiveFile, workDir, filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appString(AppStringKey.unsupport_archivefile_entry))
        }
        val result = arrayListOf<Book>()
        files.forEach { file ->
            parent?.ensureActive()
            // 包内批次按原合同: 任一失败即停批 (与 map 一致); 独立外部请求各自继续
            val book = saveBookFile(file.inputStream(), saveFileName ?: file.name).let { uriStr ->
                importLocalFile(uriStr, parent).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFile.name}"
                    addType(BookType.archive)
                }
            }
            runBlocking(parent ?: kotlin.coroutines.EmptyCoroutineContext) {
                AppDbProviders.get().bookDao.update(book)
            }
            result.add(book)
        }
        return result
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * 解压到每请求独占目录并返回解出的文件。
     *
     * 对齐 app 端 `ArchiveUtils.deCompress` + `LibArchiveUtils.unArchive` 语义
     * (md5(uri) 命名工作目录 / 路径穿越校验 / filter 过滤条目), 底层换
     * [DesktopArchiveCodec] (commons-compress + junrar) 替代 native libarchive。
     */
    private fun deCompress(
        archiveFile: File,
        destDir: File,
        filter: ((String) -> Boolean)?
    ): List<File> {
        val codec = archiveSupport
            ?: throw UnsupportedOperationException("压缩包解压需注入 archiveSupport (commons-compress/junrar 留 :desktop), headless 不支持")
        codec.checkArchive(archiveFile.name)
        destDir.mkdirs()
        return codec.unArchive(archiveFile, destDir, filter)
    }

    /**
     * 导入本地文件 (原 `FileBook.importLocalFile(FileDoc)`)。
     *
     * 判型 / 构造 Book / 落库在 [FileBookLocalImportShared] (四端唯一一份), 此处只取平台文件信息。
     * 压缩包判据仍用本端解包能力 ([getArchiveFilesName], 解包器未注入时由其报错)。
     */
    override fun importLocalFile(uriStr: String): Book = importLocalFile(uriStr, null)

    override fun importLocalFile(uriStr: String, parent: kotlinx.coroutines.Job?): Book =
        runBlocking(parent ?: kotlin.coroutines.EmptyCoroutineContext) {
        val file = resolveLocalFile(uriStr)
        FileBookLocalImportShared.importLocalFile(
            accessor = this@DesktopFileBookAccessor,
            ref = uriStr,
            fileName = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            // 存储引用: 数据根下的文件 (WebDAV 下载/压缩包子书落 books/) 存相对引用,
            // 外部任意的文件存 file: URI (与旧行为一致), 便携移动程序目录后均有效
            storedRef = desktopStoredLocalRef(file),
            archiveHasBookFile = { ref ->
                getArchiveFilesName(resolveLocalFile(ref)).any { FileBook.isBookFile(it) }
            },
            parent = parent,
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
        val codec = archiveSupport
            ?: throw UnsupportedOperationException("压缩包解压需注入 archiveSupport (commons-compress/junrar 留 :desktop), headless 不支持")
        codec.checkArchive(archiveFile.name)
        return runCatching {
            codec.getFilesName(archiveFile, null)
        }.getOrElse { emptyList() }
    }

    override fun deleteBook(book: Book, deleteOriginal: Boolean) {
        // 对齐 app 端 FileBook.deleteBook: 章节缓存与封面在应用私有区, 删不掉不影响用户的源文件,
        // 尽力而为即可; 源文件删不掉则抛出原因 (见 deleteSourceFile), 由上层如实提示。
        runCatching {
            BookStorageProviders.get().clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtilsBase.delete(desktopResolveStoredRef(book.coverUrl!!).absolutePath)
            }
        }
        if (!deleteOriginal) return
        deleteSourceFile(book)
    }

    /**
     * 删除本地源文件 (bookUrl 是存储引用 / file: URI / 绝对路径, 解析成真实路径再删)。
     *
     * 引用为空、引用解析成空路径、以及解析结果落在文件系统根目录三种情况一律拒绝删除并报出
     * 原因: 三者都不指向这本书的源文件 (空引用的解析结果是进程工作目录 `File("").absolutePath`),
     * 而这里对目录的删除是递归的。判定必须发生在删除之前 —— 删除不可撤销, 事后才发现解析结果
     * 是根目录, 波及的文件已经没了。
     *
     * 删不掉时抛出原因: 库里记录已删、文件还在磁盘上, 上层据此提示书名与原因。文件本就不在时
     * 删除意图已经达成, 不抛。
     */
    private fun deleteSourceFile(book: Book) {
        val ref = book.bookUrl
        if (ref.isBlank()) throw IOException("未能删除源文件: 书籍文件引用为空")
        val file = resolveLocalFile(ref)
        if (file.path.isBlank()) throw IOException("未能删除源文件: 书籍文件引用为空")
        val path = file.absolutePath
        if (isFileSystemRoot(path)) throw IOException("未能删除源文件: $path 是文件系统根目录")
        if (FileUtilsBase.delete(path)) return
        if (File(path).exists()) throw IOException("未能删除源文件: $path")
    }

    /** [path] 是否文件系统根目录 (`/`、`C:\` 这类没有父目录的路径): 对它的删除会波及整盘。 */
    private fun isFileSystemRoot(path: String): Boolean {
        val file = File(path)
        return runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile).parentFile == null
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
        return saveBookFile(inputStream, fileName, currentCoroutineContext()[Job])
    }

    override fun saveBookFile(
        inputStream: InputStream,
        fileName: String,
        parent: Job?,
    ): String {
        // desktop 无 SAF, 用有效书籍保存目录 + java.io.File (app 端走 defaultBookTreeUri + DocumentFile);
        // 返回存储引用: 数据根下存相对引用 (`books/x.epub`, 便携移动后有效), 兼容旧 file: URI
        //
        // 落盘协议与 app 端同一条: 目录可用性先行探测 (探不出 = 目录被删/无写权限 -> 权限类提示),
        // 内容先整份写同目录临时件再原子改名就位 —— 目标名在写入期间始终是完整内容;
        // 传输错误原样透传, 不再统一包装成权限失败 (那会把网络/取消说成目录问题)。
        //
        // 整个保存过程 (含目录解析与探测) 都在 use 内: 保存入口的消费契约是"进来就把流吃掉",
        // 目录未设置/探测失败抛异常时也必须关闭调用方交来的流。
        inputStream.use { input ->
            val file = File(bookSaveDir(), fileName)
            val probe = File(atomicWriteTempPath(file.absolutePath))
            try {
                FileOutputStream(probe).close()
            } catch (e: Exception) {
                throw SecurityException(
                    "请重新设置书籍保存位置\nPermission Denial\n$e"
                ).apply {
                    addSuppressed(e)
                }
            } finally {
                probe.delete()
            }
            // 复用既有整份写入原语 (准备循环每块前与最终替换前都核父), 不再自建等价拷贝
            copyToFileAtomically(file.absolutePath, input, parent)
            return desktopStoredLocalRef(file)
        }
    }

    /** web 上传临时文件落盘 (对齐 app 端: 委托 inputStream 重载)。 */
    override fun saveBookFileFromPath(filePath: String, fileName: String): String =
        // web 上传通道没有可归属的页面请求: 父门显式 null
        saveBookFile(File(filePath).inputStream(), fileName, null)

    /**
     * 流式保存 WebDav 远端书籍文件 ("下载到本地" / "刷新远端更新" 链路)。
     *
     * desktop 无 SAF: 交给既有的 [WebDav.downloadTo] (savedPath, replaceExisting)
     * 边下边写有效书籍保存目录下的 `{fileName}` (用户在"书籍保存位置"选定),
     * 返回引用形式与
     * [saveBookFile] (inputStream 重载) 一致 ([desktopStoredLocalRef])。
     *
     * 传输中途失败 (含取消) 时目标名上不会留下半截书: downloadTo 边下边写的是目标同目录的
     * 临时件 (共享整份写入契约), 整份写完才改名就位 —— 目标要么是原有的完整内容, 要么是
     * 完整的新内容 (或不存在)。
     *
     * 异常口径与 app 端对齐: 保存位置未设置时抛
     * [io.legado.app.exception.InvalidBooksDirException]; 已设置但不可写 (目录被删/无写权限)
     * 报权限类提示 (`SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e")`),
     * 其余异常照旧透传。
     */
    override suspend fun saveBookFileFromWebDav(webDav: WebDav, fileName: String): String {
        val file = File(bookSaveDir(), fileName)
        // 分辨"保存位置不可用"与网络/传输异常: 前者按 app 端文件路径分支的口径给出权限类提示,
        // 后者原样透传。落盘要在目标目录里建临时件再改名, 所以探的是"能否在目标目录里建出
        // 临时件" (名字用共享契约生成, 探完即删); 探目标文件本身会在下载失败时留下 0 字节的同名文件。
        val probe = File(atomicWriteTempPath(file.absolutePath))
        try {
            FileOutputStream(probe).close()
        } catch (e: Exception) {
            throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                addSuppressed(e)
            }
        } finally {
            probe.delete()
        }
        // 进入下载前与收尾前核父资格: 取消不再白下整份
        currentCoroutineContext()[Job]?.ensureActive()
        webDav.downloadTo(file.absolutePath, true)
        currentCoroutineContext()[Job]?.ensureActive()
        return desktopStoredLocalRef(file)
    }

    /** 保存引用 → bookUrl 存储引用 (桌面保存返回值已是存储引用, 恒等)。 */
    override fun normalizeBookRef(uriStr: String): String = uriStr

    override fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        // 纯逻辑走下沉的 BookNameAuthorAnalyzer; app 端 AppConfig.bookImportFileName 是
        // Android 扩展, 桌面端读同一 PreferKey (DesktopPreferenceProvider)
        val importFileNameJs = runCatching {
            PreferenceProviders.get().getString(PreferKey.bookImportFileName)
        }.getOrNull()
        return BookNameAuthorAnalyzer.analyzeNameAuthor(fileName, importFileNameJs)
    }

    /** 获取合法的文件后缀 (对齐 app 端: 复用 [UrlUtil.getSuffix], 含合法性校验 + "ext" 兜底)。 */
    override fun getUrlSuffix(name: String): String = UrlUtil.getSuffix(name)

    /** 解析 bookUrl 为 [File] (与 LocalEpubResource.jvm.kt 的 resolveLocalFile 逻辑一致)。 */
    private fun resolveLocalFile(bookUrl: String): File = resolveLocalBookFile(bookUrl)
}

/** 解析 bookUrl 为 [File] (存储引用 / file: URI / 裸绝对路径, 委托 [desktopResolveStoredRef])。 */
fun resolveLocalBookFile(bookUrl: String): File = desktopResolveStoredRef(bookUrl)

/**
 * 桌面端启动早期注册 [FileBookProviders]。
 *
 * 调用时机: desktop/headless `main()` 中, 在任何 EpubFile / FileBook 调用之前。
 * 压缩/PDF 能力由调用方按需注入 (:desktop 传全量, headless 传 null 显式降级)。
 *
 * 模式参考 [io.legado.desktop.help.book.DesktopBookHelpAccessor] 的注册方式。
 */
fun registerDesktopFileBookAccessor(
    archiveSupport: DesktopArchiveSupport?,
    pdfHandler: BaseFileBook?,
) {
    FileBookProviders.register(DesktopFileBookAccessor(archiveSupport, pdfHandler))
}
