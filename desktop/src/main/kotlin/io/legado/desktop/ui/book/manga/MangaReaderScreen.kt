package io.legado.desktop.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.ui.book.manga.MangaReaderConfig
import io.legado.app.ui.book.manga.MangaReaderScreenContent
import io.legado.app.ui.book.manga.MangaReaderViewModelShared
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

/**
 * 桌面端漫画阅读 Screen 入口 (薄壳, 调用 shared [MangaReaderScreenContent] + [MangaReaderViewModelShared])。
 *
 * # 职责
 *
 * - 注入 desktop 平台 Provider (与 [io.legado.desktop.ui.about.AboutScreen] 一致 4 个 Provider)
 * - 注入 [DesktopMangaImageExtractor] 实现 shared [io.legado.app.ui.book.manga.MangaImageExtractor]
 * - 写入 [IntentData.book] 供 shared VM 的 initData 读取 (与 app 端 Intent 传 book 一致)
 * - 收集 shared VM 状态并适配为 shared ScreenContent 入参 (images / chapterSize / error 类型转换)
 * - 提供 [imageSlot] 渲染器 (desktop 用 JDK [ImageIO] 解码, GIF 仅取首帧)
 *
 * # 与 app 端差异
 *
 * - **图片加载**: app 端用 Glide + MangaModelLoader (GIF 动图支持);
 *   桌面端用 OkHttp (本地) / [AnalyzeUrlCore] (网络, 带书源 header) + [ImageIO] 解码,
 *   **JDK ImageIO 不支持 GIF 动图, 仅取静态首帧** (任务约束, 见 [loadMangaImage] 注释)
 * - **图片缓存**: app 端 Glide 内存+磁盘缓存; 桌面端用 [mangaImageCache] 全局内存 Map (无 LRU)
 * - **GIF 自动翻页**: app 端 enableMangaGifAutoNext 让动图播完自动翻页;
 *   桌面端不支持 GIF 动图, 该配置项无意义, 不接入
 * - **颜色滤镜 / 灰度**: app 端 MangaColorFilterConfig + enableMangaGray; 桌面端未接入 (任务范围外)
 *
 * @param book 待阅读的漫画书 (book.type 含 [io.legado.app.constant.BookType.image] 位)
 * @param chapterIndex 初始章节序号 (默认 0, 取 book.durChapterIndex)
 * @param onBack 返回回调 (切回调用方路由)
 * @param onOpenToc 打开目录回调 (切到 TOC 路由, 携带 Book)
 * @param onOpenChangeSource 打开换源回调 (切到 CHANGE_SOURCE 路由, 携带 Book)
 */
@Composable
fun MangaReaderScreen(
    book: Book,
    chapterIndex: Int = 0,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit = {},
    onOpenChangeSource: (Book) -> Unit = {},
) {
    // 注入 desktop 平台 Provider (参照 ChangeChapterSourceScreen 第 96-104 行 CompositionLocalProvider 模式)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            MangaReaderContent(
                book = book,
                chapterIndex = chapterIndex,
                prefStore = prefStore,
                onBack = onBack,
                onOpenToc = onOpenToc,
                onOpenChangeSource = onOpenChangeSource,
            )
        }
    }
}

/**
 * 漫画阅读主体内容: 装配 shared [MangaReaderViewModelShared] + [MangaReaderScreenContent]。
 *
 * 拆出顶层 Composable 是为在 [CompositionLocalProvider] + [AppTheme] 包裹后消费
 * LocalXxx (如 [LocalPreferenceStoreProvider] 当前实例), 与 ChangeChapterSourceScreen 一致。
 */
