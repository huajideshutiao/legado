package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.migrateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.showSourceLogin
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayOverflowActions
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.AudioPlayScreenModel
import io.legado.app.ui.book.audio.AudioPlaySidePanelKind
import io.legado.app.ui.book.audio.AudioPlayUiEvent
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.toDurationTime
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_to_bookshelf
import legado.shared.generated.resources.check_add_bookshelf
import legado.shared.generated.resources.no
import legado.shared.generated.resources.yes
import org.jetbrains.compose.resources.stringResource

/**
 * 音频播放页 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [AudioPlayScreenModel]；播放状态和命令由 shared 托管，
 * 封面、模糊背景、歌词、定时/倍速弹层等平台渲染通过 AudioPlayPlatformProvider 注入。
 *
 * 对照 app 端 [io.legado.app.ui.book.audio.AudioPlayActivity]:
 * - onActivityCreated viewModel.initData → Init 事件
 * - showChangeSource → ChangeSourceDialogHost 全高底部弹窗 (原版 ChangeBookSourceDialog);
 *   openChapterList → push Toc (带 resultKey)
 * - showLogin / copyAudioUrl / showSourceVariable / showBookVariable / editSource / addBookmark / showAppLog →
 *   构造 [AudioPlayOverflowActions] 交由 shared [io.legado.app.ui.book.audio.AudioPlayScreenContent] 渲染溢出菜单
 * - finish: !inBookshelf 时弹加书架确认 (对照 Activity.finish alert)
 */
