package io.legado.desktop.help.book

import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.service.ExportBookDeps
import io.legado.app.service.ExportBookShared
import io.legado.app.service.ExportFileHandle
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.postEvent
import java.io.File

/**
 * 桌面端导出书籍 (TXT), 复用 shared [ExportBookShared] 的导出主流程,
 * 平台差异 (文件写出 / 配置 / 正文处理) 由本文件的 [DesktopExportBookDeps] 注入。
 *
 * 未实现 EPUB / CBZ: shared 只下沉了 TXT 分支 (EPUB 依赖未下沉的 Glide/assets/FileDoc)。
 */
object DesktopBookExport {

    private val shared by lazy { ExportBookShared(DesktopExportBookDeps) }

    /** 导出到 [dir] 目录, 每本书一个 txt (对照 app 端 ExportBookService 的 txt 分支)。 */
    suspend fun exportTxt(dir: String, books: List<Book>) {
        File(dir).mkdirs()
        books.forEach { shared.exportTxt(dir, it) }
    }
}

private object DesktopExportBookDeps : ExportBookDeps {

    private val prefs get() = PreferenceProviders.get()

    override val exportCharset: String get() = AppConfigProviders.get().exportCharset
    override val exportToWebDav: Boolean get() = prefs.getBoolean(PreferKey.exportToWebDav, false)
    override val exportUseReplace: Boolean get() = prefs.getBoolean(PreferKey.exportUseReplace, true)
    override val exportNoChapterName: Boolean get() = prefs.getBoolean(PreferKey.exportNoChapterName, false)

    override fun getExportFileName(book: Book, suffix: String): String = book.getExportFileName(suffix)

    override fun prepareExportFile(dirPath: String, filename: String): ExportFileHandle {
        val dir = File(dirPath).apply { mkdirs() }
        val file = File(dir, filename)
        if (file.exists()) file.delete()
        file.createNewFile()
        return ExportFileHandle(file.outputStream().buffered(), file.absolutePath)
    }

    override fun deleteExportUri(uri: String) {
        runCatching { File(uri).delete() }
    }

    // 桌面端无导出进度/消息面板 (app 端是通知栏 + Service 状态 Map), 留空
    override fun removeExportProgress(bookUrl: String) = Unit
    override fun setExportProgress(bookUrl: String, progress: Int) = Unit
    override fun setExportMsg(bookUrl: String, msg: String) = Unit
    override fun removeExportMsg(bookUrl: String) = Unit

    // uri 即本地文件绝对路径 (见 prepareExportFile), 直接交给 WebDav 上传
    override suspend fun exportToWebDav(uri: String, filename: String) =
        AppWebDavShared.exportWebDav(uri, filename)

    override fun strAuthorShow(author: String): String = jvmGetString("author_show", author)
    override fun strIntroShow(intro: String): String = jvmGetString("intro_show", intro)

    override fun postExportEvent(bookUrl: String) {
        postEvent(EventBus.EXPORT_BOOK, bookUrl)
    }

    override fun processContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean,
        chineseConvert: Boolean,
        reSegment: Boolean
    ): CharSequence = ContentProcessorProviders.get().getBookContent(
        book = book,
        chapter = chapter,
        content = content,
        includeTitle = includeTitle,
        useReplace = useReplace,
        chineseConvert = chineseConvert,
        reSegment = reSegment,
    ).textList.joinToString("\n")
}