@Composable
private fun MangaReaderContent(
    book: Book,
    chapterIndex: Int,
    prefStore: io.legado.app.ui.compose.platform.PreferenceStoreProvider,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit,
    onOpenChangeSource: (Book) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 漫画配置 (从 PreferenceStore 读取, AppConfigAccessor 未下沉这些 key)
    // 对照 app 端 AppConfig.enableMangaHorizontalScroll / mangaAutoPageSpeed / mangaPreDownloadNum
    val horizontalScroll = remember { prefStore.getBoolean(PreferKey.enableMangaHorizontalScroll, false) }
    val autoPageSpeed = remember { prefStore.getInt(PreferKey.mangaAutoPageSpeed, 1) }.coerceAtLeast(1)
    val preDownloadNum = remember { prefStore.getInt(PreferKey.mangaPreDownloadNum, 0) }
    // shared VM 注入: DesktopMangaImageExtractor + MangaReaderConfig (preDownloadNum 从 PreferenceStore 读取;
    // hideMangaTitle / syncBookProgressPlus 桌面端不接入, 用默认 false)
    // 对照 app 端 ReadMangaViewModel 注入: scope=viewModelScope / imageExtractor=BookHelp.flowImages
    val viewModel = remember {
        MangaReaderViewModelShared(
            scope = scope,
            imageExtractor = DesktopMangaImageExtractor(),
            config = MangaReaderConfig(preDownloadNum = preDownloadNum),
        )
    }

    // 收集 shared VM 状态 (StateFlow 经 collectAsState 订阅)
    val mangaContent by viewModel.mangaContent.collectAsState()
    val durChapterIndex by viewModel.durChapterIndex.collectAsState()
    val durChapter by viewModel.durChapter.collectAsState()
    val bookSource by viewModel.bookSource.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 适配 shared VM 状态 -> shared ScreenContent 入参
    // images: MangaContent.items 含 MangaPage + ReaderLoading 头, 仅取 MangaPage.mImageUrl
    val images = remember(mangaContent) {
        mangaContent?.items
            ?.filterIsInstance<MangaPage>()
            ?.map { it.mImageUrl }
            ?: emptyList()
    }
    // chapterSize: shared VM 是 plain Int (非 StateFlow), 随 mangaContent 变化重组时读取最新值
    val chapterSize = viewModel.chapterSize
    val chapterTitle = durChapter?.title ?: ""
    // error: shared VM 是 Pair<String, Boolean>?, ScreenContent 取 .first 作错误文案
    val errorStr = error?.first

    // 初始化数据 (装载书 + 章节列表 + 加载首章, 对照 app 端 ReadMangaActivity.onPostCreate -> initData)
    // shared VM initData 从 IntentData.book 读取, 故调用前写入 (与 app 端 Intent.putExtra("book", book) 一致)
    LaunchedEffect(book.bookUrl) {
        IntentData.book = book
        viewModel.initData(overrideIndex = chapterIndex)
    }
    // 退出时取消预下载 + 清理 CbzFile 缓存 + 释放 VM 资源
    // (对照 app 端 ReadMangaActivity.onDestroy: cancelPreDownloadTask + CbzFile.clear)
    DisposableEffect(book.bookUrl) {
        onDispose {
            viewModel.cancelPreDownloadTask()
            viewModel.onCleared()
            CbzFile.clear()
        }
    }

    MangaReaderScreenContent(
        bookName = book.name,
        chapterTitle = chapterTitle,
        images = images,
        curChapterIndex = durChapterIndex,
        chapterSize = chapterSize,
        horizontal = horizontalScroll,
        autoPageSpeed = autoPageSpeed,
        loading = loading,
        error = errorStr,
        onBack = onBack,
        onPrevChapter = { viewModel.moveToPrevChapter() },
        onNextChapter = { viewModel.moveToNextChapter() },
        onRetry = { viewModel.loadContent() },
        onOpenToc = { onOpenToc(book) },
        onOpenChangeSource = { onOpenChangeSource(book) },
        imageSlot = { url, modifier, horizontal ->
            DesktopMangaImage(
                url = url,
                book = book,
                bookSource = bookSource,
                modifier = modifier,
                horizontal = horizontal,
            )
        },
    )
}

// ---- 平台图片渲染插槽 (对照 app 端 MangaPageCell + Coil3 渲染) ----

/**
 * 桌面端漫画图片渲染 (供 shared [MangaReaderScreenContent.imageSlot] 调用)。
 *
 * - 横向模式: [ContentScale.Fit] 整页显示; 竖向模式: [ContentScale.FillWidth] webtoon 风格
 * - 加载中: 进度圈占位; 加载失败: 返回 null (调用方走 Box 黑底占位)
 * - 图片加载走 [loadMangaImage] (JDK ImageIO, GIF 仅取首帧)
 *
 * @param url 图片 URL (网络绝对 URL / cbz:// / file:// / 本地路径)
 * @param book 当前书籍 (判断 isLocal + 提供书源 key)
 * @param bookSource 书源 (网络加载时带 header / cookie)
 * @param modifier 调用方传入的尺寸约束 (横向 fillMaxSize / 竖向 fillMaxWidth)
 * @param horizontal 是否横向模式 (决定 ContentScale)
 */
