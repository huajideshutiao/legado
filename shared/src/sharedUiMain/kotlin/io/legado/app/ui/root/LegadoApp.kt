package io.legado.app.ui.root

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.IntentData
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.about.CrashLogItem
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverPlatformProviders
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.bookshelf.toCoverBook
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.platform.handleBackKey
import io.legado.app.ui.compose.platform.performBack
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.config.BookshelfLayoutConfigDialog
import io.legado.app.ui.config.BottomNavConfigDialog
import io.legado.app.ui.config.CheckSourceConfigDialog
import io.legado.app.ui.config.DirectLinkUploadConfigDialog
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.ThemeCustomizeDialog
import io.legado.app.ui.config.ThemeListDialog
import io.legado.app.ui.widget.dialog.PhotoViewOverlayDialog
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 四端统一应用根 Composable：整合 AppNavigator + ScreenModelStore + PlatformServices + WindowPolicy。
 *
 * 零薄壳方案: 所有路由由 shared [RouteContent] 直接渲染, 平台入口只调用 [LegadoApp],
 * Overlay 栈由 [OverlayContentHost] 内部统一渲染, 平台无需注入 overlayContent。
 */
@Composable
fun LegadoApp(
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore = remember { ScreenModelStore() },
    capabilities: PlatformCapabilities = PlatformCapabilityProviders.get(),
    platformServices: PlatformServices? = PlatformServiceProviders.getOrNull(),
    initialRequest: LaunchRequest? = null,
) {
    CompositionLocalProvider(
        LocalAppNavigator provides navigator,
        LocalScreenModelStore provides screenModelStore,
        LocalPlatformCapabilities provides capabilities,
        LocalPlatformServices provides platformServices,
    ) {
        // 暴露 navigator 给非 Composable 代码 (Dialog/Fragment/Activity)
        SideEffect { AppNavigatorProviders.register(navigator) }

        val entries by navigator.backStack.collectAsState()
        val transition = remember { Animatable(1f) }
        // 方向/出栈页/动画标志在组合阶段维护: LaunchedEffect 滞后一帧, 动画参数若等
        // effect 定, 首帧会先按"无动画终态"渲染 (前进: 旧页瞬间消失露底; 返回: 目标页
        // 硬切), 正是切换闪烁的来源。组合阶段先定方向, 首帧即渲染动画起始位
        var navigatingForward by remember { mutableStateOf(true) }
        var animating by remember { mutableStateOf(false) }
        var outgoingEntry by remember { mutableStateOf<RouteEntry?>(null) }
        // 上次已消化(动画播完)的栈, 由下方动画 effect 更新, 组合阶段据此检测新导航
        val lastSettled = remember { mutableStateOf(entries) }
        if (entries.size != lastSettled.value.size) {
            navigatingForward = entries.size > lastSettled.value.size
            // 返回导航时缓存即将消失的页面，用于滑出动画
            if (!navigatingForward) {
                outgoingEntry = lastSettled.value.lastOrNull()
            }
            animating = true
        }
        // 返回导航时出栈页保留在栈尾滑出, 动画结束后由 effect 清 outgoingEntry 复位
        val displayEntries = if (!navigatingForward) {
            entries + listOfNotNull(outgoingEntry)
        } else {
            entries
        }
        val currentEntry = entries.lastOrNull()
        val currentRoute = currentEntry?.route

        // 应用当前路由对应的窗口策略。只在策略真变了才下发: 桌面端 setFullscreen 会调
        // AWT GraphicsDevice.setFullScreenWindow, 每次重组都下发等于持续折腾窗口本体
        // 阅读页系统栏跟随 hideStatusBar/hideNavigationBar 配置 (对照原版 upSystemUiVisibility),
        // 默认(关闭隐藏)时状态栏须可见
        val windowPolicy = currentRoute?.let { route ->
            if (route is AppRoute.Reader) {
                WindowPolicies.Reader.copy(systemBars = readerSystemBarsPolicy(menuVisible = false))
            } else {
                WindowPolicies.forRoute(route)
            }
        } ?: WindowPolicies.Default
        LaunchedEffect(windowPolicy) {
            runCatching { applyWindowPolicy(windowPolicy) }
                .onFailure { AppLog.put("应用窗口策略失败", it) }
        }
        // 阅读页隐藏状态栏/导航栏开关在对话框里切换后重应用系统栏策略 (原版 SharedPreference
        // 监听 → upSystemUiVisibility); 用 rememberUpdatedState 取最新路由
        val currentRouteState = rememberUpdatedState(currentRoute)
        LaunchedEffect(Unit) {
            ReadBookEvents.configChange.collect { changes ->
                if (changes.any { it == ReadConfigChange.SYSTEM_UI }) {
                    val route = currentRouteState.value
                    val policy = if (route is AppRoute.Reader) {
                        WindowPolicies.Reader.copy(
                            systemBars = readerSystemBarsPolicy(menuVisible = false)
                        )
                    } else {
                        route?.let { WindowPolicies.forRoute(it) } ?: WindowPolicies.Default
                    }
                    runCatching { applyWindowPolicy(policy) }
                        .onFailure { AppLog.put("应用窗口策略失败", it) }
                }
            }
        }

        DisposableEffect(screenModelStore) {
            onDispose { screenModelStore.clear() }
        }

        // Overlay 栈状态: BackHandler 与渲染共用
        val overlays by navigator.overlays.collectAsState()

        // ESC/BackSpace 返回键由 shared 统一处理 (替代三端入口 onPreviewKeyEvent 重复实现)
        AppGlobalShortcuts(navigator)
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.colors.background)
                .handleBackKey(
                    onBack = { performBack(navigator) },
                    onRefresh = { runCatching { navigator.refreshCurrent() }.getOrDefault(false) },
                )
        ) {
            // 栈内页面保持在同一 Composition 中，返回时直接复用 remember/Effect/协程和节点树。
            // 非顶层页面移出可见区域，出栈后才真正离开 Composition 并释放资源。
            val saveableStateHolder = rememberSaveableStateHolder()
            var retainedEntryIds by remember { mutableStateOf(entries.mapTo(mutableSetOf()) { it.id }) }
            // 动画驱动 + 动画结束后的清理: 出栈页的 ScreenModel/SaveableState 要撑到动画
            // 播完, 提前 retain/removeState 会让返回动画中的页面重建 ViewModel 重载数据
            LaunchedEffect(entries) {
                if (entries.size != lastSettled.value.size) {
                    transition.snapTo(0f)
                    transition.animateTo(1f, tween(durationMillis = 300))
                    lastSettled.value = entries
                    outgoingEntry = null
                    animating = false
                } else if (transition.value != 1f) {
                    // 动画中途导航被打断且栈规模不变 (如快速 push+pop): 复位动画,
                    // 否则页面会停在动画中间位
                    transition.snapTo(1f)
                    animating = false
                }
                // ScreenModel 生命周期与栈绑定 (清理已出栈的 ScreenModel)
                screenModelStore.retain(entries)
                val currentIds = entries.mapTo(mutableSetOf()) { it.id }
                (retainedEntryIds - currentIds).forEach { removedId ->
                    saveableStateHolder.removeState(removedId.value)
                }
                retainedEntryIds = currentIds
            }
            // 动画角色: top=动画后留存的栈顶页, slide=前进时的旧页或返回时的出栈页
            val topEntry = entries.lastOrNull()
            val slideEntry = outgoingEntry ?: displayEntries.getOrNull(displayEntries.lastIndex - 1)
            displayEntries.forEach { entry ->
                val isTop = entry.id == topEntry?.id
                val isSliding = entry.id == slideEntry?.id
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = transition.value
                            val running = transition.isRunning
                            // 动画尚未启动的首帧 (组合先于 effect 的 snapTo): 按起始位渲染,
                            // 与 snapTo(0) 后的动画首帧位置一致, 避免先闪终态再动画
                            val idleFrame = animating && !running && progress == 1f
                            when {
                                isTop -> {
                                    alpha = 1f
                                    scaleX = 1f
                                    scaleY = 1f
                                    translationX = when {
                                        idleFrame && navigatingForward -> size.width
                                        idleFrame -> -size.width * 0.3f
                                        navigatingForward -> size.width * (1f - progress)
                                        else -> -size.width * 0.3f * (1f - progress)
                                    }
                                }

                                isSliding -> {
                                    // 前进: 旧页向左滑出; 返回: 出栈页向右滑出
                                    alpha = 1f
                                    scaleX = 1f
                                    scaleY = 1f
                                    translationX = when {
                                        idleFrame -> 0f
                                        animating && navigatingForward -> -size.width * 0.3f * progress
                                        animating -> size.width * progress
                                        else -> -size.width
                                    }
                                }

                                else -> {
                                    // 移出屏幕而非 scale=0: 零缩放图层的逆矩阵是退化矩阵, 命中测试产生
                                    // NaN/Inf 坐标会腐蚀指针分发 (手势进行中导航离开时尤其明显, 表现为
                                    // 返回后整个界面触摸无响应但系统事件仍到达窗口)。平移到屏幕外坐标有限,
                                    // 命中测试干净 miss, 且不参与可见绘制 (alpha=0 + clip)。
                                    alpha = 0f
                                    scaleX = 1f
                                    scaleY = 1f
                                    translationX = -size.width
                                }
                            }
                            transformOrigin = TransformOrigin(0f, 0f)
                            clip = true
                        }
                        .background(AppTheme.colors.background),
                ) {
                    saveableStateHolder.SaveableStateProvider(entry.id.value) {
                        RouteContent(entry, navigator, screenModelStore)
                    }
                }
            }

            // 渲染 Overlay 栈: 由 shared OverlayContentHost 统一分流 Dialog/Sheet
            overlays.forEach { overlay ->
                OverlayContentHost(overlay)
            }
        }

        // 系统返回键: Overlay 存在时关闭顶层 Overlay (Android 走 BackHandler;
        // 桌面端 ESC/Backspace 由上方 handleBackKey → navigator.pop() 已先 dismissTopOverlay)
        PlatformBackHandler(enabled = overlays.isNotEmpty()) {
            navigator.dismissTopOverlay()
        }

        // 处理初始启动请求
        initialRequest?.let { request ->
            LaunchedEffect(request) {
                handleLaunchRequest(request, navigator, capabilities)
            }
        }
        // 消费各平台入口投递的外部请求，队列保证冷启动和连续请求都按顺序处理。
        LaunchedEffect(Unit) {
            LaunchRequestBus.requests.collect { request ->
                handleLaunchRequest(request, navigator, capabilities)
            }
        }
    }
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator is not provided")
}

