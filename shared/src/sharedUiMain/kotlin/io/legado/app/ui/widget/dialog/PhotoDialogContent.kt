package io.legado.app.ui.widget.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.legado.app.help.image.DecodedBitmapCache
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
import io.legado.app.ui.compose.component.NinePatchImageOrImage
import io.legado.app.ui.compose.component.zoomable
import io.legado.app.ui.compose.platform.BackLayerHandler
import io.legado.app.ui.root.LocalPhotoSharedState
import io.legado.app.ui.root.LocalSharedTransitionEnabled
import io.legado.app.ui.root.LocalSharedTransitionScope
import io.legado.app.ui.root.PhotoSharedBoundsDurationMillis
import io.legado.app.ui.root.PhotoSharedContentFade
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.imageSaveFileName
import io.legado.app.ui.root.photoSharedTarget
import io.legado.app.utils.readAllAndClose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import legado.shared.generated.resources.Res
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
 *    需 [chapter] 标识, 阅读页点图调用方随 [encodePhotoOverlayPayload] 透传)
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
 * @param loadState 图片加载三态, 由调用方持有 (查看器不等它起飞: 首帧由 [rememberPhotoLoadState]
 *   的三级同步兜底保证有图可飞, 见那边的注释)
 * @param modifier 外层容器 Modifier (默认 wrap; 全屏场景传 fillMaxSize)
 * @param imageModifier 图片 Modifier (默认 fillMaxSize)
 * @param onLongPress 长按回调 (app 端长按保存等场景), 默认无
 * @param onTap 单击回调 (全屏看图单击关闭): 加载中占位与图片区都挂, 图没出来也点得掉
 * @param loadingContent 加载中占位 (默认 i18n "loading" 文案, 对照原 DesktopPhotoDialog)
 */
