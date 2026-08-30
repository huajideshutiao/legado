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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.image.decodeBytesSampled
import io.legado.app.help.image.decodeSvgFallback
import io.legado.app.help.image.rememberAnimatedImageBitmap
import io.legado.app.help.toast.Toasters
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.model.defaultCoverDisplayPath
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.bookshelf.defaultCoverEntry
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.NinePatchImageOrImage
import io.legado.app.ui.compose.component.zoomable
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageSaveFileName
import io.legado.app.utils.readAllAndClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.close
import legado.shared.generated.resources.image_cover_default
import legado.shared.generated.resources.loading
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/**
 * 跨平台大图查看内容件 (四端唯一实现; 原 app 端 PhotoDialog DialogFragment 已删,
 * Android 的图片预览与其他端一样走 key="photo" overlay → [PhotoViewOverlayDialog])。
 *
 * 图片加载走 [ImageBitmapLoader] (commonMain 面, 各端 actual: jvm=OkHttp+ImageIO,
 * iOS=Coil3，鸿蒙=ArkUI 融合渲染平台图片管线；传 [bookSource] 时网络图自动带书源防盗链 header/cookie，
 * 并按原版 Glide 封面/预览链路语义 (isCover=true) 执行书源 coverDecodeJs 响应字节解密)。
 * 多级回退与缓存复用 (对齐原版 PhotoDialog.loadPhoto 的缓存优先语义):
 * ① 进程内阅读页位图缓存 [ReaderImageCache] (阅读时已解码的正文图, 免解码直接显示)
 * ② 磁盘章节图片缓存 [BookImageStorage] (网络书阅读时已落盘, 对齐原版 loadPhoto 的
 *    `BookHelp.getImage(book, src)` 分支——章节缓存文件存在即按 2× 屏尺寸解码显示;
 *    需 [chapter] 标识, 阅读页点图调用方随 [showImagePreview] 透传)
 * ③ Coil3 封面/列表图磁盘缓存 (书架封面/列表图刚显示过时复用, 避免双链路重复下载;
 *    仅读缓存不触发网络, 见 [BookImageLoader.loadDiskCachedBytes])
 * ④ 现有 ImageBitmapLoader 链路 (ImageBytesCache 内存/磁盘缓存 → 网络下载+解密;
 *    对齐原版 loadByGlide 的 onlyRetrieveFromCache 优先 + Glide DiskCacheStrategy.DATA;
 *    失败进进程级 failUrl 黑名单, 死链不再反复请求) → ⑤ 失败显示默认封面占位
 * (对齐原版 glide error(BookCover.newDefaultDrawable()) 兜底)。同一 URL 二次打开零重复下载/解密。
 * 注: 原版 loadPhoto 的 EPUB 本地分支 (FileBook) 已下沉进本件字节链 (章节缓存之后、
 * Coil3 磁盘缓存之前)。
 * 手势复用共享 [zoomable] (双指缩放/单指平移/双击循环/fling 惯性, E-Ink 自动降级)。
 *
 * GIF/WebP 动图: desktop/iOS/鸿蒙经 [rememberAnimatedImageBitmap] 使用 Skia Codec 逐帧播放；
 * Android 经 Coil3 `AnimatedImageDecoder`/`GifDecoder` 播放。
 *
 * 加载中显示 [loadingContent] 占位；加载失败显示默认封面占位图
 * (对齐原版 PhotoDialog glide error(BookCover.newDefaultDrawable()) 兜底)。
 *
 * 原 Android 专属的四条分支 (章节缓存文件/EPUB/SVG/data URI/Coil 磁盘缓存) 都已在本件
 * 字节链内, 故 app 端不再需要自己那份加载/手势实现。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径, 各端 actual 支持范围见 ImageBitmapLoader)
 * @param modifier 外层容器 Modifier (默认 wrap; 全屏场景传 fillMaxSize)
 * @param imageModifier 图片 Modifier (默认 fillMaxSize; 对话框场景传定高约束)
 * @param book 当前书籍 (http 场景判断 isLocal 与解密 put("book")), 可空
 * @param bookSource 书源 (网络图防盗链 header/cookie/charset/JS + coverDecodeJs 封面解密), 可空
 * @param chapter 当前章节 (网络书阅读页点图时透传: 磁盘章节图片缓存 [BookImageStorage]
 *   优先链路需要; 非阅读页调用可空, 跳过②直接走 ③ 链路)
 * @param onLongPress 长按回调 (app 端长按保存等场景), 默认无
 * @param onTap 单击回调 (全屏看图单击关闭): 加载中占位与图片区都挂, 图没出来也点得掉
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
    // 动图: 原始字节在 loadPhotoState 单次读取时顺带获取 (见 [PhotoLoadState.Success.rawBytes])
    val successState = photoState as? PhotoLoadState.Success
    val animatedFrame = rememberAnimatedImageBitmap(successState?.rawBytes)
    val successBitmap = successState?.bitmap
    val image = animatedFrame ?: successBitmap
    val defaultCover = painterResource(Res.drawable.image_cover_default)
    val defaultCoverRatio = remember(defaultCover) {
        val size = defaultCover.intrinsicSize
        if (size.width > 0f && size.height > 0f) size.width / size.height else 1f
    }
    // 回调用 rememberUpdatedState 持住: 调用方 (PhotoViewOverlayDialog / LegadoApp) 内联传
    // lambda, 每次重组都是新实例, 直接做 pointerInput key 会反复重启手势检测。
    // key 只留"回调是否存在" (决定 detectTapGestures 要不要等长按超时), 引用变化不重启。
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val haptic = LocalHapticFeedback.current
    val hasTap = onTap != null
    val hasLongPress = onLongPress != null
    Box(
        // 占位态没有图片可挂 zoomable, 单击/长按要挂到容器上, 否则加载中时全屏
        // 看图层没有任何可点区域, 点不掉也退不出 (对照原 PhotoDialog 点击即关)
        modifier = if (image == null && photoState is PhotoLoadState.Loading) {
            modifier.pointerInput(hasTap, hasLongPress) {
                detectTapGestures(
                    onTap = if (hasTap) {
                        { _: Offset -> currentTap?.invoke() }
                    } else null,
                    onLongPress = if (hasLongPress) {
                        { _: Offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentLongPress?.invoke()
                        }
                    } else null,
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

            is PhotoLoadState.Failed -> {
                // 对齐原版 PhotoDialog glide error(BookCover.newDefaultDrawable()):
                // 用户自定义默认封面集优先 (含 .9 图九宫格拉伸), 空集回落内置占位图;
                // 两者都保持可缩放/可点关闭
                val cover = state.cover
                // .9 图拉伸铺满容器, 钳制按容器算 (传 null); 普通图按位图宽高比
                val placeholderRatio = when {
                    cover == null -> defaultCoverRatio
                    state.coverNinePatch -> null
                    else -> cover.width.toFloat() / cover.height
                }
                val placeholderModifier = imageModifier
                    .clipToBounds()
                    .zoomable(
                        contentAspectRatio = placeholderRatio,
                        onLongPress = onLongPress,
                        onTap = onTap,
                    )
                if (cover != null) {
                    NinePatchImageOrImage(
                        bitmap = cover,
                        isNinePatch = state.coverNinePatch,
                        modifier = placeholderModifier,
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Image(
                        painter = defaultCover,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = placeholderModifier,
                    )
                }
            }

            PhotoLoadState.Loading -> loadingContent()
        }
    }
}

/** 图片加载三态 (失败占位对齐原版 glide error 默认封面)。 */
private sealed interface PhotoLoadState {
    data object Loading : PhotoLoadState

