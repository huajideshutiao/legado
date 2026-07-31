package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.data.AppDbProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayUiEvent
import io.legado.app.ui.book.video.VideoPlayerScreenContent
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark_add
import legado.shared.generated.resources.copy_play_url
import legado.shared.generated.resources.edit_book_source
import legado.shared.generated.resources.favorites
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.review
import legado.shared.generated.resources.set_book_variable
import legado.shared.generated.resources.set_source_variable
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 视频播放页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [VideoPlayScreenModel], 渲染 [VideoPlayerScreenContent]。
 *
 * 对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity]:
 * - onActivityCreated viewModel.initData → ShowBook 事件 + ScreenModel.shared.initData
 * - chapterListData/videoUrl/resolutions observe → ScreenModel state combine
 * - onBackPressedDispatcher 三级返回 (横屏→竖屏 / 全屏→退出全屏 / 否则 finish) →
 *   onBack 基于 state.isFullScreen 处理 (横竖屏属平台专属, 由 host 接入)
 * - onTitleClick → navigator.push(BookInfo)
 * - VideoTitleActions (refresh/shelf/overflowMenu) → titleActions slot 注入
 * - 菜单项 (fullScreen/login/copyPlayUrl/sourceVar/bookVar/editSource/review/bookmark/log) →
 *   ScreenModel 方法 (平台能力走 PlatformCapabilityProviders, 平台专属 Dialog 占位)
 *
 * 平台专属能力 (播放器渲染层 videoRenderSlot + 播放控制实现 + 横竖屏切换) 待下沉,
 * 下沉前 videoRenderSlot 用空 Composable 占位, 播放控制方法为空实现。
 */
@Composable
fun VideoPlayRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.VideoPlay
    // BookRef -> Book, 导航时再 toRouteRef() 转回 (防御性拷贝, 避免与路由持有对象别名)
    val book = route.book.asBook()

    val prefStore = LocalPreferenceStoreProvider.current
    val screenModel = screenModelStore.getOrCreateTyped(entry) { VideoPlayScreenModel(prefStore) }
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 用路由持有的 Book 初始化章节状态 (bookName/chapterTitle/curChapterIndex/inShelf);
    // chapterIndex/chapterPos 用于书签/目录回传定位 (对照 AudioPlayUiEvent.Init)
    LaunchedEffect(book) {
        screenModel.dispatch(VideoPlayUiEvent.ShowBook(book, route.chapterIndex, route.chapterPos))
    }

    // 退出时保存真实进度 + 释放播放器 (对照 app onPause/onDestroy; onCleared 兜底)
    DisposableEffect(screenModel) {
        onDispose { screenModel.onExit() }
    }

    // 订阅子页结果回填 (对照 Activity bookInfoResult/sourceEditResult)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).collect { result ->
            when (result.key) {
                RouteResults.TOC -> {
                    // 目录/书签回传章节定位: 写回 Book + 落库 + 加载章节 (对照 app openChapter)
                    (result.payload as? RouteResultPayload.Toc)?.let { toc ->
                        screenModel.shared.curBook?.let { book ->
                            scope.launch {
                                // 先持久化 chapterIndex/chapterPos (写 Book + DB + Preference)
                                screenModel.shared.applyChapterOverride(
                                    book, toc.chapterIndex, toc.chapterPos
                                )
                                // 再加载章节 (persistProgress=false 避免覆盖刚写入的 durChapterPos)
                                screenModel.shared.loadChapter(
                                    toc.chapterIndex, persistProgress = false
                                )
                            }
                        }
                    }
                }

                RouteResults.CHANGE_SOURCE -> {
                    // 换源回传新 source + book + toc: 用外部章节初始化
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        screenModel.shared.initWithExternalChapters(
                            cs.book, cs.source, cs.toc, cs.book.durChapterIndex
                        )
                    }
                }

                RouteResults.BOOK_SOURCE_EDIT -> {
                    // 书源编辑后重新拉取书源 + 章节 (对照 app upSource + refresh)
                    screenModel.shared.curBook?.let { b -> screenModel.shared.initData(b) }
                }

                RouteResults.BOOK_INFO -> {
                    // 书籍详情返回: 查库确认是否仍在书架 (BookInfoRoute 不区分 Ok/Deleted payload)
                    val inShelf = withContext(IoDispatcher) {
                        AppDbProviders.get().bookDao.getBook(book.bookUrl) != null
                    }
                    if (!inShelf) navigator.pop()
                    else screenModel.dispatch(VideoPlayUiEvent.UpdateInShelf(true))
                }
            }
        }
    }

    // 返回栈由导航器统一管理; 对照 Activity onBackPressedDispatcher 三级返回
    // (横屏→竖屏属平台专属, 此处仅处理全屏→非全屏 / 否则 pop)
    val onBack: () -> Unit = {
        if (state.isFullScreen) {
            screenModel.setFullScreen(false)
        } else {
            navigator.pop()
        }
    }
    val onOpenToc: () -> Unit =
        { navigator.push(AppRoute.Toc(book.toRouteRef()), resultKey = RouteResults.TOC) }
    val onOpenChangeSource: () -> Unit =
        {
            navigator.push(
                AppRoute.ChangeSource(book.toRouteRef()),
                resultKey = RouteResults.CHANGE_SOURCE
            )
        }
    // 对照 Activity onTitleClick: bookInfoResult.launch(IntentData.book=...)
    val onTitleClick: () -> Unit =
        { navigator.push(AppRoute.BookInfo(book.toRouteRef()), resultKey = RouteResults.BOOK_INFO) }
    // 对照 Activity editSource: sourceEditResult.launch
    val onEditSource: () -> Unit = {
        screenModel.shared.curBookSource?.let {
            navigator.push(
                AppRoute.BookSourceEdit(it.bookSourceUrl),
                resultKey = RouteResults.BOOK_SOURCE_EDIT,
            )
        }
    }
    // 对照 Activity openReview: viewModel.openCommentDialog
    val onOpenReview: () -> Unit = { navigator.push(AppRoute.ReviewPost(book.toRouteRef())) }

    // 平台对话框状态 (对照 TocRoute showLogDialog/editingBookmark)
    var showLogDialog by remember { mutableStateOf(false) }

    VideoPlayerScreenContent(
        bookName = state.bookName,
        chapterTitle = state.chapterTitle,
        curChapterIndex = state.curChapterIndex,
        chapterSize = state.chapterSize,
        onBack = onBack,
        onOpenToc = onOpenToc,
        onOpenChangeSource = onOpenChangeSource,
        onPrevChapter = screenModel::onPrevChapter,
        onNextChapter = screenModel::onNextChapter,
        videoRenderSlot = { modifier ->
            val controller = screenModel.controller
            val platform = screenModel.platform
            if (controller != null && platform != null) {
                platform.Render(controller, screenModel, modifier)
            }
        },
        onPlayPause = screenModel::onPlayPause,
        onSeekDelta = screenModel::onSeekDelta,
        onSpeedChange = screenModel::onSpeedChange,
        controlsVisible = state.controlsVisible,
        onToggleControls = screenModel::onToggleControls,
        onTitleClick = onTitleClick,
        titleActions = {
            // 对照 Activity VideoTitleActions: refresh + shelf + OverflowMenu
            IconButton(onClick = screenModel::onRefreshChapter) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                    contentDescription = stringResource(Res.string.refresh),
                    tint = Color.White,
                )
            }
            IconButton(onClick = screenModel::onToggleShelf) {
                Icon(
                    painter = rememberPainter(
                        if (state.inShelf) "ic_star" else "ic_star_border"
                    ),
                    contentDescription = stringResource(Res.string.favorites),
                    tint = Color.White,
                )
            }
            VideoOverflowMenu(
                hasLogin = screenModel.shared.curBookSource?.hasLogin() == true,
                hasReview = !screenModel.shared.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
                onFullScreen = screenModel::onToggleFullScreen,
                onLogin = screenModel::onShowLogin,
                onCopyPlayUrl = screenModel::onCopyPlayUrl,
                onSourceVariable = screenModel::onShowSourceVariable,
                onBookVariable = screenModel::onShowBookVariable,
                onEditSource = onEditSource,
                onReview = onOpenReview,
                onAddBookmark = screenModel::onAddBookmark,
                onAppLog = { showLogDialog = true },
            )
        },
    )

    // 日志对话框 (对照 TocRoute AppLogDialog)
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 书签编辑对话框 (对照 TocRoute BookmarkDialog + app addBookmark)
    state.pendingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = false,
            onConfirm = { updated ->
                scope.launch { AppDbProviders.get().bookmarkDao.insert(updated) }
                screenModel.clearPendingBookmark()
            },
            onDismiss = { screenModel.clearPendingBookmark() },
        )
    }
}

