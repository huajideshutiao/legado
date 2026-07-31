package io.legado.app.ui.book.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.render.MangaRenderState
import io.legado.app.ui.book.manga.render.rememberSinglePageSnapFlingBehavior
import io.legado.app.ui.book.manga.render.webtoonGestures
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.handleReadPageKeys
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.auto_next_page
import legado.shared.generated.resources.back
import legado.shared.generated.resources.bookmark_add
import legado.shared.generated.resources.change_source
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.disable_manga_page_anim
import legado.shared.generated.resources.enable_manga_horizontal_scroll
import legado.shared.generated.resources.hide_manga_title
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_auto_page
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_skip_next
import legado.shared.generated.resources.ic_skip_previous
import legado.shared.generated.resources.ic_toc
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.manga_check_chapter
import legado.shared.generated.resources.manga_check_page_number
import legado.shared.generated.resources.manga_check_progress
import legado.shared.generated.resources.manga_color_filter
import legado.shared.generated.resources.manga_footer_config
import legado.shared.generated.resources.manga_gif_auto_next
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.page_preview
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.reload
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 漫画阅读 Screen 主体内容（各端共享，由 desktop/app 调用）。
 *
 * 布局对齐 app 端 ReadMangaActivity: 全屏渲染区 + 顶部信息条(电池/时间/进度) +
 * 菜单 Overlay(顶部标题栏 + 底部控制栏含 SeekBar), 黑底 (#1A1A1A) 白字视觉风格保留。
 *
 * 渲染区复用 app 端 [MangaRenderState] + [webtoonGestures]（缩放/平移/双击锚定/边界拖动翻页），
 * 图片加载经 [imageSlot] 注入，滤镜 (colorFilterConfig/grayEnabled) 由平台应用到 ImageView。
 * GIF 由 Coil3 自动识别解码 (coil3-gif), 无需 isGif 标记。
 *
 * @param bookName 书名（标题栏主标题）
 * @param chapterTitle 章节标题（标题栏副标题）
 * @param images 当前章节图片 URL 列表
 * @param curChapterIndex 当前章节序号 (0-based)
 * @param chapterSize 总章节数
 * @param horizontal 横向翻页模式（true=LazyRow 整页，false=LazyColumn webtoon）
 * @param autoPageSpeed 自动翻页速度（横向=秒/页，纵向=滚动速度系数）
 * @param loading 加载中标记（覆盖层）
 * @param error 错误消息（null=无错误）
 * @param batteryLevel 电池电量 0-100, -1 不显示
 * @param systemTime 系统时间 HH:mm
 * @param currentPage 章节内当前页 (0-based)
 * @param pageCount 章节内总页数
 * @param progressPercent 全书进度百分比字符串
 * @param colorFilterConfig 颜色滤镜配置 (平台应用到图片)
 * @param grayEnabled 灰度滤镜开关
 * @param onBack 返回回调
 * @param onPrevChapter 上一章
 * @param onNextChapter 下一章
 * @param onPrevPage 上一页（提供后键盘上翻键优先调用，否则回落到 onPrevChapter）
 * @param onNextPage 下一页（提供后键盘下翻键/空格优先调用，否则回落到 onNextChapter）
 * @param onRetry 错误重试
 * @param onOpenToc 打开目录回调
 * @param onOpenChangeSource 打开换源回调
 * @param onAddBookmark 添加书签
 * @param onSaveImage 长按图片保存 (参数为图片 url)
 * @param onToggleHorizontal 切换横/纵向翻页
 * @param imageSlot 平台图片渲染插槽：(url, modifier, horizontal, colorFilterConfig, grayEnabled) -> Compose 图片组件
 */
@Composable
fun MangaReaderScreenContent(
    bookName: String,
    chapterTitle: String,
    images: List<String>,
    curChapterIndex: Int,
    chapterSize: Int,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loading: Boolean,
    error: String?,
    batteryLevel: Int = -1,
    systemTime: String = "",
    currentPage: Int = 0,
    pageCount: Int = 0,
    progressPercent: String = "0.0%",
    colorFilterConfig: MangaColorFilterConfig = MangaColorFilterConfig(),
    grayEnabled: Boolean = false,
    footerConfig: MangaFooterConfig = MangaFooterConfig(),
    hideMangaTitle: Boolean = false,
    disablePageAnim: Boolean = false,
    gifAutoNext: Boolean = false,
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onSaveImage: (String) -> Unit = {},
    onToggleHorizontal: () -> Unit = {},
    onToggleHideTitle: () -> Unit = {},
    onToggleDisablePageAnim: () -> Unit = {},
    onToggleGifAutoNext: () -> Unit = {},
    onOpenColorFilter: () -> Unit = {},
    onOpenFooterConfig: () -> Unit = {},
    onOpenGridPreview: () -> Unit = {},
    imageSlot: @Composable (String, Modifier, Boolean, MangaColorFilterConfig, Boolean) -> Unit,
) {
    // 键盘事件焦点: onPreviewKeyEvent 需节点持有焦点才触发, 进入即取焦点
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }
    // 菜单 Overlay 显隐 (居中点击切换, 对照 app 端 click action 0)
    var menuVisible by remember { mutableStateOf(false) }
    // 九宫格缩略图模式 (对照 app 端 MangaRenderLayer 无, shared 简化版新增)
    var gridMode by remember { mutableStateOf(false) }
    // 渲染状态: 提升到顶层, 供 SeekBar/九宫格定位复用 listState
    val renderState = remember { MangaRenderState() }
    val scope = rememberCoroutineScope()
    renderState.scope = scope
    renderState.horizontal = horizontal

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .handleReadPageKeys(
                onPrevPage = { onPrevPage?.invoke() ?: onPrevChapter() },
                onNextPage = { onNextPage?.invoke() ?: onNextChapter() },
                onBack = onBack,
            )
            .focusRequester(keyFocusRequester)
            .focusable(),
    ) {
        MangaRenderArea(
            images = images,
            horizontal = horizontal,
            autoPageSpeed = autoPageSpeed,
            loading = loading,
            error = error,
            onRetry = onRetry,
            onMenuToggle = { menuVisible = !menuVisible },
            onLongPressImage = onSaveImage,
            state = renderState,
            imageSlot = imageSlot,
            colorFilterConfig = colorFilterConfig,
            grayEnabled = grayEnabled,
        )

        // 底部信息条: 按 footerConfig 格式化进度文字 (对照 app 端 MangaInfoBar + upInfoBar)
        if (!gridMode && !footerConfig.hideFooter) {
            MangaInfoBarOverlay(
                footerConfig = footerConfig,
                chapterName = chapterTitle,
                chapterIndex = curChapterIndex,
                chapterSize = chapterSize,
                chapterPos = currentPage,
                imageCount = pageCount,
                progressPercent = progressPercent,
                systemTime = systemTime,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 菜单 Overlay: 顶部标题栏 + 底部控制栏(SeekBar) (对照 app 端 MangaMenuOverlay)
        if (menuVisible && !gridMode) {
            MangaMenuOverlay(
                bookName = bookName,
                chapterTitle = chapterTitle,
                curChapterIndex = curChapterIndex,
                chapterSize = chapterSize,
                currentPage = currentPage,
                pageCount = pageCount,
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenToc = onOpenToc,
                onOpenChangeSource = onOpenChangeSource,
                onAddBookmark = onAddBookmark,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenGridPreview = {
                    menuVisible = false
                    gridMode = true
                },
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onSeekPage = { index ->
                    scope.launch {
                        renderState.listState.scrollToItem(
                            index.coerceIn(
                                0,
                                (pageCount - 1).coerceAtLeast(0)
                            )
                        )
                    }
                },
                onDismiss = { menuVisible = false },
            )
        }

        // 九宫格缩略图模式 (LazyVerticalGrid)
        if (gridMode) {
            MangaThumbnailGridOverlay(
                images = images,
                imageSlot = imageSlot,
                colorFilterConfig = colorFilterConfig,
                grayEnabled = grayEnabled,
                onClose = { gridMode = false },
                onPageSelected = { index ->
                    gridMode = false
                    scope.launch {
                        renderState.listState.scrollToItem(
                            index.coerceIn(
                                0,
                                (pageCount - 1).coerceAtLeast(0)
                            )
                        )
                    }
                },
            )
        }
    }
}

// ---- 底部信息条 (进度文字 + 时间, 对照 app 端 MangaInfoBar + upInfoBar) ----

@Composable
private fun MangaInfoBarOverlay(
    footerConfig: MangaFooterConfig,
    chapterName: String,
    chapterIndex: Int,
    chapterSize: Int,
    chapterPos: Int,
    imageCount: Int,
    progressPercent: String,
    systemTime: String,
    modifier: Modifier = Modifier,
) {
    // 标签文字 (对照 app 端 getString(R.string.manga_check_*))
    val pageLabel = stringResource(Res.string.manga_check_page_number)
    val chapterLabel = stringResource(Res.string.manga_check_chapter)
    val progressLabel = stringResource(Res.string.manga_check_progress)
    val infoText = buildString {
        if (!footerConfig.hideChapterName && chapterName.isNotEmpty()) {
            append(chapterName).append(" ")
        }
        if (!footerConfig.hidePageNumber && imageCount > 0) {
            if (!footerConfig.hidePageNumberLabel) append(pageLabel)
            append("${chapterPos + 1}/$imageCount ")
        }
        if (!footerConfig.hideChapter && chapterSize > 0) {
            if (!footerConfig.hideChapterLabel) append(chapterLabel)
            append("${chapterIndex + 1}/$chapterSize ")
        }
        if (!footerConfig.hideProgressRatio) {
            if (!footerConfig.hideProgressRatioLabel) append(progressLabel)
            append(progressPercent)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = infoText,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = systemTime, color = Color.White, fontSize = 12.sp)
    }
}

// ---- 菜单 Overlay (顶部标题栏 + 底部控制栏) ----

@Composable
private fun MangaMenuOverlay(
    bookName: String,
    chapterTitle: String,
    curChapterIndex: Int,
    chapterSize: Int,
    currentPage: Int,
    pageCount: Int,
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenGridPreview: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 全屏拦截触摸, 点击空白收起菜单
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
        )
        AnimatedVisibility(
            visibleState = remember {
                androidx.compose.animation.core.MutableTransitionState(true)
                    .apply { targetState = true }
            },
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
        ) {
            MangaMenuTopBar(
                bookName = bookName,
                chapterTitle = chapterTitle,
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenToc = onOpenToc,
                onOpenChangeSource = onOpenChangeSource,
                onAddBookmark = onAddBookmark,
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenGridPreview = onOpenGridPreview,
            )
        }
        AnimatedVisibility(
            visibleState = remember {
                androidx.compose.animation.core.MutableTransitionState(true)
                    .apply { targetState = true }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            MangaMenuBottomBar(
                curChapterIndex = curChapterIndex,
                chapterSize = chapterSize,
                currentPage = currentPage,
                pageCount = pageCount,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onSeekPage = onSeekPage,
            )
        }
    }
}