    /** @param rawBytes 原始图片字节，供 [rememberAnimatedImageBitmap] 判定并逐帧播放动图 */
    data class Success(val bitmap: ImageBitmap, val rawBytes: ByteArray?) : PhotoLoadState
    /** @param cover 用户自定义默认封面集选出的位图 (null = 图集为空/缺文件, 用内置占位图) */
    data class Failed(
        val cover: ImageBitmap?,
        val coverNinePatch: Boolean = false,
    ) : PhotoLoadState
}

/**
 * 大图加载（缓存优先，对齐原版 PhotoDialog.loadPhoto 的章节缓存文件分支）：
 * ① [ReaderImageCache] 内存位图（阅读页当前书同 URL 已解码，直接复用免解码）
 * ② 磁盘字节缓存（[BookImageStorage] 章节缓存 → Coil3 封面/列表图磁盘缓存），
 *    命中后按 2× 屏尺寸采样解码
 * ③ 现有 [ImageBitmapLoader] 链路（ImageBytesCache 内存/磁盘缓存 + 网络下载）
 * 全部失败走 [defaultCoverState]（用户自定义默认封面集优先，空集回落内置占位图，
 * 对齐原版 glide error(newDefaultDrawable()) 兜底）。
 *
 * 整链在 [IoDispatcher] 上跑: 磁盘读/文件 stat/解码都是阻塞的, 缓存命中时若留在
 * produceState 的组合效应上下文 (主线程) 就是主线程 IO + 解码。
 */
