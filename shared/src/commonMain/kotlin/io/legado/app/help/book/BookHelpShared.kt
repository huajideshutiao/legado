package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.concurrent.newConcurrentMap
import io.legado.app.utils.concurrent.newConcurrentSet
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min

/**
 * BookHelp 跨平台编排逻辑 (shared commonMain)。
 *
 * 原 app 端 `BookHelp.kt` 中的平台无关编排逻辑 (`clearInvalidCache` 编排 /
 * `removeSameTitle` 算法) 下沉到本类, 供全平台复用。
 *
 * # 依赖注入
 * - [BookStorageProviders]: 章节缓存 I/O (`clearInvalidBookFolders` / `getChapterFiles`)
 * - [RuleBigDataProviders]: 书籍大变量数据清理 (`clearInvalidBookData`)
 * - [AppDbProviders]: 书架数据 (`allBookUrlsWithName`)
 * - [BookHelpProviders]: 平台专属清理 (`clearCacheExtra`)
 *
 * app 端 `BookHelp` 同名方法改为薄壳委托本类, 70+ 处消费方零改动。
 * 模式参考 [io.legado.app.model.CacheBookShared] / [io.legado.app.help.service.UpdateBookShared]。
 */
object BookHelpShared {

    /** 章节缓存目录名 (对照 app 端 `BookHelp.cacheFolderName`)。 */
    const val cacheFolderName = "book_cache"

    /** 图片缓存子目录名 (对照 app 端 `BookHelp.cacheImageFolderName`)。 */
    const val cacheImageFolderName = "images"

    /** EPUB 缓存子目录名 (对照 app 端 `BookHelp.cacheEpubFolderName`)。 */
    const val cacheEpubFolderName = "epub"

    /** 漫画图片缓存总大小上限 512MB (对照 app 端 `BookHelp.clearInvalidCache`)。 */
    const val MANGA_CACHE_MAX_SIZE: Long = 512L * 1024 * 1024

    /**
     * 清除指定书籍的章节/图片缓存 (对照原版 `BookInfoViewModel.clearCache`)。
     *
     * 删缓存目录后, 若其中有书正在阅读则丢弃它的内存章节 —— 只删盘不清内存的话,
     * 阅读页仍拿旧正文渲染 (原版 `if (ReadBook.book?.bookUrl == book.bookUrl)
     * ReadBook.clearTextChapter()`)。调用方负责 toast 与 IO 调度。
     */
    fun clearBookCache(books: List<Book>) {
        val storage = BookStorageProviders.get()
        books.forEach { storage.clearCache(it) }
        val reading = ActiveReadBookRegistry.current ?: return
        val readingUrl = reading.book.value?.bookUrl ?: return
        if (books.any { it.bookUrl == readingUrl }) reading.clearTextChapter()
    }

