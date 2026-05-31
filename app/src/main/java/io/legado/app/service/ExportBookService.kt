package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.epublib.domain.Author
import io.legado.app.lib.epublib.domain.Date
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.FileResourceProvider
import io.legado.app.lib.epublib.domain.LazyResource
import io.legado.app.lib.epublib.domain.LazyResourceProvider
import io.legado.app.lib.epublib.domain.Metadata
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.TOCReference
import io.legado.app.lib.epublib.epub.EpubWriter
import io.legado.app.lib.epublib.epub.EpubWriterProcessor
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.cnCompare
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.mapAsync
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * 导出书籍服务
 */
class ExportBookService : BaseService() {

    companion object {
        val exportProgress = ConcurrentHashMap<String, Int>()
        val exportMsg = ConcurrentHashMap<String, String>()
    }

    data class ExportConfig(
        val path: String,
        val type: String,
        val epubSize: Int = 1,
        val epubScope: String? = null
    )

    private val groupKey = "${appCtx.packageName}.exportBook"
    private val waitExportBooks = linkedMapOf<String, ExportConfig>()
    private var exportJob: Job? = null
    private var notificationContentText = appCtx.getString(R.string.service_starting)


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> kotlin.runCatching {
                val bookUrl = intent.getStringExtra("bookUrl")!!
                if (!exportProgress.contains(bookUrl)) {
                    val exportConfig = ExportConfig(
                        path = intent.getStringExtra("exportPath")!!,
                        type = intent.getStringExtra("exportType")!!,
                        epubSize = intent.getIntExtra("epubSize", 1),
                        epubScope = intent.getStringExtra("epubScope")
                    )
                    waitExportBooks[bookUrl] = exportConfig
                    exportMsg[bookUrl] = getString(R.string.export_wait)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                    export()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }

            IntentAction.stop -> {
                notificationManager.cancel(NotificationId.ExportBook)
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        exportProgress.clear()
        exportMsg.clear()
        waitExportBooks.keys.forEach {
            postEvent(EventBus.EXPORT_BOOK, it)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupSummary(true)
        startForeground(NotificationId.ExportBookService, notification.build())
    }

    private fun upExportNotification(finish: Boolean = false) {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_export)
            .setSubText(getString(R.string.export_book))
            .setContentIntent(activityPendingIntent<BookshelfManageActivity>("bookshelfManageActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText(notificationContentText)
            .setDeleteIntent(servicePendingIntent<ExportBookService>(IntentAction.stop))
            .setGroup(groupKey)
            .setOnlyAlertOnce(true)
        if (!finish) {
            notification.setOngoing(true)
            notification.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<ExportBookService>(IntentAction.stop)
            )
        }
        notificationManager.notify(NotificationId.ExportBook, notification.build())
    }

    private fun export() {
        if (exportJob?.isActive == true) {
            return
        }
        exportJob = lifecycleScope.launch(IO) {
            while (isActive) {
                val (bookUrl, exportConfig) = waitExportBooks.entries.firstOrNull() ?: let {
                    notificationContentText = "导出完成"
                    upExportNotification(true)
                    stopSelf()
                    return@launch
                }
                exportProgress[bookUrl] = 0
                waitExportBooks.remove(bookUrl)
                val book = appDb.bookDao.getBook(bookUrl)
                try {
                    book ?: throw NoStackTraceException("获取${bookUrl}书籍出错")
                    val chapters = ensureChapterList(book)
                    notificationContentText = getString(
                        R.string.export_book_notification_content,
                        book.name,
                        waitExportBooks.size
                    )
                    upExportNotification()
                    when (exportConfig.type) {
                        "epub" -> {
                            if (exportConfig.epubScope.isNullOrBlank()) {
                                exportEpub(exportConfig.path, book)
                            } else {
                                CustomExporter(
                                    exportConfig.epubScope,
                                    exportConfig.epubSize
                                ).export(exportConfig.path, book)
                            }
                        }

                        "cbz" -> exportCbz(exportConfig.path, book, chapters)
                        else -> exportTxt(exportConfig.path, book)
                    }
                    exportMsg[book.bookUrl] = getString(R.string.export_success)
                } catch (e: Throwable) {
                    ensureActive()
                    exportMsg[bookUrl] = e.localizedMessage ?: "ERROR"
                    AppLog.put("导出书籍<${book?.name ?: bookUrl}>出错", e)
                } finally {
                    exportProgress.remove(bookUrl)
                    postEvent(EventBus.EXPORT_BOOK, bookUrl)
                }
            }
        }
    }

    private fun ensureChapterList(book: Book): List<BookChapter> {
        if (book.isLocalModified()) {
            runCatching { FileBook.getChapterList(book) }.onSuccess {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*it.toTypedArray())
                appDb.bookDao.update(book)
                ReadBook.onChapterListUpdated(book)
            }
        }
        var list = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (list.isEmpty() && book.isLocal) {
            runCatching { FileBook.getChapterList(book) }.onSuccess { fresh ->
                if (fresh.isNotEmpty()) {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*fresh.toTypedArray())
                    appDb.bookDao.update(book)
                    list = fresh
                }
            }
        }
        return list
    }

