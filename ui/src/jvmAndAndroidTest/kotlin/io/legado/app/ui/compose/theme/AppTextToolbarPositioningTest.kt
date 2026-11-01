package io.legado.app.ui.compose.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本菜单落位金样：生产纯函数 TextToolbarPlacement.decide / textToolbarPosition 直接驱动。
 *
 * 坐标约定（与生产一致）：
 * - pos 的 anchor 与 protectedRects 均为局部坐标（与弹层父节点同空间，即
 *   AppTextMenuContent.anchor 的真实输入）；anchorOrigin 为该父节点在窗口中的偏移，
 *   helper 原样传给生产函数（折窗口/归一化全部由生产函数完成，helper 不做折算）。
 * - availLeft/Top/Right/Bottom 为窗口四边安全区（WindowInsets.safeDrawing，桌面另加
 *   控制条上沿）；生产把可用区归一化后决策，helper 不替生产做任何折算。
 * - popupContentSize 按真实测量口径（视觉尺寸 + 2*阴影）传入。
 *
 * 判据：可用区内的视觉卡片与保护矩形 Rect 二维不相交（能放下时）；物理空隙不足时
 * 面板按剩余空间收高、如实重叠但本体仍钳在可用区内。锚/保护分数像素一律向远离方向折整。
 */
class AppTextToolbarPositioningTest {

    // px 常量（密度无关）：横排高 50、面板宽 120、完整面板高 242、gap 18、安全区内边距 12、阴影 8
    private val gap = 18
    private val margin = 12
    private val shadow = 8
    private val rowWidth = 192
    private val rowHeight = 50
    private val panelWidth = 120
    private val panelFull = 242

    private fun decide(
        anchor: Rect,
        windowHeight: Int,
        windowWidth: Int = 400,
        topBound: Int = margin,
        hasOverflow: Boolean = true,
        protectedRects: List<Rect> = emptyList(),
        rowWidthPx: Int = rowWidth,
        panelWidthPx: Int = panelWidth,
    ) = TextToolbarPlacement.decide(
        anchorOnWindow = anchor,
        windowSize = IntSize(windowWidth, windowHeight),
        gapPx = gap,
        marginPx = margin,
        topBoundPx = topBound,
        rowWidthPx = rowWidthPx,
        rowHeightPx = rowHeight,
        panelWidthPx = panelWidthPx,
        panelFullHeightPx = panelFull,
        hasOverflow = hasOverflow,
        protectedRects = protectedRects,
    )

    /** popupContentSize 为弹层内容实测尺寸（视觉 + 2*shadowPx），与真实测量口径一致 */
    private fun pos(
        anchor: Rect,
        visualWidth: Int,
        visualHeight: Int,
        windowHeight: Int,
        windowWidth: Int = 400,
        availLeft: Int = 0,
        availTop: Int = 0,
        availRight: Int = 0,
        availBottom: Int = 0,
        hasOverflow: Boolean = true,
        protectedRects: List<Rect> = emptyList(),
        anchorOrigin: IntOffset = IntOffset.Zero,
        shadowPx: Int = shadow,
        rowWidthPx: Int = rowWidth,
        panelWidthPx: Int = panelWidth,
    ): IntOffset = textToolbarPosition(
        anchor = anchor,
        anchorBounds = IntRect(
            anchorOrigin.x,
            anchorOrigin.y,
            anchorOrigin.x + windowWidth,
            anchorOrigin.y + windowHeight,
        ),
        availRect = IntRect(
            availLeft,
            availTop,
            windowWidth - availRight,
            windowHeight - availBottom,
        ),
        popupContentSize = IntSize(visualWidth + shadowPx * 2, visualHeight + shadowPx * 2),
        // 锚与保护矩形均为局部坐标，由生产定位函数按 anchorBounds 一并折窗口
        protectedRects = protectedRects,
        decide = { anchorInAvail, availSize, protectedInAvail ->
            TextToolbarPlacement.decide(
                anchorOnWindow = anchorInAvail,
                windowSize = availSize,
                gapPx = gap,
                marginPx = margin,
                topBoundPx = margin,
                rowWidthPx = rowWidthPx,
                rowHeightPx = rowHeight,
                panelWidthPx = panelWidthPx,
                panelFullHeightPx = panelFull,
                hasOverflow = hasOverflow,
                protectedRects = protectedInAvail,
            )
        },
        shadowPx = shadowPx,
        gapPx = gap,
        marginPx = margin,
        rowWidthPx = rowWidthPx,
        rowHeightPx = rowHeight,
    )

