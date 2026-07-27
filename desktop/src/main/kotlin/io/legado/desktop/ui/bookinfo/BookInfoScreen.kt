package io.legado.desktop.ui.bookinfo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeType
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changecover.ChangeCoverViewModelShared
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.info.BookInfoMenuState
import io.legado.app.ui.book.info.BookInfoScreen as SharedBookInfoScreen
import io.legado.app.ui.book.info.BookInfoUiActions
import io.legado.app.ui.book.info.BookInfoUiState
import io.legado.app.ui.book.info.BookInfoViewModelShared
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.browseUrl
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.desktop.ui.component.DesktopBookCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import java.io.File
import okhttp3.Request
import io.legado.app.help.http.OkHttpClientProviders

/**
 * 桌面端书籍详情 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookInfoScreen])。
 *
 * # 职责
 *
 * 对照 desktop [io.legado.desktop.ui.bookshelf.BookshelfScreen] 模式, 仅做桌面平台适配,
 * 业务展示与交互逻辑全部下沉到 shared/sharedUiMain 的 [SharedBookInfoScreen]:
 *
 * - **书籍转换**: [book] 入参用 [BaseBook] 统一持有 SearchBook/Book, 内部转 Book 供 UI 与
 *   DAO 使用 (SearchBook.toBook() / Book 直接用)
 * - **书架状态**: LaunchedEffect 异步查 [AppDbProviders.get].bookDao.has(bookUrl),
 *   命中则 loadedBook=dao.getBook(bookUrl) 复用本地完整数据
 * - **UI state**: 构造 [BookInfoUiState], 桌面端固定竖屏布局 (isLandscape=false),
 *   关闭 dev 布局 (useDevFeat=false) / 关闭暗色判定 (isDarkTheme=false 由宿主主题决定)
 * - **actions**: 实现 [BookInfoUiActions] 30 个方法, 核心动作 (onBack/onReadClick/onShelfClick/
 *   onCopyBookUrl/onCopyTocUrl) 接入真实路由, 其余暂为 no-op + TODO 注释 (依赖未下沉 Dialog)
 * - **slots**: 桌面端封面/插图加载, 不引入 Glide/Coil, 用 [DesktopBookCover]
 *   (JDK ImageIO + 项目已注册 OkHttp, 详见 [io.legado.desktop.ui.component.DesktopBookCover])
 *   - blurCoverBgSlot: 封面模糊背景 (加载失败回退纯色占位)
 *   - coverSlot: 封面图 (加载失败回退书名首字占位)
 *   - introImageSlot: 简介内插图 (加载失败回退文本占位)
 *
 * # 简化项
 *
 * - 不接入下拉刷新图片封面 (桌面端无下拉手势)
 * - 不实现 onEdit/onShare/onLogin 等需 Android 专属 Dialog 的动作 (留 TODO)
 * - onRefresh 仅刷新书源名 (调 shared.refreshBookSourceName), 不重新拉网络详情
 *
 * @param book 详情页书籍 (SearchBook/Book), 由 DesktopApp 注入
 * @param onBack 返回回调 (切回调用方路由: 搜索/书架)
 * @param onReadClick "开始阅读" 回调 (切到 READER 路由, 携带 Book)
 * @param onEditClick "编辑信息" 回调 (切到 BOOK_INFO_EDIT 路由, 携带 bookUrl)
 * @param onOriginClick "切换来源" 回调 (切到 CHANGE_SOURCE 路由, 携带 Book)
 * @param onTocClick "目录" 回调 (切到 TOC 路由, 携带 Book)
 * @param onSearch 作者/分类/书名搜索回调 (切到 SEARCH 路由, 携带关键词 + submit)
 *   对照 app 端 BookInfoActivity.search 启动 SearchActivity (putExtra key/submit)
 * @param onExplore 发现结果回调 (author/kind 含 "::" 时切到 EXPLORE_SHOW 路由,
 *   对照 app 端 search 内 tmp.size > 1 分支启动 ExploreShowActivity)
 */