    /**
     * 清除无效缓存编排 (对照 app 端 `BookHelp.clearInvalidCache`)。
     *
     * 编排流程:
     * 1. 从书架获取有效书籍缓存文件夹名集合 + bookUrl 集合
     * 2. 委托 [BookStorageProviders.get].clearInvalidBookFolders 删除不在书架的缓存
     *    + 512MB 漫画缓存管理 (按最旧优先淘汰含图片子目录的文件夹)
     * 3. 委托 [RuleBigDataProviders.impl].clearInvalidBookData 清理不在书架的大变量数据
     * 4. 委托 [BookHelpProviders.get].clearCacheExtra 清理平台专属临时文件
     *    (app: `ArchiveUtils.TEMP_PATH` + `filesDir/share*.json`; 其他端 no-op)
     *
     * 文件夹名派生: `name前9字符 + md5_16(bookUrl)`, 与 [Book.getFolderName] 一致
     * (此处对 `BookFolder` 内联派生, 因 `BookFolder` 非 `Book`, 无法直接调 `getFolderNameNoCache`)。
     */
    suspend fun clearInvalidCache() {
        withContext(IoDispatcher) {
            val appDb = AppDbProviders.get()
            val allBookFolderNames = appDb.bookDao.allBookUrlsWithName()

            val bookFolderNames = allBookFolderNames.mapTo(HashSet(allBookFolderNames.size)) {
                it.name.replace(AppPattern.fileNameRegex, "").let { name ->
                    name.substring(0, min(9, name.length)) + MD5Utils.md5Encode16(it.bookUrl)
                }
            }
            val bookUrls = allBookFolderNames.mapTo(HashSet(allBookFolderNames.size)) { it.bookUrl }

            // 1 + 2: 删除不在书架的缓存 + 512MB 漫画缓存管理
            BookStorageProviders.get().clearInvalidBookFolders(
                validFolderNames = bookFolderNames,
                imageSubFolderName = cacheImageFolderName,
                maxSize = MANGA_CACHE_MAX_SIZE
            )

            // 3: 清理不在书架的大变量数据
            RuleBigDataProviders.impl?.clearInvalidBookData(bookUrls)

            // 4: 平台专属临时文件清理 (app 端 override 做 ArchiveUtils.TEMP_PATH + filesDir)
            BookHelpProviders.get().clearCacheExtra()
        }
    }

    /**
     * 删除不在 [validFolderNames] 中的书籍缓存目录 + 漫画缓存超量淘汰
     * (对照 app 端 `BookHelp.clearInvalidBookFolders`, 原三端平行实现统一下沉至此)。
     *
     * [rootPath] 为书籍缓存根目录 (book_cache), 三端 BookStorage 各自传自己的 rootPath。
     * 算法与 app 版一致: 失效目录并发删除 (EBUSY 兜底走 [FileUtilsCommon.delete]),
     * 有效目录按最后修改时间由旧到新淘汰含 [imageSubFolderName] 子目录的漫画缓存,
     * 直到总大小收敛到 [maxSize] 以内。
     */
    suspend fun clearInvalidBookFolders(
        rootPath: String,
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        withContext(IoDispatcher) {
            val bookDirs = FileUtilsCommon.listSubDirs(rootPath)
            coroutineScope {
                bookDirs.forEach { dir ->
                    if (!validFolderNames.contains(FileUtilsCommon.getName(dir))) {
                        launch { FileUtilsCommon.delete(dir, deleteRootDir = true) }
                    }
                }
            }
            // 失效目录已删, 重列即有效集 (与 app 版"列一次复用"结果等价);
            // 删除失败 (EBUSY 等) 时失效目录仍可能残留, 传在架书集合让 evictMangaCache 只统计有效目录
            evictMangaCache(rootPath, imageSubFolderName, maxSize, validFolderNames)
        }
    }

    /**
     * 漫画缓存超量淘汰 (对照 app 端 `BookHelp.evictMangaCache`, 不删失效书目录)。
     *
     * 总大小按 [rootPath] 下目录统计, 但只有含 [imageSubFolderName] 子目录的漫画缓存
     * 参与淘汰, 按最后修改时间由旧到新删除直到总大小收敛到 [maxSize] 以内。
     * 统计并发 8 路 (与 app 版 onEachParallel(8) 一致)。
     *
     * [validFolderNames] 非空时只统计在架书目录 (与原版 `cacheFiles.filter { bookFolderNames.contains(it.name) }`
     * 对齐): 调用方先删失效目录, 但 FileUtilsCommon.delete 失败 (EBUSY 等) 时失效目录仍在,
     * 若不按在架书过滤会被计入 512MB 总量, 可能优先淘汰在架漫画。
     * 默认 emptySet() 保持"统计全部目录"的旧行为 (供暂未传参的调用方)。
     */
    suspend fun evictMangaCache(
        rootPath: String,
        imageSubFolderName: String,
        maxSize: Long,
        validFolderNames: Set<String> = emptySet(),
    ) {
        withContext(IoDispatcher) {
            val bookDirs = FileUtilsCommon.listSubDirs(rootPath)
                .filter { validFolderNames.isEmpty() || validFolderNames.contains(FileUtilsCommon.getName(it)) }
            if (bookDirs.isEmpty()) return@withContext
            val folderSizes = newConcurrentMap<String, Long>()
            val mangaFolders = newConcurrentSet<String>()
            bookDirs.asFlow().onEachParallel(8) { dir ->
                folderSizes[dir] = FileUtilsCommon.getDirSize(dir)
                if (FileUtilsCommon.exist(FileUtilsCommon.getPath(dir, imageSubFolderName))) {
                    mangaFolders.add(dir)
                }
            }.collect()

            var totalSize = folderSizes.values.sum()
            if (totalSize <= maxSize) return@withContext
            for (dir in bookDirs.sortedBy { FileUtilsCommon.lastModified(it) }) {
                if (mangaFolders.contains(dir)) {
                    val size = folderSizes[dir] ?: 0L
                    FileUtilsCommon.delete(dir, deleteRootDir = true)
                    totalSize -= size
                    if (totalSize <= maxSize) break
                }
            }
        }
    }

