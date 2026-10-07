package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 筛选选项的统一样式: 选项数超过 [OptionChipThreshold] 出横向滚动 chip 行, 否则出下拉;
 * 相邻下拉按可用宽度并排 (放不下的换行), 一行的下拉等分宽度。
 *
 * 判定只在本文件, 调用点不再各自裁决用 chip 还是下拉。chips 与下拉的选中语义由调用点给的
 * 回调决定: [AppChoiceField] 单选 (点选即收起), [AppMultiChoiceField] 多选 (下拉内点选即切
 * 且不收起菜单, 对齐 tag chip 的多次点选)。
 *
 * 下拉的菜单热区占满标题以外的整行 (对齐原版 chip 行"整颗可点"的命中面); 单选行给了
 * [AppChoiceField.onReset] 时标题留作重置钮 (原版点标题 chip 重置), 其余行的标题并入热区。
 */
const val OptionChipThreshold = 4

/** 下拉箭头 + 值区两侧内边距的固定横向占位, 供分行测量预留。 */
private val DropdownActionExtent = 40.dp

private val ChoiceFontSize = 14.sp

/** 单选筛选行 (onReset 非空时标题可点重置到默认, 对齐原版点标题 chip 重置); selectedIndex 为 -1 表示当前值不在选项内, 下拉如实留空而不是预选第一档。 */
data class AppChoiceField(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
    val onReset: (() -> Unit)? = null,
)

/** 多选筛选行 (已选集合为空 = 不过滤)。 */
data class AppMultiChoiceField(
    val title: String,
    val options: List<String>,
    val selectedIndexes: Set<Int>,
    val onToggle: (Int) -> Unit,
)

@Composable
fun AppChoiceRowGroup(
    fields: List<AppChoiceField>,
    modifier: Modifier = Modifier,
) {
    ChoiceRows(modifier = modifier, rows = fields.map { it.toChoiceRow() })
}

@Composable
fun AppMultiChoiceRowGroup(
    fields: List<AppMultiChoiceField>,
    modifier: Modifier = Modifier,
) {
    ChoiceRows(modifier = modifier, rows = fields.map { it.toChoiceRow() })
}

/** 内部统一行模型: 单选与多选只差 [multiSelect] 与回调, 渲染与分行逻辑共用。 */
private data class ChoiceRow(
    val title: String,
    val options: List<String>,
    val selectedIndexes: Set<Int>,
    val multiSelect: Boolean,
    val onSelect: (Int) -> Unit,
    val onReset: (() -> Unit)?,
) {
    val displayValue: String
        get() = options.filterIndexed { index, _ -> index in selectedIndexes }
            .joinToString(separator = "、")

    fun isChipRow(): Boolean = options.size > OptionChipThreshold
}

private fun AppChoiceField.toChoiceRow() = ChoiceRow(
    title = title,
    options = options,
    selectedIndexes = setOf(selectedIndex),
    multiSelect = false,
    onSelect = onSelect,
    onReset = onReset,
)

private fun AppMultiChoiceField.toChoiceRow() = ChoiceRow(
    title = title,
    options = options,
    selectedIndexes = selectedIndexes,
    multiSelect = true,
    onSelect = onToggle,
    onReset = null,
)

@Composable
private fun ChoiceRows(
    rows: List<ChoiceRow>,
    modifier: Modifier,
) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        buildChoiceGroups(rows).forEach { group ->
            if (group.first().isChipRow()) {
                ChoiceChipRow(group.first())
            } else {
                ChoiceDropdownLines(group)
            }
        }
    }
}

/** 连续的下拉行合并为一段 (段内统一按可用宽度分行), 任何 chip 行自成一 段。 */
private fun buildChoiceGroups(rows: List<ChoiceRow>): List<List<ChoiceRow>> {
    val groups = ArrayList<ArrayList<ChoiceRow>>()
    rows.forEach { row ->
        val last = groups.lastOrNull()
        if (row.isChipRow() || last == null || last.first().isChipRow()) {
            groups.add(arrayListOf(row))
        } else {
            last.add(row)
        }
    }
    return groups
}

