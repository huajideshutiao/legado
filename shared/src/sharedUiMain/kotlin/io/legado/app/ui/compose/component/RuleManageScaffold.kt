package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.reorderable.RuleReorderableItem
import io.legado.app.ui.compose.reorderable.rememberReorderableListState
import io.legado.app.ui.compose.theme.AppTheme

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
    emptyText: String = rememberString("empty"),
    listState: LazyListState = rememberLazyListState(),
    listModifier: Modifier = Modifier,
    fillMaxHeight: Boolean = true,
    itemContent: @Composable RuleItemScope.(item: T) -> Unit,
) {
    val reorderState = rememberReorderableListState(listState) { from, to ->
        onMove(from, to)
    }
    val fillMod = if (fillMaxHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    Column(modifier.then(fillMod)) {
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
