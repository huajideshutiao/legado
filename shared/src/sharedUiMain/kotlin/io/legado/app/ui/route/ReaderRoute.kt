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
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.ReaderScreen
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.ReaderUiActions
import io.legado.app.ui.book.read.ReaderUiState
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cloud_progress_exceeds_current
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.sync_book_progress_t
import org.jetbrains.compose.resources.stringResource

/**
 * 小说阅读器 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ReaderScreenModel]，渲染 [ReaderScreen]。
 * [ReadMenuState] 等平台依赖经 [ReaderPlatformProviders] 注入；各端入口必须在进入路由前注册。
 *
 * 对照 [TocRoute] 的 ScreenModel + dispatch + Screen 组合模式。
 */
@Composable
fun ReaderRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.Reader
    val book = route.book.asBook()
    val provider = requireNotNull(ReaderPlatformProviders.getOrNull()) {
        "ReaderPlatformProvider must be registered before opening ReaderRoute"
    }

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        ReaderScreenModel(
            menuControllerFactory = { model -> provider.createMenuController(navigator, model) },
            getBatteryLevel = { provider.getBatteryLevel() },
        )
    }

    DisposableEffect(screenModel, provider) {
        provider.onEnter(screenModel)
        onDispose { provider.onExit(screenModel) }
    }

    // 初始化书籍数据（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）
    LaunchedEffect(book) {
        screenModel.initBook(book, route.chapterIndex, route.chapterPos)
    }

    val state = remember(screenModel) {
        ReaderUiState(
            viewModel = screenModel.viewModel,
            menuState = screenModel.menuState,
            batteryLevel = screenModel.batteryLevel,
        )
    }

    val actions = remember(navigator, screenModel) {
        object : ReaderUiActions {
            // 中心区域单击：显示菜单（菜单隐藏时 ReadMenuOverlay early return，由 ReadViewComposable 接管手势）
            // 菜单显示时 ReadMenuOverlay 的 bg Box 拦截触摸调 onBgClick 收起，不经过本回调
            override fun onPageClick(column: TextColumn?) {
                screenModel.showMenu()
            }

            override fun onPageLongClick(column: TextColumn?) {
                provider.onLongPress(screenModel)
            }

            override fun onBack() {
                navigator.pop()
            }
        }
    }

    val scope = rememberCoroutineScope()

    ReaderScreen(state = state, actions = actions)

    // 云进度同步确认对话框 (对照 app 端 ReadBookActivity.sureNewProgress)
    var syncProgress by remember { mutableStateOf<BookProgress?>(null) }
    LaunchedEffect(screenModel) {
        ReadBookEvents.newProgressConfirm.collect { progress ->
            syncProgress = progress
        }
    }
    syncProgress?.let { progress ->
        AppAlertDialog(
            onDismissRequest = {
                screenModel.viewModel.dismissSyncProgress()
                syncProgress = null
            },
            title = stringResource(Res.string.sync_book_progress_t),
            message = stringResource(Res.string.cloud_progress_exceeds_current),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                screenModel.viewModel.confirmSyncProgress(progress)
                syncProgress = null
            },
            cancelButton = AlertButton(stringResource(Res.string.no)) {
                screenModel.viewModel.dismissSyncProgress()
                syncProgress = null
            },
        )
    }

    // region 路由结果订阅 (目录跳转/换源/书源编辑/书籍信息)

    LaunchedEffect(screenModel, entry.id) {
        navigator.resultsFor(entry.id).collect { result ->
            when (result.key) {
                RouteResults.TOC -> {
                    val payload = result.payload as? RouteResultPayload.Toc ?: return@collect
                    screenModel.openChapter(payload.chapterIndex, payload.chapterPos)
                }

                RouteResults.CHANGE_SOURCE -> {
                    val payload =
                        result.payload as? RouteResultPayload.ChangeSource ?: return@collect
                    screenModel.initBook(payload.book, null, null)
                }

                RouteResults.BOOK_SOURCE_EDIT -> screenModel.viewModel.refreshCurrentChapter()

                RouteResults.BOOK_INFO -> when (result.payload) {
                    is RouteResultPayload.Deleted -> navigator.pop()
                    is RouteResultPayload.Ok -> navigator.pop(RouteResultPayload.Deleted)
                    else -> Unit
                }

                RouteResults.CHANGE_CHAPTER_SOURCE -> {
                    val payload = result.payload as? RouteResultPayload.ChangeChapterContent
                        ?: return@collect
                    val book = screenModel.currentBook ?: return@collect
                    val chapter = screenModel.currentChapter ?: withContext(IoDispatcher) {
                        AppDbProviders.get().bookChapterDao.getChapter(
                            book.bookUrl,
                            screenModel.viewModel.durChapterIndex.value,
                        )
                    } ?: return@collect
                    scope.launch {
                        runCatching {
                            BookStorageProviders.get().saveText(book, chapter, payload.content)
                        }
                        screenModel.viewModel.refreshCurrentChapter()
                    }
                }

                RouteResults.SEARCH_CONTENT -> {
                    val payload = result.payload as? RouteResultPayload.SearchContent
                        ?: return@collect
                    val searchResult = payload.searchResults.getOrNull(payload.searchResultIndex)
                        ?: return@collect
                    screenModel.openChapter(searchResult.chapterIndex)
                }
            }
        }
    }
    // endregion

    // region 对话框渲染 (书签/正文编辑/日志, 由 AndroidReaderMenuState 触发)
    val dialogEvent by screenModel.dialogEvent.collectAsState()
    when (val event = dialogEvent) {
        is ReaderDialogEvent.AddBookmark -> {
            BookmarkDialog(
                bookmark = event.bookmark,
                showDelete = false,
                onConfirm = { updated ->
                    scope.launch {
                        runCatching {
                            AppDbProviders.get().bookmarkDao.insert(updated)
                        }
                    }
                    screenModel.clearDialogEvent()
                },
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        is ReaderDialogEvent.EditContent -> {
            val chapter = screenModel.currentChapter
            ContentEditDialog(
                chapterName = chapter?.title ?: "",
                content = screenModel.currentChapterText,
                onSubmit = { edited ->
                    val book = screenModel.viewModel.book.value
                    if (book != null && chapter != null) {
                        scope.launch {
                            runCatching {
                                BookStorageProviders.get().saveText(book, chapter, edited)
                            }
                            screenModel.viewModel.refreshCurrentChapter()
                        }
                    }
                    screenModel.clearDialogEvent()
                },
                onDismiss = { screenModel.clearDialogEvent() },
                onReset = { screenModel.viewModel.refreshCurrentChapter() },
            )
        }

        is ReaderDialogEvent.Log -> {
            AppLogDialog(onDismiss = { screenModel.clearDialogEvent() })
        }

        null -> Unit
    }
    // endregion
}