@Composable
fun BookInfoScreen(
    book: BaseBook,
    onBack: () -> Unit,
    onReadClick: (Book) -> Unit,
    onEditClick: (String) -> Unit,
    onOriginClick: (Book) -> Unit = {},
    onTocClick: (Book) -> Unit = {},
    onSearch: (String, Boolean) -> Unit = { _, _ -> },
    onExplore: (BookSource, String, String) -> Unit = { _, _, _ -> },
) {
    // SearchBook → Book 转换 (Book 直接用, 其他类型暂无, 兜底 null)
    val displayBook: Book? = remember(book) {
        when (book) {
            is Book -> book
            is SearchBook -> book.toBook()
            else -> null
        }
    }

    // 异步查书架状态 + 加载本地完整 Book (若已在书架)
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
    // 业务方法真正工作 (替代原 TODO no-op)。其余 actions 仍保留 TODO, 因依赖未下沉的
    // Dialog/Activity 路由 (onEdit/onShare/onLogin/onSetSourceVariable/...)。
    //
    // effectiveBook 同步到 shared, 让 shared.topBook() 等方法能读到当前 book;
    // 桌面端不订阅 shared.bookData/waitDialogData/actionLive (无对应 UI 入口),
    // 这些 StateFlow 主要供 app 端 BookInfoActivity.observe 桥接使用。
    val shared = remember(scope) { BookInfoViewModelShared(scope = scope) }
    LaunchedEffect(effectiveBook) {
        shared.upBook(effectiveBook)
    }

    // 换封面 ViewModel (KMP 共享核心, commonMain ChangeCoverViewModelShared)
    // 桌面端通过 ChangeCoverPlatformDesktop 注入 threadCount + cleanAuthor
    val changeCoverVm = remember(scope) {
        ChangeCoverViewModelShared(scope, ChangeCoverPlatformDesktop())
    }
    // effectiveBook 变化时初始化换封面 VM (与 BookInfoEditScreen 模式一致)
    LaunchedEffect(effectiveBook) {
        val b = effectiveBook ?: return@LaunchedEffect
        changeCoverVm.initData(b.name, b.author)
    }

    // 封面重载 key (onCoverLongClick 换封面后递增, 驱动 coverSlot 重载;
    // 对照 BookInfoEditScreen.coverTickState 与 app 端 BookInfoActivity.coverTick)
    val coverTickState = remember { mutableStateOf(0) }
    // book 原地可变重载 key (onToggleCanUpdate/onToggleSplitLongChapter 修改 book 字段后递增,
    // 驱动 state 重建 + menu 显隐刷新; 对照 app 端 BookInfoActivity.bookTick)
    val bookTickState = remember { mutableStateOf(0) }
    // 换封面对话框状态 (false=隐藏, true=显示; onCoverLongClick 触发,
    // 末尾 ChangeCoverDialog 渲染分支读取)
    var showChangeCoverDialog by remember { mutableStateOf(false) }

    // 书源登录对话框状态 (null=隐藏, 非空=显示; onLogin 触发后异步按 origin 查 BookSource
    // 完整记录填入, 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header)
    var showLoginDialog by remember { mutableStateOf<BookSource?>(null) }
    // 分组管理对话框状态 (false=隐藏, true=显示; onGroupClick 触发, 末尾 GroupManageDialog 渲染)
    // 参考 desktop BookshelfScreen.kt 的 GroupManageDialog 接入模式:
    // - groupVm: GroupViewModelShared (shared commonMain), 注入 scope, 转发 addGroup/upGroup/delGroup
    // - groups: 订阅 bookGroupDao.flowAll() (含 show=false 分组, 管理用全集)
    var showGroupManage by remember { mutableStateOf(false) }
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    // 应用日志对话框状态 (false=隐藏, true=显示; onShowLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    // 变量编辑对话框状态 (null=隐藏, 非空=显示; 持有当前 BookSource 供 Dialog 取源变量)
    // onSetSourceVariable/onSetBookVariable 触发后异步查 source 填入, 末尾 VariableDialog 渲染
    var showVariableDialog by remember { mutableStateOf(false) }
    var variableSource by remember { mutableStateOf<BookSource?>(null) }
    // 图片大图查看对话框状态 (null=隐藏, 非空=显示; onCoverClick/onShowPhoto 触发,
    // 末尾 AlertDialog 渲染分支读取, 对照 app 端 BookInfoActivity 弹 PhotoDialog)
    var photoSrc by remember { mutableStateOf<String?>(null) }
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    val state = remember(effectiveBook, inBookshelf, coverTickState.value, bookTickState.value) {
        BookInfoUiState(
            book = effectiveBook,
            // bookTick 绑定 bookTickState.value: onToggleCanUpdate/onToggleSplitLongChapter
            // 修改 book 字段后递增, 驱动 state 重建 + menu 显隐刷新 (对照 app 端 BookInfoActivity.bookTick)
            bookTick = bookTickState.value,
            // coverTick 绑定 coverTickState.value: onCoverLongClick 换封面后递增,
            // 触发 state 重建 + coverSlot 重载 (对照 app 端 BookInfoActivity.coverTick)
            coverTick = coverTickState.value,
            inBookshelf = inBookshelf,
            groupName = "",
            tocText = null,
            lastedTitle = effectiveBook?.latestChapterTitle ?: "",
            wordCountText = effectiveBook?.wordCount,
            isLandscape = false,
            useDevFeat = false,
            isDarkTheme = false,
            menuState = BookInfoMenuState(
                isLocal = false,
                isWebDav = false,
                hasSource = false,
                sourceHasLogin = false,
                sourceHasReviewRule = false,
                // canUpdate / splitLongChapter 从 book 字段读取, onToggle 后菜单状态正确反映
                // (对照 app 端 onMenuPaused 内联求值 book?.canUpdate / book?.config.splitLongChapter)
                canUpdate = effectiveBook?.canUpdate ?: true,
                isLocalTxt = false,
                splitLongChapter = effectiveBook?.config?.splitLongChapter ?: false,
                bookUrl = effectiveBook?.bookUrl,
                tocUrl = effectiveBook?.tocUrl,
            ),
        )
    }

    val actions = remember(effectiveBook, inBookshelf, onBack, onReadClick, onEditClick, onOriginClick, onTocClick, onSearch, onExplore, shared, scope) {
        DesktopBookInfoActions(
            book = effectiveBook,
            inBookshelf = inBookshelf,
            onBack = onBack,
            onReadClick = onReadClick,
            onEditClick = onEditClick,
            shared = shared,
            scope = scope,
            bookTickState = bookTickState,
            onOriginClick = onOriginClick,
            onTocClick = onTocClick,
            onSearch = onSearch,
            onExplore = onExplore,
            onShelfClick = {
                // 切换书架状态: 在书架则删除, 不在则插入
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
            onLoginCb = { src -> showLoginDialog = src },
            onGroupClickCb = { showGroupManage = true },
            onShowLogCb = { showLogDialog = true },
            onVariableCb = { src ->
                variableSource = src
                showVariableDialog = true
            },
            // onCoverLongClick 触发换封面对话框显示 (对照 app 端 BookInfoActivity.onCoverLongClick
            // → showDialogFragment(ChangeCoverDialog); 末尾 ChangeCoverDialog 渲染分支读取)
            onCoverLongClickCb = { showChangeCoverDialog = true },
            // onCoverClick/onShowPhoto 触发图片大图查看对话框显示 (对照 app 端弹 PhotoDialog)
            onShowPhotoCb = { src -> photoSrc = src },
        )
    }

    SharedBookInfoScreen(
        state = state,
        actions = actions,
        blurCoverBgSlot = { modifier -> DesktopBookCover.BlurCoverBg(effectiveBook, modifier) },
        coverSlot = { b, modifier ->
            // key(coverTick) 包裹: coverTick 变化时强制重载封面
            // (onCoverLongClick 换封面后递增 coverTickState, 驱动 DesktopBookCover 重新加载;
            // 对照 BookInfoEditScreen coverSlot 的 key(coverTickState.value) 模式)
            key(coverTickState.value) {
                DesktopBookCover.InfoCover(b, modifier)
            }
        },
        introImageSlot = { src, onClick -> DesktopBookCover.IntroImage(src, onClick = onClick) },
    )

    // ---- 书源登录对话框 (onLogin 触发, 调用 shared/sharedUiMain 下沉的 SourceLoginDialog) ----
    // 登录逻辑由 shared SourceLoginDialog 内部处理 (putLoginInfo + login JS),
    // 与 app 端一致; 桌面端仅需注入 onOpenUrl 回调
    showLoginDialog?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { showLoginDialog = null },
            onOpenUrl = { url -> browseUrl(url) },
        )
    }

    // ---- 分组管理对话框 (onGroupClick 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // 接入模式参考 desktop BookshelfScreen.kt: groups 来自 bookGroupDao.flowAll(),
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

    // ---- 变量编辑对话框 (onSetSourceVariable/onSetBookVariable 触发) ----
    // KMP 共享 VariableDialog: 两个 Tab (源变量/书籍变量) 一次编辑
    // sourceVariables 从 variableSource.getVariable() 解析 (decodeStringMapOrNull 容错)
    // bookVariables 直接用 effectiveBook.variableMap (HashMap, lazy 解析自 variable 字段)
    // onConfirm 写回: source.setVariable(encodeStringMap) + book.variable = encodeStringMap + dao.update
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

    // ---- 换封面对话框 (onCoverLongClick 触发, 调用 shared/sharedUiMain 下沉的 ChangeCoverDialog) ----
    // 对照 app 端 BookInfoActivity.onCoverLongClick → showDialogFragment(ChangeCoverDialog);
    // onCoverSelected 写入 book.customCoverUrl + 递增 coverTickState 驱动封面重载,
    // 若在书架则 dao.update 落库 (对照 app 端 BookInfoActivity.coverChangeTo:
    //   book.customCoverUrl = coverUrl; coverTick++; if (inBookshelf) saveBook(book))
    if (showChangeCoverDialog) {
        ChangeCoverDialog(
            viewModel = changeCoverVm,
            onCoverSelected = { coverUrl ->
                effectiveBook?.let { b ->
                    // 写入 customCoverUrl (对照 app 端 BookInfoActivity.coverChangeTo,
                    // app 端直接写入不区分 "use_default_cover"; desktop 端同样直接写入,
                    // "use_default_cover" 标记会导致 DesktopBookCover 走占位, 视觉上为默认封面)
                    b.customCoverUrl = coverUrl
                    coverTickState.value++
                    // 在书架则落库 (对照 app 端 if (inBookshelf) saveBook(book))
                    if (inBookshelf) {
                        scope.launch {
                            AppDbProviders.get().bookDao.update(b)
                        }
                    }
                }
            },
            onDismiss = { showChangeCoverDialog = false },
            coverSlot = { searchBook, modifier ->
                // 桌面端封面渲染: SearchBook 转 Book 后用 DesktopBookCover.InfoCover
                // (JDK ImageIO + OkHttp, 不引入 Glide; 与 BookInfoEditScreen 一致)
                DesktopBookCover.InfoCover(searchBook.toBook(), modifier)
            },
        )
    }

    // ---- 图片大图查看对话框 (onCoverClick/onShowPhoto 触发, 对照 app 端 PhotoDialog) ----
    // desktop 无 PhotoDialog, 用 AlertDialog + Image 简易实现: produceState 异步加载图片为
    // ImageBitmap (本地路径用 ImageIO, 网络路径用 OkHttp 下载后 ImageIO 解码, 与 DesktopBookCover 一致)
    photoSrc?.let { src ->
        DesktopPhotoDialog(src = src, onDismiss = { photoSrc = null })
    }
}

