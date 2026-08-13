package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.image.decodeBytesSampled
import io.legado.app.help.image.decodeSvgFallback
import io.legado.app.help.image.isGifBytes
import io.legado.app.help.image.rememberAnimatedImageBitmap
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.zoomable
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.close
import legado.shared.generated.resources.image_cover_default
import legado.shared.generated.resources.loading
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/**
 * 跨平台大图查看内容件 (对照 app 端 [io.legado.app.ui.widget.dialog.PhotoDialog] 的 Content)。
 *
 * 图片加载走 [ImageBitmapLoader] (commonMain 面, 各端 actual: jvm=OkHttp+ImageIO,
 * iOS=Coil3，鸿蒙=ArkUI 融合渲染平台图片管线；传 [bookSource] 时网络图自动带书源防盗链 header/cookie，
 * 并按原版 Glide 封面/预览链路语义 (isCover=true) 执行书源 coverDecodeJs 响应字节解密)。
 * 多级回退与缓存复用 (对齐原版 PhotoDialog.loadPhoto 的缓存优先语义):
 * ① 进程内阅读页位图缓存 [ReaderImageCache] (阅读时已解码的正文图, 零 IO 直接显示)
 * ② 磁盘章节图片缓存 [BookImageStorage] (网络书阅读时已落盘, 对齐原版 loadPhoto 的
 *    `BookHelp.getImage(book, src)` 分支——章节缓存文件存在即按 2× 屏尺寸解码显示;
 *    需 [chapter] 标识, 阅读页点图调用方随 [showImagePreview] 透传)
 * ③ Coil3 封面/列表图磁盘缓存 (书架封面/列表图刚显示过时复用, 避免双链路重复下载;
 *    仅读缓存不触发网络, 见 [BookImageLoader.loadDiskCachedBytes])
 * ④ 现有 ImageBitmapLoader 链路 (ImageBytesCache 内存/磁盘缓存 → 网络下载+解密;
 *    对齐原版 loadByGlide 的 onlyRetrieveFromCache 优先 + Glide DiskCacheStrategy.DATA;
 *    失败进进程级 failUrl 黑名单, 死链不再反复请求) → ⑤ 失败显示默认封面占位
 * (对齐原版 glide error(BookCover.newDefaultDrawable()) 兜底)。同一 URL 二次打开零重复下载/解密。
 * 注: 原版 loadPhoto 的 EPUB ZIP 本地分支 (FileBook) 仍由各端 ImageBitmapLoader
 * 的 cbz:// 链路承担, app 端 PhotoDialog 保留原版完整链路。
 * 手势复用共享 [zoomable] (双指缩放/单指平移/双击循环/fling 惯性, E-Ink 自动降级)。
 *
 * GIF 动图: desktop 经 [rememberAnimatedImageBitmap] 使用 Skiko Codec 逐帧播放；
 * 其余格式与其他端仍走静态 [ImageBitmapLoader] 路径。
 *
 * 加载中显示 [loadingContent] 占位；加载失败显示默认封面占位图
 * (对齐 app 端 PhotoDialog glide error(BookCover.newDefaultDrawable()) 兜底)。
 *
 * app 端 PhotoDialog 不消费本件: 其加载链含章节缓存文件/EPUB ZIP/SVG/data URI/Coil
 * 磁盘缓存 (Android 专属, KMP 端由各端加载器/磁盘缓存承担)。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径, 各端 actual 支持范围见 ImageBitmapLoader)
 * @param modifier 外层容器 Modifier (默认 wrap; 全屏场景传 fillMaxSize)
 * @param imageModifier 图片 Modifier (默认 fillMaxSize; 对话框场景传定高约束)
 * @param book 当前书籍 (http 场景判断 isLocal 与解密 put("book")), 可空
 * @param bookSource 书源 (网络图防盗链 header/cookie/charset/JS + coverDecodeJs 封面解密), 可空
 * @param chapter 当前章节 (网络书阅读页点图时透传: 磁盘章节图片缓存 [BookImageStorage]
 *   优先链路需要; 非阅读页调用可空, 跳过②直接走 ③ 链路)
 * @param onLongPress 长按回调 (app 端长按保存等场景), 默认无
 * @param loadingContent 加载中占位 (默认 i18n "loading" 文案, 对照原 DesktopPhotoDialog)
 */