val LocalScreenModelStore = staticCompositionLocalOf<ScreenModelStore> {
    error("ScreenModelStore is not provided")
}

val LocalPlatformCapabilities = staticCompositionLocalOf<PlatformCapabilities> {
    PlatformCapabilityProviders.get()
}

val LocalPlatformServices = staticCompositionLocalOf<PlatformServices?> { null }

// 窗口策略应用: 委托 PlatformServices 的 window/keyboard 各 setter (pictureInPicture 暂无统一接口, 跳过)
private fun applyWindowPolicy(policy: WindowPolicy) {
    val services = PlatformServiceProviders.getOrNull() ?: return
    val wc = services.window
    if (wc.appliesPolicyFullscreen) wc.setFullscreen(policy.fullscreen)
    wc.setKeepScreenOn(policy.keepScreenOn)
    wc.setOrientation(policy.orientation)
    wc.setSystemBars(policy.systemBars)
    // 软输入策略委托 KeyboardController (对照 app 端 window.setSoftInputMode)
    services.keyboard.setSoftInputPolicy(policy.softInput)
}

// 启动请求路由分发
private suspend fun handleLaunchRequest(
    request: LaunchRequest,
    navigator: AppNavigator,
    capabilities: PlatformCapabilities,
) {
    when (request) {
        is LaunchRequest.DeepLink -> navigator.push(AppRoute.WebView(request.url))
        is LaunchRequest.SearchBook -> navigator.push(
            AppRoute.Search(key = request.key, submit = request.submit)
        )
        // 书籍类请求需 BookRef: shared 无 DB 能力, 委托平台能力按 bookUrl 解析后导航
        // (bookUrl 在各子类独立声明, 未上提到 LaunchRequest, 故分支独立处理以正确 smart-cast)
        is LaunchRequest.OpenBook -> {
            val ref = capabilities.resolveBookRef(request.bookUrl) ?: return
            // 按书籍类型分流阅读类路由 (Audio/Video/Manga/Rss/Reader), 对照 app 端 startActivityForBook
            navigator.push(ref.toReadRoute(chapterIndex = request.chapterIndex))
        }

        is LaunchRequest.OpenBookInfo -> {
            val ref = capabilities.resolveBookRef(request.bookUrl) ?: return
            navigator.push(AppRoute.BookInfo(ref))
        }

        is LaunchRequest.OpenReader -> {
            val ref = capabilities.resolveBookRef(request.bookUrl) ?: return
            navigator.push(
                ref.toReadRoute(
                    chapterIndex = request.chapterIndex,
                    chapterPos = request.chapterPos,
                )
            )
        }

        is LaunchRequest.OpenBookSource -> navigator.push(
            AppRoute.BookSourceEdit(request.sourceUrl),
            RouteResults.BOOK_SOURCE_EDIT
        )

        is LaunchRequest.ProcessText -> navigator.push(
            AppRoute.Search(key = request.text, submit = true)
        )

        is LaunchRequest.ImportFile -> navigator.push(AppRoute.ImportBook(request.filePath))
        is LaunchRequest.SourceUi -> when (request.type) {
            LaunchRequest.SourceUiType.LOGIN -> navigator.push(AppRoute.Login(request.sourceUrl))
            // 由平台层 SourceUi 处理器消费
            LaunchRequest.SourceUiType.SOURCE_VARIABLE,
            LaunchRequest.SourceUiType.VERIFICATION_CODE -> Unit
        }

        is LaunchRequest.NavigateTo -> when (request.routeName) {
            "book_source_manage" -> navigator.push(AppRoute.BookSourceManage)
            "bookshelf_manage" -> navigator.push(AppRoute.BookshelfManage())
            // 最近阅读: 桌面快捷方式/朗读通知点击 → 打开最近阅读书籍 (对照 app 端 lastRead 快捷方式)
            "last_read" -> {
                val book = AppDbProviders.get().bookDao.lastReadBook()
                // 无最近阅读或 bookResolver 未提供时, 降级到书架主页
                if (book != null) navigator.push(book.toReadRoute())
                else navigator.push(AppRoute.Main(MainTab.BOOKSHELF))
            }

            else -> Unit
        }
    }
}

