package io.legado.app.ui.book.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaCellState
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.render.MangaReaderBackground
import io.legado.app.ui.book.manga.render.MangaRenderLayer
import io.legado.app.ui.book.manga.render.MangaRenderState
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.handleReadPageKeys
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.back
import legado.shared.generated.resources.bookmark_add
import legado.shared.generated.resources.bottom_line
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.click_regional_config
import legado.shared.generated.resources.disable_manga_page_anim
import legado.shared.generated.resources.enable_auto_page_scroll
import legado.shared.generated.resources.enable_manga_horizontal_scroll
import legado.shared.generated.resources.hide_manga_title
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.ic_toc
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.manga_auto_page_speed
import legado.shared.generated.resources.manga_check_chapter
import legado.shared.generated.resources.manga_check_page_number
import legado.shared.generated.resources.manga_check_progress
import legado.shared.generated.resources.manga_color_filter
import legado.shared.generated.resources.manga_footer_config
import legado.shared.generated.resources.manga_gif_auto_next
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.pre_download_m
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.reload
import legado.shared.generated.resources.review
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 漫画阅读 Screen 主体内容（各端共享，由 desktop/app 调用）。
 *
 * 布局对齐 app 端 ReadMangaActivity: 全屏渲染区(book_ant_10 #141414) + 底部信息条(ReaderInfoBarView) +
 * 菜单 Overlay(TitleBar + view_manga_menu 底栏), 菜单配色走 ThemeStore(background/bottomBackground/primaryText)。
 *
 * 渲染区直接复用 app 端下沉的 [MangaRenderLayer] + [MangaRenderState]：章节转场页、居中页驱动
 * 进度/跨章、预加载、GIF 播完翻页、pendingScroll 定位全部走同一份实现，
 * 平台差异只剩图片单元格 ([imageSlot]) 与预加载执行体 ([preloadImage])。
 *
 * @param bookName 书名（标题栏标题, 对照 app 端 MangaMenu.title）
 * @param chapterTitle 章节名（底部信息条用, 原版标题栏不显示章节名）
 * @param items 当前 prev/cur/next 三章合并后的页列表 (含章节转场 ReaderLoading)
 * @param contentPos 内容定位下标 (对照 app 端 buildMangaContent().pos)
 * @param curFinish 当前章是否已加载完成 (对照 app 端 MangaContent.curFinish)
 * @param book 当前书籍 (图片加载/预加载用)
 * @param bookSource 当前书源 (图片加载/预加载用)
 * @param curChapterIndex 当前章节序号 (0-based)
 * @param chapterSize 总章节数
 * @param horizontal 横向翻页模式（true=LazyRow 整页，false=LazyColumn webtoon）
 * @param autoPageSpeed 自动翻页速度（横向=秒/页，纵向=滚动速度系数）
 * @param loading 加载中标记（覆盖层）
 * @param error 错误消息（null=无错误）
 * @param batteryLevel 电池电量 0-100, -1 不显示 (原版信息条不含电池, 暂未使用)
 * @param systemTime 系统时间 HH:mm
 * @param currentPage 章节内当前页 (0-based)
 * @param pageCount 章节内总页数
 * @param progressPercent 全书进度百分比字符串
 * @param colorFilterConfig 颜色滤镜配置 (平台应用到图片)
 * @param grayEnabled 灰度滤镜开关
 * @param onBack 返回回调
 * @param onPrevChapter 上一章
 * @param onNextChapter 下一章
 * @param onPrevPage 上一页（提供后键盘上翻键优先调用，否则整屏回滚）
 * @param onNextPage 下一页（提供后键盘下翻键/空格优先调用，否则整屏前滚）
 * @param onCenterItemChanged 居中页变化（对照 app 端 onCenterItemChanged: 驱动跨章/进度）
 * @param onSeekToPage SeekBar 拖动定位到章内页 (对照 app 端 skipToPage)
 * @param onRetry 错误重试
 * @param onOpenToc 打开目录回调
 * @param onOpenBookInfo 打开书籍详情 (标题栏点击)
 * @param onAddBookmark 添加书签
 * @param onSaveImage 长按图片保存 (参数为图片 url)
 * @param onToggleHorizontal 切换横/纵向翻页
 * @param preloadImage 图片预加载执行体 (对照 app 端 Coil3 WRITE_ONLY 预载, 未提供则不预载)
 * @param imageSlot 平台图片渲染插槽：(url, modifier, horizontal, colorFilterConfig, grayEnabled, onLoadState, retryTick) -> Compose 图片组件;
 *    onLoadState 由平台上报单元格加载状态, retryTick 供"重新加载"点击驱动平台重试 (对照 app 端 MangaPageImageView.retry())
 */
@Composable
fun MangaReaderScreenContent(
    bookName: String,
    chapterTitle: String,
    items: List<BaseMangaPage>,
    contentPos: Int,
    curFinish: Boolean,
    book: Book?,
    bookSource: BookSource?,
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
    preDownloadNum: Int = 10,
    hasReview: Boolean = false,
    clickActionConfig: ClickActionConfig = ClickActionConfig(),
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onCenterItemChanged: (BaseMangaPage) -> Unit = {},
    onSeekToPage: (Int) -> Unit = {},
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    onOpenToc: () -> Unit = {},
    onOpenBookInfo: () -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onSaveImage: (String) -> Unit = {},
    onToggleHorizontal: () -> Unit = {},
    onToggleHideTitle: () -> Unit = {},
    onToggleDisablePageAnim: () -> Unit = {},
    onToggleGifAutoNext: () -> Unit = {},
    onOpenColorFilter: () -> Unit = {},
    onOpenFooterConfig: () -> Unit = {},
    onOpenPreDownloadNum: () -> Unit = {},
    onOpenAutoPageSpeed: () -> Unit = {},
    onOpenClickRegionConfig: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    preloadImage: (suspend (String, Book, BookSource?) -> Unit)? = null,
    imageSlot: @Composable (
        String, Modifier, Boolean, MangaColorFilterConfig, Boolean,
        (MangaCellState) -> Unit, Int
    ) -> Unit,
) {
    // 键盘事件焦点: onPreviewKeyEvent 需节点持有焦点才触发, 进入即取焦点
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }
    // 菜单 Overlay 显隐 (点击区域动作 0 呼出, 对照 app 端 click action 0)
    var menuVisible by remember { mutableStateOf(false) }
    // 自动翻页开关: 对照原版 menu_enable_auto_page, 由溢出菜单勾选项控制 (原版同样不持久化)
    var autoPageEnabled by remember { mutableStateOf(false) }
    // 渲染状态: 提升到顶层, 供 SeekBar 定位复用 listState
    val renderState = remember { MangaRenderState() }
    val scope = rememberCoroutineScope()
    val bottomLineText = stringResource(Res.string.bottom_line)
    renderState.scope = scope
    renderState.horizontal = horizontal
    // 速度下限 1: 对照 app 端 showNumberPickerDialog(min=1); 0 会让定时翻页退化成空转
    renderState.autoSpeed = autoPageSpeed.coerceAtLeast(1)
    renderState.items = items
    renderState.book = book
    renderState.bookSource = bookSource
    renderState.colorFilterConfig = colorFilterConfig
    renderState.grayEnabled = grayEnabled
    // 对照 app 端 initRenderLayer: GIF 播完翻页只在横向模式生效
    renderState.gifAutoNext = gifAutoNext && horizontal
    renderState.preloadCount = preDownloadNum
    renderState.preloadExecutor = preloadImage

    /**
     * 翻页, 返回是否真的翻动了 (对照 app 端 ReadMangaActivity.scrollPageTo)。
     * silent=true 时受阻不弹提示, 供 GIF 播完翻页在受阻时继续循环重试。
     */
    fun scrollPageTo(direction: Int, silent: Boolean = false): Boolean {
        if (!renderState.canScroll(direction)) {
            if (!silent) Toasters.get().toast(bottomLineText)
            return false
        }
        renderState.scrollPage(direction, animated = !disablePageAnim)
        if (disablePageAnim && renderState.gifAutoNext) {
            // 无翻页动画时同步滚动不触发停稳回调, 手动装填新当前页的 GIF
            renderState.post { renderState.syncGifAutoNextForCurrentPage() }
        }
        return true
    }

    // 居中页变化驱动跨章/进度/信息条 (对照 app 端 onCenterItemChanged)
    renderState.onCenterItemChanged = { position ->
        items.getOrNull(position)?.let(onCenterItemChanged)
    }
    // 仅在滚动彻底停止后装填居中页 GIF, 避免滑动途中提前播完停在末帧
    renderState.onScrollIdle = { renderState.syncGifAutoNextForCurrentPage() }
    renderState.onGifTurnPage = { scrollPageTo(1, silent = true) }
    renderState.onAutoPageTick = { scrollPageTo(1) }
    // 长按当前居中页图片 → 保存 (对照 app 端 onLongTap)
    renderState.onLongTap = {
        val item = items.getOrNull(renderState.centerItemIndex())
        if (item is MangaPage) {
            onSaveImage(item.mImageUrl)
            true
        } else {
            false
        }
    }

    // 点击九宫格: 对照 app 端 ClickArea.getAction + ReadMangaActivity.click(action)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    renderState.onContainerSizeExtra = { containerSize = it }
    renderState.clickActionAt = { x, y ->
        clickActionConfig.actionAt(x, y, containerSize.width, containerSize.height)
    }
    renderState.onAction = { action ->
        when (action) {
            0 -> if (!menuVisible && !loading) menuVisible = true
            1 -> if (onNextPage != null) onNextPage() else scrollPageTo(1)
            2 -> if (onPrevPage != null) onPrevPage() else scrollPageTo(-1)
            3 -> onNextChapter()
            4 -> onPrevChapter()
            10 -> onOpenToc()
        }
    }

    // 自动翻页 (对照 app 端 applyAutoPage): 横向按页定时翻, 纵向匀速滚动
    LaunchedEffect(autoPageEnabled, horizontal, renderState.autoSpeed) {
        renderState.setAutoPageEnabled(autoPageEnabled && horizontal)
        renderState.setAutoScrollEnabled(autoPageEnabled && !horizontal)
    }

    // 初始/切章定位 (对照 app 端 upContent: loadingViewVisible && curFinish 时 scrollToPosition)。
    // shared VM 在 upContent 内已把 loading 置回 false, 故用本地标记记住"这轮加载需要定位"
    var awaitingScroll by remember { mutableStateOf(true) }
    LaunchedEffect(loading) { if (loading) awaitingScroll = true }
    LaunchedEffect(items, curFinish) {
        if (awaitingScroll && curFinish && items.isNotEmpty()) {
            awaitingScroll = false
            renderState.scrollToPosition(contentPos) {
                // 初始定位不触发停稳回调, 手动装填首个当前页的 GIF
                renderState.syncGifAutoNextForCurrentPage()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MangaReaderBackground)
            // 对照 app 端 onKeyDown: 音量/翻页键走整屏翻页, 不是切章
            .handleReadPageKeys(
                onPrevPage = { if (onPrevPage != null) onPrevPage() else scrollPageTo(-1) },
                onNextPage = { if (onNextPage != null) onNextPage() else scrollPageTo(1) },
                onBack = onBack,
            )
            .focusRequester(keyFocusRequester)
            .focusable(),
    ) {
        MangaRenderLayer(renderState) { item, _ ->
            MangaPageCell(
                url = item.mImageUrl,
                horizontal = horizontal,
                imageSlot = imageSlot,
                colorFilterConfig = colorFilterConfig,
                grayEnabled = grayEnabled,
            )
        }

        // 对照 app 端 loadFail: 失败时 ll_loading 收起换 ll_retry, 故错误优先于转圈
        if (loading && error == null) {
            LoadingOverlay()
        }
        if (error != null) {
            ErrorOverlay(error = error, onRetry = onRetry)
        }

        // 底部信息条: 加载完成后才显示, 对齐原版 curFinish 后 upInfoBar。
        if (!footerConfig.hideFooter && !loading && error == null && curFinish && pageCount > 0) {
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
        if (menuVisible) {
            MangaMenuOverlay(
                bookName = bookName,
                currentPage = currentPage,
                pageCount = pageCount,
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                autoPageEnabled = autoPageEnabled,
                preDownloadNum = preDownloadNum,
                autoPageSpeed = autoPageSpeed,
                hasReview = hasReview,
                onToggleAutoPage = { autoPageEnabled = !autoPageEnabled },
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenToc = onOpenToc,
                onOpenBookInfo = onOpenBookInfo,
                onAddBookmark = onAddBookmark,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenPreDownloadNum = onOpenPreDownloadNum,
                onOpenAutoPageSpeed = onOpenAutoPageSpeed,
                onOpenClickRegionConfig = onOpenClickRegionConfig,
                onOpenReview = onOpenReview,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                // 对照 app 端 MangaSeekBar + skipToPage: 拖动中即定位到本章该页
                onSeekPage = { index ->
                    val itemPos = items.indexOfFirst {
                        it.chapterIndex == curChapterIndex && it.index == index
                    }
                    if (itemPos > -1) {
                        renderState.scrollToPosition(itemPos)
                        onSeekToPage(index)
                    }
                },
                onDismiss = { menuVisible = false },
            )
        }
    }
}

/** 点击落点 → 动作值, 对照 app 端 [io.legado.app.ui.book.read.config.ClickArea] 的 3x3 分区 */
private fun ClickActionConfig.actionAt(x: Float, y: Float, width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return -1
    val col = when {
        x < width * 0.33f -> 0
        x < width * 0.66f -> 1
        else -> 2
    }
    val row = when {
        y < height * 0.33f -> 0
        y < height * 0.66f -> 1
        else -> 2
    }
    return when (row * 3 + col) {
        0 -> tl
        1 -> tc
        2 -> tr
        3 -> ml
        4 -> mc
        5 -> mr
        6 -> bl
        7 -> bc
        else -> br
    }
}

// ---- 底部信息条 (进度文字 + 时间, 对照 app 端 ReaderInfoBarView + upInfoBar) ----

/** 对照 ReaderInfoBarView.ALIGN_CENTER */
private const val INFO_BAR_ALIGN_CENTER = 1

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
    val colors = AppTheme.colors
    // 对照 ReaderInfoBarView: colorOnSurface/colorSurface 各取 alpha 200, 描边保证压在图片上可读
    val fill = colors.primaryText.copy(alpha = 0.78f)
    val outline = colors.background.copy(alpha = 0.78f)
    Box(
        modifier
            .fillMaxWidth()
            // 对照 activity_manga.xml: 高 20dp + marginBottom 16dp; 16dp padding + 控件内 10dp inset
            .padding(bottom = 16.dp)
            .height(20.dp)
            .padding(horizontal = 26.dp),
    ) {
        val alignment = if (footerConfig.footerOrientation == INFO_BAR_ALIGN_CENTER) {
            Alignment.Center
        } else {
            Alignment.CenterStart
        }
        InfoBarText(infoText, fill, outline, Modifier.align(alignment))
        InfoBarText(systemTime, fill, outline, Modifier.align(Alignment.CenterEnd))
    }
}

/** 对照 ReaderInfoBarView.drawTextOutline: 先描边后填充 */
@Composable
private fun InfoBarText(
    text: String,
    fill: Color,
    outline: Color,
    modifier: Modifier = Modifier,
) {
    val style = LocalTextStyle.current.copy(fontSize = 11.sp)
    Box(modifier) {
        Text(
            text = text,
            color = outline,
            style = style.copy(drawStyle = Stroke(width = 2f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            color = fill,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 菜单 Overlay (顶部标题栏 + 底部控制栏) ----

@Composable
private fun MangaMenuOverlay(
    bookName: String,
    currentPage: Int,
    pageCount: Int,
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    autoPageEnabled: Boolean,
    preDownloadNum: Int,
    autoPageSpeed: Int,
    hasReview: Boolean,
    onToggleAutoPage: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookInfo: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenPreDownloadNum: () -> Unit,
    onOpenAutoPageSpeed: () -> Unit,
    onOpenClickRegionConfig: () -> Unit,
    onOpenReview: () -> Unit,
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
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenToc = onOpenToc,
                onOpenBookInfo = onOpenBookInfo,
                onAddBookmark = onAddBookmark,
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                autoPageEnabled = autoPageEnabled,
                preDownloadNum = preDownloadNum,
                autoPageSpeed = autoPageSpeed,
                hasReview = hasReview,
                onToggleAutoPage = onToggleAutoPage,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenPreDownloadNum = onOpenPreDownloadNum,
                onOpenAutoPageSpeed = onOpenAutoPageSpeed,
                onOpenClickRegionConfig = onOpenClickRegionConfig,
                onOpenReview = onOpenReview,
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
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookInfo: () -> Unit,
    onAddBookmark: () -> Unit,
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    autoPageEnabled: Boolean,
    preDownloadNum: Int,
    autoPageSpeed: Int,
    hasReview: Boolean,
    onToggleAutoPage: () -> Unit,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenPreDownloadNum: () -> Unit,
    onOpenAutoPageSpeed: () -> Unit,
    onOpenClickRegionConfig: () -> Unit,
    onOpenReview: () -> Unit,
) {
    // 对照 TitleBar: 背景走 ThemeStore backgroundColor, 文字 primaryText
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 8.dp),
    ) {
        // 整行点击打开书籍详情 (对照 app 端 toolbar click → openBookInfoActivity)
        Row(
            Modifier
                .fillMaxWidth()
                .height(DesignTokens.viewHeightMax)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onOpenBookInfo() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.back),
                    tint = colors.primaryText,
                )
            }
            // 对照 app 端 MangaMenu.title: 标题只显示书名, 20sp 单行
            Text(
                text = bookName,
                color = colors.primaryText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 刷新当前章 (对照 app 端 MangaMenuAction.REFRESH)
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                    contentDescription = stringResource(Res.string.refresh),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = onOpenToc) {
                Icon(
                    painter = painterResource(Res.drawable.ic_toc),
                    contentDescription = stringResource(Res.string.chapter_list),
                    tint = colors.primaryText,
                )
            }
            MangaOverflowMenu(
                horizontal = horizontal,
                hideMangaTitle = hideMangaTitle,
                disablePageAnim = disablePageAnim,
                gifAutoNext = gifAutoNext,
                autoPageEnabled = autoPageEnabled,
                preDownloadNum = preDownloadNum,
                autoPageSpeed = autoPageSpeed,
                hasReview = hasReview,
                onToggleAutoPage = onToggleAutoPage,
                onToggleHorizontal = onToggleHorizontal,
                onToggleHideTitle = onToggleHideTitle,
                onToggleDisablePageAnim = onToggleDisablePageAnim,
                onToggleGifAutoNext = onToggleGifAutoNext,
                onOpenColorFilter = onOpenColorFilter,
                onOpenFooterConfig = onOpenFooterConfig,
                onOpenPreDownloadNum = onOpenPreDownloadNum,
                onOpenAutoPageSpeed = onOpenAutoPageSpeed,
                onOpenClickRegionConfig = onOpenClickRegionConfig,
                onOpenReview = onOpenReview,
                onAddBookmark = onAddBookmark,
            )
        }
    }
}

/** 溢出菜单, 项与顺序对照 app 端 MangaOverflowMenu (即 menu/book_manga.xml) */
@Composable
private fun MangaOverflowMenu(
    horizontal: Boolean,
    hideMangaTitle: Boolean,
    disablePageAnim: Boolean,
    gifAutoNext: Boolean,
    autoPageEnabled: Boolean,
    preDownloadNum: Int,
    autoPageSpeed: Int,
    hasReview: Boolean,
    onToggleAutoPage: () -> Unit,
    onToggleHorizontal: () -> Unit,
    onToggleHideTitle: () -> Unit,
    onToggleDisablePageAnim: () -> Unit,
    onToggleGifAutoNext: () -> Unit,
    onOpenColorFilter: () -> Unit,
    onOpenFooterConfig: () -> Unit,
    onOpenPreDownloadNum: () -> Unit,
    onOpenAutoPageSpeed: () -> Unit,
    onOpenClickRegionConfig: () -> Unit,
    onOpenReview: () -> Unit,
    onAddBookmark: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val preDownloadText = stringResource(Res.string.pre_download_m, preDownloadNum)
    val hideTitleText = stringResource(Res.string.hide_manga_title)
    val autoPageText = stringResource(Res.string.enable_auto_page_scroll)
    val autoPageSpeedText = stringResource(Res.string.manga_auto_page_speed, autoPageSpeed)
    val horizontalText = stringResource(Res.string.enable_manga_horizontal_scroll)
    val disableAnimText = stringResource(Res.string.disable_manga_page_anim)
    val gifAutoNextText = stringResource(Res.string.manga_gif_auto_next)
    val footerConfigText = stringResource(Res.string.manga_footer_config)
    val clickRegionText = stringResource(Res.string.click_regional_config)
    val colorFilterText = stringResource(Res.string.manga_color_filter)
    val reviewText = stringResource(Res.string.review)
    val bookmarkAddText = stringResource(Res.string.bookmark_add)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = AppTheme.colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val click: () -> Unit = { expanded = false }
            // 顺序对照 app 端 MangaOverflowMenu
            OverflowItem(preDownloadText) { click(); onOpenPreDownloadNum() }
            OverflowCheckItem(hideTitleText, hideMangaTitle) { click(); onToggleHideTitle() }
            OverflowCheckItem(autoPageText, autoPageEnabled) { click(); onToggleAutoPage() }
            if (autoPageEnabled) {
                OverflowItem(autoPageSpeedText) { click(); onOpenAutoPageSpeed() }
            }
            if (horizontal) {
                OverflowCheckItem(gifAutoNextText, gifAutoNext) { click(); onToggleGifAutoNext() }
            }
            OverflowCheckItem(horizontalText, horizontal) { click(); onToggleHorizontal() }
            OverflowCheckItem(
                disableAnimText,
                disablePageAnim
            ) { click(); onToggleDisablePageAnim() }
            OverflowItem(footerConfigText) { click(); onOpenFooterConfig() }
            OverflowItem(clickRegionText) { click(); onOpenClickRegionConfig() }
            OverflowItem(colorFilterText) { click(); onOpenColorFilter() }
            if (hasReview) {
                OverflowItem(reviewText) { click(); onOpenReview() }
            }
            OverflowItem(bookmarkAddText) { click(); onAddBookmark() }
        }
    }
}

