package io.legado.app.model.rss

import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.TempTestDb
import io.legado.app.data.registerTempTestDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * RSS 上架落库规则 (真实 Room 库, 不联网)。
 *
 * 覆盖两处线上缺陷:
 * 1. 上架必须把已装载的文章列表一并落库 (对照原 `BaseReadViewModel.addToBookshelf` 的
 *    `chapterListData?.let { insert }`), 否则进阅读页要回源重拉一次目录;
 * 2. 章节与进度必须挂在 RSS 书**自己的** bookUrl 上 —— 原版把 bookUrl 写成常量 `"data:"`,
 *    多本 RSS 书会挤在同一行与同一章节表上。
 */
class RssHelpAddToBookshelfTest {

    private lateinit var driver: TempTestDb
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        driver = registerTempTestDb("legado-rss-test")
        db = driver.appDatabase
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `上架落目录并按真实 url 归行`() = runBlocking {
        insertSource("rss://a")
        // 同名书带进度: 上架时按原语义迁移 (order 已非 0, 不会被置底逻辑改写)
        db.bookDao.insert(
            Book(
                bookUrl = "rss://a/old",
                origin = "rss://a",
                name = "源A",
                author = "",
                durChapterIndex = 4,
                durChapterPos = 30,
                durChapterTitle = "第5篇",
                order = 1,
                type = BookType.rss,
            )
        )
        val feed = "rss://a/feed"
        val book = Book(
            bookUrl = feed,
            origin = "rss://a",
            name = "源A",
            author = "",
            type = BookType.rss or BookType.notShelf,
        )
        val chapters = List(3) { i ->
            BookChapter(bookUrl = feed, title = "第${i + 1}篇", index = i, url = "$feed/$i")
        }

        RssHelp.addToBookshelf(book, chapters)

        val row = db.bookDao.getBook(feed)!!
        // 同名书进度已迁移 + 去掉 notShelf 位
        assertEquals(4, row.durChapterIndex)
        assertEquals(30, row.durChapterPos)
        assertEquals("第5篇", row.durChapterTitle)
        assertFalse(row.isNotShelf)
        // 目录随上架落库
        assertEquals(3, db.bookChapterDao.getChapterList(feed).size)
    }

    @Test
    fun `两本 RSS 书各自归行目录不串`() = runBlocking {
        insertSource("rss://b")
        db.bookDao.insert(Book(bookUrl = "rss://b/seed", origin = "rss://b", name = "种子", author = "", order = 1))
        val feedA = "rss://b/feed-a"
        val feedB = "rss://b/feed-b"
        val bookA = Book(bookUrl = feedA, origin = "rss://b", name = "源A", author = "", type = BookType.rss)
        val bookB = Book(bookUrl = feedB, origin = "rss://b", name = "源B", author = "", type = BookType.rss)

        RssHelp.addToBookshelf(bookA, List(2) { chapter(feedA, it) })
        RssHelp.addToBookshelf(bookB, List(4) { chapter(feedB, it) })

        // 两行都在 (主键不撞车), 且各自目录互不混
        assertNotNull(db.bookDao.getBook(feedA))
        assertNotNull(db.bookDao.getBook(feedB))
        assertEquals(2, db.bookChapterDao.getChapterList(feedA).size)
        assertEquals(4, db.bookChapterDao.getChapterList(feedB).size)
    }

    private fun chapter(bookUrl: String, index: Int) = BookChapter(
        bookUrl = bookUrl,
        title = "第${index + 1}篇",
        index = index,
        url = "$bookUrl/$index",
    )

    private suspend fun insertSource(url: String) {
        db.bookSourceDao.insert(BookSource(bookSourceUrl = url, bookSourceName = "测试RSS源"))
    }
}
