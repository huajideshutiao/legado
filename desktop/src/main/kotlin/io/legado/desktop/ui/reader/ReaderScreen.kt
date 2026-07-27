package io.legado.desktop.ui.reader

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material.DrawerValue
import androidx.compose.material.ModalDrawer
import androidx.compose.material.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.toast.Toasters
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.DesktopReadConfigProviders
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.model.ReadBookShared
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReadMenuOverlay
import io.legado.app.ui.book.read.ReadMenuState
import io.legado.app.ui.book.read.SearchMenuOverlay
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.SourceAction
import io.legado.app.ui.book.read.TopMenuState
import io.legado.app.ui.book.read.page.ReadConfigPanel
import io.legado.app.ui.book.read.page.ReadViewComposable
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegateCompose
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.toc.TocDrawerContent
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.browseUrl
import io.legado.desktop.ui.book.changesource.ChangeChapterSourceScreen
import io.legado.desktop.ui.book.read.DesktopSearchMenuState
import io.legado.desktop.ui.book.read.config.AutoReadDialog
import io.legado.desktop.ui.book.read.config.AutoReadDialogCallbacks
import io.legado.desktop.ui.book.read.config.BgTextConfigDialog
import io.legado.desktop.ui.book.read.config.ReadStyleDialog
import io.legado.desktop.ui.book.read.config.ReadStyleDialogCallbacks
import io.legado.desktop.ui.dict.DictDialog
import io.legado.desktop.ui.reader.tts.TtsControlPanel
import io.legado.desktop.ui.reader.tts.rememberReadAloudController
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端阅读 Screen 入口：包装 shared/commonMain 的 [ReadViewComposable] + [ReadMenuOverlay]。
 *
 * # 职责
 *
 * 对照 app 端 `ReadBookActivity` 装配 `ReadView` + `ReadMenu`，桌面端在
 * [io.legado.desktop.ui.DesktopApp] 的 `DesktopRoute.READER` 分支调用本入口。
 *
 * 正文排版 / 翻页编排下沉到 shared/commonMain：[ReadViewComposable]；
 * 顶栏 / 底栏 / 悬浮按钮行 / 进度条 / 子菜单下沉到 shared/sharedUiMain：
 * [ReadMenuOverlay]（与 app 端 `ReadBookActivity.Content()` 调 `ReadMenuOverlay(readMenu)` 完全一致）。
 *
 * 桌面端在本文件仅做平台适配：
 * - **VM 生命周期**: `remember { ReadBookViewModelShared(...) }` 持有，退出时
 *   `DisposableEffect.onDispose { pageDelegate?.onDestroy() }` 释放翻页动画资源
 * - **章节装载**: `LaunchedEffect(book.bookUrl)` 内调 `readBook.loadBook(book)`
 *   + `viewModel.loadChapter(book.durChapterIndex)`
 * - **配置注入**: [DesktopReadConfigProviders] 包装 [DesktopPreferenceStoreProvider]
 *   注入 [LocalReadConfigProviders]
 * - **菜单层**: 调用 shared [ReadMenuOverlay]，传入桌面端 [DesktopReadMenuState] 实现
 *   `ReadMenuState` 接口（与 app 端 `ReadMenu` 类对应）
 * - **目录侧栏**: [ModalDrawer] + [TocDrawerContent]（由底栏 `clickCatalog` 触发）
 * - **TTS 控制**: [TtsControlPanel] 浮动在底栏上方（由 [rememberReadAloudController] 创建）
 *
 * # 点击 / 长按事件
 *
 * - **点击正文（中心区域）**: 调 [DesktopReadMenuState.toggleMenu] 切菜单显隐
 *   （与 app 端 `showActionMenu` 行为一致；翻页由 pageDelegate 自己处理 `onTap`）
 * - **长按正文**: 异步加载章节正文后弹 [TextSelectionDialog] 文字选择对话框。
 *   对照 app 端长按弹 ActionMode 文字选择 + TextActionMenu，桌面端用
 *   SelectionContainer + ComposeTextToolbar (shared/sharedUiMain 已下沉) 替代:
 *   用户可拖选文字 → 自动弹"复制/全选"菜单 + 底部"浏览器搜索/翻译"按钮
 *
 * @param book 待阅读的书籍
 * @param onBack 返回回调（切换回书架路由，桥接 `supportFinishAfterTransition`）
 */
