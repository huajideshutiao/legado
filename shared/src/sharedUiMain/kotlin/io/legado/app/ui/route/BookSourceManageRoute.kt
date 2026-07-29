package io.legado.app.ui.route

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.HelpVersion
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportBookSourceItemsDialog
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.book.source.BookSourceListCallbacks
import io.legado.app.ui.book.source.BookSourceListScreen
import io.legado.app.ui.book.source.SourceFilter
import io.legado.app.ui.book.source.manage.BookSourceScreenModel
import io.legado.app.ui.book.source.manage.BookSourceUiEvent
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.OnlineImportUrlDialog
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AppRoute.BookSourceManage 路由下沉入口: 桥接 [BookSourceScreenModel] 状态与 [BookSourceListScreen] 渲染。
 *
 * 平台字符串经 [rememberString] 注入 resolveFilter; 导航与 dispatch 回调接入 [AppNavigator]/ScreenModel;
 * 平台专属回调 (导入/分组/帮助/校验等) 通过 [PlatformCapabilityProviders] 委托或 shared 组件实现。
 */
@Composable
fun BookSourceManageRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    // resolveFilter 所需平台字符串 (对齐 app 端 R.string 比对)
    val strEnabled = rememberString("enabled")
    val strDisabled = rememberString("disabled")
    val strNeedLogin = rememberString("need_login")
    val strNoGroup = rememberString("no_group")
    val strEnabledExplore = rememberString("enabled_explore")
    val strDisabledExplore = rememberString("disabled_explore")

    // 批量栏 SelectAction 文案 (预取, 供 onSelectActions 构建)
    val strEnableSelection = rememberString("enable_selection")
    val strDisableSelection = rememberString("disable_selection")
    val strAddGroup = rememberString("add_group")
    val strRemoveGroup = rememberString("remove_group")
    val strEnableExplore = rememberString("enable_explore")
    val strDisableExplore = rememberString("disable_explore")
    val strSelectionToTop = rememberString("selection_to_top")
    val strSelectionToBottom = rememberString("selection_to_bottom")
    val strExportSelection = rememberString("export_selection")
    val strShareSelectedSource = rememberString("share_selected_source")
    val strCheckSelectSource = rememberString("check_select_source")
    val strCheckSelectedInterval = rememberString("check_selected_interval")

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookSourceScreenModel(
            resolveFilter = { searchKey ->
                when {
                    searchKey.isEmpty() -> SourceFilter.All
                    searchKey == strEnabled -> SourceFilter.Enabled
                    searchKey == strDisabled -> SourceFilter.Disabled
                    searchKey == strNeedLogin -> SourceFilter.NeedLogin
                    searchKey == strNoGroup -> SourceFilter.NoGroup
                    searchKey == strEnabledExplore -> SourceFilter.EnabledExplore
                    searchKey == strDisabledExplore -> SourceFilter.DisabledExplore
                    searchKey.startsWith("group:") ->
                        SourceFilter.Group(searchKey.substringAfter("group:"))

                    else -> SourceFilter.Search(searchKey)
                }
            },
        )
    }

    val state by screenModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 对话框状态 (对照 app 端 Activity 内 showDialogFragment 调用)
    var showHelp by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var importVm by remember { mutableStateOf<ImportBookSourceViewModelShared?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 删除确认对话框 (对照 app 端 del / delSelection 内 alert)
    var delTarget by remember { mutableStateOf<BookSourcePart?>(null) }
    var showDelSelection by remember { mutableStateOf(false) }

    // 首次打开帮助引导 (对照 app 端 onActivityCreated: !LocalConfig.bookSourcesHelpVersionIsLast)
    LaunchedEffect(Unit) {
        val prefs = PreferenceProviders.get()
        val isLastHelp = LocalConfigShared.isLastVersion(
            lastVersion = HelpVersion.bookSourcesHelp,
            versionKey = LocalConfigKeys.bookSourceHelpVersion,
            firstOpenKey = LocalConfigKeys.firstOpenBookSources,
            getInt = prefs::getInt,
            getBoolean = prefs::getBoolean,
            putInt = prefs::putInt,
        )
        if (!isLastHelp) showHelp = true
    }

    // 校验进度文案 (对照 app 端 observeLiveBus: EventBus.CHECK_SOURCE)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.CHECK_SOURCE).collect { event ->
            val msg = event as? String ?: return@collect
            screenModel.dispatch(BookSourceUiEvent.UpdateCheckSourceMsg(msg))
        }
    }

    // 校验完成 (对照 app 端 observeLiveBus: EventBus.CHECK_SOURCE_DONE)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.CHECK_SOURCE_DONE).collect {
            screenModel.dispatch(BookSourceUiEvent.HideCheckSource)
            val st = screenModel.state.value
            if (st.searchKey.isEmpty()) {
                st.groups.forEach { group ->
                    if (group.contains("失效")) {
                        screenModel.dispatch(BookSourceUiEvent.Search("失效"))
                        Toasters.get().toast("发现有失效书源，已为您自动筛选！")
                        return@collect
                    }
                }
            }
        }
    }

    // 监听导入 VM 的成功信号 (对照 ReplaceRuleRoute 模式)
    LaunchedEffect(importVm) {
        val vm = importVm ?: return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                vm.successState.collect { count ->
                    if (count != null) showImportDialog = true
                }
            }
        }
    }

    // dispatch 类接入 ScreenModel; 导航类走 navigator; 平台专属委托 PlatformCapabilityProviders
    val callbacks = remember(navigator, screenModel) {
        BookSourceListCallbacks(
            onBack = {
                // 对照 app 端 finish(): searchKey 非空时清空搜索, 空时退出
                val st = screenModel.state.value
                if (st.searchKey.isEmpty()) navigator.pop()
                else screenModel.dispatch(BookSourceUiEvent.Search(""))
            },
            onQueryChange = { screenModel.dispatch(BookSourceUiEvent.Search(it)) },
            onSortChange = { screenModel.dispatch(BookSourceUiEvent.SortChange(it)) },
            onToggleSortDesc = { screenModel.dispatch(BookSourceUiEvent.ToggleSortDesc) },
            onToggleGroupByDomain = { screenModel.dispatch(BookSourceUiEvent.ToggleGroupByDomain) },
            onToggle = { item, checked ->
                screenModel.dispatch(
                    BookSourceUiEvent.Toggle(
                        item,
                        checked
                    )
                )
            },
            onSelectAll = { screenModel.dispatch(BookSourceUiEvent.SelectAll(it)) },
            onRevertSelection = { screenModel.dispatch(BookSourceUiEvent.RevertSelection) },
            onMove = { from, to -> screenModel.dispatch(BookSourceUiEvent.Move(from, to)) },
            onPersistOrder = { screenModel.dispatch(BookSourceUiEvent.PersistOrder) },
            onEdit = { part -> navigator.push(AppRoute.BookSourceEdit(part.bookSourceUrl)) },
            onEnable = { enabled, item ->
                screenModel.dispatch(
                    BookSourceUiEvent.Enable(
                        item,
                        enabled
                    )
                )
            },
            onEnableExplore = { enabled, item ->
                screenModel.dispatch(
                    BookSourceUiEvent.EnableExplore(
                        item,
                        enabled
                    )
                )
            },
            onToTop = { screenModel.dispatch(BookSourceUiEvent.ToTop(it)) },
            onToBottom = { screenModel.dispatch(BookSourceUiEvent.ToBottom(it)) },
            onSearchBook = { navigator.push(AppRoute.Search()) },
            onDebug = { part -> navigator.push(AppRoute.BookSourceDebug(part.bookSourceUrl)) },
            onLogin = { part -> navigator.push(AppRoute.Login(part.bookSourceUrl)) },
            onDel = { delTarget = it },
            onDelSelection = { showDelSelection = true },
            onCancelCheckSource = { PlatformCapabilityProviders.getOrNull()?.cancelCheckSource() },
            onAddBookSource = { PlatformCapabilityProviders.getOrNull()?.addBookSource() },
            onImportLocal = {
                // 对照 ReplaceRuleRoute onImportLocal: 文件选择器 + ImportBookSourceViewModelShared
                val services =
                    PlatformServiceProviders.getOrNull() ?: return@BookSourceListCallbacks
                scope.launch {
                    val path = withContext(IoDispatcher) {
                        services.files.pickFile(io.legado.app.ui.root.FileFilter.Text)
                    } ?: return@launch
                    val text = withContext(IoDispatcher) { BackupFileOps.readText(path) }
                    val vm = ImportBookSourceViewModelShared(scope)
                    importVm = vm
                    vm.importSource(text)
                }
            },
            onImportOnline = { showUrlInput = true },
            onGroupManage = {
                PlatformCapabilityProviders.getOrNull()?.showBookSourceGroupManage()
            },
            onHelp = { showHelp = true },
            onSelectActions = {
                // 对照 app 端 selectActions(): 12 个 SelectAction
                listOf(
                    SelectAction(strEnableSelection) {
                        screenModel.dispatch(BookSourceUiEvent.EnableSelection(true))
                    },
                    SelectAction(strDisableSelection) {
                        screenModel.dispatch(BookSourceUiEvent.EnableSelection(false))
                    },
                    SelectAction(strAddGroup) {
                        PlatformCapabilityProviders.getOrNull()
                            ?.selectionAddToGroups(screenModel.selection())
                    },
                    SelectAction(strRemoveGroup) {
                        PlatformCapabilityProviders.getOrNull()
                            ?.selectionRemoveFromGroups(screenModel.selection())
                    },
                    SelectAction(strEnableExplore) {
                        screenModel.dispatch(BookSourceUiEvent.EnableSelectExplore)
                    },
                    SelectAction(strDisableExplore) {
                        screenModel.dispatch(BookSourceUiEvent.DisableSelectExplore)
                    },
                    SelectAction(strSelectionToTop) {
                        screenModel.dispatch(BookSourceUiEvent.SelectionToTop)
                    },
                    SelectAction(strSelectionToBottom) {
                        screenModel.dispatch(BookSourceUiEvent.SelectionToBottom)
                    },
                    SelectAction(strExportSelection) {
                        PlatformCapabilityProviders.getOrNull()?.exportBookSourceSelection(
                            selection = screenModel.selection(),
                            allCount = screenModel.state.value.sources.size,
                            sortAscending = screenModel.state.value.sortAscending,
                        )
                    },
                    SelectAction(strShareSelectedSource) {
                        PlatformCapabilityProviders.getOrNull()?.shareBookSourceSelection(
                            selection = screenModel.selection(),
                            allCount = screenModel.state.value.sources.size,
                            sortAscending = screenModel.state.value.sortAscending,
                        )
                    },
                    SelectAction(strCheckSelectSource) {
                        PlatformCapabilityProviders.getOrNull()
                            ?.checkBookSource(screenModel.selection())
                    },
                    SelectAction(strCheckSelectedInterval) {
                        screenModel.dispatch(BookSourceUiEvent.CheckSelectedInterval)
                    },
                )
            },
            getSourceHost = { screenModel.getSourceHost(it) },
        )
    }

    BookSourceListScreen(
        state = state,
        callbacks = callbacks,
        listState = listState,
        listModifier = Modifier.dragSelectable(
            listState = listState,
            autoScrollScope = scope,
            isSelected = { index ->
                val st = screenModel.state.value
                index < st.sources.size && st.selected.contains(st.sources[index].bookSourceUrl)
            },
            onSelectedChanged = { index, sel ->
                screenModel.state.value.sources.getOrNull(index)?.let {
                    screenModel.dispatch(BookSourceUiEvent.Toggle(it, sel))
                }
            },
        ),
    )

    // 帮助对话框 (对照 app 端 help / showHelp("SourceMBookHelp"))
    if (showHelp) {
        HelpDialog("SourceMBookHelp") { showHelp = false }
    }

    // 在线导入 URL 输入对话框 (对照 app 端 showImportDialog)
    if (showUrlInput) {
        OnlineImportUrlDialog(
            recordKey = "bookSourceRecordKey",
            onConfirm = { url ->
                showUrlInput = false
                val vm = ImportBookSourceViewModelShared(scope)
                importVm = vm
                vm.importSource(url)
            },
            onDismiss = { showUrlInput = false },
        )
    }

    // 书源勾选导入对话框 (对照 app 端 ImportBookSourceDialog)
    importVm?.let { vm ->
        if (showImportDialog) {
            ImportBookSourceItemsDialog(
                vm = vm,
                onDismiss = {
                    showImportDialog = false
                    importVm = null
                },
                onImported = {
                    showImportDialog = false
                    importVm = null
                },
            )
        }
    }

    // 单项删除确认 (对照 app 端 del: alert(draw) { sure_del + name; yesButton { del } })
    delTarget?.let { part ->
        AppAlertDialog(
            onDismissRequest = { delTarget = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + part.bookSourceName,
            okButton = AlertButton(rememberString("yes")) {
                screenModel.dispatch(BookSourceUiEvent.Del(part))
            },
            cancelButton = AlertButton(rememberString("no")),
        )
    }

    // 批量删除确认 (对照 app 端 delSelection: alert(draw, sure_del) { yesButton { del }; noButton() })
    if (showDelSelection) {
        AppAlertDialog(
            onDismissRequest = { showDelSelection = false },
            title = rememberString("draw"),
            message = rememberString("sure_del"),
            okButton = AlertButton(rememberString("yes")) {
                screenModel.dispatch(BookSourceUiEvent.DelSelection)
            },
            cancelButton = AlertButton(rememberString("no")),
        )
    }
}
