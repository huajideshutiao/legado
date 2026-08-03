package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.read.CharsetDialog
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.DownloadDialog
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.EffectiveReplacesScreenModel
import io.legado.app.ui.book.read.EffectiveReplacesUiEvent
import io.legado.app.ui.book.read.ImageStyleDialog
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.ReaderScreen
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.ReaderUiActions
import io.legado.app.ui.book.read.ReaderUiState
import io.legado.app.ui.book.read.SimulatedReadingDialog
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.book.read.config.HttpTtsEditDialog
import io.legado.app.ui.book.read.config.HttpTtsEditViewModelShared
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.tipRowHeightPx
import io.legado.app.ui.book.read.page.turnPage
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.AppShortcut
import io.legado.app.ui.compose.platform.AppShortcutHandler
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.chapter_pay
import legado.shared.generated.resources.cloud_progress_exceeds_current
import legado.shared.generated.resources.concurrent_rate
import legado.shared.generated.resources.login_check_js
import legado.shared.generated.resources.login_ui
import legado.shared.generated.resources.login_url
import legado.shared.generated.resources.name
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.source_http_header
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
    // asBook() 每次 copy() 新实例, remember(route) 固定后 LaunchedEffect(book) 只在换路由时重启
    val book = remember(route) { route.book.asBook() }
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

    // region 排版参数注入（对照原版 ChapterProvider.upViewSize + TextStyleProvider.upStyle）
    val density = LocalDensity.current
    val readBookConfig = LocalReadConfigProviders.current.readBookConfig
    val readTipConfig = LocalReadConfigProviders.current.readTipConfig
    val containerSize = LocalWindowInfo.current.containerSize
    // 拖动窗口时 containerSize 每帧变化，停稳后再重排（对照原版 upViewSize 的 postDelayed 去抖）
    var layoutSize by remember { mutableStateOf(containerSize) }
    LaunchedEffect(containerSize) {
        if (layoutSize != containerSize) {
            delay(VIEW_SIZE_DEBOUNCE_MS)
            layoutSize = containerSize
        }
    }
    // 配置变更即时触发：读回新配置后由 VM 比对，参数没变不重排
    var configVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange.collect { changes ->
            if (changes.any { it in relayoutChanges }) configVersion++
        }
    }
    // readTipConfig 进 key: 页眉/页脚显隐与内边距变化时重建排版视口
    LaunchedEffect(screenModel, layoutSize, configVersion, density, readBookConfig, readTipConfig) {
        screenModel.viewModel.updateLayoutConfig(
            buildLayoutConfig(layoutSize, density, readBookConfig, readTipConfig)
        )
    }
    // endregion

    // 初始化书籍数据（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）
    LaunchedEffect(book) {
        screenModel.initBook(book, route.chapterIndex, route.chapterPos)
    }

    val state = remember(screenModel) {
        ReaderUiState(
            viewModel = screenModel.viewModel,
            menuState = screenModel.menuState,
            batteryLevel = screenModel.batteryLevel,
            clockText = screenModel.clockText,
        )
    }

    val actions = remember(navigator, screenModel) {
        object : ReaderUiActions {
            // 点击动作 0（默认中心区域）：显示菜单
            // 菜单显示时 ReadMenuOverlay 的 bg Box 拦截触摸调 onBgClick 收起，不经过本回调
            override fun onPageClick(column: TextColumn?) {
                screenModel.showMenu()
            }

            override fun onPageLongClick(column: TextColumn?) {
                provider.onLongPress(screenModel)
            }

            override fun onTextSelection(text: String) {
                provider.onTextSelected(screenModel, text)
            }

            // 非翻页类点击动作，对照 app 端 ReadView.click 走 callBack 的分支
            // 5/6 朗读上下段，已通过 provider 接入（对照 app 端 ReadAloud.prevParagraph/nextParagraph）
            override fun onPageAction(action: Int) {
                val menu = screenModel.menuState
                when (action) {
                    5 -> provider.readAloudControls(navigator, screenModel)?.prevParagraph()
                    6 -> provider.readAloudControls(navigator, screenModel)?.nextParagraph()
                    7 -> menu.onTopMenuAction(ReadMenuAction.ADD_BOOKMARK)
                    9 -> menu.clickReplaceRule()
                    10 -> menu.clickCatalog()
                    11 -> menu.clickSearch()
                    13 -> menu.clickReadAloud()
                }
            }

            override fun onBack() {
                navigator.pop()
            }
        }
    }

    val scope = rememberCoroutineScope()

    // 阅读页取焦点：Compose 全应用无活动焦点节点时按键根本不进节点树
    // (FocusOwnerImpl.dispatchKeyEvent 取不到 KeyInput 节点直接 return false)，
    // 故本页在栈顶时（含从目录/换源返回后）都要重新请求
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(entry.id) {
        navigator.backStack.collect { stack ->
            if (stack.lastOrNull()?.id == entry.id) {
                runCatching { keyFocusRequester.requestFocus() }
            }
        }
    }
    // 菜单按钮是 clickable，非触摸输入模式下点击会夺焦；收起菜单后要把焦点收回。
    // 用 snapshotFlow 而非直接读 isVisible，避免整页跟着菜单动画重组
    LaunchedEffect(screenModel) {
        snapshotFlow { screenModel.menuState.isVisible }.collect { visible ->
            if (!visible) runCatching { keyFocusRequester.requestFocus() }
        }
    }

    // region 阅读页快捷键 (对照 app 端 ReadBookKeyHandler.onKeyDown)
    // 栈内页面全部留在组合中, 故非栈顶时必须失效, 否则目录/换源等子页里按方向键会翻背景的书;
    // 翻页键菜单可见时不响应 (原版 menuLayoutIsVisible 分支), 字号增减不受菜单影响
    val isTopEntry = { navigator.backStack.value.lastOrNull()?.id == entry.id }
    // 翻页键 200ms 去抖 (对照原版 ReadBookKeyHandler.keyPageDebounce: wait=200/maxWait=200,
    // leading 语义: 200ms 内重复触发只翻一页, 长按不连翻)
    val pageTurnThrottle = remember { PageTurnThrottle() }
    AppShortcutHandler(
        shortcuts = ReaderShortcuts.pageTurn,
        enabled = { isTopEntry() && !screenModel.menuState.isVisible },
    ) { shortcut ->
        val direction = if (shortcut in ReaderShortcuts.prevPage) {
            PageDirectionShared.PREV
        } else {
            PageDirectionShared.NEXT
        }
        pageTurnThrottle.tryTurn {
            // turnPage 内部优先走 pageDelegate.keyTurnPage(带动画), 无委托时直接位移页码
            screenModel.viewModel.turnPage(direction)
        }
    }
    // 音量键翻页 (对照原版 ReadBookKeyHandler VOLUME_UP/DOWN 分支, 默认开启;
    // 开关走平台配置 AppConfig.volumeKeyPage, 关闭时不拦截让系统调音量)
    // 2026-08-04: 用户决策——音量键翻页功能保留恒生效, 仅删 volumeKeyPageOnPlay 配置项。
    AppShortcutHandler(
        shortcuts = ReaderShortcuts.volumePageTurn,
        enabled = { isTopEntry() && !screenModel.menuState.isVisible && provider.volumeKeyPage },
    ) { shortcut ->
        pageTurnThrottle.tryTurn {
            screenModel.viewModel.turnPage(
                if (shortcut.key == Key.VolumeUp) PageDirectionShared.PREV
                else PageDirectionShared.NEXT
            )
        }
    }
    AppShortcutHandler(ReaderShortcuts.textSize, enabled = isTopEntry) { shortcut ->
        val delta = if (shortcut == ReaderShortcuts.increaseTextSize) 1 else -1
        val newSize = (readBookConfig.textSize + delta).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        if (newSize != readBookConfig.textSize) {
            readBookConfig.textSize = newSize
            readBookConfig.save()
            // 与 ReadStyleScreen 字号 seekBar 一致的重排事件
            ReadBookEvents.postConfig(
                ReadConfigChange.CHAPTER_STYLE,
                ReadConfigChange.LOAD_CONTENT,
            )
        }
    }
    // endregion

    ReaderScreen(state = state, actions = actions, focusRequester = keyFocusRequester)

    // region ReadBookEvents 订阅 (对照 app 端 ReadBookActivity.observeLiveBus 的 ReadBookEvents 收集)
    LaunchedEffect(screenModel) {
        // 菜单/顶栏重建 (对照 app 端 actionBarChange → readMenu.reset())
        launch { ReadBookEvents.actionBarChange.collect { screenModel.menuState.reset() } }
        // 进度条刷新 (对照 app 端 seekBarChange → readMenu.upSeekBar())
        launch { ReadBookEvents.seekBarChange.collect { screenModel.menuState.upSeekBar() } }
        // 屏幕超时设置变更 (对照 app 端 keepLightChange → upScreenTimeOut)
        launch { ReadBookEvents.keepLightChange.collect { provider.onKeepLightChange(screenModel) } }
        // 菜单数据刷新 (对照 app 端 menuRefresh → upMenuView())
        launch { ReadBookEvents.menuRefresh.collect { screenModel.menuState.refresh() } }
        // 请求重载目录 (对照 app 端 loadChapterList → viewModel.loadChapterList(book))
        launch {
            ReadBookEvents.loadChapterList.collect { book ->
                screenModel.viewModel.loadChapterList(book)
            }
        }
    }
    // endregion

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
                    // 必须走 changeTo 完成迁移+落库, 只 initBook 会丢目录并在书架残留旧书
                    screenModel.changeTo(payload.source, payload.book, payload.toc)
                }

                RouteResults.BOOK_SOURCE_EDIT -> {
                    screenModel.viewModel.upBookSource()
                    screenModel.menuState.refresh()
                }

                RouteResults.REPLACE_EDIT -> screenModel.viewModel.replaceRuleChanged()

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
                    // 回写缓存, 下次进搜索页可免重搜 (对照 ReadBookActivity.onSearchContentResult)
                    screenModel.searchContentQuery = payload.searchWord.orEmpty()
                    screenModel.searchResultList = payload.searchResults
                    screenModel.searchResultIndex = payload.searchResultIndex
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
        is ReaderDialogEvent.ChapterPay -> {
            // 章节购买确认 (对照原版 ReadBookActivity.payAction 的 alert 确认)
            val book = screenModel.currentBook
            val chapter = screenModel.currentChapter
            if (book == null || chapter == null) {
                screenModel.clearDialogEvent()
            } else {
                AppAlertDialog(
                    onDismissRequest = { screenModel.clearDialogEvent() },
                    title = stringResource(Res.string.chapter_pay),
                    message = chapter.title,
                    okButton = AlertButton(stringResource(Res.string.ok)) {
                        screenModel.clearDialogEvent()
                        screenModel.payChapter { url ->
                            // 支付页 URL 打开方式与 onChapterViewClick 一致 (内嵌 WebView 路由)
                            navigator.push(
                                AppRoute.WebView(
                                    url = url,
                                    sourceKey = book.origin,
                                    sourceName = book.originName,
                                )
                            )
                        }
                    },
                    cancelButton = AlertButton(stringResource(Res.string.cancel)) {
                        screenModel.clearDialogEvent()
                    },
                )
            }
        }

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
                // 标题栏点击改章节标题 (对照原版 ContentEditDialog.editTitle: 落库 + 重载)
                onRenameChapter = { newTitle ->
                    val index = screenModel.viewModel.durChapterIndex.value
                    screenModel.viewModel.renameChapter(index, newTitle)
                },
            )
        }

        is ReaderDialogEvent.Log -> {
            AppLogDialog(onDismiss = { screenModel.clearDialogEvent() })
        }

        // 更多设置 (对照原版 设置按钮 → MoreConfigDialog 底部弹窗)
        is ReaderDialogEvent.MoreConfig -> {
            MoreConfigDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        // 界面/样式设置 (对照原版 界面按钮 → ReadStyleDialog 底部弹窗; 子配置在弹窗内叠层)
        is ReaderDialogEvent.ReadStyle -> {
            ReadStyleDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        // 起效的替换规则 (对照原版 替换按钮 → EffectiveReplacesDialog)
        is ReaderDialogEvent.EffectiveReplaces -> {
            val readBook = LocalReadBookProvider.current.readBook
            val replacesModel = remember {
                EffectiveReplacesScreenModel(
                    getEffectiveReplaceRules = {
                        readBook.curTextChapter.value?.effectiveReplaceRules ?: emptyList()
                    },
                )
            }
            LaunchedEffect(Unit) {
                replacesModel.dispatch(EffectiveReplacesUiEvent.Init)
            }
            val replacesState by replacesModel.state.collectAsState()
            var showChineseConverter by remember { mutableStateOf(false) }
            val currentBook = screenModel.currentBook
            if (currentBook != null) {
                EffectiveReplacesDialog(
                    book = currentBook,
                    items = replacesState.items,
                    onAddRule = {
                        navigator.push(AppRoute.ReplaceEdit())
                        screenModel.clearDialogEvent()
                    },
                    onItemClick = { rule ->
                        if (rule === replacesModel.chineseConvert) {
                            showChineseConverter = true
                        } else {
                            navigator.push(AppRoute.ReplaceEdit(rule.id))
                            screenModel.clearDialogEvent()
                        }
                    },
                    onManageAll = {
                        navigator.push(AppRoute.ReplaceRule)
                        screenModel.clearDialogEvent()
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            }
            // 繁简转换选择器 (对照 EffectiveReplacesRoute)
            if (showChineseConverter) {
                ChineseConverterSelectorDialog(
                    currentType = AppConfigProviders.get().chineseConverterType,
                    onChanged = {
                        replacesModel.dispatch(EffectiveReplacesUiEvent.Init)
                    },
                    onDismiss = { showChineseConverter = false },
                )
            }
        }

        // 朗读设置 (对照原版 朗读面板设置按钮 → ReadAloudConfigDialog)
        is ReaderDialogEvent.ReadAloudConfig -> {
            ReadAloudConfigDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        // 朗读控制面板 (对照原版 ReadMenu 朗读按钮长按 → ReadAloudDialog)
        is ReaderDialogEvent.ReadAloud -> {
            val controls = remember(provider, navigator, screenModel) {
                provider.readAloudControls(navigator, screenModel)
            }
            if (controls == null) {
                // 该端无朗读实现: 直接销事件, 避免卡住后续对话框
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            } else {
                // 朗读态/定时走事件流重组 (对照 app 端 ReadAloudDialog 的 aloudState/readAloudDs 订阅)
                var playing by remember { mutableStateOf(controls.isPlaying) }
                var timer by remember { mutableIntStateOf(controls.timerMinute) }
                LaunchedEffect(controls) {
                    ReadBookEvents.aloudState.collect { playing = controls.isPlaying }
                }
                LaunchedEffect(controls) {
                    ReadBookEvents.readAloudDs.collect { timer = it }
                }
                ReadAloudDialog(
                    isPlaying = playing,
                    initialTimer = timer,
                    initialSpeechRate = controls.speechRate,
                    initialFollowSys = controls.followSys,
                    onPlayPause = { controls.playPause() },
                    onStop = { controls.stop() },
                    onPrev = { controls.prevChapter() },
                    onNext = { controls.nextChapter() },
                    onPrevParagraph = { controls.prevParagraph() },
                    onNextParagraph = { controls.nextParagraph() },
                    onSetTimer = { controls.setTimer(it) },
                    // 面板回调是展示倍率, 还原成原版 ttsSpeechRate 口径 (rate*10-5)
                    onAdjustSpeed = { controls.setSpeechRate((it * 10f).toInt() - 5) },
                    onFollowSysChange = { controls.setFollowSys(it) },
                    onOpenChapterList = {
                        screenModel.clearDialogEvent()
                        controls.openChapterList()
                    },
                    onShowMenuBar = { screenModel.showMenu() },
                    onBackstage = {
                        screenModel.clearDialogEvent()
                        controls.toBackstage()
                    },
                    onOpenSettings = {
                        screenModel.clearDialogEvent()
                        controls.openSettings()
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            }
        }

        // 编辑 HTTP TTS (对照原版 SpeakEngineDialog 中"+"按钮 → HttpTtsEditDialog)
        is ReaderDialogEvent.HttpTtsEdit -> {
            HttpTtsEditDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        // 选择朗读引擎 (对照原版 朗读面板选择引擎 → SpeakEngineDialog)
        is ReaderDialogEvent.SpeakEngine -> {
            SpeakEngineDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
                onEditEngine = { engine ->
                    // 引擎编辑入口: 走平台能力 (app 端 HttpTtsEditDialog, engine=null 新增)
                    PlatformCapabilityProviders.get().showHttpTtsEditDialog(engine)
                },
            )
        }

        // 翻页键配置 (对照原版 更多设置 → PageKeyDialog)
        is ReaderDialogEvent.PageKey -> {
            PageKeyDialogHost(
                onDismiss = { screenModel.clearDialogEvent() },
            )
        }

        // 目录 (对照原版 目录按钮 → TocDialog 全高底部弹窗; 选章节直接跳阅读)
        is ReaderDialogEvent.Toc -> {
            val book = screenModel.currentBook
            if (book != null) {
                TocDialogHost(
                    book = book,
                    onOpenChapter = { index, pos ->
                        screenModel.clearDialogEvent()
                        screenModel.openChapter(index, pos)
                    },
                    onShowTocRegexDialog = { navigator.push(AppRoute.TxtTocRule) },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 整书换源 (对照原版 换源按钮 → ChangeBookSourceDialog 全高底部弹窗)
        is ReaderDialogEvent.ChangeSource -> {
            val book = screenModel.currentBook
            if (book != null) {
                ChangeSourceDialogHost(
                    book = book,
                    onSourceChanged = { source, newBook, toc ->
                        screenModel.clearDialogEvent()
                        screenModel.changeTo(source, newBook, toc)
                    },
                    onEditSource = { origin ->
                        navigator.push(
                            AppRoute.BookSourceEdit(origin),
                            RouteResults.BOOK_SOURCE_EDIT
                        )
                    },
                    onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 章节换源 (对照原版 换源图标长按 → ChangeChapterSourceDialog 全高底部弹窗)
        is ReaderDialogEvent.ChangeChapterSource -> {
            val book = screenModel.currentBook
            val chapter = screenModel.currentChapter
            if (book != null && chapter != null) {
                ChangeChapterSourceDialogHost(
                    book = book,
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    onChapterChanged = { content ->
                        screenModel.clearDialogEvent()
                        // 对照 RouteResults.CHANGE_CHAPTER_SOURCE: 落库 + 刷新当前章
                        scope.launch {
                            runCatching {
                                BookStorageProviders.get().saveText(book, chapter, content)
                            }
                            screenModel.viewModel.refreshCurrentChapter()
                        }
                    },
                    onSourceChanged = { source, newBook, toc ->
                        screenModel.clearDialogEvent()
                        screenModel.changeTo(source, newBook, toc)
                    },
                    onEditSource = { origin ->
                        navigator.push(
                            AppRoute.BookSourceEdit(origin),
                            RouteResults.BOOK_SOURCE_EDIT
                        )
                    },
                    onBookSourceManage = { navigator.push(AppRoute.BookSourceManage) },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 模拟阅读配置 (对照原版 menu_simulated_reading → showSimulatedReading)
        is ReaderDialogEvent.SimulatedReading -> {
            val book = screenModel.currentBook
            if (book != null) {
                SimulatedReadingDialog(
                    book = book,
                    onApply = {
                        screenModel.clearDialogEvent()
                        scope.launch {
                            runCatching { AppDbProviders.get().bookDao.update(book) }
                            // 对照 app 端 book.save() + viewModel.initData: 落库后重装使模拟章节总数生效
                            screenModel.initBook(book, book.durChapterIndex)
                        }
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 图片样式选择器 (对照原版 menu_image_style: 单选后落库 + 单页样式发配置事件 + 重载当前章)
        is ReaderDialogEvent.ImageStyle -> {
            val book = screenModel.currentBook
            if (book != null) {
                ImageStyleDialog(
                    book = book,
                    onApply = { imageStyle ->
                        screenModel.clearDialogEvent()
                        book.config.imageStyle = imageStyle
                        scope.launch {
                            runCatching { AppDbProviders.get().bookDao.update(book) }
                            if (imageStyle == io.legado.app.data.entities.Book.imgStyleSingle) {
                                ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM)
                            }
                            screenModel.viewModel.loadChapter(screenModel.viewModel.durChapterIndex.value)
                        }
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 离线缓存 (对照原版 menu_download → showDownloadDialog → CacheBook.start)
        is ReaderDialogEvent.Download -> {
            val book = screenModel.currentBook
            if (book != null) {
                DownloadDialog(
                    book = book,
                    onApply = { start, end ->
                        screenModel.clearDialogEvent()
                        val bookSource = screenModel.viewModel.bookSource.value
                        val cacheBook = bookSource?.let {
                            runCatching { CacheBookShared.getOrCreate(it, book) }.getOrNull()
                        }
                        if (cacheBook == null) {
                            Toasters.get().toast("离线缓存启动失败: 书源不可用")
                        } else {
                            // 与原版一致: 输入为章节号, CacheBook 下标从 0 起 (start-1/end-1),
                            // 结束章节 clamp 到 lastChapterIndex (原版在 CacheBookService.addDownloadData 内)
                            cacheBook.addDownload(
                                (start - 1).coerceAtLeast(0),
                                end - 1
                            )
                            scope.launch(IoDispatcher) {
                                CacheBookShared.startProcessJob(coroutineContext)
                            }
                        }
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        // 文本编码选择器 (对照原版 menu_set_charset → showCharsetConfig → ReadBook.setCharset)
        is ReaderDialogEvent.SetCharset -> {
            val book = screenModel.currentBook
            if (book != null) {
                CharsetDialog(
                    book = book,
                    onApply = { charset ->
                        screenModel.clearDialogEvent()
                        screenModel.viewModel.setCharset(charset)
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            } else {
                LaunchedEffect(screenModel) { screenModel.clearDialogEvent() }
            }
        }

        null -> Unit
    }
    // endregion
}

/** 视口尺寸去抖窗口（ms），对照原版 upViewSize 的 postDelayed(300) 量级。 */
private const val VIEW_SIZE_DEBOUNCE_MS = 500L

/** 字号可调范围，对照 ReadStyleScreen 字号 seekBar（内部 0..45，展示值 +5）。 */
private const val MIN_TEXT_SIZE = 5
private const val MAX_TEXT_SIZE = 50

/**
 * 阅读页快捷键。PageUp/PageDown/空格为原版键位 (app 端 ReadBookKeyHandler);
 * 方向键是桌面端新增, 替代原版没有的音量键翻页。均无修饰键, 走冒泡阶段分发。
 */
/**
 * 翻页快捷键 200ms 去抖 (对照原版 ReadBookKeyHandler.keyPageDebounce 的 Throttle
 * wait=200/maxWait=200/leading=true/trailing=false: 首次按键立即翻页, 200ms 内
 * 重复按键 (含系统按键 repeat) 丢弃, 长按不会连翻)。
 */
private class PageTurnThrottle(private val intervalMs: Long = 200L) {
    private var lastTurnTime = 0L

    fun tryTurn(block: () -> Unit) {
        val now = systemCurrentTimeMillis()
        if (now - lastTurnTime < intervalMs) return
        lastTurnTime = now
        block()
    }
}

private object ReaderShortcuts {
    val prevPage = listOf(
        AppShortcut(Key.PageUp),
        AppShortcut(Key.DirectionLeft),
        AppShortcut(Key.DirectionUp),
    )
    val nextPage = listOf(
        AppShortcut(Key.PageDown),
        AppShortcut(Key.DirectionRight),
        AppShortcut(Key.DirectionDown),
        AppShortcut(Key.Spacebar),
    )
    val pageTurn = prevPage + nextPage

    /** 音量键翻页 (对照原版 ReadBookKeyHandler VOLUME_UP/DOWN) */
    val volumePageTurn = listOf(
        AppShortcut(Key.VolumeUp),
        AppShortcut(Key.VolumeDown),
    )

    val increaseTextSize = AppShortcut(Key.Equals, command = true)
    val decreaseTextSize = AppShortcut(Key.Minus, command = true)
    val textSize = listOf(increaseTextSize, decreaseTextSize)
}

/**
 * 触发重排的配置事件：原版这些分支都落到 `ChapterProvider.upStyle/upLayout` +
 * `ReadBook.loadContent(resetPageOffset = false)`。
 */
private val relayoutChanges = setOf(
    ReadConfigChange.STYLE,
    ReadConfigChange.CHAPTER_STYLE,
    ReadConfigChange.CHAPTER_LAYOUT,
    ReadConfigChange.LOAD_CONTENT,
)

/**
 * 窗口视口 + [ReadBookConfigShared] → 排版参数，逐字段对照原版
 * `ChapterProvider.upLayout`（padding dp→px）与 `TextStyleProvider.upStyle`（字号 / 间距）。
 */
private fun buildLayoutConfig(
    size: IntSize,
    density: Density,
    config: ReadBookConfigShared,
    tipConfig: ReadTipConfigShared,
): ReadBookViewModelShared.LayoutConfig = with(density) {
    val textSizePx = config.textSize.sp.toPx()
    // 页眉/页脚 tip 高度（隐藏时为 0）：排版视口预留，正文不钻进 tip 区。
    // 对照 app 端 contentTextView 被 header/footer 占位挤小后的实际尺寸。
    val headerTip = if (tipConfig.headerMode == 2) 0
    else tipRowHeightPx(density, config.headerPaddingTop, config.headerPaddingBottom)
    val footerTip = if (tipConfig.footerMode == 1) 0
    else tipRowHeightPx(density, config.footerPaddingTop, config.footerPaddingBottom)
    ReadBookViewModelShared.LayoutConfig(
        viewWidth = size.width,
        viewHeight = size.height - footerTip,
        paddingLeft = config.paddingLeft.dp.roundToPx(),
        paddingTop = headerTip + config.paddingTop.dp.roundToPx(),
        paddingRight = config.paddingRight.dp.roundToPx(),
        paddingBottom = config.paddingBottom.dp.roundToPx(),
        textSizePx = textSizePx,
        titleSizePx = (config.textSize + config.titleSize).sp.toPx(),
        // 原版 Paint.letterSpacing 是字号倍数，排版面按 px 消费
        letterSpacingPx = config.letterSpacing * textSizePx,
        lineSpacingExtra = config.lineSpacingExtra / 10f,
        paragraphSpacing = config.paragraphSpacing,
        titleTopSpacing = config.titleTopSpacing.dp.roundToPx(),
        titleBottomSpacing = config.titleBottomSpacing.dp.roundToPx(),
        // 末页底部留白 (对照 app 端 getTextChapter 末尾 20.dpToPx())
        endPadding = 20.dp.roundToPx(),
        paragraphIndent = config.paragraphIndent,
        textFullJustify = config.textFullJustify,
        textBottomJustify = config.textBottomJustify,
        useZhLayout = config.useZhLayout,
        titleMode = config.titleMode,
        // 度量侧字体与 ReaderDrawStyle 的 loadReaderFontFamily 同一路径，避免度量/绘制不同字体
        textFontPath = config.textFont,
    )
}

/**
 * HttpTTS 编辑对话框 Host (对照 app 端 HttpTtsEditDialog Fragment 壳)。
 *
 * 包装 shared [HttpTtsEditDialog] Composable, 内部构建 [HttpTtsEditViewModelShared]
 * + 表单 [EditEntity] 列表, 桥接平台能力 (帮助 / 日志 / 剪贴板写入)。
 *
 * 当前事件 [ReaderDialogEvent.HttpTtsEdit] 不携带 id, 默认新增 (id=null)。
 * 剪贴板读写统一经平台能力注入。
 */
@Composable
private fun HttpTtsEditDialogHost(
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val platform = PlatformCapabilityProviders.get()
    // 预解析表单 label, 避免 @Composable 在 LaunchedEffect 中误用
    val nameLabel = stringResource(Res.string.name)
    val concurrentRateLabel = stringResource(Res.string.concurrent_rate)
    val loginUrlLabel = stringResource(Res.string.login_url)
    val loginUiLabel = stringResource(Res.string.login_ui)
    val loginCheckJsLabel = stringResource(Res.string.login_check_js)
    val headerLabel = stringResource(Res.string.source_http_header)

    // HttpTTS 编辑 VM 共享核心: scope + 平台剪贴板提供者 + TTS 引擎变更通知
    val viewModel = remember {
        HttpTtsEditViewModelShared(
            scope = scope,
            clipTextProvider = { platform.getClipboardText() },
            onTtsChanged = { /* TTS 引擎刷新由平台端自行处理, 此处空实现避免循环 */ },
        )
    }
    var editEntities by remember { mutableStateOf<List<EditEntity>>(emptyList()) }
    var showLog by remember { mutableStateOf(false) }

    // 加载初始 HttpTTS (id=null 新建, 回调空 HttpTTS 表单)
    LaunchedEffect(Unit) {
        viewModel.initData(id = null) { httpTTS ->
            editEntities = buildHttpTtsEditEntities(
                httpTTS, nameLabel, concurrentRateLabel,
                loginUrlLabel, loginUiLabel, loginCheckJsLabel, headerLabel,
            )
        }
    }

    HttpTtsEditDialog(
        editEntities = editEntities,
        onBack = onDismiss,
        onSave = {
            val httpTTS = collectHttpTtsFromEntities(editEntities, viewModel.id)
            viewModel.save(httpTTS) {
                Toasters.get().toast("保存成功")
                onDismiss()
            }
        },
        onLogin = {
            val httpTts = collectHttpTtsFromEntities(editEntities, viewModel.id)
            if (httpTts.hasLogin()) {
                viewModel.save(httpTts) { httpTts.showLoginDialog() }
            } else {
                Toasters.get().toast("没有登陆界面")
            }
        },
        onShowLoginHeader = {
            val httpTts = collectHttpTtsFromEntities(editEntities, viewModel.id)
            val header = httpTts.getLoginHeader()
            if (header.isNullOrBlank()) Toasters.get().toast("无登录请求头")
            else Toasters.get().toast(header)
        },
        onDeleteLoginHeader = {
            collectHttpTtsFromEntities(editEntities, viewModel.id).removeLoginHeader()
            Toasters.get().toast("已删除")
        },
        onCopySource = {
            val httpTts = collectHttpTtsFromEntities(editEntities, viewModel.id)
            platform.copyToClipboard(KS_JSON.encodeToString(httpTts))
        },
        onPasteSource = {
            // 剪贴板读取为 KMP 限制, importFromClip 内部会 toast "剪贴板为空"
            viewModel.importFromClip { imported ->
                editEntities = buildHttpTtsEditEntities(
                    imported, nameLabel, concurrentRateLabel,
                    loginUrlLabel, loginUiLabel, loginCheckJsLabel, headerLabel,
                )
            }
        },
        onShowLog = { showLog = true },
        onShowHelp = { platform.showMdFile("帮助", "httpTTSHelp") },
        onDismiss = onDismiss,
    )

    // 日志对话框 (嵌套 Overlay, 对照 app 端 showDialogFragment<AppLogDialog>)
    if (showLog) {
        AppLogDialog(onDismiss = { showLog = false })
    }
}

/**
 * 朗读引擎选择对话框 Host (对照 app 端 SpeakEngineDialog Fragment 壳)。
 *
 * 包装 shared [SpeakEngineDialog] Composable, 内部从 [AppDbProviders] 加载 HttpTTS
 * 引擎列表, 选中后写入 [AppConfigProviders] 的 ttsEngine 字段。
 *
 * @param onEditEngine 引擎编辑入口 (新增/编辑), 由调用方决定走平台 Fragment 还是 Overlay
 */
@Composable
private fun SpeakEngineDialogHost(
    onDismiss: () -> Unit,
    onEditEngine: (HttpTTS?) -> Unit,
) {
    val appDb = remember { AppDbProviders.get() }
    val appConfig = remember { AppConfigProviders.get() }
    val scope = rememberCoroutineScope()

    // HttpTTS 引擎列表 (对照 app 端 SpeakEngineDialog.LaunchedEffect { flowAll().collect })
    var engines by remember { mutableStateOf(emptyList<HttpTTS>()) }
    LaunchedEffect(Unit) {
        appDb.httpTTSDao.flowAll()
            .catch { /* 静默, 与 ReadAloudConfigRoute 一致 */ }
            .flowOn(IoDispatcher)
            .conflate()
            .collect { engines = it }
    }

    // 当前选中引擎 (null=系统默认)
    var selectedEngineUrl by remember {
        mutableStateOf(appConfig.ttsEngine.ifBlank { null })
    }

    SpeakEngineDialog(
        engines = engines,
        selectedEngineUrl = selectedEngineUrl,
        onSelectEngine = { url ->
            selectedEngineUrl = url
            appConfig.setTtsEngine(url)
        },
        onEditEngines = onEditEngine,
        onDeleteEngine = { httpTTS ->
            scope.launch(IoDispatcher) {
                appDb.httpTTSDao.delete(httpTTS)
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * 翻页键配置对话框 Host (对照 app 端 PageKeyDialog Fragment 壳)。
 *
 * 包装 shared [PageKeyDialog] Composable, 从 [LocalPreferenceStoreProvider] 读写
 * prevKeys / nextKeys 偏好, 与 OtherConfigRoute 内嵌 PageKeyDialog 行为一致。
 */
@Composable
private fun PageKeyDialogHost(
    onDismiss: () -> Unit,
) {
    val pref = LocalPreferenceStoreProvider.current
    // 从偏好读取 prev/next 字符串, 反序列化为 Map<Int, String>
    val keyMappings = remember {
        val prev = pref.getString(PreferKey.prevKeys) ?: ""
        val next = pref.getString(PreferKey.nextKeys) ?: ""
        parsePageKeyMappings(prev, next)
    }
    PageKeyDialog(
        keyMappings = keyMappings,
        onConfirm = { mappings ->
            val (prev, next) = splitPageKeyMappings(mappings)
            pref.putString(PreferKey.prevKeys, prev)
            pref.putString(PreferKey.nextKeys, next)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * 从 [HttpTTS] 构建 [EditEntity] 表单字段列表 (对照 app 端 HttpTtsEditDialog.initView)。
 *
 * 字段顺序 / ViewType / codePatterns 与 app 端 1:1 对齐, label 由调用方预解析传入
 * (避免 @Composable 在非 Composable 上下文中误用)。
 */
private fun buildHttpTtsEditEntities(
    httpTTS: HttpTTS,
    nameLabel: String,
    concurrentRateLabel: String,
    loginUrlLabel: String,
    loginUiLabel: String,
    loginCheckJsLabel: String,
    headerLabel: String,
): List<EditEntity> = listOf(
    EditEntity("name", httpTTS.name, nameLabel),
    EditEntity(
        key = "url",
        value = httpTTS.url,
        hint = "url",
        viewType = EditEntity.ViewType.code,
        codePatterns = EditEntity.CodePattern.all,
    ),
    EditEntity("contentType", httpTTS.contentType, "Content-Type"),
    EditEntity("concurrentRate", httpTTS.concurrentRate, concurrentRateLabel),
    EditEntity(
        key = "loginUrl",
        value = httpTTS.loginUrl,
        hint = loginUrlLabel,
        viewType = EditEntity.ViewType.code,
        codePatterns = EditEntity.CodePattern.all,
    ),
    EditEntity(
        key = "loginUi",
        value = httpTTS.loginUi,
        hint = loginUiLabel,
        viewType = EditEntity.ViewType.code,
        codePatterns = EditEntity.CodePattern.json,
    ),
    EditEntity(
        key = "loginCheckJs",
        value = httpTTS.loginCheckJs,
        hint = loginCheckJsLabel,
        viewType = EditEntity.ViewType.code,
        codePatterns = EditEntity.CodePattern.js,
    ),
    EditEntity(
        key = "header",
        value = httpTTS.header,
        hint = headerLabel,
        viewType = EditEntity.ViewType.code,
        codePatterns = EditEntity.CodePattern.all,
    ),
)

/**
 * 从表单字段收集回 [HttpTTS] 实例 (对照 app 端 HttpTtsEditDialog.dataFromView)。
 */
private fun collectHttpTtsFromEntities(
    entities: List<EditEntity>,
    id: Long?,
): HttpTTS = HttpTTS(id = id ?: systemCurrentTimeMillis()).also { httpTTS ->
    entities.forEach {
        when (it.key) {
            "name" -> httpTTS.name = it.text.orEmpty()
            "url" -> httpTTS.url = it.text.orEmpty()
            "contentType" -> httpTTS.contentType = it.text
            "concurrentRate" -> httpTTS.concurrentRate = it.text
            "loginUrl" -> httpTTS.loginUrl = it.text
            "loginUi" -> httpTTS.loginUi = it.text
            "loginCheckJs" -> httpTTS.loginCheckJs = it.text
            "header" -> httpTTS.header = it.text
        }
    }
}

/**
 * 解析 prevKeys/nextKeys 字符串为 PageKeyDialog 需要的 Map<Int, String>
 * (与 PageKeyDialog 内部 buildKeyMappings 反向逻辑对齐, 同 OtherConfigRoute)。
 */
private fun parsePageKeyMappings(prevKeys: String, nextKeys: String): Map<Int, String> {
    val map = mutableMapOf<Int, String>()
    prevKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "prev_page" }
    nextKeys.split(",").mapNotNull { it.trim().toIntOrNull() }.forEach { map[it] = "next_page" }
    return map
}

/**
 * 将 Map<Int, String> 反序列化为 prevKeys/nextKeys 字符串写回 prefs (同 OtherConfigRoute)。
 */
private fun splitPageKeyMappings(mappings: Map<Int, String>): Pair<String, String> {
    val prev = mappings.filter { it.value == "prev_page" }.keys.joinToString(",") { it.toString() }
    val next = mappings.filter { it.value == "next_page" }.keys.joinToString(",") { it.toString() }
    return prev to next
}
