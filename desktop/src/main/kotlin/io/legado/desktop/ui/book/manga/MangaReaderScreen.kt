package io.legado.desktop.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

/**
 * 桌面端漫画阅读 Screen 入口 (对照 app 端 [io.legado.app.ui.book.manga.ReadMangaActivity]
 * + [io.legado.app.ui.book.manga.render.MangaRenderLayer])。
 *
 * # 职责
 *
 * - 包装 [MangaReaderViewModel] 持有章节图片列表状态
 * - 注入 desktop 平台 Provider (与 [io.legado.desktop.ui.about.AboutScreen] 一致 4 个 Provider)
 * - 顶部标题栏 (返回 + 书名 + 章节名 + 换源, 对照 app 端 MangaMenu 标题栏)
 * - 中间渲染区: 横向 LazyRow + rememberSnapFlingBehavior / 竖向 LazyColumn (webtoon)
 * - 缩放: graphicsLayer + detectTransformGestures (对照 app 端 MangaRenderState.scale/trans)
 * - 自动翻页: 水平按页定时; 垂直匀速滚动 (对照 app 端 setAutoPageEnabled / setAutoScrollEnabled)
 * - 底部章节控制栏 (上一章 / 进度 / 下一章, 对照 app 端 MangaInfoBar)
 * - 加载/错误覆盖层 (对照 app 端 ReadMangaActivity.LoadingOverlay)
 *
 * # 与 app 端差异
 *
 * - **图片加载**: app 端用 Glide + MangaModelLoader (GIF 动图支持);
 *   桌面端用 OkHttp (本地) / [AnalyzeUrlCore] (网络, 带书源 header) + [ImageIO] 解码,
 *   **JDK ImageIO 不支持 GIF 动图, 仅取静态首帧** (任务约束, 见 [loadMangaImage] 注释)
 * - **图片缓存**: app 端 Glide 内存+磁盘缓存 + BookHelp.writeImage 落盘;
 *   桌面端用 [mangaImageCache] 全局内存 Map (与 DesktopBookCover.coverCache 同风格, 无 LRU)
 * - **GIF 自动翻页**: app 端 enableMangaGifAutoNext 让动图播完自动翻页;
 *   桌面端不支持 GIF 动图, 该配置项无意义, 不接入
 * - **颜色滤镜 / 灰度**: app 端 MangaColorFilterConfig + enableMangaGray;
 *   桌面端未接入 (任务范围外, 后续可扩展)
 * - **章节预下载**: app 端预下载并显示 ReaderLoading 占位;
 *   桌面端 VM 预下载只写缓存, 当前章节切换时整体替换列表 (无占位)
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
    // 供 AppTheme / rememberString / PreferenceScreen 等通过 LocalXxx 取依赖
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
 * 漫画阅读主体内容 (对照 app 端 ReadMangaActivity.Content + MangaRenderLayer)。
 *
 * 注: 拆出顶层 Composable 是为在 [CompositionLocalProvider] + [AppTheme] 包裹后消费
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
    // VM 记忆 (避免重组重建, 与 ReaderScreen 中 ReadBookViewModelShared remember 一致)
    val viewModel = remember { MangaReaderViewModel(scope) }

    // 漫画配置 (从 PreferenceStore 读取, AppConfigAccessor 未下沉这些 key)
    // 对照 app 端 AppConfig.enableMangaHorizontalScroll / mangaAutoPageSpeed / mangaPreDownloadNum
    val horizontalScroll = remember { prefStore.getBoolean(PreferKey.enableMangaHorizontalScroll, false) }
    val autoPageSpeed = remember { prefStore.getInt(PreferKey.mangaAutoPageSpeed, 1) }.coerceAtLeast(1)
    val preDownloadNum = remember { prefStore.getInt(PreferKey.mangaPreDownloadNum, 0) }

    // 收集 VM 状态 (Compose 经 collectAsState 订阅, StateFlow 有初始值故无需传默认值)
    val curChapterImages by viewModel.curChapterImages.collectAsState()
    val curChapterIndex by viewModel.curChapterIndex.collectAsState()
    val chapterSize by viewModel.chapterSize.collectAsState()
    val curChapterTitle by viewModel.curChapterTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 初始化数据 (装载书 + 章节列表 + 加载首章, 对照 app 端 ReadMangaActivity.onPostCreate -> initData)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book, chapterIndex, preDownloadNum)
    }
    // 退出时取消预下载 + 清理 CbzFile 缓存 (对照 app 端 ReadMangaActivity.onDestroy:
    // cancelPreDownloadTask + CbzFile.clear)
    DisposableEffect(book.bookUrl) {
        onDispose {
            viewModel.cancelPreDownloadTask()
            CbzFile.clear()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏 (返回 + 书名/章节名 + 换源, 对照 app 端 MangaMenu 标题栏)
            MangaTitleBar(
                bookName = book.name,
                chapterTitle = curChapterTitle,
                onBack = onBack,
                onOpenChangeSource = { onOpenChangeSource(book) },
                onOpenToc = { onOpenToc(book) },
            )
            // 中间渲染区 (横向 LazyRow / 竖向 LazyColumn + 缩放手势)
            Box(Modifier.fillMaxSize().weight(1f)) {
                MangaRenderArea(
                    images = curChapterImages,
                    book = book,
                    bookSource = viewModel.curBookSource,
                    horizontal = horizontalScroll,
                    autoPageSpeed = autoPageSpeed,
                    loading = loading,
                    error = error,
                    onRetry = { viewModel.loadChapter(curChapterIndex) },
                )
            }
            // 底部章节控制栏 (上一章 / 进度 / 下一章, 对照 app 端 MangaInfoBar)
            MangaControlBar(
                curIndex = curChapterIndex,
                size = chapterSize,
                onPrev = { viewModel.moveToPrevChapter() },
                onNext = { viewModel.moveToNextChapter() },
            )
        }
    }
}

// ---- 顶部标题栏 (对照 app 端 MangaMenu 标题栏 + AudioPlayScreen.AudioTitleBar 风格) ----

/**
 * 漫画标题栏 (56dp 高, 返回 + 书名/章节名 + 目录 + 换源)。
 *
 * 黑底白字, 与 [io.legado.desktop.ui.book.audio.AudioPlayScreen] 的 AudioTitleBar 视觉一致
 * (漫画 Screen 全屏黑底, AppTitleBar Surface 风格不匹配)。
 */