private suspend fun loadPhotoState(
    src: String,
    book: Book?,
    bookSource: BookSource?,
    chapter: BookChapter?,
    maxDim: Int,
): PhotoLoadState = withContext(IoDispatcher) {
    // ① 内存位图（阅读页 2048px 长边上限解码, 对 2× 屏显示无感知差异, 直接复用）
    val cached = ReaderImageCache.peek(src)
    // ②/③ 字节：磁盘缓存（章节缓存 → Coil3 封面缓存）优先，未命中走
    // ImageBitmapLoader（ImageBytesCache/网络）。
    val bytes = runCatching {
        loadPhotoBytes(src, book, bookSource, chapter, isCover = true)
    }.getOrNull()
    // 内存位图命中时跳过解码, 但仍用上面读到的字节供动图播放 (阅读页缓存只存单帧)
    if (cached != null) return@withContext PhotoLoadState.Success(cached, bytes)
    if (bytes == null) return@withContext defaultCoverState(maxDim)
    // 栅格解码 → SVG 兜底（对齐原版 decodeBytes ?: SvgUtils.renderInto 语义）
    val bitmap = decodeBytesSampled(bytes, maxDim)
        ?: decodeSvgFallback(bytes, maxDim)
        ?: return@withContext defaultCoverState(maxDim)
    PhotoLoadState.Success(bitmap, bytes)
}

/**
 * 加载彻底失败的占位 (对齐原版 glide error(BookCover.newDefaultDrawable())): 用户自定义
 * 默认封面集按 NOVEL 比例选一张 (seed=null → 随机, 同原版 newDefaultDrawable());
 * 图集为空、文件缺失或解码失败回落内置 image_cover_default (cover=null)。
 */
private fun defaultCoverState(maxDim: Int): PhotoLoadState.Failed {
    // 选图与路径推导同书架封面链 (BookshelfScreen.loadDefault): entry 版才带 ninePatch 标记
    val picked = runCatching {
        val entry = defaultCoverEntry(null, CoverRatio.NOVEL) ?: return@runCatching null
        entry to defaultCoverDisplayPath(entry, CoverRatio.NOVEL)
    }.getOrNull() ?: return PhotoLoadState.Failed(null)
    val bitmap = FileUtilsCommon.readBytes(picked.second)?.let { decodeBytesSampled(it, maxDim) }
        ?: return PhotoLoadState.Failed(null)
    return PhotoLoadState.Failed(bitmap, picked.first.ninePatch)
}