@Composable
private fun ChoiceChipRow(row: ChoiceRow) {
    AppChipRow {
        AppChipRowTitle(text = row.title, onClick = row.onReset)
        row.options.forEachIndexed { index, label ->
            AppChipRowOption(
                text = label,
                selected = index in row.selectedIndexes,
                onClick = { row.onSelect(index) },
            )
        }
    }
}

@Composable
private fun ChoiceDropdownLines(rows: List<ChoiceRow>) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val gap = DesignTokens.spacingDefault
        val style = TextStyle(fontSize = ChoiceFontSize)
        val availableWidth = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val actionPx = with(density) { DropdownActionExtent.toPx() }
        val lines = splitChoiceLines(rows, availableWidth, gapPx, actionPx, measurer, style)
        Column(Modifier.fillMaxWidth()) {
            lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) Spacer(Modifier.height(DesignTokens.spacingXs))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    line.forEachIndexed { index, row ->
                        if (index > 0) Spacer(Modifier.width(gap))
                        ChoiceDropdown(row, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 贪心分行: 每行尽量多放, 放不下的换行 (每个下拉至少独占一行)。 */
private fun splitChoiceLines(
    rows: List<ChoiceRow>,
    availableWidth: Float,
    gap: Float,
    action: Float,
    measurer: TextMeasurer,
    style: TextStyle,
): List<List<ChoiceRow>> {
    val lines = ArrayList<ArrayList<ChoiceRow>>()
    var current = ArrayList<ChoiceRow>()
    var used = 0f
    rows.forEach { row ->
        val width = row.intrinsicWidth(measurer, style, action)
        when {
            current.isEmpty() -> {
                current.add(row)
                used = width
            }

            used + gap + width <= availableWidth -> {
                current.add(row)
                used += gap + width
            }

            else -> {
                lines.add(current)
                current = arrayListOf(row)
                used = width
            }
        }
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines
}

/** 固有宽度: 标题 + 当前值 + 箭头与内边距 (宽度不足时按此决定每行放几个)。 */
private fun ChoiceRow.intrinsicWidth(
    measurer: TextMeasurer,
    style: TextStyle,
    action: Float,
): Float {
    val titleWidth = measurer.measure(title, style = style).size.width
    val valueWidth = measurer.measure(displayValue, style = style).size.width
    return titleWidth + valueWidth + action
}

/**
 * 单行下拉的菜单内容: 单选点选即收起, 多选点选不收起 (对齐 tag chip 的多次点选)。
 */
@Composable
private fun ChoiceDropdownMenu(row: ChoiceRow, dismiss: () -> Unit) {
    val colors = AppTheme.colors
    if (row.multiSelect) {
        row.options.forEachIndexed { index, label ->
            DropdownMenuItem(onClick = { row.onSelect(index) }) {
                Text(
                    text = if (index in row.selectedIndexes) "✓ $label" else label,
                    color = colors.primaryText,
                )
            }
        }
    } else {
        AppSingleChoiceMenu(row.options, row.onSelect, dismiss)
    }
}

/**
 * 单行下拉: 菜单热区占满标题以外的整行 (不再只圈住值 + ▾, 行内不留死区);
 * 标题本身有重置语义 ([ChoiceRow.onReset] 非空, 对齐原版点标题 chip 重置) 时留作重置钮,
 * 否则标题并入热区, 点标题也开菜单。
 */
@Composable
private fun ChoiceDropdown(row: ChoiceRow, modifier: Modifier) {
    val colors = AppTheme.colors
    val onReset = row.onReset
    if (onReset == null) {
        AppDropdownAnchor(
            valueText = row.displayValue,
            modifier = modifier,
            leading = {
                Text(
                    text = row.title,
                    color = colors.primaryText,
                    fontSize = ChoiceFontSize,
                    modifier = Modifier.weight(1f),
                )
            },
            menuContent = { dismiss -> ChoiceDropdownMenu(row, dismiss) },
        )
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.title,
            color = colors.primaryText,
            fontSize = ChoiceFontSize,
            modifier = Modifier.clickable(onClick = onReset),
        )
        AppDropdownAnchor(
            valueText = row.displayValue,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            menuContent = { dismiss -> ChoiceDropdownMenu(row, dismiss) },
        )
    }
}
