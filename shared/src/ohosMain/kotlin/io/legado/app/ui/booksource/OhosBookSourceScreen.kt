package io.legado.app.ui.booksource

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.legado.app.ui.compose.component.Md2TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.source.BookSourceListCallbacks
import io.legado.app.ui.book.source.BookSourceListScreen
import io.legado.app.ui.book.source.BookSourceListState
import io.legado.app.ui.book.source.BookSourceListViewModel
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.SourceFilter
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 鸿蒙端书源管理 Screen 入口 (包装 shared/sharedUiMain 的 [BookSourceListScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.booksource.IosBookSourceScreen] / desktop `BookSourceScreen.kt` 的包装模式,
 * 鸿蒙端在 OhosNavHost 的 BOOK_SOURCE 路由分支调用本入口。
 *
 * 本文件仅做鸿蒙平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **VM 生命周期**: `remember { BookSourceListViewModel(scope) }` 持有
 * - **状态管理**: 用 `mutableStateOf(BookSourceListState())` 持有, 修改时 copy 出新实例
 * - **数据收集**: LaunchedEffect 监听 searchKey/sort/sortAscending/groupSourcesByDomain 变化,
 *   调 `viewModel.flowSources(...)` 收集书源列表; LaunchedEffect(Unit) 收集分组列表
 * - **过滤映射**: searchKey 字面串映射到 [SourceFilter]
 * - **callbacks**: 提供 [BookSourceListCallbacks] 实现, 核心数据操作完整可用;
 *   编辑/调试/登录/导入/分组管理/校验/帮助等依赖 Dialog 或文件选择器的动作接入真实实现
 *
 * # 简化项 (与 iOS / desktop 差异)
 *
 * - 不接入书源校验 (CheckSourceShared): 依赖较重, 鸿蒙端暂未接入
 * - 复制/粘贴/分享: 鸿蒙端剪贴板为 stub, 暂留 TODO
 *
 * @param onSearchBook 单项菜单"搜索书籍"回调, 由 OhosNavHost 注入 (切到 SEARCH 路由)
 * @param onBack 返回回调 (切回书架路由, 由 OhosNavHost 注入)
 * @param onDebug 书源调试回调, 由 OhosNavHost 注入 (切到 BOOK_SOURCE_DEBUG 路由, 携带 sourceUrl)
 */
@Composable
fun OhosBookSourceScreen(
    onSearchBook: (BookSourcePart) -> Unit = {},
    onBack: () -> Unit = {},
    onDebug: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { BookSourceListViewModel(scope) }
    val state = remember { mutableStateOf(BookSourceListState()) }
    val listState = rememberLazyListState()

    // 对话框状态
    var loginTarget by remember { mutableStateOf<BookSource?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editTargetUrl by remember { mutableStateOf<String?>(null) }
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    val importVm = remember(scope) { ImportBookSourceViewModelShared(scope) }
    val importCompleteTemplate = rememberString("import_complete")

    // 过滤关键字 label
    val enabledLabel = rememberString("enabled")
    val disabledLabel = rememberString("disabled")
    val needLoginLabel = rememberString("need_login")
    val noGroupLabel = rememberString("no_group")
    val enabledExploreLabel = rememberString("enabled_explore")
    val disabledExploreLabel = rememberString("disabled_explore")

    // searchKey 字面串映射到 SourceFilter
    fun parseFilter(searchKey: String): SourceFilter = when {
        searchKey.isEmpty() -> SourceFilter.All
        searchKey == enabledLabel -> SourceFilter.Enabled
        searchKey == disabledLabel -> SourceFilter.Disabled
        searchKey == needLoginLabel -> SourceFilter.NeedLogin
        searchKey == noGroupLabel -> SourceFilter.NoGroup
        searchKey == enabledExploreLabel -> SourceFilter.EnabledExplore
        searchKey == disabledExploreLabel -> SourceFilter.DisabledExplore
        searchKey.startsWith("group:") -> SourceFilter.Group(searchKey.substringAfter("group:"))
        else -> SourceFilter.Search(searchKey)
    }

    // 收集书源列表数据
    LaunchedEffect(state.value.searchKey, state.value.sort, state.value.sortAscending, state.value.groupSourcesByDomain) {
        viewModel.flowSources(
            filter = parseFilter(state.value.searchKey),
            sort = state.value.sort,
            sortAscending = state.value.sortAscending,
            groupSourcesByDomain = state.value.groupSourcesByDomain,
            getSourceHost = ::getSourceHost,
        ).collectLatest { sources ->
            state.value = state.value.copy(
                sources = sources,
                selected = state.value.selected.intersect(sources.map { it.bookSourceUrl }.toSet()),
            )
        }
    }

    // 收集分组列表
    LaunchedEffect(Unit) {
        viewModel.flowGroups().collectLatest { groups ->
            state.value = state.value.copy(groups = groups)
        }
    }

    // 导入书源: 解析成功 → 自动 importSelect 入库
    LaunchedEffect(importVm) {
        importVm.successState.collectLatest { count ->
            if (count != null) {
                importVm.importSelect { Toasters.get().toast(String.format(importCompleteTemplate, count)) }
            }
        }
    }
    LaunchedEffect(importVm) {
        importVm.errorState.collectLatest { err ->
            if (err != null) Toasters.get().toast(err.substringAfter("ImportError:"))
        }
    }

    // 批量操作文案标签
    val enableSelectionLabel = rememberString("enable_selection")
    val disableSelectionLabel = rememberString("disable_selection")
    val enableExploreLabel = rememberString("enable_explore")
    val disableExploreLabel = rememberString("disable_explore")
    val selectionToTopLabel = rememberString("selection_to_top")
    val selectionToBottomLabel = rememberString("selection_to_bottom")
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val newBookSourceLabel = rememberString("new_book_source")
    val newBookSourceAddedLogTemplate = rememberString("new_book_source_added_log")
    // 网络导入对话框文案
    val netImportBookSourceLabel = rememberString("net_import_book_source")
    val inputBookSourceUrlLabel = rememberString("input_book_source_url")

    // 分组列表 (供 GroupManageDialog)
    val groupNames by produceState<List<String>>(emptyList()) {
        viewModel.flowGroups().collect { value = it }
    }
    val groupEntities = remember(groupNames) {
        groupNames.mapIndexed { index, name ->
            BookGroup(groupId = (index + 1).toLong(), groupName = name)
        }
    }

    val callbacks = remember(viewModel, state) {
        BookSourceListCallbacks(
            onBack = { onBack() },
            onQueryChange = { state.value = state.value.copy(searchKey = it) },
            onSortChange = { state.value = state.value.copy(sort = it) },
            onToggleSortDesc = { state.value = state.value.copy(sortAscending = !state.value.sortAscending) },
            onToggleGroupByDomain = {
                state.value = state.value.copy(groupSourcesByDomain = !state.value.groupSourcesByDomain)
            },
            onToggle = { item, checked ->
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + item.bookSourceUrl
                    else state.value.selected - item.bookSourceUrl
                )
            },
            onSelectAll = { all ->
                state.value = state.value.copy(
                    selected = if (all) state.value.sources.map { it.bookSourceUrl }.toSet() else emptySet()
                )
            },
            onRevertSelection = {
                state.value = state.value.copy(
                    selected = state.value.sources.map { it.bookSourceUrl }.toSet() - state.value.selected
                )
            },
            onMove = { from, to ->
                state.value = state.value.copy(
                    sources = state.value.sources.toMutableList().apply { add(to, removeAt(from)) }
                )
            },
            onPersistOrder = {
                val ascending = state.value.sortAscending
                val items = state.value.sources.mapIndexed { index, part ->
                    part.copy(customOrder = if (ascending) index else -index)
                }
                viewModel.upOrder(items)
            },
            onEdit = { item ->
                // 切到书源编辑 Dialog (包装 OhosBookSourceEditScreen)
                editTargetUrl = item.bookSourceUrl
            },
            onEnable = { enable, item -> viewModel.enable(enable, listOf(item)) },
            onEnableExplore = { enable, item -> viewModel.enableExplore(enable, listOf(item)) },
            onToTop = { item ->
                if (state.value.sortAscending) viewModel.topSource(item) else viewModel.bottomSource(item)
            },
            onToBottom = { item ->
                if (state.value.sortAscending) viewModel.bottomSource(item) else viewModel.topSource(item)
            },
            onSearchBook = onSearchBook,
            onDebug = { item ->
                onDebug(item.bookSourceUrl)
            },
            onLogin = { item ->
                scope.launch {
                    loginTarget = AppDbProviders.get().bookSourceDao.getBookSource(item.bookSourceUrl)
                }
            },
            onDel = { item -> viewModel.del(listOf(item)) },
            onDelSelection = {
                showDeleteConfirm = true
            },
            onCancelCheckSource = {
                // TODO: 取消书源校验 (鸿蒙端暂未接入书源校验)
            },
            onAddBookSource = {
                // 新建空 BookSource 并入库 (用 systemCurrentTimeMillis 替代 NSDate)
                scope.launch {
                    val dao = AppDbProviders.get().bookSourceDao
                    val source = BookSource(
                        bookSourceName = newBookSourceLabel,
                        bookSourceUrl = "new_${systemCurrentTimeMillis()}",
                    )
                    dao.insert(source)
                    AppLog.put(String.format(newBookSourceAddedLogTemplate, source.bookSourceUrl))
                }
            },
            onImportLocal = {
                // pickDocuments 选 JSON/文本 → 读字节转 UTF-8 文本 → importSource 解析比对
                scope.launch {
                    val urls = pickDocuments(
                        contentTypes = listOf("public.json", "public.text"),
                        allowsMultiple = false,
                    ) ?: return@launch
                    val firstUrl = urls.firstOrNull() ?: return@launch
                    val bytes = pickDocumentContent(firstUrl) ?: return@launch
                    val text = bytes.toString(Charsets.UTF_8)
                    importVm.importSource(text)
                }
            },
            onImportOnline = {
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            },
            onGroupManage = {
                showGroupManage = true
            },
            onHelp = {
                val url = "https://github.com/gedoor/legado/wiki/书源制作"
                openURL(url)
            },
            onSelectActions = {
                val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
                listOf(
                    SelectAction(enableSelectionLabel) { viewModel.enableSelection(selection) },
                    SelectAction(disableSelectionLabel) { viewModel.disableSelection(selection) },
                    SelectAction(enableExploreLabel) { viewModel.enableSelectExplore(selection) },
                    SelectAction(disableExploreLabel) { viewModel.disableSelectExplore(selection) },
                    SelectAction(selectionToTopLabel) { viewModel.topSource(*selection.toTypedArray()) },
                    SelectAction(selectionToBottomLabel) { viewModel.bottomSource(*selection.toTypedArray()) },
                )
            },
            getSourceHost = ::getSourceHost,
        )
    }

    // 调用 shared/sharedUiMain 的 BookSourceListScreen
    BookSourceListScreen(
        state = state.value,
        callbacks = callbacks,
        listState = listState,
    )

    // 书源登录对话框
    loginTarget?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { loginTarget = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }

    // 分组管理对话框
    if (showGroupManage) {
        GroupManageDialog(
            groups = groupEntities,
            onAddGroup = { name -> viewModel.addGroup(name) },
            onRenameGroup = { groupId, newName ->
                groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { oldName ->
                    viewModel.upGroup(oldName, newName)
                }
            },
            onDeleteGroup = { groupId ->
                groupEntities.find { it.groupId == groupId.toLong() }?.groupName?.let { name ->
                    viewModel.delGroup(name)
                }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // 删除选中确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(deleteLabel) },
            text = { Text(sureDelLabel) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
                    viewModel.del(selection)
                    state.value = state.value.copy(selected = emptySet())
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // 书源编辑 Dialog (onEdit 触发, 包装 OhosBookSourceEditScreen 全屏展示)
    editTargetUrl?.let { url ->
        Dialog(
            onDismissRequest = { editTargetUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                OhosBookSourceEditScreen(
                    sourceUrl = url,
                    onBack = { editTargetUrl = null },
                    onSaved = { _ -> editTargetUrl = null },
                    onDebugSource = { srcUrl ->
                        editTargetUrl = null
                        onDebug(srcUrl)
                    },
                )
            }
        }
    }

    // 网络导入 URL 输入对话框
    if (showImportOnlineDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(netImportBookSourceLabel) },
            text = {
                Md2TextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = inputBookSourceUrlLabel,
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = importOnlineUrlText
                    showImportOnlineDialog = false
                    if (url.isNotBlank()) {
                        importVm.importSource(url)
                    }
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showImportOnlineDialog = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

/**
 * 从 URL 提取主域名 (倒数第二段 + 顶级域), 如 `https://www.example.com/path` -> `example.com`;
 * 解析失败返回 `"#"`, 与 app 端 fallback 一致 (用于按域名分组时排在最后)。
 *
 * 鸿蒙端不依赖 java.net.URL (Kotlin/Native 不可用), 用 Kotlin 标准库做简化解析,
 * 与 iOS `getSourceHost` / desktop `getSourceHost` 等价 (仅解析实现不同, 输出一致)。
 */
private fun getSourceHost(origin: String): String {
    return try {
        val afterScheme = origin.substringAfter("://", origin)
        val host = afterScheme.substringBefore("/")
        val parts = host.split(".")
        if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    } catch (_: Exception) {
        "#"
    }
}
