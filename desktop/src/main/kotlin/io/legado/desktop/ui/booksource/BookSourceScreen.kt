package io.legado.desktop.ui.booksource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.model.script.ScriptException
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.source.BookSourceListCallbacks
import io.legado.app.ui.book.source.BookSourceListScreen
import io.legado.app.ui.book.source.BookSourceListState
import io.legado.app.ui.book.source.BookSourceListViewModel
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.SourceFilter
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.component.Md2TextField
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.toJson
import io.legado.desktop.ui.association.DesktopImportDialog
import io.legado.desktop.ui.association.ImportBookSourceVmAdapter
import io.legado.desktop.ui.association.ImportListScaffoldVm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import io.legado.desktop.ui.component.FileDialogs
import kotlin.math.min

/**
 * 桌面端书源管理入口 (plan P2: 调用 shared/commonMain 下沉的 [BookSourceListScreen])。
 *
 * 职责:
 * 1. 注入 desktop 平台 Provider (ThemeStore / AppConfig / EventBus), 让 commonMain 的
 *    [AppTheme] / [BookSourceListScreen] 可跨平台运行
 * 2. 持有 [BookSourceListViewModel] (KMP 版, 用 application scope) + [BookSourceListState]
 * 3. 收集 VM 的 flowSources/flowGroups, 更新 state
 * 4. 把 searchKey 字面串映射到 [SourceFilter] (解耦 commonMain 对 R.string 的依赖)
 * 5. 提供 [BookSourceListCallbacks] 实现, 核心数据操作 (启用/禁用/排序/删除/分组) 完整可用;
 *    导入本地/网络书源通过 java.awt.FileDialog + OkHttp 实现; 编辑/调试/登录/分组管理等
 *    依赖未下沉 Dialog 的动作暂为 no-op + TODO 注释
 *
 * @param onSearchBook 单项菜单"搜索书籍"回调, 由 DesktopApp 注入 (切到 SEARCH 路由)
 */
@Composable
fun BookSourceScreen(
    onSearchBook: (BookSourcePart) -> Unit = {},
    onDebugSource: (String) -> Unit = {},
    onEditSource: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            BookSourceListContent(onSearchBook = onSearchBook, onDebugSource = onDebugSource, onEditSource = onEditSource, onBack = onBack)
        }
    }
}

