package io.legado.app.ui.book.manga.render

import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 漫画阅读底色, 对照 app 端 activity_manga.xml `@color/book_ant_10` */
val MangaReaderBackground = Color(0xFF141414)

/**
 * 漫画渲染层：手势容器(原 WebtoonFrame) + LazyColumn/LazyRow 图片流(原 RecyclerView)。
 * 缩放平移经 graphicsLayer 块读取，不触发重组；居中页/停稳/预加载全走 snapshotFlow，
 * 不经重组链。
 *
 * 图片单元格由 [pageCell] 平台注入：app 端为 MangaPageImageView(Coil3 + GIF 播完翻页),
 * 其他端为 MangaReaderScreenContent 的 imageSlot 单元格。
 */
@Composable
fun MangaRenderLayer(
    state: MangaRenderState,
    modifier: Modifier = Modifier,
    pageCell: @Composable LazyItemScope.(item: MangaPage, index: Int) -> Unit,
    /** 列表末尾 footer (对照原版 RecyclerView 的 LoadMoreView footer item) */
    footer: (@Composable LazyItemScope.() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    state.scope = scope
    state.decaySpec = remember(density) { splineBasedDecay(density) }
    val horizontal = state.horizontal
    // 横向对齐原 PagerSnapHelper：整页归位且单次手势最多翻一页；纵向为普通衰减 fling
    val fling = if (horizontal) {
        rememberSinglePageSnapFlingBehavior(state.listState)
    } else {
        ScrollableDefaults.flingBehavior()
    }
    state.flingBehavior = fling

    LaunchedEffect(state) {
        // 居中页变化(原 onScrolled + findCenterViewPosition)
        launch {
            snapshotFlow {
                // 布局与 items 可能分帧更新: 中心条目的 key 必须与当前 items 在相同 index 上
                // 一致, 否则是 items 重建窗口内的过期布局 —— 返回 -1 抑制, 防错位条目误触发
                // 跨章/进度回退 (重建完成后下一帧布局与 items 对齐, 自然恢复上报)
                val li = state.listState.layoutInfo
                val center = (li.viewportStartOffset + li.viewportEndOffset) / 2
                val info = li.visibleItemsInfo.firstOrNull {
                    center >= it.offset && center < it.offset + it.size
                }
                if (info != null && state.items.getOrNull(info.index)?.listKey() == info.key) {
                    info.index
                } else {
                    -1
                }
            }
                .distinctUntilChanged()
                .collect { if (it != -1) state.onCenterItemChanged(it) }
        }
        // 滚动停稳(原 SCROLL_STATE_IDLE)：装填当前页 GIF
        launch {
            snapshotFlow { state.listState.isScrollInProgress }
                .collect { if (!it) state.onScrollIdle() }
        }
        // 可见区间驱动预加载(原 RecyclerViewPreloader, 经 preloadExecutor 注入)
        launch {
            snapshotFlow {
                val info = state.listState.layoutInfo.visibleItemsInfo
                (info.firstOrNull()?.index ?: -1) to (info.lastOrNull()?.index ?: -1)
            }
                .distinctUntilChanged()
                .collect { (first, last) -> state.onVisibleRangeChanged(first, last) }
        }
    }

    // 定位请求：keyed 于请求对象，保证在携带新 items 的组合应用后才执行 scrollToItem
    val pendingScroll = state.pendingScroll
    LaunchedEffect(pendingScroll) {
        if (pendingScroll != null) {
            val max = (state.items.size - 1).coerceAtLeast(0)
            state.listState.scrollToItem(
                pendingScroll.index.coerceIn(0, max),
                pendingScroll.scrollOffset,
            )
            state.pendingScroll = null
            pendingScroll.onApplied?.invoke()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MangaReaderBackground)
            .onSizeChanged(state::onContainerSize)
            .pointerInput(state) { webtoonGestures(state) }
            .pointerInput(state) { mangaMouseDragGestures(state) }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { state.onSingleTap(it) },
                    onDoubleTap = { state.onDoubleTap(it) },
                    onLongPress = {
                        if (state.onLongTap()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.transX
                    translationY = state.transY
                }
        ) {
            if (horizontal) {
                LazyRow(
                    state = state.listState,
                    flingBehavior = fling,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    mangaItems(state, footer, pageCell)
                }
            } else {
                LazyColumn(
                    state = state.listState,
                    flingBehavior = fling,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    mangaItems(state, footer, pageCell)
                }
            }
        }
    }
}

private fun LazyListScope.mangaItems(
    state: MangaRenderState,
    footer: (@Composable LazyItemScope.() -> Unit)?,
    pageCell: @Composable LazyItemScope.(item: MangaPage, index: Int) -> Unit,
) {
    val list = state.items
    items(
        count = list.size,
        key = { i -> list[i].listKey() },
        contentType = { i -> if (list[i] is MangaPage) 1 else 0 },
    ) { i ->
        when (val item = list[i]) {
            is MangaPage -> pageCell(item, i)
            is ReaderLoading -> ReaderLoadingCell(item)
        }
    }
    // 原版 LoadMoreView footer: 列表末尾条目, 滚动到底可见; key 与 listKey 前缀不冲突
    if (footer != null) {
        item(key = "loadMoreFooter", contentType = { 2 }) { footer() }
    }
}

/** 章节转场占位(原 PageMoreViewHolder)：卷首占整页，其余 96dp 条 */
@Composable
private fun LazyItemScope.ReaderLoadingCell(item: ReaderLoading) {
    val heightModifier = if (item.isVolume) {
        Modifier.fillParentMaxHeight()
    } else {
        Modifier.height(96.dp)
    }
    Box(
        Modifier
            .fillParentMaxWidth()
            .then(heightModifier)
            .background(MangaReaderBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.mMessage.orEmpty(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