    /** 返回位置对应的视觉卡片矩形（弹层内容实测扣两圈阴影留白，窗口坐标） */
    private fun visual(p: IntOffset, visualWidth: Int, visualHeight: Int, shadowPx: Int = shadow) =
        Rect(
            p.x.toFloat() + shadowPx,
            p.y.toFloat() + shadowPx,
            (p.x + shadowPx + visualWidth).toFloat(),
            (p.y + shadowPx + visualHeight).toFloat(),
        )

    private fun intersects(a: Rect, b: Rect) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    /** 反例（密度 1，窗口 640 高）：锚 top110/bottom422，跨栏两手柄 y 各异，上方只够横排 */
    @Test
    fun `上方只够横排时对侧展开面板收高避让两手柄，二维不相交`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        // 起点手柄（左栏末行底 220..244）与终点手柄（398..422）
        val handles = listOf(Rect(140f, 220f, 164f, 244f), Rect(204f, 398f, 228f, 422f))
        val p = decide(anchor, windowHeight = 640, protectedRects = handles)
        assertTrue(p.opensUpwards)
        // 分支 1 被保护截断（手柄侵入上方生长带），分支 2 可用且收高到起点手柄顶之外
        assertFalse(p.expandsUpwards)
        assertEquals(220 - gap - (110 - rowHeight - gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val expanded = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            protectedRects = handles,
        )
        val expandedRect = visual(expanded, panelWidth, panelVisual)
        assertEquals(anchor.top - rowHeight - gap, expandedRect.top, 0f)
        assertFalse(intersects(expandedRect, handles[0]))
        assertFalse(intersects(expandedRect, handles[1]))
        // 收起态横排（视觉 50 高）同样不与两手柄相交
        val collapsed = pos(
            anchor,
            visualWidth = rowWidth,
            visualHeight = rowHeight,
            windowHeight = 640,
            protectedRects = handles,
        )
        assertFalse(intersects(visual(collapsed, rowWidth, rowHeight), handles[0]))
        assertFalse(intersects(visual(collapsed, rowWidth, rowHeight), handles[1]))
    }

