package io.legado.app.ui.bookinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.removeType
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.info.BookInfoMenuState

import io.legado.app.ui.book.info.BookInfoUiActions
import io.legado.app.ui.book.info.BookInfoUiState
import io.legado.app.ui.book.info.BookInfoViewModelShared
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.bookshelf.OhosBlurCoverBg
import io.legado.app.ui.bookshelf.OhosInfoCover
import io.legado.app.ui.bookshelf.OhosIntroImage
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.ui.widget.dialog.PhotoViewDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.formatNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 鸿蒙端书籍详情 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.info.BookInfoScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.bookinfo.IosBookInfoScreen] / desktop `BookInfoScreen.kt` 的包装模式,
 * 鸿蒙端在 OhosNavHost 的 BOOK_INFO 路由分支调用本入口。
 *
 * 本文件仅做鸿蒙平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **书籍转换**: [book] 入参用 [BaseBook] 统一持有 SearchBook/Book, 内部转 Book 供 UI 与 DAO 使用
 * - **书架状态**: LaunchedEffect 异步查 [AppDbProviders.get].bookDao.has(bookUrl), 命中则复用本地完整数据
 * - **UI state**: 构造 [BookInfoUiState], 鸿蒙端固定竖屏布局 (isLandscape=false),
 *   关闭 dev 布局 (useDevFeat=false) / 关闭暗色判定 (isDarkTheme=false 由宿主主题决定)
 * - **actions**: 实现 [BookInfoUiActions] 30 个方法, 核心动作接入真实路由/shared VM;
 *   onLogin/onGroupClick/onSetSourceVariable/onSetBookVariable/onShowLog 弹 shared/sharedUiMain
 *   下沉的 Dialog; onEdit/onNameClick/onSearchAuthor/onSearchKind 接入路由回调;
 *   onToggleCanUpdate/onToggleSplitLongChapter 原地修改 Book + bookTick++ + 落库
 * - **slots**: 封面/插图注入 [io.legado.app.ui.bookshelf.OhosBookCover] 系列组件 (真实加载)
 *
 * # 简化项 (与 iOS / desktop 差异)
 *
 * - 不接入 onClearCache/onOriginLongClick 等: 依赖未下沉能力 (BookHelp.clearCache / 书源长按菜单)
 *
 * @param book 详情页书籍 (SearchBook/Book), 由 OhosNavHost 注入
 * @param onBack 返回回调 (切回书架路由)
 * @param onReadClick "开始阅读" 回调 (切到 READER 路由, 携带 Book)
 * @param onEditClick "编辑信息" 回调 (切到 BOOK_INFO_EDIT 路由, 携带 bookUrl)
 * @param onOriginClick "切换来源" 回调 (鸿蒙端暂未接入 CHANGE_SOURCE 路由, 默认 no-op)
 * @param onTocClick "目录" 回调 (切到 TOC 路由, 携带 Book)
 * @param onSearchClick 搜索回调 (key, submit), 供 onSearchAuthor/onSearchKind/onNameClick 切到 SEARCH 路由
 * @param onOpenReviewPost "发布书评" 回调 (切到 REVIEW_POST 路由, 携带 Book)
 */