@Composable
fun AudioPlayRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.AudioPlay
    // asBook() 每次 copy() 新实例, remember(route) 固定后 LaunchedEffect(book) 只在换路由时重启
    val book = remember(route) { route.book.asBook() }

    val screenModel = screenModelStore.getOrCreateTyped(entry) { AudioPlayScreenModel() }
    val state by screenModel.state.collectAsState()
    val platform = AudioPlayPlatformProviders.getOrNull()
    val scope = rememberCoroutineScope()

    // 初始化标题 (对照 viewModel.initData 中 titleData.postValue(book.name) + applyBookmarkPosition)
    LaunchedEffect(book) {
        screenModel.dispatch(
            AudioPlayUiEvent.Init(book, route.chapterIndex, route.chapterPos)
        )
    }

    // 退出音频页: 落库进度 + 通知书架刷新 (对齐阅读器/视频/漫画行为, 回归 2026-08)。
    // 页面离开导航栈才触发 (LegadoApp 动画结束后组合销毁)。saveRead 内部会发
    // UP_BOOKSHELF 让书架重启分组流强制重查 durChapterTime (异步落库不随组合取消)。
    DisposableEffect(Unit) {
        onDispose {
            AudioPlayShared.saveRead()
        }
    }

    // 订阅子页结果回填 (对照 VideoPlayRoute navigator.resultsFor(entry.id).collect)
    LaunchedEffect(Unit) {
        navigator.resultsFor(entry.id).collect { result ->
            when (result.key) {
                RouteResults.TOC -> {
                    // 目录回传章节定位: 三态分支对齐 app 端 tocActivityResult
                    // (章节变 → skipTo; 同章不同 pos>0 → adjustProgress; pos=0 → skipTo 重开)
                    (result.payload as? RouteResultPayload.Toc)?.let { toc ->
                        val targetIndex = toc.chapterIndex
                        val targetPos = toc.chapterPos
                        when {
                            targetIndex != AudioPlayShared.durChapterIndex ->
                                AudioPlayShared.skipTo(targetIndex, targetPos)

                            targetPos > 0 && targetPos != AudioPlayShared.durChapterPos ->
                                AudioPlayShared.adjustProgress(targetPos)

                            targetPos == 0 -> AudioPlayShared.skipTo(targetIndex)
                        }
                    }
                }

                RouteResults.BOOK_SOURCE_EDIT -> {
                    // 书源编辑后重拉并刷新评论入口显隐
                    AudioPlayShared.book?.let { b ->
                        scope.launch(IoDispatcher) {
                            AudioPlayShared.bookSource =
                                AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
                            screenModel.dispatch(
                                AudioPlayUiEvent.UpdateInShelf(AudioPlayShared.inBookshelf)
                            )
                        }
                    }
                }
            }
        }
    }

    if (platform == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Audio platform is unavailable", color = Color.White)
        }
        return
    }

    // 平台对话框状态 (对照 VideoPlayRoute showLogDialog / pendingBookmark)
    var showLogDialog by remember { mutableStateOf(false) }
    var pendingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    // 发布输入弹窗 (对照原版 ReviewPostActivity 底部输入面板): 书籍级书评 (无回复预览)
    var showPostDialog by remember { mutableStateOf(false) }
    // 整书换源弹窗显示开关 (对照原版 menu_change_source → showDialogFragment(ChangeBookSourceDialog))
    var showChangeSourceDialog by remember { mutableStateOf(false) }

    // 退出加书架确认弹窗 (对照 Activity.finish: !inBookshelf 时弹确认)
    var showAddToShelfDialog by remember { mutableStateOf(false) }

    // 宽屏右侧面板 (评论/目录共用, 互斥显示): 窗口宽 ≥DesignTokens.wideScreenMinWidth 启用
    // (官方 Compact/Medium 分界, 与主界面 NavRail/音频页并排同源);
    // 面板宽 = 容器宽 × 0.35, 上限 DesignTokens.sidePanelMaxWidth; 窄屏保持原版交互 (弹窗/全屏页)
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val sidePanelWidth by remember(windowInfo, density) {
        derivedStateOf {
            val windowWidth = with(density) { windowInfo.containerSize.width.toDp() }
            if (windowWidth >= DesignTokens.wideScreenMinWidth) {
                (windowWidth * 0.35f).coerceAtMost(DesignTokens.sidePanelMaxWidth)
            } else {
                0.dp
            }
        }
    }
    var panelKind by remember { mutableStateOf<AudioPlaySidePanelKind?>(null) }
    // 窗口缩回窄屏时清理面板状态 (防残留: 再放大时面板内容层已销毁, panelKind 非空会白屏)
    LaunchedEffect(sidePanelWidth) {
        if (sidePanelWidth <= 0.dp) panelKind = null
    }

    // 退出处理 (对照 Activity.finish); 面板打开时先关面板 (Esc/返回/标题栏返回统一路径)
    val onBack: () -> Unit = {
        if (panelKind != null) {
            panelKind = null
        } else if (state.inShelf) {
            navigator.pop()
        } else if (!AppConfigProviders.get().showAddToShelfAlert) {
            // 不弹确认, 直接删除并退出
            scope.launch(IoDispatcher) {
                AudioPlayShared.book?.let { b ->
                    AppDbProviders.get().bookDao.delete(b)
                    b.addType(BookType.notShelf)
                }
                AudioPlayShared.inBookshelf = false
                navigator.pop()
            }
        } else {
            showAddToShelfDialog = true
        }
    }

    // 构造溢出菜单动作 (对照 app 端 AudioOverflowMenu)
    val source = AudioPlayShared.bookSource
    val overflowActions = AudioPlayOverflowActions(
        hasLogin = source?.hasLogin() == true,
        onLogin = {
            // 统一登录入口: URL 登录桌面端直开登录窗口 (2026-08-07);
            // 表单登录弹 Overlay, 带上书与当前章 (对照原版 menu_login 预置 IntentData.book/chapter)
            source?.let {
                showSourceLogin(
                    it.bookSourceUrl,
                    it,
                    AudioPlayShared.book,
                    AudioPlayShared.durChapter
                )
            }
        },
        onCopyAudioUrl = {
            val url = AudioPlayShared.durPlayUrl
            if (url.isNotEmpty()) {
                PlatformCapabilityProviders.getOrNull()?.copyToClipboard(url)
            }
        },
        onSetSourceVariable = {
            source?.let {
                PlatformCapabilityProviders.getOrNull()?.showBookSourceVariableDialog(it)
            }
        },
        onSetBookVariable = {
            AudioPlayShared.book?.let { b ->
                PlatformCapabilityProviders.getOrNull()?.showBookVariableDialog(b)
            }
        },
        onEditBookSource = {
            source?.bookSourceUrl?.let { url ->
                navigator.push(
                    AppRoute.BookSourceEdit(url),
                    resultKey = RouteResults.BOOK_SOURCE_EDIT,
                )
            }
        },
        onAddBookmark = {
            // 对照 Activity.addBookmark: 构造 Bookmark 弹 BookmarkDialog
            val b = AudioPlayShared.book ?: return@AudioPlayOverflowActions
            val chapter = AudioPlayShared.durChapter
            val pos = AudioPlayShared.durChapterPos
            val total = state.durationMs
            val bookmark = Bookmark(bookName = b.name, bookAuthor = b.author).apply {
                chapterIndex = AudioPlayShared.durChapterIndex
                chapterPos = pos
                chapterName = chapter?.title ?: b.durChapterTitle ?: ""
                bookText =
                    "${pos.toDurationTime()} / ${if (total > 0) total.toDurationTime() else "未知"}"
            }
            pendingBookmark = bookmark
        },
        onShowAppLog = { showLogDialog = true },
        onToggleWakeLock = {
            val config = AppConfigProviders.get()
            config.setAudioPlayUseWakeLock(!config.audioPlayUseWakeLock)
        },
    )

    platform.Content(
        state = state,
        onBack = onBack,
        onOpenChangeSource = {
            // 对照原版 menu_change_source → showDialogFragment(ChangeBookSourceDialog) 全高底部弹窗
            showChangeSourceDialog = true
        },
        onOpenToc = {
            // 对照 app 端 AudioPlayActivity.openChapterList: 未加书架的书目录不落库, 走内存传递。
            // 宽屏面板与窄屏 push 共用同一数据源 (IntentData.chapterList), 两个分支都要传,
            // 否则 TocScreenModel 只能读 DB (未加书架书目录不在 DB) → 目录空白。
            IntentData.chapterList = AudioPlayShared.chapterList
            if (sidePanelWidth > 0.dp) {
                // 宽屏: 右侧面板 (互斥: 直接覆盖评论面板)
                panelKind = AudioPlaySidePanelKind.TOC
            } else {
                navigator.push(AppRoute.Toc(book.toRouteRef()), resultKey = RouteResults.TOC)
            }
        },
        onOpenBookSourceEdit = { sourceUrl ->
            navigator.push(
                AppRoute.BookSourceEdit(sourceUrl),
                resultKey = RouteResults.BOOK_SOURCE_EDIT
            )
        },
        onOpenReview = openReview@{
            // 目录未加载 (chapter 空) 时静默无反应
            val chapter = AudioPlayShared.durChapter ?: return@openReview
            if (sidePanelWidth > 0.dp) {
                // 宽屏: 右侧面板 (互斥: 直接覆盖目录面板)
                panelKind = AudioPlaySidePanelKind.REVIEW
            } else if (!PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)) {
                showPostDialog = true
            }
        },
        overflowActions = overflowActions,
        onEvent = screenModel::dispatch,
        sidePanelWidth = sidePanelWidth,
        sidePanelVisible = panelKind != null,
        sidePanelKind = panelKind,
        // 点击左侧内容区空白处关闭面板 (对话框语义, 用户拍板 2026-08)
        onTapOutsideSidePanel = { if (panelKind != null) panelKind = null },
        sidePanelSlot = { kind ->
            when (kind) {
                AudioPlaySidePanelKind.TOC -> TocContent(
                    book = book,
                    navigator = navigator,
                    onBack = { panelKind = null },
                    onOpenChapter = { index, _, _ ->
                        // 对照 RouteResults.TOC 回传三态分支 (目录面板 pos 恒 0):
                        // 章节变 → skipTo(index, 0); 同章 → skipTo 重开
                        // 越界 (如模拟追读锁定章节) 时保持面板打开允许重选
                        // (对照原版 TocActivity 返回后用户仍停留在目录页可重选)
                        if (index in 0..<AudioPlayShared.simulatedChapterSize) {
                            panelKind = null
                            AudioPlayShared.skipTo(index, 0)
                        }
                    },
                )

                AudioPlaySidePanelKind.REVIEW -> {
                    // 目录未加载 (durChapter 空) 时面板留空 (入口处已拦, 双保险)
                    val chapter = AudioPlayShared.durChapter
                    if (chapter != null) {
                        ReviewListContent(
                            book = book,
                            chapter = chapter,
                            paragraphIndex = 0,
                            onDismiss = { panelKind = null },
                        )
                    }
                }
            }
        },
    )

    // 日志对话框 (对照 VideoPlayRoute AppLogDialog)
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 发布输入弹窗 (对照原版 ReviewPostActivity 底部输入面板)
    if (showPostDialog) {
        ReviewPostDialogHost(
            replyPreview = null,
            // 原 push ReviewPost 无 resultKey/结果处理, 内容不提交, 保持等价
            onPosted = { },
            onDismiss = { showPostDialog = false },
        )
    }

    // 书签编辑对话框 (对照 VideoPlayRoute BookmarkDialog + app addBookmark)
    pendingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = false,
            onConfirm = { updated ->
                scope.launch { AppDbProviders.get().bookmarkDao.insert(updated) }
                pendingBookmark = null
            },
            onDismiss = { pendingBookmark = null },
        )
    }

    // 退出加书架确认弹窗 (对照 Activity.finish alert)
    if (showAddToShelfDialog) {
        val bookName = AudioPlayShared.book?.name ?: ""
        AppAlertDialog(
            onDismissRequest = { showAddToShelfDialog = false },
            title = stringResource(Res.string.add_to_bookshelf),
            message = stringResource(Res.string.check_add_bookshelf, bookName),
            okButton = AlertButton(text = stringResource(Res.string.yes)) {
                // 放入书架: save book + insert chapters + set inBookshelf (对照 Activity okButton)
                scope.launch(IoDispatcher) {
                    AudioPlayShared.book?.let { b ->
                        b.removeType(BookType.notShelf)
                        val bookDao = AppDbProviders.get().bookDao
                        if (bookDao.has(b.bookUrl)) {
                            bookDao.update(b)
                        } else {
                            bookDao.insert(b)
                        }
                        AudioPlayShared.chapterList?.let { chapters ->
                            AppDbProviders.get().bookChapterDao.insert(*chapters.toTypedArray())
                        }
                        AudioPlayShared.inBookshelf = true
                    }
                    navigator.pop(RouteResultPayload.Ok)
                }
            },
            cancelButton = AlertButton(text = stringResource(Res.string.no)) {
                // 不放入书架: 删除并退出 (对照 Activity noButton)
                scope.launch(IoDispatcher) {
                    AudioPlayShared.book?.let { b ->
                        AppDbProviders.get().bookDao.delete(b)
                        b.addType(BookType.notShelf)
                    }
                    AudioPlayShared.inBookshelf = false
                    navigator.pop()
                }
            },
        )
    }

    // 整书换源弹窗 (对照原版 menu_change_source → ChangeBookSourceDialog 全高底部弹窗, 同阅读页/详情页同款)
    if (showChangeSourceDialog) {
        ChangeSourceDialogHost(
            book = AudioPlayShared.book ?: book,
            onSourceChanged = changeSource@{ source, newBook, toc ->
                showChangeSourceDialog = false
                if (!newBook.isAudio) {
                    // 非音频书: 停播 + 迁移落库 + 跳对应阅读路由 + 退出音频页
                    scope.launch {
                        runCatching {
                            AudioPlayShared.book?.let { oldBook ->
                                oldBook.migrateTo(newBook, toc)
                                if (AudioPlayShared.inBookshelf) {
                                    newBook.removeType(BookType.updateError)
                                    AppDbProviders.get().bookDao.delete(oldBook)
                                    AppDbProviders.get().bookDao.insert(newBook)
                                    AppDbProviders.get().bookChapterDao.insert(*toc.toTypedArray())
                                }
                            }
                        }.onFailure {
                            AppLog.put("换源失败\n$it", it, true)
                        }
                        AudioPlayShared.stop()
                        navigator.replace(newBook.toReadRoute())
                    }
                    return@changeSource
                }
                // 1) migrateTo 迁移进度/分组 2) 书架书落库 3) 切源数据落地
                scope.launch {
                    runCatching {
                        AudioPlayShared.book?.let { oldBook ->
                            oldBook.migrateTo(newBook, toc)
                            if (AudioPlayShared.inBookshelf) {
                                newBook.removeType(BookType.updateError)
                                AppDbProviders.get().bookDao.delete(oldBook)
                                AppDbProviders.get().bookDao.insert(newBook)
                                AppDbProviders.get().bookChapterDao.insert(*toc.toTypedArray())
                            }
                        }
                    }.onFailure {
                        AppLog.put("换源失败\n$it", it, true)
                    }
                    AudioPlayShared.bookSource = source
                    AudioPlayShared.chapterList = toc
                    AudioPlayShared.resetData(newBook)
                    screenModel.dispatch(AudioPlayUiEvent.UpdateInShelf(!newBook.isNotShelf))
                }
            },
            onEditSource = { origin ->
                navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
            },
            onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
            onDismiss = { showChangeSourceDialog = false },
        )
    }
}
