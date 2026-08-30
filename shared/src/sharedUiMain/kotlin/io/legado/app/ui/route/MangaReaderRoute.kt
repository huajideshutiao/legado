package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.manga.MangaReaderScreenContent
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.MangaReaderUiEvent
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteActiveEffect
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cloud_progress_exceeds_current
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.pre_download
import legado.shared.generated.resources.setting_manga_auto_page_speed
import legado.shared.generated.resources.sync_book_progress_t
import org.jetbrains.compose.resources.stringResource

/**
 * 漫画阅读页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [MangaReaderScreenModel], 渲染 [MangaReaderScreenContent]。
 *
 * 对照 app 端 [io.legado.app.ui.book.manga.ReadMangaActivity]:
 * - onActivityCreated viewModel.initData → Init 事件
 * - Content LaunchedEffect 监听换源回填
 * - MangaMenuAction.ADD_BOOKMARK → buildBookmark + BookmarkDialog
 * - onLongTap → saveImage (平台文件选择器 + 平台 saveImage)
 * - MangaMenuAction.HORIZONTAL_SCROLL → toggleHorizontal
 * - 目录: 窄屏 TocDialogHost 弹窗 (对照原版 push Toc 带 resultKey → OpenChapter 事件); 换源 push 带 resultKey 回填源
 */
