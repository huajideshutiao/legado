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
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
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
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.neutralButton
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.BookCover
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
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick
import splitties.views.onLongClick

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
            if (!viewModel.inBookshelf) viewModel.delBook()
        }
    }
    private val localBookTreeSelect by lazy {
        registerHandleFile { result ->
            result.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
            }
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
        if (it.resultCode == RESULT_OK) viewModel.upEditBook()
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode != RESULT_CANCELED) viewModel.upSource()
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog.from(this) }
    private var editMenuItem: MenuItem? = null

    override val binding by viewBinding(ActivityBookInfoBinding::inflate)
    override val viewModel by viewModels<BookInfoViewModel>()

    @SuppressLint("PrivateResource")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initData()
        initViewEvent()
    }

    private fun initView() = binding.run {
        titleBar.setBackgroundResource(R.color.transparent)
        refreshLayout.setColorSchemeColors(accentColor)
        flAction.applyNavigationBarPadding()
        tvShelf.setTextColor(getPrimaryTextColor(ColorUtils.isColorLight(bottomBackground)))
        tvIntro.revealOnFocusHint = false
    }

    private fun initData() {
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) { upLoading(false, it) }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info, menu)
        editMenuItem = menu.findItem(R.id.menu_edit)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        val book = viewModel.bookData.value
        val hasSource = viewModel.curBookSource != null
        menu.findItem(R.id.menu_can_update)?.apply {
            isChecked = book?.canUpdate ?: true
            isVisible = hasSource
        }
        menu.findItem(R.id.menu_split_long_chapter)?.apply {
            isChecked = book?.getSplitLongChapter() ?: true
            isVisible = book?.isLocalTxt ?: false
        }
        menu.findItem(R.id.menu_login)?.isVisible = viewModel.curBookSource?.hasLogin() == true
        menu.findItem(R.id.menu_set_source_variable)?.isVisible = hasSource
        menu.findItem(R.id.menu_set_book_variable)?.isVisible = hasSource
        menu.findItem(R.id.menu_upload)?.isVisible = book?.origin == BookType.localTag
        menu.findItem(R.id.menu_download_local)?.isVisible =
            book?.origin?.startsWith(BookType.webDavTag) == true
        menu.findItem(R.id.menu_delete_alert)?.isChecked = LocalConfig.bookInfoDeleteAlert
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        val book = viewModel.getBook()
        when (item.itemId) {
            R.id.menu_edit -> book?.let {
                IntentData.book = it
                infoEditResult.launch {}
            }

            R.id.menu_share_it -> viewModel.curBook?.let { shareBook(it) }
            R.id.menu_refresh -> refreshBook()
            R.id.menu_login -> viewModel.curBookSource?.let {
                IntentData.book = viewModel.bookData.value
                it.showLoginDialog(this)
            }

            R.id.menu_top -> viewModel.topBook()
            R.id.menu_set_source_variable -> viewModel.curBookSource?.showSourceVariableDialog(this)
            R.id.menu_set_book_variable -> book?.showBookVariableDialog(
                this, viewModel.curBookSource
            )

            R.id.menu_copy_book_url -> book?.bookUrl?.let { sendToClip(it) }
            R.id.menu_copy_toc_url -> book?.tocUrl?.let { sendToClip(it) }
            R.id.menu_can_update -> book?.let {
                it.canUpdate = !it.canUpdate
                if (viewModel.inBookshelf) {
                    if (!it.canUpdate) it.removeType(BookType.updateError)
                    viewModel.saveBook(it)
                }
            }

            R.id.menu_clear_cache -> viewModel.clearCache()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_split_long_chapter -> book?.let {
                upLoading(true)
                it.setSplitLongChapter(!item.isChecked)
                lifecycleScope.launch { viewModel.loadBookInfo(it) }
                item.isChecked = !item.isChecked
                if (!item.isChecked) longToastOnUi(R.string.need_more_time_load_content)
            }

            R.id.menu_delete_alert -> LocalConfig.bookInfoDeleteAlert = !item.isChecked
            R.id.menu_upload -> book?.let { uploadBook(it) }
            R.id.menu_download_local -> book?.let { viewModel.downloadToLocal(it) }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun shareBook(book: Book) {
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

    private fun uploadBook(book: Book) {
        if (book.getRemoteUrl() != null) {
            alert(R.string.draw, R.string.sure_upload) {
                okButton { viewModel.uploadBook(book) }
                cancelButton()
            }
        } else viewModel.uploadBook(book)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            if (it == "selectBooksDir") localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === binding.tvIntro && binding.tvIntro.hasSelection()) it.clearFocus()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let { viewModel.refreshBook(it) }
    }

    private fun showBook(book: Book) = binding.run {
        applyDevFeatLayout(book)
        tvName.text = book.name
        tvAuthor.text = book.getRealAuthor()
        tvOrigin.text = book.originName
        tvLasted.text = getString(R.string.lasted_show, book.latestChapterTitle)
        tvIntro.text = book.getDisplayIntro()
        tvToc.visible(!book.isWebFile)
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
        showCover(book)
    }

    private fun applyDevFeatLayout(book: Book) = binding.run {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val useDevFeat = AppConfig.devFeat && !book.isVideo && !isLandscape

        if (useDevFeat) {
            setLightStatusBar(isDarkTheme)
            bgBook.gone()
            titleBar.setTextColor(primaryTextColor)
            titleBar.setColorFilter(primaryTextColor)
            titleBar.toolbar.menu.applyTint(this@BookInfoActivity, Theme.Auto)

            llTop.orientation = LinearLayout.HORIZONTAL
            cvCover.updateLayoutParams<LinearLayout.LayoutParams> {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
            }
            llInfoTop.updateLayoutParams<LinearLayout.LayoutParams> {
                width = 0
            }
            llInfoTop.setPadding(0, 0, llInfoTop.paddingRight, 0)
            tvName.gravity = Gravity.START
            llLasted.updateLayoutParams<LinearLayout.LayoutParams> {
                gravity = Gravity.START
            }
            tvWordCount.gravity = Gravity.START
        } else {
            setLightStatusBar(false)
            bgBook.visible()
            val textColor = getPrimaryTextColor(false)
            titleBar.setTextColor(textColor)
            titleBar.setColorFilter(textColor)
            titleBar.toolbar.menu.applyTint(this@BookInfoActivity, Theme.Dark)

            if (!isLandscape) {
                llTop.orientation = LinearLayout.VERTICAL
                cvCover.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                llInfoTop.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                }
                llInfoTop.setPadding(llInfoTop.paddingRight, 0, llInfoTop.paddingRight, 0)
                tvName.gravity = Gravity.CENTER
                llLasted.updateLayoutParams<LinearLayout.LayoutParams> {
                    gravity = Gravity.CENTER
                }
                tvWordCount.gravity = Gravity.CENTER
            }
        }
    }

    private fun upKinds(book: Book) = binding.run {
        book.kind?.splitNotBlank(",", "\n")?.let { bindKinds(lbKind, it) }

        lifecycleScope.launch {
            val wordCounts = arrayListOf<String>()
            book.wordCount?.takeIf { it.isNotBlank() }?.let { wordCounts.add(it) }
            if (book.isLocal) {
                val size = withContext(IO) {
                    try {
                        if (book.bookUrl.startsWith("http", true) || book.bookUrl.startsWith(
                                "dav", true
                            )
                        ) 0L
                        else FileDoc.fromFile(book.bookUrl).size
                    } catch (_: Exception) {
                        0L
                    }
                }
                if (size > 0) wordCounts.add(ConvertUtils.formatFileSize(size))
            }
            tvWordCount.isVisible = wordCounts.isNotEmpty() || book.isLocal
            if (wordCounts.isNotEmpty()) {
                tvWordCount.text = wordCounts.joinToString(",")
            }
        }
    }

    private fun bindKinds(container: LinearLayout, kinds: Array<String>) {
        if (kinds.isEmpty()) {
            container.gone()
            return
        }
        container.visible()
        container.removeAllViews()

        val groups = linkedMapOf<String, MutableList<Pair<String, String?>>>()
        val otherLabel = getString(R.string.other)
        kinds.forEach { kind ->
            val urlSplit = kind.split("::", limit = 2)
            val tagContent = urlSplit[0].trim()
            val groupSplit = tagContent.split(":", limit = 2)

            if (groupSplit.size > 1 && groupSplit.all { it.isNotBlank() }) {
                groups.getOrPut(groupSplit[0].trim()) { mutableListOf() }
                    .add(groupSplit[1].trim() to kind)
            } else if (tagContent.isNotBlank()) {
                groups.getOrPut(otherLabel) { mutableListOf() }.add(tagContent to kind)
            }
        }

        groups.forEach { (groupName, items) ->
            val flexboxLayout = FlexboxLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                flexWrap = FlexWrap.WRAP
            }
            if (groups.size > 1 || groupName != otherLabel) {
                addTagToFlexbox(flexboxLayout, groupName, null)
            }
            items.forEach { (value, fullKind) ->
                if (value.isNotEmpty()) addTagToFlexbox(flexboxLayout, value, fullKind)
            }
            container.addView(flexboxLayout)
        }
    }

    private fun addTagToFlexbox(
        flexboxLayout: FlexboxLayout, text: String, fullKind: String?
    ) {
        ItemFilletTextBinding.inflate(layoutInflater, flexboxLayout, false).apply {
            textView.text = text
            root.isClickable = fullKind != null
            root.isFocusable = fullKind != null

            if (fullKind == null) {
                textView.alpha = 0.8f
                textView.paint.isFakeBoldText = true
            } else {
                root.onClick {
                    search(fullKind)
                }
                root.onLongClick {
                    search(text, false)
                }
            }
            flexboxLayout.addView(root)
        }
    }

    private fun showCover(book: Book) = binding.run {
        val coverUrl = book.getDisplayCover()
        ivCover.coverRatio = if (book.isVideo) CoverImageView.CoverRatio.VIDEO
        else CoverImageView.CoverRatio.NOVEL
        ivCover.load(
            coverUrl,
            book.name,
            book.getRealAuthor(),
            false,
            book.origin,
            inBookshelf = viewModel.inBookshelf
        )
        if (!AppConfig.isEInkMode && bgBook.isVisible) {
            BookCover.loadBlur(
                Glide.with(this@BookInfoActivity),
                coverUrl,
                sourceOrigin = book.origin,
                inBookshelf = viewModel.inBookshelf
            ).transform(CenterCrop(), BlurTransformation(), BookInfoBgTransformation())
                .placeholder(bgBook.drawable).into(bgBook)
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        binding.tvToc.text = when {
            isLoading -> getString(R.string.loading)
            chapterList.isNullOrEmpty() -> getString(R.string.error_load_toc)
            else -> viewModel.curBook?.durChapterTitle
        }
        if (!isLoading && !chapterList.isNullOrEmpty()) {
            viewModel.curBook?.let {
                binding.tvLasted.text = getString(R.string.lasted_show, it.latestChapterTitle)
            }
        }
    }

    private fun upTvBookshelf() {
        binding.tvShelf.text =
            getString(if (viewModel.inBookshelf) R.string.remove_from_bookshelf else R.string.add_to_bookshelf)
        editMenuItem?.isVisible = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            binding.tvGroup.text = it.takeIf { !it.isNullOrEmpty() }
                ?: getString(R.string.no_group)
        }
    }

    private fun initViewEvent() = binding.run {
        ivCover.onLongClick {
            viewModel.getBook()
                ?.let { showDialogFragment(ChangeCoverDialog(it.name, it.getRealAuthor())) }
        }
        ivCover.onClick {
            viewModel.getBook()?.getDisplayCover()?.let { showDialogFragment(PhotoDialog(it)) }
        }
        tvRead.onClick {
            viewModel.getBook()?.let { book ->
                if (book.isWebFile) showWebFileDownloadAlert { readBook(it) } else readBook(book)
            }
        }
        tvShelf.onClick {
            viewModel.getBook()?.let {
                if (viewModel.inBookshelf) deleteBook()
                else if (it.isWebFile) showWebFileDownloadAlert()
                else viewModel.addToBookshelf {
                    setResult(RESULT_OK)
                    upTvBookshelf()
                }
            }
        }
        tvOrigin.onClick {
            if (viewModel.curBook?.isLocal == true) return@onClick
            viewModel.curBookSource?.let { editSourceResult.launch { IntentData.source = it } }
                ?: toastOnUi(R.string.error_no_source)
        }
        tvOrigin.onLongClick {
            viewModel.getBook()
                ?.let { showDialogFragment(ChangeBookSourceDialog(it.name, it.getRealAuthor())) }
        }
        tvToc.onClick {
            val chapters = viewModel.chapterListData.value
            if (chapters.isNullOrEmpty()) return@onClick toastOnUi(R.string.chapter_list_empty)
            viewModel.getBook()?.let {
                IntentData.book = it
                IntentData.chapterList = chapters
                tocActivityResult.launch(it.bookUrl)
            }
        }
        tvGroup.onClick {
            viewModel.getBook()?.let { showDialogFragment(GroupSelectDialog(it.group)) }
        }
        tvAuthor.onClick {
            viewModel.getBook(false)?.let { book ->
                val authors = book.author.splitNotBlank("\n")
                if (authors.isEmpty()) return@let
                if (authors.size == 1) search(authors[0])
                else PopupMenu(this@BookInfoActivity, it).apply {
                    authors.forEachIndexed { index, s ->
                        menu.add(
                            0, index, index, s.split("::")[0]
                        )
                    }
                    setOnMenuItemClickListener { menuItem -> search(authors[menuItem.itemId]); true }
                }.show()
            }
        }
        tvAuthor.onLongClick {
            search(tvAuthor.text.toString(), false)
        }
        tvName.onClick {
            viewModel.getBook(false)?.let {
                startActivity<SearchActivity> {
                    putExtra("key", it.name)
                }
            }
        }
        refreshLayout.setOnRefreshListener {
            refreshLayout.isRefreshing = false
            refreshBook()
        }
    }

    private fun search(author: String, submit: Boolean = true) {
        val tmp = author.split("::", limit = 2)
        if (tmp.size > 1) {
            IntentData.source = viewModel.curBookSource
            startActivity<ExploreShowActivity> {
                putExtra("exploreName", tmp[0])
                putExtra("exploreUrl", tmp[1])
            }
        } else startActivity<SearchActivity> {
            putExtra("key", tmp[0])
            putExtra("submit", submit)
            viewModel.curBookSource?.let {
                it.searchUrl?.let { _ ->
                    putExtra(
                        "searchScope", SearchScope(it).toString()
                    )
                }
            }
        }
    }


    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (!LocalConfig.bookInfoDeleteAlert) {
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_DELETED)
                    finish()
                }
                return
            }
            alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
                var checkBox: CheckBox? = null
                if (book.isLocal) {
                    checkBox = CheckBox(this@BookInfoActivity).apply {
                        setText(R.string.delete_book_file)
                        isChecked = LocalConfig.deleteBookOriginal
                    }
                    customView {
                        LinearLayout(this@BookInfoActivity).apply {
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(checkBox)
                        }
                    }
                }
                yesButton {
                    checkBox?.let { LocalConfig.deleteBookOriginal = it.isChecked }
                    viewModel.delBook(LocalConfig.deleteBookOriginal) {
                        setResult(RESULT_DELETED)
                        finish()
                    }
                }
                noButton()
            }
        }
    }

    private fun showWebFileDownloadAlert(onClick: ((Book) -> Unit)? = null) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) return toastOnUi("Unexpected webFileData")
        selector(R.string.download_and_import_file, webFiles) { _, webFile, _ ->
            if (webFile.isSupported) {
                viewModel.importWebFile(webFile) { onClick?.invoke(it) }
            } else if (webFile.isSupportDecompress) {
                viewModel.downloadWebFile(webFile) { uri ->
                    viewModel.getArchiveFilesName(uri) { fileNames ->
                        if (fileNames.size == 1) viewModel.importBookFromArchive(
                            uri, fileNames[0]
                        ) { onClick?.invoke(it) }
                        else showDecompressFileImportAlert(uri, fileNames, onClick)
                    }
                }
            } else {
                alert(
                    title = getString(R.string.draw),
                    message = getString(R.string.file_not_supported, webFile.name)
                ) {
                    neutralButton(R.string.open_fun) {
                        viewModel.downloadWebFile(webFile) { openFileUri(it, "*/*") }
                    }
                    noButton()
                }
            }
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri, fileNames: List<String>, success: ((Book) -> Unit)? = null
    ) {
        if (fileNames.isEmpty()) return toastOnUi(R.string.unsupport_archivefile_entry)
        selector(R.string.import_select_book, fileNames) { _, name, _ ->
            viewModel.importBookFromArchive(archiveFileUri, name) { success?.invoke(it) }
        }
    }

    private fun readBook(book: Book) {
        IntentData.chapterList = viewModel.chapterListData.value
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            startReadActivity(book)
        } else viewModel.saveBook(book) { startReadActivity(book) }
    }

    private fun startReadActivity(book: Book) {
        IntentData.book = book
        IntentData.chapterList = viewModel.chapterListData.value
        readBookResult.launch(
            Intent(
                this, when {
                    book.isAudio -> AudioPlayActivity::class.java
                    book.isVideo -> VideoPlayActivity::class.java
                    book.isImage -> ReadMangaActivity::class.java
                    book.isRss -> ReadRssActivity::class.java
                    else -> ReadBookActivity::class.java
                }
            ).putExtra("chapterChanged", chapterChanged)
        )
    }

    override val oldBook: Book? get() = viewModel.bookData.value

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) =
        viewModel.changeTo(source, book, toc)

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            showCover(book)
            if (viewModel.inBookshelf) viewModel.saveBook(book)
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) viewModel.saveBook(book)
            else if (groupId > 0) viewModel.addToBookshelf {
                setResult(RESULT_OK)
                upTvBookshelf()
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        if (isShow) waitDialog.run { setText("Loading....."); show(supportFragmentManager) }
        else waitDialog.dismissSafe()
    }
}
