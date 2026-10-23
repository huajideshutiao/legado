package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读位置写入来源的金样。
 *
 * 朗读推进、朗读自己翻页、用户翻页写的是同一个位置字段, 谁写的决定朗读要不要跟着重开。
 * 这里把三类写入实际产出的来源钉住 —— 只测"手工构造来源"的用例抓不到写入口漏标。
 */
class ReadBookPositionOriginTest {

    @Test
    fun `朗读推进写入的位置标为朗读来源`() {
        val readBook = ReadBookShared()

        readBook.updateDurChapterPosByReadAloud(120)

        assertEquals(120, readBook.positionSnapshot.value.chapterPosition)
        assertEquals(PositionOrigin.ReadAloud, readBook.positionSnapshot.value.origin)
    }

    @Test
    fun `用户写入的位置标为用户来源`() {
        val readBook = ReadBookShared()

        readBook.writePosition(80, PositionOrigin.User)

        assertEquals(PositionOrigin.User, readBook.positionSnapshot.value.origin)
    }

    @Test
    fun `来源不会被读取动作复位`() {
        val readBook = ReadBookShared()

        readBook.updateDurChapterPosByReadAloud(120)

        // 读一次不会把来源抹掉: 否则位置没变时标志滞留, 下一次用户翻页会被误吞
        assertEquals(PositionOrigin.ReadAloud, readBook.positionSnapshot.value.origin)
        assertEquals(120, readBook.positionSnapshot.value.chapterPosition)
        assertEquals(PositionOrigin.ReadAloud, readBook.positionSnapshot.value.origin)
    }

    @Test
    fun `位置值与来源成对发布, 不会只更新其中一个`() {
        val readBook = ReadBookShared()

        readBook.updateDurChapterPosByReadAloud(200)

        val snapshot = readBook.positionSnapshot.value
        assertEquals(200, snapshot.chapterPosition)
        assertEquals(200, readBook.durChapterPosValue)
        assertEquals(PositionOrigin.ReadAloud, snapshot.origin)
    }
}
