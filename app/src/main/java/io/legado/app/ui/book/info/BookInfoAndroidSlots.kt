
package io.legado.app.ui.book.info

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.model.blurConfig
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/*
 * BookInfoScreen 下沉到 shared 后, app 端保留的 L3 (Android 专属) Composable。
 *
 * 这些 Composable 深度依赖 Coil3 / android.graphics / Bitmap, 无法下沉到 shared/sharedUiMain,
 * 通过 BookInfoScreen 的 slot 参数注入到 shared 端使用。
 *
 * 包含:
 * - [BookInfoBlurCoverBg]: 模糊封面背景 (Coil3 位图管线模糊 + Compose Image 渲染)
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
 * 模糊封面背景: 模糊计算留在 Android 位图层 (StackBlur + [BookInfoBgTransformation] 在 Coil3
 * 请求内执行; Compose `Modifier.blur` 依赖 RenderEffect, API<31 无效, 本项目 minSdk 24),
 * 渲染走 Compose `Image`, 与 shared [io.legado.app.ui.book.info.SharedBlurCoverBgCoil] 同构。
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
    val context = LocalContext.current
    val cover = book?.getDisplayCover()
    val bgDesc = rememberString("bg_image")
    // bitmap 只在成功取到新图时整体替换 (不随 cover 键重置): 换/清 URL 的那一帧保留旧图,
    // 否则重载被失败跳过表拦截时 (url 曾 403) 会闪回空白
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    // 封面取色回调 (详情页宿主提供, 见 shared BookCoverPalette); 加载失败不回调
    val onCoverLoaded = LocalCoverLoaded.current
    // 容器尺寸测量后触发加载, 变化时按新尺寸重解 (渐变蒙版比例须与容器一致,
    // 见 BookInfoBgTransformation.cropToAspect)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(cover, coverTick, isEInkMode, land, containerSize) {
        // book 为 null 时 cover 必为 null, 故先取 b 与后置判断等价; 放在 cover 判断前
        // 可避开 K2 的“cover 非空已蕴含 book 非空”推断造成的冗余告警
        val b = book ?: return@LaunchedEffect
        if (isEInkMode || cover.isNullOrBlank() || containerSize == IntSize.Zero) {
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(cover)
            .size(containerSize.width, containerSize.height)
            .scale(Scale.FILL)
            .blurConfig(
                seed = b.name,
                sourceOrigin = b.origin,
                extraTransformations = listOf(BookInfoBgTransformation(land)),
            )
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        val loaded = result?.image?.toBitmap() ?: return@LaunchedEffect
        bitmap = loaded.asImageBitmap()
        // blur 成功 = 原始字节已由 fetcher 落盘, 此小图请求只走缓存命中,
        // 不会再发网络 (本请求产物是 blur+渐变变换后的图, 不能直接采样,
        // 故经 loader 另取 24×32 原图小样; 尺寸对齐 shared 取色采样粒度)
        BookImageLoaders.getOrNull()
            ?.loadImageOrNull(cover, b.origin, 24, 32)
            ?.let { onCoverLoaded?.invoke(it) }
    }
    Box(modifier.onSizeChanged { containerSize = it }) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = bgDesc,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
                    .padding(vertical = DesignTokens.spacingXs)
                    .clickable(onClick = onClick),
            )
        }
    }
}
