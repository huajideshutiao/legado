package io.legado.app.ui.compose.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 触发拖选的左侧手柄宽度：对齐 View 版 setSlideArea(16,50) 的复选框列 */
private val DragSelectHandleWidth = 56.dp

/** 边缘自动滚动热区占列表高度比例，对齐 DragSelectTouchHelper 默认 hotspot 0.2 */
private const val EdgeRatio = 0.2f

/** 边缘自动滚动最大速度(px/帧) */
private val MaxScrollVelocity = 20.dp

/**
 * 复刻 DragSelectTouchHelper 的边缘滑动批量勾选(Mode.ToggleAndReverse)，作为共享手势基建。
 * 与长按拖拽排序按起手位置区分：手势起点落在左侧 [handleWidth] 手柄列(复选框列)才进入拖选，
 * 列表其余区域的长按仍由 reorderable 的 longPressDraggableHandle 处理排序，二者互不抢占。
 *
 * 语义严格对齐 ToggleAndReverse：锚点(起手 item)即时翻转为 !原状态，扫过的连续区间同步该翻转态，
 * 手指回退移出区间的 item 复位为锚点原状态。支持顶/底热区自动滚动并随滚动继续勾选。
 *
 * KMP 共享: 从 app/ui/compose/component 下沉到 shared/commonMain, app + desktop 直接复用。
 *
 * @param listState        与列表共用的 LazyListState，用于坐标→item index 命中与自动滚动
 * @param autoScrollScope  驱动自动滚动的协程作用域(用 rememberCoroutineScope)
 * @param isSelected       查询某 index 当前是否已选(host 据 items[index] 主键查 Set)
 * @param onSelectedChanged 设置某 index 选中态(host 据 items[index] 主键写 Set)
 * @param handleWidth      触发拖选的左侧手柄列宽度
 */
fun Modifier.dragSelectable(
    listState: LazyListState,
    autoScrollScope: CoroutineScope,
    isSelected: (index: Int) -> Boolean,
    onSelectedChanged: (index: Int, selected: Boolean) -> Unit,
    handleWidth: Dp = DragSelectHandleWidth,
): Modifier = this.pointerInput(listState) {
    val handlePx = handleWidth.toPx()
    val maxVelocity = MaxScrollVelocity.toPx()
    val viewHeight = size.height.toFloat()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 起点不在左侧手柄列则放行：交给复选框点按/长按拖拽排序
        if (down.position.x > handlePx) return@awaitEachGesture
        val startIndex = listState.itemIndexAtY(down.position.y) ?: return@awaitEachGesture

        var activated = false
        var anchor = startIndex
        var firstSelected = false
        var lastStart = startIndex
        var lastEnd = startIndex
        var pointerY = down.position.y
        var scrollVelocity = 0f
        var scrollJob: Job? = null

        // 区间增量刷新，等价 DragSelectTouchHelper.notifySelectRangeChange + ToggleAndReverse
        fun applyRange(current: Int) {
            val newStart = min(anchor, current)
            val newEnd = max(anchor, current)
            if (newStart < lastStart) {
                for (i in newStart until lastStart) onSelectedChanged(i, !firstSelected)
            } else if (newStart > lastStart) {
                for (i in lastStart until newStart) onSelectedChanged(i, firstSelected)
            }
            if (newEnd > lastEnd) {
                for (i in lastEnd + 1..newEnd) onSelectedChanged(i, !firstSelected)
            } else if (newEnd < lastEnd) {
                for (i in newEnd + 1..lastEnd) onSelectedChanged(i, firstSelected)
            }
            lastStart = newStart
            lastEnd = newEnd
        }

        fun updateFromPointer() {
            listState.itemIndexAtY(pointerY)?.let { applyRange(it) }
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (activated) change.consume()
                break
            }
            pointerY = change.position.y
            if (!activated) {
                if (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                    activated = true
                    firstSelected = isSelected(anchor)
                    onSelectedChanged(anchor, !firstSelected)
                    change.consume()
                    scrollJob = autoScrollScope.launch {
                        while (isActive) {
                            if (scrollVelocity != 0f) {
                                listState.scrollBy(scrollVelocity)
                                updateFromPointer()
                            }
                            withFrameNanos { }
                        }
                    }
                }
            } else {
                change.consume()
                updateFromPointer()
                val topZone = viewHeight * EdgeRatio
                val bottomZone = viewHeight * (1 - EdgeRatio)
                scrollVelocity = when {
                    pointerY < topZone ->
                        -maxVelocity * ((topZone - pointerY) / topZone).coerceIn(0f, 1f)
                    pointerY > bottomZone ->
                        maxVelocity * ((pointerY - bottomZone) / (viewHeight - bottomZone)).coerceIn(0f, 1f)
                    else -> 0f
                }
            }
        }
        scrollJob?.cancel()
    }
}

/** 命中当前可见区内包含纵坐标 y 的 item index，坐标系与 pointerInput 所在 LazyColumn 一致 */
private fun LazyListState.itemIndexAtY(y: Float): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull { item ->
        y >= item.offset && y < item.offset + item.size
    }?.index
}