    /** 面板宽于横排且靠左：保护检测与落点用同一横向钳位，面板右扩段不再漏检右手柄 */
    @Test
    fun `面板宽于横排靠左钳位时保护检测与实际面板范围一致`() {
        // windowW600、横排 276、面板 360：横排 x24..300，未钳面板预测 x-60..300，
        // 实际钳位 x12..372；手柄 x300..324 只有按实际范围才判得相交
        val anchor = Rect(0f, 110f, 324f, 422f)
        val handle = Rect(300f, 220f, 324f, 244f)
        val p = decide(
            anchor,
            windowHeight = 640,
            windowWidth = 600,
            protectedRects = listOf(handle),
            rowWidthPx = 276,
            panelWidthPx = 360,
        )
        assertTrue(p.opensUpwards)
        assertFalse(p.expandsUpwards)
        assertEquals(220 - gap - (110 - rowHeight - gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val expanded = pos(
            anchor,
            visualWidth = 360,
            visualHeight = panelVisual,
            windowHeight = 640,
            windowWidth = 600,
            protectedRects = listOf(handle),
            rowWidthPx = 276,
            panelWidthPx = 360,
        )
        val expandedRect = visual(expanded, 360, panelVisual)
        // 落点 x 与保护检测同一钳位：面板左缘钳到可用区边距 12
        assertEquals(margin, expandedRect.left.toInt())
        assertEquals(anchor.top - rowHeight - gap, expandedRect.top, 0f)
        assertFalse(intersects(expandedRect, handle))
    }

    @Test
    fun `向上展开时收起展开及开合中间尺寸窗口底锚定选区顶`() {
        val anchor = Rect(150f, 560f, 208f, 610f)
        // 手柄顶在面板向上生长带之外（586 > 锚顶-gap），分支 1 不被截断
        val handles = listOf(Rect(204f, 586f, 228f, 610f))
        val p = decide(anchor, windowHeight = 640, protectedRects = handles)
        assertTrue(p.opensUpwards)
        assertTrue(p.expandsUpwards)
        assertEquals(anchor.top - gap - margin, p.panelAvailHeightPx.toFloat(), 0f)
        // 收起（50）、开合中间（130）、完整面板（242）：y 随内容高变，窗口底恒 = 锚顶 - gap
        for (h in intArrayOf(50, 130, 242)) {
            val at = pos(anchor, visualWidth = rowWidth, visualHeight = h, windowHeight = 640, protectedRects = handles)
            val rect = visual(at, rowWidth, h)
            assertEquals(anchor.top - gap, rect.bottom, 0f)
            assertFalse(intersects(rect, handles[0]))
        }
    }

    @Test
    fun `选区贴近窗口顶时横排翻下方，视觉卡片顶在两游标之下`() {
        val anchor = Rect(150f, 30f, 208f, 80f)
        val handles = listOf(Rect(140f, 56f, 164f, 80f))
        val p = decide(anchor, windowHeight = 640, protectedRects = handles)
        assertFalse(p.opensUpwards)
        // 分支 1 上方无空间；分支 3 下方完整放得下 → 下放横排 + 面板向下
        assertFalse(p.expandsUpwards)
        assertEquals(640 - margin - (anchor.bottom + gap), p.panelAvailHeightPx.toFloat(), 0f)
        val at = pos(anchor, visualWidth = rowWidth, visualHeight = rowHeight, windowHeight = 640, protectedRects = handles)
        val rect = visual(at, rowWidth, rowHeight)
        assertEquals(anchor.bottom + gap, rect.top, 0f)
        assertFalse(intersects(rect, handles[0]))
    }

    @Test
    fun `下方只够横排时对侧向上展开，有保护且锚定分支都放不下时空隙选址`() {
        val anchor = Rect(150f, 30f, 208f, 560f)
        // 无保护：分支 4（下放横排 + 面板向上，底 = 横排底）
        val p = decide(anchor, windowHeight = 640)
        assertFalse(p.opensUpwards)
        assertTrue(p.expandsUpwards)
        assertNull(p.panelTopOverridePx)
        val expanded = pos(anchor, visualWidth = panelWidth, visualHeight = panelFull, windowHeight = 640)
        val rect = visual(expanded, panelWidth, panelFull)
        assertEquals(anchor.bottom + gap + rowHeight, rect.bottom, 0f)

        // 有保护（手柄侵入向上生长带）且窗口矮（锚定分支都放不下）→ 空隙选址：
        // 取可用区内最高可容面板的间隔（此样本为窗口顶段），面板按剩余空间收高
        val handles = listOf(Rect(140f, 336f, 164f, 360f))
        val pinned = decide(anchor, windowHeight = 400, protectedRects = handles)
        assertEquals(12, pinned.panelTopOverridePx)
        val panelVisual = minOf(panelFull, pinned.panelAvailHeightPx)
        val pinnedAt = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 400,
            protectedRects = handles,
        )
        val pinnedRect = visual(pinnedAt, panelWidth, panelVisual)
        assertEquals(margin, pinnedRect.top.toInt())
        assertFalse(intersects(pinnedRect, handles[0]))
    }

    /** 最上区域被手柄堵住、其下仍有完整空隙：面板放保护矩形之后，不压扁也不遮手柄 */
    @Test
    fun `最上空隙不足时选址到保护矩形之后的完整空隙`() {
        // 双栏大范围选区（锚纵跨 40..600），手柄在窗口顶附近且 x 与面板重叠
        val anchor = Rect(0f, 40f, 324f, 600f)
        val handle = Rect(150f, 40f, 174f, 64f)
        val p = decide(anchor, windowHeight = 640, protectedRects = listOf(handle))
        assertFalse(p.opensUpwards)
        assertEquals(64 + gap, p.panelTopOverridePx)
        assertEquals(640 - margin - (64 + gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            protectedRects = listOf(handle),
        )
        val rect = visual(at, panelWidth, panelVisual)
        assertEquals(64 + gap, rect.top.toInt())
        assertFalse(intersects(rect, handle))
    }

    /** 两个保护之间的空隙可放：面板落在两手柄之间之后的最大空隙 */
    @Test
    fun `多个保护矩形之间按最大空隙选址且不遮任一手柄`() {
        val anchor = Rect(0f, 40f, 324f, 600f)
        val handles = listOf(Rect(150f, 40f, 174f, 64f), Rect(150f, 300f, 174f, 324f))
        val p = decide(anchor, windowHeight = 640, protectedRects = handles)
        // 空隙：顶段 12..22(10)、两保护间 82..282(200)、末保护后 342..628(286) → 取末段
        assertEquals(324 + gap, p.panelTopOverridePx)
        assertEquals(640 - margin - (324 + gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            protectedRects = handles,
        )
        val rect = visual(at, panelWidth, panelVisual)
        assertFalse(intersects(rect, handles[0]))
        assertFalse(intersects(rect, handles[1]))
    }

    /** 所有实际空隙都不足面板最小可见高度：面板与保护矩形重叠（物理限制如实断言） */
    @Test
    fun `所有空隙都不足时面板按剩余空间收高并如实与保护重叠`() {
        // 窗口 100 高，双栏选区两行端点的手柄几乎占满可用区：空隙高 < 面板最小可见高度
        val anchor = Rect(0f, 26f, 324f, 88f)
        val handles = listOf(Rect(150f, 26f, 174f, 50f), Rect(150f, 64f, 174f, 88f))
        val p = decide(anchor, windowHeight = 100, protectedRects = handles)
        // 保护缓冲 (±gap) 覆盖整个可用区 → 无空隙，面板退回可用区顶
        assertEquals(12, p.panelTopOverridePx)
        assertEquals(0, p.panelAvailHeightPx)
        // 渲染面板最小可见高度（返回行 50）必然与手柄相交：如实重叠
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = rowHeight,
            windowHeight = 100,
            protectedRects = handles,
        )
        val rect = visual(at, panelWidth, rowHeight)
        assertEquals(12, rect.top.toInt())
        assertTrue(intersects(rect, handles[0]))
    }

    /** 非零安全区（顶部/底部/左右）: 方向与落点都用同一可用区真值, 卡片守边界且不遮手柄 */
    @Test
    fun `非零安全区下方向与落点同源，卡片守可用区边界且不遮手柄`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        val handle = Rect(140f, 220f, 164f, 244f)
        // 可用区 (24,48)-(384,608)：归一化锚顶 62 上方放不下 → 翻下方；
        // 分支 3 可用 = 608-12-(422+gap) = 156
        val panelVisual = 156
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            availLeft = 24,
            availTop = 48,
            availRight = 16,
            availBottom = 32,
            protectedRects = listOf(handle),
        )
        val rect = visual(at, panelWidth, panelVisual)
        assertTrue(rect.left >= 24 + margin)
        assertTrue(rect.right <= 384 - margin)
        assertTrue(rect.top >= 48 + margin)
        assertTrue(rect.bottom <= 608 - margin)
        assertFalse(intersects(rect, handle))
    }

