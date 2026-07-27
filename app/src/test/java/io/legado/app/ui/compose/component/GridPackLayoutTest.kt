package io.legado.app.ui.compose.component

import io.legado.app.data.entities.rule.FlexChildStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * packGridCells 占格打包纯函数测试，对照原 android.widget.GridLayout
 * (columnCount=12, orientation=horizontal) 的先到先占格自动放置。
 */
class GridPackLayoutTest {

    private fun spec(cols: Int? = null, rows: Int = 1): GridPackSpec =
        FlexChildStyle(cols = cols, rows = rows).toGridPackSpec()

    /**
     * 用户样例：[{"style":{"rows":2,"cols":3}},{},{},{},{}]（发现分类默认 cols=3）。
     * 第 1 项纵跨两行占第一列，第 2/3 项在第一行，第 4/5 项填进第二行（第 1 项旁的空格）。
     */
    @Test
    fun userSample_followersPackIntoSecondRowBesideRowSpanItem() {
        val cells = packGridCells(
            listOf(
                spec(cols = 3, rows = 2),
                spec(), spec(), spec(), spec(),
            )
        )
        assertEquals(listOf(0, 0, 0, 1, 1), cells.map { it.row })
        assertEquals(listOf(0, 4, 8, 4, 8), cells.map { it.col })
        assertEquals(2, cells[0].rowSpan)
        assertEquals(List(5) { 4 }, cells.map { it.colSpan })
    }

    /** 纵跨项在行尾：下一行从头开始填，不受行尾纵跨项影响。 */
    @Test
    fun rowSpanItemAtRowEnd_nextRowFillsFromStart() {
        val cells = packGridCells(
            listOf(
                spec(), spec(), spec(cols = 3, rows = 2),
                spec(), spec(),
            )
        )
        assertEquals(listOf(0, 0, 0, 1, 1), cells.map { it.row })
        assertEquals(listOf(0, 4, 8, 0, 4), cells.map { it.col })
    }

    /** 混合 cols：光标逐格右移，cols=2 项可从非对齐列起放；放不下换行。 */
    @Test
    fun mixedCols_cursorPacksNonAlignedThenWraps() {
        val cells = packGridCells(
            listOf(spec(cols = 2), spec(cols = 3), spec(cols = 2))
        )
        // span6@(0,0) + span4@(0,6)，第三项 span6 放不下(剩 2 格)换行
        assertEquals(
            listOf(
                GridPackCell(0, 0, 1, 6),
                GridPackCell(0, 6, 1, 4),
                GridPackCell(1, 0, 1, 6),
            ),
            cells,
        )
    }

    /** 全默认 cols=3：一行三个顺排换行。 */
    @Test
    fun allDefault_threePerRow() {
        val cells = packGridCells(List(5) { spec() })
        assertEquals(listOf(0, 0, 0, 1, 1), cells.map { it.row })
        assertEquals(listOf(0, 4, 8, 0, 4), cells.map { it.col })
    }

    /**
     * 纵跨项后跟整行项：光标只前进不回退（同 GridLayout），整行项落到纵跨项下方，
     * 第一行右侧的空缺不回填，后续项从整行项之后继续。
     */
    @Test
    fun fullWidthAfterRowSpan_cursorNeverBacktracks() {
        val cells = packGridCells(
            listOf(spec(cols = 3, rows = 2), spec(cols = 1), spec())
        )
        assertEquals(listOf(0, 2, 3), cells.map { it.row })
        assertEquals(listOf(0, 0, 0), cells.map { it.col })
        assertEquals(listOf(4, 12, 4), cells.map { it.colSpan })
    }

    /** style 契约兜底：旧版 flexBasisPercent 映射、cols 超界钳位、rows<1 归一。 */
    @Test
    fun toGridPackSpec_legacyAndClamping() {
        // flexBasisPercent=0.5 → cols=2 → span 6
        assertEquals(
            GridPackSpec(6, 1),
            FlexChildStyle(layout_flexBasisPercent = 0.5f).toGridPackSpec(),
        )
        // 0.33 含浮点误差 → cols=3 → span 4
        assertEquals(
            GridPackSpec(4, 1),
            FlexChildStyle(layout_flexBasisPercent = 0.33f).toGridPackSpec(),
        )
        // cols=9 钳到 MAX_COLS=4 → span 3；rows=0 归一为 1
        assertEquals(GridPackSpec(3, 1), FlexChildStyle(cols = 9, rows = 0).toGridPackSpec())
        // 未填 style 字段 → 默认 cols=3
        assertEquals(GridPackSpec(4, 1), FlexChildStyle().toGridPackSpec())
    }
}
