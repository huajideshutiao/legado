package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        withContext(Dispatchers.IO) {
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
     * 文件存在性通过 [BookStorageProviders.get].getChapterFiles 列出的文件名集合判断,
     * 与 app 端 `!File(path).exists()` 行为等价
     * (getChapterFiles 列出书籍缓存目录下所有文件, 含 `.nr` 标记文件)。
     *
     * @param book 书籍
     * @param chapter 章节
     * @return true 表示去除重复标题 (无 `.nr` 标记); false 表示保留 (有 `.nr` 标记)
     */
    fun removeSameTitle(book: Book, chapter: BookChapter): Boolean {
        val nrFileName = chapter.getFileName("nr")
        val cachedFiles = BookStorageProviders.get().getChapterFiles(book)
        return !cachedFiles.contains(nrFileName)
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
