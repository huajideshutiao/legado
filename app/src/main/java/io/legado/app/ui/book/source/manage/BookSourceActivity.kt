package io.legado.app.ui.book.source.manage

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.resolveBookSource
import io.legado.app.help.IntentData
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.BookSourceListCallbacks
import io.legado.app.ui.book.source.BookSourceListScreen
import io.legado.app.ui.book.source.BookSourceListState
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.observeEvent
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.throttleLatest
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 书源管理界面(纯 Compose)
 * singleTop + 外部跳转目标，intent 契约不变。
 */
class BookSourceActivity : BaseComposeActivity() {
    val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null

    var sources by mutableStateOf<List<BookSourcePart>>(emptyList())
        private set
    val selected = mutableStateOf<Set<String>>(emptySet())
    var searchKey by mutableStateOf("")
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var sort by mutableStateOf(BookSourceSort.Default)
        private set
    var sortAscending by mutableStateOf(true)
        private set
    var groupSourcesByDomain by mutableStateOf(false)
        private set

    // 校验进度(EventBus 桥接为 state)与逐项 debug 文案刷新计时器
    var checkSourceMsg by mutableStateOf<String?>(null)
        private set
    var checkSourceVisible by mutableStateOf(false)
        private set
    var checkTick by mutableStateOf(0)
        private set

    private val hostMap = hashMapOf<String, String>()

    // 增量更新: 记录上次排序参数与结果, 仅排序参数变化时全量重排
    @Volatile
    private var lastSort: BookSourceSort? = null
    @Volatile
    private var lastSortAscending: Boolean? = null
    @Volatile
    private var lastGroupSourcesByDomain: Boolean? = null
    @Volatile
    private var lastSortedSources: List<BookSourcePart> = emptyList()

