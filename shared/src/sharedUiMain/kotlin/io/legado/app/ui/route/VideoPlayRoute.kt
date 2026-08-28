package io.legado.app.ui.route

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalViewConfiguration
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.book.changeSourceTo
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayUiEvent
import io.legado.app.ui.book.video.VideoPlayerHostContainer
import io.legado.app.ui.book.video.VideoPlayerScreenContent
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.platform.AppShortcutHandler
import io.legado.app.ui.compose.platform.MediaKeyLongPressState
import io.legado.app.ui.compose.platform.hasActiveBackLayer
import io.legado.app.ui.compose.platform.mediaPlaybackKeys
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.format
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bookmark_add
import legado.shared.generated.resources.copy_play_url
import legado.shared.generated.resources.edit_book_source
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_refresh_black_24dp
import legado.shared.generated.resources.in_favorites
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login
import legado.shared.generated.resources.out_favorites
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.review
import legado.shared.generated.resources.set_book_variable
import legado.shared.generated.resources.set_source_variable
import org.jetbrains.compose.resources.StringResource
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
    // asBook() 每次调用都 copy() 新实例, 若不 remember 固定, 路由每次重组 book 都变,
    // LaunchedEffect(book) 会反复重启 → 重拉章节/重跑 JS header 规则 (调窗即报 js 错)
    val book = remember(route) { route.book.asBook() }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { VideoPlayScreenModel() }
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
                    // 换源回传新 source + book + toc: 迁移落库并用外部章节初始化
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        scope.launch {
                            screenModel.shared.curBook?.changeSourceTo(
                                cs.book, cs.toc, !cs.book.isNotShelf
                            )
                            screenModel.shared.initWithExternalChapters(
                                cs.book, cs.source, cs.toc, cs.book.durChapterIndex
                            )
                        }
                    }
                }

                RouteResults.BOOK_SOURCE_EDIT -> {
                    // 书源编辑后直接采用回传的已保存对象 (不再按 origin 查库, 不再整页重初始化)
                    val source =
                        (result.payload as? RouteResultPayload.BookSourceEdit)?.source
                            ?: return@collect
                    screenModel.shared.upBookSource(source)
                }

                RouteResults.BOOK_INFO -> {
                    // 书籍详情返回: 查库确认是否仍在书架 (BookInfoRoute 不区分 Ok/Deleted payload)。
                    // LaunchedEffect 里抛出会连坐 Recomposer, 查库异常按"仍在书架"处理
                    val inShelf = runCatching {
                        withContext(IoDispatcher) {
                            AppDbProviders.get().bookDao.getBook(book.bookUrl) != null
                        }
                    }.onFailure {
                        AppLog.put("查询书架状态出错\n${it.message}", it)
                    }.getOrDefault(true)
                    if (!inShelf) navigator.pop(RouteResultPayload.Deleted)
                    else screenModel.dispatch(VideoPlayUiEvent.UpdateInShelf(true))
                }
            }
        }
    }

    // 返回栈由导航器统一管理; 对照 Activity onBackPressedDispatcher 返回逻辑
    // (系统级全屏与窗口内全屏互斥: 当前是哪种全屏就退哪种, 退全屏后下次 ESC 才 pop)
    val onBack: () -> Unit = {
        val s = screenModel.state.value
        when {
            s.isSystemFullScreen -> screenModel.setSystemFullScreen(false)
            s.isFullScreen -> screenModel.setFullScreen(false)
            else -> navigator.pop()
        }
    }
    // 全屏时系统返回先退全屏 (对照 Activity onBackPressedDispatcher: isFullScreen -> applyFullScreen(false));
    // 两种全屏互斥, 同时只可能有一种激活; 非栈顶不拦截 (栈内页面全程留在 Composition)
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    AppBackHandler(enabled = isTopEntry && (state.isFullScreen || state.isSystemFullScreen)) {
        val s = screenModel.state.value
        when {
            s.isSystemFullScreen -> screenModel.setSystemFullScreen(false)
            s.isFullScreen -> screenModel.setFullScreen(false)
        }
    }

    // 视频页键盘快捷键 (方向键/空格): 走共享 AppShortcutHandler 快捷键栈分发。
    // 与音频页 AudioPlayRoute 同类问题一并收拢 (2026-08 用户实测: 原 handleMediaKeys
    // 依赖 Compose 焦点链, 桌面端未持焦时按键可能完全无响应) —— 快捷键栈在
    // 桌面 Window onKeyEvent (Main.kt) / Android Activity dispatchKeyEvent
    // (BaseComposeActivity) 层无条件收键, 不依赖 Compose 焦点。
    // 键位 (对照原 handleMediaKeys 语义):
    //   ←/→ = seek ∓10s (← TRIGGER 按住连续后退; → 短按松开 seek +10s / 长按 当前倍速×2
    //   松手恢复, 长短按由 MediaKeyLongPressState 判定, 恢复原 handleMediaKeys 完整语义)
    //   ↑/↓ = 上/下一章; Space = 播放/暂停
    // 媒体键 preemptive=true 捕获阶段抢占: 与 Compose 焦点系统单一所有者 (点 ⋯ 后按空格
    // 不再弹菜单+暂停双触发); 弹层打开时 hasActiveBackLayer() 让位 (弹层方向键导航优先)。
    // Esc 由统一路由 (全屏优先退全屏) / handleBackKey 处理, 不在此注册。
    val keyLongPress = remember { MediaKeyLongPressState() }
    // 长按前倍速 (界面内临时捕获): 长按激活时记录, 松手恢复, 不覆盖用户已设倍速
    val prePressSpeed = remember { mutableStateOf(1f) }
    // 长短按阈值取平台 ViewConfiguration (与全应用 clickable 键盘长按手感一致)
    val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    AppShortcutHandler(
        shortcuts = mediaPlaybackKeys,
        // 媒体键捕获阶段抢占 (preemptive=true), 弹层打开时让位 (菜单/对话框方向键导航优先)
        enabled = { isTopEntry && !hasActiveBackLayer() },
        onKeyUp = { shortcut ->
            // 右方向键: 长按松开恢复倍速 / 窗口内松开执行短按 seek +10s (KeyUp 判定)
            if (shortcut.key == Key.DirectionRight) {
                keyLongPress.onRelease(
                    onShortPress = { screenModel.onSeekDelta(10_000L) },
                    onLongPressRelease = {
                        screenModel.onSpeedChange(prePressSpeed.value)
                        screenModel.onGestureText(null)
                    },
                )
            }
        },
    ) { shortcut ->
        when (shortcut.key) {
            Key.Spacebar -> screenModel.onPlayPause()
            Key.DirectionLeft -> screenModel.onSeekDelta(-10_000L)
            Key.DirectionRight -> keyLongPress.onPress(scope, longPressTimeoutMs) {
                // 长按激活: 记录长按前倍速, 切 当前倍速×2 (对齐手势长按语义, 见 VideoGestureController.onLongPress)
                prePressSpeed.value = screenModel.state.value.playbackSpeed
                val boosted = prePressSpeed.value * 2f
                screenModel.onSpeedChange(boosted)
                screenModel.onGestureText("%.1fX".format(boosted))
            }
            Key.DirectionUp -> screenModel.onPrevChapter()
            Key.DirectionDown -> screenModel.onNextChapter()
        }
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
    // 对照 Activity openReview: viewModel.openCommentDialog → ReviewListDialog(book, chapter, 0)
    val onOpenReview: () -> Unit = {
        val chapter = state.chapters.getOrNull(state.curChapterIndex)
        PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)
    }

    // 平台对话框状态 (对照 TocRoute showLogDialog/editingBookmark)
    var showLogDialog by remember { mutableStateOf(false) }

    // 弹层可见性: 桌面端 mpv 是重量级原生窗口 (airspace), 会盖住 Compose 弹层
    // (菜单/对话框), 弹层打开时上报平台临时隐藏 mpv 窗口, 关闭后恢复
    var menuExpanded by remember { mutableStateOf(false) }
    val overlayVisible = menuExpanded || showLogDialog || state.pendingBookmark != null
    LaunchedEffect(overlayVisible) {
        screenModel.platform?.setOverlayVisible(overlayVisible)
    }

    // 选集字数显示 (对照 app VideoChapterItem 读 AppConfig.tocCountWords)
    val countWords =
        remember { runCatching { AppConfigProviders.get().tocCountWords }.getOrDefault(false) }

    VideoPlayerScreenContent(
        bookName = state.bookName,
        curChapterIndex = state.curChapterIndex,
        onBack = onBack,
        onPrevChapter = screenModel::onPrevChapter,
        onNextChapter = screenModel::onNextChapter,
        videoRenderSlot = { modifier ->
            val controller = screenModel.controller
            val platform = screenModel.platform
            if (controller != null && platform != null) {
                VideoPlayerHostContainer(platform, controller, screenModel, modifier)
            }
        },
        onTitleClick = onTitleClick,
        // 系统级全屏同样隐藏标题栏与选集网格 (两者视觉上都需要视频占满)
        isFullScreen = state.isFullScreen || state.isSystemFullScreen,
        chapters = state.chapters,
        displayTitles = state.displayTitles,
        countWords = countWords,
        onOpenChapter = screenModel::onOpenChapter,
        titleActions = {
            // 对照 app VideoTitleActions: refresh + shelf + OverflowMenu
            IconButton(onClick = screenModel::onRefreshChapter) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh_black_24dp),
                    contentDescription = stringResource(Res.string.refresh),
                    tint = AppTheme.colors.primaryText,
                )
            }
            IconButton(onClick = screenModel::onToggleShelf) {
                Icon(
                    painter = rememberPainter(
                        if (state.inShelf) "ic_star" else "ic_star_border"
                    ),
                    contentDescription = stringResource(
                        if (state.inShelf) Res.string.in_favorites else Res.string.out_favorites
                    ),
                    tint = AppTheme.colors.primaryText,
                )
            }
            VideoOverflowMenu(
                hasLogin = screenModel.shared.curBookSource?.hasLogin() == true,
                hasReview = !screenModel.shared.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
                onExpandedChange = { menuExpanded = it },
                // 右上角菜单"全屏" = 窗口内全屏 (隐藏顶栏/选集网格, 对照 Activity toggleFullScreen);
                // 右下角按钮 = 系统级全屏 (onToggleSystemFullScreen, 覆盖任务栏) —— 用户拍板两者行为区分
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
                scope.launch {
                    runCatching { AppDbProviders.get().bookmarkDao.insert(updated) }
                        .onFailure { AppLog.put("保存书签出错\n${it.message}", it) }
                }
                screenModel.clearPendingBookmark()
            },
            onDismiss = { screenModel.clearPendingBookmark() },
        )
    }
}

