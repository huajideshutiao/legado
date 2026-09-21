package io.legado.app.model

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBookInitStateTest {

    @Test
    fun `切换书籍时清目录并重置到传入进度`() {
        val book = Book(bookUrl = "new").apply {
            durChapterIndex = 7
            durChapterPos = -123
        }
        val state = calculateReadBookInitState("old", "old", 2, book)

        assertTrue(state.isDifferentBook)
        assertTrue(state.shouldDropChapterList)
        assertTrue(state.shouldResetProgress)
        assertEquals(7, state.chapterIndex)
        assertEquals(123, state.chapterPosition)
    }

    @Test
    fun `同书同进度保留窗口且只在目录属于别书时丢弃目录`() {
        val book = Book(bookUrl = "same").apply {
            durChapterIndex = 3
            durChapterPos = 45
        }
        val keep = calculateReadBookInitState("same", "same", 3, book)
        assertFalse(keep.isDifferentBook)
        assertFalse(keep.shouldDropChapterList)
        assertFalse(keep.shouldResetProgress)

        val staleToc = calculateReadBookInitState("same", "other", 3, book)
        assertTrue(staleToc.shouldDropChapterList)
        assertFalse(staleToc.shouldResetProgress)
    }

    @Test
    fun `同书数据库进度改变时重置窗口`() {
        val book = Book(bookUrl = "same").apply {
            durChapterIndex = 5
            durChapterPos = 9
        }
        val state = calculateReadBookInitState("same", "same", 4, book)

        assertFalse(state.isDifferentBook)
        assertTrue(state.shouldResetProgress)
        assertEquals(5, state.chapterIndex)
        assertEquals(9, state.chapterPosition)
    }
}
