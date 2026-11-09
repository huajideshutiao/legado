
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.sourceOrigin
import io.legado.app.model.stackBlurBitmap
import io.legado.app.ui.book.info.bookInfoBgOverlay
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 模糊封面背景: 模糊计算留在 Android 位图层 (StackBlur), 渲染走 Compose `Image`,
 * 与 shared [io.legado.app.ui.book.info.SharedBlurCoverBg] 同构。
 *
 * 三个阶段分开归属: 图片请求只取未变换的工作图 (容器尺寸不参与身份, 也不做精确尺寸匹配);
 * CPU 模糊只随真实底层位图或模糊半径变化算一次; 压暗与渐变蒙版在绘制期按当前容器叠。
 * 现在图从缓存里复用到同一张位图时, 容器微变只重绘、不重解也不重糊。
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
    val origin = book?.origin
    val bgDesc = rememberString("bg_image")
    // 取色入口 (详情页宿主提供): 本端绘制的是模糊后的图, 不能直接采样, 另取原图小样上报
    val onCoverLoaded = LocalCoverLoaded.current
    // 工作图 (未变换的原图) 与容器无关: 容器微变不重建请求, 也不重算模糊。
    // 存真实底层 Bitmap (不是每取一次就新建的 ImageBitmap 包装), 这样只有原图真的换了才重糊。
    var work by remember { mutableStateOf<Bitmap?>(null) }
    // 模糊背景是否已绘制 (宿主与绘制方共用同一状态, 见 shared BookCoverPalette)
    val paintedState = LocalBlurCoverPainted.current
    LaunchedEffect(cover, coverTick, isEInkMode, origin) {
        // book 为 null 时 cover 必为 null, 故先取 b 与后置判断等价; 放在 cover 判断前
        // 可避开 K2 的“cover 非空已蕴含 book 非空”推断造成的冗余告警
        val b = book ?: return@LaunchedEffect
        if (isEInkMode || cover.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(cover)
            // 书源防盗链 header 随 origin 注入 (同封面链), 漏掉会被只认 Referer 的源拒绝
            .sourceOrigin(b.origin)
            // 本请求不烘焙任何变换: 带变换的请求会把完整请求尺寸写进缓存键并强制精确匹配
            .memoryCacheKey("$cover\u0000${b.origin}\u0000bg")
            // 长边封顶取原图量级的未变换工作图 (按库自身的尺寸复用能力取图, 不做精确尺寸匹配)
            .size(BG_WORK_MAX_LONG_SIDE, BG_WORK_MAX_LONG_SIDE)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            // 工作图要过 CPU 模糊, 必须软件位图; 只约束本请求, 不全局禁硬件位图
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        val loaded = result?.image?.toBitmap() ?: return@LaunchedEffect
        work = loaded
        // 原始字节已落盘, 此小图请求只走缓存命中, 不会再发网络; 尺寸对齐 shared 取色采样粒度
        BookImageLoaders.get()
            .loadImageOrNull(cover, b.origin, 24, 32)
            ?.let { onCoverLoaded(it) }
    }
    // CPU 模糊只随真实底层位图与半径变化算一次 (同一原图换包装/容器微变都不重算)
    val blurred by produceState<ImageBitmap?>(null, work, BG_BLUR_RADIUS) {
        val source = work ?: return@produceState
        value = withContext(Dispatchers.Default) {
            stackBlurBitmap(source, BG_BLUR_RADIUS).asImageBitmap()
        }
    }
    // 写出时机与绘制条件一致: 本实现画出封面的充要条件就是绘制图非空
    SideEffect { paintedState.value = blurred != null }
    Box(modifier) {
        blurred?.let { drawn ->
            Image(
                bitmap = drawn,
                contentDescription = bgDesc,
                contentScale = ContentScale.Crop,
                // 压暗/渐变蒙版与三端共用同一份曲线, 压暗用原版 SRC_ATOP 语义 (只压图自身)
                modifier = Modifier
                    .fillMaxSize()
                    .bookInfoBgOverlay(land),
            )
        }
    }
}

/** 工作纹理模糊半径 (StackBlur 像素半径, 与三端 24dp 绘制期模糊不同)。 */
private const val BG_BLUR_RADIUS = 20

/** 工作纹理长边上限: 未变换原图量级的采样上限, 与容器尺寸无关。 */
private const val BG_WORK_MAX_LONG_SIDE = 1600

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
