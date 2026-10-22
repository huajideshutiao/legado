package io.legado.app.ui.compose.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.clear
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.search
import org.jetbrains.compose.resources.stringResource

/**
 * 筛选选项的统一样式。
 *
 * 单选行: 选项数超过 [OptionChipThreshold] 出横向滚动 chip 行, 否则出下拉; 相邻下拉按可用宽度
 * 并排 (放不下的换行), 一行的下拉等分宽度。
 *
 * 多选行: 对齐原版 setUpExploreOptions, 只有一种形态 —— 标题 + 已选标签 (未选项不占位),
 * 点按/长按整行打开带搜索的选择弹窗, 确认时一次写回。分类动辄上百项时不会撑成超长滚动带,
 * 也不会每点一项就重搜一次。
 *
 * 入口统一: 行名长按、整行鼠标右键、多选行的整行点按都打开同一个可搜索选择弹窗;
 * 单选行的点按语义不变 (点标题重置默认、点选项切换)。
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

/**
 * 多选筛选行 (已选集合为空 = 不过滤)。
 *
 * 选择在弹窗内进行, 确认时把整份选中集合交回 [onSelectionChange] 一次 —— 逐项回调会让
 * 调用方每点一下重搜一次。
 */
data class AppMultiChoiceField(
    val title: String,
    val options: List<String>,
    val selectedIndexes: Set<Int>,
    val onSelectionChange: (Set<Int>) -> Unit,
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

/** 内部统一行模型: 单选与多选只差 [multiSelect] 与回调, 分行与弹窗共用。
 *
 * 回调各归一边: 单选行用 [onSelect] (逐项) 与 [onReset], 多选行用 [onSelectionChange]
 * (弹窗确认时整份写回), 对方那一组回调为空。
 */
private data class ChoiceRow(
    val title: String,
    val options: List<String>,
    val selectedIndexes: Set<Int>,
    val multiSelect: Boolean,
    val onSelect: (Int) -> Unit,
    val onSelectionChange: (Set<Int>) -> Unit,
    val onReset: (() -> Unit)?,
) {
    val displayValue: String
        get() = options.filterIndexed { index, _ -> index in selectedIndexes }
            .joinToString(separator = "、")

    fun isChipRow(): Boolean = options.size > OptionChipThreshold

    /** 独占一段: 多选行与 chip 行都不与相邻下拉行并排。 */
    fun isStandalone(): Boolean = multiSelect || isChipRow()
}

private fun AppChoiceField.toChoiceRow() = ChoiceRow(
    title = title,
    options = options,
    selectedIndexes = setOf(selectedIndex),
    multiSelect = false,
    onSelect = onSelect,
    onSelectionChange = {},
    onReset = onReset,
)

private fun AppMultiChoiceField.toChoiceRow() = ChoiceRow(
    title = title,
    options = options,
    selectedIndexes = selectedIndexes,
    multiSelect = true,
    onSelect = {},
    onSelectionChange = onSelectionChange,
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
            val row = group.first()
            when {
                row.multiSelect -> MultiChoiceChipRow(row)
                row.isChipRow() -> SingleChoiceChipRow(row)
                else -> ChoiceDropdownLines(group)
            }
        }
    }
}

/** 连续的单选下拉行合并为一段 (段内统一按可用宽度分行), 多选行与 chip 行各自成段。 */
private fun buildChoiceGroups(rows: List<ChoiceRow>): List<List<ChoiceRow>> {
    val groups = ArrayList<ArrayList<ChoiceRow>>()
    rows.forEach { row ->
        val last = groups.lastOrNull()
        if (row.isStandalone() || last == null || last.first().isStandalone()) {
            groups.add(arrayListOf(row))
        } else {
            last.add(row)
        }
    }
    return groups
}

/** 单选 chip 行: 标题点按重置默认、长按打开弹窗; 选项 chip 点按即切换。
 *
 * 整行右键热区包在横向滚动外层: 内容比屏幕窄时右侧空白同样能触发。
 */
@Composable
private fun SingleChoiceChipRow(row: ChoiceRow) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().then(rememberSecondaryClickModifier { pickerOpen = true })) {
        AppChipRow {
            AppChipRowTitle(
                text = row.title,
                onClick = row.onReset,
                onLongClick = { pickerOpen = true },
            )
            row.options.forEachIndexed { index, label ->
                AppChipRowOption(
                    text = label,
                    selected = index in row.selectedIndexes,
                    onClick = { row.onSelect(index) },
                )
            }
        }
    }
    if (pickerOpen) ChoicePickerDialog(row, onDismiss = { pickerOpen = false })
}

/**
 * 多选行: 标题 + 已选标签 (未选项不占位), 点按/长按整行、鼠标右键都打开选择弹窗。
 * 标签不带点按: 触摸冒泡到整行, 胶囊之间的空白同样能打开弹窗。
 * 热区包在横向滚动外层, 覆盖整行宽度 (内容窄时右侧空白也能点)。
 */
