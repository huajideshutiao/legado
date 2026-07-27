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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.Foundation.NSDate

/**
 * iOS 端书源管理 Screen 入口 (KP4: 包装 shared/sharedUiMain 的 [BookSourceListScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/booksource/BookSourceScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 BOOK_SOURCE 路由分支调用本入口。
 *
 * 本文件仅做 iOS 平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **VM 生命周期**: `remember { BookSourceListViewModel(scope) }` 持有 (KMP 版, 用 application scope)
 * - **状态管理**: 用 `mutableStateOf(BookSourceListState())` 持有, 修改时 copy 出新实例
 *   (与 desktop BookSourceScreen line 145-147 一致)
 * - **数据收集**: LaunchedEffect 监听 searchKey/sort/sortAscending/groupSourcesByDomain 变化,
 *   调 `viewModel.flowSources(...)` 收集书源列表; LaunchedEffect(Unit) 收集分组列表
 * - **过滤映射**: searchKey 字面串映射到 [SourceFilter] (与 desktop parseFilter 一致)
 * - **callbacks**: 提供 [BookSourceListCallbacks] 实现, 核心数据操作 (启用/禁用/排序/删除/
 *   选中/拖拽/置顶/底/批量操作) 完整可用; 编辑/调试/登录/导入/分组管理/校验/帮助等
 *   依赖未下沉 Dialog 或 iOS 文件选择器的动作暂为 no-op + TODO 注释
 *
 * # 简化项 (与 desktop 差异)
 *
 * - 不接入书源校验 (CheckSourceShared): 依赖较重 (并发协程 + NotificationProgresses + Debug),
 *   iOS 端暂未接入 (后续 KP5+)
 * - 不接入本地/网络导入 (ImportBookSourceViewModelShared + DesktopImportDialog):
 *   依赖 iOS 文件选择器 (UIDocumentPickerViewController) 桥接, 后续 KP5+ 接入
 * - 不接入书源编辑/调试/登录子路由: iOS 端暂未接入对应子路由
 * - 不接入导出/分享: 依赖 iOS 文件保存/分享面板 (UIActivityViewController), 后续 KP5+ 接入
 * - 不接入分组管理 Dialog: shared/sharedUiMain 已下沉 GroupManageDialog, 但 iOS 端简化暂不接入
 *
 * @param onSearchBook 单项菜单"搜索书籍"回调, 由 IosNavHost 注入 (切到 SEARCH 路由)
 * @param onBack 返回回调 (切回书架路由, 由 IosNavHost 注入)
 */
