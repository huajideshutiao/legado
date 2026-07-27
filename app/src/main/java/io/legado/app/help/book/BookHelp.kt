package io.legado.app.help.book

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.getBookInputStream
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.read.page.provider.ChapterContentParser
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    fun clearCache() {
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    fun updateCacheFolder(oldBook: Book, newBook: Book) {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName == newFolderName) return
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            oldFolderName
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            newFolderName
        )
        FileUtils.move(oldFolderPath, newFolderPath)
    }

    /**
     * 清除已删除书的缓存 解压缓存
     *
     * 编排下沉 [BookHelpShared.clearInvalidCache] (四步: 删失效书目录 → 漫画缓存超限淘汰
     * → 大变量清理 → [clearCacheExtra]), 前两步经 [BookStorageProviders] 回调本类
     * [clearInvalidBookFolders]。
     */
    suspend fun clearInvalidCache() {
        BookHelpShared.clearInvalidCache()
    }

    /**
     * 删除不在书架的书籍缓存目录 + 漫画缓存超限淘汰 (原 [clearInvalidCache] 步骤 1 与 3)。
     *
     * 由 [AndroidBookStorage.clearInvalidBookFolders] 委托调用。
     */
    suspend fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        withContext(IO) {
            val cacheFiles = listBookCacheFolders()
            coroutineScope {
                cacheFiles.forEach { bookFile ->
                    if (!validFolderNames.contains(bookFile.name)) {
                        launch { FileUtils.delete(bookFile, true) }
                    }
                }
            }
            evictMangaCache(
                cacheFiles.filter { validFolderNames.contains(it.name) },
                imageSubFolderName,
                maxSize
            )
        }
    }

    /**
     * 漫画缓存超限淘汰 (原 [clearInvalidCache] 第 3 段, 不删失效目录)。
     *
     * 由 [AndroidBookStorage.clearInvalidCache] 委托调用。
     */
    suspend fun evictMangaCache(imageSubFolderName: String, maxSize: Long) {
        withContext(IO) {
            evictMangaCache(listBookCacheFolders(), imageSubFolderName, maxSize)
        }
    }

    private fun listBookCacheFolders(): List<File> {
        val cacheFolder = downloadDir.getFile(cacheFolderName)
        return cacheFolder.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    /**
     * 按最后修改时间由旧到新淘汰漫画缓存, 直到总大小收敛到 [maxSize] 以内。
     * 总大小按全部 [folders] 统计, 但只有含 [imageSubFolderName] 子目录的漫画缓存参与淘汰。
     */
    private suspend fun evictMangaCache(
        folders: List<File>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        if (folders.isEmpty()) return
        val folderSizes = ConcurrentHashMap<File, Long>()
        val mangaFolders = ConcurrentHashMap.newKeySet<File>()
        folders.asFlow().onEachParallel(8) { folder ->
            folderSizes[folder] = folder.walk().filter { it.isFile }.sumOf { it.length() }
            if (File(folder, imageSubFolderName).exists()) {
                mangaFolders.add(folder)
            }
        }.collect()

        var totalSize = folderSizes.values.sum()
        if (totalSize <= maxSize) return
        for (folder in folders.sortedBy { it.lastModified() }) {
            if (mangaFolders.contains(folder)) {
                val size = folderSizes[folder] ?: 0L
                FileUtils.delete(folder, true)
                totalSize -= size
                if (totalSize <= maxSize) break
            }
        }
    }

    /**
     * 清理平台专属临时文件 (供 WebBookProvidersImpl.clearCacheExtra 委托)。
     *
     * 逻辑与 [clearInvalidCache] 末段一致: ArchiveUtils.TEMP_PATH + filesDir/share*.json + books.json。
     * BookHelpShared.clearInvalidCache 经 BookHelpProviders.clearCacheExtra 调用本方法。
     */
    suspend fun clearCacheExtra() {
        withContext(IO) {
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete(File(filesDir, "shareBookSource.json").absolutePath)
            FileUtils.delete(File(filesDir, "shareRssSource.json").absolutePath)
            FileUtils.delete(File(filesDir, "books.json").absolutePath)
        }
    }

    fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        try {
            saveText(book, bookChapter, content)
            //saveImages(bookSource, book, bookChapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
        }
    }

    /**
     * 保存章节正文到缓存文件。
     *
     * 字数统计的 upWordCount 用 [runBlocking]: Room KMP 禁止 commonMain DAO 声明非 suspend 查询,
     * 无同步重载可用; saveText 自身不能改 suspend (调用面含 BookStorage 同步接口)。
     * 全部调用方 (ContentEditDialog/ReadBookViewModel/saveContent/getContent) 均在 IO 协程内, 不阻塞主线程。
     */
    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        if (content.isEmpty()) return
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName(),
        ).writeText(content)
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            runBlocking {
                appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
            }
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val imgList = ChapterContentParser.extractImages(content)
            for (i in imgList) {
                if (i.src.isBlank()) continue
                emit(i.src)
            }
        }
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        if (isImageExist(book, src)) {
            return
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (isImageExist(book, src)) {
                return
            }
            val analyzeUrl = AnalyzeUrl(
                src, source = bookSource, coroutineContext = currentCoroutineContext()
            )
            val bytes = analyzeUrl.getByteArrayAwait()
            //某些图片被加密，需要进一步解密
            runScriptWithContext {
                ImageUtils.decode(
                    src, bytes, isCover = false, bookSource, book
                )
            }?.let {
                if (!checkImage(it)) {
                    // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                    // 所以无论如何都要将数据写入到文件里
                    // throw NoStackTraceException("数据异常")
                    AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                }
                writeImage(book, src, it)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val msg = "${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}"
            AppLog.put(msg, e)
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    fun getImageSuffix(src: String): String =
        BookHelpLogic.getImageSuffix(src)

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                FileBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        if (book.bookUrl.startsWith(BookType.webDavTag)) {
            val webDavUrl = book.getRemoteUrl()!!
            val webdav = kotlin.runCatching {
                io.legado.app.lib.webdav.WebDav.fromPath(webDavUrl)
            }.getOrElse {
                io.legado.app.help.AppWebDav.authorization?.let { auth ->
                    io.legado.app.lib.webdav.WebDav(webDavUrl, auth)
                } ?: throw io.legado.app.lib.webdav.WebDavException("Unexpected defaultBookWebDav")
            }
            val size = kotlinx.coroutines.runBlocking { webdav.getWebDavFile()?.size } ?: 0L
            val storageManager =
                appCtx.getSystemService(android.os.storage.StorageManager::class.java)
            val handlerThread = android.os.HandlerThread("WebDavPfd")
            handlerThread.start()
            val handler = android.os.Handler(handlerThread.looper)
            return storageManager?.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                io.legado.app.lib.webdav.WebDavPfdCallback(webdav, size, handlerThread),
                handler
            )
        }
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt || book.isVideo || book.isAudio) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        return if (book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
        ) {
            true
        } else {
            downloadDir.exists(
                cacheFolderName,
                book.getFolderName(),
                bookChapter.getFileName()
            )
        }
    }

    /**
     * 检测图片是否下载
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        var ret = true
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        getContent(book, bookChapter)?.let {
            val imgList = ChapterContentParser.extractImages(it)
            for (i in imgList) {
                val src = i.src
                val image = getImage(book, src)
                if (!image.exists()) {
                    ret = false
                    continue
                }
                BitmapFactory.decodeFile(image.absolutePath, op)
                if (op.outWidth < 1 && op.outHeight < 1) {
                    if (SvgUtils.getSize(image.absolutePath) != null) {
                        continue
                    }
                    ret = false
                    image.delete()
                }
            }
        }
        return ret
    }

    private fun checkImage(bytes: ByteArray): Boolean {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            return SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
        }
        return true
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val file = downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = FileBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        ).delete()
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     *
     * 不委托 [BookHelpShared.removeSameTitle]: 后者经 getChapterFiles 判断 `.nr` 标记,
     * 而 [getChapterFiles] 对本地 txt / 视频 / 音频书直接返回空集, 会把已禁用去重的章节误判为需去重。
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String =
        BookHelpLogic.formatBookAuthor(author)

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int = BookHelpLogic.getDurChapter(
        oldDurChapterIndex,
        oldDurChapterName,
        newChapterList,
        oldChapterListSize
    )

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int = BookHelpLogic.getDurChapter(oldBook, newChapterList)

}