@Composable
fun PhotoDialogContent(
    src: String,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    book: Book? = null,
    bookSource: BookSource? = null,
    chapter: BookChapter? = null,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    loadingContent: @Composable () -> Unit = { Text(stringResource(Res.string.loading)) },
) {
    // 解码尺寸上限: 2× 屏幕长边 (对齐原版 PhotoDialog.loadPhoto 的 dm.widthPixels*2 /
    // heightPixels*2 语义, 给 PhotoView 放大留余量; 组合期取一次, 窗口 resize 不重启加载)
    val containerSize = LocalWindowInfo.current.containerSize
    val photoMaxDim = max(containerSize.width, containerSize.height) * 2
    // 三态: 加载中 / 成功 / 失败 (失败走默认封面占位, 对齐原版 PhotoDialog 的 glide error 兜底)
    val photoState by produceState<PhotoLoadState>(
        PhotoLoadState.Loading, src, chapter, book, bookSource
    ) {
        value = loadPhotoState(src, book, bookSource, chapter, photoMaxDim)
    }
    // GIF 动图旁路: 大图查看是唯一值得逐帧播放的场景, 故额外取一次裸字节解码。
    // Desktop 使用 Skiko Codec；Android/iOS/鸿蒙交给平台图片管线，无法逐帧时退化静态图。
    // 非 GIF 字节不进解码器, 静态图仅多一次带缓存的字节读取。
    val gifBytes by produceState<ByteArray?>(null, src, chapter, book, bookSource) {
        value = runCatching {
            loadPhotoBytes(src, book, bookSource, chapter, isCover = true)
        }.getOrNull()?.takeIf { isGifBytes(it) }
    }
    val animatedFrame = rememberAnimatedImageBitmap(gifBytes)
    val successBitmap = (photoState as? PhotoLoadState.Success)?.bitmap
    val image = animatedFrame ?: successBitmap
    val defaultCover = painterResource(Res.drawable.image_cover_default)
    val defaultCoverRatio = remember(defaultCover) {
        val size = defaultCover.intrinsicSize
        if (size.width > 0f && size.height > 0f) size.width / size.height else 1f
    }
    Box(
        // 占位态没有图片可挂 zoomable, 单击/长按要挂到容器上, 否则加载中时全屏
        // 看图层没有任何可点区域, 点不掉也退不出 (对照原 PhotoDialog 点击即关)
        modifier = if (image == null && photoState is PhotoLoadState.Loading) {
            modifier.pointerInput(onTap, onLongPress) {
                detectTapGestures(
                    onTap = onTap?.let { { _: Offset -> it() } },
                    onLongPress = onLongPress?.let { { _: Offset -> it() } },
                )
            }
        } else {
            modifier
        },
        contentAlignment = Alignment.Center,
    ) {
        when (val state = photoState) {
            is PhotoLoadState.Success -> {
                val b = if (animatedFrame != null) animatedFrame else state.bitmap
                Image(
                    bitmap = b,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // clipToBounds 在 zoomable 的 graphicsLayer 之前: 放大后不溢出图片布局边界
                    modifier = imageModifier
                        .clipToBounds()
                        .zoomable(
                            contentAspectRatio = b.width.toFloat() / b.height,
                            onLongPress = onLongPress,
                            onTap = onTap,
                        ),
                )
            }

            PhotoLoadState.Failed -> {
                // 对齐原版 PhotoDialog glide error(BookCover.newDefaultDrawable()):
                // 失败显示默认封面占位, 保持可缩放/可点关闭
                Image(
                    painter = defaultCover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier
                        .clipToBounds()
                        .zoomable(
                            contentAspectRatio = defaultCoverRatio,
                            onLongPress = onLongPress,
                            onTap = onTap,
                        ),
                )
            }

            PhotoLoadState.Loading -> loadingContent()
        }
    }
}

