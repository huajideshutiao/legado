package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.rule.FlexChildStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/** 一项在虚拟 12 列网格中的跨度：横跨 [colSpan] 列 × 纵跨 [rowSpan] 行。 */
data class GridPackSpec(val colSpan: Int, val rowSpan: Int)

/** 打包结果：一项占据的格子（左上角 [row]/[col] + 跨度）。 */
data class GridPackCell(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

/**
 * 书源 style{cols,rows} → 网格跨度。cols=N 占 12/N 格宽（1=整行、3=1/3 宽），rows=M 纵跨 M 行。
 * cols 解析复刻 FlexChildStyle.resolveCols（私有）：cols 优先，旧版 flexBasisPercent 兜底，默认 3。
 */
fun FlexChildStyle.toGridPackSpec(): GridPackSpec {
    val resolvedCols = cols?.coerceIn(1, FlexChildStyle.MAX_COLS)
        ?: if (layout_flexBasisPercent <= 0f) {
            FlexChildStyle.DEFAULT_COLS
        } else {
            // 1.001f 容错处理 1/3 等浮点精度
            (1.001f / layout_flexBasisPercent).toInt().coerceIn(1, FlexChildStyle.MAX_COLS)
        }
    return GridPackSpec(
        colSpan = FlexChildStyle.BASE_COLUMN_COUNT / resolvedCols,
        rowSpan = rows.coerceAtLeast(1),
    )
}

/**
 * 占格打包：复刻 android.widget.GridLayout 横向自动放置（先到先占格）。
 * 光标 (row,col) 只前进不回退；逐格右移找能容纳 colSpan 的空位，行尾放不下就换行；
 * 纵跨项把所占列的"水位"抬到下方行，后续项绕开被占的格子。
 */
fun packGridCells(
    specs: List<GridPackSpec>,
    columnCount: Int = FlexChildStyle.BASE_COLUMN_COUNT,
): List<GridPackCell> {
    // highWater[c]：第 c 列下一个空闲行号，对照 GridLayout.validateLayoutParams 的 maxSizes
    val highWater = IntArray(columnCount)
    var row = 0
    var col = 0
    return specs.map { spec ->
        val colSpan = spec.colSpan.coerceIn(1, columnCount)
        val rowSpan = spec.rowSpan.coerceAtLeast(1)
        while (!fits(highWater, row, col, col + colSpan)) {
            if (col + colSpan <= columnCount) {
                col++
            } else {
                col = 0
                row++
            }
        }
        val cell = GridPackCell(row, col, rowSpan, colSpan)
        highWater.fill(row + rowSpan, col, col + colSpan)
        col += colSpan
        cell
    }
}

private fun fits(highWater: IntArray, row: Int, start: Int, end: Int): Boolean {
    if (end > highWater.size) return false
    for (c in start until end) {
        if (highWater[c] > row) return false
    }
    return true
}

/**
 * 预估 GridPackLayout 的最终高度（行数 × 行单位高度），不需等待实际测量。
 *
 * 用于发现界面展开动画提前知道目标高度，避免动画期间高度跳变。
 *
 * 算法（对照补充.txt思路）：
 * - 非 JSON 发现（style=null，默认 cols=3）：每个 item 占 12/3=4 列宽，
 *   总行数 = ceil(item数 / (12/4)) = ceil(item数 / 3)
 * - JSON 发现（有 style）：每个 item 占 12/cols 列宽，
 *   总行数 = ceil(总权重 / 12) 其中总权重 = sum(每个item的 12/cols)
 * - 纵跨项(rows>1)会抬高水位，需要跑 packGridCells 精确计算
 *
 * @param specs 各项的占格规格
 * @param rowUnitMinHeight 每行最小高度
 * @param columnCount 列总数（默认 12）
 * @return 预估高度（dp）
 */
fun estimateGridHeight(
    specs: List<GridPackSpec>,
    rowUnitMinHeight: Dp,
    columnCount: Int = FlexChildStyle.BASE_COLUMN_COUNT,
): Dp {
    if (specs.isEmpty()) return 0.dp
    // 精确计算：跑 packGridCells 取最终行数
    val cells = packGridCells(specs, columnCount)
    val rowCount = cells.maxOfOrNull { it.row + it.rowSpan } ?: 0
    return rowUnitMinHeight * rowCount
}

/**
 * 网格占格布局：按 [packGridCells] 结果放置 content 各子项（与 [specs] 一一对应）。
 * 列宽 = 可用宽 × colSpan/12（同行 cols 之和不足 12 时右侧留白）；
 * 行高取行内容最大自然高，子项最小格高 [rowUnitMinHeight]×rowSpan；
 * 子项拉伸填满整格（复刻 GridLayout FILL），纵跨项高度不足时差额落到跨度末行。
 */
@Composable
fun GridPackLayout(
    specs: List<GridPackSpec>,
    modifier: Modifier = Modifier,
    columnCount: Int = FlexChildStyle.BASE_COLUMN_COUNT,
    rowUnitMinHeight: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val count = minOf(measurables.size, specs.size)
        val cells = packGridCells(specs.subList(0, count), columnCount)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        // 列边界用累计整除分配取整误差，避免各格四舍五入后行宽不齐
        fun colX(c: Int): Int = width * c / columnCount
        val minUnitPx = rowUnitMinHeight.roundToPx()
        // 每项自然高：内容 intrinsic 与最小格高取大（对照原 view.minimumHeight = 单位高×rows）
        val naturalHeights = IntArray(count) { i ->
            val cellWidth = colX(cells[i].col + cells[i].colSpan) - colX(cells[i].col)
            maxOf(measurables[i].maxIntrinsicHeight(cellWidth), minUnitPx * cells[i].rowSpan)
        }
        val rowCount = cells.maxOfOrNull { it.row + it.rowSpan } ?: 0
        // 行边界取最小解：每项要求"其跨度末边界 ≥ 起始边界 + 自然高"，同 GridLayout 约束求解
        val rowY = IntArray(rowCount + 1)
        for (r in 1..rowCount) {
            var boundary = rowY[r - 1]
            for (i in 0 until count) {
                val cell = cells[i]
                if (cell.row + cell.rowSpan == r) {
                    boundary = maxOf(boundary, rowY[cell.row] + naturalHeights[i])
                }
            }
            rowY[r] = boundary
        }
        val placeables = List(count) { i ->
            val cell = cells[i]
            val w = colX(cell.col + cell.colSpan) - colX(cell.col)
            val h = rowY[cell.row + cell.rowSpan] - rowY[cell.row]
            measurables[i].measure(Constraints(w, w, h, h))
        }
        layout(width, rowY[rowCount].coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { i, placeable ->
                placeable.placeRelative(colX(cells[i].col), rowY[cells[i].row])
            }
        }
    }
}

