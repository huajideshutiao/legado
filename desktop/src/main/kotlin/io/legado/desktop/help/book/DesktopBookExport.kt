package io.legado.desktop.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.service.ExportBookDeps
import io.legado.app.service.ExportBookEpubShared
import io.legado.app.service.ExportBookShared
import io.legado.app.service.ExportFileHandle
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.postEvent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面端导出书籍, 复用 shared [ExportBookShared] / [ExportBookEpubShared] 的导出主流程,
 * 平台差异 (文件写出 / 配置 / 正文处理 / 内置 EPUB 模板) 由本文件的 [DesktopExportBookDeps] 注入。
 */
object DesktopBookExport {

    private val shared by lazy { ExportBookShared(DesktopExportBookDeps) }
    private val epubShared by lazy { ExportBookEpubShared(DesktopExportBookDeps, shared) }

    /** 导出到 [dir] 目录, 每本书一个 txt (对照 app 端 ExportBookService 的 txt 分支)。 */
    suspend fun exportTxt(dir: String, books: List<Book>) {
        File(dir).mkdirs()
        books.forEach { shared.exportTxt(dir, it) }
    }

    /** 导出到 [dir] 目录, 每本书一个 epub (对照 app 端 ExportBookService 的 epub 分支)。 */
    suspend fun exportEpub(dir: String, books: List<Book>) {
        File(dir).mkdirs()
        books.forEach { epubShared.exportEpub(dir, it) }
    }

    /** 导出图片书到 [dir] 目录, 每本书一个 cbz (对照 app 端 ExportBookService 的 cbz 分支)。 */
    suspend fun exportCbz(dir: String, books: List<Book>) {
        File(dir).mkdirs()
        books.forEach { book ->
            val chapters = AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
            epubShared.exportCbz(dir, book, chapters)
        }
    }
}

private object DesktopExportBookDeps : ExportBookDeps {

    private val prefs get() = PreferenceProviders.get()

    override val exportCharset: String get() = AppConfigProviders.get().exportCharset
    override val exportToWebDav: Boolean get() = prefs.getBoolean(PreferKey.exportToWebDav, false)
    override val exportUseReplace: Boolean get() = prefs.getBoolean(PreferKey.exportUseReplace, true)
    override val exportNoChapterName: Boolean get() = prefs.getBoolean(PreferKey.exportNoChapterName, false)

    override fun getExportFileName(book: Book, suffix: String): String = book.getExportFileName(suffix)

    override fun getExportFileName(book: Book, suffix: String, index: Int): String =
        book.getExportFileName(suffix, index)

    override suspend fun getCoverImageBytes(book: Book): ByteArray? = null

    override fun getBuiltinAsset(assetPath: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(assetPath)?.use { it.readBytes() }
            ?: File("app/src/main/assets", assetPath).takeIf { it.isFile }?.readBytes()
            ?: error("内置 EPUB 资源不存在: " + assetPath)

    override fun listTemplateFiles(dirPath: String): List<ExportBookEpubShared.TemplateFileInfo>? {
        val assetDir = File(dirPath, "Asset").takeIf { it.isDirectory } ?: return null
        return assetDir.listFiles()?.map { it.toTemplateFileInfo() } ?: emptyList()
    }

    override fun getTitleReplaceRules(book: Book): List<ReplaceRule> =
        ContentProcessorProviders.get().getTitleReplaceRules(book)

    override fun strImgCover(): String = jvmGetString("img_cover")

    override fun strBookIntro(): String = jvmGetString("book_intro")

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

    // 导出进度/消息: 桌面端无通知栏, 按百分比步进 toast + AppLog 落日志
    // (app 端是 Service 通知栏进度)。TXT/CBZ 的 progress 是章节下标 (0..total-1),
    // 用章节总数换算百分比; EPUB save2Drive 阶段 progress 已是 0..100, 直接透传。
    private val exportTotals = ConcurrentHashMap<String, Int>()
    private val exportLastPercent = ConcurrentHashMap<String, Int>()

    private fun exportPercentOf(bookUrl: String, progress: Int): Int {
        val total = exportTotals.computeIfAbsent(bookUrl) {
            runCatching {
                runBlocking { AppDbProviders.get().bookChapterDao.getChapterCount(bookUrl) }
            }.getOrDefault(0)
        }
        return if (total > 0 && progress <= total) {
            (progress * 100 / total).coerceIn(0, 100)
        } else {
            // progress 本身已是 0..100 百分比 (EPUB save2Drive 分支)
            progress.coerceIn(0, 100)
        }
    }

    override fun removeExportProgress(bookUrl: String) {
        exportTotals.remove(bookUrl)
        exportLastPercent.remove(bookUrl)
    }

    override fun setExportProgress(bookUrl: String, progress: Int) {
        val percent = exportPercentOf(bookUrl, progress)
        val last = exportLastPercent.getOrDefault(bookUrl, -1)
        if (percent - last >= 20 || (percent >= 100 && last < 100)) {
            exportLastPercent[bookUrl] = percent
            AppLog.put("导出进度 $percent% (${bookUrl.substringAfterLast('/').take(20)})")
            Toasters.get().toast("导出进度 $percent%")
        }
    }

    override fun setExportMsg(bookUrl: String, msg: String) {
        Toasters.get().toast(msg)
    }

    override fun removeExportMsg(bookUrl: String) {
        // 每本书导出开始时调用: 顺带重置该书的进度缓存
        removeExportProgress(bookUrl)
    }

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

    private fun File.toTemplateFileInfo(): ExportBookEpubShared.TemplateFileInfo =
        ExportBookEpubShared.TemplateFileInfo(
            name = name,
            isDir = isDirectory,
            children = if (isDirectory) {
                listFiles()?.map { it.toTemplateFileInfo() } ?: emptyList()
            } else {
                emptyList()
            },
            readText = { readText() },
            readBytes = { readBytes() },
        )
}
