
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.legado.app.help.book.isVideo
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.blurConfig
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.compose.platform.rememberString

/*
 * BookInfoScreen 下沉到 shared 后, app 端保留的 L3 (Android 专属) Composable。
 *
 * 这些 Composable 深度依赖 Coil3 / AndroidView / Bitmap, 无法下沉到 shared/sharedUiMain,
 * 通过 BookInfoScreen 的 slot 参数注入到 shared 端使用。
 *
 * 包含:
 * - [BookInfoBlurCoverBg]: 模糊封面背景 (Coil3 + BookInfoBgTransformation + AndroidView)
 * - [BookInfoCover]: 书籍封面 (转发 LocalBookCoverSlot → ShelfCover → CoverImageView)
 * - [BookInfoIntroImage]: 简介内整宽图 (Coil3 execute suspend 取 Bitmap)
 *
 * 原 app 端 BookInfoScreen.kt 中的对应私有 Composable 已删除, 视觉/逻辑完全等价保留。
 *
 * isVideo / getDisplayCover / getRealAuthor 扩展直接复用 shared commonMain 的同名扩展,
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
                iv.load(book.getDisplayCover()) {
                    blurConfig(
                        seed = book.name,
                        sourceOrigin = book.origin,
                        extraTransformations = listOf(BookInfoBgTransformation(land)),
                    )
                    placeholder(iv.drawable)
                }
            }
        },
    )
}

/**
 * 书籍封面: 复用书架通用封面槽 [LocalBookCoverSlot] (app 端注入 ShelfCover → CoverImageView)。
 *
 * 详情页不再自建一套封面渲染, [coverTick] 变化时经 key 强制重建触发重载。
 *
 * @param book 当前书籍 (可能为 null)
 * @param coverTick 封面重载 key (对照 activity.coverTick)
 * @param inBookshelf 是否在书架中 (保留签名对齐 slot 契约, 封面渲染不参与)
 * @param modifier shared 端构造的 modifier (含尺寸/形状/点击)
 */
@Composable
fun BookInfoCover(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    modifier: Modifier,
) {
    book ?: return
    val coverSlot = LocalBookCoverSlot.current
    key(book.bookUrl, coverTick) {
        coverSlot(book, modifier, book.isVideo, 0)
    }
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
