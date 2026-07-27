package io.legado.app.ui.book.read.page.provider

import org.junit.Assert.assertEquals
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
}
