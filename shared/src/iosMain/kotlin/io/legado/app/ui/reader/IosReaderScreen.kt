package io.legado.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material.DrawerValue
import androidx.compose.material.ModalDrawer
import androidx.compose.material.rememberDrawerState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.AppLog
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isImage
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.model.ReadBookShared
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.IosChangeBookSourceScreen
import io.legado.app.ui.book.changesource.IosChangeChapterSourceScreen
import io.legado.app.ui.book.changesource.findChapterIndex
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadMenuOverlay
import io.legado.app.ui.book.read.config.ClickActionConfig
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.book.read.config.MoreConfigScreen
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.page.ReadViewComposable
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegateCompose
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.toc.TocDrawerContent
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.IosEventBusProvider
import io.legado.app.ui.compose.platform.IosPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.dict.IosDictDialog
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.replace.ReplaceEditScreen
import io.legado.app.ui.replace.ReplaceRuleListScreen
import io.legado.app.ui.replace.ReplaceRuleListViewModel
import io.legado.app.ui.replace.edit.ReplaceEditViewModelShared
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端阅读页 Screen 入口 (KP5: 包装 shared/sharedUiMain 的 [ReadViewComposable] + [ReadMenuOverlay])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/reader/ReaderScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 READER 路由分支调用本入口。
 *
 * 本文件仅做 iOS 平台适配, 正文排版 / 翻页编排 / 菜单层下沉到 shared:
 * - **VM 生命周期**: `remember { ReadBookViewModelShared(...) }` 持有, 退出时
 *   `DisposableEffect.onDispose { viewModel.saveProgress(); viewModel.pageDelegate?.onDestroy() }`
 *   持久化阅读进度 + 释放翻页动画资源 (对照 desktop ReaderScreen line 179-184)
 * - **章节装载**: `LaunchedEffect(book.bookUrl)` 内调 `readBook.loadBook(book)`
 *   + `viewModel.loadChapter(book.durChapterIndex)` (对照 desktop ReaderScreen line 162-165)
 * - **配置注入**: 用 [IosPreferenceStoreProvider] (NSUserDefaults) 构造 [ReadConfigProviders]
 *   工厂实例, 经 [LocalReadConfigProviders] 注入 (对照 desktop ReaderScreen line 128-131;
 *   PageViewComposable / IosReadStyleDialog 依赖 LocalReadConfigProviders.current)
 * - **菜单层**: 调用 shared [ReadMenuOverlay], 传入 iOS 端 [IosReadMenuState] 实现
 *   `ReadMenuState` 接口 (与 app 端 `ReadMenu` 类 / 桌面端 `DesktopReadMenuState` 对应)
 * - **目录侧栏**: [ModalDrawer] + [TocDrawerContent] (由底栏 `clickCatalog` 触发)
 * - **TTS 控制**: [ReadAloudDialog] (由底栏朗读按钮长按触发, 桥接 [rememberIosReadAloudController])
 *
 * # 点击 / 长按事件
 *
 * - **点击正文 (中心区域)**: 调 [IosReadMenuState.toggleMenu] 切菜单显隐
 *   (与 app 端 `showActionMenu` 行为一致; 翻页由 pageDelegate 自己处理 `onTap`)
 * - **长按正文**: 异步加载章节正文后弹 [IosTextSelectionDialog] 文字选择对话框
 *   (对照 desktop 端 TextSelectionDialog, 用 SelectionContainer + ComposeTextToolbar)
 *
 * # 简化项 (iOS 端 KP5 阶段, 与 desktop 端差异)
 *
 * - 完整版阅读样式配置已接入 [IosReadStyleDialog] (包装 shared/sharedUiMain 的 ReadStyleScreen +
 *   BgTextConfig/PaddingConfig/TipConfig 子 Dialog), 替代原简化版 ReadConfigPanel;
 *   AutoReadDialog (自动翻页) 已接入 [IosAutoReadPanel] (包装 shared/sharedUiMain 的 AutoReadPanel)
 * - 不接入 TtsControlPanel 浮动面板 (desktop 专属 Composable, 下沉范围大, 留 TODO);
 *   iOS 端朗读控制通过 [ReadAloudDialog] (底栏朗读按钮触发) 提供
 * - 不接入整书换源 (CHANGE_SOURCE / BOOK_CHANGE_SOURCE): 需独立路由, 暂留 TODO;
 *   章节换源 (CHAPTER_CHANGE_SOURCE) 已通过 [IosChangeChapterSourceScreen] 接入
 *
 * @param book 待阅读的书籍 (由 IosNavHost READER 路由分支从 readerBook 状态注入)
 * @param onBack 返回回调 (切回书架路由, 由 IosNavHost 注入)
 * @param onOpenBookInfo 进入书籍详情回调 (由顶栏标题区域点击触发, 切到 BOOK_INFO 路由)
 */
