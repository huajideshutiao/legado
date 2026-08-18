package io.legado.app.ui.route

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.toc.TocScreen
import io.legado.app.ui.book.toc.TocScreenModel
import io.legado.app.ui.book.toc.TocUiActions
import io.legado.app.ui.book.toc.TocUiEvent
import io.legado.app.ui.compose.component.AppBottomSheetDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 目录页 shared 路由入口。
 * 通过 [TocContent] 复用目录屏幕, 本路由只负责导航语义:
 * - resultKey 非空: 调用方 (详情页/阅读页) 期望结果回传, pop payload (对照 TocActivity.setResult+finish)
 * - resultKey 为空: 直接跳阅读页
 */
@Composable
fun TocRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.Toc
    // 栈顶订阅: 目录页搜索拦截只在目录是栈顶时生效 (从搜索结果打开新阅读器后返回键
    // 不被不可见目录页吞掉, 否则第一下返回静默退出搜索, 第二下才退出阅读器)
    val backStack by navigator.backStack.collectAsState()
    // asBook() 每次 copy() 新实例, remember(route) 固定后 LaunchedEffect(book) 只在换路由时重启
    val book = remember(route) { route.book.asBook() }
    val resultKey = entry.resultKey
    TocContent(
        book = book,
        navigator = navigator,
        onBack = { navigator.pop() },
        isTopEntry = backStack.lastOrNull()?.id == entry.id,
        onOpenChapter = { chapterIndex, chapterPos, chapterChanged ->
            if (resultKey != null) {
                navigator.pop(
                    payload = RouteResultPayload.Toc(
                        chapterIndex = chapterIndex,
                        chapterPos = chapterPos,
                        chapterChanged = chapterChanged,
                    )
                )
            } else {
                navigator.push(AppRoute.Reader(route.book, chapterIndex, chapterPos))
            }
        },
        // 目录规则应用由 TocContent 内部完成 (对照原版 TocActivity.upBookAndToc), 路由无额外动作
    )
}

/**
 * 进入目录前把音频播放器的实时章节状态同步到书籍快照。
 *
 * 原版 AudioPlay 单例与 Book 为同实例引用, 切章 (skipTo/prev/next) 后
 * book.durChapterIndex/durChapterTitle 即时最新, 目录页 initBook 读到即正确;
 * KMP 化后 Book 是不可变值对象, 导航参数里的 book 是切章前拷贝,
 * 直接使用会导致"当前章"高亮与顶栏章节信息落后 (对照原版同一引用语义)。
 * 以 AudioPlayShared.book (skipTo 同步写 index, saveRead 异步写净化标题) 为准。
 */
private fun Book.syncDurChapterFromAudioPlay(): Book {
    val audio = AudioPlayShared.book ?: return this
    if (audio.bookUrl != this.bookUrl) return this
    if (audio.durChapterIndex == durChapterIndex) return this
    return copy(
        durChapterIndex = audio.durChapterIndex,
        durChapterTitle = audio.durChapterTitle ?: durChapterTitle,
    )
}

/**
 * 目录弹窗形态 (对照原版 TocListDialog / TocDialog: 全高底部弹窗)。
 * 由阅读页"目录"按钮弹起, 选章节经 [onOpenChapter] 回传并关闭。
 */
@Composable
fun TocDialogHost(
    book: Book,
    navigator: AppNavigator,
    onOpenChapter: (chapterIndex: Int, chapterPos: Int) -> Unit,
    onTocRegexChanged: (book: Book, tocRegex: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    AppBottomSheetDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.background,
                modifier = Modifier.fillMaxSize(),
            ) {
                TocContent(
                    book = book,
                    navigator = navigator,
                    onBack = onDismiss,
                    onOpenChapter = { chapterIndex, chapterPos, _ ->
                        onOpenChapter(chapterIndex, chapterPos)
                    },
                    onTocRegexChanged = onTocRegexChanged,
                )
            }
        }
    }
}

/**
 * 目录屏幕共享正文 (路由/弹窗两形态共用)。
 *
 * 内部自建 [TocScreenModel] + 事件接线, 导航类动作 (返回/选章节/TXT 目录规则) 经回调外抛,
 * 由宿主 (TocRoute 或 [TocDialogHost]) 决定 pop/push 或 dismiss。
 *
 * @param book 当前书籍
 * @param navigator 导航器 (TXT 目录规则对话框的新增/导入/帮助 Overlay 用)
 * @param onBack 返回 (路由=pop, 弹窗=dismiss)
 * @param onOpenChapter 选中章节 (chapterIndex, chapterPos, chapterChanged)
 * @param onTocRegexChanged 目录正则规则应用后的宿主通知 (book 已写入 tocUrl, 宿主可额外重载)
 */