    /** 底部大安全区（键盘弹出）: 空隙不足时按真实可用区收面板, 本体仍钳在可用区内 */
    @Test
    fun `底部安全区压缩可用区时空隙选址按剩余空间收面板`() {
        val anchor = Rect(150f, 110f, 208f, 200f)
        val handle = Rect(190f, 150f, 214f, 174f)
        // 可用区 (0,0)-(400,340)：锚定分支都被保护截断 → 空隙选址 192..328 段
        val p = decide(
            anchor,
            windowHeight = 340,
            protectedRects = listOf(handle),
        )
        assertEquals(174 + gap, p.panelTopOverridePx)
        assertEquals(340 - margin - (174 + gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            availBottom = 300,
            protectedRects = listOf(handle),
        )
        val rect = visual(at, panelWidth, panelVisual)
        assertTrue(rect.bottom <= 340 - margin)
        assertFalse(intersects(rect, handle))
    }

    /** 分数像素锚/保护: 边界向远离方向折整, 18px 间距不被舍入吞掉 */
    @Test
    fun `分数像素锚与保护边界折整后仍不侵入保护`() {
        val anchor = Rect(150.4f, 110.6f, 208.2f, 422.2f)
        val handle = Rect(140.3f, 220.7f, 164.9f, 244.2f)
        val p = decide(anchor, windowHeight = 640, protectedRects = listOf(handle))
        // floor(110.6)=110 → 分支 2 面板底 = 220(floor 保护顶) - gap
        assertEquals(220 - gap - (110 - rowHeight - gap), p.panelAvailHeightPx)
        val panelVisual = minOf(panelFull, p.panelAvailHeightPx)
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = panelVisual,
            windowHeight = 640,
            protectedRects = listOf(handle),
        )
        val rect = visual(at, panelWidth, panelVisual)
        // 视觉顶 = floor(110.6) - rowH - gap = 42（若按 round 会得 43）
        assertEquals(42, rect.top.toInt())
        assertEquals(42 + panelVisual, rect.bottom.toInt())
        assertFalse(intersects(rect, handle))
    }

    /** 锚底分数向上折整: 下方展开的面板顶不侵入锚（422.2 → 441 而非 440） */
    @Test
    fun `锚底分数像素向上折整时下方展开不侵入锚区`() {
        val anchor = Rect(150f, 30f, 208f, 422.2f)
        val handle = Rect(140f, 415f, 164f, 422f)
        val at = pos(
            anchor,
            visualWidth = panelWidth,
            visualHeight = minOf(panelFull, 187),
            windowHeight = 640,
            protectedRects = listOf(handle),
        )
        val rect = visual(at, panelWidth, minOf(panelFull, 187))
        assertEquals(423 + gap, rect.top.toInt())
        assertFalse(intersects(rect, handle))
    }