    /**
     * 是否去除重复标题 (对照 app 端 `BookHelp.removeSameTitle`)。
     *
     * 算法: `.nr` 标记文件存在 = 禁用去除重复标题, 故返回 `!exists`。
     * 存在性走 [BookStorage.hasCacheFile] 直查文件系统, 与 app 端原版
     * `!File(path).exists()` 一致 (不能用 getChapterFiles: 它对本地 txt /
     * 视频 / 音频书直接返回空集, 会把已禁用去重的章节误判为需去重)。
     */
    fun removeSameTitle(book: Book, chapter: BookChapter): Boolean {
        return !BookStorageProviders.get().hasCacheFile(book, chapter.getFileName("nr"))
    }

    /**
     * 进程级"去重标题缓存"注册表 (key: bookName+bookOrigin → 该书 ContentProcessorShared.removeSameTitleCache)。
     *
     * 判据 [ContentProcessorShared.getContent] 读的是实例内 removeSameTitleCache, 而实例由各端
     * [ContentProcessorAccessor] 私有缓存 (native 强引用 map 永不失效), BookHelpShared 无公共
     * API 直接访问; 各端 accessor 缓存实例后调用 [registerRemoveSameTitleCache] 注册,
     * [setRemoveSameTitleMarker] 翻转标记时据此同步内存缓存。
     */
    private val removeSameTitleCacheRegistry = newConcurrentMap<String, MutableSet<String>>()

    /**
     * 注册书籍的去重标题缓存 (由各端 ContentProcessorAccessor 在缓存 ContentProcessorShared 实例后调用)。
     *
     * @param bookName 书名 (ContentProcessorShared 的 bookName)
     * @param bookOrigin 书源 (ContentProcessorShared 的 bookOrigin)
     * @param cache 该书 ContentProcessorShared 实例的 removeSameTitleCache 引用
     */
    fun registerRemoveSameTitleCache(bookName: String, bookOrigin: String, cache: MutableSet<String>) {
        removeSameTitleCacheRegistry[bookName + bookOrigin] = cache
    }

    /**
     * 写入 / 删除 `.nr` 标记文件 + 同步内存去重缓存 (对照 app 端 `BookHelp.setRemoveSameTitle`)。
     *
     * 去重开启 = 删除标记文件; 关闭 = 创建空标记文件。
     * 原版 BookHelp.setRemoveSameTitle 除写标记文件外还同步 ContentProcessor.removeSameTitleCache
     * (add/remove fileName); KMP 下沉时只保留文件部分, 判据读的是内存 Set, 不同步则已缓存实例
     * (尤其 native 强引用 map) 永不感知新状态, 翻转后桌面/iOS/鸿蒙不生效。经
     * [registerRemoveSameTitleCache] 注册的实例缓存在这里同步; 未注册时降级为仅写文件。
     */
    fun setRemoveSameTitleMarker(book: Book, chapter: BookChapter, removeSameTitle: Boolean) {
        val storage = BookStorageProviders.get()
        val fileName = chapter.getFileName("nr")
        if (removeSameTitle) {
            storage.deleteCacheFile(book, fileName)
        } else {
            storage.createCacheFile(book, fileName)
        }
        // 同步内存去重缓存 (对照 app 端 BookHelp.setRemoveSameTitle 的 cache.remove/add)
        removeSameTitleCacheRegistry[book.name + book.origin]?.let { cache ->
            if (removeSameTitle) cache.remove(fileName) else cache.add(fileName)
        }
    }

