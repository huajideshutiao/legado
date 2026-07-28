package io.legado.app.ui.compose.reorderable

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ohos 端无 reorderable 库 (未发布 ohosArm64 变体), 用 Compose 原生手势 API 自实现拖拽排序
// 参考DragSelect.kt 已验证的 detectDragGesturesAfterLongPress + scrollBy + layoutInfo.visibleItemsInfo 模式

actual interface RuleItemScope {
    actual fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        onDragStopped: () -> Unit,
    ): Modifier
}

actual class ReorderableListState internal constructor(
    internal val listState: LazyListState,
    internal val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val maxScrollPx: Float,
) {
    // 当前被拖拽 item 的 key, null 表示未拖拽
    var draggedKey: Any? by mutableStateOf(null)
        internal set
    // 拖拽累计纵向偏移(px), 相对被拖拽 item 在列表中的原始位置
    var draggedOffsetY: Float by mutableStateOf(0f)
        internal set

    internal fun startDrag(key: Any) {
        draggedKey = key
        draggedOffsetY = 0f
    }

    internal fun updateDrag(deltaY: Float) {
        draggedOffsetY += deltaY
    }

    internal fun endDrag() {
        draggedKey = null
        draggedOffsetY = 0f
    }

    // 按 draggedOffsetY 反查命中目标, 越过中点则触发相邻交换, 补偿偏移保持视觉连续
    // 单次只交换相邻: 跨多 item 拖拽由 onDrag 即时调用 + startDragLoop 每帧兜底共同推进
    internal fun tryMove(draggedKey: Any) {
        val layoutInfo = listState.layoutInfo
        val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey } ?: return
        val draggedIndex = draggedInfo.index
        // 被拖拽 item 视觉中心在 viewport 中的 Y
        val draggedCenter = draggedInfo.offset + draggedInfo.size / 2 + draggedOffsetY
        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull {
            draggedCenter >= it.offset && draggedCenter < it.offset + it.size
        } ?: return
        val targetIndex = targetInfo.index
        if (targetIndex == draggedIndex) return
        val neighborIndex = if (targetIndex > draggedIndex) draggedIndex + 1 else draggedIndex - 1
        val neighborInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == neighborIndex } ?: return
        val oldOffset = draggedInfo.offset
        onMove(draggedIndex, neighborIndex)
        // 交换后 dragged item 移到 neighbor 位置, 新 offset ≈ neighborInfo.offset
        // 补偿 draggedOffsetY 使视觉位置不变 (下一帧 layoutInfo 更新后修正)
        draggedOffsetY -= (neighborInfo.offset - oldOffset)
    }

    // 拖拽期间持续运行的协程: 每帧兜底推进交换 + 边缘自动滚动
    // 兜底必要性: 手指停住时 onDrag 不再触发, 但 draggedCenter 可能跨越多格, 需每帧继续推进
    internal fun startDragLoop(scope: CoroutineScope, draggedKey: Any): Job = scope.launch {
        while (isActive && this@ReorderableListState.draggedKey == draggedKey) {
            tryMove(draggedKey)
            val layoutInfo = listState.layoutInfo
            val viewportHeight = layoutInfo.viewportSize.height.toFloat()
            val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedKey }
            if (viewportHeight > 0f && draggedInfo != null) {
                val visualCenter = draggedInfo.offset + draggedInfo.size / 2 + draggedOffsetY
                val topZone = viewportHeight * EdgeRatio
                val bottomZone = viewportHeight * (1 - EdgeRatio)
                val velocity = when {
                    visualCenter < topZone ->
                        -maxScrollPx * ((topZone - visualCenter) / topZone).coerceIn(0f, 1f)

                    visualCenter > bottomZone ->
                        maxScrollPx * ((visualCenter - bottomZone) / (viewportHeight - bottomZone)).coerceIn(0f, 1f)

                    else -> 0f
                }
                if (velocity != 0f) {
                    listState.scrollBy(velocity)
                    // scrollBy(delta) 使所有可见 item offset 减少 delta, draggedOffsetY 增 delta 保持视觉位置
                    draggedOffsetY += velocity
                }
            }
            withFrameNanos { }
        }
    }
}

@Composable
actual fun rememberReorderableListState(
    listState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): ReorderableListState {
    val maxScrollPx = with(LocalDensity.current) { MaxScrollVelocity.toPx() }
    return remember(listState, onMove, maxScrollPx) {
        ReorderableListState(listState, onMove, maxScrollPx)
    }
}

// 桥接 RuleItemScope: 持有 state + item key + 协程 scope, 供 longPressDraggableHandle 启动手势与拖拽循环
private class RuleItemScopeImpl(
    private val state: ReorderableListState,
    private val key: Any,
    private val coroutineScope: CoroutineScope,
) : RuleItemScope {
    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        onDragStopped: () -> Unit,
    ): Modifier {
        if (!enabled) return this
        return this.pointerInput(key) {
            var dragLoopJob: Job? = null
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    state.startDrag(key)
                    dragLoopJob?.cancel()
                    dragLoopJob = state.startDragLoop(coroutineScope, key)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.updateDrag(dragAmount.y)
                    state.tryMove(key)
                },
                onDragEnd = {
                    dragLoopJob?.cancel()
                    dragLoopJob = null
                    state.endDrag()
                    onDragStopped()
                },
                onDragCancel = {
                    dragLoopJob?.cancel()
                    dragLoopJob = null
                    state.endDrag()
                },
            )
        }
    }
}

@Composable
actual fun LazyItemScope.RuleReorderableItem(
    state: ReorderableListState,
    key: Any,
    content: @Composable RuleItemScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ruleScope = remember(state, key) { RuleItemScopeImpl(state, key, scope) }
    val isDragging = state.draggedKey == key
    // 拖拽中: 抬升层级 + 偏移跟随手指; 非拖拽: animateItem 提供换位动画
    val modifier = if (isDragging) {
        Modifier
            .zIndex(1f)
            .offset { IntOffset(0, state.draggedOffsetY.roundToInt()) }
    } else {
        Modifier.animateItem()
    }
    Box(modifier) {
        ruleScope.content()
    }
}

// 边缘热区占 viewport 高度比例, 对齐 DragSelect.kt 的 EdgeRatio (DragSelectTouchHelper hotspot 0.2)
private const val EdgeRatio = 0.2f

// 边缘自动滚动最大速度, 对齐 DragSelect.kt 的 MaxScrollVelocity
private val MaxScrollVelocity = 20.dp
