package io.legado.app.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayOverflowActions
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.AudioPlayScreenModel
import io.legado.app.ui.book.audio.AudioPlayUiEvent
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
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
 * - showChangeSource / openChapterList → push ChangeSource / Toc (带 resultKey)
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
    val book = route.book.asBook()

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

                RouteResults.CHANGE_SOURCE -> {
                    // 换源回传新 source + book + toc (对照 Activity changeSourceResult)
                    (result.payload as? RouteResultPayload.ChangeSource)?.let { cs ->
                        AudioPlayShared.bookSource = cs.source
                        AudioPlayShared.chapterList = cs.toc
                        scope.launch {
                            AudioPlayShared.resetData(cs.book)
                            screenModel.dispatch(AudioPlayUiEvent.UpdateInShelf(!cs.book.isNotShelf))
                        }
                    }
                }

                RouteResults.BOOK_SOURCE_EDIT -> {
                    // 书源编辑后重新拉取 (对照 Activity upSource)
                    AudioPlayShared.book?.let { b ->
                        scope.launch(IoDispatcher) {
                            AudioPlayShared.bookSource =
                                AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
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

    // 退出加书架确认弹窗 (对照 Activity.finish: !inBookshelf 时弹确认)
    var showAddToShelfDialog by remember { mutableStateOf(false) }

    // 退出处理 (对照 Activity.finish)
    val onBack: () -> Unit = {
        if (state.inShelf) {
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
            // 对照 BookInfoRoute/MainRoute: 跳 shared LoginRoute, 不走平台专属 showBookSourceLogin
            source?.let { navigator.push(AppRoute.Login(it.bookSourceUrl)) }
        },
        onCopyAudioUrl = {
            val url = AudioPlayShared.durPlayUrl
            if (url.isNotEmpty()) {
                PlatformCapabilityProviders.getOrNull()?.copyToClipboard(url)
            }
        },
        onOpenAudioUrl = {
            // 浏览器打开播放 URL (对照 PlatformCapabilities.openExternalUrl)
            val url = AudioPlayShared.durPlayUrl
            if (url.isNotEmpty()) {
                PlatformCapabilityProviders.getOrNull()?.openExternalUrl(url)
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
            navigator.push(
                AppRoute.ChangeSource(book.toRouteRef()),
                resultKey = RouteResults.CHANGE_SOURCE
            )
        },
        onOpenToc = {
            navigator.push(AppRoute.Toc(book.toRouteRef()), resultKey = RouteResults.TOC)
        },
        onOpenBookSourceEdit = { sourceUrl ->
            navigator.push(
                AppRoute.BookSourceEdit(sourceUrl),
                resultKey = RouteResults.BOOK_SOURCE_EDIT
            )
        },
        onOpenReview = { navigator.push(AppRoute.ReviewPost(book.toRouteRef())) },
        overflowActions = overflowActions,
        onEvent = screenModel::dispatch,
    )

    // 日志对话框 (对照 VideoPlayRoute AppLogDialog)
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
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
}
