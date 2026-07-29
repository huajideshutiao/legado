package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
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
import io.legado.app.utils.setLightStatusBar
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.rss.ReadRssActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.video.VideoPlayActivity
import io.legado.app.ui.widget.dialog.showBookVariableDialog
import io.legado.app.ui.widget.dialog.showSourceVariableDialog
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍详情页 Activity (薄壳模式)。
 *
 * 实现下沉到 shared 的 [BookInfoUiActions] 接口供 [BookInfoScreen] 回调,
 * 已有同名方法直接 `override`; 新增的 [onBack] / [onSearchAuthor] / [onSearchKind]
 * / [onDispatchIntroAction] / [onShowPhoto] / [onCopyBookUrl] / [onCopyTocUrl]
 * / [onShowLog] 等为下沉适配方法, 内部桥接到 Activity 行为。
 *
 * L3 (Android 专属) Composable (BlurCoverBg/BookInfoCover/IntroImage) 通过
 * [BookInfoAndroidSlots] 提供, 在 [Content] 内构造 slot lambda 注入。
 *
 * 状态字段 (book/bookTick/coverTick/inBookshelf/...) 由 Activity 托管,
 * [Content] 内打包为 [BookInfoUiState] 传入 shared 端 [BookInfoScreen]。
 */
class BookInfoActivity :
    BaseComposeActivity(toolBarTheme = Theme.Dark),
    GroupSelectDialog.CallBack, ChangeCoverDialog.CallBack,
    BookInfoUiActions {

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
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog.from(this) }

    val viewModel by viewModels<BookInfoViewModel>()

    // ---- Compose 状态(镜像原 binding 写点) ----
    var book by mutableStateOf<Book?>(null)
        private set
    var bookTick by mutableIntStateOf(0) // book 原地可变，post 时递增驱动重组
        private set
    var coverTick by mutableIntStateOf(0) // 封面/模糊背景重载 key(对照 showCover 时机)
        private set
    var inBookshelf by mutableStateOf(false)
        private set
    var groupName by mutableStateOf("")
        private set
    var tocText by mutableStateOf<String?>(null) // null=加载中
        private set
    var lastedTitle by mutableStateOf("")
        private set
    var wordCountText by mutableStateOf<String?>(null) // null=隐藏
        private set

    @Composable
    override fun Content() {
        // 横竖屏 + useDevFeat (对照原 BookInfoScreen 顶层判断) 由 Activity 计算后传入 state,
        // 避免 shared 端依赖 LocalConfiguration / AppConfig
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val book = this.book
        val useDevFeat = book != null && AppConfig.bookInfoHorizontalLayout &&
            !book.isVideo && !isLandscape
        LaunchedEffect(useDevFeat, isLandscape) {
            // useDevFeat 态下标题栏文字为深色, 需要亮色状态栏图标; 其余沿用 Theme.Dark
            setLightStatusBar(if (useDevFeat) isDarkTheme else false)
        }
        // 监听 BookInfoEdit 路由回传的 Ok 结果 (对齐原 infoEditResult launcher)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.BOOK_INFO_EDIT }
                ?.collect { if (it.payload is RouteResultPayload.Ok) viewModel.upEditBook() }
        }
        // 监听整书换源路由回传结果 (原 ChangeBookSourceDialog.CallBack)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.CHANGE_SOURCE }
                ?.collect { result ->
                    val payload = result.payload as? RouteResultPayload.ChangeSource
                        ?: return@collect
                    onChangeSourceResult(payload.source, payload.book, payload.toc)
                }
        }
        // 监听书源编辑路由回传结果 (原 editSourceResult 非 CANCELED)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.BOOK_SOURCE_EDIT }
                ?.collect { result ->
                    if (result.payload is RouteResultPayload.BookSourceEdit) {
                        viewModel.upSource()
                    }
                }
        }
        // 监听目录页路由回传结果 (原 tocActivityResult: 选章节跳阅读, 返回未选则删书)
        LaunchedEffect(Unit) {
            AppNavigatorProviders.getOrNull()?.results
                ?.filter { it.key == RouteResults.TOC }
                ?.collect { result ->
                    val payload = result.payload as? RouteResultPayload.Toc
                    val book = payload?.let { viewModel.getBook(false) }
                    if (payload != null && book != null) {
                        lifecycleScope.launch {
                            withContext(IO) {
                                book.durChapterIndex = payload.chapterIndex
                                book.durChapterPos = payload.chapterPos
                                chapterChanged = payload.chapterChanged
                                appDb.bookDao.update(book)
                            }
                            startReadActivity(book)
                        }
                    } else {
                        // 用户返回未选择章节, 或获取书籍失败 (原 parseResult 返回 null)
                        if (!viewModel.inBookshelf) viewModel.delBook()
                    }
                }
        }
        val menuState = BookInfoMenuState(
            isLocal = book?.origin == BookType.localTag,
            isWebDav = book?.origin?.startsWith(BookType.webDavTag) == true,
            hasSource = viewModel.curBookSource != null,
            sourceHasLogin = viewModel.curBookSource?.hasLogin() == true,
            sourceHasReviewRule = !viewModel.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
            canUpdate = book?.canUpdate ?: true,
            isLocalTxt = book?.isLocalTxt == true,
            splitLongChapter = book?.config?.splitLongChapter ?: false,
            bookUrl = book?.bookUrl,
            tocUrl = book?.tocUrl,
        )
        val state = BookInfoUiState(
            book = book,
            bookTick = bookTick,
            coverTick = coverTick,
            inBookshelf = inBookshelf,
            groupName = groupName,
            tocText = tocText,
            lastedTitle = lastedTitle,
            wordCountText = wordCountText,
            isLandscape = isLandscape,
            useDevFeat = useDevFeat,
            isDarkTheme = this.isDarkTheme,
            menuState = menuState,
        )
        BookInfoScreen(
            state = state,
            actions = this,
            blurCoverBgSlot = { modifier ->
                BookInfoBlurCoverBg(book, coverTick, viewModel.inBookshelf, AppConfig.isEInkMode, modifier)
            },
            coverSlot = { bookArg, modifier ->
                BookInfoCover(bookArg, coverTick, viewModel.inBookshelf, modifier)
            },
            introImageSlot = { src, onClick ->
                BookInfoIntroImage(src, onClick)
            },
        )
    }

    // ---- BookInfoUiActions 实现 ----
    // 已有同名方法直接 override; Activity 内部其它地方仍调用同名方法 (如 observeLiveBus 调 refreshBook)
    // 所以保留原名私有方法 + override 别名桥接, 避免改动其它调用点。

    override fun onBack() = onBackPressedDispatcher.onBackPressed()

    override fun onRefresh() = refreshBook()

    override fun onEdit() = editBook()

    override fun onShare() = shareBook()

    override fun onUploadBook() = uploadBook()

    override fun onDownloadToLocal() = downloadToLocal()

    override fun onTopBook() {
        viewModel.topBook()
    }

    override fun onLogin() = login()

    override fun onOpenCommentDialog() {
        viewModel.openCommentDialog(this)
    }

    override fun onSetSourceVariable() = setSourceVariable()

    override fun onSetBookVariable() = setBookVariable()

    override fun onCopyBookUrl() {
        viewModel.getBook()?.bookUrl?.let { sendToClip(it) }
    }

    override fun onCopyTocUrl() {
        viewModel.getBook()?.tocUrl?.let { sendToClip(it) }
    }

    override fun onToggleCanUpdate() = toggleCanUpdate()

    override fun onToggleSplitLongChapter() = toggleSplitLongChapter()

    override fun onClearCache() {
        viewModel.clearCache()
    }

    override fun onShowLog() {
        showDialogFragment<AppLogDialog>()
    }

    override fun onSearchAuthor(author: String, submit: Boolean) {
        search(author, submit)
    }

    override fun onSearchKind(kind: String, submit: Boolean) {
        search(kind, submit)
    }

    // 注: onNameClick/onCoverClick/onCoverLongClick/onOriginClick/onOriginLongClick/
    // onTocClick/onGroupClick/onShelfClick/onReadClick/onDispatchIntroAction/onShowPhoto
    // 直接 override 已有同名方法 (见下方各 fun 定义处)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) { upLoading(false, it) }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        viewModel.actionLive.observe(this) {
            if (it == "selectBooksDir") localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) {
            refreshBook()
        }
    }

    fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let { viewModel.refreshBook(it) }
    }

    private fun showBook(book: Book) {
        this.book = book
        bookTick++
        coverTick++
        lastedTitle = getString(R.string.lasted_show, book.latestChapterTitle)
        upTvBookshelf()
        upWordCount(book)
        upGroup(book.group)
    }

    private fun upWordCount(book: Book) {
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
            wordCountText = when {
                wordCounts.isNotEmpty() -> wordCounts.joinToString(",")
                book.isLocal -> ""
                else -> null
            }
        }
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        tocText = when {
            isLoading -> null
            chapterList.isNullOrEmpty() -> getString(R.string.error_load_toc)
            else -> viewModel.curBook?.durChapterTitle ?: ""
        }
        if (!isLoading && !chapterList.isNullOrEmpty()) {
            viewModel.curBook?.let {
                lastedTitle = getString(R.string.lasted_show, it.latestChapterTitle)
            }
        }
    }

    private fun upTvBookshelf() {
        inBookshelf = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            groupName = it.takeIf { !it.isNullOrEmpty() } ?: getString(R.string.no_group)
        }
    }

    // ---- 标题栏菜单动作(对照 onCompatOptionsItemSelected) ----

    fun editBook() {
        viewModel.getBook()?.let {
            IntentData.book = it
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.BookInfoEdit(it.toRouteRef()),
                resultKey = RouteResults.BOOK_INFO_EDIT,
            )
        }
    }

    fun shareBook() {
        viewModel.curBook?.let { book ->
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
    }

    fun login() {
        viewModel.curBookSource?.let {
            IntentData.book = viewModel.bookData.value
            it.showLoginDialog()
        }
    }

    fun setSourceVariable() {
        viewModel.curBookSource?.showSourceVariableDialog(this)
    }

    fun setBookVariable() {
        viewModel.getBook()?.showBookVariableDialog(this, viewModel.curBookSource)
    }

    fun toggleCanUpdate() {
        viewModel.getBook()?.let {
            it.canUpdate = !it.canUpdate
            bookTick++
            if (viewModel.inBookshelf) {
                if (!it.canUpdate) it.removeType(BookType.updateError)
                viewModel.saveBook(it)
            }
        }
    }

    fun toggleSplitLongChapter() {
        viewModel.getBook()?.let {
            upLoading(true)
            val newValue = !it.config.splitLongChapter
            it.config.splitLongChapter = newValue
            bookTick++
            lifecycleScope.launch { viewModel.loadBookInfo(it) }
            if (!newValue) longToastOnUi(R.string.need_more_time_load_content)
        }
    }

    fun uploadBook() {
        viewModel.getBook()?.let { book ->
            if (book.getRemoteUrl() != null) {
                alert(R.string.draw, R.string.sure_upload) {
                    okButton { viewModel.uploadBook(book) }
                    cancelButton()
                }
            } else viewModel.uploadBook(book)
        }
    }

    fun downloadToLocal() {
        viewModel.getBook()?.let { viewModel.downloadToLocal(it) }
    }

    // ---- 界面点击(对照 initViewEvent) ----

    override fun onCoverClick() {
        viewModel.getBook()?.getDisplayCover()?.let { showDialogFragment(PhotoDialog(it)) }
    }

    override fun onCoverLongClick() {
        viewModel.getBook()
            ?.let { showDialogFragment(ChangeCoverDialog(it.name, it.getRealAuthor())) }
    }

    override fun onReadClick() {
        viewModel.getBook()?.let { book ->
            if (book.isWebFile) showWebFileDownloadAlert { readBook(it) } else readBook(book)
        }
    }

    override fun onShelfClick() {
        viewModel.getBook()?.let {
            if (viewModel.inBookshelf) deleteBook()
            else if (it.isWebFile) showWebFileDownloadAlert()
            else viewModel.addToBookshelf {
                setResult(RESULT_OK)
                upTvBookshelf()
            }
        }
    }

    override fun onOriginClick() {
        if (viewModel.curBook?.isLocal == true) return
        viewModel.curBookSource?.let {
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.BookSourceEdit(it.bookSourceUrl),
                resultKey = RouteResults.BOOK_SOURCE_EDIT,
            )
        } ?: toastOnUi(R.string.error_no_source)
    }

    override fun onOriginLongClick() {
        viewModel.getBook()
            ?.let {
                AppNavigatorProviders.getOrNull()?.push(
                    AppRoute.ChangeSource(it.toRouteRef()),
                    resultKey = RouteResults.CHANGE_SOURCE,
                )
            }
    }

    override fun onTocClick() {
        val chapters = viewModel.chapterListData.value
        if (chapters.isNullOrEmpty()) return toastOnUi(R.string.chapter_list_empty)
        viewModel.getBook()?.let {
            IntentData.book = it
            IntentData.chapterList = chapters
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.Toc(it.toRouteRef()),
                resultKey = RouteResults.TOC,
            )
        }
    }

    override fun onGroupClick() {
        viewModel.getBook()?.let { showDialogFragment(GroupSelectDialog(it.group)) }
    }

    override fun onNameClick() {
        viewModel.getBook(false)?.let {
            AppNavigatorProviders.getOrNull()?.push(AppRoute.Search(key = it.name))
        }
    }

    override fun onDispatchIntroAction(action: String) {
        val js = action.trim().ifEmpty { return }
        val source = viewModel.curBookSource ?: return toastOnUi(R.string.error_no_source)
        try {
            source.evalJS(js) {
                this["book"] = viewModel.getBook()
            }
        } catch (e: Exception) {
            longToastOnUi(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    override fun onShowPhoto(src: String) {
        showDialogFragment(PhotoDialog(src))
    }

    fun search(author: String, submit: Boolean = true) {
        val tmp = author.split("::", limit = 2)
        if (tmp.size > 1) {
            // 对照 BookInfoRoute.onSearchAuthor: 直接用 curBookSource 跳 ExploreShow
            val source = viewModel.curBookSource ?: return
            AppNavigatorProviders.getOrNull()?.push(AppRoute.ExploreShow(source, tmp[0], tmp[1]))
        } else {
            // 对照原 startActivity<SearchActivity> intent 契约
            var searchScope: String? = null
            viewModel.curBookSource?.let { src ->
                src.searchUrl?.let { _ ->
                    searchScope = SearchScope(src).toString()
                }
            }
            AppNavigatorProviders.getOrNull()?.push(
                AppRoute.Search(key = tmp[0], searchScope = searchScope, submit = submit)
            )
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (!AppConfig.bookInfoDeleteAlert) {
                viewModel.delBook(LocalConfig.deleteBookOriginal) {
                    setResult(RESULT_DELETED)
                    finish()
                }
                return
            }
            alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
                val deleteFile = mutableStateOf(LocalConfig.deleteBookOriginal)
                if (book.isLocal) {
                    customView {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = deleteFile.value,
                                    role = Role.Checkbox,
                                    onValueChange = { deleteFile.value = it },
                                )
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppCheckbox(checked = deleteFile.value, onCheckedChange = null)
                            Text(stringResource(R.string.delete_book_file), color = AppTheme.colors.primaryText)
                        }
                    }
                }
                yesButton {
                    if (book.isLocal) LocalConfig.deleteBookOriginal = deleteFile.value
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
        } else startReadActivity(book)
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

    // 换源结果: 整书换源 (原 ChangeBookSourceDialog.CallBack.changeTo)
    private fun onChangeSourceResult(source: BookSource, book: Book, toc: List<BookChapter>) =
        viewModel.changeTo(source, book, toc)

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            coverTick++
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
