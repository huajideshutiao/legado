package io.legado.app.ui.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 快速滚动条平台表现配置。
 *
 * 各端只声明表现差异 (尺寸 / 常显 / 悬停高亮), 共享实现 [FastScrollbar] 据此统一推导
 * 可见性与高亮状态, 不在共享代码里散落平台分支。
 *
 * - 移动端: 窄触摸条 + 滚动时短暂显示 + 仅按住高亮;
 * - 桌面端: 鼠标命中区加宽 + 常显 + 悬停即高亮加宽 (提前响应按压)。
 */
internal data class FastScrollPlatformConfig(
    /** 触摸区(命中区)宽度。移动端手指命中面积由触摸本身保证; 桌面端鼠标命中面积小, 需加宽。 */
    val touchWidth: Dp,
    /** 滑块可见宽度。 */
    val thumbWidth: Dp,
    /** 常显: 不随滚动静止自动隐藏。 */
    val alwaysVisible: Boolean = false,
    /** 悬停即进入按压高亮态 (主题色 + 加宽), 等于提前响应按压。 */
    val hoverHighlight: Boolean = false,
)

internal expect val fastScrollPlatform: FastScrollPlatformConfig

private val FastScrollThumbHeight = 100.dp

/** 按内容比例计算滑块长度时的最小长度 (短列表的滑块仍可抓取)。 */
private val FastScrollMinThumbHeight = 24.dp
private const val FastScrollHideDelayMillis = 1_000L
private const val FastScrollShowAnimationMillis = 300
private const val FastScrollHideAnimationMillis = 300

private data class FastScrollMetrics(
    val positionFraction: Float,
    /** 列表是否实际可滚动 (内容超出视口), 决定滚动条是否显示。 */
    val needsScroll: Boolean,
)

/**
 * 带快速滚动条的 LazyColumn。
 *
 * 迁移前的界面普遍使用 FastScrollRecyclerView；这个共享实现把同等的拖动跳转能力
 * 补回 Compose/KMP，并在内容无需滚动 (不超视口) 时自动隐藏滚动条。
 *
 * @param wrapContentHeight true 时 LazyColumn 自适应内容高度 (不加 fillMaxSize), 供内容自适应
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
        LazyListFastScrollbar(state)
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
        LazyGridFastScrollbar(state)
    }
}

/**
 * 带快速滚动条的 LazyVerticalStaggeredGrid (瀑布流)。
 *
 * @param wrapContentHeight 语义同 [FastScrollLazyColumn]: true 时网格高度自适应内容,
 * false (默认) 时撑满。
 */
@Composable
fun FastScrollLazyVerticalStaggeredGrid(
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalItemSpacing: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    wrapContentHeight: Boolean = false,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalStaggeredGrid(
            columns = columns,
            state = state,
            modifier = if (wrapContentHeight) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalItemSpacing = verticalItemSpacing,
            horizontalArrangement = horizontalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        LazyStaggeredGridFastScrollbar(state)
    }
}

@Composable
private fun BoxScope.LazyListFastScrollbar(state: LazyListState) {
    val metrics by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            val itemCount = layout.totalItemsCount
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
                needsScroll = state.canScrollBackward || state.canScrollForward,
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
                needsScroll = state.canScrollBackward || state.canScrollForward,
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
private fun BoxScope.LazyStaggeredGridFastScrollbar(state: LazyStaggeredGridState) {
    val metrics by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            val itemCount = layout.totalItemsCount
            val first = visible.firstOrNull()
            val averageItemHeight = if (visible.isEmpty()) {
                0f
            } else {
                visible.map { it.size.height }.average().toFloat()
            }
            val laneCount = (visible.maxOfOrNull { it.lane }?.plus(1) ?: 1).coerceAtLeast(1)
            val estimatedTotalRows = (itemCount + laneCount - 1) / laneCount
            val firstRow = (first?.index ?: 0) / laneCount
            val currentScrollOffsetPx = firstRow * averageItemHeight - (first?.offset?.y ?: 0)
            val viewportSizePx = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0)
            val maxScrollOffsetPx = (estimatedTotalRows * averageItemHeight - viewportSizePx).coerceAtLeast(0f)
            FastScrollMetrics(
                needsScroll = state.canScrollBackward || state.canScrollForward,
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
                val averageItemHeight = visible.map { it.size.height }.average().toFloat()
                val laneCount = (visible.maxOfOrNull { it.lane }?.plus(1) ?: 1).coerceAtLeast(1)
                val estimatedTotalRows = (latest.totalItemsCount + laneCount - 1) / laneCount
                val firstRow = visible.first().index / laneCount
                val currentOffset = firstRow * averageItemHeight - visible.first().offset.y
                val viewportSize = latest.viewportEndOffset - latest.viewportStartOffset
                val maxOffset = (estimatedTotalRows * averageItemHeight - viewportSize).coerceAtLeast(0f)
                state.scrollBy(fraction * maxOffset - currentOffset)
            }
        },
    )
}

