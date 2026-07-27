package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.lib.webdav.WebDav
import io.legado.app.utils.InputStream
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 书籍文件导入 目录正文解析
 * 支持在线文件(txt epub 压缩文件 本地文件
 *
 * # KMP 化说明
 *
 * 原 app 端 FileBook object 重 Android 依赖 (android.net.Uri / DocumentFile / FileDoc /
 * ArchiveUtils / AnalyzeUrl / WebDav / RemoteBook / GSON / BookHelp.formatBookAuthor /
 * appCtx / FileUtils 等), 本 commonMain 版仅保留纯逻辑:
 * - **纯逻辑 (本类直接实现)**: [isBookFile] / [getChapterList] / [getContent] /
 *   [upBookInfo] / [getImage] / [WebFile] / [clear]
 * - **平台专属 (委托 [FileBookProviders])**: [getHandler] / [importFromArchive] /
 *   [importLocalFile] / [deleteBook] / [saveBookFile] / [downloadRemoteBook] /
 *   [getCoverPath] / [mergeBook] / [analyzeNameAuthor]
 *
 * 原 Uri 参数/返回值改用 String (Uri.toString()); app 端 [io.legado.app.model.fileBook.FileBookAccessorImpl]
 * 内部 String↔Uri 转换, 行为与下沉前一致。app 端调用方通过扩展函数
 * (FileBookExtensions.kt) 保留原 Uri 重载签名, 调用方零改动。
 *
 * System.currentTimeMillis() → [systemCurrentTimeMillis] (expect/actual);
 * ContentProcessor.get(book) → [ContentProcessorProviders.get].getTitleReplaceRules(book);
 * appDb → [AppDbProviders.get] (仅 importBook 内部用, 已委托 accessor)。
 *
 * TextFile/EpubFile/CbzFile/PdfFile 由其他子代理下沉到同包, 引用路径不变。
 */
object FileBook : BaseFileBook {

    private val accessor get() = FileBookProviders.get()

    fun Book.getHandler(): BaseFileBook = accessor.getHandler(this)

    fun isBookFile(fileName: String): Boolean = AppPattern.bookFileRegex.matches(fileName)

    @Throws(TocEmptyException::class)
    override fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = book.getHandler().getChapterList(book)
        if (chapters.isEmpty()) {
            throw TocEmptyException(appString(AppStringKey.chapter_list_empty))
        }
        val list = ArrayList(LinkedHashSet(chapters))
        val replaceRules = ContentProcessorProviders.get().getTitleReplaceRules(book)
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
        book.latestChapterTime = systemCurrentTimeMillis()
        return list
    }

    override fun getContent(book: Book, chapter: BookChapter): String? {
        return try {
            book.getHandler().getContent(book, chapter)
        } catch (e: Exception) {
            "获取本地书籍内容失败\n${e.localizedMessage}".also { AppLog.put(it, e) }
        }
    }

    override fun upBookInfo(book: Book) = book.getHandler().upBookInfo(book)
    override fun getImage(book: Book, href: String) = book.getHandler().getImage(book, href)

    /* 导入压缩包内的书籍 */
    fun importFromArchive(
        archiveFileUri: String, saveFileName: String? = null, filter: ((String) -> Boolean)? = null
    ): List<Book> = accessor.importFromArchive(archiveFileUri, saveFileName, filter)

    fun importLocalFile(uriStr: String): Book = accessor.importLocalFile(uriStr)

    fun deleteBook(book: Book, deleteOriginal: Boolean) =
        accessor.deleteBook(book, deleteOriginal)

    //文件类书源 合并在线书籍信息 在线 > 本地
    fun mergeBook(localBook: Book, onLineBook: Book?): Book =
        accessor.mergeBook(localBook, onLineBook)

    //下载book对应的远程文件 并更新Book
    fun downloadRemoteBook(book: Book): Boolean = accessor.downloadRemoteBook(book)

    /**
     * 导入远程书籍 (对应原 `FileBook.importRemoteBook`)。
     *
     * 原 RemoteBook 参数拍平为 name/path/size/lastModify 基本类型
     * (RemoteBook 依赖 appDb 构造, 未下沉 commonMain)。
     * app 端扩展 [io.legado.app.model.fileBook.FileBookExtensions.importRemoteBook]
     * 保留原 RemoteBook 重载签名, 调用方零改动。
     */
    suspend fun importRemoteBook(
        webDav: WebDav,
        serverID: Long?,
        name: String,
        path: String,
        size: Long,
        lastModify: Long,
        downloadFile: Boolean = false
    ): Book = accessor.importRemoteBook(webDav, serverID, name, path, size, lastModify, downloadFile)

    fun getCoverPath(bookUrl: String): String = accessor.getCoverPath(bookUrl)

    /**
     * 从文件分析书籍必要信息（书名 作者等）。
     *
     * 原 private 方法, 依赖 AppConfig.bookImportFileName / JsEngines / GSON /
     * BookHelp.formatBookAuthor (未下沉), 委托 [FileBookAccessor.analyzeNameAuthor]。
     */
    fun analyzeNameAuthor(fileName: String): Pair<String, String> =
        accessor.analyzeNameAuthor(fileName)

    data class WebFile(
        val url: String,
        val name: String,
    ) {
        override fun toString(): String = name

        val suffix: String get() = FileBookProviders.get().getUrlSuffix(name)

        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}
