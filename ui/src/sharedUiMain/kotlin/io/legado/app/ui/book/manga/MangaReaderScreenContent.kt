package io.legado.app.ui.book.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
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
import io.legado.app.model.chapter.ChapterLoadState
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.ChapterLoadStateOverlay
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.AppShortcut
import io.legado.app.ui.compose.platform.AppShortcutHandler
import io.legado.app.ui.compose.platform.KeyRepeatPolicy
import io.legado.app.ui.compose.platform.PageTurnThrottle
import io.legado.app.ui.compose.platform.VolumeKeyPageTurnHandler
import io.legado.app.ui.compose.platform.platformNavigationBarPadding
import io.legado.app.ui.compose.platform.platformStatusBarPadding
import io.legado.app.ui.compose.platform.readerDirectionalKeys
import io.legado.app.ui.compose.platform.rememberCustomPageKeys
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.back
import legado.ui.generated.resources.bookmark_add
import legado.ui.generated.resources.bottom_line
import legado.ui.generated.resources.chapter_list
import legado.ui.generated.resources.click_regional_config
import legado.ui.generated.resources.disable_manga_page_anim
import legado.ui.generated.resources.enable_auto_page_scroll
import legado.ui.generated.resources.enable_manga_horizontal_scroll
import legado.ui.generated.resources.hide_manga_title
import legado.ui.generated.resources.ic_arrow_back
import legado.ui.generated.resources.ic_more_vert
import legado.ui.generated.resources.ic_refresh_black_24dp
import legado.ui.generated.resources.ic_toc
import legado.ui.generated.resources.manga_auto_page_speed
import legado.ui.generated.resources.manga_check_chapter
import legado.ui.generated.resources.manga_check_page_number
import legado.ui.generated.resources.manga_check_progress
import legado.ui.generated.resources.manga_color_filter
import legado.ui.generated.resources.manga_footer_config
import legado.ui.generated.resources.manga_gif_auto_next
import legado.ui.generated.resources.more_menu
import legado.ui.generated.resources.next_chapter
import legado.ui.generated.resources.pre_download_m
import legado.ui.generated.resources.previous_chapter
import legado.ui.generated.resources.refresh
import legado.ui.generated.resources.reload
import legado.ui.generated.resources.review
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** 物理 Menu 键呼出菜单 (对照原版 ReadMangaActivity KEYCODE_MENU) */
private val mangaMenuKey = listOf(AppShortcut(Key.Menu))

