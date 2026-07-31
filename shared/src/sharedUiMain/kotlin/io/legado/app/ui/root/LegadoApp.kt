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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverPlatformProviders
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.compose.platform.PlatformBackHandler
import io.legado.app.ui.compose.platform.dispatchBackKey
import io.legado.app.ui.compose.platform.handleBackKey
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.PhotoViewOverlayDialog
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

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
        val previousEntryCount = remember { mutableStateOf(entries.size) }
        val transition = remember { Animatable(1f) }
        var navigatingForward by remember { mutableStateOf(true) }
        LaunchedEffect(entries.size) {
            if (entries.size != previousEntryCount.value) {
                navigatingForward = entries.size > previousEntryCount.value
                previousEntryCount.value = entries.size
                transition.snapTo(0f)
                transition.animateTo(1f, tween(durationMillis = 220))
            }
        }
        val currentEntry = entries.lastOrNull()
        val currentRoute = currentEntry?.route

        // 应用当前路由对应的窗口策略
        val windowPolicy =
            currentRoute?.let { WindowPolicies.forRoute(it) } ?: WindowPolicies.Default
        SideEffect { applyWindowPolicy(windowPolicy) }

        // ScreenModel 生命周期与栈绑定 (清理已出栈的 ScreenModel)
        LaunchedEffect(entries) {
            screenModelStore.retain(entries)
        }
        DisposableEffect(screenModelStore) {
            onDispose { screenModelStore.clear() }
        }

        // Overlay 栈状态: BackHandler 与渲染共用
        val overlays by navigator.overlays.collectAsState()

        // ESC/BackSpace 返回键由 shared 统一处理 (替代三端入口 onPreviewKeyEvent 重复实现)
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.colors.background)
                .handleBackKey(
                    // 先关顶层 Overlay, 再让页面级 AppBackHandler 拦截 (如书源编辑未保存确认), 最后才出栈
                    onBack = {
                        if (!navigator.dismissTopOverlay() && !dispatchBackKey()) navigator.pop()
                    },
                    onRefresh = navigator::refreshCurrent,
                )
        ) {
            // 栈内页面保持在同一 Composition 中，返回时直接复用 remember/Effect/协程和节点树。
            // 非顶层页面移出可见区域，出栈后才真正离开 Composition 并释放资源。
            val saveableStateHolder = rememberSaveableStateHolder()
            var retainedEntryIds by remember { mutableStateOf(entries.mapTo(mutableSetOf()) { it.id }) }
            LaunchedEffect(entries) {
                val currentIds = entries.mapTo(mutableSetOf()) { it.id }
                (retainedEntryIds - currentIds).forEach { removedId ->
                    saveableStateHolder.removeState(removedId.value)
                }
                retainedEntryIds = currentIds
            }
            entries.forEachIndexed { index, entry ->
                val isCurrent = index == entries.lastIndex
                val isPrevious = index == entries.lastIndex - 1
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = transition.value
                            when {
                                isCurrent -> {
                                    alpha = 1f
                                    translationX = if (navigatingForward) {
                                        size.width * 0.08f * (1f - progress)
                                    } else {
                                        -size.width * 0.03f * (1f - progress)
                                    }
                                    scaleX = 1f
                                    scaleY = 1f
                                }

                                isPrevious && navigatingForward && transition.isRunning -> {
                                    alpha = 1f
                                    translationX = -size.width * 0.03f * progress
                                    scaleX = 1f
                                    scaleY = 1f
                                }

                                else -> {
                                    alpha = 0f
                                    scaleX = 0f
                                    scaleY = 0f
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
    wc.setFullscreen(policy.fullscreen)
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
        "change_cover" -> ChangeCoverDialogContent(overlay, navigator)
        "app_log" -> AppLogOverlayDialogContent(overlay, navigator)

        // 帮助: key="help" 时 payload 为 md 文件名, dictRuleHelp 为固定文档
        "help" -> HelpDialogContent(overlay, navigator, overlay.payload.orEmpty())
        "dictRuleHelp" -> HelpDialogContent(overlay, navigator, "dictRuleHelp")

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
            // SearchBook → Book 适配 LocalBookCoverSlot 签名
            bookCoverSlot(searchBook.toBook(), modifier, false)
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

// 通用 Sheet: ModalBottomSheetLayout 承载, 下滑收起后移除该 Overlay
// key 路由: "web_view" 渲染平台 WebView slot (对照 app 端 startBrowser asBottomSheet=true);
// 其他特殊 sheet key 由 OverlayDialogs 子代理按 key 接入具体内容
@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SheetOverlayContent(overlay: AppOverlay.Sheet, navigator: AppNavigator) {
    val sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Expanded)
    ModalBottomSheetLayout(
        sheetState = sheetState,
        sheetContent = {
            when (overlay.key) {
                "web_view" -> {
                    val url = overlay.payload ?: return@ModalBottomSheetLayout
                    LocalWebViewSlot.current(url, Modifier.fillMaxWidth())
                }
                // 其他 sheet key 由 OverlayDialogs 子代理接入
                else -> overlay.payload?.let { Text(it) }
            }
        },
        sheetElevation = 0.dp,
        content = { Box(Modifier.fillMaxSize()) },
    )
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == ModalBottomSheetValue.Hidden) {
            navigator.dismissOverlay(overlay.key)
        }
    }
}
