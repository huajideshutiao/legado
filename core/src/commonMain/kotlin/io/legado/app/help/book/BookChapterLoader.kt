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
import io.legado.app.model.webBook.PluginSourceDelegates
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
 * 1. **来源对账**: 在架且来自搜索/发现列表的书换成库里那本 ([resolveShelfBook])
 * 2. **书源装载**: 本地书为 null，网络书查 `bookSourceDao`
 * 3. **书籍信息补全**: `tocUrl` 为空时回源 `WebBook.getBookInfoAwait`, 在架则落库
 * 4. **目录装载**: 内存交接 (`IntentData`) -> 本地数据库 (`bookChapterDao`) -> 回源拉取 (`FileBook` / `WebBook`)
 * 5. **在架落库**: 重新拉取后自动同步更新 `books` 表与 `book_chapters` 表
 */
object BookChapterLoader {

    /**
     * 统一装载书籍核心状态 (对应 archive 端 BaseReadViewModel.upBook)。
     *
     * @param book 路由/调用方给的书; 在架且 [isSearchBook] 为真时会换成库里那本
     * @param isSearchBook 书来自搜索/发现列表的瞬态对象 (BookRef.Search); 决定是否做来源对账
     *   (同名异源标记 notShelf / 同源并回书架那本), 对应 archive 的 `book is SearchBook`
     */
    suspend fun upBook(
        book: Book,
        runPreUpdateJs: Boolean = false,
        isSearchBook: Boolean = false,
        forceReload: Boolean = false,
    ): BookLoadResult {
        // 在架判定取一次, 库查询后再纠正 (archive: inBookshelf = !book.isNotShelf 再按对账结果改)。
        // 落库门禁必须用它, 不能就地重读 !book.isNotShelf —— 搜索/发现列表的瞬态副本恰好带着
        // "在架"的 type 位却没有进度/目录, 按它整行 update 会把书架行的进度与元数据抹掉。
        var inShelf = !book.isNotShelf
        // 来源对账: 在架且来自搜索/发现列表 → 用库里那本 (archive upBook 的这一步)
        val curBook = resolveShelfBook(book, isSearchBook)
        // RSS 书没有"详情页/目录页"之分: 抓取地址就是添加时的 url, tocUrl 空时补上。
        // 不改 bookUrl —— 它是 books 表主键、也是章节与进度的键: 原版把它换成常量 "data:"
        // 会让所有 RSS 书挤在同一行/同一章节表上 (后写的 REPLACE 掉先写的), 且 "data:" 在本仓
        // 另有内联数据 URI 的语义 (isDataUrl/AnalyzeUrl)
        if (curBook.isRss && curBook.tocUrl.isEmpty()) {
            curBook.tocUrl = curBook.bookUrl
        }

        val source = if (curBook.isLocal) {
            null
        } else {
            runCatching {
                withContext(IoDispatcher) {
                    AppDbProviders.get().bookSourceDao.getBookSource(curBook.origin)
                }
            }.onFailure {
                AppLog.put("读取书源失败 ${curBook.origin}\n${it.message}", it)
            }.getOrNull()
        }

        if (source == null && !curBook.isLocal) {
            throw IllegalStateException("书源不存在: ${curBook.origin}")
        }

        // 本地书: 从文件重新读详情 (tocUrl/字数/章节数), 对照 archive upBook 的 `FileBook.upBookInfo`
        if (curBook.isLocal) {
            withContext(IoDispatcher) { FileBook.upBookInfo(curBook) }
        }

        // 补读书籍详情 (tocUrl 空, 与 archive 判据一致)
        var runPreJs = runPreUpdateJs
        if (source != null && curBook.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, curBook)
            if (isSearchBook) {
                val dbBook = withContext(IoDispatcher) {
                    val dao = AppDbProviders.get().bookDao
                    dao.getBook(curBook.bookUrl) ?: dao.getBook(curBook.name, curBook.author)
                }
                // 搜索来源的书加载详情后书名可能变化, 同源则并回书架那本, 异源则标记不在书架
                // (对照 archive loadBookInfo, #3652 #4619 #3149); 两分支都得纠正 inShelf,
                // 否则落库门禁用的还是进来时那个值
                if (dbBook != null && dbBook.origin == curBook.origin) {
                    dbBook.updateTo(curBook)
                    inShelf = true
                } else {
                    curBook.addType(BookType.notShelf)
                    inShelf = false
                }
            }
            if (inShelf) {
                runCatching {
                    withContext(IoDispatcher) {
                        // 对照 archive `if (inBookshelf) book.save()`: 去 notShelf 标记 + 有则改无则插
                        val dao = AppDbProviders.get().bookDao
                        curBook.removeType(BookType.notShelf)
                        if (dao.has(curBook.bookUrl)) dao.update(curBook) else dao.insert(curBook)
                    }
                }.onFailure {
                    AppLog.put("保存书籍信息出错\n${it.message}", it)
                }
            }
            // archive 详情分支把 `runPreUpdateJs = inBookshelf` 传给 loadChapterList
            runPreJs = runPreUpdateJs || inShelf
        } else {
            // archive 在 tocUrl 非空/本地书时走 `loadChapterList(book)`, 其 runPreUpdateJs 形参默认 true
            runPreJs = true
        }