@Composable
internal fun PhotoDialogContent(
    loadState: PhotoLoadState,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    loadingContent: @Composable () -> Unit = { Text(stringResource(Res.string.loading)) },
) {
    // 动图: 原始字节在 loadPhotoState 单次读取时顺带获取 (见 [PhotoLoadState.Success.rawBytes])
    val successState = loadState as? PhotoLoadState.Success
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
        modifier = if (image == null && loadState is PhotoLoadState.Loading) {
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
        when (loadState) {
            is PhotoLoadState.Success -> {
                val b = animatedFrame ?: loadState.bitmap
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
                val cover = loadState.cover
                // .9 图拉伸铺满容器, 钳制按容器算 (传 null); 普通图按位图宽高比
                val placeholderRatio = when {
                    cover == null -> defaultCoverRatio
                    loadState.coverNinePatch -> null
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
                        isNinePatch = loadState.coverNinePatch,
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
internal sealed interface PhotoLoadState {
    data object Loading : PhotoLoadState

    /** @param rawBytes 原始图片字节，供 [rememberAnimatedImageBitmap] 判定并逐帧播放动图 */
    data class Success(val bitmap: ImageBitmap, val rawBytes: ByteArray?) : PhotoLoadState {
        /**
         * 手写 equals/hashCode: data class 生成版对 [rawBytes] 是数组引用比较 (既会把
         * "同字节重复解码"误判为不等, 也让 hash 不稳定), 故按内容重算。
         * 写成显式判空形式: 不依赖可空接收者的 contentEquals/contentHashCode 重载。
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            if (bitmap != other.bitmap) return false
            val a = rawBytes
            val b = other.rawBytes
            if ((a == null) != (b == null)) return false
            if (a != null && !a.contentEquals(b)) return false
            return true
        }

        override fun hashCode(): Int {
            val bytes = rawBytes
            return 31 * bitmap.hashCode() + (bytes?.contentHashCode() ?: 0)
        }
    }
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
    if (cached != null) {
        cachePhotoBitmap(src, bookSource, cached)
        return@withContext PhotoLoadState.Success(cached, bytes)
    }
    if (bytes == null) return@withContext defaultCoverState(maxDim)
    // 栅格解码 → SVG 兜底（对齐原版 decodeBytes ?: SvgUtils.renderInto 语义）
    val bitmap = decodeBytesSampled(bytes, maxDim)
        ?: decodeSvgFallback(bytes, maxDim)
        ?: return@withContext defaultCoverState(maxDim)
    cachePhotoBitmap(src, bookSource, bitmap)
    PhotoLoadState.Success(bitmap, bytes)
}

/**
 * 大图解码结果回填 [DecodedBitmapCache] 主表。
 *
 * 主表原先只有 `ImageBitmapLoader.loadBitmap` 会写, 而大图走的是 loadBytes + decodeBytesSampled
 * (只解码、不写表), 真封面又只进封面小表 ([DecodedBitmapCache.recordCover]) —— 于是查看器"首帧同步
 * 取回现成位图"的 [DecodedBitmapCache.findByUrl] 在封面链上是死路, 连同一张图第二次打开都不命中。
 * 回填后二次打开才是真的 0 延迟起飞。
 */
private fun cachePhotoBitmap(src: String, bookSource: BookSource?, bitmap: ImageBitmap) {
    DecodedBitmapCache.put(
        DecodedBitmapCache.cacheKey(src, bookSource?.bookSourceUrl, isCover = true),
        bitmap,
    )
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
            val files = PlatformServiceProviders.get().files
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
 * 大图加载态 (原本写在 [PhotoDialogContent] 里, 提出来是因为查看器要按它决定"什么时候起飞"):
 * 未就绪前不能接管共享元素, 否则飞出去的是占位内容, 就绪那一刻又在飞行途中把内容换掉
 * —— 这正是"图闪一下才完整"的成因。
 *
 * @param request false = 前置信息未就绪 (书源身份查询中), 不发起加载, 避免以无书源状态先裸 GET
 */
@Composable
private fun rememberPhotoLoadState(
    src: String,
    book: Book?,
    bookSource: BookSource?,
    chapter: BookChapter?,
    request: Boolean,
): PhotoLoadState {
    if (!request) return PhotoLoadState.Loading
    // 解码尺寸上限: 2× 屏幕长边 (对齐原版 PhotoDialog.loadPhoto 的 dm.widthPixels*2 /
    // heightPixels*2 语义, 给放大留余量; 组合期取一次, 窗口 resize 不重启加载)
    val containerSize = LocalWindowInfo.current.containerSize
    val photoMaxDim = max(containerSize.width, containerSize.height) * 2
    // 首帧尝试同步命中内存中的位图 (阅读页 Peek → 大图主缓存 → 封面小表):
    // 命中时首帧即为 Success 态, 共享元素直接携带真实图像零延迟起飞, 彻底杜绝起飞前顿挫与微闪。
    // 第三档 [DecodedBitmapCache.peekCover] 是按封面/列表尺寸解的糊图, 只用来撑住接管那一帧 (官方
    // sharedBounds 把内容按终态布局整体缩放到动画盒, 飞行期间看不出分辨率), 高清图就绪后替换。
    val initialCached = remember(src) {
        ReaderImageCache.peek(src)
            ?: DecodedBitmapCache.findByUrl(src)
            ?: DecodedBitmapCache.peekCover(src)
    }
    val initialValue = remember(initialCached) {
        if (initialCached != null) PhotoLoadState.Success(initialCached, null)
        else PhotoLoadState.Loading
    }
    // 三态: 加载中 / 成功 / 失败 (失败走默认封面占位, 对齐原版 PhotoDialog 的 glide error 兜底)
    // key 只能位置传: produceState 的重载是 key1/key2/key3 + vararg keys, 具名 key4 对 vararg 非法
    return produceState<PhotoLoadState>(initialValue, src, chapter, book, bookSource) {
        value = loadPhotoState(src, book, bookSource, chapter, photoMaxDim)
    }.value
}

/**
 * 全屏大图查看 Overlay: **主窗口内**的全屏覆盖层 (黑色半透明底 + 缩放复用 [PhotoDialogContent])。
 *
 * 共享元素走官方 androidx.compose.animation 的 SharedTransition: 源封面与本查看器是
 * [photoSharedViewerKey] 同一 key 的两个端点, 谁接管由 [PhotoSharedState.viewerToken] 一处决定;
 * 翻转那一帧起官方把内容提升到 SharedTransitionLayout 的覆盖层飞完整段。这一对用官方 sharedBounds
 * (不是 sharedElement): 两端内容视觉上不等价 (屏级 Fit 大图 vs 格级 Crop 小封面), 而 sharedElement
 * 只绘制"报称可见"那一端、且逐帧按动画尺寸重排它, 于是退场会先把封面那份放大到满屏再缩回,
 * 并在交接帧留下两份都不画的空窗; sharedBounds 两端都绘制, 内容按终态尺寸布局一次再整体缩放
 * (理由与参数详见 [photoSharedTarget] / [PhotoSharedCoverHost])。
 * 所以这里没有"上报源矩形 + 手工 offset/size 插值 + 两端互补 alpha"那套轮子 —— 它的空窗帧
 * (源已隐、副本还没出现)、飞行途中飞 loading、末端跳变 (终态盒按封面宽高比算, 与 p=1 之后的
 * 真实 Fit 布局不同源) 都是结构性的, 补不干净。
 *
 * 为什么必须在主窗口: 官方两端点靠同一棵 layout 树的 lookahead 坐标换算目标位, 独立 Dialog
 * 窗口是另一条 LayoutNode 树 (跨树换算在 compose-ui 里直接抛 "layouts are not part of the
 * same hierarchy")。这也是旧实现要手工减 LocalOverlayTopInset 补坐标、且窗口自身淡入会与
 * 本动画叠加的根因 —— 换成同窗口后这两件事都不存在。
 *
 * @param src 图片路径 (http(s):// / file:// / 绝对路径 / data URI), 同时作为共享元素的 key
 * @param onDismiss 关闭回调 (返回键 / 单击)
 * @param book 当前书籍 (加载链判 isLocal/解密/章节缓存定位), 可空
 * @param bookSource 书源 (网络图防盗链), 可空
 * @param chapter 当前章节, 可空 (网络书阅读页点图时透传, 磁盘章节缓存优先链路使用)
 * @param placeholder 前置信息未就绪时的占位 (书源查询中); null = 可以直接发起图片加载
 */
@Composable
fun PhotoViewOverlayDialog(
    src: String,
    onDismiss: () -> Unit,
    book: Book? = null,
    bookSource: BookSource? = null,
    chapter: BookChapter? = null,
    placeholder: (@Composable () -> Unit)? = null,
    photoToken: String? = null,
) {
    val saveImage = rememberPhotoSaveAction(src, book, bookSource, chapter)
    val scope = LocalSharedTransitionScope.current
    val enabled = LocalSharedTransitionEnabled.current
    val photoShared = LocalPhotoSharedState.current
    // 书源身份未就绪时先不发起加载 (避免无书源裸 GET: 进黑名单/写脏缓存)
    val loadState = rememberPhotoLoadState(src, book, bookSource, chapter, placeholder == null)
    var closing by remember { mutableStateOf(false) }
    // 开关开 + 在作用域内 + 这次真的有一份源封面端点 (token 由发起方页面自签并随 overlay 带过来),
    // 才有共享转场; 阅读页内联图、验证码图等没有源端点, 本来就不该有动画, 保持原行为立即显示,
    // 不因门控变成"点了没反应"
    val shareable = enabled && scope != null && photoToken != null
    // 唯一可见性口径: 源封面与查看器都只读 photoShared.viewerToken (两端各自再算一份必然错帧,
    // 错帧就会两端同时报称可见 → 官方按"缺 target"处理, 一个都不动画), 写只在下面的 effect 里
    val taken = photoToken != null && photoShared.viewerToken == photoToken

    // key 只留真正决定"进入时是否接管"的项: shareable 在 body 内没用到; placeholder 是调用点
    // 内联 lambda (每次父重组都是新实例), 拿它当 key 会让 effect 白白重启写同值
    LaunchedEffect(closing, photoToken, placeholder == null) {
        if (closing || photoToken == null) return@LaunchedEffect
        // 进入时不等后台大图解码完成就接管共享元素: 首帧的图由 rememberPhotoLoadState 的三级同步兜底
        // (阅读页位图 → 大图主缓存 → 封面小表) 保证, 所以既不用等也不会空一帧; 高清图就绪后中途替换。
        // 前置占位 (书源身份查询中) 时先不接管
        if (placeholder == null) photoShared.viewerToken = photoToken
    }
    // 离开组合一律归还让位 (关闭 / 被替换 / 中途关开关), 不让源封面永久隐身
    DisposableEffect(photoToken) {
        onDispose { if (photoShared.viewerToken == photoToken) photoShared.viewerToken = null }
    }

    val requestDismiss: () -> Unit = { closing = true }
    // 返回键/ESC 必须走"先播退场飞行、飞完再卸载"这条路: 同窗口覆盖层不再拥有独立窗口的
    // 返回拦截, 故本层用与 AppDialog 同一套 BackLayerHandler 抢在根之前接键 (见 BackKeyHandler.kt)。
    // 退场期间 (closing=true) 也保持接键: 漏给根处理器会走 dismissTopOverlay 把回飞硬切掉
    val windowController = remember { PlatformServiceProviders.get().window }
    BackLayerHandler(enabled = true) { requestDismiss() }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        photoShared.viewerToken = null
        // shareable 成立即蕴含 scope 非空 (见其定义), K2 会把这一事实带入下面的块与 lambda
        // (局部 val 的智能转换不因进 lambda 而失效) —— 不需要再取一份局部引用判空
        if (shareable) {
            // 退场以官方转场状态为准, 不用定长 delay 近似 (慢帧下会提前把回飞与蒙版渐变硬切掉)。
            // 信号是作用域级的, 刚置 viewerToken=null 时可能还是 false (尚未起飞), 故先等它变 true
            // 再等它变 false。上限只比飞行时长多留一半: Overlay 未卸载期间仍会吃掉点击,
            // 源封面中途被回收 (LazyGrid 滚出/刷新/旋屏) 导致信号永不到来时不能多挡太久
            withTimeoutOrNull(PhotoSharedBoundsDurationMillis.toLong() * 3 / 2) {
                snapshotFlow { scope.isTransitionActive }.first { it }
                snapshotFlow { scope.isTransitionActive }.first { !it }
            }
        }
        windowController.setLightIconOverlay(false)
        onDismiss()
    }

    // 深色蒙版期间把系统栏图标改白: 原实现靠独立 Dialog 窗口自己的 insetsController,
    // 同窗口覆盖层必须由主窗口代管。只在真正接管后开、离开组合时归还 ——
    // 未就绪的等待期里页面还是普通亮底, 提前改白会让状态栏图标看不见
    LaunchedEffect(taken) {
        if (taken) windowController.setLightIconOverlay(true)
    }
    DisposableEffect(windowController) {
        onDispose { windowController.setLightIconOverlay(false) }
    }

    // 蒙版与飞行同时长同源: targetValue 直接由 taken (= viewerToken 是我) 推, 不再另算就绪条件
    val scrimAlpha by animateFloatAsState(
        targetValue = if (taken) 0.6f else 0f,
        // 共享路径与飞行同时长同曲线; 非共享路径保持旧的"直接就位"(旧实现 progress 初值即 1f)
        animationSpec = if (shareable) {
            tween(durationMillis = PhotoSharedBoundsDurationMillis, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "photoOverlayScrim",
    )

    // 主窗口内的全屏层: 页栈与 Overlay 栈同在 SharedTransitionLayout 内 (见 LegadoApp),
    // 因此不需要任何跨窗口坐标补偿, 也没有第二层窗口入场动画与飞行叠加
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { requestDismiss() })
            }
    ) {
        // 大图端 = "大图↔封面" 共享对的进入/退出端, 进出由 taken 驱动官方 sharedBounds (见
        // [photoSharedTarget]): 两端内容在同一个动画盒里交叉淡化, 离场那份不会因为"不再报称可见"
        // 而提前停绘。没有源封面端点 (阅读页内联图 / 验证码图 / 总闸已关) 时本层就是普通全屏图,
        // 恒显示、不做淡变 —— 与挂不上共享修饰符时的旧行为一致。
        AnimatedVisibility(
            visible = taken || !shareable,
            modifier = Modifier.fillMaxSize(),
            enter = if (shareable) fadeIn(PhotoSharedContentFade) else EnterTransition.None,
            exit = if (shareable) fadeOut(PhotoSharedContentFade) else ExitTransition.None,
            label = "photoViewerSharedTarget",
        ) {
            val viewerVisibilityScope: AnimatedVisibilityScope = this
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // 没有源封面端点时不挂共享节点 (与源侧“不参与”同一口径)
                        photoToken?.let {
                            Modifier.photoSharedTarget(
                                photoToken = it,
                                animatedVisibilityScope = viewerVisibilityScope,
                                // 大图浮在页栈与其它共享元素之上 (飞行途中要盖住页面内容)
                                zIndexInOverlay = 1f,
                            )
                        } ?: Modifier
                    ),
            ) {
                if (placeholder != null) {
                    // 前置信息未就绪 (书源身份查询中): 不发起加载, 也不参与飞行 ——
                    // 有源封面时本层还没接管 (屏幕上仍是封面), 无源封面时这就是原来的黑底+loading
                    placeholder()
                } else {
                    PhotoDialogContent(
                        loadState = loadState,
                        modifier = Modifier.fillMaxSize(),
                        imageModifier = Modifier.fillMaxSize(),
                        onLongPress = saveImage,
                        onTap = requestDismiss,
                        loadingContent = {
                            Text(stringResource(Res.string.loading), color = Color.White)
                        },
                    )
                }
            }
        }
    }
}