@Composable
fun ReaderScreen(
    book: Book,
    pendingChapterIndex: Int? = null,
    onChapterIndexConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onOpenBookInfo: (Book) -> Unit = {},
    onOpenSearchContent: () -> Unit = {},
    onOpenReplaceRule: () -> Unit = {},
    onOpenMoreConfig: () -> Unit = {},
    onOpenPaddingConfig: () -> Unit = {},
    onOpenTipConfig: () -> Unit = {},
    // 段评/书评列表入口 (顶栏 REVIEW 菜单触发, 桥接 app 端 openCommentDialog)
    onOpenReviewList: (BookChapter, Int) -> Unit = { _, _ -> },
    // 整书换源入口 (顶栏换源图标 → CHANGE_SOURCE / BOOK_CHANGE_SOURCE 触发, 桥接 app 端
    // ReadBookActivity.onMenuAction(CHANGE_SOURCE) → ChangeBookSourceDialog)
    onOpenChangeSource: (Book) -> Unit = {},
    // 书内全文搜索结果 (由 SearchContentScreen onOpenResult 回传, 对照 app 端 searchContentActivity 回调)
    pendingSearchResults: List<SearchResult>? = null,
    pendingSearchResultIndex: Int = 0,
    onSearchResultsConsumed: () -> Unit = {},
) {
    // 注入 ReadConfigProviders（PageViewComposable 依赖 LocalReadConfigProviders.current）
    // 用 DesktopPreferenceStoreProvider 内存 Map 实现，进程结束即丢失（与其他桌面 Provider 一致）
    val prefs = remember { DesktopPreferenceStoreProvider() }
    val readConfigProviders = remember { DesktopReadConfigProviders(prefs) }

    CompositionLocalProvider(LocalReadConfigProviders provides readConfigProviders) {
        // 创建 ReadBookShared + ViewModel
        val readBook = remember { ReadBookShared() }
        val scope = rememberCoroutineScope()
        val readBookConfig = readConfigProviders.readBookConfig
        val viewModel = remember {
            // KP2-D P1: 启动时根据 readBookConfig 构造 LayoutConfig
            // (字号/行距/段距 从持久化配置读取; density 近似 2x 与 LayoutConfig.DEFAULT 一致,
            //  后续 P2 可改用 LocalDensity.current.density 精确换算 sp→px)
            val layoutConfig = ReadBookViewModelShared.LayoutConfig(
                textSizePx = readBookConfig.textSize * 2.0f,
                lineSpacingExtra = readBookConfig.lineSpacingExtra / 10f,
                paragraphSpacing = readBookConfig.paragraphSpacing,
            )
            ReadBookViewModelShared(readBook, scope, layoutConfig).also { vm ->
                // KP2-D P1: 启动时根据 readBookConfig.pageAnim 初始化 pageDelegate
                // (覆盖 → CoverPageDelegate, 滑动 → SlidePageDelegate, 仿真 → SimulationPageDelegate,
                //  滚动 → ScrollPageDelegate, 无动画 → NoAnimPageDelegate, 其他 → null)
                // 与 ReadConfigPanel.onPageAnimChange 切换逻辑保持一致
                vm.pageDelegate = when (readBookConfig.pageAnim) {
                    PageAnim.coverPageAnim -> CoverPageDelegateCompose(vm, scope)
                    PageAnim.slidePageAnim -> SlidePageDelegateCompose(vm, scope)
                    PageAnim.simulationPageAnim -> SimulationPageDelegateCompose(vm, scope)
                    PageAnim.scrollPageAnim -> ScrollPageDelegateCompose(vm, scope)
                    PageAnim.noAnim -> NoAnimPageDelegateCompose(vm, scope)
                    else -> null
                }
            }
        }

        // 装载书 + 章节（与 app 端 ReadBook.initData + loadContent 对应）
        LaunchedEffect(book.bookUrl) {
            readBook.loadBook(book)
            viewModel.loadChapter(book.durChapterIndex)
        }

        // 书内全文搜索结果跳转: pendingChapterIndex 变化时跳转章节, 消费后通知宿主置 null
        // (对照 app 端 SearchContentActivity 跳阅读页 + skipToChapterPos)
        LaunchedEffect(pendingChapterIndex) {
            pendingChapterIndex?.let { index ->
                viewModel.loadChapter(index)
                onChapterIndexConsumed()
            }
        }

        // 退出时持久化阅读进度 + 释放翻页动画资源
        // KP2-D P0-C：调 viewModel.saveProgress() 把 durChapterIndex / durChapterPos / 标题
        // PATCH 进 books 表（pageDelegate 默认 null，无副作用）
        DisposableEffect(viewModel) {
            onDispose {
                viewModel.saveProgress()
                viewModel.pageDelegate?.onDestroy()
            }
        }

        val curTextPage by viewModel.curTextPage.collectAsState()
        val chapterList by viewModel.chapterList.collectAsState()
        val durChapterIndex by viewModel.durChapterIndex.collectAsState()

        // KP2-D P1: 阅读配置面板弹窗状态 (顶栏 Settings 按钮触发, AlertDialog 形式弹出)
        var showConfigDialog by remember { mutableStateOf(false) }

        // 完整版阅读样式配置 Dialog (顶栏 clickFont / PAGE_ANIM 触发, 与 app 端 ReadStyleDialog 对应)
        // 对照 app 端 ReadBookActivity.clickFont → showDialogFragment<ReadStyleDialog>()
        var showReadStyleDialog by remember { mutableStateOf(false) }
        // 背景文字配置 Dialog (ReadStyleDialog 内长按样式项触发, 与 app 端 BgTextConfigDialog 对应)
        // 对照 app 端 ReadStyleDialog 内 callBack.showBgTextConfig(index)
        var showBgTextConfigDialog by remember { mutableStateOf(false) }
        // 自动翻页配置 Dialog (自动翻页运行时点击中心触发, 与 app 端 AutoReadDialog 对应)
        // 对照 app 端 ReadBookActivity.showActionMenu: isAutoPage=true → showDialogFragment<AutoReadDialog>()
        var showAutoReadDialog by remember { mutableStateOf(false) }
        // 朗读控制 Dialog (长按朗读按钮触发, 与 app 端 ReadAloudDialog 对应, 桥接 readAloudController)
        var showReadAloudDialog by remember { mutableStateOf(false) }

        // 内容编辑对话框状态 (顶栏 EDIT_CONTENT 触发, ContentEditDialog 形式弹出)
        // contentEditChapterName/contentEditContent 在 onShowContentEdit 回调中异步加载后赋值
        var showContentEditDialog by remember { mutableStateOf(false) }
        var contentEditChapterName by remember { mutableStateOf("") }
        var contentEditContent by remember { mutableStateOf("") }
        // 长按正文弹文字选择对话框 (替代简化版 AlertDialog, 补齐 desktop 端文字选择)
        // 对照 app 端长按弹 ActionMode 文字选择 + TextActionMenu, 桌面端用 TextSelectionDialog 替代
        var showTextSelectionDialog by remember { mutableStateOf(false) }
        // 异步加载章节正文状态 (加载中显示 loading, 完成后切换为 TextSelectionDialog)
        var loadingTextSelection by remember { mutableStateOf(false) }
        // 文字选择对话框当前章节名 + 正文 (长按时异步加载后填充)
        var textSelectionChapterName by remember { mutableStateOf("") }
        var textSelectionContent by remember { mutableStateOf("") }
        // 查词对话框状态 (TextSelectionDialog 底部"查词"按钮触发, 对照 app 端 DictDialog)
        // dictWord 由剪贴板内容填充, showDict 控制 DictDialog 显隐
        var showDict by remember { mutableStateOf(false) }
        var dictWord by remember { mutableStateOf("") }

        // 书签对话框状态 (顶栏 ADD_BOOKMARK 触发, 对照 app 端 addBookmark → BookmarkDialog)
        // bookmarkForEdit 由当前 book + curTextPage 构造, onConfirm 入库 insert
        var showBookmarkDialog by remember { mutableStateOf(false) }
        var bookmarkForEdit by remember { mutableStateOf<Bookmark?>(null) }

        // 日志对话框状态 (顶栏 LOG 触发, 对照 app 端 showDialogFragment<AppLogDialog>)
        var showAppLogDialog by remember { mutableStateOf(false) }

        // 章节换源覆盖层状态 (顶栏换源图标长按 → CHAPTER_CHANGE_SOURCE 触发, 对照 app 端
        // ReadBookActivity.onMenuAction(CHAPTER_CHANGE_SOURCE) → ChangeChapterSourceDialog)
        // showChangeChapterSource=true 时叠加 ChangeChapterSourceScreen 全屏覆盖阅读页
        var showChangeChapterSource by remember { mutableStateOf(false) }
        // 触发章节换源时的当前章节 (onReplaceContent 时 saveText 用, 避免覆盖层显示期间章节切换导致保存错章)
        var changeChapterSourceChapter by remember { mutableStateOf<BookChapter?>(null) }

        // KP2-D P0-10: 创建 TTS 朗读控制器 (绑定 viewModel 生命周期)
        // Main.kt 已注册 DesktopSystemTtsEngine 到 TtsEngineProvider, 控制器通过 provider 取引擎
        val readAloudController = rememberReadAloudController(viewModel)

        // KP2-D P1: 目录侧栏状态 + 章节列表 / 当前章节订阅 (供 TocDrawerContent 高亮 + 跳转联动)
        // drawerState 用 ModalDrawer 标准状态, 默认 Closed; scope.launch { open/close } 切换
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        // 桌面端 ReadMenuState 实现：桥接 shared ReadMenuOverlay 与 ReadBookViewModelShared
        // 对照 app 端 ReadMenu 类，桌面端简化实现沉浸式色彩 / 出场动画收尾等细节
        // 夜间主题切换: 取 LocalThemeStoreProvider.current, 强转 DesktopThemeStoreProvider 调 toggleDark
        // (桌面端 ThemeStore 用 mutableStateOf 持有 isDark, toggleDark 触发 Compose 重组,
        //  ReadMenuOverlay 内部按 isDark 走 AppTheme.colors 分支刷新色彩)
        val themeStore = LocalThemeStoreProvider.current
        val readMenuState = remember(viewModel, book, onBack, onOpenBookInfo, onOpenSearchContent, onOpenReplaceRule, onOpenMoreConfig, onOpenPaddingConfig, onOpenTipConfig, onOpenReviewList, onOpenChangeSource) {
            DesktopReadMenuState(
                viewModel = viewModel,
                book = book,
                onBack = onBack,
                openCatalog = { scope.launch { drawerState.open() } },
                showConfigDialog = { showConfigDialog = true },
                // 完整版阅读样式配置 Dialog (替代简化版 ReadConfigPanel, 与 app 端 ReadStyleDialog 对应)
                showReadStyleDialog = { showReadStyleDialog = true },
                // 背景文字配置 Dialog (ReadStyleDialog 内长按样式项触发)
                showBgTextConfigDialog = { showBgTextConfigDialog = true },
                // 自动翻页配置 Dialog (自动翻页运行时点击中心触发)
                showAutoReadDialog = { showAutoReadDialog = true },
                onShowContentEdit = {
                    // 异步加载当前章节正文 (从 BookStorage 读缓存文件), 完成后弹出 Dialog
                    val curBook = viewModel.book.value
                    val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                    if (curBook != null && chapter != null) {
                        scope.launch {
                            val content = withContext(Dispatchers.IO) {
                                BookStorageProviders.get().getContent(curBook, chapter) ?: ""
                            }
                            contentEditChapterName = chapter.title
                            contentEditContent = content
                            showContentEditDialog = true
                        }
                    }
                },
                // 章节换源入口 (顶栏换源图标长按 → CHAPTER_CHANGE_SOURCE, 对照 app 端
                // ReadBookActivity.onMenuAction(CHAPTER_CHANGE_SOURCE) → showDialogFragment(ChangeChapterSourceDialog))
                // 取当前章节后弹出 ChangeChapterSourceScreen 覆盖层
                showChangeChapterSource = {
                    val curBook = viewModel.book.value
                    val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                    if (curBook != null && chapter != null) {
                        changeChapterSourceChapter = chapter
                        showChangeChapterSource = true
                    }
                },
                // 整书换源入口 (顶栏 CHANGE_SOURCE / BOOK_CHANGE_SOURCE, 对照 app 端
                // ReadBookActivity.onMenuAction(CHANGE_SOURCE) → ChangeBookSourceDialog)
                showChangeBookSource = { onOpenChangeSource(book) },
                onOpenBookInfo = { onOpenBookInfo(book) },
                onOpenSearchContent = onOpenSearchContent,
                onOpenReplaceRule = onOpenReplaceRule,
                onOpenMoreConfig = onOpenMoreConfig,
                onOpenPaddingConfig = onOpenPaddingConfig,
                onOpenTipConfig = onOpenTipConfig,
                // 段评/书评列表入口 (顶栏 REVIEW 菜单触发, 对照 app 端 openCommentDialog: paragraphIndex=0 章节级评论)
                onOpenReviewList = {
                    val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                    if (chapter != null) {
                        onOpenReviewList(chapter, 0)
                    }
                },
                onToggleNightTheme = {
                    (themeStore as? DesktopThemeStoreProvider)?.toggleDark()
                },
                autoPageScope = scope,
                // 朗读控制 Dialog 回调 (桥接 longClickReadAloud, 对照 app 端 ReadBookActivity.showReadAloudDialog)
                showReadAloudDialog = { showReadAloudDialog = true },
                // 添加书签回调 (顶栏 ADD_BOOKMARK, 对照 app 端 addBookmark → BookmarkDialog)
                // 由 ReaderScreen 构造 Bookmark 对象 + 弹 BookmarkDialog
                showAddBookmark = {
                    val curBook = viewModel.book.value
                    val page = curTextPage
                    if (curBook != null && page != null) {
                        bookmarkForEdit = Bookmark(
                            bookName = curBook.name,
                            bookAuthor = curBook.author,
                            chapterIndex = viewModel.durChapterIndex.value,
                            chapterPos = page.chapterPosition,
                            chapterName = page.title,
                            bookText = page.text.trim(),
                        )
                        showBookmarkDialog = true
                    }
                },
                // 日志查看回调 (顶栏 LOG, 对照 app 端 showDialogFragment<AppLogDialog>)
                showAppLogDialog = { showAppLogDialog = true },
                // 启用/禁用替换规则回调 (顶栏 ENABLE_REPLACE, 对照 app 端 changeReplaceRuleState)
                // 切换 book.config.useReplaceRule + 同步 topMenu 勾选状态 + 重载章节
                toggleReplaceRule = {
                    val curBook = viewModel.book.value
                    if (curBook != null) {
                        curBook.config.useReplaceRule = !curBook.getUseReplaceRule()
                        viewModel.loadChapter(viewModel.durChapterIndex.value)
                    }
                },
            )
        }

        // 同步 curTextPage / 章节索引 / 章节总数到 readMenuState（顶栏标题 + 底栏进度条 + 上一/下一章可用性）
        // 对照 app 端 ReadMenu.upBookView（由 ReadBook.callBack.upMenuView 触发）
        LaunchedEffect(curTextPage, durChapterIndex, chapterList.size) {
            readMenuState.upBookView(
                chapterTitle = curTextPage?.title,
                durIndex = durChapterIndex,
                chapterSize = chapterList.size,
            )
        }

        // 书内全文搜索结果搜索菜单状态 (对照 app 端 searchMenu + isShowingSearchResult)
        // 由 SearchContentScreen onOpenResult 回传 searchResultList + index, 桌面端用 DesktopSearchMenuState 承载
        var searchMenuState by remember { mutableStateOf<DesktopSearchMenuState?>(null) }

        // 搜索结果到达时: 创建 searchMenuState + 跳转选中结果所在章节 + 显示搜索菜单
        // 对照 app 端 searchContentActivity 回调: upSearchResultList + updateSearchResultIndex +
        // skipToSearch(currentResult) + showActionMenu() (isShowingSearchResult=true → searchMenu.runMenuIn)
        LaunchedEffect(pendingSearchResults) {
            val results = pendingSearchResults ?: return@LaunchedEffect
            if (results.isEmpty()) {
                onSearchResultsConsumed()
                return@LaunchedEffect
            }
            val state = DesktopSearchMenuState(
                searchResults = results,
                initialIndex = pendingSearchResultIndex,
                onNavigate = { newIndex ->
                    // 章节级跳转 (对照 app 端 skipToSearch 的章节级简化, 精确页面定位待 pageDelegate actual 补全)
                    results.getOrNull(newIndex)?.let { result ->
                        viewModel.loadChapter(result.chapterIndex)
                    }
                },
                onOpenSearchContent = onOpenSearchContent,
                onShowMainMenu = {
                    // 收起搜索菜单后显示主菜单 (对照 app 端 clickMainMenu → showMenuBar + invisible)
                    readMenuState.runMenuIn()
                },
                onExit = {
                    // 退出搜索菜单 (对照 app 端 exitSearchMenu → invisible + clearSearchResult)
                    searchMenuState = null
                },
            )
            // 初始跳转到选中结果所在章节 (对照 app 端 skipToSearch(currentResult))
            results.getOrNull(pendingSearchResultIndex)?.let { result ->
                viewModel.loadChapter(result.chapterIndex)
            }
            state.updateSearchInfo(curTextPage?.title)
            state.runMenuIn()
            searchMenuState = state
            onSearchResultsConsumed()
        }

        // 章节切换时更新搜索信息文本 (对照 app 端 SearchMenu.updateSearchInfo: ReadBook.curTextChapter?.title)
        LaunchedEffect(curTextPage?.title) {
            searchMenuState?.updateSearchInfo(curTextPage?.title)
        }

        // KP2-D P1: 用 ModalDrawer 包裹原 Box, drawerContent 渲染 TocDrawerContent
        // 对照 app 端 ReadBookActivity 用 DrawerLayout + RecyclerView 展示目录
        // - drawerContent: 章节列表 (LazyColumn + 当前章节高亮 + 点击跳转 + 搜索/反转/卷折叠/字数显示)
        // - content: 原 Box (ReadViewComposable + ReadMenuOverlay + TtsControlPanel) 不动
        ModalDrawer(
            drawerState = drawerState,
            drawerContent = {
                TocDrawerContent(
                    chapterList = chapterList,
                    currentIndex = durChapterIndex,
                    onChapterClick = { index ->
                        // 章节跳转联动: 调 viewModel.loadChapter 拉取并排版新章节
                        // (与 app 端 TocActivity.openChapter → ReadBook.loadContent 对应)
                        viewModel.loadChapter(index)
                        // 跳转后关闭 drawer (与 app 端跳转后 finish TocActivity 行为一致)
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    // 键盘翻页 (对照 app 端 ReadBookKeyHandler.onKeyDown: PageUp/PageDown/Space/方向键)
                    // 菜单可见时不拦截 (与 app 端 menuLayoutIsVisible 判断一致), Escape 收菜单
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> {
                                if (readMenuState.isVisible) {
                                    readMenuState.runMenuOut()
                                    true
                                } else false
                            }
                            Key.Spacebar, Key.PageDown, Key.DirectionDown, Key.DirectionRight -> {
                                if (readMenuState.isVisible) return@onPreviewKeyEvent false
                                // 翻下一页, 已到末页则切下一章 (与 app 端 keyPage(NEXT) 对应)
                                if (!viewModel.nextPage()) viewModel.moveToNextChapter()
                                true
                            }
                            Key.PageUp, Key.DirectionUp, Key.DirectionLeft -> {
                                if (readMenuState.isVisible) return@onPreviewKeyEvent false
                                // 翻上一页, 已到首页则切上一章 (与 app 端 keyPage(PREV) 对应)
                                if (!viewModel.prevPage()) viewModel.moveToPrevChapter()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                // 主内容：阅读视图
                // 点击中心区域 → 切菜单显隐（与 app 端 showActionMenu 一致；翻页由 pageDelegate.onTap 自己处理）
                // 长按 → 异步加载章节正文 → 弹 TextSelectionDialog (文字选择), 见下方 loadingTextSelection/showTextSelectionDialog 分支
                ReadViewComposable(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { _ ->
                        // 中心区域点击（delegate.onTap 返回 false 时转发到这里）
                        // 与 app 端 ReadBookActivity.showActionMenu() 对应，切换菜单显隐
                        readMenuState.toggleMenu()
                    },
                    onLongClick = {
                        // 长按正文: 异步加载当前章节正文, 完成后弹 TextSelectionDialog (补齐文字选择)
                        // 对照 app 端长按弹 ActionMode 文字选择 + TextActionMenu,
                        // 桌面端用 SelectionContainer + ComposeTextToolbar (shared 已下沉) 替代
                        val curBook = viewModel.book.value
                        val chapter = viewModel.chapterList.value
                            .getOrNull(viewModel.durChapterIndex.value)
                        if (curBook != null && chapter != null) {
                            loadingTextSelection = true
                            // 章节名立即赋值 (loading 对话框标题展示, 与原 AlertDialog 立即弹出行为对齐)
                            textSelectionChapterName = chapter.title
                            scope.launch {
                                runCatching {
                                    val content = withContext(Dispatchers.IO) {
                                        BookStorageProviders.get().getContent(curBook, chapter) ?: ""
                                    }
                                    textSelectionContent = content
                                }.onSuccess {
                                    loadingTextSelection = false
                                    showTextSelectionDialog = true
                                }.onFailure {
                                    loadingTextSelection = false
                                    AppLog.put(jvmGetString("load_chapter_content_failed"), it)
                                    Toasters.get().toast(it.localizedMessage ?: jvmGetString("load_chapter_content_failed"))
                                }
                            }
                        }
                    },
                )

                // shared 菜单层（顶栏 + 底栏 + 悬浮按钮行 + 进度条 + 子菜单）
                // 与 app 端 ReadBookActivity.Content() 调用 ReadMenuOverlay(readMenu) 完全一致
                ReadMenuOverlay(state = readMenuState)

                // 搜索结果菜单层 (对照 app 端 SearchMenuOverlay(searchMenu), 有搜索结果时显示)
                // 由 LaunchedEffect(pendingSearchResults) 创建 searchMenuState 后渲染
                searchMenuState?.let { searchState ->
                    SearchMenuOverlay(state = searchState)
                }

                // KP2-D P0-10: TTS 朗读控制面板 (浮动在底栏上方, 避开 48dp 底栏高度)
                TtsControlPanel(
                    controller = readAloudController,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp),
                )

                // 章节换源覆盖层 (顶栏换源图标长按 → CHAPTER_CHANGE_SOURCE 触发, 全屏覆盖阅读页)
                // 对照 app 端 ChangeChapterSourceDialog: 选中源+章节后取正文 → replaceContent 替换当前阅读页
                // onReplaceContent: saveText 保存正文到 BookStorage + loadChapter 重载章节刷新阅读视图
                // onBack: 关闭覆盖层 (changeChapterSourceChapter 保留无妨, 下次触发时覆盖)
                if (showChangeChapterSource) {
                    val curBook = viewModel.book.value
                    val chapter = changeChapterSourceChapter
                    if (curBook != null && chapter != null) {
                        ChangeChapterSourceScreen(
                            book = curBook,
                            chapterIndex = chapter.index,
                            chapterTitle = chapter.title,
                            onBack = { showChangeChapterSource = false },
                            onReplaceContent = { content ->
                                // 保存换源取到的正文到 BookStorage (对照 app 端 viewModel.saveContent)
                                // + 重载当前章节刷新阅读视图 (对照 app 端 replaceContent 后 readView.upContent)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        BookStorageProviders.get().saveText(curBook, chapter, content)
                                    }
                                    viewModel.loadChapter(viewModel.durChapterIndex.value)
                                }
                            },
                        )
                    }
                }
            }

            // KP2-D P1: 阅读配置面板 (AlertDialog), 由顶栏 Settings 按钮触发
            // 翻页模式切换时即时销毁旧 delegate + 创建新 delegate
            // (覆盖 → CoverPageDelegate, 滑动 → SlidePageDelegate, 仿真 → SimulationPageDelegate,
            //  滚动 → ScrollPageDelegate, 无动画 → NoAnimPageDelegate, 其他 → null)
            // 字号/行距/段距/背景色变化仅持久化, 下次启动 ReaderScreen 时由 viewModel 初始化生效
            if (showConfigDialog) {
                ReadConfigPanel(
                    onDismissRequest = { showConfigDialog = false },
                    onPageAnimChange = { anim ->
                        // 销毁旧 delegate (释放动画协程资源)
                        viewModel.pageDelegate?.onDestroy()
                        // 按 PageAnim 常量创建新 delegate
                        viewModel.pageDelegate = when (anim) {
                            PageAnim.coverPageAnim -> CoverPageDelegateCompose(viewModel, scope)
                            PageAnim.slidePageAnim -> SlidePageDelegateCompose(viewModel, scope)
                            PageAnim.simulationPageAnim -> SimulationPageDelegateCompose(viewModel, scope)
                            PageAnim.scrollPageAnim -> ScrollPageDelegateCompose(viewModel, scope)
                            PageAnim.noAnim -> NoAnimPageDelegateCompose(viewModel, scope)
                            else -> null
                        }
                    },
                )
            }

            // KP2-D P1: 完整版阅读样式 Dialog (顶栏 clickFont / PAGE_ANIM 触发)
            // 包装 shared/sharedUiMain 的 ReadStyleScreen, 桥接到 ReadBookConfigShared 各字段
            // callbacks 桥接 padding/tip 路由切换 + bgText Dialog + 翻页 delegate 切换
            if (showReadStyleDialog) {
                val readStyleCallbacks = remember(onOpenPaddingConfig, onOpenTipConfig) {
                    object : ReadStyleDialogCallbacks {
                        override fun showPaddingConfig() = onOpenPaddingConfig()
                        override fun showTipConfig() = onOpenTipConfig()
                        override fun showBgTextConfig(index: Int) {
                            // 触发 BgTextConfigDialog 弹出 (叠加在 ReadStyleDialog 之上)
                            showBgTextConfigDialog = true
                        }
                        override fun onUpPageAnim() {
                            // 翻页动画切换: 销毁旧 delegate + 按 readBookConfig.pageAnim 创建新 delegate
                            // 对照 app 端 callBack.upPageAnim() + ReadBook.loadContent(false)
                            viewModel.pageDelegate?.onDestroy()
                            val anim = readBookConfig.pageAnim
                            viewModel.pageDelegate = when (anim) {
                                PageAnim.coverPageAnim -> CoverPageDelegateCompose(viewModel, scope)
                                PageAnim.slidePageAnim -> SlidePageDelegateCompose(viewModel, scope)
                                PageAnim.simulationPageAnim -> SimulationPageDelegateCompose(viewModel, scope)
                                PageAnim.scrollPageAnim -> ScrollPageDelegateCompose(viewModel, scope)
                                PageAnim.noAnim -> NoAnimPageDelegateCompose(viewModel, scope)
                                else -> null
                            }
                        }
                    }
                }
                ReadStyleDialog(
                    readBookConfig = readBookConfig,
                    callbacks = readStyleCallbacks,
                    onDismiss = { showReadStyleDialog = false },
                )
            }

            // KP2-D P1: 背景文字配置 Dialog (ReadStyleDialog 内长按样式项触发)
            // 包装 shared/sharedUiMain 的 BgTextConfigScreen, 桥接到 ReadBookConfigShared.durConfig
            // isImageBook 由 ReaderScreen 入参 book 判定 (图片书籍不显示下划线开关)
            if (showBgTextConfigDialog) {
                BgTextConfigDialog(
                    readBookConfig = readBookConfig,
                    isImageBook = book.isImage,
                    onDismiss = { showBgTextConfigDialog = false },
                )
            }

            // KP2-D P1: 自动翻页配置 Dialog (自动翻页运行时点击中心触发)
            // 包装 shared/sharedUiMain 的 AutoReadPanel, 桥接到 ReadBookConfigShared.autoReadSpeed
            // callbacks 桥接到目录侧栏 / 菜单显隐 / 翻页停止 / 样式配置
            if (showAutoReadDialog) {
                val autoReadCallbacks = remember {
                    object : AutoReadDialogCallbacks {
                        override fun openChapterList() {
                            // 打开目录侧栏 (与 clickCatalog 一致)
                            scope.launch { drawerState.open() }
                        }
                        override fun showMenuBar() {
                            // 显示主菜单 (与 toggleMenu 在非 autoPage 分支一致)
                            if (!readMenuState.isVisible) readMenuState.runMenuIn()
                        }
                        override fun autoPageStop() {
                            // 停止自动翻页 (与 clickAutoPage 在 autoPage=true 分支一致)
                            readMenuState.clickAutoPage()
                        }
                        override fun showPageAnimConfig() {
                            // 显示完整版 ReadStyleDialog (与 PAGE_ANIM 一致)
                            showReadStyleDialog = true
                        }
                    }
                }
                AutoReadDialog(
                    readBookConfig = readBookConfig,
                    callbacks = autoReadCallbacks,
                    onDismiss = { showAutoReadDialog = false },
                )
            }

            // 朗读控制 Dialog (长按朗读按钮触发, 包装 shared/sharedUiMain 的 ReadAloudDialog)
            // 桥接 readAloudController: isPlaying/onPlayPause/onStop/onPrev/onNext/onPrevParagraph/
            // onNextParagraph/onAdjustSpeed 走 controller; onSetTimer/onFollowSysChange/onBackstage 暂 no-op
            // (桌面端无睡眠定时 / 跟随系统语速 / 转后台语义)
            // onOpenChapterList 桥接目录侧栏; onShowMenuBar 桥接菜单显隐 + dismiss;
            // onOpenSettings 暂 no-op (ReadAloudConfig 路由由 MoreConfig 入口, 此处不重复)
            if (showReadAloudDialog) {
                ReadAloudDialog(
                    isPlaying = readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.PLAYING,
                    initialTimer = 0, // 桌面端无睡眠定时
                    initialSpeechRate = ((readAloudController.speechRate.value * 10) - 5).toInt().coerceIn(0, 45),
                    initialFollowSys = false,
                    onPlayPause = {
                        when (readAloudController.state.value) {
                            ReadAloudControllerShared.ReadAloudState.PLAYING -> readAloudController.pause()
                            ReadAloudControllerShared.ReadAloudState.PAUSED -> readAloudController.resume()
                            else -> {
                                val startIdx = readAloudController.chapterIndex.value
                                    .takeIf { it >= 0 } ?: viewModel.durChapterIndex.value
                                readAloudController.start(startIdx)
                            }
                        }
                    },
                    onStop = { readAloudController.stop() },
                    onPrev = { readAloudController.prevChapter() },
                    onNext = { readAloudController.nextChapter() },
                    onPrevParagraph = { readAloudController.prevParagraph() },
                    onNextParagraph = { readAloudController.nextParagraph() },
                    onSetTimer = { /* 桌面端无睡眠定时 */ },
                    onAdjustSpeed = { rate -> readAloudController.setSpeechRate(rate) },
                    onFollowSysChange = { /* 桌面端无跟随系统语速 */ },
                    onOpenChapterList = { scope.launch { drawerState.open() } },
                    onShowMenuBar = {
                        if (!readMenuState.isVisible) readMenuState.runMenuIn()
                        showReadAloudDialog = false
                    },
                    onBackstage = { /* 桌面端无转后台语义 */ },
                    onOpenSettings = { /* ReadAloudConfig 路由由 MoreConfig 入口, 此处不重复 */ },
                    onDismiss = { showReadAloudDialog = false },
                )
            }

            // 内容编辑对话框 (顶栏 EDIT_CONTENT 触发), 编辑当前章节正文
            // onReset 不接入 (WebBook 重新获取正文依赖较重, 桌面端未下沉)
            if (showContentEditDialog) {
                ContentEditDialog(
                    chapterName = contentEditChapterName,
                    content = contentEditContent,
                    onSubmit = { text ->
                        val curBook = viewModel.book.value
                        val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                        if (curBook != null && chapter != null) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    BookStorageProviders.get().saveText(curBook, chapter, text)
                                }
                                // 保存后重载章节刷新阅读视图
                                viewModel.loadChapter(viewModel.durChapterIndex.value)
                            }
                        }
                    },
                    onDismiss = { showContentEditDialog = false },
                    clipTextSink = { text ->
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    },
                )
            }

            // 长按正文: 加载章节正文时弹 loading AlertDialog (与原 AlertDialog 立即弹出体验对齐)
            // 加载完成后切换为 TextSelectionDialog (SelectionContainer + ComposeTextToolbar)
            if (loadingTextSelection) {
                AlertDialog(
                    onDismissRequest = { loadingTextSelection = false },
                    title = { Text(textSelectionChapterName.ifBlank { rememberString("text_action") }) },
                    text = { Text(rememberString("loading_chapter_content")) },
                    confirmButton = {},
                )
            }

            // 长按正文弹文字选择对话框 (替代原简化版 AlertDialog, 补齐 desktop 端文字选择)
            // 对照 app 端长按弹 ActionMode 文字选择 + TextActionMenu,
            // 桌面端用 TextSelectionDialog (SelectionContainer + ComposeTextToolbar) 替代
            // - 复制 / 全选: 由 ComposeTextToolbar 自动提供 (shared/sharedUiMain 已下沉)
            // - 复制全部 / 复制章节标题: 标题栏 OverflowMenu
            // - 浏览器搜索 / 翻译: 底部按钮, 读剪贴板内容为关键字
            if (showTextSelectionDialog) {
                TextSelectionDialog(
                    chapterName = textSelectionChapterName,
                    content = textSelectionContent,
                    onDismiss = { showTextSelectionDialog = false },
                    // 剪贴板桥接用 AWT Toolkit (替代 app 端 getClipText/sendToClip,
                    // 与 DictRuleScreen.kt 同款实现)
                    clipTextProvider = {
                        runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard
                                .getData(DataFlavor.stringFlavor) as? String
                        }.getOrNull()
                    },
                    clipTextSink = { text ->
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(text), null)
                    },
                    // 查词回调: 读剪贴板取词已在 TextSelectionDialog 内完成,
                    // 此处仅接收 word → 弹 DictDialog (对照 app 端 TextActionMenu 查词 → DictDialog)
                    onDict = { word ->
                        dictWord = word
                        showDict = true
                    },
                )
            }

            // 查词对话框 (TextSelectionDialog 底部"查词"按钮触发, 对照 app 端 DictDialog)
            // word 来自剪贴板内容, onDismiss 关闭后保留 dictWord (下次触发时覆盖)
            if (showDict) {
                DictDialog(
                    word = dictWord,
                    onDismiss = { showDict = false },
                )
            }

            // 书签对话框 (顶栏 ADD_BOOKMARK 触发, 对照 app 端 addBookmark → BookmarkDialog)
            // bookmarkForEdit 由 showAddBookmark 回调构造, onConfirm 入库 insert
            if (showBookmarkDialog && bookmarkForEdit != null) {
                val bm = bookmarkForEdit!!
                BookmarkDialog(
                    bookmark = bm,
                    onConfirm = { updated ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppDatabaseProviders.get().appDb.bookmarkDao.insert(updated)
                            }
                        }
                        showBookmarkDialog = false
                        bookmarkForEdit = null
                    },
                    onDismiss = {
                        showBookmarkDialog = false
                        bookmarkForEdit = null
                    },
                )
            }

            // 日志对话框 (顶栏 LOG 触发, 对照 app 端 showDialogFragment<AppLogDialog>)
            if (showAppLogDialog) {
                AppLogDialog(
                    onDismiss = { showAppLogDialog = false },
                )
            }
        }
    }
}

