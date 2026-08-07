package io.legado.app.ui.compose.component

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 参考宽度: 用户设置的"列数"被定义为这个宽度下的列数; 低于此宽度的手机严格按用户列数, 高于此宽度按比例加列, 400dp 为用户确认的折算起点 */
val ResponsiveReferenceWidth: Dp = 400.dp

/** 最窄列宽守卫系数: 列宽下限 = 参考列宽 × 此比例 (round 进位边界防"加列反而更挤")。 */
private const val MIN_COLUMN_WIDTH_RATIO = 0.8f

/**
 * 响应式列数: 用户设置的列数 N 只描述"参考宽度下的列数", 容器更宽时按比例加列。
 *
 * 取整策略 (round + 最窄列宽守卫, 均为内部策略不对外暴露):
 * - `max` 钳底保证 ≤ 参考宽度的手机与原来完全一致(存量配置零迁移);
 * - `round` 在 0.5 进位边界提前加列 (列宽 ≈ 参考列宽×0.8 起), 比 floor 更积极利用空间;
 * - 守卫: round 进位后若列宽跌破守卫线 (如 1400dp/N=1 → round(3.5)=4 列 350dp,
 *   比 1200dp 的 3 列 400dp 更窄), 循环退列直到列宽回到守卫线上方
 *   (0.5 进位通常只差 1 列, 循环保证大 base 的 round 误差也能兜住)。
 */
fun effectiveColumns(baseColumns: Int, availableWidth: Dp, referenceWidth: Dp): Int {
    val base = baseColumns.coerceAtLeast(1)
    val scaled = (base * (availableWidth / referenceWidth)).roundToInt()
    // 参考列宽 = referenceWidth / base; 守卫下限 = 其 MIN_COLUMN_WIDTH_RATIO 倍
    val minColumnWidth = referenceWidth / base * MIN_COLUMN_WIDTH_RATIO
    var columns = scaled
    while (columns > base && availableWidth / columns < minColumnWidth) {
        columns--
    }
    return columns
}

/**
 * 按容器可用宽度自动加列的 [GridCells]。
 *
 * 宽度直接取自网格自身的测量结果, 因此不需要 BoxWithConstraints 之类的额外测量,
 * 且天然量到的是容器可用宽度(对话框内=对话框宽, Pager 页内=页宽), 而非窗口宽。
 */
class ResponsiveGridCells(
    private val baseColumns: Int,
    private val referenceWidth: Dp = ResponsiveReferenceWidth,
) : GridCells {

    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val columns = effectiveColumns(baseColumns, availableSize.toDp(), referenceWidth)
        // 与 GridCells.Fixed 同一套整数分配: 余数逐格 +1, 避免右侧留缝
        val sizeWithoutSpacing = availableSize - spacing * (columns - 1)
        val slotSize = sizeWithoutSpacing / columns
        val remainingPixels = sizeWithoutSpacing % columns
        return List(columns) { slotSize + if (it < remainingPixels) 1 else 0 }
    }

    override fun hashCode(): Int = baseColumns * 31 + referenceWidth.hashCode()

    override fun equals(other: Any?): Boolean = other is ResponsiveGridCells &&
        other.baseColumns == baseColumns && other.referenceWidth == referenceWidth
}

/** [ResponsiveGridCells] 的记忆化入口, 直接传给 LazyVerticalGrid 的 columns */
@Composable
fun rememberResponsiveColumns(
    baseColumns: Int,
    referenceWidth: Dp = ResponsiveReferenceWidth,
): GridCells = remember(baseColumns, referenceWidth) {
    ResponsiveGridCells(baseColumns, referenceWidth)
}
