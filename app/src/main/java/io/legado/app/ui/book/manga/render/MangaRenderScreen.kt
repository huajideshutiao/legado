package io.legado.app.ui.book.manga.render

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import io.legado.app.R
import io.legado.app.help.glide.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 漫画渲染层：手势容器(原 WebtoonFrame) + LazyColumn/LazyRow 图片流(原 RecyclerView)。
 * 缩放平移经 graphicsLayer 块读取，不触发重组；居中页/停稳/预加载全走 snapshotFlow，
 * 不经重组链。
 */
@Composable
fun MangaRenderLayer(state: MangaRenderState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    state.scope = scope
    state.decaySpec = remember(density) { splineBasedDecay(density) }
    val horizontal = state.horizontal
    // 横向翻页由 snap 归位到整页(原 PagerSnapHelper)；纵向为普通衰减 fling
    val fling = if (horizontal) {
        rememberSnapFlingBehavior(state.listState)
    } else {
        ScrollableDefaults.flingBehavior()
    }
    state.flingBehavior = fling
    // app 端用 Coil3 预加载: execute 配 memoryCachePolicy(WRITE_ONLY) 只写内存缓存不返回 drawable
    state.preloadExecutor = { url, book, source ->
        val screenWidth = context.resources.displayMetrics.widthPixels
        val loader = SingletonImageLoader.get(context)
        val req = ImageRequest.Builder(context)
            .data(MangaModel(url, book, source))
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .size(Size(Dimension(screenWidth), Dimension.Undefined))
            .build()
        loader.execute(req)
    }

    LaunchedEffect(state) {
        // 居中页变化(原 onScrolled + findCenterViewPosition)
        launch {
            snapshotFlow { state.centerItemIndex() }
                .distinctUntilChanged()
                .collect { if (it != -1) state.onCenterItemChanged(it) }
        }
        // 滚动停稳(原 SCROLL_STATE_IDLE)：装填当前页 GIF
        launch {
            snapshotFlow { state.listState.isScrollInProgress }
                .collect { if (!it) state.onScrollIdle() }
        }
        // 可见区间驱动预加载(原 RecyclerViewPreloader, shared 版通过 preloadExecutor 注入)
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
            state.listState.scrollToItem(pendingScroll.index.coerceIn(0, max))
            state.pendingScroll = null
            pendingScroll.onApplied?.invoke()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colorResource(R.color.book_ant_10))
            .onSizeChanged(state::onContainerSize)
            .pointerInput(state) { webtoonGestures(state) }
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
                    mangaItems(state)
                }
            } else {
                LazyColumn(
                    state = state.listState,
                    flingBehavior = fling,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    mangaItems(state)
                }
            }
        }
    }
}

private fun LazyListScope.mangaItems(state: MangaRenderState) {
    val list = state.items
    items(
        count = list.size,
        key = { i ->
            when (val item = list[i]) {
                is MangaPage -> "p:${item.chapterIndex}:${item.index}"
                else -> "l:${item.chapterIndex}"
            }
        },
        contentType = { i -> if (list[i] is MangaPage) 1 else 0 },
    ) { i ->
        when (val item = list[i]) {
            is MangaPage -> MangaPageCell(state, item, i)
            is ReaderLoading -> ReaderLoadingCell(state, item)
        }
    }
}

/** 章节转场占位(原 PageMoreViewHolder)：卷首占整页，其余 96dp 条 */
@Composable
private fun LazyItemScope.ReaderLoadingCell(state: MangaRenderState, item: ReaderLoading) {
    val heightModifier = if (item.isVolume) {
        Modifier.fillParentMaxHeight()
    } else {
        Modifier.height(96.dp)
    }
    Box(
        Modifier
            .fillParentMaxWidth()
            .then(heightModifier)
            .background(colorResource(R.color.book_ant_10)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.mMessage.orEmpty(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 图片单元格(原 PageViewHolder + item_book_manga_page.xml)：
 * 叶子 ImageView 走 Glide/GIF；loading 进度/失败重试为 Compose 覆盖层。
 * 纵向成图后按图片宽高比自适应高度，章末图最小高度 2/3 屏。
 */
@Composable
private fun LazyItemScope.MangaPageCell(state: MangaRenderState, item: MangaPage, index: Int) {
    var load by remember { mutableStateOf(MangaCellState.LOADING) }
    var progress by remember { mutableStateOf("") }
    val viewRef = remember { Ref<MangaPageImageView>() }
    val horizontal = state.horizontal
    val isLastImage = item.imageCount > 0 && item.index == item.imageCount - 1
    val context = LocalContext.current

    val cellModifier = when {
        horizontal -> Modifier.fillParentMaxSize()
        load == MangaCellState.SUCCESS -> Modifier
            .fillMaxWidth()
            .then(
                if (isLastImage) {
                    val minHeight = with(LocalDensity.current) {
                        (context.resources.displayMetrics.heightPixels * 2 / 3).toDp()
                    }
                    Modifier.heightIn(min = minHeight)
                } else {
                    Modifier
                }
            )

        else -> Modifier
            .fillMaxWidth()
            .fillParentMaxHeight()
    }

    Box(cellModifier) {
        AndroidView(
            factory = { MangaPageImageView(it) },
            modifier = if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            onReset = { it.recycle() },
            onRelease = { it.recycle() },
            update = { v ->
                viewRef.value = v
                v.scaleType =
                    if (horizontal) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY
                v.colorFilter = state.colorFilterConfig.toColorFilter()
                v.onStateChange = { load = it }
                v.onProgress = { progress = it }
                v.gifAutoNextEnabled = { state.gifAutoNext }
                v.isArmTarget = { state.gifAutoNext && state.isIdleCenterPage(index) }
                v.onTurnPage = { state.onGifTurnPage() }
                v.load(item.mImageUrl, state.book, state.bookSource, state.grayEnabled)
            },
        )
        if (load != MangaCellState.SUCCESS) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(colorResource(R.color.book_ant_10)),
                contentAlignment = Alignment.Center,
            ) {
                if (load == MangaCellState.LOADING) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 4.dp,
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(text = progress, color = Color.White)
                } else {
                    Text(
                        text = "重新加载",
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewRef.value?.retry() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    DisposableEffect(state, index) {
        val provider = { viewRef.value }
        state.registerGifCell(index, provider)
        onDispose { state.unregisterGifCell(index, provider) }
    }
}

/** 原 PageViewHolder.setImageColorFilter：RGB 反相分量 × 对比度 + 亮度补偿的合并矩阵 */
private fun MangaColorFilterConfig.toColorFilter(): ColorMatrixColorFilter {
    val rF = (255 - r) / 255f
    val gF = (255 - g) / 255f
    val bF = (255 - b) / 255f
    val contrast = 1f + ct / 50f
    val brightness = (1f - contrast) * 128f
    val m = FloatArray(20)
    m[0] = rF * contrast
    m[4] = brightness
    m[6] = gF * contrast
    m[9] = brightness
    m[12] = bF * contrast
    m[14] = brightness
    m[18] = 1f
    return ColorMatrixColorFilter(ColorMatrix(m))
}
