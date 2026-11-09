package io.legado.app.ui.compose.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.legado.app.ui.compose.component.fastScrollThumbFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本菜单落位金样：生产纯函数 TextToolbarPlacement.decide / textToolbarFrameOffset /
 * textMenuRectInWindow / textMenuAvailRect / menuPanelWidthPx / fastScrollThumbFraction 直接驱动。
 *
 * 坐标约定（与生产一致）：
 * - anchor 与 protectedRects 按 [AppTextMenuAnchorSpace] 声明空间，helper 原样传给生产
 *   转换函数 `textMenuRectInWindow`（折窗口坐标/归一化全部由生产函数完成，helper 只做
 *   可用区归一）。
 * - rootOriginInWindow = 本窗口 Compose 根在窗口中的偏移（Popup/对话框层不为 0），
 *   containerOriginInWindow = 阅读页正文容器在窗口中的位置。
 * - availLeft/Top/Right/Bottom 为窗口四边安全区，helper 转成生产的可用区矩形。
 * - 外框尺寸 = 横排/面板视觉尺寸逐轴最大值（含溢出时），定位函数按这组尺寸一次算定；
 *   视觉卡片在框内贴布局方向的 End 边对齐，尺寸按传入的动画中间帧给出。
 *
 * 判据：同一组尺寸定出的锚定边（LTR 右缘、RTL 左缘）与展开侧边缘在所有中间宽高上都不动；
 * 可用区内的卡片与保护矩形二维不相交（能放下时）；物理空隙不足时面板按剩余空间收高、
 * 如实重叠但本体仍钳在可用区内。
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

    /** 一次完整落位：决策 + 固定外框起点（窗口坐标，含两圈阴影留白）+ 外框视觉尺寸 */
    private class Frame(
        val placement: TextToolbarPlacement,
        val origin: IntOffset,
        val widthPx: Int,
        val heightPx: Int,
        val shadowPx: Int,
        val rtl: Boolean,
    )

    private fun frame(
        anchor: Rect,
        windowHeight: Int,
        windowWidth: Int = 400,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        availLeft: Int = 0,
        availTop: Int = 0,
        availRight: Int = 0,
        availBottom: Int = 0,
        topBound: Int = margin,
        hasOverflow: Boolean = true,
        protectedRects: List<Rect> = emptyList(),
        anchorSpace: AppTextMenuAnchorSpace = AppTextMenuAnchorSpace.Root,
        containerOriginInWindow: IntOffset = IntOffset.Zero,
        rootOriginInWindow: IntOffset = IntOffset.Zero,
        shadowPx: Int = shadow,
        rowWidthPx: Int = rowWidth,
        rowHeightPx: Int = rowHeight,
        panelWidthPx: Int = panelWidth,
        panelFullHeightPx: Int = panelFull,
    ): Frame {
        val availRect = IntRect(
            availLeft,
            availTop,
            windowWidth - availRight,
            windowHeight - availBottom,
        )
        val anchorInAvail = textMenuRectInWindow(
            anchor,
            anchorSpace,
            containerOriginInWindow,
            rootOriginInWindow,
        ).inAvail(availRect)
        val protectedInAvail = protectedRects.map {
            textMenuRectInWindow(
                it,
                anchorSpace,
                containerOriginInWindow,
                rootOriginInWindow,
            ).inAvail(availRect)
        }
        val availSize = IntSize(availRect.width, availRect.height)
        val placement = TextToolbarPlacement.decide(
            anchorOnWindow = anchorInAvail,
            windowSize = availSize,
            layoutDirection = layoutDirection,
            gapPx = gap,
            marginPx = margin,
            topBoundPx = topBound,
            rowWidthPx = rowWidthPx,
            rowHeightPx = rowHeightPx,
            panelWidthPx = panelWidthPx,
            panelFullHeightPx = panelFullHeightPx,
            hasOverflow = hasOverflow,
            protectedRects = protectedInAvail,
        )
        val width = if (hasOverflow) maxOf(rowWidthPx, panelWidthPx) else rowWidthPx
        val height = if (hasOverflow) maxOf(rowHeightPx, panelFullHeightPx) else rowHeightPx
        // 本帧渲染的卡片高 (返回行恒在, 可滚区按可用空间收), 与调用处同式
        val cardHeight = if (hasOverflow) {
            minOf(panelFullHeightPx, placement.panelAvailHeightPx.coerceAtLeast(rowHeightPx))
        } else {
            rowHeightPx
        }
        val at = textToolbarFrameOffset(
            anchorInAvail = anchorInAvail,
            availSize = availSize,
            placement = placement,
            layoutDirection = layoutDirection,
            frameWidthPx = width,
            frameHeightPx = height,
            cardHeightPx = cardHeight,
            rowHeightPx = rowHeightPx,
            gapPx = gap,
            marginPx = margin,
            shadowPx = shadowPx,
        )
        return Frame(
            placement = placement,
            origin = IntOffset(at.x + availRect.left, at.y + availRect.top),
            widthPx = width,
            heightPx = height,
            shadowPx = shadowPx,
            rtl = layoutDirection == LayoutDirection.Rtl,
        )
    }

    /**
     * 视觉卡片矩形：框内贴布局方向的 End 边（LTR 右、RTL 左），向上展开贴下角、
     * 向下展开贴上角，尺寸为动画中间帧。
     */
    private fun card(f: Frame, width: Int, height: Int): Rect {
        val vertical = if (f.placement.expandsUpwards) {
            val bottom = (f.origin.y + f.heightPx + f.shadowPx).toFloat()
            bottom - height to bottom
        } else {
            val top = (f.origin.y + f.shadowPx).toFloat()
            top to top + height
        }
        return if (f.rtl) {
            val left = (f.origin.x + f.shadowPx).toFloat()
            Rect(left, vertical.first, left + width, vertical.second)
        } else {
            val right = (f.origin.x + f.widthPx + f.shadowPx).toFloat()
            Rect(right - width, vertical.first, right, vertical.second)
        }
    }

    /** 本帧实际渲染的面板高（可用空间收高后，返回行恒在），与调用处同式 */
    private fun renderedPanelHeight(f: Frame): Int =
        minOf(panelFull, f.placement.panelAvailHeightPx.coerceAtLeast(rowHeight))

    private fun intersects(a: Rect, b: Rect) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun mirrored(rect: Rect, width: Int): Rect =
        Rect(width - rect.right, rect.top, width - rect.left, rect.bottom)

    /** 窗口坐标 → 可用区局部坐标（生产用同一式，helper 只在这里做一次） */
    private fun Rect.inAvail(availRect: IntRect): Rect = Rect(
        left - availRect.left,
        top - availRect.top,
        right - availRect.left,
        bottom - availRect.top,
    )

    /** 反例（密度 1，窗口 640 高）：锚 top110/bottom422，跨栏两手柄 y 各异，上方只够横排 */
    @Test
    fun `上方只够横排时对侧展开面板收高避让两手柄，二维不相交`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        // 起点手柄（左栏末行底 220..244）与终点手柄（398..422）
        val handles = listOf(Rect(140f, 220f, 164f, 244f), Rect(204f, 398f, 228f, 422f))
        val f = frame(anchor, windowHeight = 640, protectedRects = handles)
        assertTrue(f.placement.opensUpwards)
        // 分支 1 被保护截断（手柄侵入上方生长带），分支 2 可用且收高到起点手柄顶之外
        assertFalse(f.placement.expandsUpwards)
        assertEquals(220 - gap - (110 - rowHeight - gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val expanded = card(f, panelWidth, panelVisual)
        assertEquals(anchor.top - rowHeight - gap, expanded.top, 0f)
        assertFalse(intersects(expanded, handles[0]))
        assertFalse(intersects(expanded, handles[1]))
        // 收起态横排（视觉 50 高）同样不与两手柄相交
        val collapsed = card(f, rowWidth, rowHeight)
        assertFalse(intersects(collapsed, handles[0]))
        assertFalse(intersects(collapsed, handles[1]))
    }

    /** 面板宽于横排且靠左：共同锚定边把它整体挤到可用区内，保护检测与实际面板范围一致 */
    @Test
    fun `面板宽于横排靠左钳位时保护检测与实际面板范围一致`() {
        // windowW600、横排 276、面板 360：锚定边 = 12 + 360 = 372，面板 x12..372、
        // 横排 x96..372；手柄 x300..324 只有按实际范围才判得相交
        val anchor = Rect(0f, 110f, 324f, 422f)
        val handle = Rect(300f, 220f, 324f, 244f)
        val f = frame(
            anchor,
            windowHeight = 640,
            windowWidth = 600,
            protectedRects = listOf(handle),
            rowWidthPx = 276,
            panelWidthPx = 360,
        )
        assertTrue(f.placement.opensUpwards)
        assertFalse(f.placement.expandsUpwards)
        assertEquals(220 - gap - (110 - rowHeight - gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val expanded = card(f, 360, panelVisual)
        // 共同锚定边：横排与面板右缘同一像素，面板左缘钳到可用区边距 12
        assertEquals(margin, expanded.left.toInt())
        assertEquals(card(f, 276, rowHeight).right, expanded.right, 0f)
        assertEquals(anchor.top - rowHeight - gap, expanded.top, 0f)
        assertFalse(intersects(expanded, handle))
    }

    @Test
    fun `向上展开时收起展开及开合中间尺寸窗口底锚定选区顶`() {
        val anchor = Rect(150f, 560f, 208f, 610f)
        // 手柄顶在面板向上生长带之外（586 > 锚顶-gap），分支 1 不被截断
        val handles = listOf(Rect(204f, 586f, 228f, 610f))
        val f = frame(anchor, windowHeight = 640, protectedRects = handles)
        assertTrue(f.placement.opensUpwards)
        assertTrue(f.placement.expandsUpwards)
        assertEquals(anchor.top - gap - margin, f.placement.panelAvailHeightPx.toFloat(), 0f)
        // 收起（50）、开合中间（130）、完整面板（242）：底缘与右缘恒定，只有顶缘/左缘生长
        for (h in intArrayOf(50, 130, 242)) {
            val rect = card(f, rowWidth, h)
            assertEquals(anchor.top - gap, rect.bottom, 0f)
            assertEquals(card(f, rowWidth, rowHeight).right, rect.right, 0f)
            assertFalse(intersects(rect, handles[0]))
        }
    }

    /** 开合变形的每一帧：中间宽高（横排→面板）都不移动锚定边与展开侧边缘 */
    @Test
    fun `开合变形中间宽高共同锚边静止`() {
        for (up in booleanArrayOf(true, false)) {
            val anchor = if (up) Rect(150f, 560f, 208f, 610f) else Rect(150f, 30f, 208f, 80f)
            val f = frame(anchor, windowHeight = 640)
            assertEquals(up, f.placement.expandsUpwards)
            val anchoredEdge = if (up) {
                card(f, rowWidth, rowHeight).bottom
            } else {
                card(f, rowWidth, rowHeight).top
            }
            val rightEdge = card(f, rowWidth, rowHeight).right
            // 宽度从横排到面板、高度从横排到完整面板逐帧取值（含非整比例中间帧）
            for (w in intArrayOf(rowWidth, rowWidth - 1, 240, panelWidth + 1, panelWidth)) {
                for (h in intArrayOf(rowHeight, 97, 133, panelFull - 1, panelFull)) {
                    val rect = card(f, w, h)
                    assertEquals(rightEdge, rect.right, 0f)
                    if (up) {
                        assertEquals(anchoredEdge, rect.bottom, 0f)
                    } else {
                        assertEquals(anchoredEdge, rect.top, 0f)
                    }
                }
            }
        }
    }

    /** 面板比横排窄：锚定边仍与横排放开前一致（共同锚定边按较大者定） */
    @Test
    fun `窄面板展开时右缘与横排一致`() {
        val anchor = Rect(150f, 560f, 208f, 610f)
        val f = frame(anchor, windowHeight = 640, panelWidthPx = 100)
        assertTrue(f.placement.opensUpwards)
        assertEquals(f.placement.frameEdgePx, card(f, rowWidth, rowHeight).right.toInt())
        assertEquals(f.placement.frameEdgePx, card(f, 100, panelFull).right.toInt())
    }

    @Test
    fun `选区贴近窗口顶时横排翻下方，视觉卡片顶在两游标之下`() {
        val anchor = Rect(150f, 30f, 208f, 80f)
        val handles = listOf(Rect(140f, 56f, 164f, 80f))
        val f = frame(anchor, windowHeight = 640, protectedRects = handles)
        assertFalse(f.placement.opensUpwards)
        // 分支 1 上方无空间；分支 3 下方完整放得下 → 下放横排 + 面板向下
        assertFalse(f.placement.expandsUpwards)
        assertEquals(640 - margin - (anchor.bottom + gap), f.placement.panelAvailHeightPx.toFloat(), 0f)
        val rect = card(f, rowWidth, rowHeight)
        assertEquals(anchor.bottom + gap, rect.top, 0f)
        assertFalse(intersects(rect, handles[0]))
    }

    @Test
    fun `下方只够横排时对侧向上展开，有保护且锚定分支都放不下时空隙选址`() {
        val anchor = Rect(150f, 30f, 208f, 560f)
        // 无保护：分支 4（下放横排 + 面板向上，底 = 横排底）
        val f = frame(anchor, windowHeight = 640)
        assertFalse(f.placement.opensUpwards)
        assertTrue(f.placement.expandsUpwards)
        assertNull(f.placement.panelTopOverridePx)
        assertEquals(
            anchor.bottom + gap + rowHeight,
            card(f, panelWidth, panelFull).bottom,
            0f,
        )

        // 有保护（手柄侵入向上生长带）且窗口矮（锚定分支都放不下）→ 空隙选址：
        // 取可用区内最高可容面板的间隔（此样本为窗口顶段），面板按剩余空间收高
        val handles = listOf(Rect(140f, 336f, 164f, 360f))
        val pinned = frame(anchor, windowHeight = 400, protectedRects = handles)
        assertEquals(12, pinned.placement.panelTopOverridePx)
        val panelVisual = renderedPanelHeight(pinned)
        val pinnedRect = card(pinned, panelWidth, panelVisual)
        assertEquals(margin, pinnedRect.top.toInt())
        assertFalse(intersects(pinnedRect, handles[0]))
    }

    /** 最上区域被手柄堵住、其下仍有完整空隙：面板放保护矩形之后，不压扁也不遮手柄 */
    @Test
    fun `最上空隙不足时选址到保护矩形之后的完整空隙`() {
        // 双栏大范围选区（锚纵跨 40..600），手柄在窗口顶附近且 x 与面板重叠
        val anchor = Rect(0f, 40f, 324f, 600f)
        val handle = Rect(150f, 40f, 174f, 64f)
        val f = frame(anchor, windowHeight = 640, protectedRects = listOf(handle))
        assertFalse(f.placement.opensUpwards)
        assertEquals(64 + gap, f.placement.panelTopOverridePx)
        assertEquals(640 - margin - (64 + gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val rect = card(f, panelWidth, panelVisual)
        assertEquals(64 + gap, rect.top.toInt())
        assertFalse(intersects(rect, handle))
    }

    /** 两个保护之间的空隙可放：面板落在两手柄之间之后的最大空隙 */
    @Test
    fun `多个保护矩形之间按最大空隙选址且不遮任一手柄`() {
        val anchor = Rect(0f, 40f, 324f, 600f)
        val handles = listOf(Rect(150f, 40f, 174f, 64f), Rect(150f, 300f, 174f, 324f))
        val f = frame(anchor, windowHeight = 640, protectedRects = handles)
        // 空隙：顶段 12..22(10)、两保护间 82..282(200)、末保护后 342..628(286) → 取末段
        assertEquals(324 + gap, f.placement.panelTopOverridePx)
        assertEquals(640 - margin - (324 + gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val rect = card(f, panelWidth, panelVisual)
        assertFalse(intersects(rect, handles[0]))
        assertFalse(intersects(rect, handles[1]))
    }

    /** 所有实际空隙都不足面板最小可见高度：面板与保护矩形重叠（物理限制如实断言） */
    @Test
    fun `所有空隙都不足时面板按剩余空间收高并如实与保护重叠`() {
        // 窗口 100 高，双栏选区两行端点的手柄几乎占满可用区：空隙高 < 面板最小可见高度
        val anchor = Rect(0f, 26f, 324f, 88f)
        val handles = listOf(Rect(150f, 26f, 174f, 50f), Rect(150f, 64f, 174f, 88f))
        val f = frame(anchor, windowHeight = 100, protectedRects = handles)
        // 保护缓冲 (±gap) 覆盖整个可用区 → 无空隙，面板退回可用区顶
        assertEquals(12, f.placement.panelTopOverridePx)
        assertEquals(0, f.placement.panelAvailHeightPx)
        // 渲染面板最小可见高度（返回行 50）必然与手柄相交：如实重叠
        val rect = card(f, panelWidth, rowHeight)
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
        val f = frame(
            anchor,
            windowHeight = 640,
            availLeft = 24,
            availTop = 48,
            availRight = 16,
            availBottom = 32,
            protectedRects = listOf(handle),
        )
        val rect = card(f, panelWidth, panelVisual)
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
        val f = frame(anchor, windowHeight = 340, protectedRects = listOf(handle))
        assertEquals(174 + gap, f.placement.panelTopOverridePx)
        assertEquals(340 - margin - (174 + gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val rect = card(f, panelWidth, panelVisual)
        assertTrue(rect.bottom <= 340 - margin)
        assertFalse(intersects(rect, handle))
    }

    /** 分数像素锚/保护: 边界向远离方向折整, 18px 间距不被舍入吞掉 */
    @Test
    fun `分数像素锚与保护边界折整后仍不侵入保护`() {
        val anchor = Rect(150.4f, 110.6f, 208.2f, 422.2f)
        val handle = Rect(140.3f, 220.7f, 164.9f, 244.2f)
        val f = frame(anchor, windowHeight = 640, protectedRects = listOf(handle))
        // floor(110.6)=110 → 分支 2 面板底 = 220(floor 保护顶) - gap
        assertEquals(220 - gap - (110 - rowHeight - gap), f.placement.panelAvailHeightPx)
        val panelVisual = renderedPanelHeight(f)
        val rect = card(f, panelWidth, panelVisual)
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
        val f = frame(anchor, windowHeight = 640, protectedRects = listOf(handle))
        val rect = card(f, panelWidth, minOf(panelFull, 187))
        assertEquals(423 + gap, rect.top.toInt())
        assertFalse(intersects(rect, handle))
    }

    @Test
    fun `无溢出时横排同规则，图片方区与文本锚同口径`() {
        val anchor = Rect(180f, 500f, 220f, 540f)
        val f = frame(anchor, windowHeight = 640, hasOverflow = false)
        assertTrue(f.placement.opensUpwards)
        assertEquals(f.placement.opensUpwards, f.placement.expandsUpwards)
        assertEquals(0, f.placement.panelAvailHeightPx)
        assertEquals(500 - rowHeight - gap, card(f, rowWidth, rowHeight).top.toInt())
    }

    @Test
    fun `可用区上沿抬高翻转判定，决策与落点同源`() {
        val anchor = Rect(150f, 120f, 208f, 170f)
        val above = frame(anchor, windowHeight = 640, topBound = 40)
        assertTrue(above.placement.opensUpwards)
        // 上放横排: 横排顶 = 锚顶 - 行高 - gap (面板按分支 2 向下长, 横排顶就是外框顶)
        assertFalse(above.placement.expandsUpwards)
        assertEquals(120 - rowHeight - gap, card(above, rowWidth, rowHeight).top.toInt())
        val below = frame(anchor, windowHeight = 640, topBound = 60)
        assertFalse(below.placement.opensUpwards)
        assertEquals(170 + gap, card(below, rowWidth, rowHeight).top.toInt())
    }

    /**
     * 坐标空间: 容器局部坐标按容器在窗口中的位置折一次; 根坐标按根在窗口中的偏移折一次。
     * 两者描述同一选区时必须得到同一落位，偏移不能被重复相加。
     */
    @Test
    fun `容器局部坐标与根坐标折到同一窗口位置`() {
        val localAnchor = Rect(100f, 110f, 158f, 422f)
        val origin = IntOffset(50, 30)
        val rootAnchor = Rect(150f, 140f, 208f, 452f)
        val localHandles = listOf(Rect(90f, 190f, 114f, 214f))
        val rootHandles = listOf(Rect(140f, 220f, 164f, 244f))
        val viaContainer = frame(
            localAnchor,
            windowHeight = 640,
            protectedRects = localHandles,
            anchorSpace = AppTextMenuAnchorSpace.Container,
            containerOriginInWindow = origin,
        )
        val viaRoot = frame(
            rootAnchor,
            windowHeight = 640,
            protectedRects = rootHandles,
            anchorSpace = AppTextMenuAnchorSpace.Root,
            rootOriginInWindow = IntOffset.Zero,
        )
        // 根坐标 + 根窗口偏移 = 窗口坐标 (Popup/对话框层): 与容器局部坐标同一落位
        val viaRootOffset = frame(
            localAnchor,
            windowHeight = 640,
            protectedRects = localHandles,
            anchorSpace = AppTextMenuAnchorSpace.Root,
            rootOriginInWindow = origin,
        )
        for (via in listOf(viaRoot, viaRootOffset)) {
            assertEquals(viaRoot.placement.frameEdgePx, via.placement.frameEdgePx)
            assertEquals(viaRoot.origin, via.origin)
            assertEquals(card(viaRoot, rowWidth, rowHeight), card(via, rowWidth, rowHeight))
        }
        assertEquals(viaRoot.origin, viaContainer.origin)
        assertEquals(card(viaRoot, rowWidth, rowHeight), card(viaContainer, rowWidth, rowHeight))
    }

    /** 根窗口偏移必须真正参与落位 (Popup/对话框层): 偏移不同则整张卡片平移同一量 */
    @Test
    fun `根窗口偏移按整量平移菜单`() {
        val anchor = Rect(150f, 140f, 208f, 452f)
        val base = frame(anchor, windowHeight = 640)
        val shifted = frame(
            anchor,
            windowHeight = 640,
            rootOriginInWindow = IntOffset(40, 24),
        )
        // 锚点同样右移 40 / 下移 24, 因此锚定边与落点一起平移 (视口空间内相对关系不变)
        assertEquals(base.placement.frameEdgePx + 40, shifted.placement.frameEdgePx)
        assertEquals(base.origin.x + 40, shifted.origin.x)
        assertEquals(base.origin.y + 24, shifted.origin.y)
        // 下移 24 使锚下方的可用高度同步少 24 (同一窗口、同一决策输入)
        assertEquals(base.placement.panelAvailHeightPx - 24, shifted.placement.panelAvailHeightPx)
    }

    /** 阴影留白口径：视觉卡片位置与阴影大小无关，只向外扩留白 */
    @Test
    fun `阴影口径：视觉位置与阴影大小无关`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        val reference = card(frame(anchor, windowHeight = 640, shadowPx = 0), rowWidth, rowHeight)
        for (shadowPx in intArrayOf(8, 16)) {
            val rect = card(frame(anchor, windowHeight = 640, shadowPx = shadowPx), rowWidth, rowHeight)
            assertEquals(reference.left, rect.left, 0f)
            assertEquals(reference.top, rect.top, 0f)
            assertEquals(reference.right, rect.right, 0f)
            assertEquals(reference.bottom, rect.bottom, 0f)
        }
    }

    @Test
    fun `横向锚中心居中、面板锚定边对齐横排、可用区边距钳位`() {
        val anchor = Rect(150f, 110f, 208f, 422f)
        // 锚中心 x = 179；横排 192 → 左缘 83（≥12 无钳），右缘 275
        val row = card(frame(anchor, windowHeight = 640), rowWidth, rowHeight)
        assertEquals(83, row.left.toInt())
        // 面板（视觉宽 120）：锚定边与横排同一像素
        val panel = card(frame(anchor, windowHeight = 640), panelWidth, panelFull)
        assertEquals(row.right, panel.right, 0f)
        // 窄可用区：按共同外框宽度钳到边距
        val narrow = frame(anchor, windowHeight = 640, windowWidth = 200)
        assertEquals(margin, card(narrow, rowWidth, rowHeight).left.toInt())
    }

    /**
     * RTL: 锚定边换成布局方向的 End (物理左缘)。把 LTR 的锚与保护矩形镜像后按 RTL 决策,
     * 得到的物理卡片矩形必须正好是 LTR 卡片的镜像 —— 测量/定位/保护几何同一布局方向。
     */
    @Test
    fun `RTL 与镜像后的 LTR 落位一致`() {
        val width = 600
        val cases = listOf(
            // 上放横排 + 面板向上
            Rect(150f, 560f, 208f, 610f) to listOf(Rect(204f, 586f, 228f, 610f)),
            // 下放横排 + 面板向下
            Rect(150f, 30f, 208f, 80f) to listOf(Rect(140f, 56f, 164f, 80f)),
            // 跨栏大选区 + 手势避让
            Rect(150f, 110f, 208f, 422f) to listOf(Rect(140f, 220f, 164f, 244f)),
        )
        for ((anchor, handles) in cases) {
            val ltr = frame(anchor, windowHeight = 640, windowWidth = width, protectedRects = handles)
            val rtl = frame(
                mirrored(anchor, width),
                windowHeight = 640,
                windowWidth = width,
                layoutDirection = LayoutDirection.Rtl,
                protectedRects = handles.map { mirrored(it, width) },
            )
            assertEquals(ltr.placement.expandsUpwards, rtl.placement.expandsUpwards)
            assertEquals(ltr.placement.opensUpwards, rtl.placement.opensUpwards)
            assertEquals(ltr.placement.panelAvailHeightPx, rtl.placement.panelAvailHeightPx)
            for (w in intArrayOf(rowWidth, 240, panelWidth)) {
                for (h in intArrayOf(rowHeight, 133, panelFull)) {
                    assertEquals(
                        "RTL 卡片应为 LTR 的镜像 (w=$w h=$h)",
                        mirrored(card(ltr, w, h), width),
                        card(rtl, w, h),
                    )
                }
            }
        }
    }

    /** RTL 横向钳位: 左缘不得越过屏边距, 且开合中间帧锚定左边不动 */
    @Test
    fun `RTL 锚定左边在开合中间帧不动`() {
        val width = 600
        val anchor = mirrored(Rect(0f, 110f, 324f, 422f), width)
        val f = frame(anchor, windowHeight = 640, windowWidth = width, layoutDirection = LayoutDirection.Rtl)
        val anchoredLeft = card(f, rowWidth, rowHeight).left
        assertTrue(anchoredLeft >= margin)
        for (h in intArrayOf(rowHeight, 133, panelFull)) {
            val rect = card(f, panelWidth, h)
            assertEquals(anchoredLeft, rect.left, 0f)
            assertTrue(rect.right <= width - margin)
        }
    }

    /**
     * 可用区: 内容区与安全区取交集。内容区已有偏移时安全区不能再相加 (相加会二次内缩)。
     */
    @Test
    fun `可用区是内容区与安全区的交集而非相加`() {
        val window = IntSize(1000, 800)
        val safe = IntRect(16, 24, 12, 20)
        // 内容区整体在安全区之内: 结果就是内容区, 不因安全区而内缩
        assertEquals(
            IntRect(40, 30, 800, 600),
            textMenuAvailRect(
                contentArea = IntRect(40, 30, 800, 600),
                windowSize = window,
                safeInsets = safe,
                overlayTopPx = 0,
                overlayGapPx = 0,
            ),
        )
        // 内容区从桌面控制条下方开始: 上界取两者较大值 48, 不是 40 + 48
        assertEquals(
            IntRect(16, 48, 988, 780),
            textMenuAvailRect(
                contentArea = IntRect(0, 40, 1000, 800),
                windowSize = window,
                safeInsets = safe,
                overlayTopPx = 40,
                overlayGapPx = 8,
            ),
        )
        // 无内容区时用整窗减安全区
        assertEquals(
            IntRect(16, 48, 988, 780),
            textMenuAvailRect(
                contentArea = null,
                windowSize = window,
                safeInsets = safe,
                overlayTopPx = 40,
                overlayGapPx = 8,
            ),
        )
        // 交集为空 (内容区在安全区之外) 时得到空区域, 调用方不摆放卡片
        val empty = textMenuAvailRect(
            contentArea = IntRect(0, 0, 10, 10),
            windowSize = window,
            safeInsets = IntRect(16, 24, 12, 20),
            overlayTopPx = 0,
            overlayGapPx = 0,
        )
        assertTrue(empty.width <= 0 || empty.height <= 0)
    }

    /** 面板测宽: 滚动条可触宽度计入内容宽 (滑块不压标签), 同时受最小宽与可用上限约束 */
    @Test
    fun `面板宽含滚动条可触宽度且受最小宽与上限约束`() {
        // 两侧内边距按渲染时的逐边取整之和: 移动端 16 + (16+15) = 47
        // 最宽项 60 + 47 = 107
        assertEquals(107, menuPanelWidthPx(60, 47, 100, 480))
        // 桌面触区 24: 两侧 16 + (16+24) = 56, 同一标签宽下多 9px
        assertEquals(116, menuPanelWidthPx(60, 56, 100, 480))
        // 最小宽 100 生效
        assertEquals(100, menuPanelWidthPx(20, 47, 100, 480))
        // 可用上限小于内容宽时按上限 (文字单行省略)
        assertEquals(300, menuPanelWidthPx(500, 47, 100, 300))
    }

    /** 菜单滚动条长度按真实视口/可滚范围给, 不是列表那档固定长度 */
    @Test
    fun `菜单滑块比例按真实视口与可滚范围`() {
        assertEquals(0.75f, fastScrollThumbFraction(viewportSize = 150, maxValue = 50), 0f)
        assertEquals(0.1f, fastScrollThumbFraction(viewportSize = 50, maxValue = 450), 0f)
        // 可滚范围取到 Int 上限也不溢加: 两边相等时正好一半
        assertEquals(
            0.5f,
            fastScrollThumbFraction(viewportSize = Int.MAX_VALUE, maxValue = Int.MAX_VALUE),
            0f,
        )
    }
}
