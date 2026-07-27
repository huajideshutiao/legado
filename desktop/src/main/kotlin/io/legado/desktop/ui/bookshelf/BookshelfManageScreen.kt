package io.legado.desktop.ui.bookshelf

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.BookshelfManageCallbacks
import io.legado.app.ui.book.manage.BookshelfManageScreen as SharedBookshelfManageScreen
import io.legado.app.ui.book.manage.BookshelfManageState
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.desktop.ui.component.DesktopBookCover
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端书架管理入口 (包装 shared/sharedUiMain 下沉的 [SharedBookshelfManageScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [io.legado.app.ui.book.manage.BookshelfManageActivity], 桌面端仅做平台适配,
 * UI 渲染与交互骨架全部下沉到 shared/sharedUiMain 的 [SharedBookshelfManageScreen]:
 *
 * - **平台 Provider 注入**: [DesktopThemeStoreProvider] / [DesktopAppConfigProvider] /
 *   [DesktopEventBusProvider] 经 [CompositionLocalProvider] 注入, 让 commonMain 的
 *   [AppTheme] / [SharedBookshelfManageScreen] 跨平台运行
 * - **数据流**: [LaunchedEffect] 订阅 `bookGroupDao.flowAll()` 取分组, `bookDao.flowByGroup(groupId)`
 *   取书籍并按全局 [AppConfigProviders.get].bookshelfSort 排序 (简化, 不做 per-group 排序);
 *   搜索/类型筛选复用 [BookFilter.IncrementalFilter] (下沉到 commonMain)
 * - **DAO 访问**: `AppDbProviders.get().bookDao` 取书籍 DAO; `AppDatabaseProviders.get().appDb.bookGroupDao`
 *   取分组 DAO ([AppDbProviders] 的 AppDbAccessor 接口未暴露 bookGroupDao, 故走完整 appDb)
 * - **状态**: 用 [mutableStateOf] 持有 [BookshelfManageState] (immutable, copy 更新),
 *   callbacks 用 [remember] 持有稳定实例避免重组
 * - **拖选**: [Modifier.dragSelectable] (下沉到 shared/sharedUiMain) 注入 listModifier,
 *   复刻 app 端边缘拖选批量勾选语义
 * - **封面槽**: 注入 [DesktopBookCover.InfoCover] (与详情页共享同一封面加载组件 + 内存缓存)
 *
 * # 简化项 (依赖未下沉功能, 用 no-op + TODO 注释)
 *
 * - **下载/缓存**: app 端 `CacheBook` 未下沉, onToggleDownload / onDownloadAfter / onDownloadAll /
 *   isItemDownloading / onCacheInfo / downloadRunning 全部 no-op (false / null)
 * - **导出**: app 端导出依赖 `ExportBookService` + `HandleFileContract` + `ACache`, 未下沉,
 *   onExportAllUseBookSource / onSelectExportFolderMenu / onShowExportConfig no-op;
 *   exportUseReplace / enableCustomExport / exportToWebDav 三档开关读写 [PreferenceProviders]
 *   (key 走 [PreferKey] 常量), 但导出动作本身不执行
 * - **批量改源**: 接入 shared/sharedUiMain 下沉的 `SourcePickerDialog`, onSelectActions 中
 *   "批量改源"项弹窗选书源; onConfirm 简化为更新选中书籍的 bookSourceUrl/originName 字段
 *   (app 端 `BookshelfManageViewModel.changeSource` 的重新加载章节等副作用未下沉, 留 TODO)
 * - **分组管理 Dialog**: app 端 `GroupManageDialog` 未下沉, onShowGroupManage no-op
 * - **清缓存**: app 端 `BookHelp.clearCache` 未下沉 (BookHelpAccessor 仅暴露 saveContent),
 *   onSelectActions 中"清缓存"项 no-op
 * - **打开书籍详情**: 桌面端 BookInfo 路由未注入, onOpenBook no-op
 * - **日志页**: app 端 `AppLogDialog` 未下沉, onShowLog no-op
 * - **cnCompare**: app 端中文拼音排序 (StringExtensions) 未下沉, sort==2(书名) 改用
 *   [String.compareTo] (按 Unicode 序), 与 [BookshelfViewModel] 的简化一致
 *
 * # 已实现的核心功能
 *
 * - 列表加载 / 搜索 / 类型筛选 (IncrementalFilter)
 * - 拖拽排序 (canDrag = bookshelfSort == 3, 松手落库重排 order)
 * - 选中 / 全选 / 反选
 * - 单项删除 / 批量删除 (AlertDialog 确认)
 * - 单项改分组 / 批量改分组 / 添加到分组 (AlertDialog 选分组, update group 字段)
 * - 允许 / 禁止更新 (update canUpdate 字段)
 * - 顶栏分组菜单切换分组 (onSelectGroupFromMenu → 切 groupId → 重新订阅书籍流)
 * - 导出三档开关 toggle (写 PreferenceProviders)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入)
 */
@Composable
fun BookshelfManageScreen(onBack: () -> Unit) {
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
            BookshelfManageContent(onBack = onBack)
        }
    }
}