/**
 * 漫画阅读 Screen 主体内容（各端共享，由 desktop/app 调用）。
 *
 * 布局对齐 app 端 ReadMangaActivity: 全屏渲染区(book_ant_10 #141414) + 底部信息条(ReaderInfoBarView) +
 * 菜单 Overlay(TitleBar + view_manga_menu 底栏), 菜单配色统一走 ThemeStore(bottomBackground/primaryText)。
 *
 * 渲染区直接复用 app 端下沉的 [MangaRenderLayer] + [MangaRenderState]：章节转场页、居中页驱动
 * 进度/跨章、预加载、GIF 播完翻页、pendingScroll 定位全部走同一份实现，
 * 平台差异只剩图片单元格 ([imageSlot]) 与预加载执行体 ([preloadImage])。
 *
 * @param bookName 书名（标题栏标题, 对照 app 端 MangaMenu.title）
 * @param chapterTitle 章节名（底部信息条用, 原版标题栏不显示章节名）
 * @param items 当前 prev/cur/next 三章合并后的页列表 (含章节转场 ReaderLoading)
 * @param curFinish 当前章是否已加载完成 (对照 app 端 MangaContent.curFinish)
 * @param positionRequest 一次性定位请求 (初始打开/菜单切章/目录选章/书签跳转/重载/云进度/换源)
 * @param book 当前书籍 (图片加载/预加载用)
 * @param bookSource 当前书源 (图片加载/预加载用)
 * @param curChapterIndex 当前章节序号 (0-based)
 * @param chapterSize 总章节数
 * @param horizontal 横向翻页模式（true=LazyRow 整页，false=LazyColumn webtoon）
 * @param autoPageSpeed 自动翻页速度（横向=秒/页，纵向=滚动速度系数）
 * @param loadState 章节装载状态 (加载中/失败 → 覆盖层; 与视频/音频共用一套状态与覆盖层)
 * @param batteryLevel 电池电量 0-100 (读取失败回落 100 恒显示, 用户拍板 2026-08; 原版信息条不含电池)
 * @param systemTime 系统时间 HH:mm
 * @param currentPage 章节内当前页 (0-based)
 * @param pageCount 章节内总页数
 * @param progressPercent 全书进度百分比字符串
 * @param colorFilterConfig 颜色滤镜配置 (平台应用到图片)
 * @param grayEnabled 灰度滤镜开关
 * @param onBack 返回回调
 * @param onMenuVisibleChange 菜单(操作界面)显隐回调 (对照原版 ReadMangaActivity.upSystemUiVisibility:
 *   菜单显示 → 宿主恢复系统栏, 菜单隐藏 → 沉浸式全屏; 各平台经 WindowController.setSystemBars 执行)。
 *   时序对齐原版 MangaMenu menuIn/OutListener: 显示在菜单入场动画开始时 (onAnimationStart),
 *   隐藏等出场动画播完后 (onAnimationEnd), 滑出期间系统栏保持可见、顶/底栏 padding 不抽走。
 * @param onPrevChapter 上一章
 * @param onNextChapter 下一章
 * @param onPrevPage 上一页（提供后键盘上翻键优先调用，否则整屏回滚）
 * @param onNextPage 下一页（提供后键盘下翻键/空格优先调用，否则整屏前滚）
 * @param onCenterItemChanged 居中页变化（对照 app 端 onCenterItemChanged: 驱动跨章/进度）。
 *   第二参 reanchored=true 表示本次上报来自 items 重建后的按 key 重锚而非滚动, 不得据此跨章
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
    curFinish: Boolean,
    book: Book?,
    bookSource: BookSource?,
    curChapterIndex: Int,
    chapterSize: Int,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loadState: ChapterLoadState,
    /** 一次性定位请求 (初始打开/菜单切章/目录选章/书签跳转/重载/云进度/换源发出) */
    positionRequest: MangaPositionRequest? = null,
    /** 内容重建身份号 (每次发布新内容自增); 内容层用它判断"新内容到了, 该消费请求了" */
    contentEpoch: Int = 0,
    /** items 所属内容批次的归属书籍 (同一快照): 消费定位请求时与请求核对 */
    contentBookUrl: String = "",
    /** items 所属内容批次的装载代际 (同一快照): 请求只消费不早于自身代际的批次 */
    contentGeneration: Int = 0,
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
    /** 本路由是否处于导航栈栈顶 (对照小说阅读端快捷键 isTopEntry: 非栈顶时键盘不响应) */
    isTopEntry: () -> Boolean = { true },
    onMenuVisibleChange: (Boolean) -> Unit = {},
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onCenterItemChanged: (BaseMangaPage, Boolean) -> Unit = { _, _ -> },
    /** 定位请求已被消费 (回调状态持有者清空请求, 对照官方 UI 事件"消费后回报"口径) */
    onPositionRequestConsumed: (MangaPositionRequest) -> Unit = {},
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
        (MangaCellState) -> Unit, Int, (String) -> Unit
    ) -> Unit,
) {
    // 覆盖层之外仍按布尔用 (菜单抑制 / 信息条 / 切章跳转标记), 从单一状态源派生
    val loading = loadState.isLoading
    val error = loadState.errorMessage
    // 菜单 Overlay 显隐 (点击区域动作 0 呼出, 对照 app 端 click action 0)
    var menuVisible by remember { mutableStateOf(false) }
    // 菜单显隐转场状态 (对照原版 runMenuIn/runMenuOut 的 200ms 位移动画):
    // 菜单 Overlay 常驻组合, 由本转场驱动顶/底栏滑入滑出; 系统栏隐藏等
    // currentState 落到 false (出场动画播完, 对齐原版 onAnimationEnd 语义)
    val menuTransition = remember { MutableTransitionState(menuVisible) }
    LaunchedEffect(menuVisible) { menuTransition.targetState = menuVisible }
    // 系统栏随菜单显隐 (对照原版 ReadMangaActivity.upSystemUiVisibility(menuIsVisible) →
    // toggleSystemBar, 由宿主平台执行; 时序对齐原版 MangaMenu 的 menuIn/OutListener):
    // - 显示: 与菜单入场动画开始同帧 (onAnimationStart 语义), 系统栏动画与菜单滑入并行,
    //   顶/底栏 padding 逐帧跟随 insets (platformStatusBarPadding), 无离散跳变;
    // - 隐藏: 出场动画播完后 (onAnimationEnd 语义), 滑出期间系统栏保持可见、
    //   padding 不抽走, 播完后再隐藏系统栏。
    LaunchedEffect(menuVisible) {
        if (menuVisible) {
            onMenuVisibleChange(true)
        } else {
            snapshotFlow { menuTransition.currentState }.first { !it }
            onMenuVisibleChange(false)
        }
    }
    // 翻页/切章 200ms 去抖 (对照原版 prevPageThrottle/nextPageThrottle, 防长按连翻/连切章)
    val pageTurnThrottle = remember { PageTurnThrottle() }
    val chapterTurnThrottle = remember { PageTurnThrottle() }

    // 自动翻页开关: 对照原版 menu_enable_auto_page, 由溢出菜单勾选项控制 (原版同样不持久化)
    var autoPageEnabled by remember { mutableStateOf(false) }
    // 渲染状态: 提升到顶层, 供 SeekBar 定位复用 listState
    val renderState = remember { MangaRenderState() }
    val scope = rememberCoroutineScope()
    val bottomLineText = stringResource(Res.string.bottom_line)
    renderState.scope = scope
    renderState.horizontal = horizontal
    // 横竖切换时归位到当前中心页 (对照原版: 垂直翻页途中切水平, 图片自动归中当前页;
    // 跳过首次组合, 避免打断初始定位)
    var firstHorizontal by remember { mutableStateOf(true) }
    LaunchedEffect(horizontal) {
        if (firstHorizontal) {
            firstHorizontal = false
            return@LaunchedEffect
        }
        // 生效一刻取活列表的中心页 (登记后 items 可能重建, 裸下标会被新列表错误解释)
        renderState.scrollToPosition(resolve = { renderState.centerItemIndex().takeIf { it >= 0 } })
    }
    // 速度下限 1: 对照 app 端 showNumberPickerDialog(min=1); 0 会让定时翻页退化成空转
    renderState.autoSpeed = autoPageSpeed.coerceAtLeast(1)
    renderState.items = items
    renderState.contentBookUrl = contentBookUrl
    renderState.contentGeneration = contentGeneration
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

    // ---- 键盘 (对照小说阅读端同一套 AppShortcutHandler 体系) ----
    // 键位 (用户拍板 2026-08): 方向键随翻页方向自适应——横向(LazyRow)模式 ←/→=翻页、↑/↓=章节;
    // 纵向(webtoon)模式 ↑/↓=翻页、←/→=章节; PageUp/PageDown/Space 不再绑定。
    // 对照原版 ReadMangaActivity 补齐: 音量键翻页 (Vol+/Vol- = 上一页/下一页)、物理 Menu 键呼出菜单。
    // 与小说端差异对齐:
    // - 走全局快捷键栈 + 根节点分发, 不依赖本页持焦 (点击菜单/弹窗后翻页键不失效)
    // - 非栈顶路由不响应 (isTopEntry)
    // - 菜单打开时照样响应所有键 (2026-08 用户拍板, 对齐原版 ReadMangaActivity onKeyDown 无
    //   menuVisible 守卫; 小说端保留菜单守卫, 对照原版 menuLayoutIsVisible)
    // - Esc 由根节点 handleBackKey → performBack 统一处理 (菜单打开时同样出栈, 与小说端一致);
    //   Backspace 不再绑定 (根节点刻意不映射 Backspace, 与小说端一致)
    AppShortcutHandler(
        shortcuts = readerDirectionalKeys,
        enabled = isTopEntry,
    ) { shortcut ->
        when {
            !horizontal && (shortcut.key == Key.DirectionUp || shortcut.key == Key.DirectionDown) -> {
                // 上下滚动模式: ↑/↓ = 小步滚动 (用户拍板 2026-08: 一次翻整页难受), 1/3 视口
                pageTurnThrottle.tryTurn {
                    renderState.scrollPagePart(
                        if (shortcut.key == Key.DirectionUp) -1 else 1,
                        fraction = 1f / 3f,
                        animated = !disablePageAnim,
                    )
                }
            }

            !horizontal -> {
                // 上下滚动模式: ←/→ = 章节切换
                chapterTurnThrottle.tryTurn {
                    if (shortcut.key == Key.DirectionLeft) onPrevChapter()
                    else onNextChapter()
                }
            }

            shortcut.key == Key.DirectionLeft || shortcut.key == Key.DirectionRight -> {
                // 左右翻页模式: ←/→ = 翻页
                pageTurnThrottle.tryTurn {
                    if (shortcut.key == Key.DirectionLeft) {
                        if (onPrevPage != null) onPrevPage() else scrollPageTo(-1)
                    } else {
                        if (onNextPage != null) onNextPage() else scrollPageTo(1)
                    }
                }
            }

            else -> {
                // 左右翻页模式: ↑/↓ = 章节切换
                chapterTurnThrottle.tryTurn {
                    if (shortcut.key == Key.DirectionUp) onPrevChapter()
                    else onNextChapter()
                }
            }
        }
    }
    // 音量键翻页 (对照原版 ReadMangaActivity onKeyDown: 无开关检查、无菜单守卫、repeat 连翻、
    // onKeyUp 恒消费; 共享 VolumeKeyPageTurnHandler: TRIGGER 策略 + 节流连翻)。
    // 节流窗口与方向键/自定义键共用 pageTurnThrottle (对照原版各键共用 nextPageThrottle 实例)
    VolumeKeyPageTurnHandler(
        enabled = isTopEntry,
        throttle = pageTurnThrottle,
    ) { volumeUp ->
        if (volumeUp) {
            if (onPrevPage != null) onPrevPage() else scrollPageTo(-1)
        } else {
            if (onNextPage != null) onNextPage() else scrollPageTo(1)
        }
    }
    // 自定义翻页键 (对照原版 ReadMangaActivity onKeyDown 的 isPrevKey/isNextKey, 2026-08
    // 键盘迁移时消费端被砍, 现恢复)。原版无 repeat 检查、连翻经 throttle(200L, trailing=false)
    // 节流——对应 TRIGGER 策略 + 复用 pageTurnThrottle。
    // 注册在方向键/音量键之后 → 快捷键栈顶优先, 复刻原版"自定义键先于内置键判定"的覆盖语义。
    // 每次重组现读偏好 (对照原版每次按键现读 SharedPreferences), PageKeyDialog 确认后立即生效。
    val customPageKeys = rememberCustomPageKeys(KeyRepeatPolicy.TRIGGER)
    if (!customPageKeys.isEmpty()) {
        AppShortcutHandler(
            shortcuts = customPageKeys.shortcuts,
            enabled = isTopEntry,
        ) { shortcut ->
            pageTurnThrottle.tryTurn {
                if (customPageKeys.isPrev(shortcut.key)) {
                    if (onPrevPage != null) onPrevPage() else scrollPageTo(-1)
                } else {
                    if (onNextPage != null) onNextPage() else scrollPageTo(1)
                }
            }
        }
    }
    // 物理 Menu 键呼出菜单 (对照原版 ReadMangaActivity KEYCODE_MENU → runMenuIn)
    AppShortcutHandler(
        shortcuts = mangaMenuKey,
        enabled = { isTopEntry() },
    ) {
        if (!menuVisible && !loading) menuVisible = true
    }

    // 居中页变化驱动跨章/进度/信息条 (对照 app 端 onCenterItemChanged)
    renderState.onCenterItemChanged = { position, reanchored ->
        items.getOrNull(position)?.let { onCenterItemChanged(it, reanchored) }
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

    // 内容就绪后消费定位请求 (对照 app 端 upContent: submitList 回调里 scrollToPositionWithOffset)。
    // key 全部用身份而非内容:
    // - contentEpoch 每发布一批新内容自增 (items 不行 —— 列表元素是 data class, 重载后逐元素
    //   相等时 Compose 的 key 判等成立 (GapComposer.kt:842) → effect 不重启 → 定位无人消费);
    // - positionRequest 每次新请求 id 单调自增, 使"请求晚于内容到达"(初始化/换源回填等异步路径)
    //   也能立即重启消费, 不必依赖"请求必先于内容"的时序;
    // - curFinish 保证内容真的可用 (setProgress 同章刷新等路径会发布 cur 未就绪的部分批次)。
    // 无请求时直接退出: 滚动跨章/预下载带来的内容重建不得重定位 (滚动连续性由列表自身位置
    // 保持 + 中心页上报驱动, 对齐原版"仅 loading 可见时定位"的语义)。
    // 请求与内容同源闭环: 消费判定核对**内容自身快照**的书籍归属与装载代际 (不另读当前
    // book, combine 异步滞后窗口内旧批不冒充新批) → 登记时不下标固化 → 渲染层生效一刻
    // 按活列表与归属重解析 → 真滚动生效且快照未换批才回执, 过期/被顶替的请求不回执。
    LaunchedEffect(contentEpoch, positionRequest, curFinish) {
        val request = positionRequest ?: return@LaunchedEffect
        when (jumpResolutionFor(request, contentBookUrl, contentGeneration, items, curFinish)) {
            MangaJumpResolution.Apply -> renderState.scrollToPosition(
                // 生效一刻重核对归属与代际, 再按活列表重解析: 登记到生效之间可能换书/换批
                // (前章插入/移除), 目标失效则返回 null, 等下一轮内容重新登记
                resolve = {
                    if (renderState.contentBookUrl == request.bookUrl &&
                        renderState.contentGeneration >= request.generation
                    ) {
                        jumpIndexFor(request, renderState.items)
                    } else {
                        null
                    }
                },
            ) {
                // 初始定位不触发停稳回调, 手动装填首个当前页的 GIF
                renderState.syncGifAutoNextForCurrentPage()
                // 真滚动生效后身份回执
                onPositionRequestConsumed(request)
            }
            // 目标章未进内容/内容未就绪/旧代批次: 保留请求, 等下一轮内容身份变化再消费
            MangaJumpResolution.Wait -> Unit
            // 书籍归属不符 (换书/换源后的遗留请求): 作废清除, 不滚动
            MangaJumpResolution.Stale -> onPositionRequestConsumed(request)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MangaReaderBackground)
    ) {
        MangaRenderLayer(
            renderState,
            // pageCell 在签名里位于 footer 之前, 不能用尾随 lambda (会绑到 footer)
            pageCell = { item, index ->
                MangaPageCell(
                    url = item.mImageUrl,
                    horizontal = horizontal,
                    imageSlot = imageSlot,
                    colorFilterConfig = colorFilterConfig,
                    grayEnabled = grayEnabled,
                    index = index,
                    renderState = renderState,
                )
            },
        )

        // 对照 app 端 loadFail: 失败时 ll_loading 收起换 ll_retry, 故错误优先于转圈
        // (覆盖层实现已收敛至 ChapterLoadStateOverlay, 与视频/音频共用)
        ChapterLoadStateOverlay(state = loadState, onRetry = onRetry)

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

        // 菜单 Overlay: 顶部标题栏 + 底部控制栏(SeekBar) (对照 app 端 MangaMenuOverlay)。
        // 常驻组合: 由 menuTransition 驱动滑入/滑出 (对照原版 anim_readbook_top_in/out),
        // 退出动画期间系统栏保持可见 (时序见 menuTransition 与系统栏 LaunchedEffect),
        // 对齐原版 runMenuIn/runMenuOut 的 onAnimationStart/onAnimationEnd。
        MangaMenuOverlay(
            transitionState = menuTransition,
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
            // 对照 app 端 MangaSeekBar + skipToPage: 拖动中即定位到本章该页。
            // 生效一刻按活列表重解析 (同定位请求: 登记后 items 重建不漂移)
            onSeekPage = { index ->
                renderState.scrollToPosition(resolve = {
                    renderState.items.indexOfFirst {
                        it.chapterIndex == curChapterIndex && it.index == index
                    }.takeIf { it > -1 }
                })
                onSeekToPage(index)
            },
            onDismiss = { menuVisible = false },
        )
    }
}

/** 定位请求消费判定结果 (纯逻辑, 供回归测试)。 */
internal sealed interface MangaJumpResolution {
    /** 请求与当前内容同书且代际相当、目标章在内: 可登记定位 (生效一刻还会重核对)。 */
    data object Apply : MangaJumpResolution

    /** 目标章未进内容/内容未就绪/批次代际落后: 保留请求, 等下一轮内容身份变化。 */
    data object Wait : MangaJumpResolution

    /** 书籍归属不符 (换书/换源后的遗留请求): 作废清除, 不滚动。 */
    data object Stale : MangaJumpResolution
}

/**
 * 定位请求消费判定: 请求必须与**内容自身快照**同书且不落后于请求代际才可消费
 * (纯逻辑, 供回归测试)。
 *
 * - 同书: 与批次自身 [contentBookUrl] 核对, 异书遗留请求作废;
 * - 同代: 批次 [contentGeneration] 落后于请求即为旧代 (新请求先于 combine 发射落到旧
 *   items 上的滞后窗口), 保留请求等新批; 目标章未进 items 同样保留。
 */
internal fun jumpResolutionFor(
    request: MangaPositionRequest,
    contentBookUrl: String,
    contentGeneration: Int,
    items: List<BaseMangaPage>,
    curFinish: Boolean,
): MangaJumpResolution = when {
    contentBookUrl.isEmpty() -> MangaJumpResolution.Wait
    request.bookUrl != contentBookUrl -> MangaJumpResolution.Stale
    contentGeneration < request.generation -> MangaJumpResolution.Wait
    !curFinish || items.isEmpty() -> MangaJumpResolution.Wait
    jumpIndexFor(request, items) == null -> MangaJumpResolution.Wait
    else -> MangaJumpResolution.Apply
}

/**
 * 定位请求对应的 items 下标; 目标章尚未进入列表时返回 null (本轮不消费, 等内容到达)。
 *
 * 页号按目标章的**真实图片数**归一: 取绝对值 (停在章末的进度用负数编码, 见
 * [MangaReaderViewModelShared.saveRead]) 后夹到 `[0, imageCount-1]`, 与原版
 * `buildMangaContent` 的 `durChapterPos.coerceIn(0, imageCount - 1)` 同口径 —— 保存位置/
 * 书签超过换章后新章节的页数时落在末图, 不是首页。零图片的卷章只有标题条目, 定位该标题。
 */
internal fun jumpIndexFor(request: MangaPositionRequest, items: List<BaseMangaPage>): Int? {
    var chapterStart = -1
    var firstImage = -1
    var imageCount = 0
    for (i in items.indices) {
        val item = items[i]
        if (item.chapterIndex != request.chapterIndex) continue
        if (chapterStart < 0) chapterStart = i
        if (item is MangaPage) {
            if (firstImage < 0) firstImage = i
            imageCount++
        }
    }
    if (chapterStart < 0) return null
    if (firstImage < 0) return chapterStart
    val page = abs(request.page).coerceIn(0, imageCount - 1)
    val exact = items.indexOfFirst {
        it is MangaPage && it.chapterIndex == request.chapterIndex && it.index == page
    }
    return if (exact >= 0) exact else firstImage
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
    // 固定白字黑描边: 页脚压在漫画图片上, 不随主题切换
    val fill = Color.White.copy(alpha = 0.78f)
    val outline = Color.Black.copy(alpha = 0.78f)
    Box(
        modifier
            .fillMaxWidth()
            // 对照 activity_manga.xml: 高 20dp + marginBottom 16dp; 16dp padding + 控件内 10dp inset
            .padding(bottom = DesignTokens.spacingLg)
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
    // 有意偏离原版自适应的 ~7dp：12sp 在 20dp 信息条内更清晰可读
    val style = LocalTextStyle.current.copy(fontSize = 12.sp)
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
    transitionState: MutableTransitionState<Boolean>,
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
    // targetState 由屏幕层 (MangaReaderScreenContent) 随 menuVisible 同步, 此处只消费:
    // 三处 AnimatedVisibility 共用同一转场, 顶/底栏同时滑入滑出 (对照原版
    // anim_readbook_top_in/out + bottom_in/out, 200ms), 拦截层随转场淡入淡出。
    // 菜单滑入动画与系统栏动画彼此独立 (对齐原版: 原版两者完全无关——
    // 菜单动画是纯位移, padding 由 insets 流单独逐帧驱动), 无任何时序协调。
    Box(Modifier.fillMaxSize()) {
        // 全屏拦截触摸, 点击空白收起菜单 (对照原版 vwMenuBg; 仅菜单可见/进出场期间拦截)
        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() }
            )
        }
        AnimatedVisibility(
            visibleState = transitionState,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(200)) { -it },
            exit = slideOutVertically(tween(200)) { -it },
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
            visibleState = transitionState,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(200)) { it },
            exit = slideOutVertically(tween(200)) { it },
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
    // 顶栏背景统一走 ThemeStore bottomBackground, 文字 primaryText
    val colors = AppTheme.colors
    // 对照原版 TitleBar fitStatusBar=true: 系统栏恢复时顶栏避开状态栏,
    // 沉浸式全屏时 inset=0 无多余空白。逐帧跟随 insets (对齐原版 TitleBar 的
    // setOnApplyWindowInsetsListenerCompat → topPadding = insets.top):
    // 系统栏显隐动画期间 padding 平滑增长, 不会对静止顶栏产生离散跳变。
    // 注意: 这里不用配置驱动的 readerSystemBarFixedPadding —— 那是正文内容区"占位避让"语义
    // (动画期间零重排), 原版菜单栏 TitleBar 恰恰是逐帧跟随系统栏可见性的。
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bottomBackground)
            .platformStatusBarPadding()
            .padding(horizontal = DesignTokens.spacingDefault),
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
            modifier = Modifier.weight(1f).padding(end = DesignTokens.spacingMd),
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
            .background(colors.bottomBackground)
            // 对照原版 MangaMenu.initView: bottomMenu.applyNavigationBarPadding()
            // (沉浸式全屏时 inset=0 无多余空白)。逐帧跟随 insets, 与顶栏
            // platformStatusBarPadding 同理 (对齐原版 insets listener 语义),
            // 导航栏显隐动画期间 padding 平滑变化, 无离散跳变。
            .platformNavigationBarPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingMd),
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
            .padding(horizontal = DesignTokens.spacingMd)
            .clickable { onClick() }
            .padding(vertical = DesignTokens.spacingMd),
    )
}