/**
 * 桌面端 [ReadMenuState] 实现：桥接 shared [ReadMenuOverlay] 与 [ReadBookViewModelShared]。
 *
 * 对照 app 端 `ReadMenu` 类（实现同一接口，但深度依赖 Activity/lifecycleScope/AppConfig/
 * ThemeConfig/ReadBook/AppWebDav 等 Android 专属 API，属 L3 不可下沉）。桌面端简化实现：
 *
 * - **显隐**: 用 [MutableTransitionState] 直接控制（无 bgClickEnabled / 出场动画收尾等细节，
 *   桌面端无系统状态栏沉浸式联动需求）
 * - **色彩**: 固定非沉浸式（immersive=false），bgColor/textColor 给一个默认值；
 *   `ReadMenuOverlay` 内部根据 `immersive=false` 走 `AppTheme.colors` 分支（与 app 端
 *   非沉浸式分支一致）
 * - **状态同步**: 由 [ReaderScreen] 的 `LaunchedEffect(curTextPage, durChapterIndex, chapterList)`
 *   调 [upBookView] 同步顶栏标题 + 底栏进度 + 上一/下一章可用性
 * - **回调桥接**:
 *   - [supportFinishAfterTransition] → [onBack] 回书架
 *   - [clickCatalog] → 打开 ModalDrawer 目录侧栏
 *   - [clickPre] / [clickNext] → viewModel 切章
 *   - [clickFont] / [onTopMenuAction](`PAGE_ANIM`) → 显示 ReadConfigPanel 配置面板
 *   - [onTopMenuAction](`REFRESH` / `REFRESH_DUR`) → 重载当前章节
 *   - [onSeekStop] → viewModel.loadChapter 章节级跳转
 *   - [openBookInfoActivity] → [onOpenBookInfo] 切到 BOOK_INFO 路由
 *   - [clickSearch] → [onOpenSearchContent] 切到 SEARCH_CONTENT 路由
 *   - [clickReplaceRule] → [onOpenReplaceRule] 切到 EFFECTIVE_REPLACES 路由
 *   - [clickSetting] → [onOpenMoreConfig] 切到 MORE_CONFIG 路由
 *   - [clickAutoPage] → 用 [autoPageScope] 启动/取消定时翻页 job (5s 间隔)
 *   - [clickNightTheme] → [onToggleNightTheme] 切换桌面端 ThemeStore 深浅色
 *
 * @param viewModel 阅读 ViewModel，提供 curTextPage / durChapterIndex / chapterList 等
 * @param book 当前阅读的书籍（供 title / isLocal 判定）
 * @param onBack 返回书架回调（桥接 `supportFinishAfterTransition`）
 * @param openCatalog 打开目录侧栏回调（桥接 `clickCatalog`）
 * @param showConfigDialog 显示阅读配置面板回调（桥接 `clickFont` / `PAGE_ANIM`）
 * @param onOpenBookInfo 打开书籍详情回调（桥接 `openBookInfoActivity`）
 * @param onOpenSearchContent 打开书内全文搜索回调（桥接 `clickSearch`）
 * @param onOpenReplaceRule 打开有效替换规则回调（桥接 `clickReplaceRule`）
 * @param onOpenMoreConfig 打开更多配置回调（桥接 `clickSetting`）
 * @param onOpenPaddingConfig 打开边距配置回调（桥接 ReadStyleDialog 内 `showPaddingConfig`）
 * @param onOpenTipConfig 打开提示信息配置回调（桥接 ReadStyleDialog 内 `showTipConfig`）
 * @param onToggleNightTheme 切换夜间主题回调（桥接 `clickNightTheme`）
 * @param autoPageScope 自动翻页协程作用域（桥接 `clickAutoPage` 启动定时翻页 job）
 * @param showReadStyleDialog 显示完整版阅读样式 Dialog 回调（桥接 `clickFont` / `PAGE_ANIM`）
 * @param showBgTextConfigDialog 显示背景文字配置 Dialog 回调（桥接 ReadStyleDialog 内 `showBgTextConfig`）
 * @param showAutoReadDialog 显示自动翻页配置 Dialog 回调（桥接 `toggleMenu` 在 autoPage=true 时）
 */