/**
 * 四端复用的 Overlay 渲染宿主: 按 [AppOverlay] 类型分流 Dialog/Sheet。
 * 由 [LegadoApp] 内部调用, 平台入口无需注入。
 */
@Composable
fun OverlayContentHost(overlay: AppOverlay) {
    val navigator = LocalAppNavigator.current
    when (overlay) {
        is AppOverlay.Dialog -> DialogOverlayContent(overlay, navigator)
        is AppOverlay.Sheet -> SheetOverlayContent(overlay, navigator)
    }
}

// 业务 Dialog: 按 key 分流到具体 Composable
// 规则类 (字典/TXT 目录/屏蔽) 的实现在 RuleOverlayDialogs.kt
@Composable
private fun DialogOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    when (overlay.key) {
        "photo" -> PhotoOverlayDialogContent(overlay, navigator)
        "group_select" -> GroupSelectDialogContent(overlay, navigator)
        "group_manage" -> GroupManageDialogContent(overlay, navigator)
        "source_picker" -> SourcePickerDialogContent(overlay, navigator)
        "bookmark" -> BookmarkDialogContent(overlay, navigator)
        "sourceLogin" -> SourceLoginOverlayDialogContent(overlay, navigator)
        "change_cover" -> ChangeCoverDialogContent(overlay, navigator)
        "app_log" -> AppLogOverlayDialogContent(overlay, navigator)

        // 帮助: key="help" 时 payload 为 md 文件名, dictRuleHelp 为固定文档
        "help" -> HelpDialogContent(overlay, navigator, overlay.payload.orEmpty())
        "dictRuleHelp" -> HelpDialogContent(overlay, navigator, "dictRuleHelp")

        // 崩溃日志 (对照 app 端 CrashLogsDialog Fragment)
        "crash_logs" -> CrashLogsOverlayDialogContent(overlay, navigator)

        // 主题列表 (对照 app 端 ThemeListDialog Fragment)
        "theme_list" -> ThemeListOverlayDialogContent(overlay, navigator)

        // 主题定制 (对照 app 端 ThemeCustomizeDialog Fragment, 背景图功能不下沉)
        // payload 形如 "mode,configIndex,isNight"
        "theme_customize" -> ThemeCustomizeOverlayDialogContent(overlay, navigator)

        // 书架布局配置 / 底栏配置 (对照 app 端 ThemeConfigFragment 对话框;
        // app 端仍走原 Fragment 实现, 其他端经平台能力弹此 shared 对话框)
        "bookshelf_layout" -> BookshelfLayoutOverlayDialogContent(overlay, navigator)
        "bottom_nav_config" -> BottomNavConfigOverlayDialogContent(overlay, navigator)

        // 校验设置 (对照 app 端 CheckSourceConfig Fragment)
        "check_source_config" -> CheckSourceConfigOverlayDialogContent(overlay, navigator)

        // 直链上传配置 (对照 app 端 DirectLinkUploadConfig Fragment)
        "direct_link_upload_config" -> DirectLinkUploadConfigOverlayDialogContent(
            overlay,
            navigator
        )

        // 字典规则
        "dictRuleEdit" -> DictRuleEditDialogContent(overlay, navigator)
        "dictRuleImportLocal" ->
            RuleImportLocalDialogContent(overlay, navigator, RuleImportKind.DICT)

        "dictRuleImportOnline" ->
            RuleImportOnlineDialogContent(overlay, navigator, RuleImportKind.DICT)

        "dictRuleExport" -> RuleExportDialogContent(overlay, navigator, "exportDictRule.json")

        // TXT 目录规则
        "txtTocRuleEdit" -> TxtTocRuleEditDialogContent(overlay, navigator)
        "txtTocRuleImportLocal" ->
            RuleImportLocalDialogContent(overlay, navigator, RuleImportKind.TXT_TOC)

        "txtTocRuleImportOnline" ->
            RuleImportOnlineDialogContent(overlay, navigator, RuleImportKind.TXT_TOC)

        "txtTocRuleExport" -> RuleExportDialogContent(overlay, navigator, "exportTxtTocRule.json")

        // 屏蔽规则
        "sourceFilterRuleEdit" -> SourceFilterEditDialogContent(overlay, navigator)
        "sourceFilterRuleList" -> SourceFilterRuleListDialogContent(overlay, navigator)
        "sourceFilterRuleImportLocal" ->
            RuleImportLocalDialogContent(overlay, navigator, RuleImportKind.SOURCE_FILTER)

        "sourceFilterRuleImportOnline" ->
            RuleImportOnlineDialogContent(overlay, navigator, RuleImportKind.SOURCE_FILTER)

        "sourceFilterRuleExport" ->
            RuleExportDialogContent(overlay, navigator, "exportSourceFilterRule.json")

        else -> FallbackDialogContent(overlay, navigator)
    }
}

