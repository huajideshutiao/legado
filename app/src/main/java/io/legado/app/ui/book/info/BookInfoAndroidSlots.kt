
package io.legado.app.ui.book.info

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.placeholder
import coil3.size.Precision
import coil3.size.Size
import coil3.size.SizeResolver
import coil3.size.ViewSizeResolver
import coil3.size.pxOrElse
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.blurConfig
import io.legado.app.ui.compose.platform.rememberString
import kotlin.math.min
import kotlinx.coroutines.launch

/*
 * BookInfoScreen 下沉到 shared 后, app 端保留的 L3 (Android 专属) Composable。
 *
 * 这些 Composable 深度依赖 Coil3 / AndroidView / Bitmap, 无法下沉到 shared/sharedUiMain,
 * 通过 BookInfoScreen 的 slot 参数注入到 shared 端使用。
 *
 * 包含:
 * - [BookInfoBlurCoverBg]: 模糊封面背景 (Coil3 + BookInfoBgTransformation + AndroidView)
 * - [BookInfoIntroImage]: 简介内整宽图 (Coil3 execute suspend 取 Bitmap)
 *
 * 原 app 端 BookInfoScreen.kt 中的对应私有 Composable 已删除, 视觉/逻辑完全等价保留。
 * (书籍详情封面原也有 app 端 BookInfoCover 透传实现, 已随封面统一 SharedBookCover 删除,
 * 现直接走 shared 端 [io.legado.app.ui.book.info.BookInfoCover] 统一实现。)
 *
 * getDisplayCover / getRealAuthor 扩展直接复用 shared commonMain 的同名扩展,
 * 无需在 app 端重新定义 (shared 已下沉)。
 */

/**
 * stackBlur 的内部工作短边 (`maxShortSide`): 解码压到这个量级, 模糊照旧在 400 短边上完成,
 * 结果与原版逐像素一致, 但"降采样 → 模糊 → 放大回原尺寸"这趟往返只在小图上走。
 */
private const val BLUR_DECODE_SHORT_SIDE = 400

/** 模糊背景的解码尺寸: 视图实测尺寸等比压到短边 [BLUR_DECODE_SHORT_SIDE] (只缩不放)。 */
private fun blurBgSizeResolver(view: View) = SizeResolver {
    val viewSize = ViewSizeResolver(view).size()
    val width = viewSize.width.pxOrElse { 0 }
    val height = viewSize.height.pxOrElse { 0 }
    if (width <= 0 || height <= 0) return@SizeResolver viewSize
    val scale = (BLUR_DECODE_SHORT_SIDE.toFloat() / min(width, height)).coerceAtMost(1f)
    Size((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}

/**
 * 模糊封面背景: Glide + BookInfoBgTransformation 经 AndroidView 桥接 (视觉等价保留)。
 *
 * 替代原 `BlurCoverBg(activity, modifier)`, 改为接受 [book] / [coverTick] / [inBookshelf]
 * / [isEInkMode] 参数, 由 BookInfoActivity.Content() 内 slot lambda 构造时传入。
 *
 * @param modifier 调用方传入的尺寸约束 (fillMaxSize 或 fillMaxWidth+height(300.dp))
 */
@Composable
fun BookInfoBlurCoverBg(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    isEInkMode: Boolean,
    modifier: Modifier,
    land: Boolean = false,
) {
    val tick = coverTick
    val bgDesc = rememberString("bg_image")
    // 封面取色回调 (详情页宿主提供, 见 shared BookCoverPalette); 加载失败不回调
    val onCoverLoaded = LocalCoverLoaded.current
    val scope = rememberCoroutineScope()
    AndroidView(
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = bgDesc
            }
        },
        modifier = modifier,
        update = { iv ->
            if (book != null && !isEInkMode && iv.tag != tick) {
                iv.tag = tick
                val cover = book.getDisplayCover()
                iv.load(cover) {
                    blurConfig(
                        seed = book.name,
                        sourceOrigin = book.origin,
                        extraTransformations = listOf(BookInfoBgTransformation(land)),
                    )
                    // 模糊背景不必按视图实测尺寸解码 (原 ≈1080×1440, ARGB 约 6MB 常驻内存缓存):
                    // 压到 stackBlur 的工作短边后模糊像素与原版一致, 裁剪/渐变也落在小图上,
                    // 放大交给绘制期的 CENTER_CROP
                    size(blurBgSizeResolver(iv))
                    // 显式给 size 后 Coil 的精度自动推导会落到 EXACT (AndroidRequestService
                    // .resolvePrecision 只对 ViewSizeResolver 给 INEXACT), 会把小封面放大再模糊;
                    // 补回 INEXACT 保持原版"不放大"语义 (Scale 仍由 CENTER_CROP 自动推成 FILL)
                    precision(Precision.INEXACT)
                    placeholder(iv.drawable)
                    listener(onSuccess = { _, _ ->
                        val cb = onCoverLoaded ?: return@listener
                        val coverUrl = cover ?: return@listener
                        // blur 成功 = 原始字节已由 fetcher 落盘, 此小图请求只走缓存命中,
                        // 不会再发网络 (本请求产物是 blur+渐变变换后的图, 不能直接采样,
                        // 故经 loader 另取 24×32 原图小样; 尺寸对齐 shared 取色采样粒度)
                        scope.launch {
                            BookImageLoaders.getOrNull()
                                ?.loadImageOrNull(coverUrl, book.origin, 24, 32)
                                ?.let(cb)
                        }
                    })
                }
            }
        },
    )
}

/**
 * 简介内整宽图: Glide 异步加载 Bitmap, 渲染为 Compose Image (可点击查看大图)。
 *
 * 替代原 `IntroImage(src, onClick)`, 视觉/逻辑等价保留。
 *
 * @param src 图片 URL
 * @param onClick 点击查看大图回调 (派发到 actions.onShowPhoto(src))
 */
@Composable
fun BookInfoIntroImage(
    src: String,
    onClick: () -> Unit,
) {
    var bitmap by remember(src) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src) {
        // 走 ImageBitmapLoader (内置栅格解码 + androidsvg 兜底, 与图片查看器同链路); data: URI 早返回
        val bmp = ImageBitmapLoader().loadBitmap(
            url = src,
            book = null,
            bookSource = null,
            isCover = false,
            widthPx = 0,
            heightPx = 0,
            useBitmapCache = true,
        )
        bitmap = bmp?.asAndroidBitmap()
    }
    bitmap?.let {
        DisableSelection {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(onClick = onClick),
            )
        }
    }
}
