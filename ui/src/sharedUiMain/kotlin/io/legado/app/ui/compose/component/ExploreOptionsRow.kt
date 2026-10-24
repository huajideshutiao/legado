package io.legado.app.ui.compose.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.model.webBook.ExploreOption

/**
 * 参数筛选行 (下沉 app 端 `ExploreOptionView.setUpExploreOptions`)。
 *
 * 主页展示项 (HomeSectionAdapter.updateOptions)、发现结果页 (ExploreShowActivity) 与搜索页单源
 * 搜索选项共用同一视图, 故三处都消费本组件。全部选项一次交给 [AppMixedChoiceRowGroup],
 * 相邻同类聚段由组件内单点裁决:
 * - 单选段: 选项数 > 4 出横向滚动 chip 行, 否则下拉按可用宽度并排 (放不下换行);
 *   标题可点重置默认 (对齐原版点标题 chip 重置), 菜单热区占标题以外整行。
 * - 多选段: 只列已选标签, 点按整行打开带搜索的选择弹窗 (对齐原版), 确认时一次写回
 *   并只重搜一次。
 * - 长按整行 / 整行鼠标右键在任何行上都能打开同一个可搜索选择弹窗。
 *
 * [ExploreOption] 的 selectedValue / selectedValues 是普通可变字段, 不被 Compose 跟踪;
 * 点击后 bump 本地 revision 重取快照刷新选中态。
 *
 * @param optionsVersion 结构变化信号 (新解析出 options 时 bump)
 */
@Composable
fun ExploreOptionsRow(
    options: List<ExploreOption>,
    optionsVersion: Int,
    onOptionSelected: () -> Unit,
) {
    if (options.isEmpty()) return
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") optionsVersion
    val onChanged = {
        revision++
        onOptionSelected()
    }
    @Suppress("UNUSED_EXPRESSION") revision
    AppMixedChoiceRowGroup(
        fields = options.map { option ->
            if (option.multiSelect) option.toMultiChoiceField(onChanged)
            else option.toChoiceField(onChanged)
        }
    )
}

private fun ExploreOption.toChoiceField(onChanged: () -> Unit) = AppChoiceField(
    title = name,
    options = options.map { it.first },
    selectedIndex = options.indexOfFirst { it.second == selectedValue },
    onSelect = { index ->
        val value = options[index].second
        if (selectedValue != value) {
            selectedValue = value
            onChanged()
        }
    },
    onReset = {
        if (resetToDefault()) onChanged()
    },
)

private fun ExploreOption.toMultiChoiceField(onChanged: () -> Unit) = AppMultiChoiceField(
    title = name,
    options = options.map { it.first },
    selectedIndexes = options.indices.filter { options[it].second in selectedValues }.toSet(),
    // 弹窗确认时整份写回, 只触发一次重搜 (逐项回调会让每个勾选都发一轮请求)
    onSelectionChange = { indexes ->
        val values = indexes.mapTo(mutableSetOf()) { options[it].second }
        if (values != selectedValues) {
            selectedValues.clear()
            selectedValues.addAll(values)
            onChanged()
        }
    },
)
