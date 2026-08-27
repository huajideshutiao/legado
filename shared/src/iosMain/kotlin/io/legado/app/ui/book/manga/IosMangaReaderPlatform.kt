package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.PreferKey
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.MangaImageBytesLoader
import io.legado.app.model.manga.MangaModel
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext

// iOS 漫画阅读平台能力: 图片流复用 shared 提取器, 渲染走 Coil3 (对照 DesktopMangaReaderPlatform)
object IosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    private val prefs get() = PreferenceProviders.get()

    // 启动即读持久化配置 (对照原版 ReadMangaActivity 直读 AppConfig; 与 desktop 同 key 同默认值,
    // 菜单写入经 toggle/update* 走 PreferenceProviders 持久化)
    override val config: MangaReaderConfig
        get() = MangaReaderConfig(
            hideMangaTitle = prefs.getBoolean(PreferKey.hideMangaTitle, false),
            preDownloadNum = prefs.getInt(PreferKey.mangaPreDownloadNum, 10),
            syncBookProgressPlus = prefs.getBoolean(PreferKey.syncBookProgressPlus, false),
            horizontal = prefs.getBoolean(PreferKey.enableMangaHorizontalScroll, false),
            // 默认 3: 对齐 app 端 AppConfig.mangaAutoPageSpeed (0 会让定时翻页退化成空转)
            autoPageSpeed = prefs.getInt(PreferKey.mangaAutoPageSpeed, 3),
            grayEnabled = prefs.getBoolean(PreferKey.enableMangaGray, false),
            colorFilterConfig = runCatching {
                GSON.fromJsonObject<MangaColorFilterConfig>(
                    prefs.getString(PreferKey.mangaColorFilter, "")
                ).getOrNull()
            }.getOrNull() ?: MangaColorFilterConfig(),
            gifAutoNext = prefs.getBoolean(PreferKey.enableMangaGifAutoNext, false),
            disablePageAnim = prefs.getBoolean(PreferKey.disableMangaPageAnim, false),
            footerConfig = runCatching {
                GSON.fromJsonObject<MangaFooterConfig>(
                    prefs.getString(PreferKey.mangaFooterConfig, "")
                ).getOrNull()
            }.getOrNull() ?: MangaFooterConfig(),
        )

    // 图片 URL 提取: 复用 commonMain 的 MangaImageExtractorShared (与 desktop 同源)
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        MangaImageExtractorShared.extractImageUrls(content).asFlow()

    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
        colorFilterConfig: MangaColorFilterConfig,
        grayEnabled: Boolean,
        onLoadState: (MangaCellState) -> Unit,
        retryTick: Int,
        onProgress: (String) -> Unit,
    ) {
        // 内部重试计数保留原有"重新加载"按钮; shared 单元格的 retryTick 变化同样重建 ImageRequest
        var internalRetryTick by remember(url) { mutableStateOf(0) }
        val request = remember(url, book, source, internalRetryTick, retryTick) {
            ImageRequest.Builder(PlatformContext.INSTANCE)
                // MangaModel 走 MangaModelFetcher: 图片缓存 + AnalyzeUrl(防盗链 header) + 解密,
                // 裸 url 直连对需要处理的源必然全失败
                .data(book?.let { MangaModel(url, it, source) })
                // 磁盘缓存由 MangaImageBytesLoader 自管, 内存缓存保留
                .memoryCacheKey(url)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
        }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        // 合并颜色滤镜: colorFilterConfig 矩阵 + 灰度矩阵 (与 desktop/app 端同矩阵同顺序)
        val colorFilter = remember(colorFilterConfig, grayEnabled) {
            mangaColorFilter(colorFilterConfig, grayEnabled)
        }
        // 上报加载状态给 shared 单元格 (对齐 app 端 onStateChange, 失败时单元格显示"重新加载")
        LaunchedEffect(state) {
            onLoadState(
                when (state) {
                    is AsyncImagePainter.State.Success -> MangaCellState.SUCCESS
                    is AsyncImagePainter.State.Error -> MangaCellState.ERROR
                    else -> MangaCellState.LOADING
                }
            )
        }
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    // 等比渲染: 按图片固有宽高比显式定高 (与 desktop 端同一修复,
                    // 纵向永不变形; 横向仍整页铺满视口等比留白, 行为不变)
                    val intrinsic = painter.intrinsicSize
                    val aspect =
                        if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                            Modifier.aspectRatio(intrinsic.width / intrinsic.height)
                        } else {
                            Modifier
                        }
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = if (horizontal) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth().then(aspect)
                        },
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                    )
                }

                // 失败/加载中占位由 shared 单元格覆盖层统一展示, 此处保留兜底
                is AsyncImagePainter.State.Error -> Text(
                    text = "重新加载",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { internalRetryTick++ }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                else -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }

    // 保存图片到沙盒导出路径 (destPath 由 shared 路由经 PlatformServiceProviders.files.saveFile
    // 取得, iOS 的 saveFile 返回 Documents/export 可写路径): 取字节复用 [MangaImageBytesLoader]
    // (图片缓存 → 本地书 FileBook → 按书源下载+解密), 与 desktop/app 端同一条链路
    override suspend fun saveImage(
        url: String,
        book: Book?,
        source: BookSource?,
        destPath: String
    ): Boolean = withContext(IoDispatcher) {
        book ?: return@withContext false
        runCatching {
            val bytes = MangaImageBytesLoader.load(url, book, source, currentCoroutineContext())
                ?: return@runCatching false
            File(destPath).writeBytes(bytes)
            true
        }.getOrElse {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            false
        }
    }

    // 预载到内存缓存: WRITE_ONLY 只写不返回图 (与 desktop 同参; 显示端 rememberAsyncImagePainter
    // 与预载经 SingletonImageLoader 共用同一实例, memoryCacheKey(url) 同 key, 翻到预载区间即秒显)
    override suspend fun preloadImage(url: String, book: Book, source: BookSource?) {
        runCatching {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(MangaModel(url, book, source))
                .memoryCacheKey(url)
                .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(Size.ORIGINAL)
                .build()
            SingletonImageLoader.get(PlatformContext.INSTANCE).execute(request)
        }
    }
}