// 书源表单登录对话框 (key="sourceLogin", payload=SourceLoginContext 的 dataKey)。
// 对照原版 BaseSource.showLoginDialog 的 showDialogFragment<SourceLoginDialog> 分支。
@Composable
private fun SourceLoginOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val context = remember(overlay.payload) { overlay.payload?.let { SourceLoginContext.take(it) } }
    if (context == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        SourceLoginDialog(
            source = context.source,
            onDismiss = { navigator.dismissOverlay(overlay.key) },
            onOpenUrl = { PlatformCapabilityProviders.getOrNull()?.openExternalUrl(it) },
            book = context.book,
            chapter = context.chapter,
        )
    }
}

// 全屏大图查看 (key="photo", payload=图片 src)
@Composable
private fun PhotoOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val src = overlay.payload ?: return
    PhotoViewOverlayDialog(
        src = src,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 分组选择 (key="group_select", payload=当前 groupId 位掩码字符串)
@Composable
private fun GroupSelectDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    // 解析初始 groupId (对照 BookInfoRoute.onGroupClick payload=group.toString())
    val initialGroupId = overlay.payload?.toLongOrNull() ?: 0L
    val scope = rememberCoroutineScope()
    val groupViewModel = remember(scope) { GroupViewModelShared(scope) }
    var groups by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookGroupDao.flowSelect().conflate().flowOn(IoDispatcher).collect {
            groups = it
        }
    }
    GroupSelectDialog(
        groups = groups,
        initialGroupId = initialGroupId,
        onConfirm = { groupId ->
            navigator.pop(RouteResultPayload.GroupSelect(groupId))
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onPersistOrder = { ordered ->
            groupViewModel.upGroup(*ordered.toTypedArray())
        },
        onAddGroup = { addingGroup = true },
        onEditGroup = { editingGroup = it },
    )
    if (addingGroup || editingGroup != null) {
        GroupEditDialog(
            group = editingGroup,
            onConfirm = { updated ->
                if (addingGroup) {
                    groupViewModel.addGroup(
                        updated.groupName,
                        updated.bookSort,
                        updated.enableRefresh,
                        updated.cover,
                    ) { addingGroup = false }
                } else {
                    groupViewModel.upGroup(updated) { editingGroup = null }
                }
            },
            onDismiss = {
                addingGroup = false
                editingGroup = null
            },
            onDelete = { group ->
                groupViewModel.delGroup(group) { editingGroup = null }
            },
        )
    }
}

