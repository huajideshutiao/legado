package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.AppDbAccessor
import io.legado.app.data.AppDbProviders
import io.legado.app.data.BundledDatabaseDriver
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.IntentData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [BookChapterLoader] 的来源对账与 RSS 身份规则 (真实 Room 库, 全程不联网)。
 *
 * 覆盖两处线上事故的根因:
 * 1. **在架且来自搜索/发现列表的书必须并回库里那本** —— 否则拿瞬态副本 (进度 0、无目录、
 *    totalChapterNum 0) 去装载, 既多跑一次详情请求, 又会把书架行整行覆盖 (进度/分组/排序/
 *    自定义封面简介标签全被抹成默认值)。
 * 2. **RSS 书不再把 bookUrl 写成常量 `"data:"`** —— bookUrl 是 books 表主键 (兼章节/进度键),
 *    写成常量会让多本 RSS 书挤在同一行与同一章节表上。
 *
 * 每个用例都用独立临时库文件 + 显式插入的源/书/章节, 因此不触发任何网络分支:
 * 在架书 tocUrl 非空且库里有目录 → upBook 直接命中本地。
 */
class BookChapterLoaderReconcileTest {

    private lateinit var dbPath: String
    private lateinit var driver: BundledDatabaseDriver
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        dbPath = File.createTempFile("legado-loader-test", ".db").apply { delete() }.absolutePath
        driver = BundledDatabaseDriver(dbPath)
        db = driver.appDatabase
        AppDbProviders.register(TestDbAccessor(db))
        IntentData.chapterList = null
    }

    @After
    fun tearDown() {
        driver.close()
        AppDbProviders.unregister()
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { runCatching { File(it).delete() } }
    }

    // ---------------- 用例 ----------------

    @Test
    fun `在架搜索书并回库里那本且不冲掉书架字段`() = runBlocking {
        val sourceUrl = "src://a"
        val bookUrl = "$sourceUrl/book/1"
        insertSource(sourceUrl)
        db.bookDao.insert(
            Book(
                bookUrl = bookUrl,
                origin = sourceUrl,
                name = "在架书",
                author = "作者",
                tocUrl = "$sourceUrl/toc/1",
                durChapterIndex = 7,
                durChapterPos = 120,
                durChapterTitle = "第8章",
                totalChapterNum = 100,
                group = 3,
                order = -5,
                customCoverUrl = "c.png",
                customIntro = "简介",
            )
        )
        insertChapters(bookUrl, 2)

        // 搜索/发现列表里的瞬态副本: 无进度、无目录、书籍详情字段全空
        val thin = SearchBook(
            bookUrl = bookUrl,
            origin = sourceUrl,
            originName = "测试源",
            name = "在架书",
            author = "作者",
        ).toBook()
        assertEquals(0, thin.durChapterIndex)
        assertEquals("", thin.tocUrl)

        val result = BookChapterLoader.upBook(thin, isSearchBook = true)

        // 换成了库里那本: 进度与目录都来自书架行 (不再从第 0 章起读)
        assertEquals(bookUrl, result.book.bookUrl)
        assertEquals(7, result.book.durChapterIndex)
        assertEquals(2, result.chapterList.size)

        // 书架行没被瞬态副本整行覆盖
        val row = db.bookDao.getBook(bookUrl)!!
        assertEquals(7, row.durChapterIndex)
        assertEquals(120, row.durChapterPos)
        assertEquals("第8章", row.durChapterTitle)
        assertEquals(100, row.totalChapterNum)
        assertEquals(3L, row.group)
        assertEquals(-5, row.order)
        assertEquals("c.png", row.customCoverUrl)
        assertEquals("简介", row.customIntro)
        // 也没有多出一行 (整行 update 走了 has→update 分支)
        assertEquals(1, db.bookDao.all().size)
    }

    @Test
    fun `未入架搜索书不被并走且用内存交接的目录`() = runBlocking {
        val sourceUrl = "src://b"
        insertSource(sourceUrl)
        // 库里有一本同名书 —— 未入架时不能并过去 (否则会把临时的搜索书当成书架书读)
        db.bookDao.insert(
            Book(
                bookUrl = "$sourceUrl/book/shelf",
                origin = sourceUrl,
                name = "同名书",
                author = "作者",
                tocUrl = "$sourceUrl/toc/shelf",
                durChapterIndex = 5,
                totalChapterNum = 10,
            )
        )
        val thin = SearchBook(
            bookUrl = "$sourceUrl/book/temp",
            origin = sourceUrl,
            originName = "测试源",
            name = "同名书",
            author = "作者",
            tocUrl = "$sourceUrl/toc/temp",
        ).toBook()
        thin.addType(BookType.notShelf)
        // 详情页交接的内存目录 (未入架书目录不落库)
        IntentData.chapterList = listOf(
            BookChapter(bookUrl = thin.bookUrl, title = "第一篇", index = 0, url = "u0")
        )

        val result = BookChapterLoader.upBook(thin, isSearchBook = true)

        assertEquals("$sourceUrl/book/temp", result.book.bookUrl)
        assertSame(thin, result.book)
        assertTrue(result.book.isNotShelf)
        assertEquals(1, result.chapterList.size)
        assertEquals("第一篇", result.chapterList.first().title)
        // 未入架书不落库
        assertEquals(1, db.bookDao.all().size)
    }

    @Test
    fun `RSS 书不改身份键只补 tocUrl`() = runBlocking {
        val sourceUrl = "rss://x"
        val feedUrl = "$sourceUrl/feed"
        insertSource(sourceUrl)
        db.bookDao.insert(rssBook(feedUrl, sourceUrl, totalChapterNum = 3))
        insertChapters(feedUrl, 3)

        val result = BookChapterLoader.upBook(
            rssBook(feedUrl, sourceUrl, totalChapterNum = 3),
            isSearchBook = false,
        )

        // 身份键保持真实 feed url, 抓取地址补进 tocUrl
        assertEquals(feedUrl, result.book.bookUrl)
        assertEquals(feedUrl, result.book.tocUrl)
        assertEquals(3, result.chapterList.size)
    }

    @Test
    fun `多本 RSS 书各自成行不互相覆盖`() = runBlocking {
        val sourceUrl = "rss://y"
        val feedA = "$sourceUrl/feed-a"
        val feedB = "$sourceUrl/feed-b"
        insertSource(sourceUrl)
        db.bookDao.insert(rssBook(feedA, sourceUrl, name = "源A", totalChapterNum = 2))
        db.bookDao.insert(rssBook(feedB, sourceUrl, name = "源B", totalChapterNum = 5))
        insertChapters(feedA, 2)
        insertChapters(feedB, 5)

        val a = BookChapterLoader.upBook(
            rssBook(feedA, sourceUrl, name = "源A", totalChapterNum = 2), false,
        )
        val b = BookChapterLoader.upBook(
            rssBook(feedB, sourceUrl, name = "源B", totalChapterNum = 5), false,
        )

        assertEquals(feedA, a.book.bookUrl)
        assertEquals(feedB, b.book.bookUrl)
        assertEquals(2, a.chapterList.size)
        assertEquals(5, b.chapterList.size)
        // 两行都在, 且各自的目录没串
        assertEquals(2, db.bookDao.all().size)
        assertEquals(2, db.bookChapterDao.getChapterList(feedA).size)
        assertEquals(5, db.bookChapterDao.getChapterList(feedB).size)
    }

    // ---------------- fixtures ----------------

    private suspend fun insertSource(url: String) {
        db.bookSourceDao.insert(BookSource(bookSourceUrl = url, bookSourceName = "测试源"))
    }

    /**
     * RSS 书夹。
     *
     * [totalChapterNum] 必须与插入的章节数一致: 在架书 `totalChapterNum == 0` 按 archive
     * 语义会强制回源目录 ([BookChapterLoader.upBook] 的 forceTocReload), 那是联网分支,
     * 测试要靠"库里有目录"命中本地。
     */
    private fun rssBook(
        bookUrl: String,
        origin: String,
        name: String = "RSS源",
        totalChapterNum: Int = 0,
    ) = Book(
        bookUrl = bookUrl,
        origin = origin,
        name = name,
        author = "",
        type = BookType.rss,
        totalChapterNum = totalChapterNum,
    )

    private suspend fun insertChapters(bookUrl: String, count: Int) {
        db.bookChapterDao.insert(
            *Array(count) { i ->
                BookChapter(
                    bookUrl = bookUrl,
                    title = "第${i + 1}篇",
                    index = i,
                    url = "$bookUrl/$i",
                )
            }
        )
    }
}

