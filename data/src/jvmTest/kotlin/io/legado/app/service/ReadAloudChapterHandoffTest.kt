package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读换章与等章的行为金样。
 *
 * 朗读会话的章节推进只有三种结局: 章末直接续读下一章、等下一章正文就绪后续读、确实读不了则停止。
 * 这里用假章节源与假播放端口把三种结局逐一钉住 —— 章末能不能真的不拆会话、未就绪时会不会静默
 * 停在等待里、整章没有可念内容时会不会顺延、越界会不会报错, 都不该靠真机听感判断。
 */
class ReadAloudChapterHandoffTest {

    /** 可变的假章节源: [texts] 为 null 表示该章正文尚未排版就绪。 */
    private class FakeChapterSource(
        override val chapterCount: Int,
        val texts: MutableMap<Int, String?>,
        val paged: MutableSet<Int>,
    ) : ReadAloudChapterDataPort {

        private val planBuilder = ReadAloudPlanBuilder()

        override fun loadChapterPlan(
            chapterIndex: Int,
            chapterPosition: Int,
            fromLastSpeakable: Boolean,
        ): ReadAloudChapterPlan? {
            // 即使某处还能拿到文本 (缓存原文), 未排版的章也照样给得出计划:
            // 这样"未排版就不开念"才是被生产代码守住的, 而不是被假数据挡住的
            val text = texts[chapterIndex] ?: return null
            return planBuilder.buildPlan(text, chapterPosition, fromLastSpeakable)
        }

        override fun isChapterPaged(chapterIndex: Int): Boolean = chapterIndex in paged
    }

    private class FakeNavigation : ReadAloudChapterNavigationPort {
        val moved = mutableListOf<Int>()
        override fun moveToChapter(chapterIndex: Int) {
            moved += chapterIndex
        }
    }

    private class FakePlayback : ReadAloudPlaybackPort {
        val played = mutableListOf<String>()
        var stopped = 0
        var lastToken = 0L

        override fun onPlay(text: String, paragraphIndex: Int, playbackToken: Long) {
            played += text
            lastToken = playbackToken
        }

        override fun onStop() {
            stopped++
        }

        override fun onPause() = Unit

        override fun onResume() = true
    }

    private class Harness(
        chapterCount: Int,
        texts: Map<Int, String?>,
        paged: Set<Int>,
    ) {
        val source = FakeChapterSource(chapterCount, texts.toMutableMap(), paged.toMutableSet())
        val navigation = FakeNavigation()
        val playback = FakePlayback()
        val controller = ReadAloudControllerShared(
            chapterData = source,
            chapterNavigation = navigation,
            playbackPort = playback,
        )

        /** 读完整章 (段读完即停, 不跟进下一章), 用于观察章末那一刻的会话状态。 */
        fun finishCurrentChapter(limit: Int = 64) {
            val startChapter = controller.chapterIndex.value
            repeat(limit) {
                if (controller.state.value != ReadAloudControllerShared.ReadAloudState.PLAYING) return
                controller.paragraphCompleted(playback.lastToken)
                if (controller.chapterIndex.value != startChapter) return
            }
        }

        /** 一直念到会话不再播放 (用于验证能连续跨章)。 */
        fun readUntilStopped(limit: Int = 64) {
            repeat(limit) {
                if (controller.state.value != ReadAloudControllerShared.ReadAloudState.PLAYING) return
                controller.paragraphCompleted(playback.lastToken)
            }
        }
    }

    private fun paragraphText(vararg paragraphs: String) = paragraphs.joinToString("\n")

    @Test
    fun `章末下一章已排版时直接续读, 不进等待也不重开会话`() {
        val harness = Harness(
            chapterCount = 2,
            texts = mapOf(0 to paragraphText("甲一", "甲二"), 1 to paragraphText("乙一", "乙二")),
            paged = setOf(0, 1),
        )

        harness.controller.start(0)
        assertEquals(ReadAloudControllerShared.ReadAloudState.PLAYING, harness.controller.state.value)

        harness.finishCurrentChapter()

        assertTrue(
            "章末应继续念下一章, 实际播放内容: ${harness.playback.played}",
            harness.playback.played.any { it == "乙一" },
        )
        assertEquals(
            "无缝续读不该把会话停在等待态",
            ReadAloudControllerShared.ReadAloudState.PLAYING,
            harness.controller.state.value,
        )
        assertEquals("会话应已推进到下一章", 1, harness.controller.chapterIndex.value)
        assertTrue("应把阅读页切到下一章", 1 in harness.navigation.moved)
    }

    @Test
    fun `章末下一章未排版时进入等待, 不拿未排版正文开念`() {
        val harness = Harness(
            chapterCount = 2,
            texts = mapOf(0 to paragraphText("甲一", "甲二"), 1 to null),
            paged = setOf(0),
        )

        harness.controller.start(0)
        harness.finishCurrentChapter()

        assertEquals(
            "下一章没就绪时应停在等待态",
            ReadAloudControllerShared.ReadAloudState.WAITING,
            harness.controller.state.value,
        )
        assertTrue(
            "未排版时不得开始念下一章, 实际播放内容: ${harness.playback.played}",
            harness.playback.played.none { it.startsWith("乙") },
        )
    }

    @Test
    fun `整章没有可念内容时顺延到下一章而不是停在空章`() {
        val harness = Harness(
            chapterCount = 3,
            texts = mapOf(0 to paragraphText("甲一"), 1 to "\n\n", 2 to paragraphText("丙一")),
            paged = setOf(0, 1, 2),
        )

        harness.controller.start(0)
        harness.readUntilStopped()

        assertTrue(
            "空章应被跳过继续念后面的章, 实际播放内容: ${harness.playback.played}",
            harness.playback.played.any { it == "丙一" },
        )
        assertEquals(2, harness.controller.chapterIndex.value)
    }

    @Test
    fun `章节索引越界时报错而不是当作未就绪等待`() {
        val harness = Harness(
            chapterCount = 1,
            texts = mapOf(0 to paragraphText("甲一")),
            paged = setOf(0),
        )

        harness.controller.start(7)

        assertEquals(
            ReadAloudControllerShared.ReadAloudState.ERROR,
            harness.controller.state.value,
        )
    }

    @Test
    fun `未排版章节不从缓存原文开念`() {
        val harness = Harness(
            chapterCount = 2,
            texts = mapOf(0 to paragraphText("甲一"), 1 to paragraphText("乙一")),
            paged = setOf(0),
        )

        harness.controller.start(1)

        assertEquals(
            "正文未排版即视为未就绪, 不得用其它来源的文本开念",
            ReadAloudControllerShared.ReadAloudState.WAITING,
            harness.controller.state.value,
        )
        assertTrue(harness.playback.played.isEmpty())
    }
}
