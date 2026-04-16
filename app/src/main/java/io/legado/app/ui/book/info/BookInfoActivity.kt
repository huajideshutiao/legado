package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityBookInfoBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.book.addType
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.BookCover
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

class BookInfoActivity :
    VMBaseActivity<ActivityBookInfoBinding, BookInfoViewModel>(toolBarTheme = Theme.Dark),
    GroupSelectDialog.CallBack, ChangeBookSourceDialog.CallBack, ChangeCoverDialog.CallBack {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            viewModel.getBook(false)?.let { book ->
                lifecycleScope.launch {
                    withContext(IO) {
                        book.durChapterIndex = it.first
                        book.durChapterPos = it.second
                        chapterChanged = it.third
                        appDb.bookDao.update(book)
                    }
                    startReadActivity(book)
                }
            }
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook()
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.curBook = viewModel.curBook
        upLoading(false, listOf(BookChapter()))
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_DELETED)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        viewModel.upSource()
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private var editMenuItem: MenuItem? = null

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundResource(R.color.transparent)
        binding.refreshLayout?.setColorSchemeColors(accentColor)
        binding.arcView.setBgColor(backgroundColor)
        binding.llInfoTop.setBackgroundColor(backgroundColor)
        binding.llInfo.setBackgroundColor(backgroundColor)
        binding.flAction.setBackgroundColor(bottomBackground)
        binding.flAction.applyNavigationBarPadding()
        binding.tvShelf.setTextColor(getPrimaryTextColor(ColorUtils.isColorLight(bottomBackground)))
        binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
        binding.tvIntro.revealOnFocusHint = false
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) { upLoading(false, it) }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData()
        initViewEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_can_update)?.isChecked = viewModel.bookData.value?.canUpdate ?: true
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() ?: true
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.curBookSource?.loginUrl.isNullOrBlank()
        menu.findItem(R.id.menu_set_source_variable)?.isVisible = viewModel.curBookSource != null
        menu.findItem(R.id.menu_set_book_variable)?.isVisible = viewModel.curBookSource != null
        menu.findItem(R.id.menu_can_update)?.isVisible = viewModel.curBookSource != null
        menu.findItem(R.id.menu_split_long_chapter)?.isVisible =
            viewModel.bookData.value?.isLocalTxt ?: false
        menu.findItem(R.id.menu_upload)?.isVisible = viewModel.bookData.value?.isLocal ?: false
        menu.findItem(R.id.menu_delete_alert)?.isChecked = LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_edit -> {
                viewModel.getBook()?.let {
                    IntentData.book = it
                    infoEditResult.launch {}
                }
            }

            R.id.menu_share_it -> viewModel.curBook?.let { book ->
                share(
                    "[${
                        GSON.toJson(
                            mapOf(
                                "bookUrl" to book.bookUrl,
                                "tocUrl" to book.tocUrl,
                                "origin" to book.origin,
                                "originName" to book.originName,
                                "name" to book.name,
                                "author" to book.author,
                                "kind" to book.kind,
                                "coverUrl" to book.coverUrl,
                                "customCoverUrl" to book.customCoverUrl,
                                "intro" to book.intro,
                                "customIntro" to book.customIntro,
                                "type" to book.type,
                                "wordCount" to book.wordCount
                            )
                        )
                    }]"
                )
            }

            R.id.menu_refresh -> refreshBook()

            R.id.menu_login -> viewModel.curBookSource?.let {
                IntentData.book = viewModel.bookData.value
                it.showLoginDialog(this)
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> viewModel.curBookSource?.showSourceVariableDialog(this)

            R.id.menu_set_book_variable -> viewModel.getBook()
                ?.showBookVariableDialog(this, viewModel.curBookSource)

            R.id.menu_copy_book_url -> viewModel.getBook()?.bookUrl?.let {
                sendToClip(it)
            }

            R.id.menu_copy_toc_url -> viewModel.getBook()?.tocUrl?.let {
                sendToClip(it)
            }

            R.id.menu_can_update -> {
                viewModel.getBook()?.let {
                    it.canUpdate = !it.canUpdate
                    if (viewModel.inBookshelf) {
                        if (!it.canUpdate) {
                            it.removeType(BookType.updateError)
                        }
                        viewModel.saveBook(it)
                    }
                }
            }

            R.id.menu_clear_cache -> viewModel.clearCache()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_split_long_chapter -> {
                upLoading(true)
                viewModel.getBook()?.let {
                    it.setSplitLongChapter(!item.isChecked)
                    lifecycleScope.launch {
                        viewModel.loadBookInfo(it)
                    }
                }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
            R.id.menu_upload -> {
                viewModel.getBook()?.let { book ->
                    book.getRemoteUrl()?.let {
                        alert(R.string.draw, R.string.sure_upload) {
                            okButton {
                                upLoadBook(book)
                            }
                            cancelButton()
                        }
                    } ?: upLoadBook(book)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === binding.tvIntro && binding.tvIntro.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
    ) {
        lifecycleScope.launch {
            waitDialog.setText("上传中.....")
            waitDialog.show()
            try {
                bookWebDav?.upload(book) ?: throw NoStackTraceException("未配置webDav")
                //更新书籍最后更新时间,使之比远程书籍的时间新
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage)
            } finally {
                waitDialog.dismiss()
            }
        }
    }

    private fun showBook(book: Book) = binding.run {
        applyDevFeatLayout(book)
        showCover(book)
        tvName.text = book.name
        tvAuthor.text = getString(R.string.author_show, book.getRealAuthor())
        tvOrigin.text = getString(R.string.origin_show, book.originName)
        tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
        tvIntro.text = book.getDisplayIntro()
        llToc?.visible(!book.isWebFile)
        //tvToc.text = getString(R.string.toc_s, book.durChapterTitle)
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
    }

    private fun applyDevFeatLayout(book: Book) = binding.run {
        if (!AppConfig.devFeat || book.isVideo || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (bgBook.isVisible) return@run
            setLightStatusBar(false)
            bgBook.visible()
            arcView.visible()
            titleBar.setTextColor(getPrimaryTextColor(false))
            titleBar.setColorFilter(getPrimaryTextColor(false))
            tvName.gravity = Gravity.CENTER
            lbKind.setPadding(0, 0, 0, 0)
            llTop?.orientation = LinearLayout.VERTICAL
            (rlCover?.layoutParams as? LinearLayout.LayoutParams).apply {
                this?.width = LinearLayout.LayoutParams.MATCH_PARENT
            }
            (llInfoTop.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f
            }
            llInfoTop.apply { setPadding(paddingRight, 0, paddingRight, 0) }
            (lbKind.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER
        } else {
            setLightStatusBar(isDarkTheme)
            bgBook.gone()
            arcView.gone()
            titleBar.setTextColor(primaryTextColor)
            titleBar.setColorFilter(primaryTextColor)
            tvName.gravity = Gravity.START
            lbKind.setPadding((-4).dpToPx(), 0, 0, 0)
            llTop?.orientation = LinearLayout.HORIZONTAL
            (rlCover?.layoutParams as? LinearLayout.LayoutParams).apply {
                this?.width = LinearLayout.LayoutParams.WRAP_CONTENT
            }
            (llInfoTop.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f
            }
            llInfoTop.apply { setPadding(0, 0, paddingRight, 0) }
            (lbKind.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.START
        }
    }

    private fun upKinds(book: Book) = binding.run {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = FileDoc.fromFile(book.bookUrl).size
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                lbKind.gone()
            } else {
                lbKind.visible()
                val diff = kinds.size - lbKind.childCount
                if (diff > 0) {
                    repeat(diff) {
                        ItemFilletTextBinding.inflate(layoutInflater, lbKind, false).let {
                            lbKind.addView(it.root)
                        }
                    }
                } else if (diff < 0) {
                    repeat(-diff) {
                        lbKind.removeViewAt(lbKind.childCount - 1)
                    }
                }
                for ((index, kind) in kinds.withIndex()) {
                    ItemFilletTextBinding.bind(lbKind.getChildAt(index)).let {
                        it.root.id = index + 1000
                        val tmp = kind.split("::", limit = 2)
                        it.textView.text = tmp[0]
                        if (tmp.size > 1) {
                            it.root.onClick {
                                IntentData.source = viewModel.curBookSource
                                startActivity<ExploreShowActivity> {
                                    putExtra("exploreName", tmp[0])
                                    putExtra("exploreUrl", tmp[1])
                                }
                            }
                        } else {
                            it.root.setOnClickListener(null)
                        }
                    }
                }
            }
        }
    }

    private fun showCover(book: Book) {
        if (book.isVideo && binding.bgBook.isVisible) binding.ivCover.coverRatio =
            CoverImageView.CoverRatio.VIDEO
        else if (binding.ivCover.coverRatio != CoverImageView.CoverRatio.NOVEL) binding.ivCover.coverRatio =
            CoverImageView.CoverRatio.NOVEL
        binding.ivCover.load(
            book.getDisplayCover(),
            book.name,
            book.author,
            false,
            book.origin,
            inBookshelf = viewModel.inBookshelf
        ) {
            if (!AppConfig.isEInkMode && (!AppConfig.devFeat || book.isVideo || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)) {
                BookCover.loadBlur(
                    Glide.with(this),
                    book.getDisplayCover(),
                    sourceOrigin = book.origin,
                    inBookshelf = viewModel.inBookshelf
                ).placeholder(binding.bgBook.drawable).into(binding.bgBook)
            }
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                binding.tvToc.text = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                binding.tvToc.text = getString(
                    R.string.toc_s, getString(R.string.error_load_toc)
                )
            }

            else -> {
                viewModel.curBook?.let {
                    binding.tvToc.text = getString(R.string.toc_s, it.durChapterTitle)
                    binding.tvLasted.text = getString(R.string.lasted_show, it.latestChapterTitle)
                }
            }
        }
    }

    private fun upTvBookshelf() {
        if (viewModel.inBookshelf) {
            binding.tvShelf.text = getString(R.string.remove_from_bookshelf)
        } else {
            binding.tvShelf.text = getString(R.string.add_to_bookshelf)
        }
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            if (it.isNullOrEmpty()) {
                binding.tvGroup.text = if (viewModel.curBook?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                binding.tvGroup.text = getString(R.string.group_s, it)
            }
        }
    }

    private fun initViewEvent() = binding.run {
        ivCover.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        ivCover.setOnLongClickListener {
            viewModel.getBook()?.getDisplayCover()?.let { path ->
                showDialogFragment(PhotoDialog(path))
            }
            true
        }
        tvRead.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isWebFile) {
                    showWebFileDownloadAlert {
                        readBook(it)
                    }
                } else {
                    readBook(book)
                }
            }
        }
        tvShelf.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (viewModel.inBookshelf) {
                    deleteBook()
                } else {
                    if (book.isWebFile) {
                        showWebFileDownloadAlert()
                    } else {
                        viewModel.addToBookshelf {
                            setResult(RESULT_OK)
                            upTvBookshelf()
                        }
                    }
                }
            }
        }
        tvOrigin.setOnClickListener {
            viewModel.getBook()?.let { book ->
                if (book.isLocal) return@let
                if (!appDb.bookSourceDao.has(book.origin)) {
                    toastOnUi(R.string.error_no_source)
                    return@let
                }
                editSourceResult.launch {
                    putExtra("sourceUrl", book.origin)
                }
            }
        }
        tvChangeSource.setOnClickListener {
            viewModel.getBook()?.let { book ->
                showDialogFragment(ChangeBookSourceDialog(book.name, book.author))
            }
        }
        tvTocView.setOnClickListener {
            if (viewModel.chapterListData.value.isNullOrEmpty()) {
                toastOnUi(R.string.chapter_list_empty)
                return@setOnClickListener
            }
            viewModel.getBook()?.let {
                IntentData.book = it
                IntentData.chapterList = viewModel.chapterListData.value
                tocActivityResult.launch(it.bookUrl)
            }
        }
        tvChangeGroup.setOnClickListener {
            viewModel.getBook()?.let {
                showDialogFragment(
                    GroupSelectDialog(it.group)
                )
            }
        }
        tvAuthor.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                startActivity<SearchActivity> {
                    putExtra("key", book.author)
                }
            }
        }
        tvName.setOnClickListener {
            viewModel.getBook(false)?.let { book ->
                startActivity<SearchActivity> {
                    putExtra("key", book.name)
                }
            }
        }
        refreshLayout?.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let {
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw, messageResource = R.string.sure_del
                ) {
                    var checkBox: CheckBox? = null
                    if (it.isLocal) {
                        checkBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                        customView { view }
                    }
                    yesButton {
                        if (checkBox != null) {
                            LocalConfig.deleteBookOriginal = checkBox.isChecked
                        }
                        viewModel.delBook(LocalConfig.deleteBookOriginal) {
                            setResult(RESULT_DELETED)
                            finish()
                        }
                    }
                    noButton()
                }
            } else {
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_DELETED)
                    finish()
                }
            }
        }
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        selector(
            R.string.download_and_import_file, webFiles
        ) { _, webFile, _ ->
            if (webFile.isSupported) {/* import */
                viewModel.importOrDownloadWebFile<Book>(webFile) {
                    onClick?.invoke(it)
                }
            } else if (webFile.isSupportDecompress) {/* 解压筛选后再选择导入项 */
                viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                    viewModel.getArchiveFilesName(uri) { fileNames ->
                        if (fileNames.size == 1) {
                            viewModel.importArchiveBook(uri, fileNames[0]) {
                                onClick?.invoke(it)
                            }
                        } else {
                            showDecompressFileImportAlert(uri, fileNames, onClick)
                        }
                    }
                }
            } else {
                alert(
                    title = getString(R.string.draw),
                    message = getString(R.string.file_not_supported, webFile.name)
                ) {
                    neutralButton(R.string.open_fun) {
                        /* download only */
                        viewModel.importOrDownloadWebFile<Uri>(webFile) {
                            openFileUri(it, "*/*")
                        }
                    }
                    noButton()
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book, fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        IntentData.chapterList = viewModel.chapterListData.value
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            startReadActivity(book)
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(book: Book) {
        IntentData.book = book
        IntentData.chapterList = viewModel.chapterListData.value
        readBookResult.launch(
            Intent(
                this, when {
                    book.isAudio -> AudioPlayActivity::class.java
                    book.isVideo -> VideoPlayActivity::class.java
                    book.isImage && AppConfig.showMangaUi -> ReadMangaActivity::class.java
                    book.isRss -> ReadRssActivity::class.java
                    else -> ReadBookActivity::class.java
                }
            ).putExtra("chapterChanged", chapterChanged)
        )
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        viewModel.changeTo(source, book, toc)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            showCover(book)
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    setResult(RESULT_OK)
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
    }

}