@Composable
private fun OverflowItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = AppTheme.colors.menuText)
    }
}

@Composable
private fun OverflowCheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(
            text,
            color = AppTheme.colors.menuText,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        AppMenuCheckbox(checked = checked)
    }
}

/** 底部控制栏, 对照 view_manga_menu.xml: bottomBackground 底 + 上一章/页码 SeekBar/下一章 单行 */
@Composable
private fun MangaMenuBottomBar(
    currentPage: Int,
    pageCount: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekPage: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChapterNavText(
                text = stringResource(Res.string.previous_chapter),
                color = colors.primaryText,
                onClick = onPrevChapter,
            )
            // 对照 app 端 MangaSeekBar: 拖动中(fromUser)即跳页, 抬手只是结束拖动
            AppSlider(
                value = currentPage,
                max = (pageCount - 1).coerceAtLeast(0),
                onValueChange = { onSeekPage(it) },
                modifier = Modifier
                    .weight(1f)
                    .height(25.dp),
            )
            ChapterNavText(
                text = stringResource(Res.string.next_chapter),
                color = colors.primaryText,
                onClick = onNextChapter,
            )
        }
    }
}

@Composable
private fun ChapterNavText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    )
}

// ---- 渲染区图片单元格 (列表/手势/转场页均在 shared MangaRenderLayer) ----

