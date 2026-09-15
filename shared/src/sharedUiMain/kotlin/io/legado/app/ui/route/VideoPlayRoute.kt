package io.legado.app.ui.route

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalViewConfiguration
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.book.changeSourceTo
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
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
import io.legado.app.ui.root.RouteActiveEffect
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.VideoPlayTarget
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.format
import kotlinx.coroutines.launch
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
    // BookRef -> Book, 导航时再 toRouteRef() 转回 (防御性拷贝, 避免与路由持有对象别名)。
    // asBook() 每次调用都 copy() 新实例, 若不 remember 固定, 路由每次重组 book 都变,
    // LaunchedEffect(book) 会反复重启 → 重拉章节/重跑 JS header 规则 (调窗即报 js 错)。
    // 外部直投 ([VideoPlayTarget.Direct]) 没有书 → null, 依赖书的入口整块不参与。
    val book = remember(route) { route.book?.asBook() }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { VideoPlayScreenModel() }
    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 用路由持有的 Book 初始化章节状态 (bookName/chapterTitle/curChapterIndex/inShelf);
    // chapterIndex/chapterPos 用于书签/目录回传定位 (对照 AudioPlayUiEvent.Init)。
    // 直投形态则走 PlayDirect, 不携书也不查书源。
    LaunchedEffect(book) {
        when (val target = route.playTarget) {
            is VideoPlayTarget.FromBook -> screenModel.dispatch(
                VideoPlayUiEvent.ShowBook(target.book.asBook(), route.chapterIndex, route.chapterPos)
            )

            is VideoPlayTarget.Direct -> screenModel.dispatch(
                VideoPlayUiEvent.PlayDirect(target)
            )
        }
    }

    // 计时 + 落库上传 (对照 app onResume/onPause; onCleared 兜底释放播放器)。
    // 走 RouteActiveEffect: 压栈 (目录/详情/换源) 与退到后台都要按 onPause 收尾,
    // 否则阅读计时在别的页面继续累计
    RouteActiveEffect(
        entry = entry,
        navigator = navigator,
        onActive = { screenModel.onResume() },
        onInactive = { screenModel.onPause() },
    )

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
                                // 再加载章节并指定起播位置 (persistProgress=false 避免覆盖刚写入的
                                // durChapterPos; 位置必须随 loadChapter 显式传，上一版只写库、
                                // 装载端不读 → 书签/目录的“跳到 xx 秒”对视频完全失效)
                                screenModel.shared.loadChapter(
                                    toc.chapterIndex,
                                    persistProgress = false,
                                    seekPositionMs = toc.chapterPos.coerceAtLeast(0).toLong(),
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
                    // 书籍详情返回: 按回传 payload 分流 (对照原版 bookInfoResult 的 RESULT_DELETED
                    // 分支, 也与 ReaderRoute / MangaReaderRoute 同口径)。
                    // 上一版忽略 payload、改查 `bookDao.getBook(bookUrl) != null` 当“在架”判据:
                    // 从搜索/发现打开的视频书 (BookRef.Search, 不入 books 表) 本就不在库,
                    // 于是“点标题进详情 → 返回”会把视频页自己 pop 关掉。
                    if (result.payload is RouteResultPayload.Deleted) {
                        navigator.pop(RouteResultPayload.Deleted)
                    } else {
                        screenModel.shared.curBook?.let { b ->
                            screenModel.dispatch(VideoPlayUiEvent.UpdateInShelf(!b.isNotShelf))
                        }
                    }
                }
            }
        }
    }

    // 系统级全屏真实态 (桌面为窗口实际全屏, 其他端 null → 页面意图): 退栈链必须按真实态分流,
    // 否则 ESC 退过窗口全屏后页面仍自认为全屏、多吞一次返回
    val systemFullScreen = screenModel.platform?.rememberSystemFullScreen()
        ?: state.isSystemFullScreen

    // 返回栈由导航器统一管理; 对照 Activity onBackPressedDispatcher 返回逻辑
    // (系统级全屏与窗口内全屏互斥: 当前是哪种全屏就退哪种, 退全屏后下次 ESC 才 pop)
    val onBack: () -> Unit = {
        val s = screenModel.state.value
        when {
            systemFullScreen -> screenModel.setSystemFullScreen(false)
            s.isFullScreen -> screenModel.setFullScreen(false)
            else -> navigator.pop()
        }
    }
    // 全屏时系统返回先退全屏 (对照 Activity onBackPressedDispatcher: isFullScreen -> applyFullScreen(false));
    // 两种全屏互斥, 同时只可能有一种激活; 非栈顶不拦截 (栈内页面全程留在 Composition)
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    AppBackHandler(enabled = isTopEntry && (state.isFullScreen || systemFullScreen)) {
        val s = screenModel.state.value
        when {
            systemFullScreen -> screenModel.setSystemFullScreen(false)
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
        // 媒体键捕获阶段抢占 (preemptive=true), 弹层打开时让位 (菜单/对话框方向键导航优先);
        // 锁定态一并让位: 上一版 locked 只屏蔽鼠标手势, 空格/方向键仍能暂停、seek、
        // 切章、改倍速 —— 锁屏形同未锁
        enabled = { isTopEntry && !hasActiveBackLayer() && !screenModel.isLocked() },
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
                // 倍速从控制器快照现取: 上一版读 UiState.playbackSpeed, 而除 app 外三端
                // 从不回灌该字段 → 永远读到 1f, 松手就把用户自设倍速抹成 1X
                prePressSpeed.value = screenModel.currentSpeed()
                val boosted = prePressSpeed.value * 2f
                screenModel.onSpeedChange(boosted)
                screenModel.onGestureText("%.1fX".format(boosted))
            }
            Key.DirectionUp -> screenModel.onPrevChapter()
            Key.DirectionDown -> screenModel.onNextChapter()
        }
    }

    // 对照 Activity onTitleClick: bookInfoResult.launch(IntentData.book=…) + **player?.pause()**。
    // 原版进详情前显式暂停播放器, 下沉时只留了 push → 声音一直播到详情页后面。
    // 只补这一个入口: 原版 onPause() 本身不暂停 (只结束计时 + saveRead + uploadProgress),
    // 所以“退后台继续出声”是原版行为, 不在这里改。
    val onTitleClick: () -> Unit = {
        // 直投无书: 没有详情页可去 (入口在直投态已不渲染, 这里只堆兼其他路径误调)
        if (book != null) {
            screenModel.onPausePlayback()
            navigator.push(
                AppRoute.BookInfo(book.toRouteRef()),
                resultKey = RouteResults.BOOK_INFO,
            )
        }
    }
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
        if (book != null) {
            val chapter = state.chapters.getOrNull(state.curChapterIndex)
            PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)
        }
    }

    // 平台对话框状态 (对照 TocRoute showLogDialog/editingBookmark)
    var showLogDialog by remember { mutableStateOf(false) }

    // 选集字数显示 (对照 app VideoChapterItem 读 AppConfig.tocCountWords)
    val countWords = remember { AppConfigProviders.get().tocCountWords }

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
        isFullScreen = state.isFullScreen || systemFullScreen,
        chapters = state.chapters,
        displayTitles = state.displayTitles,
        countWords = countWords,
        onOpenChapter = screenModel::onOpenChapter,
        titleActions = {
            // 对照 app VideoTitleActions: refresh + shelf + OverflowMenu
            // (直投态不渲染刷新与上架: 没有章节可重新解析, 也没有一本书可上架)
            if (!state.isDirect) {
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
            }
            VideoOverflowMenu(
                isDirect = state.isDirect,
                hasLogin = screenModel.shared.curBookSource?.hasLogin() == true,
                hasReview = !screenModel.shared.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
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
    isDirect: Boolean,
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
    OverflowMenu { dismiss ->
        VideoMenuItem(Res.string.full_screen) { dismiss(); onFullScreen() }
        // 直投态只留与“一条地址”相容的动作: 全屏 / 复制地址 / 日志。
        // 登录、源/书变量、编辑书源、书评、添加书签全部依赖书与书源, 一并隐去。
        if (!isDirect) {
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
        } else {
            VideoMenuItem(Res.string.copy_play_url) { dismiss(); onCopyPlayUrl() }
        }
        VideoMenuItem(Res.string.log) { dismiss(); onAppLog() }
    }
}

@Composable
private fun VideoMenuItem(text: StringResource, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(stringResource(text), color = AppTheme.colors.primaryText)
    }
}