/**
 * [ScrollState] 驱动的快速滚动条 (没有 lazy 布局信息可用时的窄适配)。
 *
 * 滑块位置与长度用真实滚动偏移 [ScrollState.value]、可滚范围 [ScrollState.maxValue] 与
 * 视口 [ScrollState.viewportSize] 推出 (长度按视口占比给, 不是列表那档固定 100dp);
 * 拖动直接调 [ScrollState.scrollTo]。绘制、显隐规则与拖动/悬停交互全部复用 [FastScrollbar]。
 *
 * 首次组合即显示 (移动端沿用既有"静止约 1s 后隐藏", 桌面常显), 让面板打开就能看出还能滚。
 */
@Composable
internal fun BoxScope.ScrollStateFastScrollbar(
    state: ScrollState,
    interactive: Boolean,
) {
    // 首次布局前 ScrollState 仍是未测量状态 (maxValue = Int.MAX_VALUE, viewportSize = 0):
    // 此时不组合滚动条 —— 没有真实滑块长度, 也不该存在默认 100dp 滑块与它的触区
    if (state.viewportSize <= 0 || state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return
    val metrics by remember(state) {
        derivedStateOf {
            FastScrollMetrics(
                needsScroll = state.maxValue > 0,
                positionFraction = if (state.maxValue <= 0) {
                    0f
                } else {
                    (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f)
                },
            )
        }
    }
    // 视口与可滚范围都已实测 (上方守卫): 滑块长度按内容比例给出, 不用列表的固定 100dp
    val thumbFraction = fastScrollThumbFraction(state.viewportSize, state.maxValue)
    FastScrollbar(
        metrics = metrics,
        isScrollInProgress = state.isScrollInProgress,
        isDragged = state.interactionSource.collectIsDraggedAsState().value,
        scrollToFraction = { fraction ->
            state.scrollTo((fraction * state.maxValue).roundToInt())
        },
        thumbFraction = thumbFraction,
        initiallyVisible = true,
        interactive = interactive,
    )
}

/**
 * 滑块长度占轨道比例: 视口占"视口 + 可滚范围"的份额。
 *
 * 调用方只在视口已测量且确实可滚动时调用 (不可滚动时滚动条不显示, 不产生该比例);
 * 用浮点相加, 可滚范围达到 Int 上限也不溢出。
 */
internal fun fastScrollThumbFraction(viewportSize: Int, maxValue: Int): Float =
    viewportSize.toFloat() / (viewportSize.toFloat() + maxValue.toFloat())

@Composable
private fun BoxScope.FastScrollbar(
    metrics: FastScrollMetrics,
    isScrollInProgress: Boolean,
    isDragged: Boolean,
    scrollToFraction: suspend (Float) -> Unit,
    /** 滑块长度占轨道比例; null = 固定 100dp (列表既有规格) */
    thumbFraction: Float? = null,
    /** 首次组合即显示 (菜单展开后立刻给出还能滚的提示), 之后照既有隐藏规则走 */
    initiallyVisible: Boolean = fastScrollPlatform.alwaysVisible,
    /** 是否接收拖动/悬停输入; false = 只留绘制 (菜单退场动画期间) */
    interactive: Boolean = true,
) {
    // 内容无需滚动时不显示滚动条 (桌面常显/悬停高亮只对可滚动列表生效)。
    if (!metrics.needsScroll) return

    val colors = AppTheme.colors
    // 墨水屏不做过渡 (残影且无灰阶意义): 滑块缩放与整条显隐都直接到位
    val eInk = LocalEInk.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var scrollbarVisible by remember {
        mutableStateOf(initiallyVisible || fastScrollPlatform.alwaysVisible)
    }
    val thumbHeightPx = if (thumbFraction == null) {
        with(density) { FastScrollThumbHeight.toPx() }.coerceAtMost(trackHeightPx.toFloat())
    } else {
        (trackHeightPx * thumbFraction)
            .coerceAtLeast(with(density) { FastScrollMinThumbHeight.toPx() })
            .coerceAtMost(trackHeightPx.toFloat())
    }
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val positionFraction = dragFraction ?: metrics.positionFraction
    // 交互激活态: 按住 或 (桌面悬停, 等于提前响应按压)。
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val active = dragFraction != null || (fastScrollPlatform.hoverHighlight && hovered)
    val selectedScale by animateFloatAsState(
        targetValue = if (active) 1.5f else 1f,
        animationSpec = tween(if (eInk) 0 else 200),
        label = "fastScrollHandleScale",
    )

    // 可见性策略: 桌面常显; 移动端任何滚动(拖拽/惯性/滚轮/程序滚动)都显示, 静止约 1s 后隐藏;
    // 拖动 handle 期间保持显示 (原版 mHandleView.isSelected 语义)。
    LaunchedEffect(isDragged, isScrollInProgress, dragFraction) {
        when {
            fastScrollPlatform.alwaysVisible -> scrollbarVisible = true
            dragFraction != null || isDragged -> scrollbarVisible = true
            isScrollInProgress -> scrollbarVisible = true
            else -> {
                delay(FastScrollHideDelayMillis)
                scrollbarVisible = false
            }
        }
    }

    fun updateDragPosition(y: Float) {
        // 滑块位置与滚动目标共用 handleFraction (扣半滑块高的映射, 与原版 setViewPositions 一致),
        // 避免拖到中途松手后滑块跳位
        val handleFraction = if (travelPx <= 0f) {
            0f
        } else {
            ((y - thumbHeightPx / 2f) / travelPx).coerceIn(0f, 1f)
        }
        dragFraction = handleFraction
        scrollJob?.cancel()
        scrollJob = scope.launch { scrollToFraction(handleFraction) }
    }

    AnimatedVisibility(
        visible = scrollbarVisible,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = if (eInk) {
            EnterTransition.None
        } else {
            fadeIn(tween(FastScrollShowAnimationMillis)) +
                slideInHorizontally(tween(FastScrollShowAnimationMillis)) { it }
        },
        exit = if (eInk) {
            ExitTransition.None
        } else {
            fadeOut(tween(FastScrollHideAnimationMillis)) +
                slideOutHorizontally(tween(FastScrollHideAnimationMillis)) { it }
        },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(fastScrollPlatform.touchWidth)
                .then(if (interactive) Modifier.hoverable(hoverInteraction) else Modifier)
                .onSizeChanged { trackHeightPx = it.height }
                // key 不含 itemCount: 分页加载/刷新时项数变化会重启手势协程打断正在进行的拖拽
                .then(if (interactive) Modifier.pointerInput(trackHeightPx, thumbHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 对齐原版 onTouchEvent ACTION_DOWN: 按下即定位跳转, 不等 touch slop
                        updateDragPosition(down.position.y)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                updateDragPosition(change.position.y)
                            }
                        } finally {
                            dragFraction = null
                        }
                    }
                } else Modifier),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (travelPx * positionFraction).roundToInt()) }
                    .graphicsLayer {
                        scaleX = selectedScale
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    }
                    .width(fastScrollPlatform.thumbWidth)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .background(
                        if (active) {
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
