package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    /**
     * 多语种叠加: 后续份歌词的时间戳与首份不必逐位相等。
     *
     * 逐字歌词精确到毫秒, 而同一首歌的译文常常只到 10 毫秒 (QQ 音乐) —— 逐位相等会把译文整段丢掉。
     */
    @Test
    fun `译文时间戳精度低于原文时仍能叠加`() {
        // 原文毫秒值 - 译文毫秒值: 9 / 1 / 0, 覆盖截断误差的整个区间
        val cases = listOf(
            "[00:09.559]The club isn't the best" to "[00:09.55]俱乐部不是最好的地方",
            "[00:09.551]The club isn't the best" to "[00:09.55]俱乐部不是最好的地方",
            "[00:09.550]The club isn't the best" to "[00:09.55]俱乐部不是最好的地方",
            // 两份同为毫秒 (网易云双语)
            "[00:12.521]So the bar is where I go" to "[00:12.521]所以酒吧很适合我",
        )
        for ((main, trans) in cases) {
            val lines = LrcParser.parse(listOf(main, trans)).lines
            assertEquals(1, lines.size)
            assertEquals(
                main.substringAfter(']') + "\n" + trans.substringAfter(']'),
                lines[0].text
            )
        }
    }

    /** 偏差超过 10 毫秒就是另一句, 不能挂到邻句上。 */
    @Test
    fun `译文时间戳偏差过大时丢弃`() {
        val cases = listOf(
            // 差 11 毫秒
            "[00:09.551]first line" to "[00:09.54]译文",
            // 差 500 毫秒, 且原文里就有更近的句子
            "[00:09.551]first line\n[00:10.000]second line" to "[00:10.05]译文",
        )
        for ((main, trans) in cases) {
            val lines = LrcParser.parse(listOf(main, trans)).lines
            assertTrue(lines.none { it.text.contains('\n') })
        }
    }

    /** 首份歌词里没有的行, 后续份不能凭空插进来 (译文常常比原词多出行)。 */
    @Test
    fun `译文不引入新条目`() {
        val main = "[00:09.551]first line\n[00:12.521]second line"
        val trans = "[00:09.55]第一句\n[00:11.00]原文没有的一句\n[00:12.52]第二句"
        val lines = LrcParser.parse(listOf(main, trans)).lines
        assertEquals(2, lines.size)
        assertEquals("first line\n第一句", lines[0].text)
        assertEquals("second line\n第二句", lines[1].text)
    }

    /** 逐字采样只认首份; 叠加的译文排在原文之后, 不产生自己的采样点。 */
    @Test
    fun `逐字歌词叠加译文后采样仍只来自原文`() {
        val main = "[00:09.551]<00:09.551>The <00:09.700>club <00:10.274>isn't"
        val trans = "[00:09.55]俱乐部"
        val lines = LrcParser.parse(listOf(main, trans)).lines
        assertEquals(1, lines.size)
        assertEquals("The club isn't\n俱乐部", lines[0].text)
        assertEquals(3, lines[0].words.size)
        assertEquals(listOf(0, 4, 9), lines[0].words.map { it.charIndex })
    }

    /** 三份歌词 (原词 + 译文 + 罗马音) 按同一句叠加成三行。 */
    @Test
    fun `多份歌词按句叠加成多行`() {
        val main = "[00:09.551]The club"
        val lines = LrcParser.parse(listOf(main, "[00:09.55]俱乐部", "[00:09.55]the club")).lines
        assertEquals("The club\n俱乐部\nthe club", lines[0].text)
    }
}