/**
 * 桌面端 [BookInfoUiActions] 实现。
 *
 * 30 个回调中:
 * - 真实实现: [onBack] / [onReadClick] / [onShelfClick] / [onCopyBookUrl] / [onCopyTocUrl]
 *   / [onTopBook] (复用 [BookInfoViewModelShared]) / [onToggleCanUpdate] / [onToggleSplitLongChapter]
 *   / [onSearchAuthor] / [onSearchKind] / [onNameClick] / [onDispatchIntroAction] / [onShare]
 *   / [onCoverClick] / [onShowPhoto] / [onOriginLongClick]
 * - 保留 TODO: [onUploadBook] / [onDownloadToLocal] (依赖未下沉的 RemoteBookWebDav /
 *   DesktopFileBookAccessor.downloadRemoteBook) / [onOpenCommentDialog] (依赖 ReviewListDialog)
 *
 * @param book 当前展示的 Book (可能为 null, onShelfClick 等动作内做 null 安全)
 * @param inBookshelf 当前书架状态 (onShelfClick/onToggleCanUpdate 用)
 * @param onBack 由 DesktopApp 注入的路由回调
 * @param onReadClick 由 DesktopApp 注入的路由回调 (携带 Book 切到 READER)
 * @param onShelfClick 由本文件注入的书架切换实现 (调 bookDao.insert/delete + 刷新 inBookshelf)
 * @param shared 复用 commonMain 的 [BookInfoViewModelShared], 供 onTopBook 调用
 *   (effectiveBook 已由 BookInfoScreen 通过 LaunchedEffect 同步到 shared.upBook)
 * @param bookTickState book 原地可变重载 key (onToggleCanUpdate/onToggleSplitLongChapter 递增)
 * @param onSearch 作者/分类/书名搜索回调 (切到 SEARCH 路由)
 * @param onExplore 发现结果回调 (author/kind 含 "::" 时切到 EXPLORE_SHOW 路由)
 * @param onShowPhotoCb 图片大图查看回调 (onCoverClick/onShowPhoto 触发, 外层弹 PhotoDialog)
 */
