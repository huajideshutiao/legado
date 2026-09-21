package io.legado.app.model

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [ResourceUrlPreloader] 的窗口与跳过规则。
 *
 * 全部用例都走"内存目录已就绪 + 非在架书"这条路 (chapters 非 null、inBookshelf=false),
 * 于是一次 DB 都不碰, 纯 JVM 可跑; 落库分支由播放侧的 upResourceUrl 复用同一条 DAO 调用,
 * 不在这里重复覆盖。
 */
class ResourceUrlPreloaderTest {

    private fun audioBook() = Book(bookUrl = "book://a").apply { type = BookType.audio }

    private fun toc(size: Int) = List(size) { i ->
        BookChapter(url = "u$i", title = "t$i", index = i, bookUrl = "book://a")
    }

    /** 跑完一轮预解析: join 掉 scope 下所有子协程, 不靠 sleep 等。 */
    private fun runPreload(
        book: Book,
        chapters: List<BookChapter>?,
        centerIndex: Int,
        fetch: suspend (BookChapter) -> String,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        ResourceUrlPreloader(scope).preload(
            book = book,
            chapters = chapters,
            centerIndex = centerIndex,
            inBookshelf = false,
            fetch = fetch,
        )
        scope.coroutineContext.job.children.forEach { it.join() }
    }

    @Test
    fun `只预解析前后各一章且先后章再前章`() {
        val chapters = toc(6)
        val hit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), chapters, centerIndex = 3) {
            hit.add(it.index)
            "url-${it.index}"
        }

        assertEquals(listOf(4, 2), hit.toList())
        assertEquals("url-4", chapters[4].resourceUrl)
        assertEquals("url-2", chapters[2].resourceUrl)
        // 中心章与窗口外一律不碰
        assertNull(chapters[3].resourceUrl)
        assertNull(chapters[5].resourceUrl)
        assertNull(chapters[1].resourceUrl)
    }

    @Test
    fun `首章只预解析后一章_末章只预解析前一章`() {
        val first = toc(4)
        val firstHit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), first, centerIndex = 0) {
            firstHit.add(it.index)
            "u"
        }
        assertEquals(listOf(1), firstHit.toList())

        val last = toc(4)
        val lastHit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), last, centerIndex = 3) {
            lastHit.add(it.index)
            "u"
        }
        assertEquals(listOf(2), lastHit.toList())
    }

    @Test
    fun `本地书不预解析`() {
        val local = Book(bookUrl = "book://a").apply { type = BookType.local }
        val chapters = toc(4)
        val hit = CopyOnWriteArrayList<Int>()
        runPreload(local, chapters, centerIndex = 1) {
            hit.add(it.index)
            "u"
        }
        assertEquals(emptyList<Int>(), hit.toList())
    }

    @Test
    fun `卷名章与已有直链的章都跳过`() {
        val chapters = toc(4)
        chapters[2].isVolume = true
        chapters[0].resourceUrl = "old"
        val hit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), chapters, centerIndex = 1) {
            hit.add(it.index)
            "new"
        }

        assertEquals(emptyList<Int>(), hit.toList())
        // 已有直链不被覆盖
        assertEquals("old", chapters[0].resourceUrl)
        assertNull(chapters[2].resourceUrl)
    }

    @Test
    fun `空内容不写回_单章失败不影响另一章`() {
        val chapters = toc(4)
        runPreload(audioBook(), chapters, centerIndex = 2) { chapter ->
            when (chapter.index) {
                3 -> ""
                else -> error("解析失败")
            }
        }
        assertNull(chapters[3].resourceUrl)
        assertNull(chapters[1].resourceUrl)

        // 后章抛异常也要继续跑前章
        val chapters2 = toc(4)
        val hit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), chapters2, centerIndex = 2) { chapter ->
            hit.add(chapter.index)
            if (chapter.index == 3) error("解析失败") else "url-${chapter.index}"
        }
        assertEquals(listOf(3, 1), hit.toList())
        assertNull(chapters2[3].resourceUrl)
        assertEquals("url-1", chapters2[1].resourceUrl)
    }

    @Test
    fun `内存目录未就绪且非在架书时不查库`() {
        // chapters=null + inBookshelf=false → 不该碰 AppDbProviders (未注册, 碰了就抛)
        val hit = CopyOnWriteArrayList<Int>()
        runPreload(audioBook(), null, centerIndex = 2) {
            hit.add(it.index)
            "u"
        }
        assertEquals(emptyList<Int>(), hit.toList())
    }
}
