package io.legado.app.ui.book.manage

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookFilter
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.compose.component.SelectAction
import io.legado.app.ui.compose.component.dragSelectable
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ACache
import io.legado.app.utils.cnCompare
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.shouldHideSoftInput
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.startActivity
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 书架管理界面(纯 Compose)
 * intent 契约(groupId extra)不变。
 */
class BookshelfManageActivity : BaseComposeActivity(),
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    val viewModel by viewModels<BookshelfManageViewModel>()
    val groupList: ArrayList<BookGroup> = arrayListOf()
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    // 单项改分组的请求码(对齐原 BookAdapter.groupRequestCode)
    private val itemGroupRequestCode = 12
    private val incrementalFilter = BookFilter.IncrementalFilter<Book>()
    private val exportBookPathKey = "exportBookPath"

    var books by mutableStateOf<List<Book>>(emptyList())
        private set
    val selected = mutableStateOf<Set<String>>(emptySet())
    var searchKey by mutableStateOf("")
        private set
    var searchHint by mutableStateOf("")
        private set
    var bookshelfTypeFilter by mutableStateOf(0)
        private set
    var canDrag by mutableStateOf(false)
        private set
    var groups by mutableStateOf<List<BookGroup>>(emptyList())
        private set
    var downloadRunning by mutableStateOf(false)
        private set
    // 下载/缓存/封面刷新事件桥接为重组滴答
    var refreshTick by mutableStateOf(0)
        private set

    private var allBooks: List<Book>? = null
    private var booksFlowJob: Job? = null
    private var actionBook: Book? = null
    private val waitDialog by lazy { WaitDialog.from(this) }

    private val exportDir by lazy {
        registerHandleFile { result ->
            val uri = result.uri ?: return@registerHandleFile
            if (result.value == "cache") {
                val dirPath = if (uri.isContentScheme()) uri.toString() else uri.path
                    ?: return@registerHandleFile
                ACache.get().put(exportBookPathKey, dirPath)
                if (enableCustomExport()) {
                    configExportSection(dirPath)
                } else {
                    startExport(dirPath)
                }
            } else {
                showExportSuccess(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            upSearchHint()
        }
        downloadRunning = CacheBook.isRun
        waitDialog.onCancelListener = {
            viewModel.batchChangeSourceCoroutine?.cancel()
        }
        initGroupData()
        upBookDataByGroupId()
    }

    @Composable
    override fun Content() {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 把 Activity 持有的 mutableState 字段快照到 immutable state, 供 shared Screen 消费
        val state = BookshelfManageState(
            books = books,
            selected = selected.value,
            searchKey = searchKey,
            searchHint = searchHint,
            bookshelfTypeFilter = bookshelfTypeFilter,
            canDrag = canDrag,
            groups = groups,
            downloadRunning = downloadRunning,
            refreshTick = refreshTick,
            exportUseReplace = exportUseReplace,
            enableCustomExportChecked = enableCustomExportChecked,
            exportToWebDav = exportToWebDav,
        )
        // callbacks 用 remember 持有稳定实例, 避免 lambda 重组 (Activity 方法引用恒等)
        val callbacks = remember {
            BookshelfManageCallbacks(
                onBack = { finish() },
                onQueryChange = ::setQuery,
                onMove = ::onMove,
                onPersistOrder = ::persistOrder,
                onSelectAll = ::selectAll,
                onRevertSelection = ::revertSelection,
                onMainAction = ::mainAction,
                onSelectActions = ::selectActions,
                onToggle = ::toggle,
                onOpenBook = ::openBook,
                onToggleDownload = ::toggleDownload,
                isItemDownloading = ::isItemDownloading,
                onOriginText = ::originText,
                onGroupName = ::groupName,
                onCacheInfo = ::cacheInfo,
                onDeleteBook = ::deleteBook,
                onEditGroup = ::editGroup,
                onDownloadAfter = ::downloadAfter,
                onDownloadAll = ::downloadAll,
                onShowGroupManage = ::showGroupManage,
                onSelectGroupFromMenu = ::selectGroupFromMenu,
                onExportAllUseBookSource = ::exportAllUseBookSource,
                onToggleEnableReplace = ::toggleEnableReplace,
                onToggleCustomExport = ::toggleCustomExport,
                onToggleExportWebDav = ::toggleExportWebDav,
                onSelectExportFolderMenu = ::selectExportFolderMenu,
                onShowExportConfig = ::showExportConfig,
                onShowLog = ::showLog,
                onSetBookTypeFilter = ::setBookTypeFilter,
            )
        }
        BookshelfManageScreen(
            state = state,
            callbacks = callbacks,
            listState = listState,
            listModifier = Modifier.dragSelectable(
                listState = listState,
                autoScrollScope = scope,
                isSelected = { index -> selected.value.contains(books[index].bookUrl) },
                onSelectedChanged = { index, sel -> toggle(books[index], sel) },
            ),
            coverSlot = { book -> BookCover(book) },
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it.shouldHideSoftInput(ev)) {
                    it.post {
                        it.clearFocus()
                        it.hideSoftInput()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun upSearchHint() {
        searchHint = getString(R.string.screen) + " • " + viewModel.groupName
    }

    // ---- 搜索/筛选 ----

    fun setQuery(query: String) {
        searchKey = query
        upBookData()
    }

    fun setBookTypeFilter(filter: Int) {
        if (bookshelfTypeFilter == filter) return
        bookshelfTypeFilter = filter
        upBookData()
    }

    // ---- 多选 ----

    fun toggle(book: Book, checked: Boolean) {
        selected.value =
            if (checked) selected.value + book.bookUrl else selected.value - book.bookUrl
    }

    fun selectAll(all: Boolean) {
        selected.value = if (all) books.map { it.bookUrl }.toSet() else emptySet()
    }

    fun revertSelection() {
        selected.value = books.map { it.bookUrl }.toSet() - selected.value
    }

    fun checkSelectedInterval() {
        val positions = books.mapIndexedNotNull { index, book ->
            index.takeIf { selected.value.contains(book.bookUrl) }
        }
        if (positions.isEmpty()) return
        val range = positions.min()..positions.max()
        selected.value = selected.value + range.map { books[it].bookUrl }
    }

    fun selection(): List<Book> = books.filter { selected.value.contains(it.bookUrl) }

    // ---- 拖拽排序 ----

    fun onMove(from: Int, to: Int) {
        books = books.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 松手落库:按当前顺序重排 order 后整行 update(拖排是显式全量重排)。 */
    fun persistOrder() {
        books.forEachIndexed { index, book -> book.order = index + 1 }
        viewModel.updateBook(*books.toTypedArray())
    }

    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                groups = it
                refreshTick++
            }
        }
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            val bookSort = AppConfig.getBookSortByGroupId(viewModel.groupId)
            appDb.bookDao.flowByGroup(viewModel.groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    3 -> list.sortedBy { it.order }
                    4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                allBooks = it
                upBookData()
                viewModel.loadCacheFiles(it)
                canDrag = bookSort == 3
            }
        }
    }

    private fun upBookData() {
        allBooks?.let { all ->
            val typeFiltered = when (bookshelfTypeFilter) {
                1 -> all.filter { !it.isImage && !it.isAudio && !it.isVideo }
                2 -> all.filter { it.isImage }
                3 -> all.filter { it.isAudio }
                4 -> all.filter { it.isVideo }
                else -> all
            }
            books = incrementalFilter.filter(typeFiltered, searchKey)
            selected.value = selected.value.intersect(books.map { it.bookUrl }.toSet())
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        viewModel.batchChangeSourceState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_source_batch)
                waitDialog.show(supportFragmentManager)
            } else {
                waitDialog.dismissSafe()
            }
        }
        viewModel.batchChangeSourceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            refreshTick++
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            refreshTick++
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            downloadRunning = CacheBook.isRun
            refreshTick++
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            refreshTick++
        }
    }

    // ---- 单项操作(对照 BookAdapter.CallBack) ----

    fun openBook(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            IntentData.book = book
        }
    }

    fun deleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val deleteFile = mutableStateOf(LocalConfig.deleteBookOriginal)
            if (book.isLocal) {
                customView {
                    DeleteFileCheckbox(deleteFile)
                }
            }
            okButton {
                if (book.isLocal) LocalConfig.deleteBookOriginal = deleteFile.value
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
            }
        }
    }

    fun editGroup(book: Book) {
        actionBook = book
        selectGroup(itemGroupRequestCode, book.group)
    }

    /** 单项下载图标点击:对照 BookAdapter.ivDownload.setOnClickListener */
    fun toggleDownload(book: Book) {
        val cs = viewModel.cacheChapters[book.bookUrl]?.size
        if (cs != book.totalChapterNum) {
            CacheBook.cacheBookMap[book.bookUrl]?.let {
                if (!it.isStop()) {
                    CacheBook.remove(this, book.bookUrl)
                } else {
                    CacheBook.start(this, book, 0, book.lastChapterIndex)
                }
            } ?: CacheBook.start(this, book, 0, book.lastChapterIndex)
        }
    }

    fun isItemDownloading(book: Book): Boolean {
        return CacheBook.cacheBookMap[book.bookUrl]?.isStop() == false
    }

    fun originText(book: Book): String =
        if (book.isLocal) getString(R.string.local_book) else book.originName

    /** 缓存进度文案:本地书籍返回 null(隐藏) */
    fun cacheInfo(book: Book): String? {
        if (book.isLocal) return null
        val cs = viewModel.cacheChapters[book.bookUrl]
        return if (cs == null) getString(R.string.loading)
        else getString(R.string.download_count, cs.size, book.totalChapterNum)
    }

    fun groupName(groupId: Long): String {
        val names = groupList.filter { it.groupId > 0 && it.groupId and groupId > 0 }
            .map { it.groupName }
        return if (names.isEmpty()) "" else names.joinToString(",")
    }

    // ---- 批量栏(对照 bookshelf_menage_sel 菜单) ----

    fun mainAction() = selectGroup(groupRequestCode, 0)

    fun selectActions(): List<SelectAction> = listOf(
        SelectAction(getString(R.string.delete)) { alertDelSelection() },
        SelectAction(getString(R.string.export_all)) { exportAll() },
        SelectAction(getString(R.string.allow_update)) { viewModel.upCanUpdate(selection(), true) },
        SelectAction(getString(R.string.disable_update)) { viewModel.upCanUpdate(selection(), false) },
        SelectAction(getString(R.string.add_to_group)) { selectGroup(addToGroupRequestCode, 0) },
        SelectAction(getString(R.string.export_bookshelf)) {
            viewModel.exportBookshelf(selection()) { file ->
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }
        },
        SelectAction(getString(R.string.change_source_batch)) {
            showDialogFragment<SourcePickerDialog>()
        },
        SelectAction(getString(R.string.clear_cache)) { viewModel.clearCache(selection()) },
        SelectAction(getString(R.string.check_selected_interval)) { checkSelectedInterval() },
    )

    private fun alertDelSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val deleteFile = mutableStateOf(LocalConfig.deleteBookOriginal)
            customView {
                DeleteFileCheckbox(deleteFile)
            }
            okButton {
                LocalConfig.deleteBookOriginal = deleteFile.value
                viewModel.deleteBook(selection(), deleteFile.value)
            }
            noButton()
        }
    }

    /** 删除对话框的"同时删除源文件"复选框 */
    @Composable
    private fun DeleteFileCheckbox(state: androidx.compose.runtime.MutableState<Boolean>) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.value,
                    role = Role.Checkbox,
                    onValueChange = { state.value = it },
                )
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCheckbox(checked = state.value, onCheckedChange = null)
            Text(stringResource(R.string.delete_book_file), color = AppTheme.colors.primaryText)
        }
    }

    // ---- 顶栏下载(对照 menu_download / book_cache_download) ----

    /** 下载后续(menu_download / menu_download_after):从当前进度起,已全缓存的跳过;运行中则停止 */
    fun downloadAfter() {
        if (!CacheBook.isRun) {
            selection().forEach { book ->
                val cs = viewModel.cacheChapters[book.bookUrl]?.size
                if (cs != book.totalChapterNum) {
                    CacheBook.start(this, book, book.durChapterIndex, book.lastChapterIndex)
                }
            }
        } else {
            CacheBook.stop(this)
        }
    }

    /** 全部下载(menu_download_all):从头缓存;运行中则停止 */
    fun downloadAll() {
        if (!CacheBook.isRun) {
            selection().forEach { book ->
                CacheBook.start(this, book, 0, book.lastChapterIndex)
            }
        } else {
            CacheBook.stop(this)
        }
    }

    // ---- 顶栏分组/溢出菜单 ----

    fun showGroupManage() = showDialogFragment<GroupManageDialog>()

    fun selectGroupFromMenu(group: BookGroup) {
        viewModel.groupName = group.groupName
        upSearchHint()
        viewModel.groupId = runBlocking { appDb.bookGroupDao.getByName(group.groupName) }?.groupId ?: 0
        upBookDataByGroupId()
    }

    fun exportAllUseBookSource() {
        viewModel.saveAllUseBookSourceToFile { file ->
            exportDir.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("bookSource.json", file, "application/json")
            }
        }
    }

    val exportUseReplace: Boolean get() = AppConfig.exportUseReplace
    val enableCustomExportChecked: Boolean get() = AppConfig.enableCustomExport
    val exportToWebDav: Boolean get() = AppConfig.exportToWebDav

    fun toggleEnableReplace() {
        AppConfig.exportUseReplace = !AppConfig.exportUseReplace
    }

    fun toggleCustomExport() {
        AppConfig.enableCustomExport = !AppConfig.enableCustomExport
    }

    fun toggleExportWebDav() {
        AppConfig.exportToWebDav = !AppConfig.exportToWebDav
    }

    fun showLog() = showDialogFragment<AppLogDialog>()

    fun selectExportFolderMenu() = selectExportFolder()

    // ---- GroupSelect / SourcePicker 回调 ----

    private fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(GroupSelectDialog(groupId, requestCode))
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> selection().let { books ->
                val array = Array(books.size) { books[it].copy(group = groupId) }
                viewModel.updateBook(*array)
            }

            itemGroupRequestCode -> {
                actionBook?.let { viewModel.updateBook(it.copy(group = groupId)) }
            }

            addToGroupRequestCode -> selection().let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }
        }
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(selection(), source)
        viewModel.batchChangeSourceState.value = true
    }

    // ---- 导出 ----

    private fun exportAll() {
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder()
        } else {
            startExport(path)
        }
    }

    /**
     * 配置自定义导出对话框
     */
    private fun configExportSection(path: String) {
        alert(titleResource = R.string.select_section_export) {
            // allExport=true 全部导出；false 自定义导出。默认自定义
            val allExport = mutableStateOf(false)
            val epubFilename = mutableStateOf(AppConfig.episodeExportFileName ?: "")
            val epubSize = mutableStateOf("1")
            val inputScope = mutableStateOf("")
            val scopeError = mutableStateOf(false)
            val filenameHelper = mutableStateOf<String?>(null)

            fun verifyExportFileNameJsStr(js: String): Boolean =
                tryParesExportFileName(js) && epubFilename.value.isNotEmpty()

            customView {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    // 全部导出 / 自定义导出 二选一
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = allExport.value, onValueChange = { allExport.value = true })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppCheckbox(checked = allExport.value, onCheckedChange = null)
                        Text(stringResource(R.string.export_all), color = AppTheme.colors.primaryText, fontSize = 18.sp)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = !allExport.value, onValueChange = { allExport.value = false })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppCheckbox(checked = !allExport.value, onCheckedChange = null)
                        Text(stringResource(R.string.custom_export), color = AppTheme.colors.primaryText, fontSize = 18.sp)
                    }
                    if (!allExport.value) {
                        AppOutlinedTextField(
                            value = epubFilename.value,
                            onValueChange = { epubFilename.value = it },
                            label = stringResource(R.string.export_file_name),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    // 执行脚本按钮：复刻 endIcon，逐本书试算导出文件名
                                    selection().forEach { book ->
                                        filenameHelper.value =
                                            if (verifyExportFileNameJsStr(epubFilename.value))
                                                "${getString(R.string.result_analyzed)}: ${
                                                    book.getExportFileName("epub", 1, epubFilename.value)
                                                }"
                                            else "Error"
                                    }
                                }) {
                                    Icon(
                                        painter = rememberPainter("ic_play_24dp"),
                                        contentDescription = "Execute script",
                                        tint = AppTheme.colors.primaryText,
                                    )
                                }
                            },
                        )
                        filenameHelper.value?.let {
                            Text(it, color = AppTheme.colors.secondaryText, fontSize = 12.sp)
                        }
                        AppOutlinedTextField(
                            value = epubSize.value,
                            onValueChange = { epubSize.value = it.filter { c -> c.isDigit() }.take(6) },
                            label = stringResource(R.string.file_contains_number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AppOutlinedTextField(
                            value = inputScope.value,
                            onValueChange = { inputScope.value = it; scopeError.value = false },
                            label = if (scopeError.value) getString(R.string.error_scope_input)
                            else stringResource(R.string.export_chapter_index),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            positiveButtonRetain {
                if (allExport.value) {
                    startExport(path)
                    return@positiveButtonRetain true
                }
                if (epubFilename.value.isNotEmpty() && verifyExportFileNameJsStr(epubFilename.value)) {
                    AppConfig.episodeExportFileName = epubFilename.value
                }
                val epubScope = inputScope.value
                if (!verificationField(epubScope)) {
                    scopeError.value = true
                    return@positiveButtonRetain false
                }
                val size = epubSize.value.toIntOrNull() ?: 1
                selection().forEach { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", "epub")
                        putExtra("exportPath", path)
                        putExtra("epubSize", size)
                        putExtra("epubScope", epubScope)
                    }
                }
                true
            }
            cancelButton()
        }
    }

    private fun selectExportFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            value = "cache"
        }
    }

    private fun startExport(path: String) {
        val defaultType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        if (selection().isNotEmpty()) {
            selection().forEach { book ->
                val exportType = if (book.isImage) "cbz" else defaultType
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", exportType)
                    putExtra("exportPath", path)
                }
            }
        } else {
            toastOnUi(R.string.no_book)
        }
    }

    fun showExportConfig() {
        alert(R.string.export_config) {
            val fileName = mutableStateOf(AppConfig.bookExportFileName ?: "")
            val isEpub = mutableStateOf(AppConfig.exportType == 1)
            val charset = mutableStateOf(AppConfig.exportCharset)
            val noChapterName = mutableStateOf(AppConfig.exportNoChapterName)
            customView {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    AppOutlinedTextField(
                        value = fileName.value,
                        onValueChange = { fileName.value = it },
                        label = stringResource(R.string.export_file_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.export_type),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .selectable(selected = !isEpub.value, onClick = { isEpub.value = false })
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = !isEpub.value,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.accent),
                            )
                            Text("txt", color = AppTheme.colors.primaryText)
                        }
                        Row(
                            Modifier
                                .selectable(selected = isEpub.value, onClick = { isEpub.value = true })
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isEpub.value,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.accent),
                            )
                            Text("epub", color = AppTheme.colors.primaryText)
                        }
                    }
                    AppAutoCompleteField(
                        value = charset.value,
                        onValueChange = { charset.value = it },
                        label = stringResource(R.string.export_charset),
                        values = charsets.toList(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = noChapterName.value,
                                role = Role.Checkbox,
                                onValueChange = { noChapterName.value = it },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppCheckbox(checked = noChapterName.value, onCheckedChange = null)
                        Text(stringResource(R.string.export_no_chapter_name), color = AppTheme.colors.primaryText)
                    }
                }
            }
            okButton {
                AppConfig.bookExportFileName = fileName.value
                AppConfig.exportType = if (isEpub.value) 1 else 0
                AppConfig.exportCharset = charset.value.takeIf { it.isNotBlank() } ?: "UTF-8"
                AppConfig.exportNoChapterName = noChapterName.value
            }
            cancelButton()
        }
    }

    /**
     * 封面渲染槽: 注入到 shared BookshelfManageScreen 的 coverSlot 参数。
     * 沿用 View 版 CoverImageView(Glide + 默认封面自绘), 经 AndroidView 承载。
     */
    @Composable
    private fun BookCover(book: Book) {
        AndroidView(
            factory = { CoverImageView(it) },
            update = { iv ->
                iv.load(
                    book.getDisplayCover(),
                    book.name,
                    book.author,
                    false,
                    book.origin,
                    inBookshelf = true,
                )
            },
        )
    }

}