    @Test
    fun `无溢出时横排同规则，图片方区与文本锚同口径`() {
        val anchor = Rect(180f, 500f, 220f, 540f)
        val p = decide(anchor, windowHeight = 640, hasOverflow = false)
        assertTrue(p.opensUpwards)
        assertEquals(p.opensUpwards, p.expandsUpwards)
        assertEquals(0, p.panelAvailHeightPx)
        val at = pos(anchor, visualWidth = rowWidth, visualHeight = rowHeight, windowHeight = 640, hasOverflow = false)
        assertEquals(500 - rowHeight - gap, at.y + shadow)
    }

    @Test
    fun `可用区上沿抬高翻转判定，决策与落点同源`() {
        val anchor = Rect(150f, 120f, 208f, 170f)
        val above = decide(anchor, windowHeight = 640, topBound = 40)
        assertTrue(above.opensUpwards)
        assertEquals(
            120 - rowHeight - gap,
            pos(
                anchor,
                visualWidth = rowWidth,
                visualHeight = rowHeight,
                windowHeight = 640,
                availTop = 40,
            ).y + shadow,
        )
        val below = decide(anchor, windowHeight = 640, topBound = 60)
        assertFalse(below.opensUpwards)
        assertEquals(
            170 + gap,
            pos(
                anchor,
                visualWidth = rowWidth,
                visualHeight = rowHeight,
                windowHeight = 640,
                availTop = 60,
            ).y + shadow,
        )
    }

    /** 局部锚 + 非零父偏移 与 全窗锚 + 零偏移：同决策、同落点、同视觉矩形 */
    @Test
    fun `等价平移：局部锚加父偏移与全窗锚决策与落点一致`() {
        val localAnchor = Rect(100f, 110f, 158f, 422f)
        val origin = IntOffset(50, 30)
        val windowAnchor = Rect(
            localAnchor.left + origin.x,
            localAnchor.top + origin.y,
            localAnchor.right + origin.x,
            localAnchor.bottom + origin.y,
        )
        val localHandles = listOf(Rect(90f, 190f, 114f, 214f))
        val windowHandles = listOf(Rect(140f, 220f, 164f, 244f))
        // 局部锚+偏移 与 全窗锚+零偏移 走同一生产函数：决策与落点逐位相同
        val viaLocal = pos(
            localAnchor,
            visualWidth = rowWidth,
            visualHeight = rowHeight,
            windowHeight = 640,
            protectedRects = localHandles,
            anchorOrigin = origin,
        )
        val viaWindow = pos(
            windowAnchor,
            visualWidth = rowWidth,
            visualHeight = rowHeight,
            windowHeight = 640,
            protectedRects = windowHandles,
        )
        assertEquals(viaWindow, viaLocal)
        assertEquals(
            visual(viaWindow, rowWidth, rowHeight),
            visual(viaLocal, rowWidth, rowHeight),
        )
        assertFalse(intersects(visual(viaLocal, rowWidth, rowHeight), windowHandles[0]))
    }

    /** 阴影留白口径：popupContentSize 含两圈留白，视觉卡片位置不随阴影大小漂移 */
    @Test
    fun `阴影口径：内容实测含两圈留白，视觉位置与阴影大小无关`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        for (shadowPx in intArrayOf(8, 16)) {
            val at = pos(
                anchor,
                visualWidth = rowWidth,
                visualHeight = rowHeight,
                windowHeight = 640,
                shadowPx = shadowPx,
            )
            val rect = visual(at, rowWidth, rowHeight, shadowPx)
            assertEquals(anchor.top - rowHeight - gap, rect.top, 0f)
            assertEquals(anchor.top - gap, rect.bottom, 0f)
        }
    }

    @Test
    fun `横向锚中心居中、面板右缘对齐横排右缘、可用区边距钳位`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        // 锚中心 x = 179；rowX = 179-96 = 83（≥12 无钳）
        assertEquals(83, pos(anchor, visualWidth = rowWidth, visualHeight = rowHeight, windowHeight = 640).x + shadow)
        // 面板（视觉宽 120）：右缘对齐横排右缘（83+192）
        assertEquals(
            83 + rowWidth - panelWidth,
            pos(anchor, visualWidth = panelWidth, visualHeight = panelFull, windowHeight = 640).x + shadow,
        )
        // 窄可用区：横排钳到边距
        val narrow = pos(anchor, visualWidth = rowWidth, visualHeight = rowHeight, windowHeight = 640, windowWidth = 200)
        assertEquals(margin, narrow.x + shadow)
    }
}
