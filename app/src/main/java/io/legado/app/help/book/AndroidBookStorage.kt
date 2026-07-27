package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.runBlocking

/**
 * [BookStorage] 安卓端 actual 实现: 委托 [BookHelp] 完成章节正文缓存 I/O。
 *
 * 注册: app 端 [io.legado.app.App.onCreate] 早期调用
 * [BookStorageProviders.register] 注入本 object (在任何 BookContent 调用之前),
 * 供 shared commonMain 下沉的 webBook 编排层通过 provider 间接调用。
 *
 * 行为与原 `BookHelp.xxx` 直接调用完全一致, 仅多一层 provider 间接;
 * 桌面 JVM 端对应 [io.legado.app.help.book.JvmBookStorage] (java.nio.file.Path 实现)。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders]。
 */
object AndroidBookStorage : BookStorage {

    /** 章节缓存根路径 (`appCtx.externalFiles/book_cache`), 对应 [BookHelp.cachePath]。 */
    override val rootPath: String
        get() = BookHelp.cachePath

    /** 获取书籍缓存文件夹名, 委托 [Book.getFolderName]。 */
    override fun getFolderName(book: Book): String = book.getFolderName()

    /** 列出书籍缓存文件夹下所有章节文件名, 委托 [BookHelp.getChapterFiles]。 */
    override fun getChapterFiles(book: Book): List<String> =
        BookHelp.getChapterFiles(book).toList()

    /** 保存章节正文到缓存文件, 委托 [BookHelp.saveText]。 */
    override fun saveText(book: Book, chapter: BookChapter, text: String) {
        BookHelp.saveText(book, chapter, text)
    }

    /** 读取章节正文, 委托 [BookHelp.getContent]。 */
    override fun getContent(book: Book, chapter: BookChapter): String? =
        BookHelp.getContent(book, chapter)

    /**
     * 删除该书所有章节缓存文件 (整本书缓存目录)。
     *
     * 接口语义是删除整本书缓存, 对应 [BookHelp.clearCache]`(book)`
     * (而非 [BookHelp.delContent] 后者按单章节删除)。
     */
    override fun delContent(book: Book) {
        BookHelp.clearCache(book)
    }

    /**
     * 删除指定章节的缓存文件 (单章节, 不影响同书其他章节), 委托 [BookHelp.delContent]。
     */
    override fun delContent(book: Book, chapter: BookChapter) {
        BookHelp.delContent(book, chapter)
    }

    /** 检测指定章节是否已缓存, 委托 [BookHelp.hasContent]。 */
    override fun hasContent(book: Book, chapter: BookChapter): Boolean =
        BookHelp.hasContent(book, chapter)

    /** 清空所有书的章节缓存 (整个 book_cache 目录), 委托 [BookHelp.clearCache]。 */
    override fun clearCache() {
        BookHelp.clearCache()
    }

    /** 清空指定书的章节缓存, 委托 [BookHelp.clearCache]`(book)`。 */
    override fun clearCache(book: Book) {
        BookHelp.clearCache(book)
    }

    /** 重命名缓存文件夹, 委托 [BookHelp.updateCacheFolder]。 */
    override fun updateCacheFolder(oldBook: Book, newBook: Book) {
        BookHelp.updateCacheFolder(oldBook, newBook)
    }

    /**
     * 漫画缓存超量淘汰, 委托 [BookHelp.evictMangaCache] (不删失效书目录)。
     *
     * 该方法为 suspend, 用 [runBlocking] 适配接口的同步签名。
     */
    override fun clearInvalidCache(maxSize: Long) {
        runBlocking {
            BookHelp.evictMangaCache(BookHelpShared.cacheImageFolderName, maxSize)
        }
    }

    /**
     * 删除不在书架的书籍缓存目录 + 漫画缓存超量淘汰,
     * 参数透传给 [BookHelp.clearInvalidBookFolders] (与 [clearInvalidCache] 一致用 [runBlocking] 适配同步签名)。
     */
    override fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        runBlocking {
            BookHelp.clearInvalidBookFolders(validFolderNames, imageSubFolderName, maxSize)
        }
    }
}
