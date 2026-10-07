package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.model.webBook.ExploreOption

/**
 * 参数筛选行 (下沉 app 端 `ExploreOptionView.setUpExploreOptions`)。
 *
 * 主页展示项 (HomeSectionAdapter.updateOptions)、发现结果页 (ExploreShowActivity) 与搜索页单源
 * 搜索选项共用同一视图, 故三处都消费本组件。每个 [ExploreOption] 一行, 选项交给
 * [AppChoiceRowGroup] / [AppMultiChoiceRowGroup] 统一裁决 (选项数 > 4 出横向滚动 chip 行, 否则
 * 下拉并排); 单选行的标题可点重置默认 (对齐原版点标题 chip 重置), 菜单热区占标题以外整行,
 * 多选行点 chip / 下拉项即切。任意变化调 [onOptionSelected]。
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
    Column(Modifier.fillMaxWidth()) {
        options.forEach { option ->
            key(option.name) {
                @Suppress("UNUSED_EXPRESSION") revision
                val onChanged = {
                    revision++
                    onOptionSelected()
                }
                if (option.multiSelect) {
                    AppMultiChoiceRowGroup(
                        fields = listOf(option.toMultiChoiceField(onChanged)),
                    )
                } else {
                    AppChoiceRowGroup(
                        fields = listOf(option.toChoiceField(onChanged)),
                    )
                }
            }
        }
    }
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
    onToggle = { index ->
        val value = options[index].second
        if (value in selectedValues) {
            selectedValues.remove(value)
        } else {
            selectedValues.add(value)
        }
        onChanged()
    },
)