// 分组管理 (key="group_manage", 无 payload)
// 对照 app 端 GroupManageDialog: 全高分组的增/删/改/排序/显示开关, 内嵌 GroupEditDialog
@Composable
private fun GroupManageDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    val groupViewModel = remember(scope) { GroupViewModelShared(scope) }
    var groups by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookGroupDao.flowAll()
            .catch { AppLog.put("分组管理获取分组数据失败\n${it.message}", it) }
            .flowOn(IoDispatcher)
            .conflate()
            .collect { groups = it }
    }
    GroupManageDialog(
        groups = groups,
        onAddGroup = { addingGroup = true },
        onEditGroup = { editingGroup = it },
        onUpdateGroup = { groupViewModel.upGroup(it) },
        onPersistOrder = { ordered -> groupViewModel.upGroup(*ordered.toTypedArray()) },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        canAddGroup = { AppDbProviders.get().bookGroupDao.canAddGroup() },
    )
    if (addingGroup || editingGroup != null) {
        GroupEditDialog(
            group = editingGroup,
            onConfirm = { updated ->
                if (addingGroup) {
                    groupViewModel.addGroup(
                        updated.groupName,
                        updated.bookSort,
                        updated.enableRefresh,
                        updated.cover,
                    ) { addingGroup = false }
                } else {
                    groupViewModel.upGroup(updated) { editingGroup = null }
                }
            },
            onDismiss = {
                addingGroup = false
                editingGroup = null
            },
            onDelete = { group ->
                groupViewModel.delGroup(group) { editingGroup = null }
            },
        )
    }
}