/** 图片加载三态 (失败占位对齐原版 glide error 默认封面)。 */
private sealed interface PhotoLoadState {
    data object Loading : PhotoLoadState
    data class Success(val bitmap: ImageBitmap) : PhotoLoadState
    data object Failed : PhotoLoadState
}

/**
 * 大图加载（缓存优先，对齐原版 PhotoDialog.loadPhoto 的章节缓存文件分支）：
 * ① [ReaderImageCache] 内存位图（阅读页当前书同 URL 已解码，零 IO 直接显示）
 * ② 磁盘字节缓存（[BookImageStorage] 章节缓存 → Coil3 封面/列表图磁盘缓存），
 *    命中后按 2× 屏尺寸采样解码
 * ③ 现有 [ImageBitmapLoader] 链路（ImageBytesCache 内存/磁盘缓存 + 网络下载）
 * 全部失败返回 Failed（默认封面占位，对齐原版 glide error 兜底）。
 */
private suspend fun loadPhotoState(
    src: String,
    book: Book?,
    bookSource: BookSource?,
    chapter: BookChapter?,
    maxDim: Int,
): PhotoLoadState {
    // ① 内存位图（阅读页 2048px 长边上限解码, 对 2× 屏显示无感知差异, 直接复用）
    ReaderImageCache.peek(src)?.let { return PhotoLoadState.Success(it) }
    // ②/③ 字节：磁盘缓存（章节缓存 → Coil3 封面缓存）优先，未命中走
    // ImageBitmapLoader（ImageBytesCache/网络）
    val bytes = loadPhotoBytes(src, book, bookSource, chapter, isCover = true)
        ?: return PhotoLoadState.Failed
    // 栅格解码 → SVG 兜底（对齐原版 decodeBytes ?: SvgUtils.renderInto 语义）
    val bitmap = decodeBytesSampled(bytes, maxDim)
        ?: decodeSvgFallback(bytes, maxDim)
        ?: return PhotoLoadState.Failed
    return PhotoLoadState.Success(bitmap)
}

/**
 * 大图字节获取（缓存优先）：
 * ① 网络书已读过的图：磁盘章节图片缓存 [BookImageStorage]（阅读页 [ReaderImageResolver]
 *    下载时按 book+url 落盘，md5(url) 文件名；chapter 参与接口签名，各端实现路径均只由
 *    book+url 派生，与阅读页取图同源同路径）
 * ② Coil3 封面/列表图磁盘缓存（书架封面/列表图刚显示过时复用，双链路架构下 Coil3 缓存
 *    与自下载链路不共享——避免重新下载；仅读缓存不触发网络，见 [BookImageLoader.loadDiskCachedBytes]）
 * ③ 未命中 → [ImageBitmapLoader]（ImageBytesCache 内存/磁盘缓存 + 网络下载+解密）
 */
private suspend fun loadPhotoBytes(
    src: String,
    book: Book?,
    bookSource: BookSource?,
    chapter: BookChapter?,
    isCover: Boolean,
): ByteArray? {
    if (book != null && chapter != null && !book.isLocal) {
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull()
        val path = storage?.let {
            runCatching { it.getImagePath(book, chapter, src) }.getOrNull()
        }
        if (path != null) {
            FileUtilsCommon.readBytes(path)?.let { return it }
        }
    }
    // Coil3 封面/列表图磁盘缓存（仅读不网络；磁盘 IO 异常回退网络链路）
    BookImageLoaders.getOrNull()?.let { loader ->
        runCatching { loader.loadDiskCachedBytes(src, bookSource?.bookSourceUrl) }
            .getOrNull()?.let { if (it.isNotEmpty()) return it }
    }
    return ImageBitmapLoader().loadBytes(src, book, bookSource, isCover)
}

