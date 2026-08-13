package io.legado.app.ui.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FastScrollTouchWidth = 15.dp
private val FastScrollThumbWidth = 5.dp
private val FastScrollThumbHeight = 100.dp
private const val FastScrollHideDelayMillis = 1_000L
private const val FastScrollShowAnimationMillis = 300
private const val FastScrollHideAnimationMillis = 300

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
 *
 * @param fastScrollEnabled 是否启用快速滚动条 (对照原版 FastScrollRecyclerView.setFastScrollEnabled)。
 * 关闭时仅隐藏滚动条, 列表本身不受影响。
 * @param wrapContentHeight true 时 LazyColumn 不自适应内容高度 (不加 fillMaxSize), 供内容自适应
 * 对话框 (如分组选择) 使用: 项少时列表随内容收缩, 超出父容器约束时封顶并可滚动;
 * false (默认) 时保持 fillMaxSize 撑满, 行为与既有调用方完全一致。
 */
@Composable
fun FastScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    fastScrollEnabled: Boolean = true,
    wrapContentHeight: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = if (wrapContentHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        if (fastScrollEnabled) LazyListFastScrollbar(state)
    }
}

/**
 * 带快速滚动条的 LazyVerticalGrid。
 *
 * @param wrapContentHeight 语义同 [FastScrollLazyColumn]: true 时网格高度自适应内容 (不加 fillMaxSize),
 * false (默认) 时撑满, 行为与既有调用方完全一致。
 */
@Composable
fun FastScrollLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    fastScrollEnabled: Boolean = true,
    wrapContentHeight: Boolean = false,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            modifier = if (wrapContentHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        if (fastScrollEnabled) LazyGridFastScrollbar(state)
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
            val averageItemSize = if (visible.isEmpty()) {
                0f
            } else {
                visible.map { it.size }.average().toFloat()
            }
            val currentScrollOffsetPx = state.firstVisibleItemIndex * averageItemSize -
                (first?.offset ?: 0)
            val viewportSizePx = (layout.viewportEndOffset - layout.viewportStartOffset)
                .coerceAtLeast(0)
            val maxScrollOffsetPx = (itemCount * averageItemSize - viewportSizePx)
                .coerceAtLeast(0f)
            FastScrollMetrics(
                itemCount = itemCount,
                visibleItemCount = visibleCount,
                positionFraction = when {
                    !state.canScrollBackward -> 0f
                    !state.canScrollForward -> 1f
                    maxScrollOffsetPx <= 0f -> 0f
                    else -> (currentScrollOffsetPx / maxScrollOffsetPx).coerceIn(0f, 1f)
                },
            )
        }
    }
    FastScrollbar(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        isDragged = state.interactionSource.collectIsDraggedAsState().value,
        scrollToFraction = { fraction ->
            val latest = state.layoutInfo
            val visible = latest.visibleItemsInfo
            if (visible.isNotEmpty()) {
                val averageItemSize = visible.map { it.size }.average().toFloat()
                val currentOffset = state.firstVisibleItemIndex * averageItemSize -
                    visible.first().offset
                val viewportSize = latest.viewportEndOffset - latest.viewportStartOffset
                val maxOffset = (latest.totalItemsCount * averageItemSize - viewportSize)
                    .coerceAtLeast(0f)
                state.scrollBy(fraction * maxOffset - currentOffset)
            }
        },
    )
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
            val visibleRows = visible.groupBy { it.row }.filterKeys { it >= 0 }
            val averageRowHeight = if (visibleRows.isEmpty()) {
                0f
            } else {
                visibleRows.values.map { row -> row.maxOf { it.size.height } }
                    .average().toFloat() + layout.mainAxisItemSpacing
            }
            val totalRows = if (layout.maxSpan <= 0) {
                0
            } else {
                (itemCount + layout.maxSpan - 1) / layout.maxSpan
            }
            val firstRow = first?.row?.coerceAtLeast(0) ?: 0
            val currentScrollOffsetPx = firstRow * averageRowHeight - (first?.offset?.y ?: 0)
            val viewportSizePx = (layout.viewportEndOffset - layout.viewportStartOffset)
                .coerceAtLeast(0)
            val maxScrollOffsetPx = (totalRows * averageRowHeight - viewportSizePx)
                .coerceAtLeast(0f)
            FastScrollMetrics(
                itemCount = itemCount,
                visibleItemCount = visibleCount,
                positionFraction = when {
                    !state.canScrollBackward -> 0f
                    !state.canScrollForward -> 1f
                    maxScrollOffsetPx <= 0f -> 0f
                    else -> (currentScrollOffsetPx / maxScrollOffsetPx).coerceIn(0f, 1f)
                },
            )
        }
    }
    FastScrollbar(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        isDragged = state.interactionSource.collectIsDraggedAsState().value,
        scrollToFraction = { fraction ->
            val latest = state.layoutInfo
            val visible = latest.visibleItemsInfo
            if (visible.isNotEmpty() && latest.maxSpan > 0) {
                val visibleRows = visible.groupBy { it.row }.filterKeys { it >= 0 }
                val averageRowHeight = visibleRows.values.map { row ->
                    row.maxOf { it.size.height }
                }.average().toFloat() + latest.mainAxisItemSpacing
                val totalRows = (latest.totalItemsCount + latest.maxSpan - 1) / latest.maxSpan
                val firstRow = visible.first().row.coerceAtLeast(0)
                val currentOffset = firstRow * averageRowHeight - visible.first().offset.y
                val viewportSize = latest.viewportEndOffset - latest.viewportStartOffset
                val maxOffset = (totalRows * averageRowHeight - viewportSize)
                    .coerceAtLeast(0f)
                state.scrollBy(fraction * maxOffset - currentOffset)
            }
        },
    )
}