// 书源选择 (key="source_picker", 无 payload)
// 对照 app 端 SourcePickerDialog: 加载启用书源, 选中后回传 BookSource
@Composable
private fun SourcePickerDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    var sources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
    LaunchedEffect(Unit) {
        sources = AppDbProviders.get().bookSourceDao.enabled()
    }
    SourcePickerDialog(
        sources = sources,
        initialDelay = AppConfigProviders.get().batchChangeSourceDelay,
        onSourceSelected = { source ->
            navigator.dismissOverlay(
                overlay.key,
                RouteResultPayload.SourcePicker(source),
            )
        },
        onDelayChange = { delay ->
            PreferenceProviders.get().putInt(PreferKey.batchChangeSourceDelay, delay)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 书签编辑 (key="bookmark", payload=IntentData key)
// 对照 app 端 BookmarkDialog: 新建书签的插入, showDelete=false (所有 app 调用点均为新建)
@Composable
private fun BookmarkDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    val bookmark = remember(overlay.payload) {
        IntentData.get<Bookmark>(overlay.payload)
    }
    if (bookmark == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    BookmarkDialog(
        bookmark = bookmark,
        showDelete = false,
        onConfirm = { updated ->
            scope.launch(IoDispatcher) {
                runCatching { AppDbProviders.get().bookmarkDao.insert(updated) }
            }
            navigator.dismissOverlay(overlay.key)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 换封面 (key="change_cover", payload="name\nauthor")
@Composable
private fun ChangeCoverDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    // 解析 name + author (对照 BookInfoRoute.onCoverLongClick payload="$name\n$author")
    val payload = overlay.payload.orEmpty()
    val parts = payload.split("\n", limit = 2)
    val name = parts.getOrNull(0).orEmpty()
    val author = parts.getOrNull(1).orEmpty()

    val scope = rememberCoroutineScope()
    val platform = ChangeCoverPlatformProviders.get()
    // ViewModel 持有: initData 后启动搜索 (对照 app 端 ChangeCoverDialog.Content LaunchedEffect)
    val viewModel = remember(overlay.key) {
        ChangeCoverViewModelShared(scope = scope, platform = platform).also {
            it.initData(name, author)
        }
    }
    // 释放搜索线程池 (对照 app 端 ViewModel.onCleared)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // 封面 slot: 适配 LocalBookCoverSlot (Book, Modifier, Boolean) -> (SearchBook, Modifier)
    val bookCoverSlot = LocalBookCoverSlot.current
    ChangeCoverDialog(
        viewModel = viewModel,
        onCoverSelected = { coverUrl ->
            navigator.dismissOverlay(
                overlay.key,
                RouteResultPayload.ChangeCover(coverUrl),
            )
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        coverSlot = { searchBook, modifier ->
            // SearchBook → Book 适配 LocalBookCoverSlot 签名; 候选封面恒走临时缓存区
            // (对照原版 CoverAdapter 的 ivCover.load(..) 默认 inBookshelf = false)
            bookCoverSlot(searchBook.toCoverBook(), modifier, false, 0)
        },
    )
}

// 应用日志 (key="app_log", 无 payload)
@Composable
private fun AppLogOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    AppLogDialog(onDismiss = { navigator.dismissOverlay(overlay.key) })
}

// 未知 key 兜底: 记日志并立即关闭。不画假对话框, 避免新 key 漏接线时静默通过
@Composable
private fun FallbackDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    LaunchedEffect(overlay.key) {
        AppLog.put("未接线的 Overlay Dialog key: ${overlay.key}")
        navigator.dismissOverlay(overlay.key)
    }
}

// 崩溃日志对话框 (对照 app 端 CrashLogsDialog Fragment 壳)
// 包装 shared CrashLogsDialogContent, 通过 CrashLogProvider 提供数据/回调
@Composable
private fun CrashLogsOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val services = PlatformServiceProviders.getOrNull()
    val provider = services?.crashLogs
    if (provider == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<CrashLogProvider.CrashLogEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        logs = provider.loadCrashLogs()
    }

    CrashLogsDialog(
        logs = logs.map { CrashLogItem(it.name) },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onClear = {
            scope.launch {
                provider.clearCrashLogs()
                logs = provider.loadCrashLogs()
            }
        },
        onReadFile = { item, cb ->
            scope.launch {
                val content = provider.readCrashLog(item.name)
                if (content != null) cb(content)
            }
        },
        onShare = { item -> provider.shareCrashLog(item.name) },
    )
}

// 主题列表对话框 (对照 app 端 ThemeListDialog Fragment 壳)
// 包装 shared ThemeListDialog Composable, 通过 PlatformServices 提供剪贴板/分享能力,
// ThemeCustomizeDialog 仍走平台 Fragment (ThemeCustomizeDialog 未下沉 shared)
@Composable
private fun ThemeListOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val services = PlatformServiceProviders.getOrNull()
    val platform = PlatformCapabilityProviders.getOrNull()

    ThemeListDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onEditConfig = { configIndex ->
            // 编辑主题: 委托平台能力 (app 端 ThemeCustomizeDialog Fragment)
            platform?.showThemeCustomizeDialog(configIndex)
        },
        onNewConfig = { isNight ->
            // 新建主题: 委托平台能力 (app 端 ThemeCustomizeDialog Fragment)
            platform?.showThemeCustomizeDialog(null, isNight)
        },
        onImportFromClip = {
            platform?.getClipboardText()
        },
        onShare = { json ->
            // 分享: 通过 ShareService
            services?.sharing?.shareText(json)
        },
    )
}

