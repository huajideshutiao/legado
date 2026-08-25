package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.clear
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.search
import org.jetbrains.compose.resources.stringResource

// chip 透明度: 对照 app 端 ExploreOptionView ALPHA_TITLE / ALPHA_SELECTED / ALPHA_UNSELECTED
private const val OPTION_ALPHA_TITLE = 0.8f
private const val OPTION_ALPHA_SELECTED = 1.0f
private const val OPTION_ALPHA_UNSELECTED = 0.5f

/** 单行渲染快照: live [ExploreOption] (供点击就地改) + 选中态快照 (供 alpha 渲染)。 */
private data class OptionRowSnapshot(
    val option: ExploreOption,
    val selectedValues: Set<String>,
    val selectedValue: String,
)

/**
 * 发现参数 chip 行 (下沉 app 端 `ExploreOptionView.setUpExploreOptions`)。
 *
 * 主页展示项 (HomeSectionAdapter.updateOptions) 与发现结果页 (ExploreShowActivity) 共用同一
 * 视图, 故两处都消费本组件。每个 [ExploreOption] 一行 (标题 chip + 选项 chip, 横向滚动):
 * 单选点 chip 即切, 点标题 chip 重置默认; 多选只显示已选 chip, 整行点击开
 * [MultiSelectOptionDialog] (带搜索, 确定才写回)。任意变化调 [onOptionSelected]。
 *
 * [ExploreOption] 的 selectedValue / selectedValues 是普通可变字段, 不被 Compose 跟踪;
 * 点击后 bump 本地 revision 重取快照刷新 alpha。
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
    var dialogOptionName by remember { mutableStateOf<String?>(null) }
    val rows = remember(options, optionsVersion, revision) {
        options.map { OptionRowSnapshot(it, it.selectedValues.toSet(), it.selectedValue) }
    }
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (option, selectedValues, selectedValue) ->
            key(option.name) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        // 多选整行可点 (对照 bindMultiSelect 的 row.setOnClickListener, 扩大点击区)
                        .then(
                            if (option.multiSelect) {
                                Modifier.clickable { dialogOptionName = option.name }
                            } else {
                                Modifier
                            }
                        )
                        // space.default × space.xs
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (option.multiSelect) {
                        // 标题 chip 与已选 chip 行为一致 (都开对话框), 对照 addTitleChip(onClick = null)
                        AppFilletTextButton(
                            text = option.name,
                            modifier = Modifier.alpha(OPTION_ALPHA_TITLE),
                            onClick = { dialogOptionName = option.name },
                        )
                        option.options.forEach { (label, value) ->
                            if (value in selectedValues) {
                                AppFilletTextButton(
                                    text = label,
                                    onClick = { dialogOptionName = option.name },
                                )
                            }
                        }
                    } else {
                        AppFilletTextButton(
                            text = option.name,
                            modifier = Modifier.alpha(OPTION_ALPHA_TITLE),
                            onClick = {
                                if (option.resetToDefault()) {
                                    revision++
                                    onOptionSelected()
                                }
                            },
                        )
                        option.options.forEach { (label, value) ->
                            AppFilletTextButton(
                                text = label,
                                modifier = Modifier.alpha(
                                    if (selectedValue == value) {
                                        OPTION_ALPHA_SELECTED
                                    } else {
                                        OPTION_ALPHA_UNSELECTED
                                    }
                                ),
                                onClick = {
                                    if (option.selectedValue != value) {
                                        option.selectedValue = value
                                        revision++
                                        onOptionSelected()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    options.firstOrNull { it.multiSelect && it.name == dialogOptionName }?.let { option ->
        MultiSelectOptionDialog(
            option = option,
            onDismiss = { dialogOptionName = null },
            onChanged = {
                revision++
                onOptionSelected()
            },
        )
    }
}

/**
 * 多选参数对话框 (对照 `ExploreOptionView.showMultiSelectDialog`): 搜索框 + tag chip 网格 +
 * 确定/清除/取消。working 是副本, 确定才写回; 清除直接清空真实选择并立即回调。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectOptionDialog(
    option: ExploreOption,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    var query by remember(option) { mutableStateOf("") }
    var working by remember(option) { mutableStateOf(option.selectedValues.toSet()) }
    val visibleOptions = remember(option, query) {
        val filter = query.trim()
        if (filter.isEmpty()) {
            option.options
        } else {
            option.options.filter { (label, value) ->
                label.contains(filter, ignoreCase = true) ||
                    value.contains(filter, ignoreCase = true)
            }
        }
    }
    // 固定半屏高 (对照原 displayMetrics.heightPixels / 2)
    val containerHeight = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    val listHeight = remember(containerHeight, density) {
        with(density) { (containerHeight / 2).toDp() }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = option.name,
        neutralButton = AlertButton(stringResource(Res.string.clear)) {
            if (option.selectedValues.isNotEmpty()) {
                option.selectedValues.clear()
                onChanged()
            }
        },
        cancelButton = AlertButton(stringResource(Res.string.cancel)),
        okButton = AlertButton(stringResource(Res.string.ok)) {
            if (working != option.selectedValues) {
                option.selectedValues.clear()
                option.selectedValues.addAll(working)
                onChanged()
            }
        },
    ) {
        AppSearchField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(Res.string.search),
            modifier = Modifier.padding(
                start = DesignTokens.spacingDefault,
                top = 8.dp,
                bottom = 4.dp
            ),
        )
        FlowRow(
            Modifier
                .height(listHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.spacingDefault, vertical = 4.dp),
        ) {
            visibleOptions.forEach { (label, value) ->
                val selected = value in working
                AppFilletTextButton(
                    text = label,
                    modifier = Modifier.alpha(
                        if (selected) OPTION_ALPHA_SELECTED else OPTION_ALPHA_UNSELECTED
                    ),
                    onClick = { working = if (selected) working - value else working + value },
                )
            }
        }
    }
}