@Composable
private fun MultiChoiceChipRow(row: ChoiceRow) {
    var pickerOpen by remember { mutableStateOf(false) }
    val open = { pickerOpen = true }
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = open,
                onLongClick = open,
            )
            .then(rememberSecondaryClickModifier(open)),
    ) {
        AppChipRow {
            AppChipRowTitle(text = row.title)
            row.options.forEachIndexed { index, label ->
                if (index in row.selectedIndexes) {
                    AppChipRowOption(text = label)
                }
            }
        }
    }
    if (pickerOpen) ChoicePickerDialog(row, onDismiss = { pickerOpen = false })
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
 * 单行下拉: 菜单热区占满标题以外的整行 (不再只圈住值 + ▾, 行内不留死区);
 * 标题本身有重置语义 ([ChoiceRow.onReset] 非空, 对齐原版点标题 chip 重置) 时留作重置钮,
 * 否则标题并入热区, 点标题也开菜单。行名长按与整行鼠标右键都打开选择弹窗。
 */
@Composable
private fun ChoiceDropdown(row: ChoiceRow, modifier: Modifier) {
    val colors = AppTheme.colors
    var pickerOpen by remember { mutableStateOf(false) }
    val open = { pickerOpen = true }
    val onReset = row.onReset
    val rowModifier = modifier.then(rememberSecondaryClickModifier(open))
    if (onReset == null) {
        AppDropdownAnchor(
            valueText = row.displayValue,
            modifier = rowModifier,
            leading = {
                Text(
                    text = row.title,
                    color = colors.primaryText,
                    fontSize = ChoiceFontSize,
                    modifier = Modifier.weight(1f),
                )
            },
            onLongClick = open,
            menuContent = { dismiss -> AppSingleChoiceMenu(row.options, row.onSelect, dismiss) },
        )
    } else {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                color = colors.primaryText,
                fontSize = ChoiceFontSize,
                modifier = Modifier.combinedClickable(
                    onClick = onReset,
                    onLongClick = open,
                ),
            )
            AppDropdownAnchor(
                valueText = row.displayValue,
                modifier = Modifier.weight(1f),
                onLongClick = open,
                menuContent = { dismiss -> AppSingleChoiceMenu(row.options, row.onSelect, dismiss) },
            )
        }
    }
    if (pickerOpen) ChoicePickerDialog(row, onDismiss = { pickerOpen = false })
}

/**
 * 行名长按 / 整行右键打开的选择弹窗: 顶栏放大镜进搜索态, 列表按标签过滤。
 *
 * 单选行点选即写回并关闭 (与换源选择一致); 多选行点选只改工作副本, 底栏「确定」才一次写回,
 * 「清空」立即清空并关闭 (对齐原版 okButton / neutralButton(clear) 语义)。
 *
 * 搜索只匹配标签文本: 列表不显示取值, 按取值匹配会命中"看不见关键词"的条目。
 */
@Composable
private fun ChoicePickerDialog(row: ChoiceRow, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val searchState = rememberAppDialogSearchState()
    val query = searchState.query
    val visible = remember(row.options, query) {
        row.options.withIndex().filter {
            query.isBlank() || it.value.contains(query, ignoreCase = true)
        }
    }
    // 打开即停在当前选中项附近 (选项上百时省掉手滑)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = visible.indexOfFirst {
            it.index in row.selectedIndexes
        }.coerceAtLeast(0),
    )
    var working by remember(row.selectedIndexes) { mutableStateOf(row.selectedIndexes) }
    AppPickDialog(
        onDismissRequest = onDismiss,
        title = row.title,
        searchState = searchState,
        searchHint = stringResource(Res.string.search),
        properties = AppDialogSizes.properties(),
        bottomBar = if (row.multiSelect) {
            {
                AppTextButton(text = stringResource(Res.string.clear)) {
                    if (row.selectedIndexes.isNotEmpty()) row.onSelectionChange(emptySet())
                    onDismiss()
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(
                    text = stringResource(Res.string.cancel),
                    color = colors.secondaryText,
                    onClick = onDismiss,
                )
                AppTextButton(text = stringResource(Res.string.ok)) {
                    if (working != row.selectedIndexes) row.onSelectionChange(working)
                    onDismiss()
                }
            }
        } else null,
    ) {
        FastScrollLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(visible, key = { it.index }) { entry ->
                val label = entry.value
                if (row.multiSelect) {
                    val checked = entry.index in working
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                            ) { isChecked ->
                                working = if (isChecked) {
                                    working + entry.index
                                } else {
                                    working - entry.index
                                }
                            }
                            .padding(
                                horizontal = DesignTokens.spacingLg,
                                vertical = DesignTokens.spacingDefault,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(checked = checked, onCheckedChange = null)
                        Text(
                            text = label,
                            color = colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                        )
                    }
                } else {
                    val selected = entry.index in row.selectedIndexes
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                            ) {
                                row.onSelect(entry.index)
                                onDismiss()
                            }
                            .padding(
                                horizontal = DesignTokens.spacingLg,
                                vertical = DesignTokens.spacingDefault,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppRadioButton(selected = selected, onClick = null)
                        Text(
                            text = label,
                            color = colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 鼠标右键 (次要键) 触发: 全端同一实现, 触屏设备收不到次要键事件, 行为不变。
 * 不消费事件: 行内没有别的右键处理方。
 */
@Composable
private fun rememberSecondaryClickModifier(onClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onClick)
    return Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                if (event.buttons.isSecondaryPressed) current()
            }
        }
    }
}