// ---- 渲染区图片单元格 (列表/手势/转场页均在 shared MangaRenderLayer) ----

/**
 * GIF 播完翻页的平台注入槽 (对照原版 ReadMangaActivity gifAutoNext 接线): 单元格在组合期
 * Provide, 平台图片槽 (Android MangaCoilImage / skiko MangaSkiaImage) 读取后把渲染器实例上报给
 * [MangaRenderState] 的 GIF 注册表, 并设置装填/翻页三项回调。
 *
 * 无 GIF 能力的平台 (desktop 无动图解码, iOS/鸿蒙无 GIF 分支) 不读取, 保持 no-op。
 */
class MangaGifSlot(
    /** 是否启用播完翻页 (横向模式且设置开启) */
    val enabled: () -> Boolean,
    /** 此页是否为停稳的居中页 (播完翻页只装填当前页) */
    val isArmTarget: () -> Boolean,
    /** 播完翻页动作, 返回是否真的翻动 (受阻时渲染器自首帧重播下轮再试) */
    val onTurnPage: () -> Boolean,
    /** 渲染器实例就绪回调 (上报给注册表) */
    val onRenderer: (() -> MangaRenderState.MangaPageRenderer?) -> Unit,
)

val LocalMangaGifSlot = staticCompositionLocalOf<MangaGifSlot?> { null }

