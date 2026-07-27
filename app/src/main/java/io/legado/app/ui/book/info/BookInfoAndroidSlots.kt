@file:OptIn(ExperimentalMaterial3Api::class)

package io.legado.app.ui.book.info

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isVideo
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.CoverRatio
import io.legado.app.ui.main.bookshelf.ShelfCover

/*
 * BookInfoScreen 下沉到 shared 后, app 端保留的 L3 (Android 专属) Composable。
 *
 * 这些 Composable 深度依赖 Glide / AndroidView / Bitmap, 无法下沉到 shared/sharedUiMain,
 * 通过 BookInfoScreen 的 slot 参数注入到 shared 端使用。
 *
 * 包含:
 * - [BookInfoBlurCoverBg]: 模糊封面背景 (Glide + BookInfoBgTransformation + AndroidView)
 * - [BookInfoCover]: 书籍封面 (AndroidView + CoverImageView + Glide, 包装 ShelfCover)
 * - [BookInfoIntroImage]: 简介内整宽图 (Glide + asBitmap + CustomTarget<Bitmap>)
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
) {
    val tick = coverTick
    val bgDesc = stringResource(R.string.bg_image)
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
                BookCover.loadBlur(
                    ImageLoader.with(iv),
                    book.getDisplayCover(),
                    sourceOrigin = book.origin,
                    inBookshelf = inBookshelf,
                    seed = book.name,
                    extraTransformations = listOf(BookInfoBgTransformation()),
                ).placeholder(iv.drawable).into(iv)
            }
        },
    )
}

/**
 * 书籍封面: 包装 [ShelfCover] (AndroidView + CoverImageView + Glide)。
 *
 * 替代原 `InfoCover` 中 ShelfCover 调用部分。modifier 由 shared 端构造时已包含
 * height(144.dp)/clip/background/clickable, 本函数只负责把 (book, reloadKey, inBookshelf)
 * 透传给 ShelfCover。
 *
 * @param book 当前书籍 (可能为 null)
 * @param coverTick 封面重载 key (对照 activity.coverTick)
 * @param inBookshelf 是否在书架中 (对照 activity.viewModel.inBookshelf)
 * @param modifier shared 端构造的 modifier (含尺寸/形状/点击)
 */
@Composable
fun BookInfoCover(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    modifier: Modifier,
) {
    val ratio = if (book?.isVideo == true) CoverRatio.VIDEO
    else CoverRatio.NOVEL
    ShelfCover(
        path = book?.getDisplayCover(),
        name = book?.name,
        author = book?.getRealAuthor(),
        origin = book?.origin,
        ratio = ratio,
        reloadKey = coverTick,
        inBookshelf = inBookshelf,
        modifier = modifier,
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
    val context = LocalContext.current
    LaunchedEffect(src) {
        ImageLoader.with(context).asBitmap().load(src).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        })
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
