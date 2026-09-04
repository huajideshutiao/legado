package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * measureTextSplit 字素簇聚合金样：mock 逐字宽度数组 → 聚簇结果断言。
 * 纯 JVM 可跑（无 android 依赖），作为下沉 shared 的回归红线，也是跨端对拍基准。
 */
class TextLayoutMathTest {

    /** ASCII：每字宽>0，各自成簇。 */
    @Test
    fun asciiEachCharOwnCluster() {
        val text = "abc"
        val widths = floatArrayOf(10f, 10f, 10f)
        val r = measureTextSplit(text, widths)
        assertEquals(listOf("a", "b", "c"), r.words)
        assertEquals(listOf(10f, 10f, 10f), r.widths)
    }

    /** 中文：每字宽>0，各自成簇。 */
    @Test
    fun cjkEachCharOwnCluster() {
        val text = "我是三"
        val widths = floatArrayOf(20f, 20f, 20f)
        val r = measureTextSplit(text, widths)
        assertEquals(listOf("我", "是", "三"), r.words)
        assertEquals(listOf(20f, 20f, 20f), r.widths)
    }

    /** 表情字素簇：单码点代理对（😀），簇首宽 20、续字宽 0，聚成一簇。 */
    @Test
    fun emojiSurrogatePairIsOneCluster() {
        val text = "😀"
        val widths = floatArrayOf(20f, 0f)
        val r = measureTextSplit(text, widths)
        assertEquals(listOf("😀"), r.words)
        assertEquals(listOf(20f), r.widths)
    }

    /** 可视区宽度：单页模式 = 视宽 - 左右内边距。 */
    @Test
    fun calcVisibleWidthSinglePage() {
        assertEquals(1080 - 24 - 24, calcVisibleWidth(1080, 24, 24, false))
    }

    /** 可视区宽度：双页模式 = 视宽/2 - 左右内边距（中缝占两倍内边距）。 */
    @Test
    fun calcVisibleWidthDoublePage() {
        assertEquals(1080 / 2 - 24 - 24, calcVisibleWidth(1080, 24, 24, true))
    }

    /** 可视区高度 = 视高 - 上下内边距。 */
    @Test
    fun calcVisibleHeightBasic() {
        assertEquals(1920 - 32 - 32, calcVisibleHeight(1920, 32, 32))
    }

    /** 可视区右边界 = 视宽 - 右内边距。 */
    @Test
    fun calcVisibleRightBasic() {
        assertEquals(1080 - 24, calcVisibleRight(1080, 24))
    }

    /** 可视区下边界 = 上内边距 + 可视区高度。 */
    @Test
    fun calcVisibleBottomBasic() {
        assertEquals(32 + 1856, calcVisibleBottom(32, 1856))
    }

    /**
     * ZWJ(U+200D) 是连接符不是簇边界：U+1F468 ZWJ U+1F469 必须聚成一簇，
     * 且整串就是一簇时直接返回原字符串引用（不做 substring 的零拷贝路径）。
     */
    @Test
    fun `ZWJ 复合表情聚成一簇且整串直返不做 substring`() {
        val text = "👨‍👩"
        assertEquals(5, text.length)
        val widths = floatArrayOf(20f, 0f, 0f, 0f, 0f)
        val r = measureTextSplit(text, widths)

        assertEquals(1, r.words.size)
        assertSame(text, r.words.single())
        assertEquals(listOf(20f), r.widths)
    }

    /** 组合字（e + U+0301 组合锐音符）同样一簇，且走 clusterBaseIndex==0 && i==length 的整串直返。 */
    @Test
    fun `组合字一簇时整串直返`() {
        val text = "e" + Char(0x0301)
        assertEquals(2, text.length)
        val r = measureTextSplit(text, floatArrayOf(10f, 0f))

        assertSame(text, r.words.single())
        assertEquals(listOf(10f), r.widths)
    }

    /** ZWSP/ZWNJ/WJ/BOM 是簇边界：各自独占一簇（宽度 0），不并入相邻字。 */
    @Test
    fun `零宽分隔字符各自成簇边界`() {
        val boundaries = listOf(
            "ZWSP" to '​',
            "ZWNJ" to '‌',
            "WJ" to '⁠',
            "BOM" to '﻿',
        )
        for ((name, ch) in boundaries) {
            // 乙 是合法标识符字符, 不加花括号会被当成模板变量名 ch乙
            val text = "甲${ch}乙"
            val r = measureTextSplit(text, floatArrayOf(10f, 0f, 10f))
            assertEquals(name, listOf("甲", ch.toString(), "乙"), r.words)
            assertEquals(name, listOf(10f, 0f, 10f), r.widths)
        }
    }

    /** BOM 紧跟代理对时切断簇：表情仍是一簇（走 substring 分支），BOM 另起一簇。 */
    @Test
    fun `BOM 不被并入前一个表情簇`() {
        val text = "😀﻿"
        val r = measureTextSplit(text, floatArrayOf(20f, 0f, 0f))

        assertEquals(listOf("😀", "﻿"), r.words)
        assertEquals(listOf(20f, 0f), r.widths)
    }

    /** start 偏移：宽度数组是整段共用的，measureTextSplit 只读 [start, start+length) 区间。 */
    @Test
    fun `按 start 偏移读取宽度数组`() {
        val widths = floatArrayOf(99f, 99f, 20f, 0f, 30f)
        val r = measureTextSplit("😀我", widths, start = 2)

        assertEquals(listOf("😀", "我"), r.words)
        assertEquals(listOf(20f, 30f), r.widths)
    }

    /** 图片占位符双保险：默认占位符与调用方注入的占位符都算命中，空注入值不参与判定。 */
    @Test
    fun `图片占位符判定认默认值也认注入值`() {
        val default = ChapterContentParserShared.srcReplaceChar

        assertTrue(isImagePlaceholder(default))
        assertTrue(isImagePlaceholder(default, srcReplaceChar = "◆"))
        assertTrue(isImagePlaceholder("◆", srcReplaceChar = "◆"))
        assertFalse(isImagePlaceholder("甲", srcReplaceChar = "◆"))
        assertFalse(isImagePlaceholder("", srcReplaceChar = ""))
    }

    /** 图片等比缩放：先按最大宽压，再按最大高压，两轴都不超框。 */
    @Test
    fun `图片按最大宽高等比缩放`() {
        assertEquals(100f to 50f, getFitSize(200f, 100f, 100f, 1000f))
        assertEquals(50f to 100f, getFitSize(100f, 200f, 1000f, 100f))
        assertEquals(80f to 40f, getFitSize(80f, 40f, 100f, 100f))
    }
}