@Composable
private fun BoxScope.FastScrollbar(
    metrics: FastScrollMetrics,
    isScrollInProgress: Boolean,
    isDragged: Boolean,
    scrollToFraction: suspend (Float) -> Unit,
) {
    if (metrics.itemCount <= metrics.visibleItemCount || metrics.visibleItemCount <= 0) return

    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var scrollbarVisible by remember { mutableStateOf(false) }
    val thumbHeightPx = with(density) { FastScrollThumbHeight.toPx() }
        .coerceAtMost(trackHeightPx.toFloat())
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val positionFraction = dragFraction ?: metrics.positionFraction
    val selectedScale by animateFloatAsState(
        targetValue = if (dragFraction != null) 1.5f else 1f,
        animationSpec = tween(200),
        label = "fastScrollHandleScale",
    )

    // 对照原版 FastScroller: 任何滚动(拖拽/惯性/滚轮/程序滚动)都显示, 静止约 1s 后隐藏;
    // 拖动 handle 期间保持显示 (原版 mHandleView.isSelected 语义)。
    LaunchedEffect(isDragged, isScrollInProgress, dragFraction) {
        when {
            dragFraction != null || isDragged -> scrollbarVisible = true
            isScrollInProgress -> scrollbarVisible = true
            else -> {
                delay(FastScrollHideDelayMillis)
                scrollbarVisible = false
            }
        }
    }

    fun updateDragPosition(y: Float) {
        val handleFraction = if (travelPx <= 0f) {
            0f
        } else {
            ((y - thumbHeightPx / 2f) / travelPx).coerceIn(0f, 1f)
        }
        dragFraction = handleFraction
        val scrollFraction = (y / trackHeightPx).coerceIn(0f, 1f)
        scrollJob?.cancel()
        scrollJob = scope.launch { scrollToFraction(scrollFraction) }
    }

    AnimatedVisibility(
        visible = scrollbarVisible,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = fadeIn(tween(FastScrollShowAnimationMillis)) +
            slideInHorizontally(tween(FastScrollShowAnimationMillis)) { it },
        exit = fadeOut(tween(FastScrollHideAnimationMillis)) +
            slideOutHorizontally(tween(FastScrollHideAnimationMillis)) { it },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(FastScrollTouchWidth)
                .onSizeChanged { trackHeightPx = it.height }
                .pointerInput(metrics.itemCount, trackHeightPx, thumbHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset: Offset -> updateDragPosition(offset.y) },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            updateDragPosition(change.position.y)
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (travelPx * positionFraction).roundToInt()) }
                    .graphicsLayer {
                        scaleX = selectedScale
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    }
                    .width(FastScrollThumbWidth)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .background(
                        if (dragFraction != null) {
                            colors.accent
                        } else if (colors.isDark) {
                            Color(0x66666666)
                        } else {
                            Color(0xAAAAAAAA)
                        },
                        RoundedCornerShape(topStart = 2.5.dp, bottomStart = 2.5.dp),
                    ),
            )
        }
    }
}