        // archive 的目录分支: `!inBookshelf || totalChapterNum == 0 → loadChapterList`。
        // totalChapterNum 为 0 表示目录未装载/已失效, 忽略内存交接与本地库直接回源,
        // 否则会长期用陈旧目录且该列一直修不回来 (内存交接对未入架书仍优先, 见 loadChapterList)
        val forceTocReload = forceReload ||
            (inShelf && !curBook.isLocal && curBook.totalChapterNum == 0)
        val chapters = loadChapterList(curBook, source, runPreJs, forceTocReload)

        return BookLoadResult(
            book = curBook,
            source = source,
            chapterList = chapters,
        )
    }

    /**
     * 来源对账: 在架且来自搜索/发现列表的书换成库里那本 (对照 archive
     * `BaseReadViewModel.upBook` 的 `if (inBookshelf && isSearchBook) book = appDb.bookDao
     * .getBook(bookUrl) ?: getBook(name, author) ?: book`)。
     *
     * 规则单一出处: [upBook] 与文本阅读装载都走它。缺这步会拿瞬态副本 (无进度、无
     * totalChapterNum、tocUrl 空) 去取章/起播, 回源拉一次多余详情, 并把书架行整行覆盖。
     *
     * @param isSearchBook 书来自搜索/发现列表 (路由的 BookRef.Search)
     * @return 库中那本 (bookUrl 不成则按 name+author 找, 都没有则原书)
     */
    suspend fun resolveShelfBook(book: Book, isSearchBook: Boolean): Book {
        if (book.isNotShelf || !isSearchBook) return book
        return withContext(IoDispatcher) {
            val dao = AppDbProviders.get().bookDao
            dao.getBook(book.bookUrl) ?: dao.getBook(book.name, book.author)
        } ?: book
    }

    /**
     * 统一加载/确保书籍章节列表 (内存交接 -> 本地数据库 -> 回源拉取 -> 在架落库)。
     */
    suspend fun loadChapterList(
        book: Book,
        source: BookSource? = null,
        runPreUpdateJs: Boolean = false,
        forceReload: Boolean = false,
    ): List<BookChapter> {
        // 1. 内存交接 (IntentData)
        if (!forceReload) {
            val handoff = IntentData.chapterList?.takeIf { it.firstOrNull()?.bookUrl == book.bookUrl }
            if (!handoff.isNullOrEmpty()) {
                return handoff
            }
        }

        // 2. 本地数据库 (DB 优先)
        if (!forceReload) {
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
        } catch (e: Throwable) {
            AppLog.put(tocFailMessage(source, e), e)
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

    /** 目录失败日志唯一记录点: 委派源由委派给出带来源标识的文案, 其余用通用文案。 */
    private fun tocFailMessage(source: BookSource?, e: Throwable): String {
        if (source != null) {
            PluginSourceDelegates.resolve(source)?.tocFailMessage(source, e)?.let { return it }
        }
        return "获取目录失败\n${e.message}"
    }
}
