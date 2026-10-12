package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.empty
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * 网格条目: 一条数据项, 或一条占满整行的分组标题。
 * [Entry.Row.dataIndex] 是该项在原始数据里的下标, 用于把网格下标还原成数据下标 (拖拽/拖选用)。
 */
private sealed interface Entry<out T> {
    data class Header(val text: String) : Entry<Nothing>
    data class Row<T>(val item: T, val dataIndex: Int) : Entry<T>
}

/** 按 [headerOf] 把数据列表展开为网格条目表 (标题占一个网格条目, 不占数据下标)。 */
private fun <T> buildEntries(items: List<T>, headerOf: ((T) -> String)?): List<Entry<T>> =
    buildList {
        items.forEachIndexed { index, item ->
            val header = headerOf?.invoke(item).orEmpty()
            if (header.isNotEmpty()) add(Entry.Header(header))
            add(Entry.Row(item, index))
        }
    }

/**
 * 网格下标 → 数据下标映射 (与 [RuleManageScaffold] 的条目表同源)。
 * 分组标题占一个网格下标但不占数据下标, 该位置返回 -1; 越界返回 null。
 * 供带分组标题的调用方把网格回调 (拖选/滚动定位) 还原到数据下标。
 */
fun <T> manageGridIndexMap(items: List<T>, headerOf: ((T) -> String)? = null): IntArray =
    buildEntries(items, headerOf)
        .map { if (it is Entry.Row) it.dataIndex else GridIndexHeader }
        .toIntArray()

/** [manageGridIndexMap] 中分组标题占位的网格下标取值 (无对应数据项)。 */
const val GridIndexHeader: Int = -1

/**
 * 「搜索 + 列表 + 多选 + 拖拽排序 + 分组筛选 + 底部批量操作栏」规则管理界面共享骨架(plan P2)。
 * 无状态槽位式：数据/选中/查询状态由调用方(DialogFragment/Activity 壳层)持有，本骨架只组织布局与交互。
 *
 * 列表按容器宽度自动分列 (与书架/阅读记录/目录页同一口径): 容器宽度决定列数, 更宽按比例加列, 无上限。
 * 窄屏 (手机竖屏) 只有一列, 表现与分列前一致。
 *
 * @param titleBar       顶部区域槽(标题栏/搜索框)，Dialog 型传 DialogTitleBar，Activity 型可传空由 View TitleBar 承载
 * @param items          列表数据，需带稳定 key
 * @param itemKey        item 唯一 key，用于网格复用与拖拽定位
 * @param onMove         拖拽换位回调(from,to)，入参为 [items] 的数据下标 (分组标题不占下标)；
 * 调用方据此交换数据并即时更新 state，松手落库另由 item 内把手回调驱动
 * @param actionBar      底部批量操作栏槽(通常放 SelectActionBar)，为空则不显示(如纯拖拽的 GroupManage)
 * @param emptyText      列表为空时的占位文案
 * @param listModifier   施加于网格的 modifier，供 Activity 型接入 dragSelectable 边缘拖选
 * @param fillMaxHeight  列表区是否吃满可用高度；非全高 Dialog 传 false 以让内容自适应收缩(复刻 AutoShrinkLinearLayout)
 * @param wrapContentHeight true 时网格高度自适应内容 (透传 [FastScrollLazyVerticalGrid], 需配合
 * fillMaxHeight=false 使用): 项少时随内容收缩, 多时由外部 heightIn 封顶并可滚动;
 * false (默认) 时网格仍 fillMaxSize 吃满, 行为与既有调用方完全一致
 * @param listState      外部传入的网格状态, 供 dragSelectable 边缘拖选复用
 * @param headerOf       返回非空串时该项上方插入占满整行的分组标题 (书源按域名分组用),
 * 返回空串为普通项; 标题不参与选中与拖拽。需传稳定引用 (调用方 remember), 否则每次重组重建条目表
 * @param itemContent    单项内容槽，携带 ReorderableCollectionItemScope 以便 item 内部用 longPressDraggableHandle 绑定把手
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
    listState: LazyGridState = rememberLazyGridState(),
    listModifier: Modifier = Modifier,
    fillMaxHeight: Boolean = true,
    wrapContentHeight: Boolean = false,
    /** 底部内容回避 padding: 无底栏的全屏独立页传 rememberNavigationBarPaddingValues() (Android 15+ 强制
     * edge-to-edge 时列表末尾不被导航栏遮挡); Dialog 型与有 SelectActionBar 的使用方保持默认 0
     * (底栏自身已避让手势条, 再叠一层就是双重避让) */
    bottomPadding: PaddingValues = PaddingValues(0.dp),
    headerOf: ((T) -> String)? = null,
    itemContent: @Composable ReorderableCollectionItemScope.(item: T) -> Unit,
) {
    // 网格条目 = 可选的分组标题 + 数据项; 标题占满整行, 数据项按列排布
    // headerOf 为 null (多数调用方) 时 entries 与 items 一一对应, 且 remember 只随 items 失效
    val entries = remember(items, headerOf) { buildEntries(items, headerOf) }
    val reorderState = rememberReorderableLazyGridState(listState) { from, to ->
        // 库回调给的是条目信息 (取 .index 还原网格下标); 任一端是分组标题
        // (分组态本就不允许拖拽) 时忽略
        val fromEntry = entries.getOrNull(from.index)
        val toEntry = entries.getOrNull(to.index)
        if (fromEntry is Entry.Row && toEntry is Entry.Row) {
            onMove(fromEntry.dataIndex, toEntry.dataIndex)
        }
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
                FastScrollLazyVerticalGrid(
                    columns = rememberResponsiveColumns(1),
                    state = listState,
                    modifier = fillMod.then(listModifier),
                    wrapContentHeight = wrapContentHeight,
                ) {
                    items(
                        items = entries,
                        key = { entry ->
                            when (entry) {
                                is Entry.Header -> "header:${entry.text}"
                                is Entry.Row -> itemKey(entry.item)
                            }
                        },
                        span = { entry ->
                            // 分组标题跨全部列 (占满整行, 不被分列切开)
                            if (entry is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                        contentType = { entry ->
                            if (entry is Entry.Header) "header" else "item"
                        },
                    ) { entry ->
                        when (entry) {
                            is Entry.Header -> Text(
                                text = entry.text,
                                color = AppTheme.colors.accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = DesignTokens.spacingDefault,
                                        vertical = DesignTokens.spacingXs,
                                    ),
                            )

                            is Entry.Row -> ReorderableItem(
                                state = reorderState,
                                key = itemKey(entry.item),
                            ) {
                                itemContent(entry.item)
                            }
                        }
                    }
                }
            }
        }
        actionBar()
    }
}