/**
 * 视频页溢出菜单 (对照 app 端 VideoTitleActions 中的 OverflowMenu)。
 * 黑底白字风格, 与 [io.legado.app.ui.book.video.VideoTitleBar] 一致。
 */
@Composable
private fun VideoOverflowMenu(
    hasLogin: Boolean,
    hasReview: Boolean,
    onFullScreen: () -> Unit,
    onLogin: () -> Unit,
    onCopyPlayUrl: () -> Unit,
    onSourceVariable: () -> Unit,
    onBookVariable: () -> Unit,
    onEditSource: () -> Unit,
    onReview: () -> Unit,
    onAddBookmark: () -> Unit,
    onAppLog: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = Color.White,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val dismiss = { expanded = false }
            DropdownMenuItem(onClick = { dismiss(); onFullScreen() }) {
                Text(stringResource(Res.string.full_screen))
            }
            if (hasLogin) {
                DropdownMenuItem(onClick = { dismiss(); onLogin() }) {
                    Text(stringResource(Res.string.login))
                }
            }
            DropdownMenuItem(onClick = { dismiss(); onCopyPlayUrl() }) {
                Text(stringResource(Res.string.copy_play_url))
            }
            DropdownMenuItem(onClick = { dismiss(); onSourceVariable() }) {
                Text(stringResource(Res.string.set_source_variable))
            }
            DropdownMenuItem(onClick = { dismiss(); onBookVariable() }) {
                Text(stringResource(Res.string.set_book_variable))
            }
            DropdownMenuItem(onClick = { dismiss(); onEditSource() }) {
                Text(stringResource(Res.string.edit_book_source))
            }
            if (hasReview) {
                DropdownMenuItem(onClick = { dismiss(); onReview() }) {
                    Text(stringResource(Res.string.review))
                }
            }
            DropdownMenuItem(onClick = { dismiss(); onAddBookmark() }) {
                Text(stringResource(Res.string.bookmark_add))
            }
            DropdownMenuItem(onClick = { dismiss(); onAppLog() }) {
                Text(stringResource(Res.string.log))
            }
        }
    }
}