@Composable
fun IosBookSourceScreen(
    onSearchBook: (BookSourcePart) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { BookSourceListViewModel(scope) }
    // 用 MutableState 引用持有, callbacks lambda 通过 .value 读写最新 state, 避免 callbacks 重组
    // (与 desktop BookSourceScreen line 145-147 一致)
    val state = remember { mutableStateOf(BookSourceListState()) }
    val listState = rememberLazyListState()

    // ---- KP5: 接入 onLogin/onGroupManage/onDelSelection/onHelp/onAddBookSource 用的 state ----
    // 书源登录对话框状态 (null=隐藏, 非空=显示; onLogin 触发后异步按 url 查 BookSource 完整记录填入,
    // 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header; 对照 desktop line 244)
    var loginTarget by remember { mutableStateOf<BookSource?>(null) }
    // 分组管理对话框状态 (false=隐藏; onGroupManage 触发, 末尾 GroupManageDialog 渲染)
    var showGroupManage by remember { mutableStateOf(false) }
    // 删除选中确认对话框状态 (false=隐藏; onDelSelection 触发, 末尾 AlertDialog 渲染)
    // (替换原直接删除, 加二次确认; 对照 desktop line 226 deleteSelectionTarget)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 书源编辑/调试 Dialog 状态 (null=隐藏; onEdit/onDebug 触发后填入 bookSourceUrl)
    var editTargetUrl by remember { mutableStateOf<String?>(null) }
    var debugTargetUrl by remember { mutableStateOf<String?>(null) }
    // 网络导入 URL 输入对话框状态 (onImportOnline 触发)
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }

    // 书源导入 VM (KP6: pickDocuments → importSource → importSelect 自动导入)
    val importVm = remember(scope) { ImportBookSourceViewModelShared(scope) }
    // 文案模板 (importVm LaunchedEffect lambda 非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")

    // 过滤关键字 label (rememberString 是 @Composable, 顶层 remember 一次;
    // parseFilter 局部函数非 @Composable, 需预先缓存 label 后捕获)
    // key 对齐 ResourceProvider.ios.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val enabledLabel = rememberString("enabled")
    val disabledLabel = rememberString("disabled")
    val needLoginLabel = rememberString("need_login")
    val noGroupLabel = rememberString("no_group")
    val enabledExploreLabel = rememberString("enabled_explore")
    val disabledExploreLabel = rememberString("disabled_explore")

    // 把 searchKey 字面串映射到 SourceFilter (与 desktop BookSourceScreen.parseFilter 一致)
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

    // 收集书源列表数据 (searchKey/sort/sortAscending/groupSourcesByDomain 变化时重启)
    // (与 desktop BookSourceScreen line 173-187 一致)
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
                // 选中集与最新数据取交集, 避免删除后 selected 残留
                selected = state.value.selected.intersect(sources.map { it.bookSourceUrl }.toSet()),
            )
        }
    }

    // 收集分组列表 (与 desktop BookSourceScreen line 190-194 一致)
    LaunchedEffect(Unit) {
        viewModel.flowGroups().collectLatest { groups ->
            state.value = state.value.copy(groups = groups)
        }
    }

    // 导入书源: 解析成功 → 自动 importSelect 入库; 失败 → toast 错误
    // (KP6: 后续接入 ImportDialog 后改为用户勾选比对再 importSelect)
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

    // 文案标签 (rememberString 是 @Composable, 顶层 remember 一次; onSelectActions lambda 非 @Composable, 需预先缓存)
    // key 对齐 app 端 BookSourceActivity.selectActions() 的 R.string.* (与 values-zh/strings.xml 一致)
    val enableSelectionLabel = rememberString("enable_selection")
    val disableSelectionLabel = rememberString("disable_selection")
    val enableExploreLabel = rememberString("enable_explore")
    val disableExploreLabel = rememberString("disable_explore")
    val selectionToTopLabel = rememberString("selection_to_top")
    val selectionToBottomLabel = rememberString("selection_to_bottom")
    // KP5 新增文案标签 (onDelSelection AlertDialog / onAddBookSource BookSource 构造用)
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val newBookSourceLabel = rememberString("new_book_source")
    // 文案模板 (onAddBookSource lambda 内 AppLog 非 @Composable, 预先 remember 模板)
    val newBookSourceAddedLogTemplate = rememberString("new_book_source_added_log")

    // 分组列表 (订阅 viewModel.flowGroups(), 用于 GroupManageDialog; BookSource 分组是 String 逗号分隔,
    // shared GroupManageDialog 期望 List<BookGroup>, 用 groupEntities 做 String→BookGroup 适配,
    // groupId 用递增索引避免与系统分组 <=0 冲突, 与 desktop BookSourceScreen line 249-256 一致)
    val groupNames by produceState<List<String>>(emptyList()) {
        viewModel.flowGroups().collect { value = it }
    }
    val groupEntities = remember(groupNames) {
        groupNames.mapIndexed { index, name ->
            BookGroup(groupId = (index + 1).toLong(), groupName = name)
        }
    }

    // callbacks 用 remember 持有稳定实例, lambda 捕获 state (MutableState) 引用, 不触发重组
    // (与 desktop BookSourceScreen line 296-455 一致, 简化版: 核心数据操作接真实回调,
    //  依赖未下沉 Dialog/子路由的动作 no-op + TODO)
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
                // 持久化拖拽后的顺序 (与 desktop BookSourceScreen line 326-332 一致)
                val ascending = state.value.sortAscending
                val items = state.value.sources.mapIndexed { index, part ->
                    part.copy(customOrder = if (ascending) index else -index)
                }
                viewModel.upOrder(items)
            },
            onEdit = { item ->
                // TODO: 切到书源编辑子路由 (iOS 端暂未接入 BOOK_SOURCE_EDIT, 后续 KP5+)
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
                // TODO: 切到书源调试子路由 (iOS 端暂未接入 BOOK_SOURCE_DEBUG, 后续 KP5+)
            },
            onLogin = { item ->
                // 异步按 url 查 BookSource 完整记录 (BookSourcePart 是 DatabaseView 不含 header/loginUrl),
                // 设置 loginTarget, 末尾 SourceLoginDialog 渲染分支读取展示 (与 desktop line 347-353 一致)
                scope.launch {
                    loginTarget = AppDbProviders.get().bookSourceDao.getBookSource(item.bookSourceUrl)
                }
            },
            onDel = { item -> viewModel.del(listOf(item)) },
            onDelSelection = {
                // KP5: 加二次确认 (替换原直接删除), 设置 showDeleteConfirm=true, 末尾 AlertDialog 渲染
                // (与 desktop line 355-359 deleteSelectionTarget 模式一致; 确认回调里执行删除)
                showDeleteConfirm = true
            },
            onCancelCheckSource = {
                // TODO: 取消书源校验 (iOS 端暂未接入书源校验, 后续 KP5+)
            },
            onAddBookSource = {
                // KP5: 新建空 BookSource 并入库 (与 desktop line 369-380 一致);
                // bookSourceUrl 用 "new_" + 时间戳 保证唯一, 后续可由 onEdit 跳编辑页填充字段
                // (Kotlin/Native 无 System.currentTimeMillis, 用 NSDate.timeIntervalSince1970 等价)
                scope.launch {
                    val dao = AppDbProviders.get().bookSourceDao
                    val source = BookSource(
                        bookSourceName = newBookSourceLabel,
                        bookSourceUrl = "new_${NSDate().timeIntervalSince1970().toLong()}",
                    )
                    dao.insert(source)
                    AppLog.put(String.format(newBookSourceAddedLogTemplate, source.bookSourceUrl))
                }
            },
            onImportLocal = {
                // KP6: pickDocuments 选 JSON/文本 → 读字节转 UTF-8 文本 → importSource 解析比对
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
                // 弹 AlertDialog URL 输入 → 确认后 importVm.importSource(url) 触发下载/解析/比对,
                // 末尾 LaunchedEffect(importVm) 自动 importSelect 入库 (与 desktop onImportOnline 一致)
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            },
            onGroupManage = {
                // KP5: 触发 GroupManageDialog 显示 (末尾渲染分支读取 showGroupManage)
                showGroupManage = true
            },
            onHelp = {
                // KP5: 打开浏览器跳转书源帮助页 (与 desktop browseUrl 等价, URL 对齐 legado 官方 wiki)
                val url = "https://github.com/gedoor/legado/wiki/书源制作"
                openURL(url)
            },
            onSelectActions = {
                // 顺序对齐 app 端 BookSourceActivity.selectActions() (避免"入口位置变了"),
                // iOS 端 KP4 阶段接入 6 项核心批量操作, 其余 (加入/移出分组/导出/分享/校验) 留 TODO
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

    // 调用 shared/sharedUiMain 的 BookSourceListScreen (与 desktop BookSourceScreen 调用方式一致)
    BookSourceListScreen(
        state = state.value,
        callbacks = callbacks,
        listState = listState,
    )

    // ---- KP5: 书源登录对话框 (onLogin 触发, 调用 shared/sharedUiMain 下沉的 SourceLoginDialog) ----
    // 登录逻辑 (putLoginInfo + login JS) 由 shared SourceLoginDialog 内部处理, 与 app/desktop 一致;
    // iOS 仅注入 onOpenUrl 走系统浏览器 (与 desktop browseUrl 等价)
    loginTarget?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { loginTarget = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }

    // ---- KP5: 分组管理对话框 (onGroupManage 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // BookSource 分组是 String (逗号分隔), shared GroupManageDialog 期望 List<BookGroup>,
    // 用 groupEntities 做 String→BookGroup 适配; onAddGroup/onRenameGroup/onDeleteGroup
    // 委托 BookSourceListViewModel.addGroup/upGroup/delGroup (String 签名, 与 desktop line 583-599 一致)
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

    // ---- KP5: 删除选中确认对话框 (onDelSelection 触发, 替换原直接删除) ----
    // 用户确认后调 viewModel.del(selection) 并清空 selected; 取消则仅关闭对话框 (与 desktop line 496-515 一致)
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

    // ---- 书源编辑 Dialog (onEdit 触发, 包装 IosBookSourceEditScreen 全屏展示) ----
    // 对照 desktop onEditSource 路由; iOS 端用 Dialog 替代路由跳转,
    // IosBookSourceEditScreen 内部调 shared BookSourceEditScreen 渲染完整编辑表单;
    // onSaved 关闭 Dialog (列表由 flowSources 自动刷新), onDebugSource 切到调试 Dialog
    editTargetUrl?.let { url ->
        Dialog(
            onDismissRequest = { editTargetUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                IosBookSourceEditScreen(
                    sourceUrl = url,
                    onBack = { editTargetUrl = null },
                    onSaved = { _ -> editTargetUrl = null },
                    onDebugSource = { srcUrl ->
                        editTargetUrl = null
                        debugTargetUrl = srcUrl
                    },
                )
            }
        }
    }

    // ---- 书源调试 Dialog (onDebug 触发, 包装 IosBookSourceDebugScreen 全屏展示) ----
    // 对照 desktop onDebugSource 路由; iOS 端用 Dialog 替代路由跳转,
    // IosBookSourceDebugScreen 内部调 shared BookSourceDebugScreen 渲染调试输出
    debugTargetUrl?.let { url ->
        Dialog(
            onDismissRequest = { debugTargetUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                IosBookSourceDebugScreen(
                    sourceUrl = url,
                    onBack = { debugTargetUrl = null },
                )
            }
        }
    }

    // ---- 网络导入 URL 输入对话框 (onImportOnline 触发, 对照 desktop showImportOnlineDialog) ----
    // 用户确认后调 importVm.importSource(url) 触发下载 → 解析 → 比对,
    // 末尾 LaunchedEffect(importVm) 监听 success/error 自动 importSelect 入库 + toast
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
 * iOS 端不依赖 java.net.URL (Kotlin/Native 不可用), 用 Kotlin 标准库做简化解析:
 * 1. 去掉 scheme (`://` 之前部分)
 * 2. 取第一个 `/` 之前部分作为 host
 * 3. 取倒数两段拼成主域名 (与 desktop `getSourceHost` 行为一致)
 *
 * 与 desktop `BookSourceScreen.kt` 的 `getSourceHost` 等价 (仅解析实现不同, 输出一致)。
 */
private fun getSourceHost(origin: String): String {
    return try {
        // 简化实现: 取 scheme:// 后到第一个 / 之间的部分作为 host (跨平台, 不依赖 java.net.URL)
        val afterScheme = origin.substringAfter("://", origin)
        val host = afterScheme.substringBefore("/")
        val parts = host.split(".")
        if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    } catch (_: Exception) {
        "#"
    }
}