@Composable
fun TocContent(
    book: Book,
    navigator: AppNavigator,
    onBack: () -> Unit,
    onOpenChapter: (chapterIndex: Int, chapterPos: Int, chapterChanged: Boolean) -> Unit,
    onTocRegexChanged: (book: Book, tocRegex: String) -> Unit = { _, _ -> },
    // 目录页是否栈顶: 非栈顶时搜索返回拦截失效 (防不可见目录页吞掉压栈页面的返回键)
    isTopEntry: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val screenModel = remember(book.bookUrl) {
        TocScreenModel(getChapterFiles = { b ->
            BookStorageProviders.get().getChapterFiles(b).toSet()
        })
    }
    val state by screenModel.state.collectAsState()
    val waitDialogVisible by screenModel.waitDialog.collectAsState()
    // 弹窗关闭时释放 ScreenModel 的协程 (对照 screenModelStore.onCleared)
    DisposableEffect(screenModel) {
        onDispose { screenModel.onCleared() }
    }

    // TXT 目录规则对话框显隐 (对照原版 TxtTocRuleDialog: 全高底部弹窗, 基于当前生效规则)
    var showTocRegexDialog by remember { mutableStateOf(false) }

    // 初始化书籍数据: 只按 bookUrl 重启——弹窗形态下 book 参数随阅读页书籍状态变化产生新
    // 实例 (如目录选章节后 dur 更新), 若按实例重启会反复触发 setBook 全量刷新 → 列表重置/
    // 滚动/闪烁; 对照原版目录 Activity 打开期间书籍数据变化不重置目录列表。进入目录前先把
    // 音频播放器的实时章节状态同步到快照, 见 syncDurChapterFromAudioPlay
    LaunchedEffect(book.bookUrl) {
        screenModel.dispatch(TocUiEvent.SetBook(book.syncDurChapterFromAudioPlay()))
    }

    // 对照 Activity.observeLiveBus: SAVE_CONTENT 事件触发 cacheFileNames 增量更新
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.SAVE_CONTENT).collect { event ->
            (event as? Pair<*, *>)?.let { (savedBook, chapter) ->
                val bookTyped = savedBook as? Book ?: return@let
                val chapterTyped = chapter as? BookChapter ?: return@let
                val curBookUrl = screenModel.state.value.book?.bookUrl ?: return@let
                if (bookTyped.bookUrl == curBookUrl) {
                    screenModel.dispatch(TocUiEvent.AddCacheFile(chapterTyped.getFileName()))
                }
            }
        }
    }

    // 平台对话框状态
    var showLogDialog by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }

    val actions = remember(screenModel, scope, onBack, onOpenChapter, onTocRegexChanged) {
        object : TocUiActions {
            override fun onBack() {
                onBack()
            }

            // 章节点击: 回传定位 (弹窗关闭由宿主 onOpenChapter 处理)
            override fun openChapter(chapter: BookChapter) {
                onOpenChapter(
                    chapter.index, 0,
                    chapter.index != screenModel.state.value.durChapterIndex,
                )
            }

            override fun setSearchMode(active: Boolean) {
                screenModel.dispatch(TocUiEvent.SetSearchMode(active))
            }

            override fun setQuery(query: String) {
                screenModel.dispatch(TocUiEvent.SetQuery(query))
            }

            override fun toggleVolume(volume: BookChapter) {
                screenModel.dispatch(TocUiEvent.ToggleVolume(volume))
            }

            // 反转章节列表: 重排 index 后 dispatch, 并持久化 (对照原版 TocViewModel.reverseToc)
            override fun reverseChapterList() {
                val current = screenModel.state.value.chapters
                if (current.isEmpty()) return
                val reversed = current.reversed().apply {
                    forEachIndexed { i, c -> c.index = i }
                }
                screenModel.dispatch(TocUiEvent.ReverseChapterList(reversed))
                val curBook = screenModel.state.value.book ?: return
                curBook.config.reverseToc = !curBook.config.reverseToc
                scope.launch {
                    val db = AppDbProviders.get()
                    // 未入架的书不落库 (books 行都没有), UI 已由上面 dispatch 反转
                    if (curBook.isNotShelf) return@launch
                    db.bookChapterDao.insert(*reversed.toTypedArray())
                }
            }

            // 切换净化标题开关: AppConfig 写回与 state 同步由 ScreenModel 内部完成 (对照 Activity)
            override fun toggleUseReplace() {
                screenModel.dispatch(TocUiEvent.ToggleUseReplace)
            }

            // 切换字数显示开关: AppConfig 写回与 state 同步由 ScreenModel 内部完成 (对照 Activity)
            override fun toggleCountWords() {
                screenModel.dispatch(TocUiEvent.ToggleCountWords)
            }

            // 翻转拆分长章节开关并重载 TOC (对照 Activity.upBookAndToc + viewModel.upBookTocRule)
            override fun toggleSplitLongChapter() {
                val curBook = screenModel.state.value.book ?: return
                curBook.config.splitLongChapter = !curBook.config.splitLongChapter
                screenModel.dispatch(TocUiEvent.UpBookTocRule(curBook))
            }

            // 弹起 TXT 目录规则对话框 (对照原版 TxtTocRuleDialog)
            override fun showTocRegexDialog() {
                showTocRegexDialog = true
            }

            // 导出书签 JSON: 平台文件选择器 + BackupFileOps 写文件 (对照 viewModel.saveBookmark)
            override fun exportBookmark() {
                val curBook = screenModel.state.value.book ?: return
                scope.launch {
                    try {
                        val path = withContext(IoDispatcher) {
                            PlatformServiceProviders.get().files.saveFile(
                                "bookmark-${curBook.name} ${curBook.author}.json"
                            )
                        } ?: return@launch
                        withContext(IoDispatcher) {
                            val bookmarks = AppDbProviders.get().bookmarkDao
                                .getByBook(curBook.name, curBook.author)
                            BackupFileOps.writeText(path, Json.encodeToString(bookmarks))
                        }
                        Toasters.get().toast("导出成功")
                    } catch (e: Throwable) {
                        AppLog.put("导出失败\n${e.message}", e, true)
                    }
                }
            }

            // 导出书签 Markdown (对照 viewModel.saveBookmarkMd)
            override fun exportBookmarkMd() {
                val curBook = screenModel.state.value.book ?: return
                scope.launch {
                    try {
                        val path = withContext(IoDispatcher) {
                            PlatformServiceProviders.get().files.saveFile(
                                "bookmark-${curBook.name} ${curBook.author}.md"
                            )
                        } ?: return@launch
                        withContext(IoDispatcher) {
                            val sb = StringBuilder()
                            sb.append("## ${curBook.name} ${curBook.author}\n\n")
                            AppDbProviders.get().bookmarkDao
                                .getByBook(curBook.name, curBook.author).forEach {
                                    sb.append("#### ${it.chapterName}\n\n")
                                    sb.append("###### 原文\n ${it.bookText}\n\n")
                                    sb.append("###### 摘要\n ${it.content}\n\n")
                                }
                            BackupFileOps.writeText(path, sb.toString())
                        }
                        Toasters.get().toast("导出成功")
                    } catch (e: Throwable) {
                        AppLog.put("导出失败\n${e.message}", e, true)
                    }
                }
            }

            override fun showLog() {
                showLogDialog = true
            }

            // 书签点击: 回传定位 (对照 openChapter)
            override fun openBookmark(bookmark: Bookmark) {
                onOpenChapter(
                    bookmark.chapterIndex, bookmark.chapterPos,
                    false,
                )
            }

            // 弹出书签编辑对话框 (shared BookmarkDialog)
            override fun editBookmark(bookmark: Bookmark, pos: Int) {
                editingBookmark = bookmark
            }
        }
    }

    // 搜索模式下系统返回键退出搜索 (对照 app TocActivity.Content 的 BackHandler);
    // isTopEntry 门控: 目录压栈后从搜索结果开新阅读器时, 不可见目录页不得拦截返回键
    PlatformBackHandler(enabled = state.searching && isTopEntry) { actions.setSearchMode(false) }

    // 日志对话框
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 等待对话框 (对照 Activity.waitDialog, 由 ScreenModel.upBookTocRule 控制显隐)
    WaitDialog(visible = waitDialogVisible, onDismissRequest = { })

    // 书签编辑对话框
    editingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = true,
            onConfirm = { updated ->
                scope.launch {
                    AppDbProviders.get().bookmarkDao.update(updated)
                }
                editingBookmark = null
            },
            onDismiss = { editingBookmark = null },
            onDelete = {
                scope.launch {
                    AppDbProviders.get().bookmarkDao.delete(bookmark)
                }
                editingBookmark = null
            },
        )
    }

    // TXT 目录规则对话框 (对照原版 TxtTocRuleDialog: 全高底部弹窗, 预选中当前生效规则)
    if (showTocRegexDialog) {
        val curBook = screenModel.state.value.book ?: book
        TxtTocRuleDialogHost(
            book = curBook,
            navigator = navigator,
            onTocRegexResult = { tocRegex ->
                showTocRegexDialog = false
                // 对照原版 TocActivity.onTocRegexDialogResult: book.tocUrl = tocRegex + upBookAndToc
                curBook.tocUrl = tocRegex
                screenModel.dispatch(TocUiEvent.UpBookTocRule(curBook))
                onTocRegexChanged(curBook, tocRegex)
            },
            onDismiss = { showTocRegexDialog = false },
        )
    }

    TocScreen(state = state, actions = actions)
}