@Composable
fun MangaReaderRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.MangaReader
    // asBook() 每次 copy() 新实例, remember(route) 固定后 LaunchedEffect(book) 只在换路由时重启
    val book = remember(route) { route.book.asBook() }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { MangaReaderScreenModel() }
    val state by screenModel.state.collectAsState()
    val batteryLevel by screenModel.batteryLevel.collectAsState()
    val systemTime by screenModel.systemTime.collectAsState()
    // 栈顶判定改为响应式: collectAsState 订阅 backStack, 栈变化触发重组刷新 lambda 捕获值
    val backStack by navigator.backStack.collectAsState()
    val scope = rememberCoroutineScope()

    // 初始化书籍数据, 透传书签跳转参数 (对照 app 端 applyBookmarkPosition: chapterIndex/chapterPos)
    LaunchedEffect(book) {
        screenModel.dispatch(
            MangaReaderUiEvent.Init(book, route.chapterIndex, route.chapterPos)
        )
    }

    // 订阅子页结果回填 (对照 ReadMangaActivity.Content LaunchedEffect + VideoPlayRoute)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).collect { result ->
            when (result.key) {
                RouteResults.CHANGE_SOURCE -> {
                    // 换源回传新 source + book + toc: 必须走 changeTo 完成迁移+落库,
                    // 只 initMangaData 会丢目录并在书架残留旧书
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        screenModel.dispatch(
                            MangaReaderUiEvent.ChangeSource(cs.source, cs.book, cs.toc)
                        )
                    }
                }

                RouteResults.BOOK_INFO -> when (result.payload) {
                    // 书籍详情返回: 删书透传退出, 未删补载缺失章节 (对照原版
                    // ReadMangaActivity.bookInfoActivity 回调: RESULT_OK → setResult(DELETED)+finish,
                    // else → loadOrUpContent)
                    is RouteResultPayload.Deleted -> navigator.pop()
                    is RouteResultPayload.Ok -> navigator.pop(RouteResultPayload.Deleted)
                    else -> screenModel.loadOrUpContent()
                }
            }
        }
    }

    // 阅读计时 + 离开时落库/上传进度/取消预下载 (对照 app 端 onResume ReadTimeRecorder.start /
    // onPause ReadTimeRecorder.end + saveRead + uploadProgress + cancelPreDownloadTask)。
    // 走 RouteActiveEffect 而非 DisposableEffect(Unit): 压栈 (顶栏进详情/换源) 与退到后台
    // 都要按 onPause 收尾, 否则计时继续走、预下载继续把正文写回刚清掉的缓存目录
    RouteActiveEffect(
        entry = entry,
        navigator = navigator,
        onActive = { screenModel.onEnter() },
        onInactive = { screenModel.onLeave() },
    )

    // 云进度同步确认对话框 (对照 app 端 ReadMangaActivity.sureNewProgress)
    var syncProgress by remember { mutableStateOf<BookProgress?>(null) }
    LaunchedEffect(screenModel) {
        ReadBookEvents.newProgressConfirm.collect { progress ->
            syncProgress = progress
        }
    }
    syncProgress?.let { progress ->
        AppAlertDialog(
            onDismissRequest = {
                screenModel.dismissSyncProgress()
                syncProgress = null
            },
            title = stringResource(Res.string.sync_book_progress_t),
            message = stringResource(Res.string.cloud_progress_exceeds_current),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.confirmSyncProgress(progress)
                syncProgress = null
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {
                screenModel.dismissSyncProgress()
                syncProgress = null
            },
        )
    }

    // 返回栈由导航器统一管理; 目录派发独立 BookRef 快照 + resultKey
    val onBack: () -> Unit = { navigator.pop() }
    // 目录弹窗显示开关 (窄屏目录入口, 对照阅读页 ReaderDialogEvent.Toc → TocDialogHost)
    var showTocDialog by remember { mutableStateOf(false) }
    val onOpenToc: () -> Unit = {
        // 目录的内存章节来源 = IntentData.chapterList → TocScreenModel 自身缓存 → DB 兜底
        // (漫画不进 ActiveReadBookRegistry, 未落库的书只有这一条内存通路)。
        // IntentData.book 不传: 目录直接收 screenModel.currentBook, 而 IntentData 是"取一次即
        // 失效"的全局槽, 写了没人取会残留, 被后续深链/详情页的 IntentData.book 消费点捡走
        IntentData.chapterList = screenModel.chapterList
        // 目录弹窗 (对照阅读页 ReaderDialogEvent.Toc → TocDialogHost; 原 push Toc 全屏路由,
        // 迁移后选章经 onOpenChapter 直接处理, 不再走 RouteResults.TOC 回传)
        showTocDialog = true
    }
    // 顶栏标题点击进书籍详情 (对照 app 端 MangaMenu toolbar click → openBookInfoActivity;
    // 带 resultKey 接收删书透传/返回回执)
    val onOpenBookInfo: () -> Unit = {
        navigator.push(
            AppRoute.BookInfo((screenModel.currentBook ?: book).toRouteRef()),
            resultKey = RouteResults.BOOK_INFO,
        )
    }

    // 书签编辑对话框状态 (对照 VideoPlayRoute pendingBookmark)
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    // 颜色滤镜对话框 (对照 app 端 showDialogFragment<MangaColorFilterDialog>)
    var showColorFilterDialog by remember { mutableStateOf(false) }
    // 页脚配置对话框 (对照 app 端 showDialogFragment<MangaFooterSettingDialog>)
    var showFooterConfigDialog by remember { mutableStateOf(false) }
    // 预下载数量 / 自动翻页速度 数字选择框 (对照 app 端 showNumberPickerDialog)
    var showPreDownloadDialog by remember { mutableStateOf(false) }
    var showAutoPageSpeedDialog by remember { mutableStateOf(false) }
    // 点击区域配置 (对照 app 端 showDialogFragment<ClickActionConfigDialog>)
    var showClickRegionDialog by remember { mutableStateOf(false) }

    MangaReaderScreenContent(
        bookName = state.bookName,
        chapterTitle = state.chapterTitle,
        items = state.items,
        contentPos = state.contentPos,
        curFinish = state.curFinish,
        book = screenModel.currentBook,
        bookSource = screenModel.currentSource,
        curChapterIndex = state.curChapterIndex,
        chapterSize = state.chapterSize,
        horizontal = state.horizontal,
        autoPageSpeed = state.autoPageSpeed,
        loading = state.loading,
        jumpTick = state.jumpTick,
        error = state.error,
        batteryLevel = batteryLevel,
        systemTime = systemTime,
        currentPage = state.currentPage,
        pageCount = state.pageCount,
        progressPercent = state.progressPercent,
        colorFilterConfig = state.colorFilterConfig,
        grayEnabled = state.grayEnabled,
        footerConfig = state.footerConfig,
        hideMangaTitle = state.hideMangaTitle,
        disablePageAnim = state.disablePageAnim,
        gifAutoNext = state.gifAutoNext,
        preDownloadNum = state.preDownloadNum,
        hasReview = state.hasReview,
        clickActionConfig = state.clickActionConfig,
        onBack = onBack,
        // 键盘快捷键仅栈顶路由响应 (对照小说阅读端 isTopEntry, 目录/详情等子页在栈顶时不翻背景的书)
        isTopEntry = { backStack.lastOrNull()?.id == entry.id },
        // 菜单显隐 → 系统栏显隐 (对照原版 ReadMangaActivity.upSystemUiVisibility(menuIsVisible)
        // → toggleSystemBar: 菜单显示恢复状态栏/导航栏, 菜单隐藏沉浸式全屏)
        onMenuVisibleChange = { visible ->
            PlatformServiceProviders.getOrNull()?.window?.setSystemBars(
                if (visible) SystemBarsPolicy.Default else SystemBarsPolicy.Hidden
            )
        },
        onPrevChapter = { screenModel.dispatch(MangaReaderUiEvent.PrevChapter) },
        onNextChapter = { screenModel.dispatch(MangaReaderUiEvent.NextChapter) },
        onCenterItemChanged = { item, reanchored -> screenModel.onCenterItemChanged(item, reanchored) },
        onSeekToPage = { screenModel.seekToPage(it) },
        onRetry = { screenModel.dispatch(MangaReaderUiEvent.Retry) },
        onRefresh = { screenModel.dispatch(MangaReaderUiEvent.Refresh) },
        onOpenToc = onOpenToc,
        onOpenBookInfo = onOpenBookInfo,
        onAddBookmark = {
            screenModel.buildBookmark()?.let { editingBookmark = it }
        },
        onSaveImage = { url ->
            scope.launch {
                runCatching {
                    when (screenModel.platformRenderer?.saveImage(
                        url, screenModel.currentBook, screenModel.currentSource
                    )) {
                        true -> Toasters.get().toast("保存成功")
                        false -> Toasters.get().toast("保存失败")
                        null -> Unit
                    }
                }.onFailure {
                    AppLog.put("保存图片出错\n${it.message}", it)
                    Toasters.get().toast("保存失败")
                }
            }
        },
        onToggleHorizontal = { screenModel.toggleHorizontal() },
        onToggleHideTitle = { screenModel.toggleHideTitle() },
        onToggleDisablePageAnim = { screenModel.toggleDisablePageAnim() },
        onToggleGifAutoNext = { screenModel.toggleGifAutoNext() },
        onOpenColorFilter = { showColorFilterDialog = true },
        onOpenFooterConfig = { showFooterConfigDialog = true },
        onOpenPreDownloadNum = { showPreDownloadDialog = true },
        onOpenAutoPageSpeed = { showAutoPageSpeedDialog = true },
        onOpenClickRegionConfig = { showClickRegionDialog = true },
        onOpenReview = {
            // 对照 Activity openReview: viewModel.openCommentDialog → ReviewListDialog(book, chapter, 0)
            val chapter = screenModel.currentChapter
            PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)
        },
        preloadImage = screenModel.preloadImage,
        imageSlot = { url, modifier, horizontal, colorFilterConfig, grayEnabled, onLoadState, retryTick, onProgress ->
            screenModel.platformRenderer?.Image(
                url = url,
                modifier = modifier,
                horizontal = horizontal,
                book = screenModel.currentBook,
                source = screenModel.currentSource,
                colorFilterConfig = colorFilterConfig,
                grayEnabled = grayEnabled,
                onLoadState = onLoadState,
                retryTick = retryTick,
                onProgress = onProgress,
            )
        },
    )

    // 书签编辑对话框 (对照 VideoPlayRoute BookmarkDialog + app addBookmark)
    editingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = false,
            onConfirm = { updated ->
                scope.launch {
                    runCatching { AppDbProviders.get().bookmarkDao.insert(updated) }
                        .onFailure { AppLog.put("保存书签出错\n${it.message}", it) }
                }
                editingBookmark = null
            },
            onDismiss = { editingBookmark = null },
        )
    }

    // 颜色滤镜对话框 (对照 app 端 MangaColorFilterDialog)
    if (showColorFilterDialog) {
        MangaColorFilterDialog(
            config = state.colorFilterConfig,
            grayEnabled = state.grayEnabled,
            onColorFilterChange = { screenModel.updateColorFilter(it) },
            onGrayChange = { screenModel.updateGray(it) },
            onDismiss = { showColorFilterDialog = false },
        )
    }

    // 页脚配置对话框 (对照 app 端 MangaFooterSettingDialog)
    if (showFooterConfigDialog) {
        MangaFooterSettingDialog(
            config = state.footerConfig,
            onConfigChange = { screenModel.updateFooterConfig(it) },
            onDismiss = { showFooterConfigDialog = false },
        )
    }

    // 预下载章节数 (对照 app 端 showNumberPickerDialog(min=0, max=9999))
    if (showPreDownloadDialog) {
        NumberPickerDialog(
            title = stringResource(Res.string.pre_download),
            value = state.preDownloadNum,
            range = 0..9999,
            onConfirm = {
                screenModel.setPreDownloadNum(it)
                showPreDownloadDialog = false
            },
            onDismiss = { showPreDownloadDialog = false },
        )
    }

    // 自动翻页速度 (对照 app 端 showNumberPickerDialog(min=1, max=9999))
    if (showAutoPageSpeedDialog) {
        NumberPickerDialog(
            title = stringResource(Res.string.setting_manga_auto_page_speed),
            value = state.autoPageSpeed,
            range = 1..9999,
            onConfirm = {
                screenModel.setAutoPageSpeed(it)
                showAutoPageSpeedDialog = false
            },
            onDismiss = { showAutoPageSpeedDialog = false },
        )
    }

    // 点击区域配置 (对照 app 端 ClickActionConfigDialog, 即时写入)
    if (showClickRegionDialog) {
        ClickActionDialog(
            clickActionConfig = state.clickActionConfig,
            onConfirm = { screenModel.updateClickActionConfig(it) },
            onDismiss = { showClickRegionDialog = false },
        )
    }

    // 目录弹窗 (对照阅读页 ReaderDialogEvent.Toc → TocDialogHost 全高底部弹窗;
    // 选章跳转对齐原 RouteResults.TOC 回传消费 → OpenChapter 事件, 关闭由宿主回调处理)
    if (showTocDialog) {
        TocDialogHost(
            // 必须用阅读器现行书籍, 不能用路由快照 book: 换源后 currentBook 换了 bookUrl,
            // 旧 url 的行已被删, 目录按旧 url 查库只会空白; 进度类字段也停在进入时的值
            book = screenModel.currentBook ?: book,
            navigator = navigator,
            onOpenChapter = { index, pos ->
                showTocDialog = false
                screenModel.dispatch(MangaReaderUiEvent.OpenChapter(index, pos))
            },
            onDismiss = { showTocDialog = false },
        )
    }
}