@Composable
fun IosReaderScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenBookInfo: (Book) -> Unit = {},
) {
    // 注入 ReadConfigProviders (PageViewComposable 依赖 LocalReadConfigProviders.current)
    // 用 IosPreferenceStoreProvider 内存 Map 实现, 进程结束即丢失 (与其他 iOS Provider 一致,
    // 对照 desktop ReaderScreen line 128-131 的 DesktopPreferenceStoreProvider 模式)
    val prefs = remember { IosPreferenceStoreProvider() }
    val readConfigProviders = remember { ReadConfigProviders(prefs) }

    CompositionLocalProvider(LocalReadConfigProviders provides readConfigProviders) {
        // 创建 ReadBookShared + ViewModel (对照 desktop ReaderScreen line 133-159)
        val readBook = remember { ReadBookShared() }
        val scope: CoroutineScope = rememberCoroutineScope()
        // KP4: 暂用 LayoutConfig.DEFAULT (默认字号/行距/段距, 桌面 720x1080 等价近似值),
        // 不读 readBookConfig 的 textSize/lineSpacingExtra/paragraphSpacing (避免与 desktop 端
        // "density 近似 2x" 的转换耦合); 后续 KP5+ 接入精确 sp→px 换算
        val viewModel = remember {
            ReadBookViewModelShared(readBook, scope)
        }

        // 装载书 + 章节 (与 app 端 ReadBook.initData + loadContent 对应, 对照 desktop line 162-165)
        LaunchedEffect(book.bookUrl) {
            readBook.loadBook(book)
            viewModel.loadChapter(book.durChapterIndex)
        }

        // 退出时持久化阅读进度 + 释放翻页动画资源
        // (KP2-D P0-C: 调 viewModel.saveProgress() 把 durChapterIndex/durChapterPos/标题 PATCH 进 books 表)
        DisposableEffect(viewModel) {
            onDispose {
                viewModel.saveProgress()
                viewModel.pageDelegate?.onDestroy()
            }
        }

        val curTextPage by viewModel.curTextPage.collectAsState()
        val chapterList by viewModel.chapterList.collectAsState()
        val durChapterIndex by viewModel.durChapterIndex.collectAsState()

        // KP5: 创建 TTS 朗读控制器 (绑定 viewModel 生命周期)
        // IosProviderRegistry 已注册 IosSystemTtsEngine (AVSpeechSynthesizer), 控制器通过 provider 取引擎
        val readAloudController = rememberIosReadAloudController(viewModel)

        // KP5: 目录侧栏状态 + 章节列表 / 当前章节订阅 (供 TocDrawerContent 高亮 + 跳转联动)
        // drawerState 用 ModalDrawer 标准状态, 默认 Closed; scope.launch { open/close } 切换
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        // 朗读控制 Dialog 显隐状态 (底栏朗读按钮长按触发, 与 app 端 ReadBookActivity.showReadAloudDialog 对应)
        var showReadAloudDialog by remember { mutableStateOf(false) }

        // 自动翻页配置 Dialog 显隐状态 (自动翻页运行时点击中心触发, 与 app 端 ReadBookActivity.showActionMenu 对应)
        // 对照 desktop ReaderScreen: toggleMenu 在 autoPage=true 时弹 AutoReadDialog 配置速度
        var showAutoReadDialog by remember { mutableStateOf(false) }

        // 阅读菜单回调触发的 Dialog/Screen 显隐状态 (clickFont/clickSetting/clickSearch/clickReplaceRule)
        // 与桌面端 ReaderScreen 的 showConfigDialog/showReadStyleDialog 等模式一致
        // showReadStyleDialog: 完整版阅读样式配置 (包装 shared/sharedUiMain 的 ReadStyleScreen, 替代简化版 ReadConfigPanel)
        var showReadStyleDialog by remember { mutableStateOf(false) }
        var showEffectiveReplaces by remember { mutableStateOf(false) }
        // 替换规则编辑 Dialog (EffectiveReplaces onAddRule/onItemClick 或管理页 onAddRule/onEditRule 触发)
        // ruleId > 0 编辑已有; <= 0 新增 (scope 预填当前书籍作用范围, 对照 app 端 addRule)
        var showReplaceEditDialog by remember { mutableStateOf(false) }
        var replaceEditRuleId by remember { mutableStateOf(-1L) }
        var replaceEditScope by remember { mutableStateOf<String?>(null) }
        // 全局替换规则管理 Dialog (EffectiveReplaces onManageAll 触发, 包装 ReplaceRuleListScreen)
        var showReplaceRuleListDialog by remember { mutableStateOf(false) }
        var showMoreConfig by remember { mutableStateOf(false) }
        var showSearchContent by remember { mutableStateOf(false) }

        // 章节正文编辑 Dialog (顶栏 EDIT_CONTENT 触发, 对照 desktop ReaderScreen)
        var showContentEditDialog by remember { mutableStateOf(false) }
        var contentEditChapterName by remember { mutableStateOf("") }
        var contentEditContent by remember { mutableStateOf("") }

        // 长按正文文字选择 Dialog (替代原 no-op, 对照 desktop TextSelectionDialog)
        var showTextSelectionDialog by remember { mutableStateOf(false) }
        var loadingTextSelection by remember { mutableStateOf(false) }
        var textSelectionChapterName by remember { mutableStateOf("") }
        var textSelectionContent by remember { mutableStateOf("") }

        // 查词对话框状态 (IosTextSelectionDialog 底部"查词"按钮触发, 对照 desktop ReaderScreen)
        // dictWord 由剪贴板内容填充, showDict 控制 IosDictDialog 显隐
        var showDict by remember { mutableStateOf(false) }
        var dictWord by remember { mutableStateOf("") }

        // 章节换源覆盖层 (顶栏 CHAPTER_CHANGE_SOURCE 触发, 对照 desktop ReaderScreen)
        var showChangeChapterSource by remember { mutableStateOf(false) }
        var changeChapterSourceChapter by remember { mutableStateOf<BookChapter?>(null) }

        // 整书换源覆盖层 (顶栏 CHANGE_SOURCE / BOOK_CHANGE_SOURCE 触发, 对照 app 端 ChangeBookSourceDialog)
        var showChangeBookSource by remember { mutableStateOf(false) }

        // 添加书签 Dialog (顶栏 ADD_BOOKMARK 触发, 对照 app 端 addBookmark → BookmarkDialog)
        // bookmarkDraft: ADD_BOOKMARK 触发时构建, BookmarkDialog 编辑后 onConfirm 入库
        var showBookmarkDialog by remember { mutableStateOf(false) }
        var bookmarkDraft by remember { mutableStateOf<Bookmark?>(null) }

        // 书源登录 Dialog (SourceAction.LOGIN 触发, 对照 app 端 showLogin → showLoginDialog)
        // loginTarget: LOGIN 触发时从 readBook.bookSource 取, 末尾 SourceLoginDialog 渲染分支读取
        var loginTarget by remember { mutableStateOf<BookSource?>(null) }
        // 变量编辑 Dialog (SourceAction.SET_SOURCE_VARIABLE / SET_BOOK_VARIABLE 触发)
        var showVariableDialog by remember { mutableStateOf(false) }

        // 夜间主题切换: 取外层 MainViewController 注入的 LocalPreferenceStoreProvider +
        // LocalEventBusProvider, 写 PreferKey.themeMode 后 emitRecreate 触发 AppTheme 重组
        // (对照 desktop ReaderScreen 的 onToggleNightTheme → DesktopThemeStoreProvider.toggleDark)
        val prefStore = LocalPreferenceStoreProvider.current
        val eventBus = LocalEventBusProvider.current

        // iOS 端 ReadMenuState 实现: 桥接 shared ReadMenuOverlay 与 ReadBookViewModelShared
        // 对照 app 端 ReadMenu 类 / 桌面端 DesktopReadMenuState, iOS 端简化实现沉浸式色彩 / 出场动画收尾等细节
        val readMenuState = remember(viewModel, book, onBack, onOpenBookInfo) {
            IosReadMenuState(
                viewModel = viewModel,
                book = book,
                onBack = onBack,
                onOpenBookInfo = { onOpenBookInfo(book) },
                openCatalog = { scope.launch { drawerState.open() } },
                showReadAloudDialog = { showReadAloudDialog = true },
                onToggleNightTheme = {
                    // 切换 themeMode: 当前夜间 ("2") → 日间 ("0"); 否则 → 夜间 ("2")
                    // (避免 E-Ink "3" 被误切换; E-Ink 模式下点击夜间主题按钮会切到夜间, 与 app 端行为一致)
                    val currentMode = prefStore.getString(PreferKey.themeMode, "0") ?: "0"
                    val newMode = if (currentMode == "2") "0" else "2"
                    prefStore.putString(PreferKey.themeMode, newMode)
                    // emitRecreate 触发 AppTheme 重组, 重读 ThemeStore 刷新主题色
                    (eventBus as? IosEventBusProvider)?.emitRecreate()
                },
                onOpenSearchContent = { showSearchContent = true },
                onOpenReplaceRule = { showEffectiveReplaces = true },
                onOpenMoreConfig = { showMoreConfig = true },
                onOpenReadStyle = { showReadStyleDialog = true },
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
                showChangeChapterSource = {
                    // 取当前章节后弹出 IosChangeChapterSourceScreen 覆盖层
                    val curBook = viewModel.book.value
                    val chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                    if (curBook != null && chapter != null) {
                        changeChapterSourceChapter = chapter
                        showChangeChapterSource = true
                    }
                },
                showAddBookmark = {
                    // 构建书签草稿 (对照 app 端 addBookmark): 取当前页 title/text + 章节索引/位置
                    val curBook = viewModel.book.value
                    val page = curTextPage
                    if (curBook != null && page != null) {
                        bookmarkDraft = Bookmark(
                            bookName = curBook.name,
                            bookAuthor = curBook.author,
                        ).apply {
                            chapterIndex = viewModel.durChapterIndex.value
                            chapterPos = readBook.durChapterPos.value
                            chapterName = page.title
                            bookText = page.text.trim()
                        }
                        showBookmarkDialog = true
                    }
                },
                showChangeBookSource = {
                    // 弹出 IosChangeBookSourceScreen 覆盖层 (整书换源)
                    showChangeBookSource = true
                },
                showSourceLogin = {
                    // 取当前书源后弹 SourceLoginDialog (对照 app 端 showLogin → showLoginDialog)
                    readBook.bookSource.value?.let { loginTarget = it }
                },
                onChapterPay = {
                    // 章节购买: app 端执行 source.contentRule.payAction JS 规则取支付 URL,
                    // iOS 端简化为打开章节 URL (AnalyzeRule 执行逻辑较重, 留 TODO)
                    viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value)
                        ?.url?.takeIf { it.isNotEmpty() }?.let { openURL(it) }
                },
                showVariableDialog = {
                    // 弹变量编辑 Dialog (源变量 + 书籍变量双 Tab)
                    showVariableDialog = true
                },
                openSourceEdit = {
                    // 编辑书源: iOS 端暂无 BOOK_SOURCE_EDIT 路由 + IosBookSourceEditScreen wrapper, 留 TODO
                    Toasters.get().toast(editBookSourceText)
                },
                autoPageScope = scope,
                showAutoReadDialog = { showAutoReadDialog = true },
                readBookConfig = readConfigProviders.readBookConfig,
            )
        }

        // 同步 curTextPage / 章节索引 / 章节总数到 readMenuState (顶栏标题 + 底栏进度条 + 上一/下一章可用性)
        // 对照 app 端 ReadMenu.upBookView (由 ReadBook.callBack.upMenuView 触发)
        LaunchedEffect(curTextPage, durChapterIndex, chapterList.size) {
            readMenuState.upBookView(
                chapterTitle = curTextPage?.title,
                durIndex = durChapterIndex,
                chapterSize = chapterList.size,
            )
        }

        // KP5: 用 ModalDrawer 包裹 Box, drawerContent 渲染 TocDrawerContent
        // 对照 desktop ReaderScreen line 315-329 的目录侧栏模式
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
            Box(modifier = Modifier.fillMaxSize()) {
                // 主内容: 阅读视图
                // 点击中心区域 → 切菜单显隐 (与 app 端 showActionMenu 一致; 翻页由 pageDelegate.onTap 自己处理)
                // 长按正文 → 异步加载正文弹 IosTextSelectionDialog (文字选择)
                // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
                val editBookSourceText = rememberString("ios_edit_book_source_not_implemented")
                // 文案 (onFailure lambda 非 @Composable, 预先 remember)
                val loadChapterContentFailedText = rememberString("load_chapter_content_failed")
                ReadViewComposable(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { _ ->
                        // 中心区域点击 (delegate.onTap 返回 false 时转发到这里)
                        // 与 app 端 ReadBookActivity.showActionMenu() 对应, 切换菜单显隐
                        readMenuState.toggleMenu()
                    },
                    onLongClick = { _ ->
                        // 长按正文: 异步加载当前章节正文, 完成后弹 IosTextSelectionDialog (文字选择)
                        // 对照 desktop 端 TextSelectionDialog, iOS 端用 SelectionContainer + ComposeTextToolbar
                        val curBook = viewModel.book.value
                        val chapter = viewModel.chapterList.value
                            .getOrNull(viewModel.durChapterIndex.value)
                        if (curBook != null && chapter != null) {
                            loadingTextSelection = true
                            // 章节名立即赋值 (loading 对话框标题展示)
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
                                    AppLog.put(loadChapterContentFailedText, it)
                                    Toasters.get().toast(it.localizedMessage ?: loadChapterContentFailedText)
                                }
                            }
                        }
                    },
                )

                // shared 菜单层 (顶栏 + 底栏 + 悬浮按钮行 + 进度条 + 子菜单)
                // 与 app 端 ReadBookActivity.Content() 调用 ReadMenuOverlay(readMenu) 一致
                ReadMenuOverlay(state = readMenuState)

                // 章节换源覆盖层 (顶栏 CHAPTER_CHANGE_SOURCE 触发, 全屏覆盖阅读页)
                // 对照 desktop ReaderScreen: 选中源+章节后取正文 → replaceContent 替换当前阅读页
                // onReplaceContent: saveText 保存正文到 BookStorage + loadChapter 重载章节刷新阅读视图
                if (showChangeChapterSource) {
                    val curBook = viewModel.book.value
                    val chapter = changeChapterSourceChapter
                    if (curBook != null && chapter != null) {
                        IosChangeChapterSourceScreen(
                            book = curBook,
                            chapterIndex = chapter.index,
                            chapterTitle = chapter.title,
                            onBack = { showChangeChapterSource = false },
                            onReplaceContent = { content ->
                                // 保存换源取到的正文到 BookStorage + 重载章节刷新阅读视图
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

                // 整书换源覆盖层 (顶栏 CHANGE_SOURCE / BOOK_CHANGE_SOURCE 触发, 全屏覆盖阅读页)
                // 对照 app 端 ChangeBookSourceDialog: 选中源后 callBack.changeTo(source, book, toc)
                // onChangeSource: 迁移旧书信息到新书 + 入库 + 重载阅读视图 (对照 app 端 ReadBookViewModel.changeTo)
                if (showChangeBookSource) {
                    val curBook = viewModel.book.value
                    if (curBook != null) {
                        IosChangeBookSourceScreen(
                            book = curBook,
                            onBack = { showChangeBookSource = false },
                            onChangeSource = { source, newBook, toc ->
                                // 整书换源迁移 (对照 app 端 curBook.migrateTo(newBook, toc) + 入库)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        // 章节定位 (对照 app 端 BookHelp.getDurChapter, 简化匹配策略)
                                        val newIndex = findChapterIndex(
                                            toc, curBook.durChapterIndex, curBook.durChapterTitle ?: "",
                                        )
                                        newBook.durChapterIndex = newIndex
                                        // TODO: ContentProcessor.getTitleReplaceRules 未下沉,
                                        //   直接取 toc[newIndex].title (与桌面端简化一致)
                                        newBook.durChapterTitle = toc.getOrNull(newIndex)?.title
                                            ?: curBook.durChapterTitle
                                        // 复制阅读进度 / 分组 / 自定义字段 (对照 app 端 Book.migrateTo)
                                        newBook.durChapterPos = curBook.durChapterPos
                                        newBook.durChapterTime = curBook.durChapterTime
                                        newBook.group = curBook.group
                                        newBook.order = curBook.order
                                        newBook.customCoverUrl = curBook.customCoverUrl
                                        newBook.customIntro = curBook.customIntro
                                        newBook.customTag = curBook.customTag
                                        newBook.canUpdate = curBook.canUpdate
                                        newBook.readConfig = curBook.readConfig
                                        // 入库: 删旧书 + 存新书 + 存新目录
                                        // (对照 app 端 curBook.delete + bookDao.insert + bookChapterDao.insert)
                                        val appDb = AppDbProviders.get()
                                        appDb.bookDao.delete(curBook)
                                        appDb.bookDao.insert(newBook)
                                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                                    }
                                    // 重载阅读视图 (对照 app 端 onSourceChanged → ReadBook.loadContent)
                                    readBook.loadBook(newBook)
                                    viewModel.loadChapter(newBook.durChapterIndex)
                                    showChangeBookSource = false
                                }
                            },
                        )
                    }
                }
            }
        }

        // 朗读控制 Dialog (底栏朗读按钮触发, 包装 shared/sharedUiMain 的 ReadAloudDialog)
        // 桥接 readAloudController: isPlaying/onPlayPause/onStop/onPrev/onNext/onPrevParagraph/
        // onNextParagraph 走 controller; onSetTimer/onFollowSysChange/onBackstage 暂 no-op
        // (iOS 端无睡眠定时 / 跟随系统语速 / 转后台语义)
        // onOpenChapterList 桥接目录侧栏; onShowMenuBar 桥接菜单显隐 + dismiss;
        // onOpenSettings 暂 no-op (ReadAloudConfig 路由由 MoreConfig 入口, 此处不重复)
        if (showReadAloudDialog) {
            ReadAloudDialog(
                isPlaying = readAloudController.state.value == ReadAloudControllerShared.ReadAloudState.PLAYING,
                initialTimer = 0, // iOS 端无睡眠定时
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
                onSetTimer = { /* iOS 端无睡眠定时 */ },
                onAdjustSpeed = { rate -> readAloudController.setSpeechRate(rate) },
                onFollowSysChange = { /* iOS 端无跟随系统语速 */ },
                onOpenChapterList = { scope.launch { drawerState.open() } },
                onShowMenuBar = {
                    if (!readMenuState.isVisible) readMenuState.runMenuIn()
                    showReadAloudDialog = false
                },
                onBackstage = { /* iOS 端无转后台语义 */ },
                onOpenSettings = { /* ReadAloudConfig 路由由 MoreConfig 入口, 此处不重复 */ },
                onDismiss = { showReadAloudDialog = false },
            )
        }

        // 自动翻页配置 Dialog (自动翻页运行时点击中心触发, 包装 shared/sharedUiMain 的 AutoReadPanel)
        // 对照桌面端 ReaderScreen: toggleMenu 在 autoPage=true 时弹 AutoReadDialog 配置速度
        // callbacks 桥接到目录侧栏 / 菜单显隐 / 翻页停止 / 样式配置
        if (showAutoReadDialog) {
            val autoReadCallbacks = remember {
                object : IosAutoReadDialogCallbacks {
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
            IosAutoReadPanel(
                readBookConfig = readConfigProviders.readBookConfig,
                callbacks = autoReadCallbacks,
                onDismiss = { showAutoReadDialog = false },
            )
        }

        // 阅读样式配置 (clickFont 触发, 包装 shared/sharedUiMain 的 ReadStyleScreen)
        // 对照桌面端 ReaderScreen line 442-472 的 ReadStyleDialog 调用模式
        // onUpPageAnim: 翻页动画切换后销毁旧 delegate + 按 readBookConfig.pageAnim 创建新 delegate
        // (与 app 端 callBack.upPageAnim() + ReadBook.loadContent(false) 行为对齐)
        if (showReadStyleDialog) {
            IosReadStyleDialog(
                readBookConfig = readConfigProviders.readBookConfig,
                isImageBook = book.isImage,
                onUpPageAnim = {
                    // 销毁旧 delegate (释放动画协程资源)
                    viewModel.pageDelegate?.onDestroy()
                    // 按 readBookConfig.pageAnim 创建新 delegate
                    // (覆盖 → CoverPageDelegate, 滑动 → SlidePageDelegate, 仿真 → SimulationPageDelegate,
                    //  滚动 → ScrollPageDelegate, 无动画 → NoAnimPageDelegate, 其他 → null)
                    val anim = readConfigProviders.readBookConfig.pageAnim
                    viewModel.pageDelegate = when (anim) {
                        PageAnim.coverPageAnim -> CoverPageDelegateCompose(viewModel, scope)
                        PageAnim.slidePageAnim -> SlidePageDelegateCompose(viewModel, scope)
                        PageAnim.simulationPageAnim -> SimulationPageDelegateCompose(viewModel, scope)
                        PageAnim.scrollPageAnim -> ScrollPageDelegateCompose(viewModel, scope)
                        PageAnim.noAnim -> NoAnimPageDelegateCompose(viewModel, scope)
                        else -> null
                    }
                },
                onDismiss = { showReadStyleDialog = false },
            )
        }

        // 有效替换规则 (clickReplaceRule 触发)
        if (showEffectiveReplaces) {
            // 装载当前书籍启用的内容作用域替换规则 (与 ContentProcessorShared.getContentReplaceRules 等价,
            // 取 replaceRuleDao.findEnabledByContentScope)
            // TODO: ReadBookViewModelShared.loadChapter 未接入 ContentProcessorShared,
            //   无法获取真正"起效"的规则 (实际改变了正文的规则子集), 此处展示启用的内容作用域规则作为近似
            var effectiveRules by remember { mutableStateOf<List<ReplaceRule>>(emptyList()) }
            LaunchedEffect(book.bookUrl) {
                val rules = withContext(Dispatchers.IO) {
                    runCatching {
                        AppDbProviders.get().replaceRuleDao
                            .findEnabledByContentScope(book.name, book.origin)
                    }.getOrDefault(emptyList())
                }
                effectiveRules = rules
            }
            EffectiveReplacesDialog(
                book = book,
                items = effectiveRules,
                onAddRule = {
                    // 新增针对当前书籍的替换规则 (对照 app 端 addRule: scope = book.name;bookSourceUrl)
                    showEffectiveReplaces = false
                    replaceEditRuleId = -1L
                    replaceEditScope = listOfNotNull(book.name, book.origin).joinToString(";")
                    showReplaceEditDialog = true
                },
                onItemClick = { item ->
                    // 编辑指定规则 (对照 app 端 onItemClick: 启动 ReplaceEditActivity 传 item.id)
                    showEffectiveReplaces = false
                    replaceEditRuleId = item.id
                    replaceEditScope = null
                    showReplaceEditDialog = true
                },
                onManageAll = {
                    // 跳转全局替换规则管理 (对照 app 端启动 ReplaceRuleActivity)
                    showEffectiveReplaces = false
                    showReplaceRuleListDialog = true
                },
                onDismiss = { showEffectiveReplaces = false },
            )
        }

        // 替换规则编辑 Dialog (包装 shared/sharedUiMain 的 ReplaceEditScreen, 对照 desktop ReplaceEditScreen)
        // onAddRule/onItemClick/管理页 onAddRule/onEditRule 触发; ruleId > 0 编辑, <= 0 新增 (scope 预填)
        if (showReplaceEditDialog) {
            val editViewModel = remember {
                ReplaceEditViewModelShared(
                    scope = scope,
                    clipTextProvider = { readFromClipboard() },
                )
            }
            // 加载规则 (ruleId > 0 编辑; <= 0 新增用 scope 预填, 对照 app 端 initData)
            LaunchedEffect(replaceEditRuleId) {
                editViewModel.initData(id = replaceEditRuleId, scope = replaceEditScope) { }
            }
            Dialog(
                onDismissRequest = { showReplaceEditDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReplaceEditScreen(
                        viewModel = editViewModel,
                        onBack = { showReplaceEditDialog = false },
                        onSaved = { showReplaceEditDialog = false },
                        onCopyRule = { rule -> copyToClipboard(GSON.toJson(rule)) },
                        onPasteRule = { success -> editViewModel.pasteRule(success) },
                        onHelp = {
                            openURL("https://www.runoob.com/regexp/regexp-tutorial.html")
                        },
                    )
                }
            }
        }

        // 全局替换规则管理 Dialog (包装 shared/sharedUiMain 的 ReplaceRuleListScreen, 对照 desktop ReplaceRuleScreen)
        // onManageAll 触发; 管理页内 onAddRule/onEditRule 再触发上面的编辑 Dialog (浮于管理页之上)
        if (showReplaceRuleListDialog) {
            val listViewModel = remember { ReplaceRuleListViewModel() }
            DisposableEffect(listViewModel) {
                onDispose { listViewModel.onCleared() }
            }
            // 分组管理对话框状态 (onGroupManage 触发, 与 desktop ReplaceRuleScreen 一致:
            // ReplaceRule 分组是 String, GroupManageDialog 期望 List<BookGroup>, 用 groupEntities 适配)
            var showGroupManage by remember { mutableStateOf(false) }
            val groupNames by listViewModel.groups.collectAsState()
            val groupEntities = remember(groupNames) {
                groupNames.mapIndexed { index, name ->
                    BookGroup(groupId = (index + 1).toLong(), groupName = name)
                }
            }
            Dialog(
                onDismissRequest = { showReplaceRuleListDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReplaceRuleListScreen(
                        viewModel = listViewModel,
                        onBack = { showReplaceRuleListDialog = false },
                        onAddRule = {
                            // 新增规则 (全局管理页新增, 不预填 scope; 对照 app 端 ReplaceRuleActivity 新增)
                            replaceEditRuleId = -1L
                            replaceEditScope = null
                            showReplaceEditDialog = true
                        },
                        onEditRule = { id ->
                            // 编辑规则 (管理页保持底层, 编辑 Dialog 浮于上层, 保存后返回管理页)
                            replaceEditRuleId = id
                            replaceEditScope = null
                            showReplaceEditDialog = true
                        },
                        onImportLocal = {
                            // TODO: iOS 文件选择器未接入, 暂不实现本地导入
                        },
                        onImportOnline = {
                            // TODO: iOS 网络导入 URL 输入对话框未接入, 暂不实现
                        },
                        onHelp = {
                            openURL("https://www.runoob.com/regexp/regexp-tutorial.html")
                        },
                        onGroupManage = { showGroupManage = true },
                        onExport = { _ ->
                            // TODO: iOS 文件保存未接入, 暂不实现导出
                        },
                    )
                }
            }
            // 分组管理对话框 (对照 desktop ReplaceRuleScreen 的 GroupManageDialog 渲染分支)
            if (showGroupManage) {
                GroupManageDialog(
                    groups = groupEntities,
                    onAddGroup = { name -> listViewModel.addGroup(name) },
                    onRenameGroup = { groupId, newName ->
                        groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { oldName ->
                            listViewModel.upGroup(oldName, newName)
                        }
                    },
                    onDeleteGroup = { groupId ->
                        groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { name ->
                            listViewModel.delGroup(name)
                        }
                    },
                    onDismiss = { showGroupManage = false },
                )
            }
        }

        // 更多阅读配置 (clickSetting 触发, 全屏覆盖)
        if (showMoreConfig) {
            // pageTouchSlop 当前值 (mutableIntStateOf 让 summary 在 NumberPickerDialog 确认后重组)
            var pageTouchSlop by remember {
                mutableIntStateOf(prefStore.getInt(PreferKey.pageTouchSlop, 0))
            }
            // 2 个嵌套 Dialog 显隐状态 (接入 shared 共享的 NumberPickerDialog / ClickActionDialog)
            var showPageTouchSlopDialog by remember { mutableStateOf(false) }
            var showClickActionDialog by remember { mutableStateOf(false) }
            val pageTouchSlopDialogTitle = rememberString("page_touch_slop_dialog_title")

            Dialog(
                onDismissRequest = { showMoreConfig = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppTitleBar(
                            title = rememberString("more_config"),
                            onBack = { showMoreConfig = false },
                        )
                        MoreConfigScreen(
                            pageTouchSlopSummary = pageTouchSlop.toString(),
                            onPageTouchSlop = { showPageTouchSlopDialog = true },
                            onClickRegionalConfig = { showClickActionDialog = true },
                        )
                    }
                }
            }

            // 滑动翻页阈值 NumberPickerDialog (与 app 端 showNumberPicker 对齐, 范围 0..9999, 0=系统默认)
            if (showPageTouchSlopDialog) {
                NumberPickerDialog(
                    title = pageTouchSlopDialogTitle,
                    value = pageTouchSlop,
                    range = 0..9999,
                    onConfirm = {
                        pageTouchSlop = it
                        prefStore.putInt(PreferKey.pageTouchSlop, it)
                        showPageTouchSlopDialog = false
                    },
                    onDismiss = { showPageTouchSlopDialog = false },
                )
            }

            // 点击区域动作配置 Dialog (接入 shared ClickActionDialog, 读写 PreferKey.clickActionXX)
            // 默认值与 app 端 AppConfig 一致: tl=2/tc=2/tr=1/ml=2/mc=0/mr=1/bl=2/bc=1/br=1
            if (showClickActionDialog) {
                val clickActionConfig = ClickActionConfig(
                    tl = prefStore.getInt(PreferKey.clickActionTL, 2),
                    tc = prefStore.getInt(PreferKey.clickActionTC, 2),
                    tr = prefStore.getInt(PreferKey.clickActionTR, 1),
                    ml = prefStore.getInt(PreferKey.clickActionML, 2),
                    mc = prefStore.getInt(PreferKey.clickActionMC, 0),
                    mr = prefStore.getInt(PreferKey.clickActionMR, 1),
                    bl = prefStore.getInt(PreferKey.clickActionBL, 2),
                    bc = prefStore.getInt(PreferKey.clickActionBC, 1),
                    br = prefStore.getInt(PreferKey.clickActionBR, 1),
                )
                ClickActionDialog(
                    clickActionConfig = clickActionConfig,
                    onConfirm = { newCfg ->
                        // 即时写回 9 个 clickActionXX 字段 (与 app 端 AppConfig.clickActionXX = it 语义对齐)
                        prefStore.putInt(PreferKey.clickActionTL, newCfg.tl)
                        prefStore.putInt(PreferKey.clickActionTC, newCfg.tc)
                        prefStore.putInt(PreferKey.clickActionTR, newCfg.tr)
                        prefStore.putInt(PreferKey.clickActionML, newCfg.ml)
                        prefStore.putInt(PreferKey.clickActionMC, newCfg.mc)
                        prefStore.putInt(PreferKey.clickActionMR, newCfg.mr)
                        prefStore.putInt(PreferKey.clickActionBL, newCfg.bl)
                        prefStore.putInt(PreferKey.clickActionBC, newCfg.bc)
                        prefStore.putInt(PreferKey.clickActionBR, newCfg.br)
                    },
                    onDismiss = { showClickActionDialog = false },
                )
            }
        }

        // 书内全文搜索 (clickSearch 触发, 全屏覆盖)
        if (showSearchContent) {
            Dialog(
                onDismissRequest = { showSearchContent = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IosSearchContentScreen(
                        book = book,
                        onBack = { showSearchContent = false },
                        onOpenResult = { result ->
                            showSearchContent = false
                            viewModel.loadChapter(result.chapterIndex)
                        },
                    )
                }
            }
        }

        // 章节正文编辑 Dialog (顶栏 EDIT_CONTENT 触发), 编辑当前章节正文
        // onReset 不接入 (WebBook 重新获取正文依赖较重, iOS 端未下沉)
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
                clipTextSink = { text -> copyToClipboard(text) },
            )
        }

        // 添加书签 Dialog (顶栏 ADD_BOOKMARK 触发, 包装 shared/sharedUiMain 的 BookmarkDialog)
        // 对照 app 端 addBookmark() → showDialogFragment(BookmarkDialog(bookmark))
        // onConfirm: 修改后的 bookmark 入库 (bookmarkDao.insert, 主键 time 已在草稿构造时生成)
        if (showBookmarkDialog && bookmarkDraft != null) {
            BookmarkDialog(
                bookmark = bookmarkDraft!!,
                showDelete = false,
                onConfirm = { bookmark ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().bookmarkDao.insert(bookmark)
                        }
                        showBookmarkDialog = false
                        bookmarkDraft = null
                    }
                },
                onDismiss = {
                    showBookmarkDialog = false
                    bookmarkDraft = null
                },
            )
        }

        // 长按正文: 加载章节正文时弹 loading AlertDialog (与原 AlertDialog 立即弹出体验对齐)
        if (loadingTextSelection) {
            AlertDialog(
                onDismissRequest = { loadingTextSelection = false },
                title = { Text(textSelectionChapterName.ifBlank { "文字操作" }) },
                text = { Text("正在加载章节内容...") },
                confirmButton = {},
            )
        }

        // 长按正文弹文字选择 Dialog (SelectionContainer + ComposeTextToolbar)
        // 对照 desktop 端 TextSelectionDialog, iOS 端用 IosTextSelectionDialog
        if (showTextSelectionDialog) {
            IosTextSelectionDialog(
                chapterName = textSelectionChapterName,
                content = textSelectionContent,
                onDismiss = { showTextSelectionDialog = false },
                // 查词回调: 读剪贴板取词已在 IosTextSelectionDialog 内完成,
                // 此处仅接收 word → 弹 IosDictDialog (对照 desktop ReaderScreen 查词 → DictDialog)
                onDict = { word ->
                    dictWord = word
                    showDict = true
                },
            )
        }

        // 查词对话框 (IosTextSelectionDialog 底部"查词"按钮触发, 包装 shared DictDialogContent)
        // word 来自剪贴板内容, onDismiss 关闭后保留 dictWord (下次触发时覆盖)
        if (showDict) {
            IosDictDialog(
                word = dictWord,
                onDismiss = { showDict = false },
            )
        }

        // 书源登录 Dialog (SourceAction.LOGIN 触发, 包装 shared/sharedUiMain 的 SourceLoginDialog)
        // 对照 app 端 showLogin → showLoginDialog, iOS 端用 Dialog + SourceLoginDialog
        loginTarget?.let { src ->
            Dialog(
                onDismissRequest = { loginTarget = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SourceLoginDialog(
                        source = src,
                        onDismiss = { loginTarget = null },
                        onOpenUrl = { url -> openURL(url) },
                        book = viewModel.book.value,
                        chapter = viewModel.chapterList.value.getOrNull(viewModel.durChapterIndex.value),
                    )
                }
            }
        }

        // 变量编辑 Dialog (SET_SOURCE_VARIABLE / SET_BOOK_VARIABLE 触发)
        // shared 版双 Tab Map 编辑器, 对照 desktop SourceUiEventBridgeHost 的 VariableDialog 调用
        if (showVariableDialog) {
            val src = readBook.bookSource.value
            val bk = viewModel.book.value
            val sourceVars = remember(src) {
                decodeStringMapOrNull(src?.getVariable()) ?: emptyMap()
            }
            val bookVars = remember(bk) {
                decodeStringMapOrNull(bk?.getCustomVariable()) ?: emptyMap()
            }
            VariableDialog(
                sourceVariables = sourceVars,
                bookVariables = bookVars,
                onConfirm = { newSrcVars, newBookVars ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            src?.setVariable(encodeStringMap(newSrcVars))
                            bk?.putCustomVariable(encodeStringMap(newBookVars))
                        }
                        showVariableDialog = false
                    }
                },
                onDismiss = { showVariableDialog = false },
            )
        }
    }
}