private class DesktopBookInfoActions(
    private val book: Book?,
    private val inBookshelf: Boolean,
    private val onBack: () -> Unit,
    private val onReadClick: (Book) -> Unit,
    private val onEditClick: (String) -> Unit,
    private val shared: BookInfoViewModelShared,
    private val scope: CoroutineScope,
    private val bookTickState: androidx.compose.runtime.MutableState<Int>,
    private val onOriginClick: (Book) -> Unit,
    private val onTocClick: (Book) -> Unit,
    private val onSearch: (String, Boolean) -> Unit,
    private val onExplore: (BookSource, String, String) -> Unit,
    private val onShelfClick: () -> Unit,
    private val onLoginCb: (BookSource) -> Unit,
    private val onGroupClickCb: () -> Unit,
    private val onShowLogCb: () -> Unit,
    private val onVariableCb: (BookSource) -> Unit,
    // onCoverLongClick 触发回调: 由 BookInfoScreen 注入, 弹 ChangeCoverDialog
    // (对照 app 端 BookInfoActivity.onCoverLongClick → showDialogFragment(ChangeCoverDialog))
    private val onCoverLongClickCb: () -> Unit,
    // onCoverClick/onShowPhoto 触发回调: 由 BookInfoScreen 注入, 弹图片大图查看 Dialog
    // (对照 app 端 BookInfoActivity.onCoverClick/onShowPhoto → showDialogFragment(PhotoDialog))
    private val onShowPhotoCb: (String) -> Unit,
) : BookInfoUiActions {

    override fun onBack() = onBack.invoke()

    override fun onEdit() {
        book?.bookUrl?.let { onEditClick(it) }
    }

    override fun onShare() {
        // 桌面端无 Intent.ACTION_SHARE, 复制书籍 JSON 到剪贴板 (对照 app 端 shareBook 的 JSON 结构)
        val b = book ?: return
        val json = "[${GSON.toJson(
            mapOf(
                "bookUrl" to b.bookUrl,
                "tocUrl" to b.tocUrl,
                "origin" to b.origin,
                "originName" to b.originName,
                "name" to b.name,
                "author" to b.author,
                "kind" to b.kind,
                "coverUrl" to b.coverUrl,
                "customCoverUrl" to b.customCoverUrl,
                "intro" to b.intro,
                "customIntro" to b.customIntro,
                "type" to b.type,
                "wordCount" to b.wordCount
            )
        )}]"
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(json), null)
        Toasters.get().toast(jvmGetString("book_info_copied"))
    }

    override fun onRefresh() {
        // 刷新书源名 (对照 BookInfoViewModelShared.refreshBookSourceName)
        // 从 DAO 取 origin 对应 BookSource, 调 shared.refreshBookSourceName 更新 originName 字段
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            shared.refreshBookSourceName(b, source)
        }
    }

    override fun onUploadBook() {
        // TODO: 依赖 AppWebDav.defaultBookWebDav (RemoteBookWebDav) 上传整本书到远程,
        //   该类依赖 Android 专属组件 (withNetworkCheck/Uri), 未下沉 shared;
        //   AppWebDavShared 仅有 uploadBookProgress (进度同步), 无 upload(book) 整本上传
        Toasters.get().toast(jvmGetString("desktop_upload_not_supported"))
    }

    override fun onDownloadToLocal() {
        // TODO: 依赖 DesktopFileBookAccessor.downloadRemoteBook (当前抛 UnsupportedOperationException)
        //   或 app 端 FileBook.downloadRemoteBook (依赖 Android Uri), 桌面端未实现
        Toasters.get().toast(jvmGetString("desktop_download_local_not_supported"))
    }

    override fun onTopBook() {
        // 复用 shared commonMain 的 BookInfoViewModelShared.topBook (无 Android 依赖, 已下沉)
        // effectiveBook 已由 BookInfoScreen 通过 LaunchedEffect 同步到 shared.upBook
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
        // TODO: 依赖书源评论规则 + 评论列表 Dialog, 桌面端未下沉
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
        // 复制书籍 URL 到系统剪贴板
        book?.bookUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(url), null)
        }
    }

    override fun onCopyTocUrl() {
        // 复制目录 URL 到系统剪贴板
        book?.tocUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(url), null)
        }
    }

    override fun onToggleCanUpdate() {
        // 切换 canUpdate + 递增 bookTick 驱动菜单状态刷新 + 在书架则落库
        // (对照 app 端 BookInfoActivity.toggleCanUpdate: it.canUpdate = !it.canUpdate;
        //   bookTick++; if (inBookshelf) { if (!it.canUpdate) it.removeType(BookType.updateError); saveBook(it) })
        val b = book ?: return
        b.canUpdate = !b.canUpdate
        bookTickState.value++
        scope.launch {
            if (inBookshelf) {
                if (!b.canUpdate) b.removeType(BookType.updateError)
                AppDbProviders.get().bookDao.update(b)
            }
        }
    }

    override fun onToggleSplitLongChapter() {
        // 切换 splitLongChapter + 递增 bookTick + 落库 + 关闭时提示
        // (对照 app 端 BookInfoActivity.toggleSplitLongChapter: newValue = !config.splitLongChapter;
        //   config.splitLongChapter = newValue; bookTick++; loadBookInfo(it);
        //   if (!newValue) longToastOnUi(R.string.need_more_time_load_content))
        // 注: app 端 loadBookInfo 依赖 BaseReadViewModel (未下沉), desktop 端简化为仅落库
        val b = book ?: return
        val newValue = !b.config.splitLongChapter
        b.config.splitLongChapter = newValue
        bookTickState.value++
        scope.launch {
            AppDbProviders.get().bookDao.update(b)
        }
        if (!newValue) {
            Toasters.get().toast(jvmGetString("need_more_time_load_content"))
        }
    }

    override fun onClearCache() {
        // 清除该书缓存: 调 BookStorageProviders.get().clearCache(book) (JvmBookStorage 实现,
        // 删除 ~/.legado/book_cache/{bookFolderName}/ 目录), 对照 app 端 BookHelp.clearCache(book)
        // 与 BookshelfManageScreen 的"清缓存"批量项一致, 复用 shared BookStorageProviders, 不复制逻辑
        val b = book ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    io.legado.app.help.book.BookStorageProviders.get().clearCache(b)
                }.onFailure {
                    AppLog.put(jvmGetString("clear_cache_failed", b.name, it.localizedMessage), it)
                }
            }
            io.legado.app.help.toast.Toasters.get().toast(jvmGetString("clear_cache_success"))
        }
    }

    override fun onShowLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowLogCb.invoke()
    }

    override fun onNameClick() {
        // 用书名作关键词搜索 (对照 app 端 BookInfoActivity.onNameClick → SearchActivity putExtra key)
        val b = book ?: return
        search(b.name, true)
    }

    override fun onCoverClick() {
        // 查看封面大图 (对照 app 端 BookInfoActivity.onCoverClick → PhotoDialog(getDisplayCover))
        book?.getDisplayCover()?.let { onShowPhotoCb.invoke(it) }
    }

    override fun onCoverLongClick() {
        // 弹 ChangeCoverDialog (书源搜索换封面, KMP 共享核心)
        // 对照 app 端 BookInfoActivity.onCoverLongClick → showDialogFragment(ChangeCoverDialog);
        // BookInfoScreen 末尾 ChangeCoverDialog 渲染分支读取 showChangeCoverDialog,
        // onCoverSelected 写入 book.customCoverUrl + 递增 coverTick + 落库
        onCoverLongClickCb.invoke()
    }

    override fun onOriginClick() {
        // 切到 CHANGE_SOURCE 路由 (换源页), 由 BookInfoScreen 注入的 onOriginClick 回调触发
        book?.let { onOriginClick(it) }
    }

    override fun onOriginLongClick() {
        // 长按书源: app 端弹 ChangeBookSourceDialog (换源), desktop 端 ChangeBookSourceDialog
        // 未下沉, 复用 onOriginClick 切到 CHANGE_SOURCE 路由 (与短按行为一致, 换源页即可选源)
        book?.let { onOriginClick(it) }
    }

    override fun onTocClick() {
        // 切到 TOC 路由 (目录页), 由 BookInfoScreen 注入的 onTocClick 回调触发
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
        // 作者搜索 (对照 app 端 BookInfoActivity.search(author, submit))
        search(author, submit)
    }

    override fun onSearchKind(kind: String, submit: Boolean) {
        // 分类标签搜索 (对照 app 端 BookInfoActivity.search(kind, submit))
        search(kind, submit)
    }

    override fun onDispatchIntroAction(action: String) {
        // 简介内 <button onclick="..."> 动作派发 (对照 app 端 BookInfoActivity.onDispatchIntroAction)
        // 异步查 source 后调 source.evalJS(js) { this["book"] = book }
        val js = action.trim().ifEmpty { return }
        val b = book ?: return
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
            if (source == null) {
                Toasters.get().toast(jvmGetString("no_book_source"))
                return@launch
            }
            try {
                source.evalJS(js) {
                    this["book"] = b
                }
            } catch (e: Exception) {
                AppLog.put(jvmGetString("intro_action_dispatch_failed", e.localizedMessage), e)
                Toasters.get().toast(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    override fun onShowPhoto(src: String) {
        // 简介内 <img src="..."> 点击查看大图 (对照 app 端 PhotoDialog(src))
        onShowPhotoCb.invoke(src)
    }

    /**
     * 搜索/发现路由分发 (对照 app 端 BookInfoActivity.search)。
     *
     * - key 含 "::" → 切到 EXPLORE_SHOW 路由 (发现结果页), 需当前书源
     *   (对照 app 端 search 内 tmp.size > 1 分支: IntentData.source + ExploreShowActivity)
     * - 否则 → 切到 SEARCH 路由, 携带 key + submit
     *   (对照 app 端 search 内 else 分支: SearchActivity putExtra key/submit)
     *   注: app 端额外传 searchScope (curBookSource 有 searchUrl 时), desktop 端 SearchScreen
     *   暂不支持预置 searchScope, 简化为仅传 key/submit
     */
    private fun search(key: String, submit: Boolean) {
        val tmp = key.split("::", limit = 2)
        if (tmp.size > 1) {
            // 含 "::" → 发现结果页, 需异步查当前书源
            val b = book ?: return
            scope.launch {
                val source = AppDbProviders.get().bookSourceDao.getBookSource(b.origin)
                if (source != null) {
                    onExplore.invoke(source, tmp[0], tmp[1])
                }
            }
        } else {
            onSearch.invoke(tmp[0], submit)
        }
    }
}

/**
 * 桌面端图片大图查看 Dialog (替代 app 端 PhotoDialog)。
 *
 * 用 [produceState] 异步加载图片为 [ImageBitmap] (本地路径用 [ImageIO.read], 网络路径用
 * OkHttp 下载后 [ImageIO.read]), 在 [AlertDialog] 内 [Image] 显示。
 * 加载策略与 [DesktopBookCover] 一致 (JDK ImageIO + OkHttp, 不引入 Glide)。
 *
 * @param src 图片路径 (本地路径 / file:// / http(s)://)
 * @param onDismiss 关闭回调
 */
@Composable
private fun DesktopPhotoDialog(src: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, src) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val input: java.io.InputStream = when {
                    src.startsWith("http", true) -> {
                        val client = OkHttpClientProviders.get().okHttpClient
                        val response = client.newCall(Request.Builder().url(src).build()).execute()
                        ByteArrayInputStream(response.body?.bytes() ?: ByteArray(0))
                    }
                    src.startsWith("file:") -> {
                        val path = java.net.URI(src).path ?: src.removePrefix("file:")
                        File(path).inputStream()
                    }
                    else -> File(src).inputStream()
                }
                ImageIO.read(input)?.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(rememberString("close"))
            }
        },
        text = {
            bitmap?.let { b ->
                Image(
                    bitmap = b,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text(rememberString("loading"))
        },
    )
}
