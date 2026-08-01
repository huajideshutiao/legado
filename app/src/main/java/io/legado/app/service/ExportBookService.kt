package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import coil3.toBitmap
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
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.help.setLiveOngoing
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.ConcurrentHashMap

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

    // 下沉业务逻辑桥接 (setEpubMetadata / exportTxt / exportEpub / exportCbz / CustomExporter 委托 shared)
    private val depsImpl = ExportBookDepsImpl()
    private val shared = ExportBookShared(depsImpl)
    private val epubShared = ExportBookEpubShared(depsImpl, shared)


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
            // BookshelfManageActivity 已被 shared BookshelfManageRoute 替代,
            // 通知点击 → NavigateTo("bookshelf_manage") 打开书架管理页面
            .setContentIntent(activityPendingIntent<MainActivity>("bookshelfManageActivity") {
                putExtra("route", "bookshelf_manage")
            })
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentText(notificationContentText)
            .setDeleteIntent(servicePendingIntent<ExportBookService>(IntentAction.stop))
            .setGroup(groupKey)
            .setOnlyAlertOnce(!finish)
        if (!finish) {
            notification.setOngoing(true)
            // 导出逐本串行、进度为文本摘要而非单一确定总量, 故只请求"实时进行中"胶囊,
            // 不挂 ProgressStyle; 若系统判定不可提升会自动降级为普通通知, 无副作用。
            notification.setLiveOngoing()
            notification.addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<ExportBookService>(IntentAction.stop)
            )
        } else {
            notification.setOngoing(false)
            notification.setAutoCancel(true)
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
                                epubShared.exportEpub(exportConfig.path, book)
                            } else {
                                epubShared.exportCustom(
                                    exportConfig.path,
                                    book,
                                    exportConfig.epubScope,
                                    exportConfig.epubSize
                                )
                            }
                        }

                        "cbz" -> epubShared.exportCbz(exportConfig.path, book, chapters)
                        else -> shared.exportTxt(exportConfig.path, book)
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

    // Room KMP: 内部调用 suspend DAO 方法，改为 suspend fun；唯一调用方在 launch(IO) 协程内
    private suspend fun ensureChapterList(book: Book): List<BookChapter> {
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

    /**
     * ExportBookDeps 平台实现, 桥接 AppConfig / ContentProcessor / FileDoc /
     * AppWebDav / R.string / EventBus / exportProgress / exportMsg 等 app 端依赖。
     *
     * 详见 [ExportBookShared] 类注释 "平台依赖注入" 段。
     */
    private inner class ExportBookDepsImpl : ExportBookDeps {
        override val exportCharset: String get() = AppConfig.exportCharset
        override val exportUseReplace: Boolean get() = AppConfig.exportUseReplace
        override val exportNoChapterName: Boolean get() = AppConfig.exportNoChapterName
        override val exportToWebDav: Boolean get() = AppConfig.exportToWebDav

        override fun strAuthorShow(author: String): String =
            getString(R.string.author_show, author)

        override fun strIntroShow(intro: String): String =
            getString(R.string.intro_show, intro)

        override fun getExportFileName(book: Book, suffix: String): String =
            book.getExportFileName(suffix)

        override fun getExportFileName(book: Book, suffix: String, index: Int): String =
            book.getExportFileName(suffix, index)

        override fun processContent(
            book: Book,
            chapter: BookChapter,
            content: String,
            includeTitle: Boolean,
            useReplace: Boolean,
            chineseConvert: Boolean,
            reSegment: Boolean
        ): CharSequence =
            ContentProcessor.get(book.name, book.origin)
                .getContent(
                    book,
                    chapter,
                    content,
                    includeTitle = includeTitle,
                    useReplace = useReplace,
                    chineseConvert = chineseConvert,
                    reSegment = reSegment
                ).toString()

        override fun prepareExportFile(dirPath: String, filename: String): ExportFileHandle {
            val fileDoc = FileDoc.fromDir(dirPath)
            fileDoc.find(filename)?.delete()
            val bookDoc = fileDoc.createFileIfNotExist(filename)
            val os = bookDoc.openOutputStream().getOrThrow()
            return ExportFileHandle(os, bookDoc.uri.toString())
        }

        override fun deleteExportUri(uri: String) {
            FileDoc.fromUri(Uri.parse(uri), false).delete()
        }

        override fun postExportEvent(bookUrl: String) {
            postEvent(EventBus.EXPORT_BOOK, bookUrl)
        }

        override fun setExportProgress(bookUrl: String, progress: Int) {
            exportProgress[bookUrl] = progress
        }

        override fun removeExportProgress(bookUrl: String) {
            exportProgress.remove(bookUrl)
        }

        override fun setExportMsg(bookUrl: String, msg: String) {
            exportMsg[bookUrl] = msg
        }

        override fun removeExportMsg(bookUrl: String) {
            exportMsg.remove(bookUrl)
        }

        override suspend fun exportToWebDav(uri: String, filename: String) {
            AppWebDav.exportWebDav(Uri.parse(uri), filename)
        }

        // ==================== EPUB/CBZ 导出 (ExportBookEpubShared) 平台依赖 ====================

        override suspend fun getCoverImageBytes(book: Book): ByteArray? {
            // 原 setCover 逻辑: 先查 Coil3 磁盘缓存文件; 没有则 execute 下载到 Bitmap 写临时文件
            val loader = coil3.SingletonImageLoader.get(this@ExportBookService)
            val coverUrl = book.getDisplayCover() ?: return null
            val cacheFile = loader.diskCache?.openSnapshot(coverUrl)?.use { it.data.toFile() }
            val file = cacheFile ?: run {
                val req = coil3.request.ImageRequest.Builder(this@ExportBookService)
                    .data(coverUrl).build()
                val result = loader.execute(req)
                val bmp = (result as? coil3.request.SuccessResult)?.image?.toBitmap()
                    ?: error("cover decode failed")
                java.io.File.createTempFile("epub_cover", ".jpg").apply {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream())
                    deleteOnExit()
                }
            }
            return file.readBytes()
        }

        override fun getBuiltinAsset(assetPath: String): ByteArray =
            appCtx.assets.open(assetPath).readBytes()

        override fun listTemplateFiles(
            dirPath: String
        ): List<ExportBookEpubShared.TemplateFileInfo>? {
            // 原 setAssets 的 FileDoc.fromDir(path).find("Asset") 遍历
            val assetDoc = FileDoc.fromDir(dirPath).find("Asset") ?: return null
            return assetDoc.list()!!.map { folder ->
                ExportBookEpubShared.TemplateFileInfo(
                    name = folder.name,
                    isDir = folder.isDir,
                    children = if (folder.isDir) {
                        folder.list()!!.map { file ->
                            ExportBookEpubShared.TemplateFileInfo(
                                name = file.name,
                                isDir = false,
                                children = emptyList(),
                                readText = { file.readText() },
                                readBytes = { file.readBytes() }
                            )
                        }
                    } else emptyList(),
                    readText = { folder.readText() },
                    readBytes = { folder.readBytes() }
                )
            }
        }

        override fun getTitleReplaceRules(book: Book): List<ReplaceRule> =
            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()

        override fun strImgCover(): String = getString(R.string.img_cover)

        override fun strBookIntro(): String = getString(R.string.book_intro)
    }
}