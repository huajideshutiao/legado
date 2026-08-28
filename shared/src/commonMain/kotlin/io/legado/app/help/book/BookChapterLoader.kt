package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 书籍与目录装载结果。
 */
data class BookLoadResult(
    val book: Book,
    val source: BookSource?,
    val chapterList: List<BookChapter>,
)

/**
 * 跨平台书籍与目录装载统一工具类 (shared commonMain)。
 *
 * 完整还原 archive 分支 [io.legado.app.base.BaseReadViewModel.upBook] 与
 * [io.legado.app.base.BaseReadViewModel.loadChapterList] 的核心流转管线:
 * 1. **书源装载**: 本地书为 null，网络书查 `bookSourceDao`
 * 2. **书籍信息补全**: 若 `tocUrl` 为空或 `totalChapterNum == 0`，回源 `WebBook.getBookInfoAwait` 并在架落库
 * 3. **目录装载**: 内存交接 (`IntentData`) -> 本地数据库 (`bookChapterDao`) -> 回源拉取 (`FileBook` / `WebBook`)
 * 4. **在架落库**: 重新拉取后自动同步更新 `books` 表与 `book_chapters` 表
 */
object BookChapterLoader {

    /**
     * 统一装载书籍核心状态 (对应 archive 端 BaseReadViewModel.upBook)。
     */
    suspend fun upBook(
        book: Book,
        runPreUpdateJs: Boolean = false,
        isSearchBook: Boolean = false,
    ): BookLoadResult {
        if (book.isRss) {
            book.tocUrl = book.bookUrl
            book.bookUrl = "data:"
        }

        val source = if (book.isLocal) {
            null
        } else {
            runCatching {
                withContext(IoDispatcher) {
                    AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                }
            }.onFailure {
                AppLog.put("读取书源失败 ${book.origin}\n${it.message}", it)
            }.getOrNull()
        }

        if (source == null && !book.isLocal) {
            throw IllegalStateException("书源不存在: ${book.origin}")
        }

        // 补读书籍详情 (tocUrl 空或 totalChapterNum == 0)
        if (source != null && (book.tocUrl.isEmpty() || book.totalChapterNum == 0)) {
            WebBook.getBookInfoAwait(source, book)
            if (isSearchBook) {
                val dbBook = withContext(IoDispatcher) {
                    val dao = AppDbProviders.get().bookDao
                    dao.getBook(book.bookUrl) ?: dao.getBook(book.name, book.author)
                }
                // 搜索来源的书加载详情后书名可能变化, 同源则并回书架那本, 异源则标记不在书架
                // (对照 archive loadBookInfo, #3652 #4619 #3149)
                if (dbBook != null && dbBook.origin == book.origin) {
                    dbBook.updateTo(book)
                } else {
                    book.addType(BookType.notShelf)
                }
            }
            if (!book.isNotShelf) {
                runCatching {
                    withContext(IoDispatcher) {
                        AppDbProviders.get().bookDao.update(book)
                    }
                }.onFailure {
                    AppLog.put("保存书籍信息出错\n${it.message}", it)
                }
            }
        }

        val chapters = loadChapterList(book, source, runPreUpdateJs)

        return BookLoadResult(
            book = book,
            source = source,
            chapterList = chapters,
        )
    }

    /**
     * 统一加载/确保书籍章节列表 (内存交接 -> 本地数据库 -> 回源拉取 -> 在架落库)。
     */
    suspend fun loadChapterList(
        book: Book,
        source: BookSource? = null,
        runPreUpdateJs: Boolean = false,
    ): List<BookChapter> {
        // 1. 内存交接 (IntentData)
        val handoff = IntentData.chapterList?.takeIf { it.firstOrNull()?.bookUrl == book.bookUrl }
        if (!handoff.isNullOrEmpty()) {
            return handoff
        }

        // 2. 本地数据库 (DB 优先)
        val dbList = runCatching {
            withContext(IoDispatcher) {
                AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl)
            }
        }.onFailure {
            AppLog.put("读取本地目录失败\n${it.message}", it)
        }.getOrNull()
        if (!dbList.isNullOrEmpty()) {
            return dbList
        }

        // 3. 回源拉取与落库
        return fetchFromSource(book, source, runPreUpdateJs)
    }

    /**
     * 回源拉取章节列表并落库 (对应 archive 端 BaseReadViewModel.loadChapterList)。
     */
    suspend fun fetchFromSource(
        book: Book,
        source: BookSource?,
        runPreUpdateJs: Boolean = false,
    ): List<BookChapter> {
        val oldBook = book.copy()
        val list: List<BookChapter> = try {
            if (book.isLocal) {
                withContext(IoDispatcher) { FileBook.getChapterList(book) }
            } else {
                val bs = source ?: throw IllegalStateException("书源不存在")
                WebBook.getChapterListAwait(bs, book, runPreUpdateJs).getOrThrow()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("获取目录失败\n${e.message}", e)
            throw e
        }

        if (!book.isNotShelf && list.isNotEmpty()) {
            runCatching {
                withContext(IoDispatcher) {
                    val appDb = AppDbProviders.get()
                    if (oldBook.bookUrl == book.bookUrl) {
                        appDb.bookDao.update(book)
                    } else {
                        appDb.bookDao.replace(oldBook, book)
                        BookStorageProviders.get().updateCacheFolder(oldBook, book)
                    }
                    appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                    appDb.bookChapterDao.insert(*list.toTypedArray())
                }
            }.onFailure {
                AppLog.put("目录落库失败\n${it.message}", it)
            }
        }

        return list
    }
}