    private suspend fun exportTxt(path: String, book: Book) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportTxt(fileDoc, book)
    }

    private suspend fun exportTxt(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("txt")
        fileDoc.find(filename)?.delete()

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        try {
            val charset = Charset.forName(AppConfig.exportCharset)
            bookDoc.openOutputStream().getOrThrow().bufferedWriter(charset).use { bw ->
                getAllContents(book) { text ->
                    bw.write(text)
                }
            }
            if (AppConfig.exportToWebDav) {
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { bookDoc.delete() }
            throw e
        }
    }

    private suspend fun getAllContents(
        book: Book,
        append: (text: String) -> Unit
    ) = coroutineScope {
        val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val qy = "${book.name}\n${
            getString(R.string.author_show, book.getRealAuthor())
        }\n${
            getString(
                R.string.intro_show,
                "\n" + HtmlFormatter.format(book.getDisplayIntro())
            )
        }"
        append(qy)
        flow {
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsync(AppConst.MAX_THREAD) { chapter ->
            getExportData(book, chapter, contentProcessor, useReplace)
        }.collectIndexed { index, result ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            append.invoke(result)
        }

    }

    private fun getExportData(
        book: Book,
        chapter: BookChapter,
        contentProcessor: ContentProcessor,
        useReplace: Boolean
    ): String {
        val content = BookHelp.getContent(book, chapter)
        val content1 = contentProcessor
            .getContent(
                book,
                // 不导出vip标识
                chapter.apply { isVip = false },
                content ?: if (chapter.isVolume) "" else "null",
                includeTitle = !AppConfig.exportNoChapterName,
                useReplace = useReplace,
                chineseConvert = false,
                reSegment = false
            ).toString()
        return "\n\n$content1"
    }

    /**
     * 导出图片书为 cbz
     */
    private suspend fun exportCbz(path: String, book: Book, chapters: List<BookChapter>) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportCbz(fileDoc, book, chapters)
    }

    private suspend fun exportCbz(
        fileDoc: FileDoc,
        book: Book,
        chapters: List<BookChapter>
    ) = coroutineScope {
        if (chapters.isEmpty()) {
            throw NoStackTraceException("书籍<${book.name}>未找到章节信息")
        }
        val filename = book.getExportFileName("cbz")
        fileDoc.find(filename)?.delete()
        val bookDoc = fileDoc.createFileIfNotExist(filename)
        try {
            var totalWritten = 0
            bookDoc.openOutputStream().getOrThrow().buffered().use { os ->
                ZipOutputStream(os).use { zos ->
                    // 图片本身已经是压缩格式，再 deflate 几乎无收益且耗 CPU
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    flow {
                        chapters.forEachIndexed { index, chapter -> emit(index to chapter) }
                    }.mapAsync(AppConst.MAX_THREAD) { (index, chapter) ->
                        index to extractCbzPages(book, chapter, index)
                    }.collect { (index, pages) ->
                        currentCoroutineContext().ensureActive()
                        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                        exportProgress[book.bookUrl] = index
                        pages.forEach { (entryName, openStream) ->
                            zos.putNextEntry(ZipEntry(entryName))
                            openStream()?.use { it.copyTo(zos) } ?: return@forEach
                            zos.closeEntry()
                            totalWritten++
                        }
                    }
                    zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                    zos.write(buildComicInfo(book, totalWritten).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            if (AppConfig.exportToWebDav) {
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { bookDoc.delete() }
            throw e
        }
    }

    private fun extractCbzPages(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int
    ): List<Pair<String, () -> InputStream?>> {
        val content = BookHelp.getContent(book, chapter) ?: return emptyList()
        val chapterDir = "%04d-%s".format(
            chapterIndex + 1,
            chapter.title.normalizeFileName().take(50)
        )
        val pages = ArrayList<Pair<String, () -> InputStream?>>()
        var pageIndex = 0
        // 本地缓存内容经过 formatKeepImg 处理，img 标签格式统一为 <img src="...">
        val imgPattern = Pattern.compile("<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val matcher = imgPattern.matcher(content)
        while (matcher.find()) {
            val src = matcher.group(1) ?: continue
            pageIndex++
            val entryName = "$chapterDir/%04d.%s"
                .format(pageIndex, BookHelp.getImageSuffix(src))
            pages.add(entryName to {
                val vFile = BookHelp.getImage(book, src)
                if (vFile.exists()) vFile.inputStream() else FileBook.getImage(book, src)
            })
        }
        return pages
    }

    private fun buildComicInfo(book: Book, pageCount: Int): String {
        fun esc(s: String?): String = (s ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
            append("  <Title>").append(esc(book.name)).append("</Title>\n")
            book.getRealAuthor().takeIf { it.isNotBlank() }?.let {
                append("  <Writer>").append(esc(it)).append("</Writer>\n")
            }
            book.getDisplayIntro().takeIf { !it.isNullOrBlank() }?.let {
                append("  <Summary>").append(esc(it)).append("</Summary>\n")
            }
            book.kind?.takeIf { it.isNotBlank() }?.let {
                append("  <Genre>").append(esc(it.replace(",", ", "))).append("</Genre>\n")
            }
            append("  <PageCount>").append(pageCount).append("</PageCount>\n")
            append("  <LanguageISO>zh</LanguageISO>\n")
            append("  <Manga>Yes</Manga>\n")
            append("</ComicInfo>\n")
        }
    }

    /**
     * 导出Epub
     */
    private suspend fun exportEpub(path: String, book: Book) {
        exportMsg.remove(book.bookUrl)
        postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
        val fileDoc = FileDoc.fromDir(path)
        exportEpub(fileDoc, book)
    }

    private suspend fun exportEpub(fileDoc: FileDoc, book: Book) {
        val filename = book.getExportFileName("epub")
        fileDoc.find(filename)?.delete()

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook)
        //set css
        val contentModel = setAssets(fileDoc, book, epubBook)

        //设置正文
        setEpubContent(contentModel, book, epubBook)

        val bookDoc = fileDoc.createFileIfNotExist(filename)
        try {
            bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter().write(epubBook, bookOs)
            }
            if (AppConfig.exportToWebDav) {
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        } catch (e: Throwable) {
            runCatching { bookDoc.delete() }
            throw e
        }
    }

    private fun setAssets(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        val customPath = doc.find("Asset")
        val contentModel = if (customPath == null) {//使用内置模板
            setAssets(book, epubBook)
        } else {//外部模板
            setAssetsExternal(customPath, book, epubBook)
        }

        return contentModel
    }

    private fun setAssetsExternal(doc: FileDoc, book: Book, epubBook: EpubBook): String {
        var contentModel = ""
        doc.list()!!.forEach { folder ->
            if (folder.isDir && folder.name == "Text") {
                folder.list()!!.sortedWith { o1, o2 ->
                    o1.name.cnCompare(o2.name)
                }.forEach loop@{ file ->
                    if (file.isDir) {
                        return@loop
                    }
                    when {
                        //正文模板
                        file.name.equals("chapter.html", true)
                                || file.name.equals("chapter.xhtml", true) -> {
                            contentModel = file.readText()
                        }
                        //封面等其他模板
                        file.name.endsWith("html", true) -> {
                            epubBook.addSection(
                                FileUtils.getNameExcludeExtension(file.name),
                                ResourceUtil.createPublicResource(
                                    book.name,
                                    book.getRealAuthor(),
                                    book.getDisplayIntro(),
                                    book.kind,
                                    book.wordCount,
                                    file.readText(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                        //其他格式文件当做资源文件
                        else -> {
                            epubBook.resources.add(
                                Resource(
                                    file.readBytes(),
                                    "${folder.name}/${file.name}"
                                )
                            )
                        }
                    }
                }
            } else if (folder.isDir) {
                //资源文件
                folder.list()!!.forEach loop2@{
                    if (it.isDir) {
                        return@loop2
                    }
                    epubBook.resources.add(
                        Resource(
                            it.readBytes(),
                            "${folder.name}/${it.name}"
                        )
                    )
                }
            } else {//Asset下面的资源文件
                epubBook.resources.add(
                    Resource(
                        folder.readBytes(),
                        folder.name
                    )
                )
            }
        }
        return contentModel
    }

    private fun setAssets(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/fonts.css").readBytes(),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/main.css").readBytes(),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                appCtx.assets.open("epub/logo.png").readBytes(),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            getString(R.string.img_cover),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/cover.html").readBytes()),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            getString(R.string.book_intro),
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(appCtx.assets.open("epub/intro.html").readBytes()),
                "Text/intro.html"
            )
        )
        return String(appCtx.assets.open("epub/chapter.html").readBytes())
    }

    private fun setCover(book: Book, epubBook: EpubBook) {
        kotlin.runCatching {
            val file = Glide.with(this)
                .asFile()
                .load(book.getDisplayCover())
                .submit()
                .get()
            val provider = object : LazyResourceProvider {
                override fun getResourceStream(href: String?) = file.inputStream()
            }
            epubBook.coverImage = LazyResource(provider, "Images/cover.jpg")
        }.onFailure {
            AppLog.put("获取书籍封面出错\n${it.localizedMessage}", it)
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook
    ) = coroutineScope {
        //正文
        val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        var parentSection: TOCReference? = null
        flow {
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEach { chapter ->
                emit(chapter)
            }
        }.mapAsyncIndexed(AppConst.MAX_THREAD) { index, chapter ->
            val content = BookHelp.getContent(book, chapter)
            val (contentFix, resources) = fixPic(
                book,
                content ?: if (chapter.isVolume) "" else "null",
                chapter
            )
            // 不导出vip标识
            chapter.isVip = false
            val content1 = contentProcessor
                .getContent(
                    book,
                    chapter,
                    contentFix,
                    includeTitle = false,
                    useReplace = useReplace,
                    chineseConvert = false,
                    reSegment = false
                ).toString()
            val title = chapter.run {
                // 不导出vip标识
                isVip = false
                getDisplayTitle(
                    contentProcessor.getTitleReplaceRules(),
                    useReplace = useReplace
                )
            }
            val chapterResource = ResourceUtil.createChapterResource(
                title.replace("\uD83D\uDD12", ""),
                content1,
                contentModel,
                "Text/chapter_${index}.html"
            )
            ExportChapter(title, chapterResource, resources, chapter)
        }.collectIndexed { index, exportChapter ->
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            exportProgress[book.bookUrl] = index
            val (title, chapterResource, resources, chapter) = exportChapter
            epubBook.resources.addAll(resources)
            if (chapter.isVolume) {
                parentSection = epubBook.addSection(title, chapterResource)
            } else {
                val parent = parentSection
                if (parent == null) {
                    epubBook.addSection(title, chapterResource)
                } else {
                    epubBook.addSection(parent, title, chapterResource)
                }
            }
        }
    }

    data class ExportChapter(
        val title: String,
        val chapterResource: Resource,
        val resources: ArrayList<Resource>,
        val chapter: BookChapter
    )

    private fun fixPic(
        book: Book,
        content: String,
        chapter: BookChapter
    ): Pair<String, ArrayList<Resource>> {
        val data = StringBuilder("")
        val resources = arrayListOf<Resource>()
        content.split("\n").forEach { text ->
            var text1 = text
            // 本地缓存内容经过 formatKeepImg 处理，img 标签格式统一为 <img src="...">
            // 直接用简单的正则提取 src 即可
            val imgPattern = Pattern.compile("<img src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            val matcher = imgPattern.matcher(text)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val originalHref =
                    "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                val href =
                    "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                val vFile = BookHelp.getImage(book, src)

                AppLog.putDebug("导出图片检查: ${chapter.title}\n  URL: $src\n  缓存路径: ${vFile.absolutePath}\n  是否存在: ${vFile.exists()}")

                val fp = FileResourceProvider(vFile.parent)
                if (vFile.exists()) {
                    val img = LazyResource(fp, href, originalHref)
                    resources.add(img)
                    text1 = text1.replace(src, "../${href}")
                } else {
                    AppLog.put("导出书籍<${book.name}> ${chapter.title} 图片缓存不存在: $src")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString() to resources
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles?.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    //////end of EPUB

    //////start of custom exporter
    /**
     * 自定义Exporter
     * @param scope 导出范围
     * @param size epub 文件包含最大章节数
     */
    inner class CustomExporter(scopeStr: String, private val size: Int) {

        private var scope = parseScope(scopeStr)

        /**
         * 导出Epub
         * @param path 导出的路径
         * @param book 书籍
         */
        suspend fun export(
            path: String,
            book: Book
        ) {
            exportProgress[book.bookUrl] = 0
            exportMsg.remove(book.bookUrl)
            postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
            val currentTimeMillis = System.currentTimeMillis()
            val count = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            scope = scope.filter { it < count }.toHashSet()

            val fileDoc = FileDoc.fromDir(path)

            val (contentModel, epubList) = createEpubs(book, fileDoc)
            var progressBar = 0.0
            epubList.forEachIndexed { index, ep ->
                val (filename, epubBook) = ep
                //设置正文
                setEpubContent(
                    contentModel,
                    book,
                    epubBook,
                    index
                ) { _, _ ->
                    // 将章节写入内存时更新进度条
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    progressBar += book.totalChapterNum.toDouble() / scope.size / 2
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
                save2Drive(filename, epubBook, fileDoc) { total, _ ->
                    //写入硬盘时更新进度条
                    progressBar += book.totalChapterNum.toDouble() / epubList.size / total / 2
                    postEvent(EventBus.EXPORT_BOOK, book.bookUrl)
                    exportProgress[book.bookUrl] = progressBar.toInt()
                }
            }

            val elapsed = System.currentTimeMillis() - currentTimeMillis
            AppLog.put("分割导出书籍 ${book.name} 一共耗时 $elapsed")
        }


        /**
         * 设置epub正文
         *
         * @param contentModel 正文模板
         * @param book 书籍
         * @param epubBook 分割后的epub
         * @param epubBookIndex 分割后的epub序号
         */
        private suspend fun setEpubContent(
            contentModel: String,
            book: Book,
            epubBook: EpubBook,
            epubBookIndex: Int,
            updateProgress: (chapterList: MutableList<BookChapter>, index: Int) -> Unit
        ) {
            //正文
            val useReplace = AppConfig.exportUseReplace && book.getUseReplaceRule()
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            var chapterList: MutableList<BookChapter> = ArrayList()
            appDb.bookChapterDao.getChapterList(book.bookUrl).forEachIndexed { index, chapter ->
                if (scope.contains(index)) {
                    chapterList.add(chapter)
                }
                if (scope.size == chapterList.size) {
                    return@forEachIndexed
                }
            }
            // val totalChapterNum = book.totalChapterNum / scope.size
            if (chapterList.isEmpty()) {
                throw RuntimeException("书籍<${book.name}>(${epubBookIndex + 1})未找到章节信息")
            }
            chapterList = chapterList.subList(
                epubBookIndex * size,
                min(scope.size, (epubBookIndex + 1) * size)
            )
            chapterList.forEachIndexed { index, chapter ->
                currentCoroutineContext().ensureActive()
                updateProgress(chapterList, index)
                BookHelp.getContent(book, chapter).let { content ->
                    val (contentFix, resources) = fixPic(
                        book,
                        content ?: if (chapter.isVolume) "" else "null",
                        chapter
                    )
                    epubBook.resources.addAll(resources)
                    val content1 = contentProcessor
                        .getContent(
                            book,
                            chapter,
                            contentFix,
                            includeTitle = false,
                            useReplace = useReplace,
                            chineseConvert = false,
                            reSegment = false
                        ).toString()
                    val title = chapter.run {
                        // 不导出vip标识
                        isVip = false
                        getDisplayTitle(
                            contentProcessor.getTitleReplaceRules(),
                            useReplace = useReplace
                        )
                    }
                    epubBook.addSection(
                        title,
                        ResourceUtil.createChapterResource(
                            title.replace("\uD83D\uDD12", ""),
                            content1,
                            contentModel,
                            "Text/chapter_${index}.html"
                        )
                    )
                }
            }
        }

        /**
         * 创建多个epub 对象
         *
         * 分割epub时，一个书籍需要创建多个epub对象
         * @param book 书籍
         * @param fileDoc 导出文件夹文档
         *
         * @return <内容模板字符串, <epub文件名, epub对象>>
         */
        private fun createEpubs(
            book: Book,
            fileDoc: FileDoc
        ): Pair<String, List<Pair<String, EpubBook>>> {
            val paresNumOfEpub = paresNumOfEpub(scope.size, size)
            val result: MutableList<Pair<String, EpubBook>> = ArrayList(paresNumOfEpub)
            var contentModel = ""
            for (i in 1..paresNumOfEpub) {
                val filename = book.getExportFileName("epub", i)
                fileDoc.find(filename)?.delete()

                val epubBook = EpubBook()
                epubBook.version = "2.0"
                //set metadata
                setEpubMetadata(book, epubBook)
                //set cover
                setCover(book, epubBook)
                //set css
                contentModel = setAssets(fileDoc, book, epubBook)

                // add epubBook
                result.add(Pair(filename, epubBook))
            }
            return Pair(contentModel, result)
        }

        /**
         * 保存文件到 设备
         */
        private suspend fun save2Drive(
            filename: String,
            epubBook: EpubBook,
            fileDoc: FileDoc,
            callback: (total: Int, progress: Int) -> Unit
        ) {
            val bookDoc = fileDoc.createFileIfNotExist(filename)
            bookDoc.openOutputStream().getOrThrow().buffered().use { bookOs ->
                EpubWriter()
                    .setCallback(object : EpubWriterProcessor.Callback {
                        override fun onProgressing(total: Int, progress: Int) {
                            callback(total, progress)
                        }
                    })
                    .write(epubBook, bookOs)
            }

            if (AppConfig.exportToWebDav) {
                // 导出到webdav
                AppWebDav.exportWebDav(bookDoc.uri, filename)
            }
        }

        /**
         * 解析 分割epub后的数量
         *
         * @param total 章节总数
         * @param size 每个epub文件包含多少章节
         */
        private fun paresNumOfEpub(total: Int, size: Int): Int {
            val i = total % size
            var result = total / size
            if (i > 0) {
                result++
            }
            return result
        }

        /**
         * 解析范围字符串
         *
         * @param scope 范围字符串
         * @return 范围
         *
         * @since 2023/5/22
         * @author Discut
         */
        private fun parseScope(scope: String): Set<Int> {
            val split = scope.split(",")

            val result = linkedSetOf<Int>()
            for (s in split) {
                val v = s.split("-")
                if (v.size != 2) {
                    result.add(s.toInt() - 1)
                    continue
                }
                val left = v[0].toInt()
                val right = v[1].toInt()
                if (left > right) {
                    AppLog.put("Error expression : $s; left > right")
                    continue
                }
                for (i in left..right)
                    result.add(i - 1)
            }
            return result
        }
    }
}