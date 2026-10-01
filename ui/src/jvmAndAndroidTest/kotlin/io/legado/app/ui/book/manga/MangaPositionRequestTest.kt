package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4 定位闭环纯逻辑回归：请求身份（id 单调不复位）、回执规则、书籍/代际归属核对与下标漂移。
 * 结构性部分（无纯逻辑单元，评审保证）：
 * - 无请求连续滚动：消费 effect 首行 `?: return@LaunchedEffect`，滚动跨章/预下载的内容
 *   重建不产生任何定位登记（辅助断言见 `无请求状态不产生定位`）；
 * - 生效一刻解析与挂起滚动期间换批不回执：渲染层解析器 + items 引用比对；
 * - 旧装载回调按发起代际拒收并补装：contentLoadFinish 的代际对账；
 * - F6（切章/云进度被接受后才发请求）：dispatch 内的调用顺序守卫；
 * - F5（页号按真实图片数归一）金样：[MangaJumpIndexTest]。
 */
class MangaPositionRequestTest {

    /** 三章 items：前章 1 图、当前章 3 图（带标题条）、后章 1 图。下标: 0=前章图, 1=标题条, 2..4=当前章图, 5=后章图 */
    private fun items(): List<BaseMangaPage> {
        val list = arrayListOf<BaseMangaPage>()
        list.add(page(chapter = 0, index = 0))
        list.add(ReaderLoading(chapterIndex = 1, index = -1, mMessage = "阅读 第2章"))
        for (i in 0 until 3) {
            list.add(page(chapter = 1, index = i))
        }
        list.add(page(chapter = 2, index = 0))
        return list
    }

    private fun page(chapter: Int, index: Int): MangaPage = MangaPage(
        chapterIndex = chapter,
        chapterSize = 3,
        mImageUrl = "https://example.com/$chapter-$index.jpg",
        index = index,
    )

    private fun request(
        chapterIndex: Int = 1,
        page: Int = 0,
        bookUrl: String = BOOK,
        generation: Int = 1,
        id: Int = 1,
    ) = MangaPositionRequest(
        id = id,
        bookUrl = bookUrl,
        generation = generation,
        chapterIndex = chapterIndex,
        page = page,
    )

    /** 重复同章：连续两次发布请求 id 单调不同 —— "再点一次当前章"必须产生新身份重启定位。 */
    @Test
    fun `重复同章请求id单调不复位`() {
        var state = MangaReaderUiState()
        state = state.withJumpRequest(1, 0, BOOK, 1)
        val first = state.positionRequest!!
        state = state.withJumpRequest(1, 0, BOOK, 1)
        val second = state.positionRequest!!
        assertEquals(first.chapterIndex, second.chapterIndex)
        assertEquals(first.page, second.page)
        assertEquals(first.id + 1, second.id)
    }

    /** 旧请求回执：过期回执不清新请求；清空后 id 真源保留，下一请求不复位（旧回执撞不上新请求）。 */
    @Test
    fun `旧回执不清新请求且清空后id不复位`() {
        var state = MangaReaderUiState()
        state = state.withJumpRequest(1, 0, BOOK, 1)
        val first = state.positionRequest!!
        state = state.withJumpRequest(2, 0, BOOK, 1)
        val second = state.positionRequest!!
        // 迟到的旧回执: 不清新请求
        state = state.withRequestReceipt(first)
        assertSame(second, state.positionRequest)
        // 当前回执: 清空, 但 lastRequestId 保留
        state = state.withRequestReceipt(second)
        assertNull(state.positionRequest)
        assertEquals(second.id, state.lastRequestId)
        // 清空后再发: id 继续 +1, 不重回 1
        state = state.withJumpRequest(0, 0, BOOK, 1)
        assertEquals(second.id + 1, state.positionRequest!!.id)
    }

    /** 同书同章旧代批次不消费：新请求先于 combine 落到旧 items 上（异步滞后窗口）必须等待。 */
    @Test
    fun `同书同章旧代批次不消费`() {
        // 反例要件: 新请求 + 同书 + 目标章在 items 内 + curFinish=true, 仅代际落后
        val stale = jumpResolutionFor(request(generation = 2), BOOK, 1, items(), true)
        assertTrue(stale is MangaJumpResolution.Wait)
        // 同代与更新代批次均可消费
        assertTrue(jumpResolutionFor(request(generation = 1), BOOK, 1, items(), true) is MangaJumpResolution.Apply)
        assertTrue(jumpResolutionFor(request(generation = 1), BOOK, 2, items(), true) is MangaJumpResolution.Apply)
    }

    /** 换书旧 content：与内容自身快照核对归属, 异书遗留请求作废清除, 与"当前书"读值无关。 */
    @Test
    fun `换书旧content按快照归属作废`() {
        // 内容仍是旧书快照 (currentBook 已切新书也不影响判定)
        assertTrue(
            jumpResolutionFor(request(bookUrl = NEW), OLD, 9, items(), true)
                is MangaJumpResolution.Stale
        )
        // 尚无内容批次: 保留等待
        assertTrue(jumpResolutionFor(request(), "", 0, emptyList(), false) is MangaJumpResolution.Wait)
        // 内容未就绪 (setProgress 同章刷新发布的部分批次): 不消费
        assertTrue(jumpResolutionFor(request(), BOOK, 1, items(), false) is MangaJumpResolution.Wait)
    }

    /** 前章插入导致下标漂移：生效一刻重解析跟随章节+页号身份，同一张图不被裸下标带错。 */
    @Test
    fun `前章插入后重解析不漂移`() {
        val jump = request()
        assertEquals(2, jumpIndexFor(jump, items()))
        // 前两章插入到表头, 同一张图 (chapter=1, index=0) 的下标整体后移
        val drifted = buildList {
            add(page(chapter = 7, index = 0))
            add(ReaderLoading(chapterIndex = 8, index = -1, mMessage = "阅读 第9章"))
            add(page(chapter = 8, index = 0))
            addAll(items())
        }
        val applied = jumpIndexFor(jump, drifted)
        assertNotEquals(2, applied)
        // 仍解析到同一张图: chapter=1, index=0 (下标随新列表重算)
        assertEquals(1, drifted[applied!!].chapterIndex)
        assertEquals(0, drifted[applied].index)
    }

    /** 无请求连续滚动：空状态不产生定位，迟到回执不改变状态（内容重建无人重定位）。 */
    @Test
    fun `无请求状态不产生定位`() {
        val state = MangaReaderUiState()
        assertNull(state.positionRequest)
        assertSame(
            state,
            state.withRequestReceipt(request(id = 1)),
        )
    }

    companion object {
        private const val BOOK = "test://book"
        private const val OLD = "test://old-source"
        private const val NEW = "test://new-source"
    }
}