@Composable
private fun LazyItemScope.MangaPageCell(
    url: String,
    horizontal: Boolean,
    imageSlot: @Composable (
        String, Modifier, Boolean, MangaColorFilterConfig, Boolean,
        (MangaCellState) -> Unit, Int
    ) -> Unit,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
) {
    // 图片加载状态驱动转圈/占位/重试 (对照 app 端 MangaRenderScreen.MangaPageCell);
    // 状态完全由平台图片槽经 onLoadState 上报 (Android onStateChange / Coil LaunchedEffect),
    // 不设 onSizeChanged 兜底 —— 兜底会在 LOADING 时把占位高度误判为出图, 与平台上报互相
    // 覆写导致"转圈闪现/ERROR 被盖/高度 H↔内容 抖动" (桌面端"一直加载中"观感)
    var load by remember(url) { mutableStateOf(MangaCellState.LOADING) }
    // 重试计数: "重新加载"点击自增, 平台图片槽据此重试 (Android 直接调 MangaPageImageView.retry())
    var retryTick by remember(url) { mutableStateOf(0) }
    val cellModifier = when {
        horizontal -> Modifier.fillParentMaxSize()
        load == MangaCellState.SUCCESS -> Modifier.fillMaxWidth()
        else -> Modifier.fillMaxWidth().fillParentMaxHeight()
    }
    Box(
        cellModifier.background(MangaReaderBackground),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            imageSlot(
                url,
                if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                horizontal,
                colorFilterConfig,
                grayEnabled,
                { load = it },
                retryTick,
            )
        }
        if (load != MangaCellState.SUCCESS) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MangaReaderBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (load == MangaCellState.LOADING) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.reload),
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { retryTick++ }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
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