// 主题定制对话框 (对照 app 端 ThemeCustomizeDialog Fragment 壳)
// payload 形如 "mode,configIndex,isNight"; Toast 通过 shared Toasters
@Composable
private fun ThemeCustomizeOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    val parts = overlay.payload.orEmpty().split(",")
    val mode = parts.getOrNull(0)?.toIntOrNull() ?: MODE_EDIT_PREFS
    val configIndex = parts.getOrNull(1)?.toIntOrNull() ?: -1
    val isNight = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false

    ThemeCustomizeDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onToast = { msg -> io.legado.app.help.toast.Toasters.get().toast(msg) },
        mode = mode,
        configIndex = configIndex,
        initIsNight = isNight,
    )
}

// 书架布局配置对话框 (对照 app 端 ThemeConfigHost.configBookshelf + dialog_bookshelf_config.xml)
// shared 实现; 变更事件走 FlowBus (NOTIFY_MAIN / BOOKSHELF_REFRESH / RECREATE)
@Composable
private fun BookshelfLayoutOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    BookshelfLayoutConfigDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 底栏配置对话框 (对照 app 端 ThemeConfigHost.configBottomNav + dialog_bottom_nav_config.xml)
// shared 实现; 变更后 emitRecreate() 触发全局重组
@Composable
private fun BottomNavConfigOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    BottomNavConfigDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 校验设置对话框 (对照 app 端 CheckSourceConfig Fragment 壳)
// Toast 通过 shared Toasters (各端 actual: app=toastOnUi, desktop=Toasters)
@Composable
private fun CheckSourceConfigOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator
) {
    CheckSourceConfigDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onToast = { msg -> io.legado.app.help.toast.Toasters.get().toast(msg) },
    )
}