@Composable
private fun MangaMenuTopBar(
    bookName: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onAddBookmark: () -> Unit,
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenGridPreview: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF01A1A1A))
            .padding(horizontal = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.back),
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
            // 刷新当前章 (对照 app 端 MangaMenuAction.REFRESH)
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                    contentDescription = stringResource(Res.string.refresh),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpenToc) {
                Icon(
                    painter = painterResource(Res.drawable.ic_toc),
                    contentDescription = stringResource(Res.string.chapter_list),
                    tint = Color.White,
                )
            }
            MangaOverflowMenu(
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenChangeSource = onOpenChangeSource,
                onAddBookmark = onAddBookmark,
                onOpenGridPreview = onOpenGridPreview,
            )
        }
    }
}

/** 溢出菜单 (对照 app 端 MangaOverflowMenu, 含全部 MangaMenuAction 项) */
@Composable
private fun MangaOverflowMenu(
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenGridPreview: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hideTitleText = stringResource(Res.string.hide_manga_title)
    val horizontalText = stringResource(Res.string.enable_manga_horizontal_scroll)
    val disableAnimText = stringResource(Res.string.disable_manga_page_anim)
    val gifAutoNextText = stringResource(Res.string.manga_gif_auto_next)
    val footerConfigText = stringResource(Res.string.manga_footer_config)
    val colorFilterText = stringResource(Res.string.manga_color_filter)
    val changeSourceText = stringResource(Res.string.change_source)
    val bookmarkAddText = stringResource(Res.string.bookmark_add)
    val gridPreviewText = stringResource(Res.string.page_preview)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = Color.White,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val click: () -> Unit = { expanded = false }
            OverflowCheckItem(hideTitleText, hideMangaTitle) { click(); onToggleHideTitle() }
            OverflowCheckItem(horizontalText, horizontal) { click(); onToggleHorizontal() }
            if (horizontal) {
                OverflowCheckItem(gifAutoNextText, gifAutoNext) { click(); onToggleGifAutoNext() }
            }
            OverflowCheckItem(
                disableAnimText,
                disablePageAnim
            ) { click(); onToggleDisablePageAnim() }
            OverflowItem(footerConfigText) { click(); onOpenFooterConfig() }
            OverflowItem(colorFilterText) { click(); onOpenColorFilter() }
            OverflowItem(changeSourceText) { click(); onOpenChangeSource() }
            OverflowItem(bookmarkAddText) { click(); onAddBookmark() }
            // 九宫格预览 (shared 独有, app 端用 ClickArea 分区导航)
            OverflowItem(gridPreviewText) { click(); onOpenGridPreview() }
        }
    }
}

