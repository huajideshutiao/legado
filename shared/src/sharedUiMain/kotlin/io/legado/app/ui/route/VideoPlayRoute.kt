package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayUiEvent
import io.legado.app.ui.book.video.VideoPlayerScreenContent
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef

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

    val screenModel = screenModelStore.getOrCreateTyped(entry) { VideoPlayScreenModel() }
    val state by screenModel.state.collectAsState()

    // 用路由持有的 Book 初始化章节状态 (bookName/chapterTitle/curChapterIndex/inShelf)
    LaunchedEffect(book) {
        screenModel.dispatch(VideoPlayUiEvent.ShowBook(book))
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
    val onOpenToc: () -> Unit = { navigator.push(AppRoute.Toc(book.toRouteRef())) }
    val onOpenChangeSource: () -> Unit =
        { navigator.push(AppRoute.ChangeSource(book.toRouteRef())) }
    // 对照 Activity onTitleClick: bookInfoResult.launch(IntentData.book=...)
    val onTitleClick: () -> Unit = { navigator.push(AppRoute.BookInfo(book.toRouteRef())) }
    // 对照 Activity editSource: sourceEditResult.launch
    val onEditSource: () -> Unit = {
        screenModel.shared.curBookSource?.let {
            navigator.push(AppRoute.BookSourceEdit(it.bookSourceUrl))
        }
    }
    // 对照 Activity openReview: viewModel.openCommentDialog
    val onOpenReview: () -> Unit = { navigator.push(AppRoute.ReviewPost(book.toRouteRef())) }

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
                    painter = rememberPainter("ic_refresh_black_24dp"),
                    contentDescription = rememberString("refresh"),
                    tint = Color.White,
                )
            }
            IconButton(onClick = screenModel::onToggleShelf) {
                Icon(
                    painter = rememberPainter(
                        if (state.inShelf) "ic_star" else "ic_star_border"
                    ),
                    contentDescription = rememberString("favorites"),
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
                onAddBookmark = { screenModel.onAddBookmark(0L, 0L) },
                onAppLog = screenModel::onShowAppLog,
            )
        },
    )
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
                painter = rememberPainter("ic_more_vert"),
                contentDescription = rememberString("more_menu"),
                tint = Color.White,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val dismiss = { expanded = false }
            DropdownMenuItem(onClick = { dismiss(); onFullScreen() }) {
                Text(rememberString("full_screen"))
            }
            if (hasLogin) {
                DropdownMenuItem(onClick = { dismiss(); onLogin() }) {
                    Text(rememberString("login"))
                }
            }
            DropdownMenuItem(onClick = { dismiss(); onCopyPlayUrl() }) {
                Text(rememberString("copy_play_url"))
            }
            DropdownMenuItem(onClick = { dismiss(); onSourceVariable() }) {
                Text(rememberString("set_source_variable"))
            }
            DropdownMenuItem(onClick = { dismiss(); onBookVariable() }) {
                Text(rememberString("set_book_variable"))
            }
            DropdownMenuItem(onClick = { dismiss(); onEditSource() }) {
                Text(rememberString("edit_book_source"))
            }
            if (hasReview) {
                DropdownMenuItem(onClick = { dismiss(); onReview() }) {
                    Text(rememberString("review"))
                }
            }
            DropdownMenuItem(onClick = { dismiss(); onAddBookmark() }) {
                Text(rememberString("bookmark_add"))
            }
            DropdownMenuItem(onClick = { dismiss(); onAppLog() }) {
                Text(rememberString("log"))
            }
        }
    }
}