@Composable
fun OhosBookInfoScreen(
    book: BaseBook,
    onBack: () -> Unit,
    onReadClick: (Book) -> Unit,
    onEditClick: (String) -> Unit = {},
    onOriginClick: (Book) -> Unit = {},
    onTocClick: (Book) -> Unit = {},
    onSearchClick: (String, Boolean) -> Unit = { _, _ -> },
    onOpenReviewPost: (Book) -> Unit = {},
) {
    // SearchBook → Book 转换 (Book 直接用, 其他类型暂无, 兜底 null)
    val displayBook: Book? = remember(book) {
        when (book) {
            is Book -> book
            is io.legado.app.data.entities.SearchBook -> book.toBook()
            else -> null
        }
    }

    // 异步查书架状态 + 加载本地完整 Book
    var inBookshelf by remember { mutableStateOf(false) }
    var loadedBook by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(book.bookUrl) {
        val dao = AppDbProviders.get().bookDao
        inBookshelf = dao.has(book.bookUrl)
        if (inBookshelf) {
            loadedBook = dao.getBook(book.bookUrl)
        }
    }

    // 优先用本地完整 Book, 否则用 displayBook
    val effectiveBook: Book? = loadedBook ?: displayBook

    val scope = rememberCoroutineScope()

    // 复用 shared commonMain 的 BookInfoViewModelShared
    val shared = remember(scope) { BookInfoViewModelShared(scope = scope) }
    LaunchedEffect(effectiveBook) {
        shared.upBook(effectiveBook)
    }

    // 换封面 ViewModel (KMP 共享核心, commonMain ChangeCoverViewModelShared)
    // 鸿蒙端通过 OhosChangeCoverPlatform 注入 threadCount + cleanAuthor
    val changeCoverVm = remember(scope) {
        ChangeCoverViewModelShared(scope, OhosChangeCoverPlatform())
    }
    // effectiveBook 变化时初始化换封面 VM (与 desktop BookInfoScreen 模式一致)
    LaunchedEffect(effectiveBook) {
        val b = effectiveBook ?: return@LaunchedEffect
        changeCoverVm.initData(b.name, b.author)
    }

    // 分组管理 ViewModel
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    // 对话框状态
    var showLoginDialog by remember { mutableStateOf<BookSource?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showVariableDialog by remember { mutableStateOf(false) }
    var variableSource by remember { mutableStateOf<BookSource?>(null) }
    var showUploadConfirmDialog by remember { mutableStateOf(false) }
    // 换封面对话框状态 (false=隐藏, true=显示; onCoverLongClick 触发,
    // 末尾 ChangeCoverDialog 渲染分支读取)
    var showChangeCoverDialog by remember { mutableStateOf(false) }
    // 图片大图查看对话框状态 (null=隐藏, 非空=显示; onCoverClick/onShowPhoto 触发,
    // 末尾 PhotoViewDialog 渲染分支读取, 对照 app 端 BookInfoActivity 弹 PhotoDialog)
    var photoSrc by remember { mutableStateOf<String?>(null) }

    // state 加载: groupName / tocText / bookSource / bookTick
    var bookTick by remember { mutableStateOf(0) }
    var tocReloadTick by remember { mutableStateOf(0) }
    var groupName by remember { mutableStateOf("") }
    val noGroupLabel = rememberString("no_group")
    LaunchedEffect(effectiveBook?.group) {
        val groupId = effectiveBook?.group ?: return@LaunchedEffect
        shared.loadGroup(groupId) { names ->
            groupName = names?.takeIf { it.isNotEmpty() } ?: noGroupLabel
        }
    }
    var tocText by remember { mutableStateOf<String?>(null) }
    val errorLoadTocLabel = rememberString("error_load_toc")
    val getTocFailedTemplate = rememberString("get_toc_failed_log")
    val uploadSuccessText = rememberString("upload_success")
    val uploadFailedText = rememberString("upload_failed")
    LaunchedEffect(effectiveBook?.bookUrl, inBookshelf, tocReloadTick) {
        val b = effectiveBook ?: return@LaunchedEffect
        val dao = AppDbProviders.get().bookChapterDao
        var chapters: List<BookChapter> = dao.getChapterList(b.bookUrl)
        if (chapters.isEmpty() && !b.isLocal && b.tocUrl.isNotEmpty()) {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                try {
                    chapters = WebBook.getChapterListAwait(source, b).getOrThrow()
                    if (inBookshelf) {
                        dao.insert(*chapters.toTypedArray())
                    }
                } catch (e: Throwable) {
                    AppLog.put(getTocFailedTemplate.formatNative(e.localizedMessage), e)
                }
            }
        }
        tocText = when {
            chapters.isEmpty() -> errorLoadTocLabel
            else -> b.durChapterTitle
        }
    }
    var bookSource by remember { mutableStateOf<BookSource?>(null) }
    LaunchedEffect(effectiveBook?.origin) {
        val origin = effectiveBook?.origin ?: return@LaunchedEffect
        bookSource = AppDbProviders.get().bookSourceDao.getBookSource(origin)
    }

    val state = remember(effectiveBook, inBookshelf, bookTick, bookSource, groupName, tocText) {
        BookInfoUiState(
            book = effectiveBook,
            bookTick = bookTick,
            coverTick = 0,
            inBookshelf = inBookshelf,
            groupName = groupName,
            tocText = tocText,
            lastedTitle = effectiveBook?.latestChapterTitle ?: "",
            wordCountText = effectiveBook?.wordCount,
            isLandscape = false,
            useDevFeat = false,
            isDarkTheme = false,
            menuState = BookInfoMenuState(
                isLocal = effectiveBook?.isLocal == true,
                isWebDav = effectiveBook?.origin?.startsWith(BookType.webDavTag) == true,
                hasSource = bookSource != null,
                sourceHasLogin = bookSource?.hasLogin() == true,
                sourceHasReviewRule = !bookSource?.reviewRule?.reviewUrl.isNullOrBlank(),
                canUpdate = effectiveBook?.canUpdate ?: true,
                isLocalTxt = effectiveBook?.isLocalTxt == true,
                splitLongChapter = effectiveBook?.config?.splitLongChapter ?: false,
                bookUrl = effectiveBook?.bookUrl,
                tocUrl = effectiveBook?.tocUrl,
            ),
        )
    }

    // 上传书籍协程
    val launchUpload: () -> Unit = {
        scope.launch {
            val b = effectiveBook ?: return@launch
            try {
                shared.upWaitDialog(true)
                AppWebDavShared.uploadBook(b)
                Toasters.get().toast(uploadSuccessText)
            } catch (e: Throwable) {
                Toasters.get().toast(e.localizedMessage ?: uploadFailedText)
            } finally {
                shared.upWaitDialog(false)
            }
        }
    }

    val actions = remember(
        effectiveBook, inBookshelf, onBack, onReadClick, onEditClick, onOriginClick, onTocClick,
        onSearchClick, onOpenReviewPost, shared, scope, bookSource,
    ) {
        OhosBookInfoActions(
            book = effectiveBook,
            inBookshelf = inBookshelf,
            onBack = onBack,
            onReadClick = onReadClick,
            onEditClick = onEditClick,
            onSearchClick = onSearchClick,
            bookSource = bookSource,
            shared = shared,
            scope = scope,
            onOriginClick = onOriginClick,
            onTocClick = onTocClick,
            onShelfClick = {
                scope.launch {
                    val dao = AppDbProviders.get().bookDao
                    val b = effectiveBook ?: return@launch
                    if (inBookshelf) {
                        dao.delete(b)
                    } else {
                        dao.insert(b)
                    }
                    inBookshelf = !inBookshelf
                }
            },
            onToggleCanUpdateCb = {
                val b = effectiveBook
                if (b != null) {
                    b.canUpdate = !b.canUpdate
                    bookTick++
                    if (inBookshelf) {
                        if (!b.canUpdate) b.removeType(BookType.updateError)
                        scope.launch(Dispatchers.IO) {
                            AppDbProviders.get().bookDao.update(b)
                        }
                    }
                }
            },
            onToggleSplitLongChapterCb = {
                val b = effectiveBook
                if (b != null) {
                    b.config.splitLongChapter = !b.config.splitLongChapter
                    bookTick++
                    tocReloadTick++
                }
            },
            onLoginCb = { src -> showLoginDialog = src },
            onGroupClickCb = { showGroupManage = true },
            onShowLogCb = { showLogDialog = true },
            onVariableCb = { src ->
                variableSource = src
                showVariableDialog = true
            },
            onUploadBookCb = {
                effectiveBook?.let { b ->
                    if (b.getRemoteUrl() != null) {
                        showUploadConfirmDialog = true
                    } else {
                        launchUpload()
                    }
                }
            },
            onOpenReviewPostCb = {
                // "发布书评" 菜单入口 (sourceHasReviewRule=true 时显示), 切到 REVIEW_POST 路由
                effectiveBook?.let { onOpenReviewPost(it) }
            },
            // onCoverLongClick 触发换封面对话框显示 (对照 desktop BookInfoScreen.onCoverLongClickCb)
            onCoverLongClickCb = { showChangeCoverDialog = true },
            // onCoverClick/onShowPhoto 触发图片大图查看对话框显示 (对照 app 端弹 PhotoDialog)
            onShowPhotoCb = { src -> photoSrc = src },
        )
    }

    // 调用 shared/sharedUiMain 的 BookInfoScreen, 注入鸿蒙端 3 个 stub slot
    io.legado.app.ui.book.info.BookInfoScreen(
        state = state,
        actions = actions,
        blurCoverBgSlot = { modifier -> OhosBlurCoverBg(effectiveBook, modifier) },
        coverSlot = { b, modifier -> OhosInfoCover(b, modifier) },
        introImageSlot = { src, onClick -> OhosIntroImage(src, onClick = onClick) },
    )

    // 书源登录对话框
    showLoginDialog?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { showLoginDialog = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }

    // 分组管理对话框
    if (showGroupManage) {
        GroupManageDialog(
            groups = groups,
            onAddGroup = { name ->
                groupVm.addGroup(
                    groupName = name,
                    bookSort = -1,
                    enableRefresh = true,
                    cover = null,
                ) {}
            },
            onRenameGroup = { groupId, newName ->
                groups.find { it.groupId == groupId.toLong() }?.let {
                    groupVm.upGroup(it.copy(groupName = newName))
                }
            },
            onDeleteGroup = { groupId ->
                groups.find { it.groupId == groupId.toLong() }?.let {
                    groupVm.delGroup(it) {}
                }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // 应用日志对话框
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // 变量编辑对话框
    if (showVariableDialog) {
        variableSource?.let { src ->
            effectiveBook?.let { b ->
                VariableDialog(
                    sourceVariables = decodeStringMapOrNull(src.getVariable()) ?: emptyMap(),
                    bookVariables = b.variableMap,
                    onConfirm = { newSourceVars, newBookVars ->
                        scope.launch {
                            src.setVariable(encodeStringMap(newSourceVars))
                            b.variableMap.apply {
                                clear()
                                putAll(newBookVars)
                            }
                            b.variable = encodeStringMap(newBookVars)
                            AppDbProviders.get().bookDao.update(b)
                        }
                        showVariableDialog = false
                        variableSource = null
                    },
                    onDismiss = {
                        showVariableDialog = false
                        variableSource = null
                    },
                )
            }
        }
    }

    // ---- 图片大图查看对话框 (onCoverClick/onShowPhoto 触发, 对照 app 端 PhotoDialog) ----
    // 消费 sharedUiMain PhotoViewDialog (ImageBitmapLoader 鸿蒙 actual=Skia 解码 + 共享 zoomable 手势;
    // 传 book/bookSource 让网络图带书源防盗链 header, 对照 app 端 PhotoDialog sourceOrigin)
    photoSrc?.let { src ->
        PhotoViewDialog(
            src = src,
            onDismiss = { photoSrc = null },
            book = effectiveBook,
            bookSource = bookSource,
        )
    }

    // 上传书籍确认对话框
    if (showUploadConfirmDialog) {
        AppAlertDialog(
            onDismissRequest = { showUploadConfirmDialog = false },
            title = rememberString("draw"),
            message = rememberString("sure_upload"),
            okButton = AlertButton(text = rememberString("ok")) { launchUpload() },
            cancelButton = AlertButton(text = rememberString("cancel")),
        )
    }

    // ---- 换封面对话框 (onCoverLongClick 触发, 调用 shared/sharedUiMain 下沉的 ChangeCoverDialog) ----
    // 对照 desktop BookInfoScreen.onCoverLongClick → showDialogFragment(ChangeCoverDialog);
    // onCoverSelected 写入 book.customCoverUrl + 落库 (对照 app 端 BookInfoActivity.coverChangeTo:
    //   book.customCoverUrl = coverUrl; if (inBookshelf) saveBook(book))
    // coverSlot 复用现有 OhosInfoCover (SearchBook 转 Book 后传入, 与 desktop 模式一致)
    if (showChangeCoverDialog) {
        ChangeCoverDialog(
            viewModel = changeCoverVm,
            onCoverSelected = { coverUrl ->
                effectiveBook?.let { b ->
                    b.customCoverUrl = coverUrl
                    if (inBookshelf) {
                        scope.launch {
                            AppDbProviders.get().bookDao.update(b)
                        }
                    }
                }
            },
            onDismiss = { showChangeCoverDialog = false },
            coverSlot = { searchBook, modifier ->
                OhosInfoCover(searchBook.toBook(), modifier)
            },
        )
    }
}

/**
 * 鸿蒙端 [BookInfoUiActions] 实现 (对照 iOS `IosBookInfoActions` / desktop `DesktopBookInfoActions`)。
 *
 * 30 个回调中:
 * - 真实实现: onBack / onReadClick / onShelfClick / onOriginClick / onTocClick / onTopBook
 *   (复用 BookInfoViewModelShared) / onRefresh / onLogin / onGroupClick / onSetSourceVariable /
 *   onSetBookVariable / onShowLog / onEdit / onSearchAuthor / onSearchKind / onNameClick /
 *   onToggleCanUpdate / onToggleSplitLongChapter / onDispatchIntroAction /
 *   onCoverClick / onShowPhoto (弹 sharedUiMain PhotoViewDialog 查看大图)
 * - no-op + TODO: 其余依赖未下沉 Dialog 或鸿蒙平台 actual 的动作
 */
private class OhosBookInfoActions(
    private val book: Book?,
    private val inBookshelf: Boolean,
    private val onBack: () -> Unit,
    private val onReadClick: (Book) -> Unit,
    private val onEditClick: (String) -> Unit,
    private val onSearchClick: (String, Boolean) -> Unit,
    private val bookSource: BookSource?,
    private val shared: BookInfoViewModelShared,
    private val scope: CoroutineScope,
    private val onOriginClick: (Book) -> Unit,
    private val onTocClick: (Book) -> Unit,
    private val onShelfClick: () -> Unit,
    private val onToggleCanUpdateCb: () -> Unit,
    private val onToggleSplitLongChapterCb: () -> Unit,
    private val onLoginCb: (BookSource) -> Unit,
    private val onGroupClickCb: () -> Unit,
    private val onShowLogCb: () -> Unit,
    private val onVariableCb: (BookSource) -> Unit,
    private val onUploadBookCb: () -> Unit,
    private val onOpenReviewPostCb: () -> Unit,
    // onCoverLongClick 触发回调: 由 OhosBookInfoScreen 注入, 弹 ChangeCoverDialog
    // (对照 desktop BookInfoScreen.onCoverLongClickCb)
    private val onCoverLongClickCb: () -> Unit,
    // onCoverClick/onShowPhoto 触发回调: 由 OhosBookInfoScreen 注入, 弹 PhotoViewDialog 查看大图
    // (对照 app 端 BookInfoActivity.onCoverClick/onShowPhoto → showDialogFragment(PhotoDialog))
    private val onShowPhotoCb: (String) -> Unit,
) : BookInfoUiActions {

    override fun onBack() = onBack.invoke()

    override fun onEdit() {
        book?.bookUrl?.let { onEditClick(it) }
    }

    override fun onShare() {
        // 鸿蒙端复制书籍 URL 到剪贴板替代分享
        book?.bookUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onRefresh() {
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            shared.refreshBookSourceName(b, source)
        }
    }

    override fun onUploadBook() {
        onUploadBookCb.invoke()
    }

    override fun onDownloadToLocal() {
        val b = book ?: return
        scope.launch {
            try {
                FileBook.downloadRemoteBook(b)
                Toasters.get().toast(sharedStringTable["download_success"]!!)
                shared.upBook(b)
            } catch (e: Throwable) {
                AppLog.put(sharedStringTable["download_remote_book_failed_log"]!!.formatNative(b.name), e, true)
            }
        }
    }

    override fun onTopBook() {
        shared.topBook()
    }

    override fun onLogin() {
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onLoginCb.invoke(source)
            }
        }
    }

    override fun onOpenCommentDialog() {
        // 切到 REVIEW_POST 路由 (书源 reviewRule 入口, 鸿蒙端暂以发布书评 Screen stub)
        // 真实流程: BookInfo → ReviewListDialog → ReviewPostActivity (段评列表 → 发布段评)
        // 鸿蒙端 ReviewListDialog 未下沉, 直接切到 REVIEW_POST 路由 (后续接入 ReviewListDialog 后改回)
        onOpenReviewPostCb()
    }

    override fun onSetSourceVariable() {
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onVariableCb.invoke(source)
            }
        }
    }

    override fun onSetBookVariable() {
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onVariableCb.invoke(source)
            }
        }
    }

    override fun onCopyBookUrl() {
        book?.bookUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onCopyTocUrl() {
        book?.tocUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onToggleCanUpdate() {
        onToggleCanUpdateCb.invoke()
    }

    override fun onToggleSplitLongChapter() {
        onToggleSplitLongChapterCb.invoke()
    }

    override fun onClearCache() {
        // TODO: 清除书籍缓存 (BookHelp.clearCache), 鸿蒙端 BookHelpAccessor 未提供 clearCache
    }

    override fun onShowLog() {
        onShowLogCb.invoke()
    }

    override fun onNameClick() {
        val name = book?.name ?: return
        onSearchClick(name, true)
    }

    override fun onCoverClick() {
        // 查看封面大图 (对照 app 端 BookInfoActivity.onCoverClick → PhotoDialog(getDisplayCover))
        book?.getDisplayCover()?.let { onShowPhotoCb.invoke(it) }
    }

    override fun onCoverLongClick() {
        // 弹 ChangeCoverDialog (书源搜索换封面, KMP 共享核心)
        // 对照 desktop BookInfoScreen.onCoverLongClick → onCoverLongClickCb;
        // OhosBookInfoScreen 末尾 ChangeCoverDialog 渲染分支读取 showChangeCoverDialog,
        // onCoverSelected 写入 book.customCoverUrl + 落库
        onCoverLongClickCb.invoke()
    }

    override fun onOriginClick() {
        book?.let { onOriginClick(it) }
    }

    override fun onOriginLongClick() {
        // TODO: 长按书源菜单 (复制/调试/编辑), 鸿蒙端未下沉
    }

    override fun onTocClick() {
        book?.let { onTocClick(it) }
    }

    override fun onGroupClick() {
        onGroupClickCb.invoke()
    }

    override fun onShelfClick() {
        onShelfClick.invoke()
    }

    override fun onReadClick() {
        book?.let { onReadClick.invoke(it) }
    }

    override fun onSearchAuthor(author: String, submit: Boolean) {
        onSearchClick(author, submit)
    }

    override fun onSearchKind(kind: String, submit: Boolean) {
        onSearchClick(kind, submit)
    }

    override fun onDispatchIntroAction(action: String) {
        val js = action.trim().ifEmpty { return }
        val source = bookSource ?: return
        try {
            source.evalJS(js) {
                this["book"] = book
            }
        } catch (e: Exception) {
            AppLog.put(sharedStringTable["intro_action_failed_log"]!!.formatNative(e.localizedMessage), e)
        }
    }

    override fun onShowPhoto(src: String) {
        // 简介内 <img src="..."> 点击查看大图 (对照 app 端 onShowPhoto → PhotoDialog(src))
        onShowPhotoCb.invoke(src)
    }
}