@Composable
private fun OverflowItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = Color.White)
    }
}

@Composable
private fun OverflowCheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        AppMenuCheckbox(checked = checked)
    }
}

@Composable
private fun MangaMenuBottomBar(
    curChapterIndex: Int,
    chapterSize: Int,
    currentPage: Int,
    pageCount: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekPage: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF01A1A1A))
            .padding(horizontal = 8.dp),
    ) {
        // 章节内页面 SeekBar (对照 app 端 MangaSeekBar)
        if (pageCount > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${currentPage + 1}",
                    color = Color.White,
                    fontSize = 12.sp,
                )
                // 拖动中预览值, 抬手跳页
                var dragValue by remember { mutableStateOf<Int?>(null) }
                AppSlider(
                    value = dragValue ?: currentPage,
                    max = (pageCount - 1).coerceAtLeast(1),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onSeekPage(it) }
                        dragValue = null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = "$pageCount",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevChapter, enabled = curChapterIndex > 0) {
                Icon(
                    painter = painterResource(Res.drawable.ic_skip_previous),
                    contentDescription = stringResource(Res.string.previous_chapter),
                    tint = if (curChapterIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                )
            }
            Text(
                text = "${curChapterIndex + 1}/$chapterSize",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNextChapter, enabled = curChapterIndex < chapterSize - 1) {
                Icon(
                    painter = painterResource(Res.drawable.ic_skip_next),
                    contentDescription = stringResource(Res.string.next_chapter),
                    tint = if (curChapterIndex < chapterSize - 1) Color.White else Color.White.copy(
                        alpha = 0.3f
                    ),
                )
            }
        }
    }
}