/** 测试用 DAO 容器: 全部委托真实 [AppDatabase] (17 个 DAO 与产品实现同源)。 */
private class TestDbAccessor(private val db: AppDatabase) : AppDbAccessor {
    override val bookDao get() = db.bookDao
    override val bookSourceDao get() = db.bookSourceDao
    override val bookChapterDao get() = db.bookChapterDao
    override val bookGroupDao get() = db.bookGroupDao
    override val keywordHighlightDao get() = db.keywordHighlightDao
    override val replaceRuleDao get() = db.replaceRuleDao
    override val readRecordDao get() = db.readRecordDao
    override val serverDao get() = db.serverDao
    override val txtTocRuleDao get() = db.txtTocRuleDao
    override val dictRuleDao get() = db.dictRuleDao
    override val sourceFilterRuleDao get() = db.sourceFilterRuleDao
    override val httpTTSDao get() = db.httpTTSDao
    override val cacheDao get() = db.cacheDao
    override val cookieDao get() = db.cookieDao
    override val ruleSubDao get() = db.ruleSubDao
    override val bookmarkDao get() = db.bookmarkDao
    override val searchKeywordDao get() = db.searchKeywordDao
    override val keyboardAssistsDao get() = db.keyboardAssistsDao
    override suspend fun <R> runInTransactionSuspending(block: suspend () -> R): R = block()
}
