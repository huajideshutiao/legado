package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.GlobalVars
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityArrangeBookBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogSelectSectionExportBinding
import io.legado.app.help.book.contains
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheAdapter
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setIconCompat
import io.legado.app.utils.shouldHideSoftInput
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.max

/**
 * 书架管理
 */
class BookshelfManageActivity :
    VMBaseActivity<ActivityArrangeBookBinding, BookshelfManageViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    BookAdapter.CallBack,
    CacheAdapter.CallBack,
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    override val binding by viewBinding(ActivityArrangeBookBinding::inflate)
    override val viewModel by viewModels<BookshelfManageViewModel>()
    override val groupList: ArrayList<BookGroup> = arrayListOf()
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    private val adapter by lazy { BookAdapter(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private var booksFlowJob: Job? = null
    private var menu: Menu? = null
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var books: List<Book>? = null
    private val waitDialog by lazy { WaitDialog(this) }
//    private val exportDir = registerForActivityResult(HandleFileContract()) {
//        it.uri?.let { uri ->
//            alert(R.string.export_success) {
//                if (uri.toString().isAbsUrl()) {
//                    setMessage(DirectLinkUpload.getSummary())
//                }
//                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
//                    editView.hint = getString(R.string.path)
//                    editView.setText(uri.toString())
//                }
//                customView { alertBinding.root }
//                okButton {
//                    sendToClip(uri.toString())
//                }
//            }
//        }
//    }

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub")

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->

        var uri = result.uri ?: return@registerForActivityResult
        var dirPath = if (uri.isContentScheme())uri.toString() else uri.path ?:return@registerForActivityResult

        ACache.get().put(exportBookPathKey, dirPath)

        if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
            configExportSection(dirPath, result.requestCode)
        } else {
            startExport(dirPath, result.requestCode)
        }
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            upTitle()
        }
        initSearchView()
        initRecyclerView()
        initOtherView()
        initGroupData()
        upBookDataByGroupId()
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

    private fun notifyItemChanged(bookUrl: String) {
        kotlin.runCatching {
            adapter.getItems().forEachIndexed { index, book ->
                if (bookUrl == book.bookUrl) {
                    adapter.notifyItemChanged(index, true)
                    return
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.batchChangeSourceState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_source_batch)
                waitDialog.show()
            } else {
                waitDialog.dismiss()
            }
        }
        viewModel.batchChangeSourceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            if (!CacheBook.isRun) {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_play_24dp)
                    item.setTitle(R.string.download_start)
                }
                menu?.applyTint(this)
            } else {
                menu?.findItem(R.id.menu_download)?.let { item ->
                    item.setIconCompat(R.drawable.ic_stop_black_24dp)
                    item.setTitle(R.string.stop)
                }
                menu?.applyTint(this)
            }
            notifyItemChanged(it)
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            notifyItemChanged(book.bookUrl)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookshelf_manage, menu)
        menu.iconItemOnLongClick(R.id.menu_download) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_cache_download)
                this.menu.applyOpenTint(this@BookshelfManageActivity)
                setOnMenuItemClickListener(this@BookshelfManageActivity)
            }.show()
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        menu.findItem(R.id.menu_open_book_info_by_click_title)?.isChecked =
            AppConfig.openBookInfoByClickTitle
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_replace)?.isChecked = AppConfig.exportUseReplace
        // 菜单打开时读取状态[enableCustomExport]
        menu.findItem(R.id.menu_enable_custom_export)?.isChecked = AppConfig.enableCustomExport
        menu.findItem(R.id.menu_export_no_chapter_name)?.isChecked = AppConfig.exportNoChapterName
        menu.findItem(R.id.menu_export_web_dav)?.isChecked = AppConfig.exportToWebDav
        menu.findItem(R.id.menu_export_pics_file)?.isChecked = AppConfig.exportPictureFile
        menu.findItem(R.id.menu_parallel_export)?.isChecked = AppConfig.parallelExportBook
        menu.findItem(R.id.menu_export_type)?.title =
            "${getString(R.string.export_type)}(${getTypeName()})"
        menu.findItem(R.id.menu_export_charset)?.title =
            "${getString(R.string.export_charset)}(${AppConfig.exportCharset})"
        return super.onMenuOpened(featureId, menu)
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        selectGroup(groupRequestCode, 0)
    }

    private fun upTitle() {
        searchView.queryHint = getString(R.string.screen) + " • " + viewModel.groupName
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upBookData()
                return false
            }

        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        itemTouchCallback.isCanDrag = AppConfig.bookshelfSort == 3
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        // When this page is opened, it is in selection mode
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initOtherView() {
        binding.selectActionBar.setMainActionText(R.string.move_to_group)
        binding.selectActionBar.inflateMenu(R.menu.bookshelf_menage_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        waitDialog.setOnCancelListener {
            viewModel.batchChangeSourceCoroutine?.cancel()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                adapter.notifyDataSetChanged()
                upMenu()
            }
        }
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            val bookSort = AppConfig.getBookSortByGroupId(viewModel.groupId)
            appDb.bookDao.flowByGroup(viewModel.groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
                .conflate().collect {
                    books = it
                    upBookData()
                    itemTouchCallback.isCanDrag = bookSort == 3
                }
        }
    }

    private fun upBookData() {
        books?.let { books ->
            val searchKey = searchView.query
            if (searchKey.isNullOrEmpty()) {
                adapter.setItems(books)
            } else {
                books.filter {
                    it.contains(searchKey.toString())
                }.let {
                    adapter.setItems(it)
                }
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_open_book_info_by_click_title -> {
                AppConfig.openBookInfoByClickTitle = !item.isChecked
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }

            R.id.menu_export_all_use_book_source -> viewModel.saveAllUseBookSourceToFile { file ->
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "bookSource.json",
                        file,
                        "application/json"
                    )
                }
            }

            R.id.menu_download,
            R.id.menu_download_after -> {
                if (!CacheBook.isRun) {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@BookshelfManageActivity,
                            book,
                            book.durChapterIndex,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@BookshelfManageActivity)
                }
            }

            R.id.menu_download_all -> {
                if (!CacheBook.isRun) {
                    adapter.getItems().forEach { book ->
                        CacheBook.start(
                            this@BookshelfManageActivity,
                            book,
                            0,
                            book.lastChapterIndex
                        )
                    }
                } else {
                    CacheBook.stop(this@BookshelfManageActivity)
                }
            }

            R.id.menu_export_all -> exportAll()
            R.id.menu_enable_replace -> AppConfig.exportUseReplace = !item.isChecked
            // 更改菜单状态[enableCustomExport]
            R.id.menu_enable_custom_export -> AppConfig.enableCustomExport = !item.isChecked
            R.id.menu_export_no_chapter_name -> AppConfig.exportNoChapterName = !item.isChecked
            R.id.menu_export_web_dav -> AppConfig.exportToWebDav = !item.isChecked
            R.id.menu_export_pics_file -> AppConfig.exportPictureFile = !item.isChecked
            R.id.menu_parallel_export -> AppConfig.parallelExportBook = !item.isChecked
            R.id.menu_export_folder -> {
                selectExportFolder(-1)
            }

            R.id.menu_export_file_name -> alertExportFileName()
            R.id.menu_export_type -> showExportTypeConfig()
            R.id.menu_export_charset -> showCharsetConfig()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()

            else -> if (item.groupId == R.id.menu_group) {
                viewModel.groupName = item.title.toString()
                upTitle()
                viewModel.groupId =
                    appDb.bookGroupDao.getByName(item.title.toString())?.groupId ?: 0
                upBookDataByGroupId()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection -> alertDelSelection()
            R.id.menu_export_all -> exportAll()
            R.id.menu_update_enable ->
                viewModel.upCanUpdate(adapter.selection, true)

            R.id.menu_update_disable ->
                viewModel.upCanUpdate(adapter.selection, false)

            R.id.menu_add_to_group -> selectGroup(addToGroupRequestCode, 0)
            R.id.menu_change_source -> showDialogFragment<SourcePickerDialog>()
            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(adapter.selection) { file ->
                exportDir.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }
            R.id.menu_clear_cache -> viewModel.clearCache(adapter.selection)
            R.id.menu_check_selected_interval -> adapter.checkSelectedInterval()
        }
        return false
    }

    private fun upMenu() {
        menu?.findItem(R.id.menu_book_group)?.subMenu?.let { subMenu ->
            subMenu.removeGroup(R.id.menu_group)
            groupList.forEach { bookGroup ->
                subMenu.add(R.id.menu_group, bookGroup.order, Menu.NONE, bookGroup.groupName)
            }
        }
    }

    private fun alertDelSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val checkBox = CheckBox(this@BookshelfManageActivity).apply {
                setText(R.string.delete_book_file)
                isChecked = LocalConfig.deleteBookOriginal
            }
            val view = LinearLayout(this@BookshelfManageActivity).apply {
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                addView(checkBox)
            }
            customView { view }
            okButton {
                LocalConfig.deleteBookOriginal = checkBox.isChecked
                viewModel.deleteBook(adapter.selection, checkBox.isChecked)
            }
            noButton()
        }
    }

    override fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(
            GroupSelectDialog(groupId, requestCode)
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) {
                    books[it].copy(group = groupId)
                }
                viewModel.updateBook(*array)
            }

            adapter.groupRequestCode -> {
                adapter.actionItem?.let {
                    viewModel.updateBook(it.copy(group = groupId))
                }
            }

            addToGroupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }
        }
    }

    override fun upSelectCount() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.getItems().size)
    }

    override fun updateBook(vararg book: Book) {
        viewModel.updateBook(*book)
    }

    override fun deleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (book.isLocal) {
                checkBox = CheckBox(this@BookshelfManageActivity).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@BookshelfManageActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            okButton {
                if (checkBox != null) {
                    LocalConfig.deleteBookOriginal = checkBox.isChecked
                }
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
            }
        }
    }

    override fun openBook(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            GlobalVars.nowBook
        }
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(adapter.selection, source)
        viewModel.batchChangeSourceState.value = true
    }

    override fun export(position: Int) {
        val path = ACache.get().getAsString(exportBookPathKey)
        lifecycleScope.launch {
            if (path.isNullOrEmpty() ||
                withContext(IO) { !FileDoc.fromDir(path).checkWrite() }
            ) {
                selectExportFolder(position)
            } else if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
                configExportSection(path, position)
            } else {
                startExport(path, position)
            }
        }
    }

    private fun exportAll() {
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder(-10)
        } else {
            startExport(path, -10)
        }
    }

    /**
     * 配置自定义导出对话框
     *
     * @param path  导出路径
     * @param position  book位置
     * @author Discut
     * @since 1.0.0
     */
    private fun configExportSection(path: String, position: Int) {

        val alertBinding = DialogSelectSectionExportBinding.inflate(layoutInflater)
            .apply {
                fun verifyExportFileNameJsStr(js: String): Boolean {
                    return tryParesExportFileName(js) && etEpubFilename.text.toString()
                        .isNotEmpty()
                }

                fun enableLyEtEpubFilenameIcon() {
                    lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    lyEtEpubFilename.setEndIconOnClickListener {
                        adapter.getItem(position)?.run {
                            lyEtEpubFilename.helperText =
                                if (verifyExportFileNameJsStr(etEpubFilename.text.toString()))
                                    "${resources.getString(R.string.result_analyzed)}: ${
                                        getExportFileName(
                                            "epub",
                                            1,
                                            etEpubFilename.text.toString()
                                        )
                                    }"
                                else "Error"
                        } ?: run {
                            lyEtEpubFilename.helperText = "Error"
                            AppLog.put("未找到书籍，position is $position")
                        }
                    }
                }
                etEpubSize.setText("1")
                // lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                etEpubFilename.text?.append(AppConfig.episodeExportFileName)
                // 存储解析文件名的jsStr
                etEpubFilename.let {
                    it.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus)
                            return@setOnFocusChangeListener
                        it.text?.run {
                            if (verifyExportFileNameJsStr(toString())) {
                                AppConfig.episodeExportFileName = toString()
                            }
                        }
                    }
                }
                tvAllExport.setOnClickListener {
                    cbAllExport.callOnClick()
                }
                tvSelectExport.setOnClickListener {
                    cbSelectExport.callOnClick()
                }
                cbSelectExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = true
                        etInputScope.isEnabled = true
                        etEpubFilename.isEnabled = true
                        enableLyEtEpubFilenameIcon()
                        cbAllExport.isChecked = false
                    }
                }
                cbAllExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = false
                        etInputScope.isEnabled = false
                        etEpubFilename.isEnabled = false
                        lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                        cbSelectExport.isChecked = false
                    }
                }

                etInputScope.onFocusChangeListener =
                    View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            etInputScope.hint = "1-5,8,10-18"
                        } else {
                            etInputScope.hint = ""
                        }
                    }

                // 默认选择自定义导出
                cbSelectExport.callOnClick()
            }
        val alertDialog = alert(titleResource = R.string.select_section_export) {
            customView { alertBinding.root }
            positiveButton(R.string.ok)
            cancelButton()
        }
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            alertBinding.apply {
                if (cbAllExport.isChecked) {
                    startExport(path, position)
                    alertDialog.hide()
                    return@apply
                }
                val epubScope = etInputScope.text.toString()
                if (!verificationField(epubScope)) {
                    etInputScope.error = appCtx.getString(R.string.error_scope_input)//"请输入正确的范围"
                    return@apply
                }
                etInputScope.error = null
                val epubSize = etEpubSize.text.toString().toIntOrNull() ?: 1
                adapter.getItem(position)?.let { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", "epub")
                        putExtra("exportPath", path)
                        putExtra("epubSize", epubSize)
                        putExtra("epubScope", epubScope)
                    }
                }
                alertDialog.hide()
            }

        }
    }

    private fun selectExportFolder(exportPosition: Int) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            requestCode = exportPosition
        }
    }

    private fun startExport(path: String, exportPosition: Int) {
        val exportType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        if (exportPosition == -10) {
            if (adapter.getItems().isNotEmpty()) {
                adapter.getItems().forEach { book ->
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
        } else if (exportPosition >= 0) {
            adapter.getItem(exportPosition)?.let { book ->
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", exportType)
                    putExtra("exportPath", path)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun alertExportFileName() {
        alert(R.string.export_file_name) {
            val message = "Variable: name, author."
            setMessage(message)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "file name js"
                editView.setText(AppConfig.bookExportFileName)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.bookExportFileName = alertBinding.editView.text?.toString()
            }
            cancelButton()
        }
    }

    private fun getTypeName(): String {
        return exportTypes.getOrElse(AppConfig.exportType) {
            exportTypes[0]
        }
    }

    private fun showExportTypeConfig() {
        selector(R.string.export_type, exportTypes) { _, i ->
            AppConfig.exportType = i
        }
    }

    private fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "charset name"
                editView.setFilterValues(charsets)
                editView.setText(AppConfig.exportCharset)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.exportCharset = alertBinding.editView.text?.toString() ?: "UTF-8"
            }
            cancelButton()
        }
    }

    override val cacheChapters: HashMap<String, HashSet<String>>
        get() = viewModel.cacheChapters

    override fun exportProgress(bookUrl: String): Int? {
        return ExportBookService.exportProgress[bookUrl]
    }

    override fun exportMsg(bookUrl: String): String? {
        return ExportBookService.exportMsg[bookUrl]
    }

}