@Composable
private fun DesktopMangaImage(
    url: String,
    book: Book,
    bookSource: BookSource?,
    modifier: Modifier = Modifier,
    horizontal: Boolean,
) {
    // produceState 触发图片异步加载 (与 DesktopBookCover.loadCoverBitmap 一致模式)
    val bitmap by produceState<ImageBitmap?>(null, url) {
        if (url.isBlank()) return@produceState
        value = loadMangaImage(url, book, bookSource)
    }
    Box(
        modifier.background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                // 横向: Fit 整页; 竖向: FillWidth 高度按比例 (对照 app 端 FIT_CENTER / FIT_XY)
                // 注: app 端竖向用 FIT_XY 拉伸, 但桌面端 ImageBitmap 用 FillWidth 更自然 (webtoon 风格)
                contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
            )
        } else {
            // 加载中: 进度圈 (对照 app 端 CircularProgressIndicator)
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

// ---- 图片加载 (对照 app 端 ImageLoader.loadManga + MangaModelLoader) ----

/**
 * 全局漫画图片内存缓存 (与 [io.legado.desktop.ui.component.DesktopBookCoverCache] 同风格)。
 *
 * key: 图片 URL (与 produceState 的 key 一致)
 * value: 已解码 [ImageBitmap] (Compose 可直接渲染)
 *
 * 线程安全: 用 [Collections.synchronizedMap] 包装, 下载在 IO Dispatcher, 读取在 UI 线程。
 *
 * TODO: 无 LRU 淘汰, 长时间阅读可能占用较多内存; 后续可改为 androidx.collection.LruCache。
 */
private val mangaImageCache: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(mutableMapOf())

/**
 * 加载漫画图片为 [ImageBitmap] (对照 app 端 [io.legado.app.help.glide.ImageLoader.loadManga])。
 *
 * # 加载策略 (与 app 端 MangaModelLoader.loadManga 对齐)
 *
 * 1. **本地路径** (`file://` / 绝对路径 `/...`): [ImageIO.read] 读文件 (本地漫画书)
 * 2. **网络路径** (`http://` / `https://`):
 *    - 本地书 (`book.isLocal`): 用 [OkHttpClientProviders] 直接 GET (本地书源无书源 header 配置)
 *    - 网络书: 用 [AnalyzeUrlCore] 发请求, 自动带书源 header / cookie / charset / JS 解析
 *      (对照 app 端 `AnalyzeUrl(imageUrl, source=bookSource).getByteArrayAwait()`)
 * 3. **内存缓存**: 命中 [mangaImageCache] 直接返回 (与 app 端 Glide 内存缓存等价)
 *
 * # GIF 限制
 *
 * **JDK [ImageIO] 不支持 GIF 动图, 仅取静态首帧** (任务约束, app 端用 Glide 支持 GIF 动图)。
 * 后续若需 GIF 动图支持, 可引入 `com.squareup:gif-h` 或 Dart 端 AnimatedImage 替代方案。
 *
 * @param url 图片 URL (网络绝对 URL / 相对路径 / file:// 路径)
 * @param book 当前书籍 (判断 isLocal + 提供书源 key)
 * @param bookSource 书源 (网络加载时带 header / cookie)
 * @return 已解码 [ImageBitmap], 失败返回 null (调用方走占位)
 */
private suspend fun loadMangaImage(
    url: String,
    book: Book,
    bookSource: BookSource?,
): ImageBitmap? {
    // 命中内存缓存直接返回 (避免重复下载/解码, 与 app 端 Glide 内存缓存等价)
    mangaImageCache[url]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val image = when {
                url.startsWith("cbz://") -> {
                    // 本地 cbz/zip 漫画: 从 zip 内嵌条目读取图片流 (对照 app 端 MangaModelLoader
                    // 走 Book.getHandler().getImage(book, href) -> CbzFile.getImage)
                    val entryName = url.removePrefix("cbz://")
                    CbzFile.getImage(book, entryName)?.use { input ->
                        ImageIO.read(input)
                    }
                }
                url.startsWith("file://") -> ImageIO.read(File(url.removePrefix("file://")))
                url.startsWith("/") -> ImageIO.read(File(url))
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val bytes = if (book.isLocal) {
                        // 本地书: 直接 OkHttp GET (参照 DesktopBookCover.downloadAndDecode)
                        downloadBytesSimple(url)
                    } else {
                        // 网络书: AnalyzeUrlCore 带书源 header/cookie/charset/JS (对照 app 端 AnalyzeUrl)
                        downloadBytesWithSource(url, bookSource)
                    }
                    bytes?.let { ImageIO.read(ByteArrayInputStream(it)) }
                }
                else -> null
            }
            image?.toComposeImageBitmap()?.also { mangaImageCache[url] = it }
        }.onFailure {
            AppLog.put(jvmGetString("manga_image_load_failed_log", url, it.message), it)
        }.getOrNull()
    }
}

/**
 * 简单 OkHttp GET 取字节流 (本地书用, 参照 [io.legado.desktop.ui.component.DesktopBookCover] 的 downloadAndDecode)。
 */
private fun downloadBytesSimple(url: String): ByteArray? {
    val client = OkHttpClientProviders.get().okHttpClient
    val request = Request.Builder().url(url).build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }
    }.getOrNull()
}

/**
 * 用 [AnalyzeUrlCore] 发请求带书源 header/cookie/charset/JS (网络书用)。
 *
 * 对照 app 端 `AnalyzeUrl(imageUrl, source=bookSource, coroutineContext=...).getByteArrayAwait()`。
 * shared 端用 [AnalyzeUrlCore] (app 端 AnalyzeUrl 的 KMP 等价类, 内部走 OkHttp + JS 引擎)。
 */
private suspend fun downloadBytesWithSource(url: String, bookSource: BookSource?): ByteArray? {
    if (bookSource == null) return downloadBytesSimple(url)
    return runCatching {
        val analyzeUrl = AnalyzeUrlCore(
            rawUrl = url,
            source = bookSource,
            coroutineContext = currentCoroutineContext(),
        )
        analyzeUrl.getByteArrayAwait()
    }.getOrNull()
}
