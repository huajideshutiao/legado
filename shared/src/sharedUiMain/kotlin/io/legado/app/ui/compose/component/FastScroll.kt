package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FastScrollTouchWidth = 24.dp
private val FastScrollThumbWidth = 5.dp
private val FastScrollMinThumbHeight = 48.dp

private data class FastScrollMetrics(
    val itemCount: Int,
    val visibleItemCount: Int,
    val positionFraction: Float,
)

/**
 * 带快速滚动条的 LazyColumn。
 *
 * 迁移前的界面普遍使用 FastScrollRecyclerView；这个共享实现把同等的拖动跳转能力
 * 补回 Compose/KMP，并在内容不足一屏时自动隐藏。
 */
@Composable
fun FastScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        LazyListFastScrollbar(state)
    }
}

/** 带快速滚动条的 LazyVerticalGrid。 */
@Composable
fun FastScrollLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        LazyGridFastScrollbar(state)
    }
}

@Composable
private fun BoxScope.LazyListFastScrollbar(state: LazyListState) {
    val metrics by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            val itemCount = layout.totalItemsCount
            val visibleCount = visible.size
            val first = visible.firstOrNull()
            val itemProgress = if (first == null || first.size <= 0) {
                0f
            } else {
                (-first.offset).toFloat() / first.size
            }
            val maxFirstIndex = (itemCount - visibleCount).coerceAtLeast(1)
            FastScrollMetrics(
                itemCount = itemCount,
                visibleItemCount = visibleCount,
                positionFraction = ((state.firstVisibleItemIndex + itemProgress) / maxFirstIndex)
                    .coerceIn(0f, 1f),
            )
        }
    }
    FastScrollbar(metrics) { index -> state.scrollToItem(index) }
}

@Composable
private fun BoxScope.LazyGridFastScrollbar(state: LazyGridState) {
    val metrics by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            val itemCount = layout.totalItemsCount
            val visibleCount = visible.size
            val first = visible.firstOrNull()
            val itemProgress = if (first == null || first.size.height <= 0) {
                0f
            } else {
                (-first.offset.y).toFloat() / first.size.height
            }
            val maxFirstIndex = (itemCount - visibleCount).coerceAtLeast(1)
            FastScrollMetrics(
                itemCount = itemCount,
                visibleItemCount = visibleCount,
                positionFraction = ((state.firstVisibleItemIndex + itemProgress) / maxFirstIndex)
                    .coerceIn(0f, 1f),
            )
        }
    }
    FastScrollbar(metrics) { index -> state.scrollToItem(index) }
}

@Composable
private fun BoxScope.FastScrollbar(
    metrics: FastScrollMetrics,
    scrollToItem: suspend (Int) -> Unit,
) {
    if (metrics.itemCount <= metrics.visibleItemCount || metrics.visibleItemCount <= 0) return

    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val visibleFraction = (metrics.visibleItemCount.toFloat() / metrics.itemCount).coerceIn(0f, 1f)
    val minThumbPx = with(density) { FastScrollMinThumbHeight.toPx() }
    val thumbHeightPx = (trackHeightPx * visibleFraction)
        .coerceAtLeast(minThumbPx)
        .coerceAtMost(trackHeightPx.toFloat())
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val positionFraction = dragFraction ?: metrics.positionFraction

    fun jumpTo(fraction: Float) {
        if (metrics.itemCount <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * (metrics.itemCount - 1)).roundToInt()
        scrollJob?.cancel()
        scrollJob = scope.launch { scrollToItem(target) }
    }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(FastScrollTouchWidth)
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(metrics.itemCount, trackHeightPx, thumbHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { offset: Offset ->
                        val fraction = if (travelPx <= 0f) 0f else {
                            ((offset.y - thumbHeightPx / 2f) / travelPx).coerceIn(0f, 1f)
                        }
                        dragFraction = fraction
                        jumpTo(fraction)
                    },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val fraction = ((dragFraction ?: metrics.positionFraction) +
                            if (travelPx > 0f) dragAmount / travelPx else 0f)
                            .coerceIn(0f, 1f)
                        dragFraction = fraction
                        jumpTo(fraction)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (travelPx * positionFraction).roundToInt()) }
                .width(FastScrollThumbWidth)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(colors.accent.copy(alpha = 0.85f), RoundedCornerShape(3.dp)),
        )
    }
}
