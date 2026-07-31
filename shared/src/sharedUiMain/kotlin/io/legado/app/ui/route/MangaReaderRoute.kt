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
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.manga.MangaReaderScreenContent
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.MangaReaderUiEvent
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch

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
    val book = route.book.asBook()

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
                    // 换源回传新 book + toc (对照 app 端 onSourceChanged)
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        screenModel.dispatch(MangaReaderUiEvent.ChangeSource(cs.book, cs.toc))
                    }
                }
            }
        }
    }

    // 返回栈由导航器统一管理; 目录/换源派发独立 BookRef 快照 + resultKey
    val onBack: () -> Unit = { navigator.pop() }
    val onOpenToc: () -> Unit =
        { navigator.push(AppRoute.Toc(book.toRouteRef()), resultKey = RouteResults.TOC) }
    val onOpenChangeSource: () -> Unit =
        {
            navigator.push(
                AppRoute.ChangeSource(book.toRouteRef()),
                resultKey = RouteResults.CHANGE_SOURCE
            )
        }

    // 书签编辑对话框状态 (对照 VideoPlayRoute pendingBookmark)
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    // 颜色滤镜对话框 (对照 app 端 showDialogFragment<MangaColorFilterDialog>)
    var showColorFilterDialog by remember { mutableStateOf(false) }
    // 页脚配置对话框 (对照 app 端 showDialogFragment<MangaFooterSettingDialog>)
    var showFooterConfigDialog by remember { mutableStateOf(false) }

    MangaReaderScreenContent(
        bookName = state.bookName,
        chapterTitle = state.chapterTitle,
        images = state.images,
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
        onBack = onBack,
        onPrevChapter = { screenModel.dispatch(MangaReaderUiEvent.PrevChapter) },
        onNextChapter = { screenModel.dispatch(MangaReaderUiEvent.NextChapter) },
        onRetry = { screenModel.dispatch(MangaReaderUiEvent.Retry) },
        onRefresh = { screenModel.dispatch(MangaReaderUiEvent.Refresh) },
        onOpenToc = onOpenToc,
        onOpenChangeSource = onOpenChangeSource,
        onAddBookmark = {
            screenModel.buildBookmark()?.let { editingBookmark = it }
        },
        onSaveImage = { url ->
            scope.launch {
                runCatching {
                    val destPath = PlatformServiceProviders.get().files.saveFile(
                        "manga-${systemCurrentTimeMillis()}.jpg"
                    ) ?: return@launch
                    val ok = screenModel.platformRenderer?.saveImage(
                        url, screenModel.currentBook, screenModel.currentSource, destPath
                    ) ?: false
                    Toasters.get().toast(if (ok) "保存成功" else "保存失败")
                }.onFailure {
                    AppLog.put("保存图片出错\n${it.localizedMessage}", it)
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
}