private class DesktopReadMenuState(
    private val viewModel: ReadBookViewModelShared,
    private val book: Book,
    private val onBack: () -> Unit,
    private val openCatalog: () -> Unit,
    private val showConfigDialog: () -> Unit,
    private val onShowContentEdit: () -> Unit,
    // 章节换源覆盖层触发回调 (顶栏 CHAPTER_CHANGE_SOURCE → 弹 ChangeChapterSourceScreen)
    private val showChangeChapterSource: () -> Unit,
    private val onOpenBookInfo: () -> Unit,
    private val onOpenSearchContent: () -> Unit,
    private val onOpenReplaceRule: () -> Unit,
    private val onOpenMoreConfig: () -> Unit,
    private val onOpenPaddingConfig: () -> Unit,
    private val onOpenTipConfig: () -> Unit,
    // 段评/书评列表入口 (顶栏 REVIEW 菜单触发, 对照 app 端 openCommentDialog)
    private val onOpenReviewList: () -> Unit,
    private val onToggleNightTheme: () -> Unit,
    private val autoPageScope: CoroutineScope,
    // 完整版 Dialog 回调 (KP2-D P1: 替代简化版 ReadConfigPanel)
    private val showReadStyleDialog: () -> Unit,
    private val showBgTextConfigDialog: () -> Unit,
    private val showAutoReadDialog: () -> Unit,
    // 朗读控制 Dialog 回调 (桥接 longClickReadAloud)
    private val showReadAloudDialog: () -> Unit,
    // 整书换源回调 (顶栏 CHANGE_SOURCE / BOOK_CHANGE_SOURCE, 对照 app 端 ChangeBookSourceDialog)
    private val showChangeBookSource: () -> Unit,
    // 添加书签回调 (顶栏 ADD_BOOKMARK, 对照 app 端 addBookmark → BookmarkDialog)
    private val showAddBookmark: () -> Unit,
    // 日志查看回调 (顶栏 LOG, 对照 app 端 showDialogFragment<AppLogDialog>)
    private val showAppLogDialog: () -> Unit,
    // 启用/禁用替换规则回调 (顶栏 ENABLE_REPLACE, 对照 app 端 changeReplaceRuleState)
    private val toggleReplaceRule: () -> Unit,
) : ReadMenuState {

    // 自动翻页定时 job (clickAutoPage 启动/取消, 5s 间隔调 viewModel.nextPage)
    private var autoPageJob: Job? = null

    // ---- 显隐与动画 ----
    override val visibleState = MutableTransitionState(false)
    override val animate: Boolean = true
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override var canShowMenu: Boolean = false
        private set

    // ---- 沉浸式菜单色彩（桌面端固定非沉浸式，走 ReadMenuOverlay 的 AppTheme.colors 分支）----
    // bgColor/textColor 仅在 immersive=true 时被 ReadMenuOverlay 使用，这里给默认值避免 0 黑色
    override val immersive: Boolean = false
    override var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())
        private set
    override var textColor by mutableIntStateOf(0xFF000000.toInt())
        private set
    override val hasBgImage: Boolean = false

    // ---- 顶栏 ----
    override var title by mutableStateOf<String?>(null)
        private set
    override var chapterName by mutableStateOf<String?>(null)
        private set
    override var chapterUrl by mutableStateOf<String?>(null)
        private set
    override var chapterNameVisible by mutableStateOf(false)
        private set
    override var chapterUrlVisible by mutableStateOf(false)
        private set
    override var sourceActionText by mutableStateOf("")
        private set
    override var sourceActionVisible by mutableStateOf(false)
        private set
    override val titleBarAdditionVisible: Boolean = true
    override val topMenu: TopMenuState = TopMenuState().apply {
        // 桌面端默认按"在线书源"展示顶栏换源/刷新/离线缓存按钮
        // (对照 app 端 ReadMenu.upTopMenu: onLine = !book.isLocal)
        onLine = !book.isLocal
    }

    // ---- 底栏 ----
    override var seekMax by mutableIntStateOf(0)
        private set
    override var seekValue by mutableIntStateOf(0)
        private set
    override var prevEnabled by mutableStateOf(false)
        private set
    override var nextEnabled by mutableStateOf(false)
        private set
    override var autoPage by mutableStateOf(false)

    // ---- 内部辅助方法 ----

    /**
     * 由 [ReaderScreen] 调用同步状态（curTextPage / 章节索引 / 章节总数变化时触发）。
     * 对照 app 端 `ReadMenu.upBookView()`：同步顶栏标题 + 底栏进度 + 上一/下一章可用性。
     */
    fun upBookView(chapterTitle: String?, durIndex: Int, chapterSize: Int) {
        title = book.name
        chapterName = chapterTitle
        chapterNameVisible = chapterTitle != null
        chapterUrlVisible = false
        seekMax = (chapterSize - 1).coerceAtLeast(0)
        seekValue = durIndex
        prevEnabled = durIndex > 0
        nextEnabled = durIndex < chapterSize - 1
        // 同步替换规则勾选状态 (对照 app 端 ReadMenu.upTopMenu: enableReplaceChecked = book.getUseReplaceRule)
        topMenu.enableReplaceChecked = book.getUseReplaceRule()
    }

    /**
     * 切菜单显隐（中心点击触发，与 app 端 showActionMenu 行为对齐）。
     *
     * 对照 app 端 [io.legado.app.ui.book.read.ReadBookActivity.showActionMenu]：
     * - 朗读服务运行中 → showReadAloudDialog (桌面端 TTS 控制已通过 TtsControlPanel 提供, 此处不重复)
     * - 自动翻页运行中 (isAutoPage=true) → 弹 AutoReadDialog 配置速度
     * - 正在显示搜索结果 → searchMenu.runMenuIn (桌面端未接入搜索菜单, 走 else 分支)
     * - 其他 → readMenu.runMenuIn / runMenuOut (切菜单显隐)
     */
    fun toggleMenu() {
        if (autoPage) {
            // 自动翻页运行中, 中心点击弹 AutoReadDialog 配置速度 (对齐 app 端 showActionMenu)
            showAutoReadDialog()
        } else if (isVisible) {
            runMenuOut()
        } else {
            runMenuIn()
        }
    }

    /** 显示菜单（与 app 端 ReadMenu.runMenuIn 对应，简化出场动画收尾）*/
    fun runMenuIn() {
        canShowMenu = true
        visibleState.targetState = true
    }

    /** 隐藏菜单（与 app 端 ReadMenu.runMenuOut 对应，简化出场动画收尾）*/
    fun runMenuOut() {
        visibleState.targetState = false
    }

    // ---- 动画生命周期回调 ----

    override fun onTransitionIdle(shown: Boolean) {
        if (!shown) {
            canShowMenu = false
        }
    }

    override fun onBgClick() {
        // 菜单显示时点击空白区域收起（与 app 端 onBgClick 一致）
        if (isVisible) runMenuOut()
    }

    // ---- 顶栏动作回调 ----

    override fun onChapterViewClick() {
        // app 端用浏览器打开章节 URL（依赖 WebViewActivity），桌面端用 Desktop.browse 打开
        // 取当前章节 url (对照 ReadRssScreen 的 currentChapter?.url 用法)
        val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
        val url = chapter?.url
        if (!url.isNullOrBlank()) {
            browseUrl(url)
        }
    }

    override fun onChapterViewLongClick() {
        // app 端弹"用浏览器打开"开关对话框，桌面端暂 no-op
    }

    override fun onOverflowOpened() {
        // app 端在展开溢出菜单时刷新 sameTitleRemoved / review 可见性，
        // 桌面端这两个菜单项的可见性由 TopMenuState 默认值控制（reviewVisible=false）
    }

    override fun sourceLoginVisible(): Boolean = false

    override fun sourcePayVisible(): Boolean = false

    override fun onSourceAction(action: SourceAction) {
        when (action) {
            SourceAction.DISABLE_SOURCE -> {
                // 禁用书源 (对照 app 端 disableSource → viewModel.disableSource)
                viewModel.disableSource()
            }
            // 其余书源操作 (登录/章节购买/变量编辑/编辑书源) 桌面端暂未接入, no-op
            else -> {}
        }
    }

    // ---- 宿主桥接（原 state.activity.xxx）----

    override fun openBookInfoActivity() {
        // 切到 BOOK_INFO 路由 (携带当前 book), 由 ReaderScreen 注入的 onOpenBookInfo 回调触发
        onOpenBookInfo()
    }

    override fun supportFinishAfterTransition() {
        onBack()
    }

    override fun onTopMenuAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.REFRESH,
            ReadMenuAction.REFRESH_DUR -> {
                // 重载当前章节（与 app 端 refreshContentDur 对应）
                viewModel.loadChapter(viewModel.durChapterIndex.value)
            }
            ReadMenuAction.PAGE_ANIM -> {
                // 弹出完整版 ReadStyleDialog (含翻页模式切换 + 样式列表)
                // 对照 app 端 onTopMenuAction(PAGE_ANIM) → showPageAnimConfig → upPageAnim
                showReadStyleDialog()
            }
            ReadMenuAction.EDIT_CONTENT -> {
                // 弹出 ContentEditDialog 编辑当前章节正文
                // (与 app 端 showDialogFragment(ContentEditDialog()) 对应)
                onShowContentEdit()
            }
            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> {
                // 章节换源: 弹 ChangeChapterSourceScreen 覆盖层
                // (对照 app 端 onMenuAction(CHAPTER_CHANGE_SOURCE) → ChangeChapterSourceDialog)
                showChangeChapterSource()
            }
            ReadMenuAction.REVIEW -> {
                // 段评/书评列表: 切到 REVIEW_LIST 路由 (对照 app 端 openCommentDialog, paragraphIndex=0 章节级评论)
                onOpenReviewList()
            }
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                // 整书换源: 切到 CHANGE_SOURCE 路由 (对照 app 端 ChangeBookSourceDialog)
                showChangeBookSource()
            }
            ReadMenuAction.ADD_BOOKMARK -> {
                // 添加书签: 弹 BookmarkDialog (对照 app 端 addBookmark → BookmarkDialog)
                showAddBookmark()
            }
            ReadMenuAction.LOG -> {
                // 日志查看: 弹 AppLogDialog (对照 app 端 showDialogFragment<AppLogDialog>)
                showAppLogDialog()
            }
            ReadMenuAction.ENABLE_REPLACE -> {
                // 启用/禁用替换规则 (对照 app 端 changeReplaceRuleState)
                // 同步 topMenu 勾选状态 + 调 toggleReplaceRule 切换 config + 重载章节
                val curBook = viewModel.book.value
                if (curBook != null) {
                    topMenu.enableReplaceChecked = !curBook.getUseReplaceRule()
                }
                toggleReplaceRule()
            }
            // 其余顶栏 action 桌面端暂未实现（需 DesktopApp 路由切换 / DialogFragment 薄壳 /
            // 专属 Activity），no-op 占位但菜单项保留（对齐 app 原版菜单结构，不删除）
            else -> {}
        }
    }

    // ---- 底栏动作回调 ----

    override fun onSeekDragStart() {
        // 进度条拖动开始（app 端用 bgClickEnabled=false 拦截 bg 点击，桌面端简化）
    }

    override fun onSeekStop(progress: Int) {
        // 章节级跳转（与 app 端 skipToChapter 对应，桌面端 viewModel.loadChapter 即可）
        viewModel.loadChapter(progress)
    }

    override fun clickSearch() {
        // 切到 SEARCH_CONTENT 路由 (书内全文搜索), 由 ReaderScreen 注入的 onOpenSearchContent 回调触发
        onOpenSearchContent()
    }

    override fun clickAutoPage() {
        // 启动/取消自动翻页定时 job
        // (app 端用 readView.autoPager.start, 桌面端简化为协程定时调 viewModel.nextPage,
        //  间隔 5s 与 app 端默认 autoPagePeriod 一致)
        autoPage = !autoPage
        if (autoPage) {
            autoPageJob?.cancel()
            autoPageJob = autoPageScope.launch {
                while (isActive) {
                    delay(5000)
                    viewModel.nextPage()
                }
            }
        } else {
            autoPageJob?.cancel()
            autoPageJob = null
        }
    }

    override fun clickReplaceRule() {
        // 切到 EFFECTIVE_REPLACES 路由 (有效替换规则), 由 ReaderScreen 注入的 onOpenReplaceRule 回调触发
        onOpenReplaceRule()
    }

    override fun clickNightTheme() {
        // 切换夜间主题: 调用方注入 onToggleNightTheme, 内部走 DesktopThemeStoreProvider.toggleDark
        // (ThemeStore 用 mutableStateOf 持有 isDark, toggleDark 触发 Compose 重组刷新色彩)
        onToggleNightTheme()
    }

    override fun clickPre() {
        // 上一章（与 app 端 ReadBook.moveToPrevChapter 对应）
        viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        // 下一章（与 app 端 ReadBook.moveToNextChapter 对应）
        viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        // 打开目录侧栏（与 app 端 openChapterList 对应，桌面端用 ModalDrawer 实现）
        openCatalog()
    }

    override fun clickReadAloud() {
        // 朗读控制已通过 TtsControlPanel 提供（浮动在底栏上方），无需重复入口
        // 此处保留菜单项与 app 端一致，点击 no-op（不重复弹朗读对话框）
    }

    override fun longClickReadAloud() {
        // 弹出 ReadAloudDialog (shared 共享, 替代 app 端 showDialogFragment<ReadAloudDialog>())
        // 对照 app 端 ReadBookActivity.longClickReadAloud → showReadAloudDialog
        showReadAloudDialog()
    }

    override fun clickFont() {
        // 显示完整版 ReadStyleDialog (与 app 端 showReadStyle 对应)
        // 对照 app 端 ReadBookActivity.clickFont → showDialogFragment<ReadStyleDialog>()
        showReadStyleDialog()
    }

    override fun clickSetting() {
        // 切到 MORE_CONFIG 路由 (更多阅读配置), 由 ReaderScreen 注入的 onOpenMoreConfig 回调触发
        onOpenMoreConfig()
    }
}
