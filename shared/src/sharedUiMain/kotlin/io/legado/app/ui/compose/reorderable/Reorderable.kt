package io.legado.app.ui.compose.reorderable

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 规则项作用域: 抽离 sh.calvin.reorderable.ReorderableCollectionItemScope,
// ohos 端库未发布 ohosArm64 变体, actual 用 Compose 原生手势 API 自实现拖拽排序
// 简化为 enabled + onDragStopped 两个参数 (调用方实际只使用这两个, 库的 interactionSource/onDragStarted 未使用)
expect interface RuleItemScope {
    // 长按拖拽把手: 成员扩展以保持 Modifier.longPressDraggableHandle(...) 链式调用语法
    fun Modifier.longPressDraggableHandle(
        enabled: Boolean = true,
        onDragStopped: () -> Unit = {},
    ): Modifier
}

expect class ReorderableListState

@Composable
expect fun rememberReorderableListState(
    listState: LazyListState,
    vertical: Boolean = true,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): ReorderableListState

// LazyItemScope 接收者: 库的 ReorderableItem 依赖 LazyItemScope.animateItem() 计算默认动画
@Composable
expect fun LazyItemScope.RuleReorderableItem(
    state: ReorderableListState,
    key: Any,
    content: @Composable RuleItemScope.() -> Unit,
)
