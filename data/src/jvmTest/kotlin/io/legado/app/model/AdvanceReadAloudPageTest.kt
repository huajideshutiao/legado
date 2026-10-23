package io.legado.app.model

import io.legado.app.help.tts.ReadAloudChapterSignal
import io.legado.app.help.tts.ReadAloudChapterUpdate
import io.legado.app.help.tts.ReadAloudPosition
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 朗读进度 → 阅读页翻页联动的行为面: 以页位差驱动补翻;
 * 章号不符、不超前、翻不动都如实返回失败, 不虚报完成。
 */
class AdvanceReadAloudPageTest {

    /** 活动阅读页替身: 阅读状态用真实 ReadBookShared, 翻页成败可注入。 */
    private class FakeOwner(
        override val readBook: ReadBookShared,
        private val nextPage: () -> Boolean,
    ) : ActiveReadAloudOwner {
        override val positionUpdates: Flow<ReadAloudPosition> = emptyFlow()
        override val chapterUpdates: Flow<ReadAloudChapterUpdate> = emptyFlow()
        override val chapterSignals: Flow<ReadAloudChapterSignal> = emptyFlow()
        override fun chapterText(chapterIndex: Int): String? = null
        override fun moveToChapter(chapterIndex: Int) = Unit
        override fun moveToNextPage(origin: PositionOrigin): Boolean = nextPage()
        override fun uploadProgress() = Unit
        override fun prefetchChapter(chapterIndex: Int) = Unit
    }

    private lateinit var readBook: ReadBookShared
    private var owner: FakeOwner? = null

    /** 三页章节: 页首字符偏移 0 / 10 / 20。 */
    private val chapter = TextChapterShared(0, listOf(page(0), page(10), page(20)))

    private fun page(chapterPosition: Int) = TextPage().apply {
        addLine(TextLine(text = "行文", chapterPosition = chapterPosition))
    }

    @Before
    fun setUp() {
        readBook = ReadBookShared().apply {
            curChapter = chapter
            durChapterPosValue = 0
        }
        attachOwner { readBook.moveToNextPage(PositionOrigin.ReadAloud) }
    }

    @After
    fun tearDown() {
        owner?.let { ActiveReadAloudHostPorts.detach(it) }
    }

    private fun attachOwner(nextPage: () -> Boolean) {
        FakeOwner(readBook, nextPage).let {
            owner = it
            ActiveReadAloudHostPorts.attach(it)
        }
    }

    @Test
    fun `朗读位置未超前当前页时不翻页`() {
        listOf(0, 5, 9).forEach { pos ->
            readBook.durChapterPosValue = 0
            assertFalse("页内位置 $pos 不该触发翻页", ActiveReadAloudHostPorts.advanceReadAloudPage(0, pos))
            assertEquals("位置 $pos 不该挪动阅读位置", 0, readBook.durChapterPosValue)
        }
    }

    @Test
    fun `朗读位置超前时逐页补翻到目标页并标记朗读来源`() {
        listOf(15 to 1, 10 to 1, 25 to 2).forEach { (pos, expectedPage) ->
            readBook.durChapterPosValue = 0
            assertTrue("位置 $pos 超前该翻页", ActiveReadAloudHostPorts.advanceReadAloudPage(0, pos))
            assertEquals(
                "位置 $pos 应翻到第 $expectedPage 页页首",
                chapter.getReadLength(expectedPage),
                readBook.durChapterPosValue,
            )
            assertEquals(PositionOrigin.ReadAloud, readBook.positionSnapshot.value.origin)
        }
    }

    @Test
    fun `章号不匹配时不翻页`() {
        listOf(1, 5).forEach { index ->
            assertFalse(ActiveReadAloudHostPorts.advanceReadAloudPage(index, 25))
        }
        assertEquals(0, readBook.durChapterPosValue)
    }

    @Test
    fun `翻不动时如实返回失败不虚报完成`() {
        attachOwner { false }
        assertFalse(ActiveReadAloudHostPorts.advanceReadAloudPage(0, 25))
        assertEquals("翻页失败时阅读位置不得被虚推进", 0, readBook.durChapterPosValue)
    }
}