// 直链上传配置对话框 (对照 app 端 DirectLinkUploadConfig Fragment 壳)
// 平台能力通过 PlatformServices/PlatformCapabilities 注入
@Composable
private fun DirectLinkUploadConfigOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
) {
    val platform = PlatformCapabilityProviders.getOrNull()
    var selectorItems by remember { mutableStateOf<List<String>?>(null) }
    var selectorCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    DirectLinkUploadConfigDialog(
        onDismiss = { navigator.dismissOverlay(overlay.key) },
        onToast = { msg -> io.legado.app.help.toast.Toasters.get().toast(msg) },
        onGetClip = { platform?.getClipboardText() },
        onSetClip = { text -> platform?.copyToClipboard(text) },
        onSelector = { items, callback ->
            if (items.isNotEmpty()) {
                selectorItems = items
                selectorCallback = callback
            }
        },
        onTest = { rule, onSuccess, onError ->
            platform?.testDirectLinkUpload(rule, onSuccess, onError)
                ?: onError("当前平台暂不支持测试直链上传")
        },
    )

    selectorItems?.let { items ->
        AppSelectorDialog(
            onDismissRequest = {
                selectorItems = null
                selectorCallback = null
            },
            items = items,
            onItemSelected = { index -> selectorCallback?.invoke(index) },
        )
    }
}

// 通用 Sheet: ModalBottomSheetLayout 承载, 下滑收起后移除该 Overlay
// key 路由: "web_view" 渲染平台 WebView slot (对照 app 端 startBrowser asBottomSheet=true);
// 其他特殊 sheet key 由 OverlayDialogs 子代理按 key 接入具体内容
@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SheetOverlayContent(overlay: AppOverlay.Sheet, navigator: AppNavigator) {
    // E-Ink 直接以 Expanded 起步 = 无进场动画 (对齐 app 版无动画);
    // 其余以 Hidden 起步, 由下方 show() 滑入展开
    val sheetState = rememberModalBottomSheetState(
        if (AppConfigProviders.get().isEInkMode) ModalBottomSheetValue.Expanded
        else ModalBottomSheetValue.Hidden
    )
    var hasShown by remember { mutableStateOf(false) }
    ModalBottomSheetLayout(
        sheetState = sheetState,
        sheetContent = {
            when (overlay.key) {
                "web_view" -> {
                    val url = overlay.payload ?: return@ModalBottomSheetLayout
                    LocalWebViewSlot.current(
                        WebViewConfig(url = url),
                        Modifier.fillMaxWidth(),
                        WebViewCallbacks(),
                    )
                }
                // 其他 sheet key 由 OverlayDialogs 子代理接入
                else -> overlay.payload?.let { Text(it) }
            }
        },
        sheetElevation = 0.dp,
        content = { Box(Modifier.fillMaxSize()) },
    )
    // 初始 Hidden 再滑入展开: 对齐 app 版底部菜单滑入动画 (E-Ink 已起步 Expanded, 无动画)
    LaunchedEffect(Unit) {
        if (sheetState.currentValue != ModalBottomSheetValue.Expanded) {
            sheetState.show()
        }
        hasShown = true
    }
    LaunchedEffect(sheetState.currentValue) {
        // hasShown 排除初始 Hidden (滑入动画起点), 只在展开过之后响应下滑收起
        if (hasShown && sheetState.currentValue == ModalBottomSheetValue.Hidden) {
            navigator.dismissOverlay(overlay.key)
        }
    }
}