/**
 * 大图字节获取（缓存优先）：
 * ① 网络书已读过的图：磁盘章节图片缓存 [BookImageStorage]（阅读页 [ReaderImageResolver]
 *    下载时按 book+url 落盘，md5(url) 文件名；chapter 参与接口签名，各端实现路径均只由
 *    book+url 派生，与阅读页取图同源同路径）
 * ② 本地 EPUB 内嵌图：[FileBook.getImage]（包内裸 href 无 scheme，须先于 ImageBitmapLoader）
 * ③ Coil3 封面/列表图磁盘缓存（书架封面/列表图刚显示过时复用，双链路架构下 Coil3 缓存
 *    与自下载链路不共享——避免重新下载；仅读缓存不触发网络，见 [BookImageLoader.loadDiskCachedBytes]）
 * ④ 未命中 → [ImageBitmapLoader]（ImageBytesCache 内存/磁盘缓存 + 网络下载+解密）
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
    // 本地 EPUB 内嵌图 (下沉原 app 端 PhotoDialog 的 FileBook 分支): href 是包内裸路径,
    // 无 scheme, ImageBitmapLoader 认不出, 必须先于其兜底
    if (book != null && book.isEpub) {
        runCatching { FileBook.getImage(book, src)?.readAllAndClose() }
            .getOrNull()?.let { if (it.isNotEmpty()) return it }
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

@Composable
private fun rememberPhotoSaveAction(
    src: String,
    book: Book?,
    bookSource: BookSource?,
    chapter: BookChapter?,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch(IoDispatcher) {
            val bytes = loadPhotoBytes(src, book, bookSource, chapter, isCover = true)
                ?: run {
                    Toasters.get().toast("保存图片失败")
                    return@launch
                }
            val files = PlatformServiceProviders.getOrNull()?.files
            if (files == null) {
                Toasters.get().toast("保存图片失败")
                return@launch
            }
            // 实际字节决定扩展名；用户取消选目录 (null) 静默返回。
            when (files.saveImageRememberingDir(imageSaveFileName(src, bytes), bytes)) {
                true -> Toasters.get().toast("保存成功")
                false -> Toasters.get().toast("保存图片失败")
                null -> Unit
            }
        }
    }
}

/**
 * 大图查看对话框 (AppAlertDialog 形态, 视觉对照原 desktop DesktopPhotoDialog:
 * 内容区 + "关闭"按钮, 图片区占对话框高 0.8)。desktop/iOS/鸿蒙三端共用;
 * 全屏看图 (含 Android) 走 [PhotoViewOverlayDialog], 不经本件。
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
    val saveImage = rememberPhotoSaveAction(src, book, bookSource, chapter)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        okButton = AlertButton(stringResource(Res.string.close)),
    ) {
        Column(Modifier.padding(horizontal = DesignTokens.spacingDefault)) {
            PhotoDialogContent(
                src = src,
                imageModifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                book = book,
                bookSource = bookSource,
                chapter = chapter,
                onLongPress = saveImage,
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
 * 内容区支持前置占位 [placeholder]: 占位与图片内容共用同一对话框实例, 状态就绪后
 * 只换内容不重建窗口, 对话框进入动画只播一次 (书源身份查询完成时若销毁重建对话框
 * 会重播 AppDialog 进入动画, 表现为二次闪烁)。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径 / data URI)
 * @param onDismiss 关闭回调 (返回键 / 单击)
 * @param book 当前书籍, 可空 (透传 [PhotoDialogContent])
 * @param bookSource 书源 (网络图防盗链), 可空 (透传 [PhotoDialogContent])
 * @param chapter 当前章节, 可空 (网络书阅读页点图时透传, 磁盘章节缓存优先链路使用)
 * @param placeholder 图片内容就绪前的占位内容 (如书源查询中), 传 null 直接显示图片
 */
@Composable
fun PhotoViewOverlayDialog(
    src: String,
    onDismiss: () -> Unit,
    book: Book? = null,
    bookSource: BookSource? = null,
    chapter: BookChapter? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val saveImage = rememberPhotoSaveAction(src, book, bookSource, chapter)
    PlatformPhotoOverlayDialog(onDismissRequest = onDismiss) {
        if (placeholder != null) {
            placeholder()
        } else {
            PhotoDialogContent(
                src = src,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                imageModifier = Modifier.fillMaxSize(),
                book = book,
                bookSource = bookSource,
                chapter = chapter,
                onLongPress = saveImage,
                onTap = onDismiss,
                loadingContent = { Text(stringResource(Res.string.loading), color = Color.White) },
            )
        }
    }
}