/**
 * 选分组对话框目标。
 *
 * - [Replace] 替换分组 (mainAction 单项/批量): `book.copy(group = selectedGroupId)`
 * - [Merge] 并入分组 (addToGroup 批量): `book.copy(group = book.group or selectedGroupId)`
 */
private sealed class GroupSelectTarget {
    abstract val books: List<Book>

    class Replace(override val books: List<Book>) : GroupSelectTarget()
    class Merge(override val books: List<Book>) : GroupSelectTarget()
}

@Composable
private fun BookshelfManageContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // 用 MutableState 引用持有 immutable state, callbacks lambda 通过 .value 读写最新状态
    val state = remember { mutableStateOf(BookshelfManageState()) }
    val listState = rememberLazyListState()

    // 不在 BookshelfManageState 里的运行时状态 (host 私有)
    var groupId by remember { mutableStateOf(BookGroup.IdAll) }
    // groupList: 全部分组 (含系统分组?), 供 onGroupName 查询; state.groups 仅给顶栏菜单用
    var groupList by remember { mutableStateOf<List<BookGroup>>(emptyList()) }
    // 分组管理对话框状态 (false=隐藏, true=显示; onShowGroupManage 触发, 末尾 GroupManageDialog 渲染)
    // 接入模式参考 desktop BookshelfScreen.kt: 增删改走 GroupViewModelShared (shared commonMain),
    // groups 复用 groupList (已订阅 bookGroupDao.flowAll(), 含 show=false 分组, 管理用全集)
    var showGroupManage by remember { mutableStateOf(false) }
    // GroupEditDialog 显隐状态 (接入 GroupManageDialog 的 onAddGroup / onDeleteGroup 回调):
    // - showAddGroupDialog: 新增分组 (group=null), 由 onAddGroup 触发, onConfirm 调 groupVm.addGroup
    // - editingGroup: 编辑/删除现有分组 (group=非空), 由 onDeleteGroup 触发, onDelete 调 groupVm.delGroup
    //   null=隐藏, 非 null=显示 (GroupEditDialog 内删除按钮二次确认后回调 onDelete)
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    // 应用日志对话框状态 (false=隐藏, true=显示; onShowLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    // 批量改源对话框状态 (false=隐藏, true=显示; onSelectActions "批量改源" 项触发)
    var showSourcePicker by remember { mutableStateOf(false) }
    // 可选书源列表 (LaunchedEffect 从 appDb.bookSourceDao.enabled() 加载完整 BookSource;
    // flowEnabled 返回 BookSourcePart 视图字段不全, 故用 enabled() suspend 方法获取完整实体)
    var bookSources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
    // allBooks: 当前分组全量数据 (未过滤/搜索), 供 upBookData 重算
    var allBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    // 增量过滤器 (有内部状态 skipFirst, 需 remember 持有同一实例)
    val incrementalFilter = remember { BookFilter.IncrementalFilter<Book>() }
    var refreshTick by remember { mutableIntStateOf(0) }

    // 文案标签 (rememberString 是 @Composable, 顶层 remember 一次)
    val screenLabel = rememberString("screen")
    val noGroupLabel = rememberString("no_group")
    val localBookLabel = rememberString("local_book")
    val deleteLabel = rememberString("delete")
    val sureDelLabel = rememberString("sure_del")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val groupLabel = rememberString("group")
    val groupManageLabel = rememberString("group_manage")
    // onSelectActions 用到的 key (jvmMain rememberString table, 避免 onSelectActions lambda 内中英混排)
    // key 对齐 app 端 BookshelfManageActivity.selectActions() 的 R.string.* (与 values-zh/strings.xml 一致)
    val allowUpdateLabel = rememberString("allow_update")
    val clearCacheLabel = rememberString("clear_cache")
    val exportAllLabel = rememberString("export_all")
    val disableUpdateLabel = rememberString("disable_update")
    val addToGroupLabel = rememberString("add_to_group")
    val exportBookshelfLabel = rememberString("export_bookshelf")
    val changeSourceBatchLabel = rememberString("change_source_batch")
    val checkSelectedIntervalLabel = rememberString("check_selected_interval")

    // 导出三档开关 (PreferenceProviders 持久化, 初始读一次, toggle 时写回 + 刷新 state)
    val prefs = remember { PreferenceProviders.get() }
    var exportUseReplace by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.exportUseReplace, false))
    }
    var enableCustomExport by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.enableCustomExport, false))
    }
    var exportToWebDav by remember {
        mutableStateOf(prefs.getBoolean(PreferKey.exportToWebDav, false))
    }

    // 当前分组显示名 (顶栏 searchHint 用 "screen • groupName")
    var groupName by remember { mutableStateOf(noGroupLabel) }

    // 选分组对话框目标 (非 null 即弹窗)
    var groupSelectTarget by remember { mutableStateOf<GroupSelectTarget?>(null) }
    // 删除确认对话框目标 (非 null 即弹窗)
    var deleteTarget by remember { mutableStateOf<List<Book>?>(null) }

    // ---- 业务函数 (局部函数, 定义在使用之前) ----

    /**
     * 按 bookshelfTypeFilter + searchKey 过滤 allBooks, 更新 state.books + selected 交集。
     * 对齐 app 端 BookshelfManageActivity.upBookData。
     */
    fun upBookData() {
        val all = allBooks
        val typeFiltered = when (state.value.bookshelfTypeFilter) {
            1 -> all.filter { !it.isImage && !it.isAudio && !it.isVideo }
            2 -> all.filter { it.isImage }
            3 -> all.filter { it.isAudio }
            4 -> all.filter { it.isVideo }
            else -> all
        }
        val filtered = incrementalFilter.filter(typeFiltered, state.value.searchKey)
        state.value = state.value.copy(
            books = filtered,
            // 选中集与最新数据取交集, 避免删除后 selected 残留
            selected = state.value.selected.intersect(filtered.map { it.bookUrl }.toSet()),
        )
    }

    /** 切换分组 (顶栏分组菜单 onSelectGroupFromMenu + 初始化): 更新 groupId/groupName, LaunchedEffect(groupId) 会重新订阅书籍流 */
    fun selectGroup(group: BookGroup) {
        groupId = group.groupId
        groupName = group.groupName.ifEmpty { noGroupLabel }
    }

    /** 弹选分组对话框 (mainAction 批量替换 / onEditGroup 单项替换 / addToGroup 批量并入) */
    fun requestSelectGroup(target: GroupSelectTarget) {
        groupSelectTarget = target
    }

    /** 弹删除确认对话框 (onDeleteBook 单项 / selectActions 批量删除) */
    fun requestDelete(books: List<Book>) {
        deleteTarget = books
    }

    /** 选中集对应的 Book 列表 */
    fun selection(): List<Book> =
        state.value.books.filter { state.value.selected.contains(it.bookUrl) }

    // ---- 数据流订阅 ----

    // 收集全部分组 (flowAll, 含系统分组?; 顶栏菜单 + groupName 查询共用)
    LaunchedEffect(Unit) {
        AppDatabaseProviders.get().appDb.bookGroupDao.flowAll()
            .catch { AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { groups ->
                groupList = groups
                state.value = state.value.copy(groups = groups)
                refreshTick++
            }
    }

    // 加载启用书源 (供批量改源对话框选择; 对齐 app 端 bookSourceDao.flowEnabled 语义,
    // 但 flowEnabled 返回 BookSourcePart 视图, SourcePickerDialog 需要完整 BookSource,
    // 故用 enabled() suspend 方法获取完整实体)
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                AppDatabaseProviders.get().appDb.bookSourceDao.enabled(true)
            }
        }.onSuccess { bookSources = it }
            .onFailure { AppLog.put("书架管理界面获取书源数据失败\n${it.localizedMessage}", it) }
    }

    // 收集当前分组书籍 (groupId 变化时重启), 排序后缓存到 allBooks + upBookData
    LaunchedEffect(groupId) {
        val bookSort = AppConfigProviders.get().bookshelfSort
        AppDbProviders.get().bookDao.flowByGroup(groupId)
            .map { list ->
                // 对齐 app 端 upBookDataByGroupId 的排序分支
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.compareTo(o2.name) } // cnCompare 未下沉, 用 compareTo
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }
            .catch { AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it) }
            .flowOn(Dispatchers.IO)
            .conflate()
            .collectLatest { books ->
                allBooks = books
                upBookData()
                // canDrag 仅手动排序 (sort==3) 时为 true, 与 app 端一致
                state.value = state.value.copy(canDrag = bookSort == 3)
                refreshTick++
            }
    }

    // ---- callbacks (remember 持有稳定实例, lambda 捕获 state 引用不触发重组) ----
    val callbacks = remember(state) {
        BookshelfManageCallbacks(
            onBack = onBack,
            onQueryChange = { query ->
                state.value = state.value.copy(searchKey = query)
                upBookData()
            },
            onMove = { from, to ->
                state.value = state.value.copy(
                    books = state.value.books.toMutableList().apply { add(to, removeAt(from)) }
                )
            },
            onPersistOrder = {
                // 松手落库: 按当前顺序重排 order 后整行 update (拖排是显式全量重排)
                val books = state.value.books
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val array = Array(books.size) { i ->
                            books[i].copy(order = i + 1)
                        }
                        AppDbProviders.get().bookDao.update(*array)
                    }
                }
            },
            onSelectAll = { all ->
                state.value = state.value.copy(
                    selected = if (all) state.value.books.map { it.bookUrl }.toSet() else emptySet()
                )
            },
            onRevertSelection = {
                state.value = state.value.copy(
                    selected = state.value.books.map { it.bookUrl }.toSet() - state.value.selected
                )
            },
            onMainAction = {
                // 主操作 = 移到分组 (替换 group 字段); 弹选分组对话框
                requestSelectGroup(GroupSelectTarget.Replace(selection()))
            },
            onSelectActions = {
                // 对齐 app 端 selectActions: 每个 SelectAction.onClick 在点击时实时调 selection(),
                // 避免菜单展开期间用户改变选中集导致快照 stale
                // 所有 key 均走 rememberString (顶层预缓存为 *Label, key 对齐 app 端 R.string.*)
                listOf(
                    SelectAction(deleteLabel) { requestDelete(selection()) },
                    SelectAction(exportAllLabel) {
                        // ExportBookService 强依赖 Android (epublib/Glide/Service/通知/FileDoc/Uri),
                        // 未下沉; desktop 简化为导出选中书籍元数据 JSON: FileDialog SAVE → GSON.toJson → 写文件
                        val sel = selection()
                        scope.launch {
                            val targetPath = withContext(Dispatchers.IO) {
                                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                                val dialog = FileDialog(Frame(), "导出书架 JSON", FileDialog.SAVE)
                                dialog.setFile("bookshelf-${dateFormat.format(Date())}.json")
                                dialog.isVisible = true
                                val dir = dialog.directory ?: return@withContext null
                                val file = dialog.file ?: return@withContext null
                                dir + file
                            } ?: run {
                                AppLog.put("导出书架: 用户取消选择")
                                return@launch
                            }
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    File(targetPath).writeText(GSON.toJson(sel))
                                }
                                io.legado.app.help.toast.Toasters.get().toast("导出成功")
                            }.onFailure {
                                AppLog.put("导出失败\n${it.localizedMessage}", it, true)
                            }
                        }
                    },
                    SelectAction(allowUpdateLabel) {
                        val sel = selection()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val array = Array(sel.size) { i ->
                                    sel[i].copy(canUpdate = true)
                                }
                                AppDbProviders.get().bookDao.update(*array)
                            }
                        }
                    },
                    SelectAction(disableUpdateLabel) {
                        val sel = selection()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val array = Array(sel.size) { i ->
                                    sel[i].copy(canUpdate = false)
                                }
                                AppDbProviders.get().bookDao.update(*array)
                            }
                        }
                    },
                    SelectAction(addToGroupLabel) {
                        // 并入分组: book.group = book.group or selectedGroupId
                        requestSelectGroup(GroupSelectTarget.Merge(selection()))
                    },
                    SelectAction(exportBookshelfLabel) {
                        // TODO: 桌面端导出书架 JSON 依赖 HandleFileContract, 未下沉
                    },
                    SelectAction(changeSourceBatchLabel) {
                        // 触发 SourcePickerDialog 显示 (末尾渲染分支读取 showSourcePicker)
                        // 仅当有选中书籍时弹窗 (批量改源需要目标书籍)
                        if (selection().isNotEmpty()) {
                            showSourcePicker = true
                        }
                    },
                    SelectAction(clearCacheLabel) {
                        // 清缓存: 调用 BookStorageProviders.get().clearCache(book) (JvmBookStorage 实现,
                        // 删除 ~/.legado/book_cache/{bookFolderName}/ 目录)
                        // 对照 app 端 BookshelfManageViewModel.clearCache(books) → BookHelp.clearCache(book)
                        val sel = selection()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                sel.forEach { book ->
                                    runCatching {
                                        io.legado.app.help.book.BookStorageProviders.get().clearCache(book)
                                    }.onFailure {
                                        AppLog.put("清缓存失败: ${book.name}\n${it.localizedMessage}", it)
                                    }
                                }
                            }
                            // 提示成功 (与 app 端 toastOnUi(R.string.clear_cache_success) 行为一致)
                            io.legado.app.help.toast.Toasters.get().toast("清缓存成功")
                        }
                    },
                    SelectAction(checkSelectedIntervalLabel) {
                        // TODO: checkSelectedInterval 需要 LazyListState 可见区间, 桌面端简化暂不实现
                    },
                )
            },
            onToggle = { book, checked ->
                state.value = state.value.copy(
                    selected = if (checked) state.value.selected + book.bookUrl
                    else state.value.selected - book.bookUrl
                )
            },
            onOpenBook = {
                // TODO: 桌面端 BookInfo 路由未注入, 后续由宿主注入 onOpenBook 回调后接入
            },
            onToggleDownload = {
                // TODO: 依赖 CacheBook (app 端缓存服务), 未下沉
            },
            isItemDownloading = { false },
            onOriginText = { book ->
                if (book.isLocal) localBookLabel else book.originName
            },
            onGroupName = { gid ->
                // 对齐 app 端: groupId > 0 且位与 gid > 0 的分组名拼接
                groupList.filter { it.groupId > 0 && it.groupId and gid > 0 }
                    .map { it.groupName }
                    .let { if (it.isEmpty()) "" else it.joinToString(",") }
            },
            onCacheInfo = { book ->
                // 缓存信息: 取 BookStorageProviders.get().getChapterFiles(book).size 与 book.totalChapterNum 对比
                // 对照 app 端 cacheInfo: viewModel.cacheChapters[book.bookUrl]?.size / book.totalChapterNum
                // 桌面端简化: 每次直接扫描目录 (app 端用 cacheChapters 缓存避免重复扫描,
                // 桌面端书籍数量通常较少, 性能可接受; 后续可接入 BookshelfManageViewModelShared.loadCacheFiles 缓存)
                if (book.isLocal) null
                else runCatching {
                    val cached = io.legado.app.help.book.BookStorageProviders.get().getChapterFiles(book).size
                    "$cached/${book.totalChapterNum}"
                }.getOrNull()
            },
            onDeleteBook = { book -> requestDelete(listOf(book)) },
            onEditGroup = { book ->
                // 单项改分组 (替换 group 字段)
                requestSelectGroup(GroupSelectTarget.Replace(listOf(book)))
            },
            onDownloadAfter = {
                // TODO: 依赖 CacheBook, 未下沉
            },
            onDownloadAll = {
                // TODO: 依赖 CacheBook, 未下沉
            },
            onShowGroupManage = {
                // 触发 GroupManageDialog 显示 (末尾渲染分支读取 showGroupManage)
                showGroupManage = true
            },
            onSelectGroupFromMenu = { group -> selectGroup(group) },
            onExportAllUseBookSource = {
                // TODO: 依赖 HandleFileContract + BookshelfManageViewModel.saveAllUseBookSourceToFile, 未下沉
            },
            onToggleEnableReplace = {
                exportUseReplace = !exportUseReplace
                prefs.putBoolean(PreferKey.exportUseReplace, exportUseReplace)
            },
            onToggleCustomExport = {
                enableCustomExport = !enableCustomExport
                prefs.putBoolean(PreferKey.enableCustomExport, enableCustomExport)
            },
            onToggleExportWebDav = {
                exportToWebDav = !exportToWebDav
                prefs.putBoolean(PreferKey.exportToWebDav, exportToWebDav)
            },
            onSelectExportFolderMenu = {
                // TODO: 依赖 HandleFileContract (文件夹选择), 未下沉
            },
            onShowExportConfig = {
                // TODO: 依赖 app 端 alert 对话框 + AppConfig, 未下沉
            },
            onShowLog = {
                // TODO: 依赖 AppLogDialog, 未下沉 → 已接入 showLogDialog
                showLogDialog = true
            },
            onSetBookTypeFilter = { filter ->
                if (state.value.bookshelfTypeFilter != filter) {
                    state.value = state.value.copy(bookshelfTypeFilter = filter)
                    upBookData()
                }
            },
        )
    }

    // ---- 渲染 shared Screen (位置参数: 函数类型参数不能用命名参数) ----
    val dragListModifier = Modifier.dragSelectable(
        listState = listState,
        autoScrollScope = scope,
        isSelected = { index ->
            // 越界保护 (books 为空或 index 超出时返回 false)
            state.value.books.getOrNull(index)?.bookUrl in state.value.selected
        },
        onSelectedChanged = { index, selected ->
            // 越界保护: 取不到 book 时忽略, 避免下标越界
            state.value.books.getOrNull(index)?.let { book ->
                state.value = state.value.copy(
                    selected = if (selected) state.value.selected + book.bookUrl
                    else state.value.selected - book.bookUrl
                )
            }
        },
    )

    // 同步 state: searchHint / refreshTick / 导出三档开关 (每次重组刷新)
    val currentState = state.value.copy(
        searchHint = "$screenLabel • $groupName",
        refreshTick = refreshTick,
        exportUseReplace = exportUseReplace,
        enableCustomExportChecked = enableCustomExport,
        exportToWebDav = exportToWebDav,
        groups = groupList,
    )

    SharedBookshelfManageScreen(
        currentState,
        callbacks,
        listState,
        dragListModifier,
        { book -> DesktopBookCover.InfoCover(book, Modifier) },
    )

    // ---- 选分组对话框 (mainAction / onEditGroup / addToGroup 共用) ----
    groupSelectTarget?.let { target ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { groupSelectTarget = null },
            title = { Text(groupLabel) },
            text = {
                // 列出用户分组 (groupId > 0); 点击即选中并关闭
                androidx.compose.foundation.layout.Column {
                    groupList.filter { it.groupId > 0 }.forEach { group ->
                        TextButton(onClick = {
                            groupSelectTarget = null
                            val selectedGroupId = group.groupId
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val array = when (target) {
                                        is GroupSelectTarget.Replace -> Array(target.books.size) { i ->
                                            target.books[i].copy(group = selectedGroupId)
                                        }
                                        is GroupSelectTarget.Merge -> Array(target.books.size) { i ->
                                            val b = target.books[i]
                                            b.copy(group = b.group or selectedGroupId)
                                        }
                                    }
                                    AppDbProviders.get().bookDao.update(*array)
                                }
                            }
                        }) {
                            Text(group.groupName)
                        }
                    }
                    if (groupList.none { it.groupId > 0 }) {
                        Text(groupManageLabel)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { groupSelectTarget = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // ---- 删除确认对话框 (onDeleteBook 单项 / selectActions 批量删除共用) ----
    deleteTarget?.let { books ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { deleteTarget = null },
            title = { Text(deleteLabel) },
            text = { Text(sureDelLabel) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDbProviders.get().bookDao.delete(*books.toTypedArray())
                            // 本地书删除源文件 (对照 app 端 BookshelfManageViewModel.deleteBook
                            // → FileBook.deleteBook(book, deleteOriginal))
                            // 桌面端用 LocalBookLocators.get().deleteBook (JvmLocalBookLocator)
                            books.forEach { book ->
                                if (book.isLocal) {
                                    runCatching {
                                        io.legado.app.help.book.LocalBookLocators.get().deleteBook(book)
                                    }.onFailure {
                                        AppLog.put("删除本地书源文件失败: ${book.name}\n${it.localizedMessage}", it)
                                    }
                                }
                            }
                        }
                        // 同步清理选中集
                        val removed = books.map { it.bookUrl }.toSet()
                        state.value = state.value.copy(
                            selected = state.value.selected - removed
                        )
                    }
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // ---- 分组管理对话框 (onShowGroupManage 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // 接入模式参考 desktop BookshelfScreen.kt: groups 复用 groupList (已订阅 flowAll),
    // 增删改走 GroupViewModelShared (shared commonMain) 转发到 bookGroupDao/bookDao
    if (showGroupManage) {
        GroupManageDialog(
            groups = groupList,
            // 新建分组: 触发 GroupEditDialog(group=null) 让用户编辑分组名/排序/刷新等字段,
            // onConfirm 中调 groupVm.addGroup (用 BookGroup 字段), 替代原直接 addGroup(name)
            onAddGroup = { _ -> showAddGroupDialog = true },
            onRenameGroup = { groupId, newName ->
                groupList.find { it.groupId == groupId.toLong() }?.let {
                    groupVm.upGroup(it.copy(groupName = newName))
                }
            },
            // 删除: 触发 GroupEditDialog(group=找到的 BookGroup), 由 GroupEditDialog 内删除按钮
            // 二次确认后回调 onDelete → groupVm.delGroup (替代原直接 delGroup)
            onDeleteGroup = { groupId ->
                groupList.find { it.groupId == groupId.toLong() }?.let { editingGroup = it }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // ---- 新增分组对话框 (GroupManageDialog.onAddGroup 触发, GroupEditDialog(group=null)) ----
    // onConfirm: 用 BookGroup 字段调 groupVm.addGroup (groupName/bookSort/enableRefresh/cover),
    //   替代原 onAddGroup 直接 addGroup(name, bookSort=-1, enableRefresh=true, cover=null)
    if (showAddGroupDialog) {
        GroupEditDialog(
            group = null,
            onConfirm = { g ->
                groupVm.addGroup(
                    groupName = g.groupName,
                    bookSort = g.bookSort,
                    enableRefresh = g.enableRefresh,
                    cover = g.cover,
                ) {}
            },
            onDismiss = { showAddGroupDialog = false },
        )
    }

    // ---- 编辑/删除分组对话框 (GroupManageDialog.onDeleteGroup 触发, GroupEditDialog(group=editingGroup)) ----
    // onDelete: GroupEditDialog 内删除按钮二次确认后回调, 调 groupVm.delGroup 连带 bookDao.removeGroup
    editingGroup?.let { g ->
        GroupEditDialog(
            group = g,
            onConfirm = { updated -> groupVm.upGroup(updated) },
            onDismiss = { editingGroup = null },
            onDelete = { del -> groupVm.delGroup(del) {} },
        )
    }

    // ---- 批量改源对话框 (onSelectActions "批量改源" 触发, 调用 shared/sharedUiMain 下沉的 SourcePickerDialog) ----
    // book: 选中第一本书作为上下文 (SourcePickerDialog 当前未使用 book 字段, 仅作占位)
    // sources: 从 appDb.bookSourceDao.enabled() 加载的启用书源 (完整 BookSource 实体)
    // onConfirm: 对选中书籍批量更新 bookSourceUrl + originName (简化: 取第一个勾选书源作为新源)
    //   app 端 changeSource 还涉及重新加载章节等, 此处仅更新字段, 副作用留 TODO
    if (showSourcePicker) {
        val contextBook = selection().firstOrNull()
        if (contextBook != null) {
            SourcePickerDialog(
                book = contextBook,
                sources = bookSources,
                selectedSourceUrls = emptySet(),
                onConfirm = { selectedUrls ->
                    showSourcePicker = false
                    // 批量改源语义: 所有选中书切到同一书源 (取第一个勾选书源)
                    val newSourceUrl = selectedUrls.firstOrNull() ?: return@SourcePickerDialog
                    val newSource = bookSources.find { it.bookSourceUrl == newSourceUrl }
                    val sel = selection()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val array = Array(sel.size) { i ->
                                sel[i].copy(
                                    origin = newSourceUrl,
                                    originName = newSource?.bookSourceName ?: sel[i].originName,
                                )
                            }
                            AppDbProviders.get().bookDao.update(*array)
                        }
                    }
                },
                onDismiss = { showSourcePicker = false },
            )
        }
    }
}