    private val importDoc = registerHandleFile {
        it.uri?.let { uri ->
            showDialogFragment(ImportBookSourceDialog(uri.toString()))
        }
    }
    private val exportDir = registerHandleFile {
        it.uri?.let { uri ->
            showExportSuccess(uri)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBookSource()
        initLiveDataGroup()
        resumeCheckSource()
        if (!LocalConfig.bookSourcesHelpVersionIsLast) {
            showHelp("SourceMBookHelp")
        }
    }

    @Composable
    override fun Content() {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 把 Activity 分散的 mutableStateOf 字段聚合为 immutable state, 供 shared 端 BookSourceListScreen 消费
        val state = BookSourceListState(
            sources = sources,
            selected = selected.value,
            searchKey = searchKey,
            groups = groups,
            sort = sort.toKmp(),
            sortAscending = sortAscending,
            groupSourcesByDomain = groupSourcesByDomain,
            checkSourceMsg = checkSourceMsg,
            checkSourceVisible = checkSourceVisible,
            checkTick = checkTick,
        )
        // callbacks 用 remember 持有稳定实例, lambda 捕获 Activity 方法引用, 调用时实时读取最新字段
        val callbacks = remember(this) {
            BookSourceListCallbacks(
                onBack = { finish() },
                onQueryChange = { setQuery(it) },
                onSortChange = { changeSort(it.toManage()) },
                onToggleSortDesc = { toggleSortDesc() },
                onToggleGroupByDomain = { toggleGroupByDomain() },
                onToggle = { item, checked -> toggle(item, checked) },
                onSelectAll = { selectAll(it) },
                onRevertSelection = { revertSelection() },
                onMove = { from, to -> onMove(from, to) },
                onPersistOrder = { persistOrder() },
                onEdit = { edit(it) },
                onEnable = { enable, item -> enable(enable, item) },
                onEnableExplore = { enable, item -> enableExplore(enable, item) },
                onToTop = { toTop(it) },
                onToBottom = { toBottom(it) },
                onSearchBook = { searchBook(it) },
                onDebug = { debug(it) },
                onLogin = { login(it) },
                onDel = { del(it) },
                onDelSelection = { delSelection() },
                onCancelCheckSource = { cancelCheckSource() },
                onAddBookSource = { addBookSource() },
                onImportLocal = { importLocal() },
                onImportOnline = { showImportDialog() },
                onGroupManage = { showGroupManage() },
                onHelp = { help() },
                onSelectActions = { selectActions() },
                getSourceHost = { getSourceHost(it) },
            )
        }
        BookSourceListScreen(
            state = state,
            callbacks = callbacks,
            listState = listState,
            listModifier = Modifier.dragSelectable(
                listState = listState,
                autoScrollScope = scope,
                isSelected = { index -> selected.value.contains(sources[index].bookSourceUrl) },
                onSelectedChanged = { index, sel -> toggle(sources[index], sel) },
            ),
        )
    }

    // ---- BookSourceSort 跨模块映射(app 端 manage.BookSourceSort 与 shared 端 io.legado.app.ui.book.source.BookSourceSort 值一一对应) ----

    private fun BookSourceSort.toKmp(): io.legado.app.ui.book.source.BookSourceSort = when (this) {
        BookSourceSort.Default -> io.legado.app.ui.book.source.BookSourceSort.Default
        BookSourceSort.Name -> io.legado.app.ui.book.source.BookSourceSort.Name
        BookSourceSort.Url -> io.legado.app.ui.book.source.BookSourceSort.Url
        BookSourceSort.Weight -> io.legado.app.ui.book.source.BookSourceSort.Weight
        BookSourceSort.Update -> io.legado.app.ui.book.source.BookSourceSort.Update
        BookSourceSort.Enable -> io.legado.app.ui.book.source.BookSourceSort.Enable
        BookSourceSort.Respond -> io.legado.app.ui.book.source.BookSourceSort.Respond
    }

    private fun io.legado.app.ui.book.source.BookSourceSort.toManage(): BookSourceSort = when (this) {
        io.legado.app.ui.book.source.BookSourceSort.Default -> BookSourceSort.Default
        io.legado.app.ui.book.source.BookSourceSort.Name -> BookSourceSort.Name
        io.legado.app.ui.book.source.BookSourceSort.Url -> BookSourceSort.Url
        io.legado.app.ui.book.source.BookSourceSort.Weight -> BookSourceSort.Weight
        io.legado.app.ui.book.source.BookSourceSort.Update -> BookSourceSort.Update
        io.legado.app.ui.book.source.BookSourceSort.Enable -> BookSourceSort.Enable
        io.legado.app.ui.book.source.BookSourceSort.Respond -> BookSourceSort.Respond
    }

    /** 搜索/分组筛选沿用原 SearchView 语义(前缀语法以原实现为准)。 */
    fun setQuery(query: String) {
        searchKey = query
        upBookSource(query)
    }

    fun changeSort(newSort: BookSourceSort) {
        sort = newSort
        upBookSource(searchKey)
    }

    fun toggleSortDesc() {
        sortAscending = !sortAscending
        upBookSource(searchKey)
    }

    fun toggleGroupByDomain() {
        groupSourcesByDomain = !groupSourcesByDomain
        upBookSource(searchKey)
    }

    /** 手动排序且未按域名分组时才允许拖拽(对照原 itemTouchCallback.isCanDrag)。 */
    val canDrag: Boolean
        get() = sort == BookSourceSort.Default && !groupSourcesByDomain

    fun toggle(item: BookSourcePart, checked: Boolean) {
        selected.value =
            if (checked) selected.value + item.bookSourceUrl else selected.value - item.bookSourceUrl
    }

    fun selectAll(all: Boolean) {
        selected.value = if (all) sources.map { it.bookSourceUrl }.toSet() else emptySet()
    }

    fun revertSelection() {
        selected.value = sources.map { it.bookSourceUrl }.toSet() - selected.value
    }

    /** 复刻 adapter.checkSelectedInterval:补选已选区间内的全部条目。 */
    fun checkSelectedInterval() {
        val positions = sources.mapIndexedNotNull { index, part ->
            index.takeIf { selected.value.contains(part.bookSourceUrl) }
        }
        if (positions.isEmpty()) return
        val range = positions.min()..positions.max()
        selected.value = selected.value + range.map { sources[it].bookSourceUrl }
    }

    fun selection(): List<BookSourcePart> =
        sources.filter { selected.value.contains(it.bookSourceUrl) }

    fun onMove(from: Int, to: Int) {
        sources = sources.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 松手落库:按当前顺序重排 customOrder(升/降序),沿用 upOrder 的按行 update 语义。 */
    fun persistOrder() {
        val items = sources.mapIndexed { index, part ->
            part.customOrder = if (sortAscending) index else -index
            part
        }
        // 重置排序状态: customOrder 已变更, 下次 emit 需按新 customOrder 全量重排
        lastSort = null
        lastSortAscending = null
        lastGroupSourcesByDomain = null
        viewModel.upOrder(items)
    }

    fun getSourceHost(origin: String): String {
        return hostMap.getOrPut(origin) {
            NetworkUtils.getSubDomainOrNull(origin) ?: "#"
        }
    }

    /** 增量合并: 保持 oldList 顺序, 用 newData 中对应项替换, 已删除项过滤, 新增项追加末尾。 */
    private fun mergeIncremental(
        oldList: List<BookSourcePart>,
        newData: List<BookSourcePart>,
    ): List<BookSourcePart> {
        if (oldList.isEmpty()) return newData
        val newMap = newData.associateBy { it.bookSourceUrl }
        val result = ArrayList<BookSourcePart>(newData.size)
        for (item in oldList) {
            newMap[item.bookSourceUrl]?.let(result::add)
        }
        val oldUrls = HashSet<String>(oldList.size)
        for (item in oldList) oldUrls.add(item.bookSourceUrl)
        for (item in newData) {
            if (item.bookSourceUrl !in oldUrls) result.add(item)
        }
        return result
    }

    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        // 重置排序状态: 新 Flow 首次 emit 强制全量重排 (应对 searchKey/sort 变化导致数据集切换)
        lastSort = null
        lastSortAscending = null
        lastGroupSourcesByDomain = null
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.bookSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.bookSourceDao.flowEnabled(false)
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.bookSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.bookSourceDao.flowNoGroup()
                }

                searchKey == getString(R.string.enabled_explore) -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey == getString(R.string.disabled_explore) -> {
                    appDb.bookSourceDao.flowExplore(false)
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.map { data ->
                // hostMap 为纯缓存, 不随数据变化失效
                val needResort = lastSort != sort
                    || lastSortAscending != sortAscending
                    || lastGroupSourcesByDomain != groupSourcesByDomain
                val sorted = if (needResort) {
                    // 排序参数变化, 全量重排
                    if (groupSourcesByDomain) {
                        data.sortedWith(
                            compareBy<BookSourcePart> { getSourceHost(it.bookSourceUrl) == "#" }
                                .thenBy { getSourceHost(it.bookSourceUrl) }
                                .thenByDescending { it.lastUpdateTime })
                    } else {
                        val tmp = when (sort) {
                            BookSourceSort.Weight -> data.sortedBy { it.weight }
                            BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                                o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }

                            BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                            BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                            BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                            BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                                var sortNum = -o1.enabled.compareTo(o2.enabled)
                                if (sortNum == 0) {
                                    sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                                }
                                sortNum
                            }

                            else -> data.sortedBy { it.customOrder }
                        }
                        if (!sortAscending) tmp.reversed() else tmp
                    }
                } else {
                    // 排序参数未变, 增量合并: 保持旧顺序, 仅替换/增删变化项
                    mergeIncremental(lastSortedSources, data)
                }
                lastSort = sort
                lastSortAscending = sortAscending
                lastGroupSourcesByDomain = groupSourcesByDomain
                lastSortedSources = sorted
                sorted
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).throttleLatest(500).collect { data ->
                sources = data
                selected.value =
                    selected.value.intersect(data.map { it.bookSourceUrl }.toSet())
            }
        }
    }

    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups()
                .flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .distinctUntilChanged()
                .throttleLatest(500)
                .collect { groups = it }
        }
    }

    // ---- 单项操作(对照原 BookSourceAdapter.CallBack) ----

    fun del(bookSource: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + bookSource.bookSourceName)
            noButton()
            yesButton {
                viewModel.del(listOf(bookSource))
            }
        }
    }

    fun edit(bookSource: BookSourcePart) {
        startActivity<BookSourceEditActivity> {
            IntentData.source = bookSource.resolveBookSource()
        }
    }

    fun enable(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enable(enable, listOf(bookSource))
    }

    fun enableExplore(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enableExplore(enable, listOf(bookSource))
    }

    fun toTop(bookSource: BookSourcePart) {
        if (sortAscending) viewModel.topSource(bookSource) else viewModel.bottomSource(bookSource)
    }

    fun toBottom(bookSource: BookSourcePart) {
        if (sortAscending) viewModel.bottomSource(bookSource) else viewModel.topSource(bookSource)
    }

    fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    fun debug(bookSource: BookSourcePart) {
        startActivity<BookSourceDebugActivity> {
            putExtra("key", bookSource.bookSourceUrl)
        }
    }

    fun login(bookSource: BookSourcePart) {
        bookSource.resolveBookSource()?.showLoginDialog(this as AppCompatActivity)
    }

    // ---- 批量栏 actions(对照 book_source_sel 菜单) ----

    fun selectActions(): List<SelectAction> = listOf(
        SelectAction(getString(R.string.enable_selection)) { viewModel.enableSelection(selection()) },
        SelectAction(getString(R.string.disable_selection)) { viewModel.disableSelection(selection()) },
        SelectAction(getString(R.string.add_group)) { selectionAddToGroups() },
        SelectAction(getString(R.string.remove_group)) { selectionRemoveFromGroups() },
        SelectAction(getString(R.string.enable_explore)) { viewModel.enableSelectExplore(selection()) },
        SelectAction(getString(R.string.disable_explore)) { viewModel.disableSelectExplore(selection()) },
        SelectAction(getString(R.string.selection_to_top)) { viewModel.topSource(*selection().toTypedArray()) },
        SelectAction(getString(R.string.selection_to_bottom)) { viewModel.bottomSource(*selection().toTypedArray()) },
        SelectAction(getString(R.string.export_selection)) { exportSelection() },
        SelectAction(getString(R.string.share_selected_source)) { shareSelection() },
        SelectAction(getString(R.string.check_select_source)) { checkSource() },
        SelectAction(getString(R.string.check_selected_interval)) { checkSelectedInterval() },
    )

    fun delSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(selection()) }
            noButton()
        }
    }

    private fun exportSelection() {
        viewModel.saveToFile(selection(), sources.size, sortAscending, sort) { file ->
            exportDir.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "bookSource.json",
                    file,
                    "application/json"
                )
            }
        }
    }

    private fun shareSelection() {
        viewModel.saveToFile(selection(), sources.size, sortAscending, sort) {
            share(it, title = getString(R.string.share_selected_source))
        }
    }

    private fun checkSource() {
        alert(titleResource = R.string.search_book_key) {
            val getText = editTextView(hint = "search word", text = CheckSource.keyword)
            okButton {
                keepScreenOn(true)
                getText().let {
                    if (it.isNotEmpty()) {
                        CheckSource.keyword = it
                    }
                }
                val selectItems = selection()
                CheckSource.start(this@BookSourceActivity, selectItems)
                val firstItem = sources.indexOf(selectItems.firstOrNull())
                val lastItem = sources.indexOf(selectItems.lastOrNull())
                Debug.isChecking = firstItem >= 0 && lastItem >= 0
                startCheckMessageRefreshJob()
            }
            // neutralButton dismissOnClick=false，点击打开校验设置后对话框不关闭
            neutralButtonRetain(R.string.check_source_config) {
                showDialogFragment<CheckSourceConfig>()
            }
            cancelButton()
        }
    }

    private fun resumeCheckSource() {
        if (!Debug.isChecking) {
            return
        }
        keepScreenOn(true)
        CheckSource.resume(this)
        startCheckMessageRefreshJob()
    }

    fun cancelCheckSource() {
        CheckSource.stop(this)
        Debug.finishChecking()
    }

    private fun selectionAddToGroups() {
        alert(titleResource = R.string.add_group) {
            val getText = editTextView(hint = getString(R.string.group_name), filterValues = groups.toList())
            okButton {
                getText().let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionAddToGroups(selection(), it)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun selectionRemoveFromGroups() {
        alert(titleResource = R.string.remove_group) {
            val getText = editTextView(hint = getString(R.string.group_name), filterValues = groups.toList())
            okButton {
                getText().let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionRemoveFromGroups(selection(), it)
                    }
                }
            }
            cancelButton()
        }
    }

    // ---- 溢出菜单动作 ----

    fun addBookSource() = startActivity<BookSourceEditActivity>()

    fun showGroupManage() = showDialogFragment<GroupManageDialog>()

    fun importLocal() = importDoc.launch {
        mode = HandleFileContract.FILE
        allowExtensions = arrayOf("txt", "json")
    }

    fun help() = showHelp("SourceMBookHelp")

    fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val getText = editTextView(
                hint = "url",
                filterValues = cacheUrls,
                onDelete = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                },
            )
            okButton {
                getText().let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportBookSourceDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.CHECK_SOURCE) { msg ->
            checkSourceMsg = msg
            checkSourceVisible = true
        }
        observeEvent<Int>(EventBus.CHECK_SOURCE_DONE) {
            keepScreenOn(false)
            checkSourceVisible = false
            checkTick++
            groups.forEach { group ->
                if (group.contains("失效") && searchKey.isEmpty()) {
                    setQuery("失效")
                    toastOnUi("发现有失效书源，已为您自动筛选！")
                }
            }
        }
    }

    private fun startCheckMessageRefreshJob() {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    checkTick++
                    if (!Debug.isChecking) {
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(300L)
                }
            }
        }
    }

    override fun finish() {
        if (searchKey.isEmpty()) {
            super.finish()
        } else {
            setQuery("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }
}
