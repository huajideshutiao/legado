package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.AndroidBookStorage.clearInvalidCache
import io.legado.app.help.storage.DataStorageProviders
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

    /** 章节缓存根路径, 取 [DataStorageProviders] 的 `chapterCacheDir` (= [BookHelp.cachePath])。 */
    override val rootPath: String
        get() = DataStorageProviders.get().chapterCacheDir

    /** 获取书籍缓存文件夹名, 委托 [Book.getFolderName]。 */
    override fun getFolderName(book: Book): String = book.getFolderName()

    /** 列出书籍缓存文件夹下所有章节文件名, 委托 [BookHelp.getChapterFiles]。 */
    override fun getChapterFiles(book: Book): List<String> =
        BookHelp.getChapterFiles(book).toList()

    /** 缓存目录下按文件名判断存在性, 委托 [BookHelp.getCacheFile] (直查文件系统, 不走 getChapterFiles 短路)。 */
    override fun hasCacheFile(book: Book, fileName: String): Boolean =
        BookHelp.getCacheFile(book, fileName).exists()

    /** 缓存目录下按文件名读文本, 文件不存在返回 null。 */
    override fun readCacheFile(book: Book, fileName: String): String? {
        val file = BookHelp.getCacheFile(book, fileName)
        return if (file.exists()) file.readText() else null
    }

    /** 创建空标记文件, 委托 [BookHelp.createCacheFile] (已存在则保留)。 */
    override fun createCacheFile(book: Book, fileName: String) {
        BookHelp.createCacheFile(book, fileName)
    }

    /** 删除缓存目录下指定文件。 */
    override fun deleteCacheFile(book: Book, fileName: String) {
        BookHelp.getCacheFile(book, fileName).delete()
    }

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
     * 漫画缓存超量淘汰, 委托 [BookHelpShared.evictMangaCache] (不删失效书目录)。
     *
     * 该方法为 suspend, 用 [runBlocking] 适配接口的同步签名。
     */
    override fun clearInvalidCache(maxSize: Long) {
        runBlocking {
            BookHelpShared.evictMangaCache(rootPath, BookHelpShared.cacheImageFolderName, maxSize)
        }
    }

    /**
     * 删除不在书架的书籍缓存目录 + 漫画缓存超量淘汰,
     * 委托 [BookHelpShared.clearInvalidBookFolders] (统一编排, 三端共用;
     * 与 [clearInvalidCache] 一致用 [runBlocking] 适配同步签名)。
     */
    override fun clearInvalidBookFolders(
        validFolderNames: Set<String>,
        imageSubFolderName: String,
        maxSize: Long
    ) {
        runBlocking {
            BookHelpShared.clearInvalidBookFolders(
                rootPath, validFolderNames, imageSubFolderName, maxSize
            )
        }
    }
}
