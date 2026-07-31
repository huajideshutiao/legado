package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.runBlockingInScope
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.postEvent
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
     * 写入 / 删除 `.nr` 标记文件 (对照 app 端 `BookHelp.setRemoveSameTitle` 的文件部分)。
     *
     * 去重开启 = 删除标记文件; 关闭 = 创建空标记文件。
     * ContentProcessor 的 removeSameTitleCache 同步由各端调用方负责。
     */
    fun setRemoveSameTitleMarker(book: Book, chapter: BookChapter, removeSameTitle: Boolean) {
        val storage = BookStorageProviders.get()
        val fileName = chapter.getFileName("nr")
        if (removeSameTitle) {
            storage.deleteCacheFile(book, fileName)
        } else {
            storage.createCacheFile(book, fileName)
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
