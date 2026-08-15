package io.legado.app.ui.book.manga.render

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.entities.MangaPage

/**
 * app 端漫画渲染的平台部分：图片单元格(叶子 ImageView 走 Coil3/GIF) + Coil3 预加载器。
 * 列表/手势/缩放/章节转场页/预加载簿记已下沉到 shared [MangaRenderLayer]。
 */

/** 装配 Coil3 预加载：memoryCachePolicy(WRITE_ONLY) 只写内存缓存不返回 drawable */
fun MangaRenderState.installCoilPreloader(context: Context) {
    preloadExecutor = { url, book, source ->
        val screenWidth = context.resources.displayMetrics.widthPixels
        val loader = SingletonImageLoader.get(context)
        val req = ImageRequest.Builder(context)
            .data(MangaModel(url, book, source))
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .size(Size(Dimension(screenWidth), Dimension.Undefined))
            .build()
        loader.execute(req)
    }
}

/**
 * 图片单元格(原 PageViewHolder + item_book_manga_page.xml)：
 * 叶子 ImageView 走 Coil3/GIF；loading 进度/失败重试为 Compose 覆盖层。
 * 纵向成图后按图片宽高比自适应高度，章末图最小高度 2/3 屏。
 */
@Composable
fun LazyItemScope.MangaPageCell(state: MangaRenderState, item: MangaPage, index: Int) {
    var load by remember { mutableStateOf(MangaCellState.LOADING) }
    // 初始即显示 0% (原版布局 item_book_manga_page.xml 初始文本 "0%")
    var progress by remember { mutableStateOf("0%") }
    val viewRef = remember { Ref<MangaPageImageView>() }
    val horizontal = state.horizontal
    val isLastImage = item.imageCount > 0 && item.index == item.imageCount - 1

    val cellModifier = when {
        horizontal -> Modifier.fillParentMaxSize()
        load == MangaCellState.SUCCESS -> Modifier
            .fillMaxWidth()
            .then(
                if (isLastImage) {
                    val minHeight = with(LocalDensity.current) {
                        // LocalResources 随 Configuration 变更失效 (LocalContext.resources 不会)
                        (LocalResources.current.displayMetrics.heightPixels * 2 / 3).toDp()
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
                v.loadPageImage(item.mImageUrl, state.book, state.bookSource, state.grayEnabled)
            },
        )
        if (load != MangaCellState.SUCCESS) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MangaReaderBackground),
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
