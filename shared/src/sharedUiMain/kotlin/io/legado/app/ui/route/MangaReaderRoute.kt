package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.manga.MangaReaderScreenContent
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.MangaReaderUiEvent
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.pre_download
import legado.shared.generated.resources.setting_manga_auto_page_speed
import org.jetbrains.compose.resources.stringResource

/**
 * 漫画阅读页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [MangaReaderScreenModel], 渲染 [MangaReaderScreenContent]。
 *
 * 对照 app 端 [io.legado.app.ui.book.manga.ReadMangaActivity]:
 * - onActivityCreated viewModel.initData → Init 事件
 * - Content LaunchedEffect 监听 RouteResults.TOC → OpenChapter 事件
 * - MangaMenuAction.ADD_BOOKMARK → buildBookmark + BookmarkDialog
 * - onLongTap → saveImage (平台文件选择器 + 平台 saveImage)
 * - MangaMenuAction.HORIZONTAL_SCROLL → toggleHorizontal
 * - 目录/换源 push 带 resultKey, 回填章节/源
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
                RouteResults.TOC -> {
                    // 目录回传章节定位 (对照 app 端 viewModel.openChapter)
                    (result.payload as? RouteResultPayload.Toc)?.let { toc ->
                        screenModel.dispatch(
                            MangaReaderUiEvent.OpenChapter(toc.chapterIndex, toc.chapterPos)
                        )
                    }
                }

                RouteResults.CHANGE_SOURCE -> {
                    // 换源回传新 source + book + toc: 必须走 changeTo 完成迁移+落库,
                    // 只 initMangaData 会丢目录并在书架残留旧书
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        screenModel.dispatch(
                            MangaReaderUiEvent.ChangeSource(cs.source, cs.book, cs.toc)
                        )
                    }
                }
            }
        }
    }

    // 阅读计时 + 退出落库/上传进度 (对照 app 端 onResume ReadTimeRecorder.start /
    // onPause ReadTimeRecorder.end + saveRead + uploadProgress + cancelPreDownloadTask)
    DisposableEffect(Unit) {
        screenModel.onEnter()
        onDispose { screenModel.onLeave() }
    }

    // 返回栈由导航器统一管理; 目录派发独立 BookRef 快照 + resultKey
    val onBack: () -> Unit = { navigator.pop() }
    val onOpenToc: () -> Unit =
        { navigator.push(AppRoute.Toc(book.toRouteRef()), resultKey = RouteResults.TOC) }
    // 顶栏标题点击进书籍详情 (对照 app 端 MangaMenu toolbar click → openBookInfoActivity)
    val onOpenBookInfo: () -> Unit = { navigator.push(AppRoute.BookInfo(book.toRouteRef())) }

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
        onPrevChapter = { screenModel.dispatch(MangaReaderUiEvent.PrevChapter) },
        onNextChapter = { screenModel.dispatch(MangaReaderUiEvent.NextChapter) },
        onCenterItemChanged = { screenModel.onCenterItemChanged(it) },
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
                    // scope 是 rememberCoroutineScope (主线程调度), 阻塞式选择器必须切 IO
                    val destPath = withContext(IoDispatcher) {
                        PlatformServiceProviders.get().files.saveFile(
                            "manga-${systemCurrentTimeMillis()}.jpg"
                        )
                    } ?: return@launch
                    val ok = screenModel.platformRenderer?.saveImage(
                        url, screenModel.currentBook, screenModel.currentSource, destPath
                    ) ?: false
                    Toasters.get().toast(if (ok) "保存成功" else "保存失败")
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
            if (!PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)) {
                navigator.push(AppRoute.ReviewPost(book.toRouteRef()))
            }
        },
        preloadImage = screenModel.preloadImage,
        imageSlot = { url, modifier, horizontal, colorFilterConfig, grayEnabled ->
            screenModel.platformRenderer?.Image(
                url = url,
                modifier = modifier,
                horizontal = horizontal,
                book = screenModel.currentBook,
                source = screenModel.currentSource,
                colorFilterConfig = colorFilterConfig,
                grayEnabled = grayEnabled,
            )
        },
    )

    // 书签编辑对话框 (对照 VideoPlayRoute BookmarkDialog + app addBookmark)
    editingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = false,
            onConfirm = { updated ->
                scope.launch { AppDbProviders.get().bookmarkDao.insert(updated) }
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
}
