package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.reorderable.RuleReorderableItem
import io.legado.app.ui.compose.reorderable.rememberReorderableListState
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.preview.LegadoThemePreview
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.empty
import org.jetbrains.compose.resources.stringResource

/**
 * 「搜索 + 列表 + 多选 + 拖拽排序 + 分组筛选 + 底部批量操作栏」规则管理界面共享骨架(plan P2)。
 * 无状态槽位式：数据/选中/查询状态由调用方(DialogFragment/Activity 壳层)持有，本骨架只组织布局与交互。
 *
 * @param titleBar       顶部区域槽(标题栏/搜索框)，Dialog 型传 DialogTitleBar，Activity 型可传空由 View TitleBar 承载
 * @param items          列表数据，需带稳定 key
 * @param itemKey        item 唯一 key，用于 LazyColumn 复用与拖拽定位
 * @param onMove         拖拽换位回调(from,to)，调用方据此交换数据并即时更新 state；松手落库另由 item 内把手回调驱动
 * @param actionBar      底部批量操作栏槽(通常放 SelectActionBar)，为空则不显示(如纯拖拽的 GroupManage)
 * @param emptyText      列表为空时的占位文案
 * @param listModifier   施加于 LazyColumn 的 modifier，供 Activity 型接入 dragSelectable 边缘拖选
 * @param fillMaxHeight  列表区是否吃满可用高度；非全高 Dialog 传 false 以让内容自适应收缩(复刻 AutoShrinkLinearLayout)
 * @param wrapContentHeight true 时列表 LazyColumn 高度自适应内容 (透传 [FastScrollLazyColumn], 需配合
 * fillMaxHeight=false 使用): 项少时随内容收缩, 多时由外部 heightIn 封顶并可滚动;
 * false (默认) 时列表仍 fillMaxSize 吃满, 行为与既有调用方完全一致
 * @param itemContent    单项内容槽，携带 RuleItemScope 以便 item 内部用 draggableHandle 绑定把手
 */
@Composable
fun <T> RuleManageScaffold(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    titleBar: @Composable () -> Unit = {},
    actionBar: @Composable () -> Unit = {},
    emptyText: String = stringResource(Res.string.empty),
    listState: LazyListState = rememberLazyListState(),
    listModifier: Modifier = Modifier,
    fillMaxHeight: Boolean = true,
    wrapContentHeight: Boolean = false,
    /** 底部内容回避 padding: 全屏独立页/全高对话框传 rememberNavigationBarPaddingValues() (Android 15+ 强制
     * edge-to-edge 时列表末尾不被导航栏遮挡); Dialog 型/有底栏兑底的使用方保持默认 0 不受影响 */
    bottomPadding: PaddingValues = PaddingValues(0.dp),
    itemContent: @Composable RuleItemScope.(item: T) -> Unit,
) {
    val reorderState = rememberReorderableListState(listState) { from, to ->
        onMove(from, to)
    }
    val fillMod = if (fillMaxHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    Column(modifier.then(fillMod).padding(bottom = bottomPadding.calculateBottomPadding())) {
        titleBar()
        Box(Modifier.fillMaxWidth().weight(1f, fill = fillMaxHeight)) {
            if (items.isEmpty()) {
                Text(
                    text = emptyText,
                    color = AppTheme.colors.secondaryText,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                FastScrollLazyColumn(
                    state = listState,
                    modifier = fillMod.then(listModifier),
                    wrapContentHeight = wrapContentHeight,
                ) {
                    items(items, key = itemKey) { item ->
                        RuleReorderableItem(reorderState, key = itemKey(item)) {
                            itemContent(item)
                        }
                    }
                }
            }
        }
        actionBar()
    }
}

// ===== @Preview 合并自 androidMain 的 compose/component/RuleManageScaffoldPreviews.kt =====

/**
 * [RuleManageScaffold.kt] 中 [RuleManageScaffold] 的 @Preview。
 *
 * RuleManageScaffold 是「搜索 + 列表 + 多选 + 拖拽排序 + 底部批量操作栏」共享骨架,
 * Preview 中可预览整体布局结构; 拖拽排序交互受限。
 */

@Preview
@Composable
fun RuleManageScaffoldPreview() = LegadoThemePreview {
    var items by remember { mutableStateOf(listOf("规则一", "规则二", "规则三")) }
    RuleManageScaffold(
        items = items,
        itemKey = { it },
        onMove = { from, to ->
            items = items.toMutableList().apply { add(to, removeAt(from)) }
        },
        modifier = Modifier.fillMaxWidth(),
        titleBar = {
            AppTitleBar(title = "规则管理", onBack = {})
        },
        actionBar = {
            SelectActionBar(
                selectCount = 0,
                allCount = items.size,
                onSelectAll = {},
                onRevertSelection = {},
                mainActionText = "删除",
                onMainAction = {},
                modifier = Modifier.fillMaxWidth(),
            )
        },
        emptyText = "暂无规则",
        itemContent = { item ->
            Text(
                text = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        },
    )
}

@Preview
@Composable
fun RuleManageScaffoldEmptyPreview() = LegadoThemePreview {
    RuleManageScaffold(
        items = emptyList<String>(),
        itemKey = { it },
        onMove = { _, _ -> },
        modifier = Modifier.fillMaxWidth(),
        titleBar = {
            AppTitleBar(title = "空列表", onBack = {})
        },
        emptyText = "暂无数据",
        fillMaxHeight = false,
        itemContent = {},
    )
}
