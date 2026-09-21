package io.legado.app.model.fileBook

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.InputStream
import kotlin.concurrent.Volatile

/**
 * FileBook / BaseFileBook 跨平台只读访问接口。
 *
 * app 端 [io.legado.app.model.fileBook.FileBook] 与 [BaseFileBook] 重 Android 依赖
 * (android.net.Uri / DocumentFile / FileDoc / ArchiveUtils / AnalyzeUrl / WebDav /
 * RemoteBook / GSON / BookHelp.formatBookAuthor / appCtx / FileUtils 等), 无法直接下沉
 * shared/commonMain。本接口把这些 Android 专属操作抽象为 commonMain 可用的方法签名
 * (Uri 改用 String 传递, RemoteBook 字段拍平为基本类型参数), 由 app 端
 * [io.legado.app.model.fileBook.FileBookAccessorImpl] 包装原 FileBook 实现,
 * 在 App.onCreate 经 [FileBookProviders.register] 注册。
 *
 * commonMain 中的 [FileBook] object / [BaseFileBook] interface 仅保留纯逻辑
 * (isBookFile / getChapterList / getContent / upBookInfo / getImage / WebFile),
 * Android 专属方法委托本接口实现。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders] /
 * [io.legado.app.model.AudioPlayCommanders]。
 */
interface FileBookAccessor {

    // ---- BaseFileBook 默认实现 ----

    /**
     * 获取书籍输入流 (对应 BaseFileBook.getBookInputStream 默认实现)。
     *
     * 内部走 Uri.inputStream(appCtx) + 失败回退 importFromArchive / downloadRemoteBook,
     * 重 Android 依赖 (appCtx / Uri.inputStream) 留 app 端。
     */
    fun getBookInputStream(book: Book): InputStream

    /**
     * 获取书籍文件最后修改时间 (对应 BaseFileBook.getLastModified 默认实现)。
     *
     * 内部走 DocumentFile.fromSingleUri / File.lastModified, 重 Android 依赖留 app 端。
     */
    fun getLastModified(book: Book): Result<Long>

    // ---- FileBook 平台专属方法 ----

    /**
     * 获取书籍对应的文件解析器 (对应 `Book.getHandler()`)。
     *
     * 内部依据 [io.legado.app.data.entities.Book.isPdf] / isEpub / isLocal / isImage
     * (Book 扩展, app 端 BookExtensions.kt, 未下沉) 分派到 PdfFile / EpubFile / CbzFile / TextFile。
     */
    fun getHandler(book: Book): BaseFileBook

    /** 导入压缩包内的书籍 (对应 `FileBook.importFromArchive`)。Uri 传 String 形式。 */
    fun importFromArchive(
        archiveFileUri: String,
        saveFileName: String? = null,
        filter: ((String) -> Boolean)? = null
    ): List<Book>

    /** 导入本地文件 (对应 `FileBook.importLocalFile`)。Uri 传 String 形式。 */
    fun importLocalFile(uriStr: String): Book

    /** 删除书籍 (对应 `FileBook.deleteBook`)。 */
    fun deleteBook(book: Book, deleteOriginal: Boolean)

    /**
     * 下载并保存在线文件 (对应 `FileBook.saveBookFile(str, fileName, source)`)。
     * 原 Uri 返回值改为 String (uri.toString())。
     */
    suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource? = null
    ): String

    /**
     * 保存输入流到文件 (对应 `FileBook.saveBookFile(inputStream, fileName)`)。
     * 原 Uri 返回值改为 String (uri.toString())。
     */
    fun saveBookFile(inputStream: InputStream, fileName: String): String

    /**
     * 保存本地文件路径到书籍文件 (对应 `FileBook.saveBookFile(File(filePath).inputStream(), fileName)`)。
     *
     * BookController.addLocalBook web API 下沉新增: 原 app 端调用
     * `FileBook.saveBookFile(File(fileData).inputStream(), fileName)`, 其中 fileData 是 web 上传
     * 临时文件路径。commonMain 无法直接 `File(path).inputStream()` (java.io.File / kotlin.io
     * inputStream 扩展均为 JVM-only, 且 expect InputStream 与 java.io.FileInputStream 类型不兼容),
     * 故新增本重载, 由各平台 actual 自行完成 "路径 -> InputStream -> saveBookFile" 转换。
     *
     * default 实现抛 [UnsupportedOperationException]: desktop/iOS/鸿蒙端 web 上传文件场景本就
     * 不可用 (DesktopFileBookAccessor.saveBookFile 亦抛异常), 仅 app 端 override 真正实现。
     *
     * @param filePath web 上传临时文件路径 (原 addLocalBook 的 files["fileData"])
     * @param fileName 保存文件名
     * @return 保存后的文件 Uri 字符串 (uri.toString())
     */
    fun saveBookFileFromPath(filePath: String, fileName: String): String =
        throw UnsupportedOperationException("saveBookFileFromPath not supported on this platform")

    /** 下载远程书籍文件并更新 Book (对应 `FileBook.downloadRemoteBook`)。 */
    fun downloadRemoteBook(book: Book): Boolean

    /** 获取封面缓存路径 (对应 `FileBook.getCoverPath`)。 */
    fun getCoverPath(bookUrl: String): String

    /** 合并在线书籍信息 (对应 `FileBook.mergeBook`)。 */
    fun mergeBook(localBook: Book, onLineBook: Book?): Book

    /** 从文件名分析书名与作者 (对应 `FileBook.analyzeNameAuthor`, 原 private 方法)。 */
    fun analyzeNameAuthor(fileName: String): Pair<String, String>

    /**
     * 导入远程书籍 (对应 `FileBook.importRemoteBook`)。
     *
     * 原 RemoteBook 参数拍平为 name/path/size/lastModify 基本类型
     * (RemoteBook 依赖 appDb 构造, 未下沉 commonMain)。
     */
    suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean = false
    ): Book

    /**
     * 获取合法的文件后缀 (对应 `UrlUtil.getSuffix(name)`)。
     *
     * UrlUtil 在 jvmAndAndroidMain (依赖 CustomUrl + 正则), 未下沉 commonMain。
     * 供 [FileBook.WebFile] 计算后缀用。
     */
    fun getUrlSuffix(name: String): String
}

/**
 * [FileBookAccessor] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `FileBookProviders.get().importLocalFile(...)` 替代
 * 原 `FileBook.importLocalFile(...)`, 行为完全一致, 仅多一层 provider 间接。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders]。
 */
object FileBookProviders {

    @Volatile
    private var impl: FileBookAccessor? = null

    /** 宿主启动早期注册一次 (任何 FileBook 调用之前)。 */
    fun register(impl: FileBookAccessor) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): FileBookAccessor = impl ?: error("FileBookProviders not registered")
}