    /**
     * 保存章节正文并通知阅读页 (对照 app 端 `BookHelp.saveContent`)。
     *
     * 失败只记日志不抛出, 与 app 端一致。
     */
    fun saveContent(book: Book, chapter: BookChapter, content: String) {
        try {
            BookStorageProviders.get().saveText(book, chapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
        } catch (e: Exception) {
            AppLog.put("保存正文失败 ${book.name} ${chapter.title}", e)
        }
    }

    /**
     * 读取章节正文 (对照 app 端 `BookHelp.getContent`)。
     *
     * 缓存文件存在则直接返回 (空文件视为无内容返回 null, 不回退本地解析);
     * 无缓存且为本地书时走 [FileBook] 解析, epub 结果顺带写回缓存。
     */
    fun getContent(book: Book, chapter: BookChapter): String? {
        val storage = BookStorageProviders.get()
        val cached = storage.readCacheFile(book, chapter.getFileName())
        if (cached != null) {
            return cached.ifEmpty { null }
        }
        if (book.isLocal) {
            val string = FileBook.getContent(book, chapter)
            if (string != null && book.isEpub) {
                storage.saveText(book, chapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 在线 txt 章节字数统计并写库 (对照 app 端 `BookHelp.saveText` 后半段)。
     *
     * 用 [runBlockingInScope]: Room KMP 禁止非 suspend 查询, 而调用方 saveText
     * 不能改 suspend (BookStorage 同步接口); 全部调用方均在 IO 协程内。
     */
    fun upWordCount(book: Book, chapter: BookChapter, content: String) {
        if (!book.isOnLineTxt || !AppConfigProviders.get().tocCountWords) return
        val wordCount = StringUtils.wordCountFormat(content.length)
        chapter.wordCount = wordCount
        runBlockingInScope(EmptyCoroutineContext) {
            AppDbProviders.get().bookChapterDao.upWordCount(chapter.bookUrl, chapter.url, wordCount)
        }
    }

    /** hasContent 前置分支: 本地 txt / 卷宗章节直接视为已缓存, 不查文件 (对照 app 端 BookHelp.hasContent)。 */
    fun shouldSkipHasContent(book: Book, bookChapter: BookChapter): Boolean =
        book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))

    /** getChapterFiles 前置分支: 本地 txt / 视频 / 音频书无章节缓存文件 (对照 app 端 BookHelp.getChapterFiles)。 */
    fun shouldSkipChapterFiles(book: Book): Boolean =
        book.isLocalTxt || book.isVideo || book.isAudio

    /** updateCacheFolder 前置比较: getFolderNameNoCache 相同则无需重命名 (对照 app 端 BookHelp.updateCacheFolder)。 */
    fun shouldUpdateCacheFolder(oldBook: Book, newBook: Book): Boolean =
        oldBook.getFolderNameNoCache() != newBook.getFolderNameNoCache()

    /**
     * 格式化作者 (对照 app 端 `BookHelp.formatBookAuthor`)。
     *
     * 原实现在 [BookHelpLogic] (jvmAndAndroidMain), 因 [BookNameAuthorAnalyzer] 下沉
     * commonMain 需复用, 移到本类; BookHelpLogic 同名方法改为委托。
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim()
    }
}