/**
 * 视频页溢出菜单 (对照 app 端 VideoTitleActions 中的 OverflowMenu)。
 */
@Composable
private fun VideoOverflowMenu(
    hasLogin: Boolean,
    hasReview: Boolean,
    onExpandedChange: (Boolean) -> Unit = {},
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
    OverflowMenu(onExpandedChange = onExpandedChange) { dismiss ->
        VideoMenuItem(Res.string.full_screen) { dismiss(); onFullScreen() }
        if (hasLogin) {
            VideoMenuItem(Res.string.login) { dismiss(); onLogin() }
        }
        VideoMenuItem(Res.string.copy_play_url) { dismiss(); onCopyPlayUrl() }
        VideoMenuItem(Res.string.set_source_variable) { dismiss(); onSourceVariable() }
        VideoMenuItem(Res.string.set_book_variable) { dismiss(); onBookVariable() }
        VideoMenuItem(Res.string.edit_book_source) { dismiss(); onEditSource() }
        if (hasReview) {
            VideoMenuItem(Res.string.review) { dismiss(); onReview() }
        }
        VideoMenuItem(Res.string.bookmark_add) { dismiss(); onAddBookmark() }
        VideoMenuItem(Res.string.log) { dismiss(); onAppLog() }
    }
}

@Composable
private fun VideoMenuItem(text: StringResource, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(stringResource(text), color = AppTheme.colors.primaryText)
    }
}