/**
 * photo overlay payload 编码: "src\u0000chapterIndex"（URL 不含 NUL 字符，安全）。
 * chapterIndex < 0（未知章节，非阅读页调用）时保持裸 src，兼容旧调用方与旧 payload。
 */
fun encodePhotoOverlayPayload(src: String, chapterIndex: Int): String =
    if (chapterIndex < 0) src else "$src\u0000$chapterIndex"

/** 解析 [encodePhotoOverlayPayload] 编码的 payload；兼容裸 src（章节索引返回 -1）。 */
fun decodePhotoOverlayPayload(payload: String): Pair<String, Int> {
    val sep = payload.indexOf('\u0000')
    if (sep < 0) return payload to -1
    return payload.substring(0, sep) to (payload.substring(sep + 1).toIntOrNull() ?: -1)
}

/**
 * 大图查看对话框 (AppAlertDialog 形态, 视觉对照原 desktop DesktopPhotoDialog:
 * 内容区 + "关闭"按钮, 图片区占对话框高 0.8)。desktop/iOS/鸿蒙三端共用;
 * app 端走全屏 DialogFragment 版 [io.legado.app.ui.widget.dialog.PhotoDialog], 不经本件。
 *
 * @param src 图片路径
 * @param onDismiss 关闭回调
 * @param book 当前书籍, 可空 (透传 [PhotoDialogContent])
 * @param bookSource 书源 (网络图防盗链), 可空 (透传 [PhotoDialogContent])
 * @param chapter 当前章节, 可空 (网络书阅读页点图时透传, 磁盘章节缓存优先链路使用)
 */
@Composable
fun PhotoViewDialog(
    src: String,
    onDismiss: () -> Unit,
    book: Book? = null,
    bookSource: BookSource? = null,
    chapter: BookChapter? = null,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        okButton = AlertButton(stringResource(Res.string.close)),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            PhotoDialogContent(
                src = src,
                imageModifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                book = book,
                bookSource = bookSource,
                chapter = chapter,
            )
        }
    }
}

/**
 * 全屏大图 Overlay 的平台承载: 各端 actual 用对应平台的 Dialog 配置让黑色背景
 * 铺满整屏并延伸到系统栏之下 (Android: decorFitsSystemWindows=false;
 * iOS/鸿蒙/桌面: usePlatformInsets=false)。
 */
@Composable
internal expect fun PlatformPhotoOverlayDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)

/**
 * 大图查看 Overlay: 全屏铺满 + 黑色半透明底色, 图片加载和缩放复用 [PhotoDialogContent]。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径 / data URI)
 * @param onDismiss 关闭回调 (返回键 / 单击)
 * @param book 当前书籍, 可空 (透传 [PhotoDialogContent])
 * @param bookSource 书源 (网络图防盗链), 可空 (透传 [PhotoDialogContent])
 * @param chapter 当前章节, 可空 (网络书阅读页点图时透传, 磁盘章节缓存优先链路使用)
 */
@Composable
fun PhotoViewOverlayDialog(
    src: String,
    onDismiss: () -> Unit,
    book: Book? = null,
    bookSource: BookSource? = null,
    chapter: BookChapter? = null,
) {
    PlatformPhotoOverlayDialog(onDismissRequest = onDismiss) {
        PhotoDialogContent(
            src = src,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            imageModifier = Modifier.fillMaxSize(),
            book = book,
            bookSource = bookSource,
            chapter = chapter,
            onLongPress = null,
            onTap = onDismiss,
            loadingContent = { Text(stringResource(Res.string.loading), color = Color.White) },
        )
    }
}