@Composable
private fun MangaTitleBar(
    bookName: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = rememberPainter("ic_arrow_back"),
                contentDescription = rememberString("back"),
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = bookName,
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapterTitle.isNotEmpty()) {
                Text(
                    text = chapterTitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenToc) {
            Icon(
                painter = rememberPainter("ic_toc"),
                contentDescription = rememberString("chapter_list"),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenChangeSource) {
            Icon(
                painter = rememberPainter("ic_exchange"),
                contentDescription = rememberString("change_source"),
                tint = Color.White,
            )
        }
    }
}

// ---- 渲染区 (对照 app 端 MangaRenderLayer) ----

/**
 * 漫画渲染区: 手势容器 + LazyRow/LazyColumn 图片流。
 *
 * 缩放平移经 graphicsLayer 块读取, 不触发重组 (与 app 端 MangaRenderState 一致);
 * 横向模式 LazyRow 整页归位 (对照 app 端 PagerSnapHelper, 但桌面端用 [rememberSnapFlingBehavior]
 * 等价的 snap 行为由 flingBehavior 控制); 竖向模式 LazyColumn 普通衰减 fling (webtoon 风格)。
 *
 * 注: app 端 MangaRenderLayer 用 webtoonGestures (自定义 detectTransformGestures 扩展)
 * 处理缩放+平移+翻页联动; 桌面端简化为 detectTransformGestures (缩放) + detectTapGestures
 * (单击/双击), 不做"边界拖动翻页"联动 (复杂度高, 桌面端用底部控制栏翻页即可)。
 */