@Composable
private fun LazyItemScope.MangaPageCell(
    url: String,
    horizontal: Boolean,
    imageSlot: @Composable (
        String, Modifier, Boolean, MangaColorFilterConfig, Boolean,
        (MangaCellState) -> Unit, Int, (String) -> Unit
    ) -> Unit,
    colorFilterConfig: MangaColorFilterConfig,
    grayEnabled: Boolean,
    index: Int,
    renderState: MangaRenderState?,
) {
    // GIF 播完翻页接线 (对照原版 ReadMangaActivity 滚动停稳回调 → MangaVH 装填):
    // 本单元格按 index 注册进 renderState 的 GIF 注册表, 平台图片槽经 [LocalMangaGifSlot]
    // 注入停稳/翻页回调并上报渲染器实例; 出组合即注销 (LazyColumn 虚拟化移出的页不再参与)。
    val gifRenderer = remember { mutableStateOf<() -> MangaRenderState.MangaPageRenderer?>({ null }) }
    DisposableEffect(index, renderState) {
        if (renderState != null) {
            val getter = { gifRenderer.value() }
            renderState.registerGifCell(index, getter)
            onDispose { renderState.unregisterGifCell(index, getter) }
        } else {
            onDispose { }
        }
    }
    // 图片加载状态驱动转圈/占位/重试 (对照 app 端 MangaRenderScreen.MangaPageCell);
    // 状态完全由平台图片槽经 onLoadState 上报 (Android onStateChange / Coil LaunchedEffect),
    // 不设 onSizeChanged 兜底 —— 兜底会在 LOADING 时把占位高度误判为出图, 与平台上报互相
    // 覆写导致"转圈闪现/ERROR 被盖/高度 H↔内容 抖动" (桌面端"一直加载中"观感)
    var load by remember(url) { mutableStateOf(MangaCellState.LOADING) }
    // 下载进度文本 (对照原版 MangaPageImageView.onProgress → 转圈内百分比; 原版布局初始文本为 "0%")。
    // 初始即显示 0% (加载开始就有进度字, 不等到第一个进度回调)
    var progress by remember(url) { mutableStateOf("0%") }
    // 重试计数: "重新加载"点击自增, 平台图片槽据此重试 (重试跳过内存缓存读但仍写回)
    var retryTick by remember(url) { mutableStateOf(0) }
    val cellModifier = when {
        horizontal -> Modifier.fillParentMaxSize()
        load == MangaCellState.SUCCESS -> Modifier.fillMaxWidth()
        // LOADING/ERROR 占位: 用典型漫画页宽高比 (2:3) 而非整屏高。整屏高占位在
        // 成图后塌成内容高, 高度跳变会扰动居中页计算 (中心跳动→误触发跨章/进度回退),
        // 是桌面端"闪现/错位"的主要放大器; 2:3 占位高度≈成图高度, 跳变消失
        else -> Modifier.fillMaxWidth().aspectRatio(2f / 3f)
    }
    Box(
        cellModifier.background(MangaReaderBackground),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalMangaGifSlot provides if (renderState == null) null else MangaGifSlot(
                    enabled = { renderState.gifAutoNext },
                    isArmTarget = { renderState.isIdleCenterPage(index) },
                    onTurnPage = { renderState.onGifTurnPage() },
                    onRenderer = { gifRenderer.value = it },
                )
            ) {
            imageSlot(
                url,
                if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                horizontal,
                colorFilterConfig,
                grayEnabled,
                { load = it },
                retryTick,
                { progress = it },
            )
            }
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
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.size(48.dp),
                    )
                    // 下载进度叠在转圈环心 (对照 app 端 MangaRenderScreen: 转圈 + Text(progress))
                    Text(text = progress, color = Color.White)
                } else {
                    Text(
                        text = stringResource(Res.string.reload),
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { retryTick++ }
                            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
                    )
                }
            }
        }
    }
}