@Composable
private fun BookSourceListContent(
    onSearchBook: (BookSourcePart) -> Unit,
    onDebugSource: (String) -> Unit,
    onEditSource: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { BookSourceListViewModel(scope) }
    // 用 MutableState 引用持有, callbacks lambda 通过 .value 读写最新 state, 避免 callbacks 重组
    val state = remember { mutableStateOf(BookSourceListState()) }
    val listState = rememberLazyListState()

    // 过滤关键字 label (rememberString 是 @Composable, 顶层 remember 一次;
    // parseFilter 局部函数非 @Composable, 需预先缓存 label 后捕获)
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val enabledLabel = rememberString("enabled")
    val disabledLabel = rememberString("disabled")
    val needLoginLabel = rememberString("need_login")
    val noGroupLabel = rememberString("no_group")
    val enabledExploreLabel = rememberString("enabled_explore")
    val disabledExploreLabel = rememberString("disabled_explore")

    // 把 searchKey 字面串映射到 SourceFilter (与 jvmMain rememberString table 的中文值对齐)
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

    // 收集分组列表
    LaunchedEffect(Unit) {
        viewModel.flowGroups().collectLatest { groups ->
            state.value = state.value.copy(groups = groups)
        }
    }

    // 文案标签 (rememberString 是 @Composable, 顶层 remember 一次; onSelectActions lambda 非 @Composable, 需预先缓存)
    // key 对齐 app 端 BookSourceActivity.selectActions() 的 R.string.* (与 values-zh/strings.xml 一致)
    val enableSelectionLabel = rememberString("enable_selection")
    val disableSelectionLabel = rememberString("disable_selection")
    val addGroupLabel = rememberString("add_group")
    val removeGroupLabel = rememberString("remove_group")
    val groupNameLabel = rememberString("group_name")
    val enableExploreLabel = rememberString("enable_explore")
    val disableExploreLabel = rememberString("disable_explore")
    val selectionToTopLabel = rememberString("selection_to_top")
    val selectionToBottomLabel = rememberString("selection_to_bottom")
    val exportSelectionLabel = rememberString("export_selection")
    val shareSelectedSourceLabel = rememberString("share_selected_source")
    val checkSelectSourceLabel = rememberString("check_select_source")
    val checkSelectedIntervalLabel = rememberString("check_selected_interval")
    // 书源校验关键词输入对话框文案 (对应 app 端 BookSourceActivity.checkSource 的 alert;
    // search_book_key 用作对话框 title 与输入框 label, 与 app 端 alert title 一致)
    val searchBookKeyLabel = rememberString("search_book_key")
    // 删除确认对话框文案 (替换原 JOptionPane, 与 BookshelfManageScreen deleteTarget 模式一致)
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 导入/导出/新建书源文案 (suspend 函数非 @Composable, 需预先缓存传入)
    val newBookSourceLabel = rememberString("new_book_source")
    val selectBookSourceJsonLabel = rememberString("select_book_source_json")
    val inputBookSourceUrlLabel = rememberString("input_book_source_url")
    val netImportBookSourceLabel = rememberString("net_import_book_source")
    val exportBookSourceJsonLabel = rememberString("export_book_source_json")
    val jsonFileFilterLabel = rememberString("json_file_filter")
    // 删除选中确认对话框显示状态 (null=隐藏, 非空=显示; 替代原 JOptionPane.showConfirmDialog 同步阻塞)
    var deleteSelectionTarget by remember { mutableStateOf(false) }
    // 网络导入 URL 输入对话框状态 (替换原 JOptionPane.showInputDialog 同步阻塞,
    // 与 deleteSelectionTarget 模式一致; onImportOnline 触发显示,
    // 末尾 AlertDialog 渲染分支读取, 确认按钮调 importVm.importSource(url))
    var showImportOnlineDialog by remember { mutableStateOf(false) }
    var importOnlineUrlText by remember { mutableStateOf("") }
    // 导入 VM 适配器 (null=无导入任务, 非 null=渲染 DesktopImportDialog 让用户勾选比对);
    // 网络导入/本地文件导入均走 ImportBookSourceViewModelShared.importSource 路径
    // (URL 下载/JSON 解析/comparisonSource 比对), 成功后弹 DesktopImportDialog 让用户勾选
    // 选中项再 importSelect 入库 (含 keepName/Group/Enable 还原 + adjustSortNumber +
    // ContentProcessorProviders.upReplaceRules), 与 app 端 ImportBookSourceDialog 流程等价
    var importVm by remember { mutableStateOf<ImportListScaffoldVm?>(null) }
    // 导入初始文本 (URL 或 JSON), DesktopImportDialog 的 LaunchedEffect 用它调 vm.startImport
    var importInitialText by remember { mutableStateOf("") }
    // 导入对话框标题 (ImportListScaffold.title, 对照 app 端 getString(R.string.import_book_source))
    val importBookSourceLabel = rememberString("import_book_source")
    // Toasters/AppLog 文案 (BookSourceChecker / 顶层 suspend 函数非 @Composable, 用 jvmGetString)
    // 书源登录对话框状态 (null=隐藏, 非空=显示; onLogin 触发后异步按 url 查 BookSource
    // 完整记录填入, 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header)
    var loginTarget by remember { mutableStateOf<BookSource?>(null) }
    // 分组管理对话框状态 (false=隐藏; onGroupManage 触发, 末尾 GroupManageDialog 渲染)
    // BookSource 分组是 String (逗号分隔), shared GroupManageDialog 期望 List<BookGroup>,
    // 用 groupEntities 做 String → BookGroup 适配 (groupId 用递增索引, 避免与系统分组 <=0 冲突)
    var showGroupManage by remember { mutableStateOf(false) }
    val groupNames by produceState<List<String>>(emptyList()) {
        viewModel.flowGroups().collect { value = it }
    }
    val groupEntities = remember(groupNames) {
        groupNames.mapIndexed { index, name ->
            BookGroup(groupId = (index + 1).toLong(), groupName = name)
        }
    }
    // 选分组对话框状态 (addGroupLabel/removeGroupLabel SelectAction 触发;
    //   对应 app 端 selectionAddToGroups/selectionRemoveFromGroups 的 alert 输入分组名)
    // mode: null=隐藏, "add"=加入分组, "remove"=移出分组; 末尾 AlertDialog 渲染读取
    var groupActionMode by remember { mutableStateOf<String?>(null) }
    var groupActionText by remember { mutableStateOf("") }

    // ---- 书源校验状态 (对照 app 端 BookSourceActivity.checkSourceMsg/checkSourceVisible/checkTick) ----
    // 注入 BookSourceListState 的 checkSourceMsg/checkSourceVisible/checkTick 字段, 由
    // shared BookSourceListScreen 消费 (checkSourceVisible=true 显示进度条 + onCancel 按钮;
    // checkTick 变化触发 remember(checkTick) 重组刷新 Debug.debugMessageMap 文案)
    // desktop 端无 Android Service, 用 scope.launch + onEachParallel 限流并发校验;
    // 进度反馈: checkSourceMsg/checkTick 注入 state (UI 内进度显示, 桌面端不发系统通知) +
    // postEvent(EventBus.CHECK_SOURCE / CHECK_SOURCE_DONE) 通知其他组件
    var checkSourceMsg by remember { mutableStateOf<String?>(null) }
    var checkSourceVisible by remember { mutableStateOf(false) }
    var checkTick by remember { mutableStateOf(0) }
    // checkJob/checkRefreshJob 封装在 BookSourceChecker 内部管理 (见 val checker),
    // onCancelCheckSource 调 checker.cancel(), BookSourceListContent 无需直接持有 Job
    // 校验关键词输入对话框状态 (checkSelectSourceLabel SelectAction 触发;
    //   对应 app 端 BookSourceActivity.checkSource 的 alert, 确认后启动 startCheckSource)
    var showCheckSourceDialog by remember { mutableStateOf(false) }
    var checkKeywordText by remember { mutableStateOf(CheckSourceShared.keyword) }

    // ---- 书源校验编排器 (提取为顶层 BookSourceChecker 类, 避免 @Composable 函数内
    // suspend 局部函数互相引用报 Unresolved reference; Compose 编译器变换 @Composable
    // 函数后破坏 suspend 局部函数作用域, checkSourceImpl/doCheckSourceImpl 等互相调用
    // 时编译器找不到对方。BookSourceChecker 持有 scope + onMsg/onVisible/onTick 回调,
    // 内部管理 checkJob/checkRefreshJob, onCancelCheckSource 调 checker.cancel()) ----
    val checker = remember(scope) {
        BookSourceChecker(
            scope = scope,
            onMsg = { checkSourceMsg = it },
            onVisible = { checkSourceVisible = it },
            onTick = { checkTick++ },
        )
    }

    // callbacks 用 remember 持有稳定实例, lambda 捕获 state (MutableState) 引用, 不触发重组
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
                onEditSource(item.bookSourceUrl)
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
                onDebugSource(item.bookSourceUrl)
            },
            onLogin = { item ->
                // BookSourcePart 是 DatabaseView 不含 header/loginUrl 等字段, 需按 url 查完整 BookSource;
                // 查到后设置 loginTarget, 末尾 SourceLoginDialog 渲染分支读取展示
                scope.launch {
                    loginTarget = AppDbProviders.get().bookSourceDao.getBookSource(item.bookSourceUrl)
                }
            },
            onDel = { item -> viewModel.del(listOf(item)) },
            onDelSelection = {
                // 二次确认: 弹 AlertDialog (替换原 JOptionPane.showConfirmDialog 同步阻塞,
                // 与 BookshelfManageScreen deleteTarget 模式一致; 确认回调里执行删除)
                deleteSelectionTarget = true
            },
            onCancelCheckSource = {
                // 取消书源校验 (对照 app 端 BookSourceActivity.cancelCheckSource:
                //   CheckSource.stop(context) + Debug.finishChecking(); desktop 端无 Service,
                //   直接 cancel 协程 Job + 清空 UI 状态 + 取消系统托盘通知)
                checker.cancel()
                checkSourceVisible = false
                checkSourceMsg = null
                Toasters.get().toast(jvmGetString("check_source_cancelled"))
            },
            onAddBookSource = {
                // 新建空 BookSource 并入库, 后续可由 onEdit 跳编辑页填充字段
                scope.launch {
                    val dao = AppDbProviders.get().bookSourceDao
                    val source = BookSource(
                        bookSourceName = newBookSourceLabel,
                        bookSourceUrl = "new_${System.currentTimeMillis()}",
                    )
                    dao.insert(source)
                    AppLog.put(jvmGetString("new_book_source_added", source.bookSourceUrl))
                }
            },
            onImportLocal = {
                // 弹 FileDialog 选 JSON 文件 → 读文本 → ImportBookSourceViewModelShared.importSource
                // → 弹 DesktopImportDialog 让用户勾选比对 (新增/更新/已有) 后 importSelect 入库
                // (与 app 端 ImportBookSourceDialog 完整流程等价, 不再简化为 fromJsonArray 直接入库;
                //  local JSON 走 vm.importSource 路径以获得"新增/更新/已有"比对, 与网络导入一致)
                scope.launch {
                    val json = importBookSourcesFromLocalFile(selectBookSourceJsonLabel) ?: return@launch
                    val vm = ImportBookSourceVmAdapter(ImportBookSourceViewModelShared(scope))
                    importInitialText = json
                    importVm = vm
                    // startImport 由 DesktopImportDialog 的 LaunchedEffect(vm) 触发
                }
            },
            onImportOnline = {
                // 弹 AlertDialog URL 输入 → 用户确认后调 ImportBookSourceViewModelShared.importSource
                // (替换原 javax.swing.JOptionPane.showInputDialog; 仅触发对话框显示,
                //  确认按钮回调里新建 importVm 并调 vm.importSource(url) 触发完整导入流程)
                importOnlineUrlText = ""
                showImportOnlineDialog = true
            },
            onGroupManage = {
                // 触发 GroupManageDialog 显示 (末尾渲染分支读取 showGroupManage)
                showGroupManage = true
            },
            onHelp = {
                // 打开浏览器跳转书源帮助页 (legado 官方 wiki)
                browseUrl("https://github.com/gedoor/legado/wiki/书源制作")
            },
            onSelectActions = {
                // 顺序对齐 app 端 BookSourceActivity.selectActions() 12 项批量操作
                // (避免"入口位置变了" / "菜单项变了"), 桌面端 6 项 no-op 保留位置待 Dialog 下沉
                val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
                listOf(
                    SelectAction(enableSelectionLabel) { viewModel.enableSelection(selection) },
                    SelectAction(disableSelectionLabel) { viewModel.disableSelection(selection) },
                    SelectAction(addGroupLabel) {
                        // 弹输入分组名对话框 (对应 app 端 selectionAddToGroups 的 alert),
                        // 确认后调 viewModel.selectionAddToGroups(selection, groupName) 把选中书源加入该分组
                        groupActionText = ""
                        groupActionMode = "add"
                    },
                    SelectAction(removeGroupLabel) {
                        // 弹输入分组名对话框 (对应 app 端 selectionRemoveFromGroups 的 alert),
                        // 确认后调 viewModel.selectionRemoveFromGroups(selection, groupName) 把选中书源移出该分组
                        groupActionText = ""
                        groupActionMode = "remove"
                    },
                    SelectAction(enableExploreLabel) { viewModel.enableSelectExplore(selection) },
                    SelectAction(disableExploreLabel) { viewModel.disableSelectExplore(selection) },
                    SelectAction(selectionToTopLabel) { viewModel.topSource(*selection.toTypedArray()) },
                    SelectAction(selectionToBottomLabel) { viewModel.bottomSource(*selection.toTypedArray()) },
                    SelectAction(exportSelectionLabel) {
                        scope.launch { exportBookSourcesToFile(selection, exportBookSourceJsonLabel, jsonFileFilterLabel) }
                    },
                    SelectAction(shareSelectedSourceLabel) {
                        scope.launch { shareBookSourcesToClipboard(selection) }
                    },
                    SelectAction(checkSelectSourceLabel) {
                        // 批量校验选中书源 (对照 app 端 BookSourceActivity.checkSource 的 alert;
                        // desktop 端弹 AlertDialog 输入校验关键词, 确认后 startCheckSource 并发校验。
                        // 未选中书源时 toast 提示, 与 app 端 selection() 为空时静默不同, 桌面端给反馈)
                        val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
                        if (selection.isEmpty()) {
                            Toasters.get().toast(jvmGetString("no_source_selected"))
                            return@SelectAction
                        }
                        checkKeywordText = CheckSourceShared.keyword
                        showCheckSourceDialog = true
                    },
                    SelectAction(checkSelectedIntervalLabel) {
                        // 补选已选区间内的全部条目 (对照 app 端 BookSourceActivity.checkSelectedInterval;
                        // 此项非"间隔校验", 是 selection 扩展操作: 取已选书源的最小/最大 index,
                        // 选中该闭区间内全部 bookSourceUrl。app 端注释 "复刻 adapter.checkSelectedInterval"
                        // 已说明语义为"补选已选区间内的全部条目")
                        val positions = state.value.sources.mapIndexedNotNull { index, part ->
                            index.takeIf { state.value.selected.contains(part.bookSourceUrl) }
                        }
                        if (positions.isEmpty()) return@SelectAction
                        val range = positions.min()..positions.max()
                        state.value = state.value.copy(
                            selected = state.value.selected + range.map { state.value.sources[it].bookSourceUrl }.toSet()
                        )
                    },
                )
            },
            getSourceHost = ::getSourceHost,
        )
    }

    BookSourceListScreen(
        // 注入校验状态 (checkSourceMsg/checkSourceVisible/checkTick) 到 shared BookSourceListScreen,
        // 由其消费: checkSourceVisible=true 显示进度条 + onCancel 按钮 (行 123-126);
        // checkTick 变化触发 remember(state.checkTick) 重组刷新 Debug.debugMessageMap 文案 (行 84)
        state = state.value.copy(
            checkSourceMsg = checkSourceMsg,
            checkSourceVisible = checkSourceVisible,
            checkTick = checkTick,
        ),
        callbacks = callbacks,
        listState = listState,
    )

    // ---- 导入对话框 (网络导入/本地文件导入共用, importVm 非 null 时渲染 DesktopImportDialog
    // 让用户勾选"新增/更新/已有"项后确定 importSelect 入库, 与 app 端 ImportBookSourceDialog
    // 流程等价; 取消/确定均触发 onDismiss → importVm=null 关闭 Dialog;
    // 导入完成后 BookSourceListScreen 的 LaunchedEffect(flowSources) 会自动刷新列表, 无需 toast) ----
    importVm?.let { vm ->
        DesktopImportDialog(
            title = importBookSourceLabel,
            vm = vm,
            initialText = importInitialText,
            onDismiss = { importVm = null },
        )
    }

    // ---- 删除选中确认对话框 (onDelSelection 触发, 替换原 JOptionPane.showConfirmDialog) ----
    if (deleteSelectionTarget) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { deleteSelectionTarget = false },
            title = { Text(deleteLabel) },
            text = { Text(sureDelLabel) },
            confirmButton = {
                TextButton(onClick = {
                    deleteSelectionTarget = false
                    viewModel.del(state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) })
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelectionTarget = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // ---- 网络导入 URL 输入对话框 (onImportOnline 触发, 替换原 JOptionPane.showInputDialog) ----
    // 用户确认后新建 ImportBookSourceViewModelShared 实例并 importSource(url) 触发
    // 下载 → 解析 (URL/JSON/sourceUrls 数组/OldRssSource) → comparisonSource 比对 → success;
    // 末尾 LaunchedEffect 监听 success/error, 自动 importSelect 全部入库 (与 app 端完整流程等价);
    // inputBookSourceUrlLabel 用作 OutlinedTextField label, netImportBookSourceLabel 用作 title
    if (showImportOnlineDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportOnlineDialog = false },
            title = { Text(netImportBookSourceLabel) },
            text = {
                Md2TextField(
                    value = importOnlineUrlText,
                    onValueChange = { importOnlineUrlText = it },
                    // fillMaxWidth 让输入框占满对话框宽度 (修复用户反馈"输入框无法自动跟到窗口宽度, 会被截断");
                    // 不加的话 OutlinedTextField 默认 widthIn(min=280dp), 在 0.8 窗口宽度的对话框中只占左侧一部分
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
                        // 新建适配器 (包装 ImportBookSourceViewModelShared, 避免 success/error state 残留);
                        // 设置 importInitialText + importVm 触发 DesktopImportDialog 渲染,
                        // Dialog 的 LaunchedEffect(vm) 会调 vm.startImport(url) 触发下载 → 解析 →
                        // comparisonSource 比对, 成功后让用户勾选"新增/更新/已有"项再 importSelect 入库
                        // (与 app 端 ImportBookSourceDialog 流程等价, 不再自动 importSelect 全部入库)
                        val vm = ImportBookSourceVmAdapter(ImportBookSourceViewModelShared(scope))
                        importInitialText = url
                        importVm = vm
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

    // ---- 书源登录对话框 (onLogin 触发, 调用 shared/commonMain 下沉的 SourceLoginDialog) ----
    // 登录逻辑由 shared SourceLoginDialog 内部处理 (putLoginInfo + login JS),
    // 与 app 端一致; 桌面端仅需注入 onOpenUrl 回调
    loginTarget?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { loginTarget = null },
            onOpenUrl = { url -> browseUrl(url) },
        )
    }

    // ---- 分组管理对话框 (onGroupManage 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // BookSource 分组是 String (逗号分隔), shared GroupManageDialog 期望 List<BookGroup>,
    // 用 groupEntities 做 String → BookGroup 适配; onAddGroup/onRenameGroup/onDeleteGroup
    // 委托 BookSourceListViewModel.addGroup/upGroup/delGroup (String 签名)
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

    // ---- 选分组对话框 (addGroupLabel/removeGroupLabel SelectAction 触发) ----
    // 对应 app 端 selectionAddToGroups/selectionRemoveFromGroups 的 alert (输入分组名);
    // 确认后调 viewModel.selectionAddToGroups/selectionRemoveFromGroups(selection, groupName)
    groupActionMode?.let { mode ->
        val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { groupActionMode = null },
            title = { Text(if (mode == "add") addGroupLabel else removeGroupLabel) },
            text = {
                Md2TextField(
                    value = groupActionText,
                    onValueChange = { groupActionText = it },
                    // fillMaxWidth 让输入框占满对话框宽度 (修复用户反馈"输入框无法自动跟到窗口宽度, 会被截断");
                    // 与 showImportOnlineDialog 的 URL 输入框保持一致
                    modifier = Modifier.fillMaxWidth(),
                    label = groupNameLabel,
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val groupName = groupActionText
                    groupActionMode = null
                    if (groupName.isNotEmpty()) {
                        if (mode == "add") {
                            viewModel.selectionAddToGroups(selection, groupName)
                        } else {
                            viewModel.selectionRemoveFromGroups(selection, groupName)
                        }
                    }
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { groupActionMode = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // ---- 书源校验关键词输入对话框 (checkSelectSourceLabel SelectAction 触发,
    // 对应 app 端 BookSourceActivity.checkSource 的 alert; 确认后启动 startCheckSource) ----
    // app 端 alert 含 neutralButton "校验设置" 打开 CheckSourceConfig Dialog (未下沉, desktop 端
    // 仅保留关键词输入 + okButton 启动校验; CheckSourceShared 配置可通过 OtherConfigScreen 修改)
    // 关键词非空时回写 CheckSourceShared.keyword (与 app 端 getText().let { if (it.isNotEmpty()) CheckSource.keyword = it } 一致),
    // 空则用默认 keyword (CheckSourceShared.keyword 当前值), 与 app 端空串不覆盖行为一致
    if (showCheckSourceDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showCheckSourceDialog = false },
            title = { Text(searchBookKeyLabel) },
            text = {
                Md2TextField(
                    value = checkKeywordText,
                    onValueChange = { checkKeywordText = it },
                    // fillMaxWidth 让输入框占满对话框宽度 (与 showImportOnlineDialog / groupActionMode 对话框一致)
                    modifier = Modifier.fillMaxWidth(),
                    label = searchBookKeyLabel,
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val keyword = checkKeywordText
                    showCheckSourceDialog = false
                    if (keyword.isNotEmpty()) {
                        CheckSourceShared.keyword = keyword
                    }
                    val selection = state.value.sources.filter { state.value.selected.contains(it.bookSourceUrl) }
                    if (selection.isNotEmpty()) {
                        // checker.startCheckSource 是 BookSourceChecker 的方法, checker 通过
                        // onMsg/onVisible/onTick 回调修改 checkSourceMsg/checkSourceVisible/checkTick
                        // 这些 state (delegated property 的 setter 反映到外部)
                        checker.startCheckSource(selection)
                    }
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showCheckSourceDialog = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

/**
 * 书源校验编排器 (desktop 端无 Android Service, 用协程模拟 CheckSourceService)。
 *
 * 从 BookSourceListContent 提取为顶层类, 避免 @Composable 函数内 suspend 局部函数
 * 互相引用报 Unresolved reference (Compose 编译器变换 @Composable 函数后, suspend
 * 局部函数的作用域被破坏, checkSourceImpl/doCheckSourceImpl/checkBookImpl/firstExploreUrl
 * 互相调用时编译器找不到对方)。
 *
 * 职责对照 app 端 CheckSourceService:
 * - [startCheckSource] = check + checkSource + doCheckSource + checkBook 编排
 * - [cancel] = stop + finishChecking
 * - [checkSourceImpl] = checkSource (单源校验 + 超时/异常分类)
 * - [doCheckSourceImpl] = doCheckSource (搜索/发现/详情/目录/正文校验)
 * - [checkBookImpl] = checkBook (详情/目录/正文校验)
 * - [firstExploreUrl] = exploreKinds 简化版 (取第一个发现分类 URL)
 *
 * @param scope 主线程 CoroutineScope (rememberCoroutineScope), 用于切回主线程更新 Compose state
 * @param onMsg 更新 checkSourceMsg (BookSourceListContent 的 mutableStateOf)
 * @param onVisible 更新 checkSourceVisible
 * @param onTick 自增 checkTick (触发 BookSourceListScreen remember(checkTick) 重组刷新文案)
 */
private class BookSourceChecker(
    private val scope: CoroutineScope,
    private val onMsg: (String?) -> Unit,
    private val onVisible: (Boolean) -> Unit,
    private val onTick: () -> Unit,
) {
    private var checkJob: Job? = null
    private var checkRefreshJob: Job? = null

    /** 是否正在校验 (onCancelCheckSource 前置检查) */
    val isRunning: Boolean get() = checkJob?.isActive == true

    /**
     * 启动书源校验 (对照 app 端 CheckSourceService.check + BookSourceActivity.checkSource)。
     *
     * @param selection 待校验书源 (BookSourcePart, 按 bookSourceUrl 查完整 BookSource 后校验;
     *   BookSourcePart 是 DatabaseView 字段不全, 需 getBookSource 取完整记录, 与 app 端 check 一致)
     */
    fun startCheckSource(selection: List<BookSourcePart>) {
        if (checkJob?.isActive == true) {
            Toasters.get().toast(jvmGetString("check_source_in_progress"))
            return
        }
        val total = selection.size
        val finishCount = AtomicInteger(0)
        // 限流线程池 (对照 app 端 CheckSourceService.searchCoroutine, 池大小 = min(threadCount, MAX_THREAD))
        val threadCount = min(AppConfigProviders.get().threadCount, AppConst.MAX_THREAD)
        val pool = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
        checkJob = scope.launch(pool) {
            flow {
                for (part in selection) {
                    AppDbProviders.get().bookSourceDao.getBookSource(part.bookSourceUrl)?.let { emit(it) }
                }
            }.onStart {
                val msg = jvmGetString("check_source_progress_msg", 0, total, "").trim()
                // scope.launch 切回主线程修改 state (mutableStateOf 非主线程修改可能导致重组时机不确定)
                scope.launch { onMsg(msg); onVisible(true) }
                postEvent(EventBus.CHECK_SOURCE, msg)
            }.onEachParallel(threadCount) { source ->
                checkSourceImpl(source)
            }.onEach {
                val count = finishCount.incrementAndGet()
                val msg = jvmGetString("check_source_progress_msg", count, total, it.bookSourceName)
                scope.launch { onMsg(msg); onTick() }
                postEvent(EventBus.CHECK_SOURCE, msg)
                // 校验后写回 DB (bookSourceGroup/respondTime/bookSourceComment 等字段已修改)
                AppDbProviders.get().bookSourceDao.update(it)
            }.onCompletion {
                // scope.launch 切回主线程修改 state (避免在 pool 线程直接改 mutableStateOf)
                scope.launch { onVisible(false); onTick() }
                postEvent(EventBus.CHECK_SOURCE_DONE, 0)
                Debug.finishChecking()
                pool.close()
                if (it == null) Toasters.get().toast(jvmGetString("check_source_completed"))
            }.catch {
                AppLog.put(jvmGetString("check_source_error", it.localizedMessage), it)
            }.collect()
        }
        // 300ms 刷新 Job (对照 app 端 startCheckMessageRefreshJob), 让 BookSourceListScreen
        // 行 84 remember(state.checkTick) 周期重组刷新 Debug.debugMessageMap 文案
        checkRefreshJob?.cancel()
        checkRefreshJob = scope.launch {
            while (isActive && Debug.isChecking) {
                onTick()
                delay(300L)
            }
        }
    }

    /**
     * 取消校验 (对照 app 端 BookSourceActivity.cancelCheckSource:
     * CheckSource.stop(context) + Debug.finishChecking())。
     */
    fun cancel() {
        checkJob?.cancel()
        checkJob = null
        checkRefreshJob?.cancel()
        checkRefreshJob = null
        Debug.finishChecking()
    }

    /**
     * 校验单个书源 (对照 app 端 CheckSourceService.checkSource)。
     *
     * 流程: withTimeout(timeout) 包装 doCheckSource, 成功 updateFinalMessage("校验成功"),
     * 失败按异常类型 addGroup("校验超时"/"js失效"/"网站失效") + addErrorComment + updateFinalMessage("校验失败:...");
     * 最后记录 respondTime (Debug.getRespondTime, 成功用耗时, 失败用 timeout+耗时惩罚)。
     */
    private suspend fun checkSourceImpl(source: BookSource) {
        kotlin.runCatching {
            withTimeout(CheckSourceShared.timeout) {
                doCheckSourceImpl(source)
            }
        }.onSuccess {
            Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
        }.onFailure {
            currentCoroutineContext().ensureActive()
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            source.addErrorComment(it)
            Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:${it.localizedMessage}")
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    /**
     * 执行书源校验 (对照 app 端 CheckSourceService.doCheckSource)。
     *
     * 流程: Debug.startChecking → removeInvalidGroups/removeErrorComment 清理旧标记 →
     * 校验搜索 (checkSearch + getCheckKeyword + WebBook.getBookListAwait) →
     * 校验发现 (checkDiscovery + firstExploreUrl + WebBook.getBookListAwait) →
     * 检查 getInvalidGroupNames 非空则抛 NoStackTraceException (汇总失效分组)。
     */
    private suspend fun doCheckSourceImpl(source: BookSource) {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        source.removeErrorComment()
        // 校验搜索书籍
        if (CheckSourceShared.checkSearch) {
            val searchWord = source.getCheckKeyword(CheckSourceShared.keyword)
            if (!source.searchUrl.isNullOrBlank()) {
                source.removeGroup("搜索链接规则为空")
                val searchBooks = WebBook.getBookListAwait(source, searchWord).books
                if (searchBooks.isEmpty()) {
                    source.addGroup("搜索失效")
                } else {
                    source.removeGroup("搜索失效")
                    checkBookImpl(searchBooks.first().toBook(), source)
                }
            } else {
                source.addGroup("搜索链接规则为空")
            }
        }
        // 校验发现书籍
        if (CheckSourceShared.checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
            val url = firstExploreUrl(source)
            if (url.isNullOrBlank()) {
                source.addGroup("发现规则为空")
            } else {
                source.removeGroup("发现规则为空")
                val exploreBooks = WebBook.getBookListAwait(source, url, isSearch = false).books
                if (exploreBooks.isEmpty()) {
                    source.addGroup("发现失效")
                } else {
                    source.removeGroup("发现失效")
                    checkBookImpl(exploreBooks.first().toBook(), source, false)
                }
            }
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    /**
     * 校验书源的详情/目录/正文 (对照 app 端 CheckSourceService.checkBook)。
     *
     * @param isSearchBook true=来自搜索, false=来自发现 (用于 addGroup 区分"搜索正文失效"/"发现正文失效")
     */
    private suspend fun checkBookImpl(book: Book, source: BookSource, isSearchBook: Boolean = true) {
        kotlin.runCatching {
            if (!CheckSourceShared.checkInfo) {
                return
            }
            // 校验详情
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSourceShared.checkCategory || source.bookSourceType == BookSourceType.file) {
                return
            }
            // 校验目录 (取前 2 章用于正文 nextChapterUrl)
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSourceShared.checkContent) {
                return
            }
            // 校验正文
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
    }

    /**
     * 取第一个发现分类 URL (简化版 exploreKinds, 对照 app 端 BookSource.exploreKinds() 扩展)。
     *
     * app 端 exploreKinds 依赖 ACache + runScriptWithContext (JS 执行), desktop 端未注册
     * ExploreKindsCacheProvider, 对 JS 开头的 exploreUrl 无法执行; 此处仅做文本/JSON 解析:
     * - JSON 数组: 用 GSON.fromJsonArray<ExploreKind> 解析取第一个非空 url
     * - 文本格式 (title::url 多行, && 或 \n 分隔): split 后取第二段 url
     *
     * 不执行 JS 是 desktop 端固有限制 (与 ExploreScreen.exploreKindsDesktop 一致),
     * JS 发现规则的书源会跳过发现校验 (返回 null → addGroup("发现规则为空"))。
     */
    private fun firstExploreUrl(source: BookSource): String? {
        val exploreUrl = source.exploreUrl ?: return null
        return runCatching {
            if (exploreUrl.isJsonArray()) {
                GSON.fromJsonArray<ExploreKind>(exploreUrl).getOrDefault(emptyList())
                    .firstOrNull { !it.url.isNullOrBlank() }?.url
            } else {
                exploreUrl.split("(&&|\n)+".toRegex())
                    .mapNotNull { kindStr ->
                        val parts = kindStr.split("::")
                        parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    }
                    .firstOrNull()
            }
        }.getOrNull()
    }
}

/**
 * 简化版域名提取 (对应 app 端 NetworkUtils.getSubDomainOrNull)。
 *
 * 从 URL 提取主域名 (倒数第二段 + 顶级域), 如 `https://www.example.com/path` -> `example.com`;
 * 解析失败返回 `"#"`, 与 app 端 fallback 一致 (用于按域名分组时排在最后)。
 */
private fun getSourceHost(origin: String): String {
    return try {
        val host = URL(origin).host
        val parts = host.split(".")
        if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    } catch (_: Exception) {
        "#"
    }
}

/**
 * 从本地 JSON 文件读取书源文本 (对应 app 端 importBookSource 文件选择 + 读取)。
 *
 * 仅负责选文件 + 读文本, 不再解析+入库 (交给 ImportBookSourceViewModelShared.importSource
 * 走完整比对流程, 让用户在 DesktopImportDialog 勾选"新增/更新/已有"后 importSelect 入库)。
 *
 * 流程:
 * 1. 弹 [FileDialog] (LOAD 模式, 过滤 .json) 选择文件
 * 2. 读文件内容为 String (UTF-8) 返回给调用方
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 返回 null 让调用方跳过。
 *
 * @return JSON 文本, 用户取消/IO 错时返回 null
 */
private suspend fun importBookSourcesFromLocalFile(dialogTitle: String): String? {
    val json = withContext(Dispatchers.IO) {
        val dialog = FileDialog(Frame(), dialogTitle, FileDialog.LOAD)
        dialog.setFile("*.json")
        dialog.isVisible = true
        val file = dialog.files?.firstOrNull() ?: return@withContext null
        file.readText()
    } ?: run {
        AppLog.put(jvmGetString("import_source_cancelled"))
        return null
    }
    return json
}

/**
 * 导出选中书源到本地 JSON 文件 (对应 app 端 exportBookSource)。
 *
 * 流程:
 * 1. 取选中项 url 列表 → [BookSourceDao.getBookSources] 取完整 BookSource 列表
 *    (BookSourcePart 是 DatabaseView, 字段不全, 需取完整 BookSource 再序列化)
 * 2. 用 [GSON.toJson] 序列化为 JSON 字符串
 * 3. 弹 [JFileChooser] (SAVE 模式, 过滤 .json) 选择保存路径
 * 4. 写文件 (UTF-8)
 *
 * 任何一步失败 (用户取消/IO 错) 都打印日志但不抛异常, 不中断主流程。
 */
private suspend fun exportBookSourcesToFile(selection: List<BookSourcePart>, dialogTitle: String, fileFilterDesc: String) {
    if (selection.isEmpty()) {
        AppLog.put(jvmGetString("export_source_no_selection"))
        return
    }
    val sources = withContext(Dispatchers.IO) {
        AppDbProviders.get().bookSourceDao.getBookSources(selection.map { it.bookSourceUrl })
    }
    if (sources.isEmpty()) {
        AppLog.put(jvmGetString("export_source_db_empty"))
        return
    }
    val json = GSON.toJson(sources)
    val file = withContext(Dispatchers.IO) {
        FileDialogs.pickSaveFile(
            title = dialogTitle,
            defaultName = "bookSource_${System.currentTimeMillis()}.json",
            extensions = listOf("json"),
        )
    } ?: run {
        AppLog.put(jvmGetString("export_source_cancelled"))
        return
    }
    runCatching {
        withContext(Dispatchers.IO) {
            file.writeText(json)
        }
        AppLog.put(jvmGetString("export_source_done", sources.size, file.absolutePath))
    }.onFailure {
        AppLog.put(jvmGetString("export_source_write_failed"), it)
    }
}

/**
 * 分享选中书源到剪贴板 (对应 app 端分享书源 JSON)。
 *
 * 桌面端简化: 直接把 JSON 复制到系统剪贴板 (app 端走 ACTION_SEND Intent),
 * 用户可粘贴到任意文本编辑器/聊天工具。
 *
 * 流程:
 * 1. 取选中项 url 列表 → [BookSourceDao.getBookSources] 取完整 BookSource 列表
 * 2. 用 [GSON.toJson] 序列化为 JSON 字符串
 * 3. 用 [Toolkit.getDefaultToolkit].systemClipboard + [StringSelection] 复制到剪贴板
 */
private suspend fun shareBookSourcesToClipboard(selection: List<BookSourcePart>) {
    if (selection.isEmpty()) {
        AppLog.put(jvmGetString("share_source_no_selection"))
        return
    }
    val sources = withContext(Dispatchers.IO) {
        AppDbProviders.get().bookSourceDao.getBookSources(selection.map { it.bookSourceUrl })
    }
    if (sources.isEmpty()) {
        AppLog.put(jvmGetString("share_source_db_empty"))
        return
    }
    val json = GSON.toJson(sources)
    withContext(Dispatchers.IO) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(json), null)
    }
    AppLog.put(jvmGetString("share_source_copied", sources.size))
}
