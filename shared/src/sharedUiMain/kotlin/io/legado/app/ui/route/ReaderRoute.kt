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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.model.ReadBookPlatforms
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
import io.legado.app.ui.book.read.ReaderScreenModelRegistry
import io.legado.app.ui.book.read.ReaderUiActions
import io.legado.app.ui.book.read.ReaderUiState
import io.legado.app.ui.book.read.SimulatedReadingDialog
import io.legado.app.ui.book.read.config.AutoReadActions
import io.legado.app.ui.book.read.config.AutoReadController
import io.legado.app.ui.book.read.config.AutoReadPanelDialogHost
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.book.read.config.HttpTtsEditDialog
import io.legado.app.ui.book.read.config.HttpTtsEditViewModelShared
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.book.read.page.TITLE_SIZE_EXTRA_SP
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.turnPage
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.compose.platform.AppShortcutHandler
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.PageTurnThrottle
import io.legado.app.ui.compose.platform.VolumeKeyPageTurnHandler
import io.legado.app.ui.compose.platform.performBack
import io.legado.app.ui.compose.platform.readerDirectionalKeys
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add_to_bookshelf
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.chapter_pay
import legado.shared.generated.resources.check_add_bookshelf
import legado.shared.generated.resources.cloud_progress_exceeds_current
import legado.shared.generated.resources.concurrent_rate
import legado.shared.generated.resources.draw
import legado.shared.generated.resources.login_check_js
import legado.shared.generated.resources.login_ui
import legado.shared.generated.resources.login_url
import legado.shared.generated.resources.name
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.read_aloud_pause
import legado.shared.generated.resources.restore_last_book_process
import legado.shared.generated.resources.source_http_header
import legado.shared.generated.resources.sync_book_progress_t
import legado.shared.generated.resources.yes
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
            // 搜索菜单"结果"按钮 → 打开全文搜索页 (对照原版 openSearchActivity:
            // 携带当前书/索引/结果列表, 免重搜; 列表仅首条 query 匹配时携带)
            onOpenSearch = { model, book, word ->
                // 带 resultKey 接收选中结果回写 (对齐 iOS/OHOS provider, 2026-08-06 修复漏接)
                navigator.push(
                    AppRoute.SearchContent(
                        index = model.searchResultIndex,
                        word = word ?: model.searchContentQuery,
                        initialResults = model.searchResultList?.takeIf { list ->
                            list.firstOrNull()?.query == model.searchContentQuery
                        },
                        book = book.toRouteRef(),
                    ),
                    resultKey = RouteResults.SEARCH_CONTENT,
                )
            },
        )
    }

    DisposableEffect(screenModel, provider) {
        provider.onEnter(screenModel)
        // 注册为当前阅读屏 (鸿蒙 napi 回调/非 Compose 宿主取 dialogEvent/menuState 用)
        ReaderScreenModelRegistry.attach(screenModel)
        onDispose {
            ReaderScreenModelRegistry.detach(screenModel)
            provider.onExit(screenModel)
        }
    }

    // region 排版参数注入（对照原版 ContentTextView.onSizeChanged → ChapterProvider.upViewSize
    // + TextStyleProvider.upStyle）
    val density = LocalDensity.current
    val readBookConfig = LocalReadConfigProviders.current.readBookConfig
    // 排版视口单一来源：正文区（PageViewComposable 内 weight(1f) 布局占位子节点）实测尺寸，
    // 经 onTextAreaMeasured 上报。该正文区已被系统栏 inset + 页眉/页脚约束，实测值即原版
    // contentTextView.onSizeChanged 拿到的尺寸——同一布局系统同帧测量，与渲染严格同源，
    // 不再由「窗口高 − insets − 页眉 − 页脚」拼差值（各来源到达帧不同会造成配置多跳重排）。
    // null = 首帧尚未测量：保持当前排版不更新（布局依赖顺序——测量回调后重排收敛）。
    var textAreaSize by remember { mutableStateOf<IntSize?>(null) }
    // 配置变更即时触发：读回新配置后由 VM 比对，参数没变不重排
    var configVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadBookEvents.configChange.collect { changes ->
            if (changes.any { it in relayoutChanges }) configVersion++
        }
    }
    // 正文区实测尺寸 / 阅读配置变化 → 以新视口重排（VM 内部对高度-only 变化去抖，
    // 宽度变化即时，见 ReadBookViewModelShared.updateLayoutConfig）
    LaunchedEffect(
        screenModel, textAreaSize, configVersion, density, readBookConfig,
    ) {
        val textArea = textAreaSize ?: return@LaunchedEffect
        screenModel.viewModel.updateLayoutConfig(
            buildLayoutConfig(textArea, density, readBookConfig)
        )
    }
    // endregion

    // 初始化书籍数据（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）。
    // 推迟到正文区实测后再装载：首排必用实测视口，杜绝 LayoutConfig.DEFAULT(720x1080)
    // 先行排版 → 实测配置到达后再整章重排的「二次排版」（对照原版 initData 用
    // Looper.myQueue().addIdleHandler 把 loadContent 推迟到视图测量完成后执行）。
    // book 在路由组合期内固定（remember(route)），用一次性标志保证只 init 一次，
    // 后续 textAreaSize 变化（窗口 resize）不重复 initBook。
    var bookInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(textAreaSize) {
        if (textAreaSize == null || bookInitialized) return@LaunchedEffect
        bookInitialized = true
        screenModel.initBook(book, route.chapterIndex, route.chapterPos)
    }

    val state = remember(screenModel) {
        ReaderUiState(
            viewModel = screenModel.viewModel,
            menuState = screenModel.menuState,
            searchMenuState = screenModel.searchMenuState,
            selection = screenModel.selection,
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

            override fun onImageLongPress(src: String, x: Float, y: Float) {
                provider.onImageLongPress(screenModel, src, x, y)
            }

            override fun onTextSelection(text: String, anchorX: Float, anchorY: Float) {
                provider.onTextSelected(screenModel, text, anchorX, anchorY)
            }

            // 点按取消选择等手势分支：同步关平台浮动菜单（对照原版 ACTION_DOWN →
            // textActionMenu.dismiss 同步语义；事件链异步兜底仍保留，见下方
            // selectionDismissed 收集）
            override fun onDismissTextActionMenu() {
                provider.dismissTextActionMenu(screenModel)
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
                // 统一返回链: 先关顶层覆盖物 (阅读菜单/对话框/Popup) 再出栈,
                // 对齐原版 BACK 语义 (桌面端 Backspace/兜底 ESC 均经此链)
                performBack(navigator)
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
    // 栈顶判定改为响应式: collectAsState 订阅 backStack, 栈变化驱动重组刷新下方消费点;
    // 旧写法 lambda 读 .value 不订阅 StateFlow, AppBackHandler 的 enabled 会停在过期值
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    // 未入架书退出确认（对照原版 BaseReadActivity.finish：弹"加入书架"确认框，
    // 确定=入架后留在阅读界面不退出；取消=删除临时书后退出；关闭提示=留在阅读界面，
    // 书保持临时态，下次返回再弹框；退出后才由 onDestroy 路径兜底删除临时书）。
    // 返回键统一走 performBack → dispatchBackKey 分发到本拦截器，覆盖 ESC/系统返回/菜单返回按钮。
    //
    // 返回键拦截链 (对照原版 ReadBookActivity.onBackPressedDispatcher 五段链：
    // ③全文搜索段 → ①恢复跳转前进度 → ②朗读暂停 → ④自动翻页停止 → finish(未入架弹框/出栈)。
    // 用单个 handler + body 内按原版顺序逐段判定：lastBookProgress /
    // confirmRestoreProcess / 朗读态(平台静态字段) 均非响应式，放 enabled 会在组合期求值停在
    // 过期值；body 在按键时刻执行必然读到最新值，故 enabled 只做响应式栈顶判定。
    // 弹框/toast 文案在组合期预取 (onBack 非 @Composable 不能 stringResource)。
    val readAloudPauseText = stringResource(Res.string.read_aloud_pause)
    val currentBook by screenModel.viewModel.book.collectAsState()
    AppBackHandler(enabled = isTopEntry) {
        // ③ 全文搜索段 (原版五段链第一段: 搜索态 → 退出搜索态 + 恢复进入搜索前的进度)
        if (screenModel.isShowingSearchResult) {
            screenModel.exitSearchMenu()
            screenModel.restoreLastBookProcess()
            return@AppBackHandler
        }
        // ① 恢复跳转前进度 (原版: lastBookProgress != null && confirmRestoreProcess != false)
        if (screenModel.lastBookProgress != null && screenModel.confirmRestoreProcess != false) {
            screenModel.restoreLastBookProcess()
            return@AppBackHandler
        }
        // ② 朗读暂停 (原版: BaseReadAloudService.isPlay() = isRun && !pause, 暂停态不重复触发)
        val readBookPlatform = ReadBookPlatforms.get()
        if (readBookPlatform.isReadAloudRun && !readBookPlatform.isReadAloudPause) {
            readBookPlatform.pauseReadAloud()
            Toasters.get().toast(readAloudPauseText)
            return@AppBackHandler
        }
        // ④ 自动翻页停止 (原版: isAutoPage → autoPageStop)
        if (screenModel.menuState.autoPage) {
            provider.autoPageStop(screenModel)
            return@AppBackHandler
        }
        // 未入架书退出确认 (原版 finish 的"加入书架"确认框，语义见上方注释)
        if (currentBook?.isNotShelf == true && AppConfigProviders.get().showAddToShelfAlert) {
            screenModel.postDialogEvent(ReaderDialogEvent.AddToShelfConfirm)
        } else {
            navigator.pop()
        }
    }
    // 方向键 (用户拍板 2026-08): 阅读键盘只保留方向键, PageUp/PageDown/Space 不再绑定;
    // 键位随翻页方向自适应——左右翻页模式 ←/→=翻页、↑/↓=章节切换;
    // 上下滚动模式 ↑/↓=翻页、←/→=章节切换 (原版 ReadBookKeyHandler 的 prevKeys/nextKeys
    // 含 ↑↓ 翻页, 用户拍板改为章节切换)。
    // 翻页去抖已由 AppShortcuts 分发的按住过滤承担 (系统 repeat 只触发一次、快速连按每次生效,
    // 见 dispatchShortcut 的按住态判定); 动画中按键由 delegate abortAnim 打断重翻
    // (对照原版 keyTurnPage → nextPageByAnim → abortAnim 语义)。章节切换保留独立去抖,
    // 防快速连按误触连切章。
    val chapterTurnThrottle = remember { PageTurnThrottle() }
    AppShortcutHandler(
        shortcuts = readerDirectionalKeys,
        enabled = { isTopEntry && !screenModel.menuState.isVisible },
    ) { shortcut ->
        // 分发时读当前翻页方向 (scroll=上下滚动, 其余=左右翻页), 不依赖重组
        val isScroll = screenModel.viewModel.isScrollPageAnim
        when (shortcut.key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                val prev = shortcut.key == Key.DirectionLeft
                if (isScroll) {
                    // 上下滚动模式: ←/→ = 章节切换 (用户拍板)
                    // 方向键切章统一落到新章第一页: toLast=false 章首 + resetOffset=true
                    // 归零滚动偏移 (滚动模式下滑窗预载命中时默认保留偏移, 视觉上会延续
                    // 上一章滚动位置; 菜单/自动切章等入口保持原语义不动)
                    chapterTurnThrottle.tryTurn {
                        if (prev) {
                            screenModel.viewModel.moveToPrevChapter(
                                toLast = false, resetOffset = true
                            )
                        } else {
                            screenModel.viewModel.moveToNextChapter(resetOffset = true)
                        }
                    }
                } else {
                    // turnPage 内部优先走 pageDelegate.keyTurnPage(带动画), 无委托时直接位移页码
                    screenModel.viewModel.turnPage(
                        if (prev) PageDirectionShared.PREV else PageDirectionShared.NEXT
                    )
                }
            }

            Key.DirectionUp, Key.DirectionDown -> {
                val up = shortcut.key == Key.DirectionUp
                if (isScroll) {
                    // 滚动模式: ↑/↓ = 小步滚动 (用户拍板: 一次整页难受),
                    // 1/3 视口 + 动画 (scrollByAnimated: 页内动画, 越界同步折算跨页)
                    val pageH =
                        (screenModel.viewModel.curTextPage.value?.visibleHeight ?: 0).toFloat()
                    if (pageH > 0f) {
                        (screenModel.viewModel.pageDelegate as? ScrollPageDelegateCompose)
                            ?.scrollByAnimated(if (up) pageH / 3f else -pageH / 3f)
                    } else {
                        screenModel.viewModel.turnPage(
                            if (up) PageDirectionShared.PREV else PageDirectionShared.NEXT
                        )
                    }
                } else {
                    // 左右翻页模式: ↑/↓ = 章节切换 (用户拍板)
                    // 同滚动模式方向键切章: toLast=false + resetOffset=true, 新章从第一页开始
                    chapterTurnThrottle.tryTurn {
                        if (up) {
                            screenModel.viewModel.moveToPrevChapter(
                                toLast = false, resetOffset = true
                            )
                        } else {
                            screenModel.viewModel.moveToNextChapter(resetOffset = true)
                        }
                    }
                }
            }
        }
    }
    // 音量键翻页 (对照原版 ReadBookKeyHandler VOLUME_UP/DOWN 分支; 2026-08 用户拍板:
    // 恒生效不提供开关, 已移除 AppConfig.volumeKeyPage 配置读取, 始终拦截音量键翻页)
    // 长按策略 (2026-08 用户拍板): 与漫画页一致——TRIGGER 连翻 + 200ms 节流,
    // 接线收敛在共享 VolumeKeyPageTurnHandler (单击翻页 / 长按连翻节流 / 抬起消费)
    VolumeKeyPageTurnHandler(
        enabled = { isTopEntry && !screenModel.menuState.isVisible },
    ) { volumeUp ->
        screenModel.viewModel.turnPage(
            if (volumeUp) PageDirectionShared.PREV else PageDirectionShared.NEXT
        )
    }
    // endregion

    // Ctrl+滚轮调字号 (用户拍板: 替代 Ctrl+=/-= 快捷键, 更直观, 每格 ±2, 范围 5..50);
    // 非 Ctrl 滚轮不消费 (滚轮翻页已彻底禁用 2026-08 用户拍板, 与 mouseWheelPage 设置项一并移除),
    // 交还原链路; 菜单可见时也不消费滚轮, 让位菜单内列表滚动 (对照原版 onMouseWheel 的
    // menuLayoutIsVisible 守卫)。
    ReaderScreen(
        state = state,
        actions = actions,
        focusRequester = keyFocusRequester,
        onTextAreaMeasured = { textAreaSize = it },
        modifier = Modifier
            .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val delta = change.scrollDelta.y
                    if (delta == 0f) continue
                    if (event.keyboardModifiers.isCtrlPressed) {
                        val deltaSize = if (delta > 0) 2 else -2
                        val newSize = (readBookConfig.textSize + deltaSize)
                            .coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
                        if (newSize != readBookConfig.textSize) {
                            readBookConfig.textSize = newSize
                            readBookConfig.save()
                            // 与 ReadStyleScreen 字号 seekBar 一致的重排事件
                            ReadBookEvents.postConfig(
                                ReadConfigChange.CHAPTER_STYLE,
                                ReadConfigChange.LOAD_CONTENT,
                            )
                        }
                        change.consume()
                    }
                }
            }
        },
    )

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
        // 页内选区已消失 → 平台收起浮动文本操作菜单 (对照旧 onCancelSelect → textActionMenu.dismiss;
        // 事件源: ReadViewComposable 观察 selection.isActive 下降沿, 见 PageSelectionState.cancel)
        launch {
            ReadBookEvents.selectionDismissed.collect {
                provider.onTextSelectionDismissed(screenModel)
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
                    // 对照原版 ReadBookActivity.sourceEditActivity: 仅 resultCode==RESULT_OK
                    // (真正保存) 才回调; BookSourceEditRoute 退出未保存时 pop 不带 payload,
                    // 此处忽略, 避免误触发书源引用刷新/菜单重绘
                    val source =
                        (result.payload as? RouteResultPayload.BookSourceEdit)?.source
                            ?: return@collect
                    // 直接采用回传的已保存对象 (不再按 origin 查库)
                    screenModel.viewModel.upBookSource(source)
                    screenModel.menuState.refresh()
                }

                RouteResults.REPLACE_EDIT -> screenModel.viewModel.replaceRuleChanged()

                RouteResults.BOOK_INFO -> when (result.payload) {
                    is RouteResultPayload.Deleted -> navigator.pop()
                    is RouteResultPayload.Ok -> navigator.pop(RouteResultPayload.Deleted)
                    // 书籍详情正常返回 (未删书): 补载缺失章节 (对照原版
                    // bookInfoActivity 回调 else 分支 → ReadBook.loadOrUpContent)
                    else -> screenModel.viewModel.loadOrUpContent()
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
                    // 对照原版 searchContentActivity 回调 (ReadBookActivity:170-186)：
                    // 从选中结果取 query 回写缓存, 灌结果列表, 进搜索态 (置标志 + 存进度
                    // 快照 + skipToSearch + 弹搜索菜单)
                    val searchResult = payload.searchResults.getOrNull(payload.searchResultIndex)
                        ?: return@collect
                    screenModel.searchContentQuery = searchResult.query
                    screenModel.searchResultList = payload.searchResults
                    screenModel.searchResultIndex = payload.searchResultIndex
                    screenModel.onSearchContentResult(searchResult)
                }
            }
        }
    }
    // endregion

    // region 对话框渲染 (书签/正文编辑/日志, 由 AndroidReaderMenuState 触发)
    val dialogEvent by screenModel.dialogEvent.collectAsState()
    when (val event = dialogEvent) {
        is ReaderDialogEvent.RestoreProcessConfirm -> {
            // 返回键恢复跳转前进度确认 (对照原版 restoreLastBookProcess 的 alert：
            // 是=恢复跳转前进度并以后总是恢复；否/点外部关闭=放弃快照并以后不再询问)
            AppAlertDialog(
                onDismissRequest = {
                    screenModel.clearLastBookProgress()
                    screenModel.confirmRestoreProcess = false
                    screenModel.clearDialogEvent()
                },
                title = stringResource(Res.string.draw),
                message = stringResource(Res.string.restore_last_book_process),
                okButton = AlertButton(stringResource(Res.string.yes)) {
                    screenModel.confirmRestoreProcess = true
                    screenModel.restoreLastBookProgress()
                    screenModel.clearDialogEvent()
                },
                cancelButton = AlertButton(stringResource(Res.string.no)) {
                    screenModel.clearLastBookProgress()
                    screenModel.confirmRestoreProcess = false
                    screenModel.clearDialogEvent()
                },
            )
        }

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
                            // 2026-08-06: 打开方式与 onChapterViewClick 一致, 走平台 openWebView
                            // (桌面端=独立窗口, 移动端=原 WebViewRoute 路由)
                            PlatformCapabilityProviders.getOrNull()?.openWebView(
                                url, book.origin, book.originName
                            )
                        }
                    },
                    cancelButton = AlertButton(stringResource(Res.string.cancel)) {
                        screenModel.clearDialogEvent()
                    },
                )
            }
        }

        is ReaderDialogEvent.AddToShelfConfirm -> {
            // 未入架书退出确认 (对照原版 BaseReadActivity.finish 的 alert：
            // 确定=入架后不退出留在阅读界面；取消=删除后退出；点外部关闭留在阅读页)
            val book = screenModel.currentBook
            if (book == null) {
                screenModel.clearDialogEvent()
            } else {
                AppAlertDialog(
                    onDismissRequest = { screenModel.clearDialogEvent() },
                    title = stringResource(Res.string.add_to_bookshelf),
                    message = stringResource(Res.string.check_add_bookshelf, book.name),
                    okButton = AlertButton(stringResource(Res.string.ok)) {
                        // 入架成功后才关弹框：toggleBookshelfCore 原地清掉 book.type 的 notShelf 位，
                        // 关弹框触发重组使 AppBackHandler.enabled 重求值为 false，下次返回直接退出不再弹框
                        screenModel.viewModel.addToBookshelf {
                            screenModel.clearDialogEvent()
                        }
                    },
                    cancelButton = AlertButton(stringResource(Res.string.cancel)) {
                        screenModel.clearDialogEvent()
                        screenModel.viewModel.removeFromBookshelf { navigator.pop() }
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
                // 正文由 contentLoader 异步加载当前章节全文 (对照原版 ContentEditViewModel.initContent);
                // 传空占位, 加载中由对话框内转圈覆盖 —— 不再用当前页文本 (curTextPage) 当整章正文
                content = "",
                contentLoader = { reset -> screenModel.loadChapterFullText(reset) },
                onSubmit = { edited ->
                    val book = screenModel.viewModel.book.value
                    if (book != null && chapter != null) {
                        scope.launch {
                            // 缓存写入是同步文件 IO, 切 IO 线程 (对照原版 save() 在 Coroutine.async 中执行)
                            runCatching {
                                withContext(IoDispatcher) {
                                    BookStorageProviders.get().saveText(book, chapter, edited)
                                }

                            }
                            // 保存后从缓存重载当前章 (对照原版 save() → ReadBook.loadContent:
                            // 不清缓存, 阅读器直接显示编辑后的正文; 不能用 refreshCurrentChapter,
                            // 它会 delContent 删掉刚保存的内容再重新下载)。keepScrollOffset 保留滚动偏移
                            screenModel.viewModel.loadChapter(
                                screenModel.viewModel.durChapterIndex.value,
                                keepScrollOffset = true,
                            )
                        }
                    }
                    screenModel.clearDialogEvent()
                },
                onDismiss = { screenModel.clearDialogEvent() },
                // 重置: 正文重拉完成后刷新阅读器 (对照原版 menu_reset 回调里 ReadBook.loadContent:
                // 此时新缓存已就绪, loadChapter 从缓存装载, 不重复下载; keepScrollOffset 保留滚动偏移)
                onReset = {
                    screenModel.viewModel.loadChapter(
                        screenModel.viewModel.durChapterIndex.value,
                        keepScrollOffset = true,
                    )
                },
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
                        navigator.push(
                            AppRoute.ReplaceEdit(),
                            resultKey = RouteResults.REPLACE_EDIT
                        )
                        screenModel.clearDialogEvent()
                    },
                    onItemClick = { rule ->
                        if (rule === replacesModel.chineseConvert) {
                            showChineseConverter = true
                        } else {
                            navigator.push(
                                AppRoute.ReplaceEdit(rule.id),
                                resultKey = RouteResults.REPLACE_EDIT
                            )
                            screenModel.clearDialogEvent()
                        }
                    },
                    onManageAll = {
                        navigator.push(
                            AppRoute.ReplaceRule,
                            resultKey = RouteResults.REPLACE_EDIT
                        )
                        screenModel.clearDialogEvent()
                    },
                    onDismiss = { screenModel.clearDialogEvent() },
                )
            }
            // 繁简转换选择器 (对照 EffectiveReplacesDialog)
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

        // 自动翻页控制面板 (对照原版 自动翻页运行时点屏幕 → AutoReadDialog: 速度滑条 + 目录/主菜单/停止/设置)
        is ReaderDialogEvent.AutoRead -> {
            // 自动翻页自行结束 (如翻到全书末尾, app 端 pager.onEnd → stopAutoPage) 时收起面板
            // (对照原版 autoPageStop → dismissDialogFragment(AutoReadDialog))
            val autoPageActive = screenModel.menuState.autoPage
            LaunchedEffect(autoPageActive) {
                if (!autoPageActive) screenModel.clearDialogEvent()
            }
            AutoReadPanelDialogHost(
                controller = object : AutoReadController {
                    // 对照原版 ReadBookConfig.autoReadSpeed (共享配置, 各端同一存储)
                    override var autoReadSpeed: Int
                        get() = ReadBookConfigProviders.get().autoReadSpeed
                        set(value) {
                            ReadBookConfigProviders.get().autoReadSpeed = value.coerceAtLeast(1)
                        }
                },
                actions = object : AutoReadActions {
                    // 目录按钮 → TocDialog (对照原版 callBack.openChapterList)
                    override fun openChapterList() {
                        screenModel.clearDialogEvent()
                        screenModel.postDialogEvent(ReaderDialogEvent.Toc)
                    }

                    // 主菜单按钮 → 强制弹常规菜单 (对照原版 callBack.showMenuBar, 不走 autoPage 重定向)
                    override fun showMenuBar() {
                        screenModel.clearDialogEvent()
                        screenModel.menuController.showMenu()
                    }

                    // 停止自动翻页 (对照原版 callBack.autoPageStop)
                    override fun autoPageStop() {
                        screenModel.clearDialogEvent()
                        provider.autoPageStop(screenModel)
                    }

                    // 设置按钮 → 翻页动画配置 (对照原版 showPageAnimConfig)
                    override fun showPageAnimConfig() {
                        provider.showPageAnimConfig(screenModel)
                    }

                    // 滑条抬手 → 同步 TTS 语速 (对照原版 upTtsSpeechRate)
                    override fun upTtsSpeechRate() {
                        provider.upTtsSpeechRate(screenModel)
                    }
                },
                onDismiss = { screenModel.clearDialogEvent() },
            )
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
                    navigator = navigator,
                    onOpenChapter = { index, pos ->
                        screenModel.clearDialogEvent()
                        screenModel.openChapter(index, pos)
                    },
                    onTocRegexChanged = { tocBook, _ ->
                        // 对照原版 ReadBookActivity.onTocRegexDialogResult: 规则已写入 book.tocUrl,
                        // 阅读页按新规则重载目录 (本地 txt 重新解析)
                        screenModel.viewModel.loadChapterList(tocBook)
                    },
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

        // 模拟追读配置 (对照原版 menu_simulated_reading → showSimulatedReading)
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

/** 字号可调范围，对照 ReadStyleScreen 字号 seekBar（内部 0..45，展示值 +5）。 */
private const val MIN_TEXT_SIZE = 5
private const val MAX_TEXT_SIZE = 50

/**
 * 触发重排的配置事件：原版这些分支都落到 `ChapterProvider.upStyle/upLayout` +
 * `ReadBook.loadContent(resetPageOffset = false)`。
 * SYSTEM_UI（隐藏状态栏/导航栏切换）：页眉按 headerMode=0 联动显隐、排版视口随
 * 系统栏 inset 与页眉高度变化，必须同步重算（原版由 [0,2] 双事件中的 STYLE 分支
 * upStyle 驱动占位 View 显隐后布局变化触发；本地事件已拆分为 SYSTEM_UI 单独语义，
 * 故在此补上，避免沉浸切换后排版视口仍按旧页眉高度预留）。
 */
private val relayoutChanges = setOf(
    ReadConfigChange.SYSTEM_UI,
    ReadConfigChange.STYLE,
    ReadConfigChange.CHAPTER_STYLE,
    ReadConfigChange.CHAPTER_LAYOUT,
    ReadConfigChange.LOAD_CONTENT,
)

/**
 * 窗口视口 + [ReadBookConfigShared] → 排版参数，逐字段对照原版
 * `ChapterProvider.upLayout`（padding dp→px）与 `TextStyleProvider.upStyle`（字号 / 间距）。
 *
 * 正文视口单一来源：正文区布局占位子节点（PageViewComposable weight(1f) Box）实测尺寸，
 * 该子节点已被系统栏 inset + 页眉/页脚约束（对照原版 contentTextView 被 vwStatusBar +
 * llHeader + llFooter + vwNavigationBar 挤小后的实际尺寸）——与渲染同一布局系统同帧测量，
 * 不再拼差值。padding 只含正文自身内边距。
 */
private fun buildLayoutConfig(
    textArea: IntSize,
    density: Density,
    config: ReadBookConfigShared,
): ReadBookViewModelShared.LayoutConfig = with(density) {
    val textSizePx = config.textSize.sp.toPx()
    // 平板/横屏双页（对照原版 ChapterProvider.upLayout 的 doublePageHorizontal 分支）：
    // "0"=全域单页 "1"=全域双页 "2"=横向双页(宽>高, 滚动动画除外) "3"=平板/横屏双页(宽>高或平板)
    val doublePage = when (AppConfigProviders.get().doublePageHorizontal) {
        "1" -> true
        "2" -> textArea.width > textArea.height && config.pageAnim != PageAnim.scrollPageAnim
        "3" -> (textArea.width > textArea.height ||
            PlatformCapabilityProviders.get().isTablet()) &&
            config.pageAnim != PageAnim.scrollPageAnim
        else -> false
    }
    ReadBookViewModelShared.LayoutConfig(
        // 正文区实测宽高：即原版 contentTextView.onSizeChanged 的 w/h（系统栏避让与
        // 页眉/页脚扣除已由布局系统完成，排版视口与渲染严格同源）
        viewWidth = textArea.width,
        viewHeight = textArea.height,
        doublePage = doublePage,
        paddingLeft = config.paddingLeft.dp.roundToPx(),
        // 正文区顶部即页眉底边（布局占位子节点把正文约束在页眉/页脚之间），
        // paddingTop 只含正文自身内边距，不含页眉高度
        paddingTop = config.paddingTop.dp.roundToPx(),
        paddingRight = config.paddingRight.dp.roundToPx(),
        paddingBottom = config.paddingBottom.dp.roundToPx(),
        textSizePx = textSizePx,
        // 标题字号 = 正文 + titleSize + 固定"略大"增量（与绘制侧 ReaderDrawStyle.titleStyle 同口径）
        titleSizePx = (config.textSize + config.titleSize + TITLE_SIZE_EXTRA_SP).sp.toPx(),
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
            if (header.isNullOrBlank()) Toasters.get().toast("无登录头")
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
            .catch { /* 静默, 与 ReadAloudConfigDialog 一致 */ }
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