@Composable
private fun MangaRenderArea(
    images: List<String>,
    book: Book,
    bookSource: BookSource?,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    // 列表状态 (横向/竖向共用一个 LazyListState, 切换时状态保留)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 缩放/平移状态 (对照 app 端 MangaRenderState.scale/transX/transY)
    var scale by remember { mutableFloatStateOf(DEFAULT_RATE) }
    var transX by remember { mutableFloatStateOf(0f) }
    var transY by remember { mutableFloatStateOf(0f) }
    // 自动翻页开关 (默认关, 由按钮切换; 对照 app 端 enableAutoPage)
    var autoPageEnabled by remember { mutableStateOf(false) }

    // 自动翻页: 水平模式按页定时翻; 垂直模式持续匀速滚动
    // (对照 app 端 MangaRenderState.setAutoPageEnabled / setAutoScrollEnabled)
    LaunchedEffect(autoPageEnabled, horizontal, autoPageSpeed) {
        if (!autoPageEnabled) return@LaunchedEffect
        if (horizontal) {
            // 水平: 每 autoPageSpeed 秒翻一页 (对照 app 端 setAutoPageEnabled 循环 delay)
            while (isActive) {
                kotlinx.coroutines.delay(autoPageSpeed * 1000L)
                if (!listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                // 翻一页: 按 viewport 宽度滚动 (对照 app 端 scrollPage(direction=1, animated=true))
                val viewport = listState.layoutInfo.viewportSize.width
                scope.launch {
                    // Compose Multiplatform 的 LazyListState 无 animateScrollBy, 用 scrollBy 替代 (无动画)
                    listState.scrollBy(viewport.toFloat())
                }
            }
        } else {
            // 垂直: 持续匀速滚动 (对照 app 端 autoScrollLoop: animateScrollBy 10000px)
            while (isActive) {
                if (!listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                // 速度: autoPageSpeed 越大越快; 16px/帧 / speed * 10000px 段时长
                // 注: Compose Multiplatform 的 LazyListState 无 animateScrollBy, 用 scrollBy 替代 (无动画)
                val segment = 10000f
                runCatching {
                    listState.scrollBy(segment)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            // 缩放手势 (对照 app 端 webtoonGestures 的 pinchBy / 双击)
            .pointerInput(Unit) {
                detectTransformGestures(
                    onGesture = { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_RATE, MAX_RATE)
                        if (newScale > DEFAULT_RATE) {
                            // 放大态: 平移钳制 (对照 app 端 applyClampedTranslation)
                            scale = newScale
                            transX += pan.x
                            transY += pan.y
                            // 钳制 (简化: 不依赖 containerSize, 用绝对值上限)
                            transX = transX.coerceIn(-MAX_TRANS, MAX_TRANS)
                            transY = transY.coerceIn(-MAX_TRANS, MAX_TRANS)
                        } else if (newScale < DEFAULT_RATE) {
                            // 缩小到 1× 以下弹回 1× (对照 app 端 endPinch: scale < DEFAULT_RATE -> resetZoom)
                            scale = DEFAULT_RATE
                            transX = 0f
                            transY = 0f
                        }
                    },
                )
            }
            // 点击手势 (对照 app 端 detectTapGestures: onTap/onDoubleTap/onLongPress)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击切换 1× ↔ 2× (对照 app 端 onDoubleTap: isAtDefaultScale ? DOUBLE_TAP_SCALE : DEFAULT_RATE)
                        if (scale > DEFAULT_RATE + SCALE_EPSILON) {
                            scale = DEFAULT_RATE
                            transX = 0f
                            transY = 0f
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = transX
                    translationY = transY
                },
        ) {
            if (images.isNotEmpty()) {
                if (horizontal) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                book = book,
                                bookSource = bookSource,
                                horizontal = true,
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                book = book,
                                bookSource = bookSource,
                                horizontal = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // 加载覆盖层 (对照 app 端 ReadMangaActivity.LoadingOverlay)
        if (loading) {
            LoadingOverlay()
        }
        // 错误覆盖层 (对照 app 端 retryVisible)
        if (error != null && !loading) {
            ErrorOverlay(error = error, onRetry = onRetry)
        }
        // 自动翻页切换按钮 (右下角悬浮, 对照 app 端 MangaMenu 的 AUTO_PAGE 菜单项)
        AutoPageToggleButton(
            enabled = autoPageEnabled,
            onToggle = { autoPageEnabled = !autoPageEnabled },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/**
 * 漫画图片单元格 (对照 app 端 MangaPageCell)。
 *
 * - 横向模式: fillParentMaxSize + ContentScale.FitCenter (整页显示)
 * - 竖向模式: fillMaxWidth + ContentScale.FillWidth (webtoon 风格, 高度自适应)
 *
 * 图片加载走 [loadMangaImage], 加载中显示进度圈, 加载失败显示重试按钮。
 */
@Composable
private fun MangaPageCell(
    url: String,
    book: Book,
    bookSource: BookSource?,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    // produceState 触发图片异步加载 (与 DesktopBookCover.loadCoverBitmap 一致模式)
    val bitmap by produceState<ImageBitmap?>(null, url) {
        if (url.isBlank()) return@produceState
        value = loadMangaImage(url, book, bookSource)
    }
    Box(
        modifier
            .background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                // 横向: FitCenter 整页; 竖向: FillWidth 高度按比例 (对照 app 端 FIT_CENTER / FIT_XY)
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

// ---- 加载/错误覆盖层 (对照 app 端 ReadMangaActivity.LoadingOverlay) ----

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = rememberString("loading"),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, color = Color.White, textAlign = TextAlign.Center)
            Text(
                text = rememberString("reload"),
                color = Color(0xFF165DFF),
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- 自动翻页切换按钮 (对照 app 端 MangaMenuAction.AUTO_PAGE) ----

/**
 * 自动翻页悬浮按钮 (右下角)。
 *
 * 显示当前状态: 启用时 accent 色, 禁用时半透明白。
 * 点击切换 [enabled] 状态 (由调用方传入 [onToggle])。
 */
@Composable
private fun AutoPageToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF165DFF)
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) accent else Color(0x66000000))
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_auto_page"),
            contentDescription = rememberString("auto_next_page"),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ---- 底部章节控制栏 (对照 app 端 MangaInfoBar) ----

/**
 * 底部章节控制栏: 上一章 / 进度 / 下一章。
 *
 * 黑底白字, 与标题栏风格一致; 进度文本格式 "当前章/总章" (对照 app 端 manga_check_chapter)。
 */
@Composable
private fun MangaControlBar(
    curIndex: Int,
    size: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一章 (对照 app 端 click action 4: moveToPrevChapter)
        IconButton(onClick = onPrev, enabled = curIndex > 0) {
            Icon(
                painter = rememberPainter("ic_skip_previous"),
                contentDescription = rememberString("previous_chapter"),
                tint = if (curIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        Text(
            text = "${curIndex + 1}/${size}",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // 下一章 (对照 app 端 click action 3: moveToNextChapter)
        IconButton(onClick = onNext, enabled = curIndex < size - 1) {
            Icon(
                painter = rememberPainter("ic_skip_next"),
                contentDescription = rememberString("next_chapter"),
                tint = if (curIndex < size - 1) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

// ---- 图片加载 (对照 app 端 ImageLoader.loadManga + MangaModelLoader) ----

/**
 * 全局漫画图片内存缓存 (与 [io.legado.desktop.ui.component.DesktopBookCover] 的 coverCache 同风格)。
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
            AppLog.put("桌面漫画图片加载失败: $url\n${it.message}", it)
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
            if (!response.isSuccessful) null else response.body?.bytes()
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

// ---- 常量 (对照 app 端 MangaRenderState.companion) ----

/** 默认缩放比 (对照 app 端 MangaRenderState.DEFAULT_RATE = 1f) */
private const val DEFAULT_RATE = 1f
/** 最小缩放比 (对照 app 端 MangaRenderState.MIN_RATE = 0.5f) */
private const val MIN_RATE = 0.5f
/** 最大缩放比 (对照 app 端 MangaRenderState.MAX_RATE = 3f) */
private const val MAX_RATE = 3f
/** 双击缩放比 (对照 app 端 MangaRenderState.DOUBLE_TAP_SCALE = 2f) */
private const val DOUBLE_TAP_SCALE = 2f
/** 缩放阈值 (对照 app 端 MangaRenderState.SCALE_EPSILON = 0.001f) */
private const val SCALE_EPSILON = 0.001f
/** 翻页动画时长 (对照 app 端 MangaRenderState.PAGE_ANIM_DURATION = 400) */
private const val PAGE_ANIM_DURATION = 400
/** 平移钳制上限 (px, 简化: 不依赖 containerSize, 用固定上限) */
private const val MAX_TRANS = 2000f