// ---- 渲染区 (复用 shared MangaRenderState + webtoonGestures) ----

@Composable
private fun MangaRenderArea(
    images: List<String>,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onMenuToggle: () -> Unit,
    onLongPressImage: (String) -> Unit,
    state: MangaRenderState,
    imageSlot: @Composable (String, Modifier, Boolean, MangaColorFilterConfig, Boolean) -> Unit,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
) {
    // 横向对齐 PagerSnapHelper：整页归位且单次手势最多翻一页；纵向普通衰减 fling
    val fling = if (horizontal) {
        rememberSinglePageSnapFlingBehavior(state.listState)
    } else {
        ScrollableDefaults.flingBehavior()
    }
    state.flingBehavior = fling

    // 自动翻页开关（默认关，由悬浮按钮切换）
    var autoPageEnabled by remember { mutableStateOf(false) }

    // 自动翻页: 水平模式按页定时翻; 垂直模式持续滚动
    LaunchedEffect(autoPageEnabled, horizontal, autoPageSpeed) {
        if (!autoPageEnabled) return@LaunchedEffect
        if (horizontal) {
            while (isActive) {
                kotlinx.coroutines.delay(autoPageSpeed * 1000L)
                if (!state.listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                val viewport = state.listState.layoutInfo.viewportSize.width
                state.listState.scrollBy(viewport.toFloat())
            }
        } else {
            while (isActive) {
                if (!state.listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                runCatching {
                    state.listState.scrollBy(10000f)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .onSizeChanged(state::onContainerSize)
            .pointerInput(state) { webtoonGestures(state) }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = {
                        state.onSingleTap(it)
                        // 居中点击切换菜单 (对照 app 端 click action 0, 简化版无九宫格分区)
                        onMenuToggle()
                    },
                    onDoubleTap = { state.onDoubleTap(it) },
                    onLongPress = {
                        // 长按当前居中页图片 → 保存 (对照 app 端 onLongTap)
                        val center = state.centerItemIndex()
                        if (center in images.indices) onLongPressImage(images[center])
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.transX
                    translationY = state.transY
                },
        ) {
            if (images.isNotEmpty()) {
                if (horizontal) {
                    LazyRow(
                        state = state.listState,
                        flingBehavior = fling,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                horizontal = true,
                                imageSlot = imageSlot,
                                colorFilterConfig = colorFilterConfig,
                                grayEnabled = grayEnabled,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = state.listState,
                        flingBehavior = fling,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                horizontal = false,
                                imageSlot = imageSlot,
                                colorFilterConfig = colorFilterConfig,
                                grayEnabled = grayEnabled,
                            )
                        }
                    }
                }
            }
        }

        if (loading) {
            LoadingOverlay()
        }
        if (error != null && !loading) {
            ErrorOverlay(error = error, onRetry = onRetry)
        }
        AutoPageToggleButton(
            enabled = autoPageEnabled,
            onToggle = { autoPageEnabled = !autoPageEnabled },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun LazyItemScope.MangaPageCell(
    url: String,
    horizontal: Boolean,
    imageSlot: @Composable (String, Modifier, Boolean, MangaColorFilterConfig, Boolean) -> Unit,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
) {
    val cellModifier = if (horizontal) Modifier.fillParentMaxSize() else Modifier.fillMaxWidth()
    Box(
        cellModifier.background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        imageSlot(
            url,
            if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            horizontal,
            colorFilterConfig,
            grayEnabled,
        )
    }
}

// ---- 九宫格缩略图 ----

@Composable
private fun MangaThumbnailGridOverlay(
    images: List<String>,
    imageSlot: @Composable (String, Modifier, Boolean, MangaColorFilterConfig, Boolean) -> Unit,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
    onClose: () -> Unit,
    onPageSelected: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏: 关闭按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xF01A1A1A))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${images.size} 页",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = stringResource(Res.string.back),
                        tint = Color.White,
                    )
                }
            }
            LazyVerticalGrid(
                columns = rememberResponsiveColumns(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(images, key = { _, url -> url }) { index, url ->
                    Box(
                        Modifier
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2A2A2A))
                            .clickable { onPageSelected(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 缩略图: 纵向填充, 不应用横向整页尺寸
                        imageSlot(
                            url,
                            Modifier.fillMaxSize(),
                            false,
                            colorFilterConfig,
                            grayEnabled
                        )
                        Text(
                            text = "${index + 1}",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color(0x88000000), RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---- 加载/错误覆盖层 ----

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
                text = stringResource(Res.string.loading),
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
                text = stringResource(Res.string.reload),
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

// ---- 自动翻页切换按钮 ----

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
            painter = painterResource(Res.drawable.ic_auto_page),
            contentDescription = stringResource(Res.string.auto_next_page),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
