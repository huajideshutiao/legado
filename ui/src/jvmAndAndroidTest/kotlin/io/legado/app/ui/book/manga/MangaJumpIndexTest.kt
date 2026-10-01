package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [jumpIndexFor] 定位金样：请求页号按目标章**真实图片数**归一。
 *
 * 口径对照原版 `ReadMangaViewModel.buildMangaContent` 的
 * `durChapterPos.coerceIn(0, imageCount - 1)` 与 `ReadMangaActivity.upContent` 的
 * `scrollToPositionWithOffset(pos, 0)`：超出新章节页数的保存位置/书签落在**末图**，
 * 不是章首标题条；零图片的卷章没有图片可夹，定位它的标题条目。
 */
class MangaJumpIndexTest {

    /** 三章 items：前章 1 图、当前章 `imageCount` 图（带标题条）、后章 1 图。 */
    private fun items(imageCount: Int, withTitle: Boolean = true): List<BaseMangaPage> {
        val list = arrayListOf<BaseMangaPage>()
        list.add(page(chapter = 0, index = 0, imageCount = 1))
        if (withTitle) {
            list.add(ReaderLoading(chapterIndex = 1, index = -1, mMessage = "阅读 第2章"))
        }
        for (i in 0 until imageCount) {
            list.add(page(chapter = 1, index = i, imageCount = imageCount))
        }
        list.add(page(chapter = 2, index = 0, imageCount = 1))
        return list
    }

    private fun page(chapter: Int, index: Int, imageCount: Int): MangaPage =
        MangaPage(
            chapterIndex = chapter,
            chapterSize = 3,
            mImageUrl = "https://example.com/$chapter-$index.jpg",
            index = index,
            imageCount = imageCount,
        )

    /** 第 2 章（下标 1）的请求；目标章必在 [items] 内，null 即定位失效。 */
    private fun jumpAt(page: Int, list: List<BaseMangaPage>): Int =
        jumpIndexFor(requestAt(page), list)
            ?: error("目标章已在列表内, 不应返回 null")

    private fun requestAt(page: Int, id: Int = 1) =
        MangaPositionRequest(
            id = id,
            bookUrl = "test://book",
            generation = 0,
            chapterIndex = 1,
            page = page,
        )

    /** 页号在范围内：精确命中该图，不落到标题条。 */
    @Test
    fun `范围内页号精确命中图片`() {
        val list = items(imageCount = 3)
        // 下标：0=前章图, 1=标题条, 2..4=当前章三图, 5=后章图
        assertEquals(2, jumpAt(0, list))
        assertEquals(3, jumpAt(1, list))
        assertEquals(4, jumpAt(2, list))
    }

    /** 页号超出新章节页数：夹到末图（原版 coerceIn 口径），不是标题条。 */
    @Test
    fun `页号超出页数定位末图`() {
        val list = items(imageCount = 3)
        assertEquals(4, jumpAt(3, list))
        assertEquals(4, jumpAt(99, list))
    }

    /** 停在章末的负编码进度（3 图章末 = -2）按原版 abs 读取后同样是末图。 */
    @Test
    fun `负编码进度按绝对值读取`() {
        val list = items(imageCount = 3)
        assertEquals(4, jumpAt(-2, list))
        assertEquals(4, jumpAt(-99, list))
    }

    /** hideMangaTitle：章内无标题条，首图就是章首。 */
    @Test
    fun `无标题条时首页为第一张图`() {
        val list = items(imageCount = 3, withTitle = false)
        assertEquals(1, jumpAt(0, list))
        assertEquals(3, jumpAt(3, list))
    }

    /** 零图片的卷章只有标题条目，定位该标题。 */
    @Test
    fun `零图片卷章定位标题`() {
        val list = listOf<BaseMangaPage>(
            ReaderLoading(chapterIndex = 1, index = -1, mMessage = "第一卷", isVolume = true),
            page(chapter = 2, index = 0, imageCount = 1),
        )
        assertEquals(0, jumpAt(0, list))
        assertEquals(0, jumpAt(5, list))
    }

    /** 目标章尚未进入 items：本轮不消费，返回 null 等内容到达。 */
    @Test
    fun `目标章不在列表返回null`() {
        val list = items(imageCount = 3)
        assertNull(jumpIndexFor(requestAt(page = 0, id = 2).copy(chapterIndex = 7), list))
        assertNull(jumpIndexFor(requestAt(page = 0, id = 2), emptyList()))
    }
}
