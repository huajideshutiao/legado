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
import io.legado.app.ui.bookshelf.IosBlurCoverBg
import io.legado.app.ui.bookshelf.IosInfoCover
import io.legado.app.ui.bookshelf.IosIntroImage
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
 * iOS 端书籍详情 Screen 入口 (KP4: 包装 shared/sharedUiMain 的 [io.legado.app.ui.book.info.BookInfoScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/bookinfo/BookInfoScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 BOOK_INFO 路由分支调用本入口。
 *
 * 本文件仅做 iOS 平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **书籍转换**: [book] 入参用 [BaseBook] 统一持有 SearchBook/Book, 内部转 Book 供 UI 与
 *   DAO 使用 (SearchBook.toBook() / Book 直接用)
 * - **书架状态**: LaunchedEffect 异步查 [AppDbProviders.get].bookDao.has(bookUrl),
 *   命中则 loadedBook=dao.getBook(bookUrl) 复用本地完整数据 (与 desktop 一致)
 * - **UI state**: 构造 [BookInfoUiState], iOS 端固定竖屏布局 (isLandscape=false),
 *   关闭 dev 布局 (useDevFeat=false) / 关闭暗色判定 (isDarkTheme=false 由宿主主题决定)
 * - **actions**: 实现 [BookInfoUiActions] 30 个方法, 核心动作 (onBack/onReadClick/onShelfClick/
 *   onOriginClick/onTocClick/onTopBook/onRefresh) 接入真实路由/shared VM;
 *   onLogin/onGroupClick/onSetSourceVariable/onSetBookVariable/onShowLog 弹 shared/sharedUiMain
 *   下沉的 Dialog (SourceLoginDialog/GroupManageDialog/VariableDialog/AppLogDialog, iOS 端直接复用);
 *   onEdit/onNameClick/onSearchAuthor/onSearchKind 接入路由回调 (onEditClick/onSearchClick);
 *   onToggleCanUpdate/onToggleSplitLongChapter 原地修改 Book + bookTick++ + 落库;
 *   onDispatchIntroAction 用 bookSource.evalJS 执行简介按钮 JS;
 *   onShare/onCopyBookUrl/onCopyTocUrl 用 UIPasteboard 复制 URL;
 *   其余暂为 no-op + TODO 注释 (依赖未下沉 Dialog 或 iOS 平台 actual)
 * - **slots**: iOS 端封面/插图加载, 用 [IosBlurCoverBg] / [IosInfoCover] / [IosIntroImage]
 *   (UIImage + Skia ImageBitmap 桥接, 详见 [io.legado.app.ui.bookshelf.IosBookCover] 文档)
 *
 * # 简化项 (与 desktop 差异)
 *
 * - 不接入 onClearCache/onOriginLongClick 等: 依赖未下沉能力 (BookHelp.clearCache / 书源长按菜单)
 *
 * @param book 详情页书籍 (SearchBook/Book), 由 IosNavHost 注入
 * @param onBack 返回回调 (切回书架路由)
 * @param onReadClick "开始阅读" 回调 (切到 READER 路由, 携带 Book)
 * @param onEditClick "编辑信息" 回调 (切到 BOOK_INFO_EDIT 路由, 携带 bookUrl; 对照 desktop)
 * @param onOriginClick "切换来源" 回调 (iOS 端暂未接入 CHANGE_SOURCE 路由, 默认 no-op)
 * @param onTocClick "目录" 回调 (iOS 端暂未接入 TOC 路由, 默认 no-op)
 * @param onSearchClick 搜索回调 (key, submit), 供 onSearchAuthor/onSearchKind/onNameClick
 *   切到 SEARCH 路由并预填关键词 (对照 app 端 SearchActivity putExtra("key", ...))
 */
@Composable
fun IosBookInfoScreen(
    book: BaseBook,
    onBack: () -> Unit,
    onReadClick: (Book) -> Unit,
    onEditClick: (String) -> Unit = {},
    onOriginClick: (Book) -> Unit = {},
    onTocClick: (Book) -> Unit = {},
    onSearchClick: (String, Boolean) -> Unit = { _, _ -> },
) {
    // SearchBook → Book 转换 (Book 直接用, 其他类型暂无, 兜底 null; 与 desktop 一致)
    val displayBook: Book? = remember(book) {
        when (book) {
            is Book -> book
            is SearchBook -> book.toBook()
            else -> null
        }
    }

    // 异步查书架状态 + 加载本地完整 Book (若已在书架; 与 desktop BookInfoScreen line 92-100 一致)
    var inBookshelf by remember { mutableStateOf(false) }
    var loadedBook by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(book.bookUrl) {
        val dao = AppDbProviders.get().bookDao
        inBookshelf = dao.has(book.bookUrl)
        if (inBookshelf) {
            loadedBook = dao.getBook(book.bookUrl)
        }
    }

    // 优先用本地完整 Book (含章节/进度等), 否则用 displayBook (SearchBook 转出的精简版)
    val effectiveBook: Book? = loadedBook ?: displayBook

    val scope = rememberCoroutineScope()

    // 复用 shared commonMain 的 BookInfoViewModelShared, 让 onTopBook 等无 Android 依赖的
    // 业务方法真正工作 (与 desktop BookInfoScreen line 114-117 一致)
    val shared = remember(scope) { BookInfoViewModelShared(scope = scope) }
    LaunchedEffect(effectiveBook) {
        shared.upBook(effectiveBook)
    }

    // 换封面 ViewModel (KMP 共享核心, IosChangeCoverPlatform 注入, 对照 desktop/ohos BookInfoScreen)
    val changeCoverVm = remember(scope) {
        ChangeCoverViewModelShared(scope, IosChangeCoverPlatform())
    }
    LaunchedEffect(effectiveBook) {
        val b = effectiveBook ?: return@LaunchedEffect
        changeCoverVm.initData(b.name, b.author)
    }
    // 换封面对话框状态 (onCoverLongClick 触发, 末尾 ChangeCoverDialog 渲染分支读取)
    var showChangeCoverDialog by remember { mutableStateOf(false) }

    // 分组管理 ViewModel (shared commonMain GroupViewModelShared, 供 GroupManageDialog 增删改分组)
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    // 全部分组 (订阅 bookGroupDao.flowAll(), 供 GroupManageDialog 展示分组列表)
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    // 书源登录对话框状态 (null=隐藏, 非空=显示; onLogin 触发后异步按 origin 查 BookSource
    // 完整记录填入, 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header)
    var showLoginDialog by remember { mutableStateOf<BookSource?>(null) }
    // 分组管理对话框状态 (false=隐藏, true=显示; onGroupClick 触发, 末尾 GroupManageDialog 渲染)
    var showGroupManage by remember { mutableStateOf(false) }
    // 应用日志对话框状态 (false=隐藏, true=显示; onShowLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    // 变量编辑对话框状态 (null=隐藏, 非空=显示; 持有当前 BookSource 供 Dialog 取源变量)
    // onSetSourceVariable/onSetBookVariable 触发后异步查 source 填入, 末尾 VariableDialog 渲染
    var showVariableDialog by remember { mutableStateOf(false) }
    var variableSource by remember { mutableStateOf<BookSource?>(null) }
    // 上传确认对话框状态 (false=隐藏, true=显示; onUploadBook 触发, 仅当 book 已有远程 URL 时弹出)
    // 对照 app 端 BookInfoActivity.uploadBook: alert(R.string.draw, R.string.sure_upload)
    var showUploadConfirmDialog by remember { mutableStateOf(false) }
    // 图片大图查看对话框状态 (null=隐藏, 非空=显示; onCoverClick/onShowPhoto 触发,
    // 末尾 PhotoViewDialog 渲染分支读取, 对照 app 端 BookInfoActivity 弹 PhotoDialog)
    var photoSrc by remember { mutableStateOf<String?>(null) }

    // ---- state 加载: groupName / tocText / bookSource / bookTick (对照 app 端 BookInfoActivity) ----
    // bookTick: book 原地可变对象 (canUpdate/config 等), 修改后递增驱动 state 重组
    // (对照 app 端 BookInfoActivity.bookTick)
    var bookTick by remember { mutableStateOf(0) }
    // tocReloadTick: onToggleSplitLongChapter 后递增, 触发 tocText LaunchedEffect 重新加载章节
    // (对照 app 端 toggleSplitLongChapter → upLoading(true) + loadBookInfo)
    var tocReloadTick by remember { mutableStateOf(0) }
    // groupName: 异步查分组名 (对照 app 端 upGroup → viewModel.loadGroup)
    var groupName by remember { mutableStateOf("") }
    val noGroupLabel = rememberString("no_group")
    LaunchedEffect(effectiveBook?.group) {
        val groupId = effectiveBook?.group ?: return@LaunchedEffect
        shared.loadGroup(groupId) { names ->
            groupName = names?.takeIf { it.isNotEmpty() } ?: noGroupLabel
        }
    }
    // tocText: 异步加载章节列表, null=加载中, 空=错误提示, 非空=durChapterTitle
    // (对照 app 端 upLoading: isLoading→null, empty→error_load_toc, else→durChapterTitle)
    var tocText by remember { mutableStateOf<String?>(null) }
    val errorLoadTocLabel = rememberString("error_load_toc")
    // 文案模板 (LaunchedEffect / launchUpload lambda 非 @Composable, 预先 remember 模板)
    val getTocFailedTemplate = rememberString("get_toc_failed_log")
    val uploadSuccessText = rememberString("upload_success")
    val uploadFailedText = rememberString("upload_failed")
    LaunchedEffect(effectiveBook?.bookUrl, inBookshelf, tocReloadTick) {
        val b = effectiveBook ?: return@LaunchedEffect
        val dao = AppDbProviders.get().bookChapterDao
        // 先读本地缓存, 命中则直接展示 (对照 app 端 BaseReadViewModel.upBook 的本地分支)
        var chapters: List<BookChapter> = dao.getChapterList(b.bookUrl)
        if (chapters.isEmpty() && !b.isLocal && b.tocUrl.isNotEmpty()) {
            // 本地无缓存且非本地书: 尝试网络加载 (对照 app 端 loadChapterList)
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
    // bookSource: 异步按 origin 查 BookSource 完整记录, 供 menuState (hasLogin/reviewRule) +
    // onDispatchIntroAction (evalJS) 使用 (对照 app 端 BaseReadViewModel.curBookSource)
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

    // 上传书籍协程启动 (供 onUploadBookCb 直接调用 + 确认对话框 OK 按钮调用)
    // 对照 app 端 BookInfoViewModel.uploadBook: execute { upWaitDialog(true); upload; save }
    //   .onSuccess { toast("上传成功") }.onError { toast(msg) }.onFinally { upWaitDialog(false) }
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
        onSearchClick, shared, scope, bookSource,
    ) {
        IosBookInfoActions(
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
                // 切换书架状态: 在书架则删除, 不在则插入 (与 desktop BookInfoScreen line 197-209 一致)
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
                // 对照 app 端 toggleCanUpdate: 切换 canUpdate + bookTick++ + 落库
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
                // 对照 app 端 toggleSplitLongChapter: 切换 config + bookTick++ + 重新加载章节
                val b = effectiveBook
                if (b != null) {
                    b.config.splitLongChapter = !b.config.splitLongChapter
                    bookTick++
                    tocReloadTick++ // 触发 tocText LaunchedEffect 重新加载 (对照 upLoading(true) + loadBookInfo)
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
                // 对照 app 端 BookInfoActivity.uploadBook:
                // 已有远程 URL → 弹确认对话框; 无远程 URL → 直接上传
                effectiveBook?.let { b ->
                    if (b.getRemoteUrl() != null) {
                        showUploadConfirmDialog = true
                    } else {
                        launchUpload()
                    }
                }
            },
            onCoverLongClickCb = { showChangeCoverDialog = true },
            // onCoverClick/onShowPhoto 触发图片大图查看对话框显示 (对照 app 端弹 PhotoDialog)
            onShowPhotoCb = { src -> photoSrc = src },
        )
    }

    // 调用 shared/sharedUiMain 的 BookInfoScreen, 注入 iOS 端 3 个 slot (与 desktop 一致模式,
    // 仅封面加载实现换成 iOS 端 IosBlurCoverBg/IosInfoCover/IosIntroImage)
    io.legado.app.ui.book.info.BookInfoScreen(
        state = state,
        actions = actions,
        blurCoverBgSlot = { modifier -> IosBlurCoverBg(effectiveBook, modifier) },
        coverSlot = { b, modifier -> IosInfoCover(b, modifier) },
        introImageSlot = { src, onClick -> IosIntroImage(src, onClick = onClick) },
    )

    // ---- 书源登录对话框 (onLogin 触发, 调用 shared/sharedUiMain 下沉的 SourceLoginDialog) ----
    // 登录逻辑 (putLoginInfo + login JS) 由 shared SourceLoginDialog 内部处理, 与 app/desktop 一致;
    // iOS 仅注入 onOpenUrl 走系统浏览器 (与 desktop browseUrl 等价)
    showLoginDialog?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { showLoginDialog = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }

    // ---- 分组管理对话框 (onGroupClick 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // 接入模式与 desktop BookInfoScreen line 257-280 一致: groups 来自 bookGroupDao.flowAll(),
    // 增删改走 GroupViewModelShared (shared commonMain) 转发到 bookGroupDao/bookDao
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

    // ---- 应用日志对话框 (onShowLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- 换封面对话框 (onCoverLongClick 触发, KMP 共享核心; 语义对照 ohos/desktop BookInfoScreen) ----
    if (showChangeCoverDialog) {
        ChangeCoverDialog(
            viewModel = changeCoverVm,
            onCoverSelected = { coverUrl ->
                effectiveBook?.let { b ->
                    b.customCoverUrl = coverUrl
                    bookTick++
                    if (inBookshelf) {
                        scope.launch {
                            AppDbProviders.get().bookDao.update(b)
                        }
                    }
                }
            },
            onDismiss = { showChangeCoverDialog = false },
            coverSlot = { searchBook, modifier ->
                IosInfoCover(searchBook.toBook(), modifier)
            },
        )
    }

    // ---- 变量编辑对话框 (onSetSourceVariable/onSetBookVariable 触发) ----
    // KMP 共享 VariableDialog: 两个 Tab (源变量/书籍变量) 一次编辑
    // sourceVariables 从 variableSource.getVariable() 解析 (decodeStringMapOrNull 容错)
    // bookVariables 直接用 effectiveBook.variableMap (HashMap, lazy 解析自 variable 字段)
    // onConfirm 写回: source.setVariable(encodeStringMap) + book.variable = encodeStringMap + dao.update
    // (与 desktop BookInfoScreen line 292-318 实现完全一致)
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
    // 消费 sharedUiMain PhotoViewDialog (ImageBitmapLoader iOS actual=Coil3 + 共享 zoomable 手势;
    // 传 book/bookSource 让网络图带书源防盗链 header, 对照 app 端 PhotoDialog sourceOrigin)
    photoSrc?.let { src ->
        PhotoViewDialog(
            src = src,
            onDismiss = { photoSrc = null },
            book = effectiveBook,
            bookSource = bookSource,
        )
    }

    // ---- 上传书籍确认对话框 (onUploadBook 触发, 仅当 book 已有远程 URL 时显示) ----
    // 对照 app 端 BookInfoActivity.uploadBook: alert(R.string.draw, R.string.sure_upload) { okButton { ... }; cancelButton() }
    if (showUploadConfirmDialog) {
        AppAlertDialog(
            onDismissRequest = { showUploadConfirmDialog = false },
            title = rememberString("draw"),
            message = rememberString("sure_upload"),
            okButton = AlertButton(text = rememberString("ok")) { launchUpload() },
            cancelButton = AlertButton(text = rememberString("cancel")),
        )
    }
}

/**
 * iOS 端 [BookInfoUiActions] 实现 (对照 desktop `DesktopBookInfoActions`)。
 *
 * 30 个回调中:
 * - 真实实现: [onBack] / [onReadClick] / [onShelfClick] / [onOriginClick] / [onTocClick] / [onTopBook]
 *   (复用 [BookInfoViewModelShared]) / [onRefresh] (复用 shared.refreshBookSourceName) /
 *   [onLogin] / [onGroupClick] / [onSetSourceVariable] / [onSetBookVariable] / [onShowLog]
 *   (弹 shared/sharedUiMain 下沉的 Dialog) / [onEdit] (路由回调) /
 *   [onSearchAuthor] / [onSearchKind] / [onNameClick] (路由回调) /
 *   [onToggleCanUpdate] / [onToggleSplitLongChapter] (原地修改 + bookTick++) /
 *   [onDispatchIntroAction] (evalJS via [bookSource]) /
 *   [onCoverClick] / [onShowPhoto] (弹 sharedUiMain PhotoViewDialog 查看大图)
 * - no-op + TODO: 其余依赖未下沉 Dialog 或 iOS 平台 actual 的动作
 *
 * @param book 当前展示的 Book (可能为 null, onShelfClick 等动作内做 null 安全)
 * @param inBookshelf 当前书架状态 (onShelfClick 用)
 * @param onBack 由 IosNavHost 注入的路由回调
 * @param onReadClick 由 IosNavHost 注入的路由回调 (携带 Book 切到 READER)
 * @param onEditClick 由 IosNavHost 注入的路由回调 (携带 bookUrl 切到 BOOK_INFO_EDIT)
 * @param onSearchClick 由 IosNavHost 注入的搜索路由回调 (key, submit)
 * @param bookSource 当前书源 (异步加载, 供 onDispatchIntroAction evalJS 用; null 时 no-op)
 * @param onOriginClick 由 IosNavHost 注入的路由回调 (iOS 端暂未接入 CHANGE_SOURCE, 默认 no-op)
 * @param onTocClick 由 IosNavHost 注入的路由回调 (iOS 端暂未接入 TOC, 默认 no-op)
 * @param shared 复用 commonMain 的 [BookInfoViewModelShared], 供 onTopBook/onRefresh 调用
 * @param scope 协程作用域, onShelfClick/onLogin 等异步操作用
 * @param onShelfClick 由本文件注入的书架切换实现 (调 bookDao.insert/delete + 刷新 inBookshelf)
 * @param onToggleCanUpdateCb 由本文件注入, 切换 canUpdate + bookTick++ + 落库
 * @param onToggleSplitLongChapterCb 由本文件注入, 切换 config + bookTick++ + 重新加载章节
 * @param onLoginCb 由本文件注入, 弹 SourceLoginDialog (异步查 BookSource 后回传)
 * @param onGroupClickCb 由本文件注入, 弹 GroupManageDialog
 * @param onShowLogCb 由本文件注入, 弹 AppLogDialog
 * @param onVariableCb 由本文件注入, 弹 VariableDialog (异步查 BookSource 后回传)
 * @param onUploadBookCb 由本文件注入, 上传书籍到 WebDav (检查远程 URL 决定弹确认/直接上传)
 * @param onShowPhotoCb 由本文件注入, 弹 PhotoViewDialog 查看大图 (onCoverClick/onShowPhoto 触发)
 */
private class IosBookInfoActions(
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
    private val onCoverLongClickCb: () -> Unit,
    private val onShowPhotoCb: (String) -> Unit,
) : BookInfoUiActions {

    override fun onBack() = onBack.invoke()

    override fun onEdit() {
        // 切到 BOOK_INFO_EDIT 路由 (携带 bookUrl), 由 IosNavHost 注入的 onEditClick 回调触发
        // (对照 desktop BookInfoScreen.onEdit → onEditClick(bookUrl); iOS 端 IosNavHost 后续接入编辑子路由)
        book?.bookUrl?.let { onEditClick(it) }
    }

    override fun onShare() {
        // iOS 端无 Intent.ACTION_SHARE, 复制书籍 URL 到剪贴板替代分享
        book?.bookUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onRefresh() {
        // 刷新书源名 (对照 BookInfoViewModelShared.refreshBookSourceName, 与 desktop 一致)
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            shared.refreshBookSourceName(b, source)
        }
    }

    override fun onUploadBook() {
        // 上传书籍到 WebDav (对照 app 端 BookInfoActivity.uploadBook)
        // 外层 Composable 检查 getRemoteUrl 决定弹确认对话框或直接上传
        onUploadBookCb.invoke()
    }

    override fun onDownloadToLocal() {
        // 下载远程书籍到本地 (对照 app 端 BookInfoViewModel.downloadToLocal)
        // execute { FileBook.downloadRemoteBook(book) }.onSuccess { toast("下载成功"); upBook }
        //   .onError { AppLog.put("下载远程书籍失败", e, true) }
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
        // 复用 shared commonMain 的 BookInfoViewModelShared.topBook (无 Android 依赖, 已下沉)
        // effectiveBook 已由 IosBookInfoScreen 通过 LaunchedEffect 同步到 shared.upBook
        shared.topBook()
    }

    override fun onLogin() {
        // 异步按 book.origin 查 BookSource 完整记录, 回传给外层 Composable 控制 Dialog 显示
        // (BookSourcePart 是 DatabaseView 不含 header/loginUrl, 需取完整 BookSource 供 SourceLoginDialog)
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onLoginCb.invoke(source)
            }
        }
    }

    override fun onOpenCommentDialog() {
        // TODO: 依赖书源评论规则 + 评论列表 Dialog, iOS 端未下沉
    }

    override fun onSetSourceVariable() {
        // 异步按 book.origin 查 BookSource, 回传给外层 Composable 控制 VariableDialog 显示
        // (KMP VariableDialog 一次编辑源变量+书籍变量两个 Tab, 故与 onSetBookVariable 行为一致)
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onVariableCb.invoke(source)
            }
        }
    }

    override fun onSetBookVariable() {
        // KMP VariableDialog 一次编辑源变量+书籍变量两个 Tab, 与 onSetSourceVariable 行为一致
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source != null) {
                onVariableCb.invoke(source)
            }
        }
    }

    override fun onCopyBookUrl() {
        // 复制书籍 URL 到剪贴板 (对照 desktop Toolkit.systemClipboard.setContents)
        book?.bookUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onCopyTocUrl() {
        // 复制目录 URL 到剪贴板 (对照 desktop Toolkit.systemClipboard.setContents)
        book?.tocUrl?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(it) }
    }

    override fun onToggleCanUpdate() {
        // 切换 canUpdate + bookTick++ + 落库 (对照 app 端 toggleCanUpdate)
        // 实际逻辑由外层 Composable 注入 (访问 effectiveBook/inBookshelf/scope/bookTick)
        onToggleCanUpdateCb.invoke()
    }

    override fun onToggleSplitLongChapter() {
        // 切换 config.splitLongChapter + bookTick++ + 重新加载章节 (对照 app 端 toggleSplitLongChapter)
        // 实际逻辑由外层 Composable 注入 (访问 effectiveBook/bookTick/tocReloadTick)
        onToggleSplitLongChapterCb.invoke()
    }

    override fun onClearCache() {
        // TODO: 清除书籍缓存 (BookHelp.clearCache), iOS 端 BookHelpAccessor 未提供 clearCache
    }

    override fun onShowLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowLogCb.invoke()
    }

    override fun onNameClick() {
        // 用书名作关键词切到搜索路由 (对照 app 端 onNameClick → SearchActivity putExtra("key", name))
        val name = book?.name ?: return
        onSearchClick(name, true)
    }

    override fun onCoverClick() {
        // 查看封面大图 (对照 app 端 BookInfoActivity.onCoverClick → PhotoDialog(getDisplayCover))
        book?.getDisplayCover()?.let { onShowPhotoCb.invoke(it) }
    }

    override fun onCoverLongClick() {
        // 弹 ChangeCoverDialog (书源搜索换封面, IosChangeCoverPlatform 注入)
        onCoverLongClickCb.invoke()
    }

    override fun onOriginClick() {
        // 切到 CHANGE_SOURCE 路由 (换源页), 由 IosNavHost 注入的 onOriginClick 回调触发
        // (iOS 端暂未接入 CHANGE_SOURCE 路由, IosNavHost 默认 no-op; 后续 KP5+ 接入)
        book?.let { onOriginClick(it) }
    }

    override fun onOriginLongClick() {
        // TODO: 长按书源菜单 (复制/调试/编辑), iOS 端未下沉
    }

    override fun onTocClick() {
        // 切到 TOC 路由 (目录页), 由 IosNavHost 注入的 onTocClick 回调触发
        // (iOS 端暂未接入 TOC 路由, IosNavHost 默认 no-op; 后续 KP5+ 接入)
        book?.let { onTocClick(it) }
    }

    override fun onGroupClick() {
        // 触发分组管理 Dialog 显示 (外层 Composable 控制 showGroupManage 状态)
        onGroupClickCb.invoke()
    }

    override fun onShelfClick() {
        // 切换书架状态 (在书架 → 移出, 不在 → 加入)
        onShelfClick.invoke()
    }

    override fun onReadClick() {
        // 跳转阅读路由 (携带 Book)
        book?.let { onReadClick.invoke(it) }
    }

    override fun onSearchAuthor(author: String, submit: Boolean) {
        // 用 author 作关键词切到搜索路由 (对照 app 端 search → SearchActivity putExtra("key", author))
        // submit=true 自动搜索, false 仅填充 (长按探索)
        onSearchClick(author, submit)
    }

    override fun onSearchKind(kind: String, submit: Boolean) {
        // 用 kind 作关键词切到搜索路由 (分类标签点击, 对照 app 端 search → SearchActivity)
        onSearchClick(kind, submit)
    }

    override fun onDispatchIntroAction(action: String) {
        // 简介内 <button onclick="..."> JS 动作派发 (对照 app 端 onDispatchIntroAction)
        // 用 bookSource.evalJS 执行 JS, 注入 book 变量; 无书源则 no-op
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