// ===== @Preview 合并自 androidMain 的 compose/component/GridPackLayoutPreviews.kt =====

/**
 * [GridPackLayout.kt] 中 [GridPackLayout] 的 @Preview。
 *
 * GridPackLayout: 占格打包布局, 模拟 GridLayout 横向自动放置。
 * Preview 用不同 colSpan/rowSpan 的子项展示布局效果。
 */

@Preview
@Composable
fun GridPackLayoutPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        GridPackLayout(
            specs = listOf(
                GridPackSpec(colSpan = 6, rowSpan = 1), // 半行宽
                GridPackSpec(colSpan = 6, rowSpan = 1), // 半行宽
                GridPackSpec(colSpan = 4, rowSpan = 1), // 1/3 宽
                GridPackSpec(colSpan = 4, rowSpan = 1),
                GridPackSpec(colSpan = 4, rowSpan = 1),
                GridPackSpec(colSpan = 12, rowSpan = 1), // 整行
            ),
            modifier = Modifier.fillMaxWidth(),
            rowUnitMinHeight = 48.dp,
        ) {
            // content lambda 被多次调用 (每个 spec 一次)
            // 此处用单一 placeholder, 实际项目里每个子项不同
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0x33165DFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("GridPack 子项")
            }
        }
    }
}

@Preview
@Composable
fun GridPackLayoutRowSpanPreview() = LegadoThemePreview {
    Box(Modifier.padding(8.dp)) {
        GridPackLayout(
            specs = listOf(
                GridPackSpec(colSpan = 4, rowSpan = 2), // 跨 2 行
                GridPackSpec(colSpan = 8, rowSpan = 1),
                GridPackSpec(colSpan = 8, rowSpan = 1),
            ),
            modifier = Modifier.fillMaxWidth(),
            rowUnitMinHeight = 60.dp,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x33165DFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text("跨行子项", modifier = Modifier.padding(8.dp))
            }
        }
    }
}
