
package io.legado.app.ui.book.info

import android.graphics.Bitmap
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
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.blurConfig
import io.legado.app.ui.compose.platform.rememberString
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
