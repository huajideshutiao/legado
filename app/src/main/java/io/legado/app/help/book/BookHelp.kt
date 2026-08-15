package io.legado.app.help.book

import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import io.legado.app.App
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp.clearCacheExtra
import io.legado.app.help.book.BookHelp.clearInvalidCache
import io.legado.app.help.book.BookHelp.getCacheFile
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.read.page.provider.ChapterContentParser
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = App.instance.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
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
        if (!BookHelpShared.shouldUpdateCacheFolder(oldBook, newBook)) return
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            oldBook.getFolderNameNoCache()
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            newBook.getFolderNameNoCache()
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

    // 缓存目录清理编排 (clearInvalidBookFolders/evictMangaCache) 已下沉
    // [BookHelpShared] (三端统一), 本端经 [AndroidBookStorage] 委托调用, 不再保留本地实现。

    /**
     * 清理平台专属临时文件 (供 WebBookProvidersImpl.clearCacheExtra 委托)。
     *
     * 逻辑与 [clearInvalidCache] 末段一致: ArchiveUtils.TEMP_PATH + filesDir/share*.json + books.json。
     * BookHelpShared.clearInvalidCache 经 BookHelpProviders.clearCacheExtra 调用本方法。
     */
    suspend fun clearCacheExtra() {
        withContext(IO) {
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = App.instance.filesDir
            FileUtils.delete(File(filesDir, "shareBookSource.json").absolutePath)
            FileUtils.delete(File(filesDir, "shareRssSource.json").absolutePath)
            FileUtils.delete(File(filesDir, "books.json").absolutePath)
        }
    }

    /** 保存正文并发通知, 逻辑下沉 [BookHelpShared.saveContent] (bookSource 仅保留签名兼容)。 */
    fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        BookHelpShared.saveContent(book, bookChapter, content)
    }

    /**
     * 保存章节正文到缓存文件。
     *
     * 字数统计下沉 [BookHelpShared.upWordCount] (内部 runBlocking 写库, 全部调用方均在 IO 协程内)。
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
        BookHelpShared.upWordCount(book, bookChapter, content)
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
            BookHelpLogic.imageFileName(src)
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

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        if (book.bookUrl.startsWith(BookType.webDavTag)) {
            // ProxyFileDescriptorCallback/openProxyFileDescriptor 是 API 26+ (minSdk 24),
            // 低版本无此能力, 返回 null 由调用方走降级/报错, 避免类加载崩溃
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return null
            }
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
                App.instance.getSystemService(android.os.storage.StorageManager::class.java)
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
            App.instance.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (BookHelpShared.shouldSkipChapterFiles(book)) {
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
     * 书籍缓存目录下按文件名取文件 (`.nr` 标记等), 供 [AndroidBookStorage] 的 cache-file 原语委托。
     */
    fun getCacheFile(book: Book, fileName: String): File {
        return downloadDir.getFile(cacheFolderName, book.getFolderName(), fileName)
    }

    /** 同 [getCacheFile], 但确保文件及父目录已创建。 */
    fun createCacheFile(book: Book, fileName: String): File {
        return FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            fileName
        )
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        return if (BookHelpShared.shouldSkipHasContent(book, bookChapter)) {
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
     * 读取章节内容, 逻辑下沉 [BookHelpShared.getContent]
     * (缓存文件读取经 [AndroidBookStorage] 回落到本类的 cache-file 原语)。
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        return BookHelpShared.getContent(book, bookChapter)
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
     *
     * 标记文件读写下沉 [BookHelpShared.setRemoveSameTitleMarker], 本端另同步 ContentProcessor 缓存。
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        BookHelpShared.setRemoveSameTitleMarker(book, bookChapter, removeSameTitle)
        if (removeSameTitle) {
            contentProcessor.removeSameTitleCache.remove(fileName)
        } else {
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        return BookHelpShared.removeSameTitle(book, bookChapter)
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
