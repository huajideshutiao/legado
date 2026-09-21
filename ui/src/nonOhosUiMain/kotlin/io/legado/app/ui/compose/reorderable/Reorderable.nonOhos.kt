package io.legado.app.ui.compose.reorderable

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

// 不用 typealias: 库接口的 longPressDraggableHandle 带默认参数值, 与 expect 冲突
// (KMP 规则: actual 函数不能有默认参数值, 应由 expect 声明)
// 改为手写 actual interface + 桥接类, 在桥接类中委托库实现
actual interface RuleItemScope {
    actual fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        onDragStopped: () -> Unit,
    ): Modifier
}

// 桥接类: 持有库的 ReorderableCollectionItemScope, 覆盖 longPressDraggableHandle 调用库实现
private class RuleItemScopeBridge(
    private val delegate: ReorderableCollectionItemScope,
) : RuleItemScope {
    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        onDragStopped: () -> Unit,
    ): Modifier = with(delegate) {
        // 此作用域内 this=delegate(ReorderableCollectionItemScope), 调用库的成员扩展函数
        this@longPressDraggableHandle.longPressDraggableHandle(
            enabled = enabled,
            onDragStopped = onDragStopped,
        )
    }
}

actual typealias ReorderableListState = ReorderableLazyListState

@Composable
actual fun rememberReorderableListState(
    listState: LazyListState,
    vertical: Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): ReorderableListState = rememberReorderableLazyListState(listState) { from, to ->
    // 库 onMove 入参为 LazyListItemInfo, 此处取 .index 转换为 Int
    // 方向参数由库自动检测, 此处忽略 (ohos 手写实现才需要显式方向)
    onMove(from.index, to.index)
}

@Composable
actual fun LazyItemScope.RuleReorderableItem(
    state: ReorderableListState,
    key: Any,
    content: @Composable RuleItemScope.() -> Unit,
) = ReorderableItem(
    state = state,
    key = key,
) {
    // 库 content 带 isDragging: Boolean 参数, 此处忽略
    // this 是 ReorderableCollectionItemScope, 包装为 RuleItemScopeBridge 供 content 使用
    RuleItemScopeBridge(this).content()
}
