package io.legado.app.ui.bookshelf

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import io.legado.app.ui.compose.component.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isLocal
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.manage.BookshelfManageCallbacks
import io.legado.app.ui.book.manage.BookshelfManageScreen as SharedBookshelfManageScreen
import io.legado.app.ui.book.manage.BookshelfManageState
import io.legado.app.ui.bookshelf.BookshelfScreen as SharedBookshelfScreen
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.formatNative
import io.legado.app.utils.parseJsonElement
import io.legado.app.utils.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import platform.Foundation.NSURL

/**
 * iOS 端书架 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookshelfScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/bookshelf/BookshelfScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.MainViewController] 中调用本入口。
 *
 * 本文件仅做 iOS 平台适配, 业务逻辑全部下沉到 shared/sharedUiMain:
 * - **VM 生命周期**: 用 `remember { BookshelfViewModel() }` 持有, UIViewController
 *   退出时 `DisposableEffect.onDispose { viewModel.onCleared() }` 取消内部协程 scope
 *   (iOS 端无 lifecycleScope, 对照 app 端 ViewModel.onCleared 语义);
 * - **默认布局**: 强制 [BookshelfTier.GRID] (网格风格, 与 desktop 一致);
 * - **封面加载**: 注入 [IosShelfCover], 复用 [IosInfoCover] (本地图 UIImage(contentsOfFile:)
 *   + 网络图 KmpHttpClient 下载, 详见 [IosBookCover] 文档);
 * - **路由跳转**: 通过 [onBookClick] / [onBookLongClick] / [onSearchClick] 回调注入,
 *   由 [MainViewController] 顶层实现具体路由切换 (P0 阶段 no-op, 后续接入 UINavigationController)。
 *
 * # 简化项 (与 desktop 一致)
 *
 * - 注入完整 8 项顶栏溢出菜单 (对照 shared [BookshelfActions]), 4 项接真实回调,
 *   5 项 no-op 保留入口位置 (避免"入口位置变了", 后续下沉 Dialog 再接真实逻辑);
 * - 不接入下拉刷新 (iOS 端暂未接入 PullToRefresh);
 * - 路由跳转 P0 阶段 no-op (后续接入 UINavigationController / SwiftUI NavigationStack)。
 *
 * @param onBookClick 点击书籍 → 阅读路由 (携带 Book), 默认 no-op 容错
 * @param onBookLongClick 长按书籍 → 详情路由 (携带 Book), 默认 no-op 容错
 * @param onSearchClick 顶栏搜索按钮 → 搜索路由, 默认 no-op 容错
 * @param onAddLocalBook 顶栏溢出菜单"添加本地书籍" → 导入本地书籍路由, 默认 no-op 容错
 * @param onAddRemoteBook 顶栏溢出菜单"添加远程书籍" → 远程书籍路由, 默认 no-op 容错
 * @param onOpenBookshelfManage 顶栏溢出菜单"书架管理" → 书架管理路由, 默认 no-op 容错
 * @param onOpenMyConfig 顶栏溢出菜单"我的" → 我的设置路由 (iOS 无底部 tab, 设置/RSS 由此进入), 默认 no-op 容错
 * @param onOpenExplore 顶栏溢出菜单"发现" → 发现页路由 (iOS 无底部 tab, 发现由此进入), 默认 no-op 容错
 *
 * 模式参考 desktop `BookshelfScreen.kt` (desktop/src/main/kotlin/io/legado/desktop/ui/bookshelf/BookshelfScreen.kt)。
 */
@Composable
fun IosBookshelfScreen(
    onBookClick: (Book) -> Unit = {},
    onBookLongClick: (Book) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onAddLocalBook: () -> Unit = {},
    onAddRemoteBook: () -> Unit = {},
    onOpenBookshelfManage: () -> Unit = {},
    onOpenMyConfig: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
) {
    val viewModel = remember { BookshelfViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // ---- KP5: 接入 onShowAppLog / onShowGroupManage 用的 state ----
    // 应用日志对话框状态 (false=隐藏; onShowAppLog 触发, 末尾 AppLogDialog 渲染;
    // 与 IosBookInfoScreen / desktop BookshelfScreen line 155-156 一致)
    var showLogDialog by remember { mutableStateOf(false) }
    // 分组管理对话框状态 (false=隐藏; onShowGroupManage 触发, 末尾 GroupManageDialog 渲染)
    var showGroupManage by remember { mutableStateOf(false) }
    // 分组管理 ViewModel (shared commonMain GroupViewModelShared, 供 GroupManageDialog 增删改分组;
    // 与 IosBookInfoScreen line 112 一致)
    val scope = rememberCoroutineScope()
    val groupVm = remember(scope) { GroupViewModelShared(scope) }
    // 全部分组 (订阅 bookGroupDao.flowAll(), 供 GroupManageDialog 展示分组列表;
    // 与 IosBookInfoScreen line 114-116 一致)
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    // ---- onAddRemoteBook / onOpenBookshelfManage 用的 state ----
    // 远程书籍 URL 输入对话框 (onAddRemoteBook 触发, 末尾 AlertDialog 渲染;
    // iOS 端无 RemoteBookWebDav, 简化为 URL 输入下载导入, 对照 desktop BookshelfScreen onShowAddBookByUrlAlert)
    var showAddRemoteBookDialog by remember { mutableStateOf(false) }
    var remoteBookUrlText by remember { mutableStateOf("") }
    // 添加中等待状态 (true=禁用对话框关闭; 对照 desktop addingBook)
    var importingRemoteBook by remember { mutableStateOf(false) }
    // 书架管理对话框 (onOpenBookshelfManage 触发, 末尾渲染 shared/sharedUiMain BookshelfManageScreen;
    // 对照 app 端 BookshelfManageActivity, iOS 端用全屏 Dialog 包装, 简化 state/callback)
    var showBookshelfManage by remember { mutableStateOf(false) }
    // 书架管理页面书籍列表 (订阅 bookDao.flowAll 取全集, 供 BookshelfManageScreen 展示)
    val manageBooks by produceState<List<Book>>(emptyList()) {
        AppDbProviders.get().bookDao.flowAll().collect { value = it }
    }
    // 书架管理页面选中集 (Book.bookUrl 集合)
    var manageSelected by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 文案模板 (actions lambda / 协程非 @Composable, 预先 remember 模板)
    val importCompleteTemplate = rememberString("import_complete")
    val importCompleteWithFailTemplate = rememberString("import_complete_with_fail_count")
    val formatInvalidText = rememberString("format_invalid")
    val sourceNotExistText = rememberString("source_not_exists")
    val importLocalBookFailedTemplate = rememberString("import_local_book_failed_log")
    val importBookFailedTemplate = rememberString("import_book_failed")
    val importBookshelfFailedTemplate = rememberString("import_bookshelf_failed")
    val addBookUrlFailedTemplate = rememberString("add_book_url_failed")
    val addBookUrlSuccessTemplate = rememberString("add_book_url_success")
    val addBookUrlAllFailedText = rememberString("add_book_url_all_failed")
    val addingBookText = rememberString("adding_book")

    SharedBookshelfScreen(
        viewModel = viewModel,
        onBookClick = onBookClick,
        onBookLongClick = onBookLongClick,
        onSearchClick = onSearchClick,
        tier = BookshelfTier.GRID,
        coverSlot = { book -> IosShelfCover(book) },
        actions = {
            // 注入完整 8 项溢出菜单 (对照 shared BookshelfActions), 全部接真实回调
            BookshelfActions(
                BookshelfActionsCallbacks(
                    onOpenSearch = onSearchClick,
                    // KP7: pickDocuments 选 epub/txt → FileBook.importLocalFile 导入
                    // (对照 app 端 ImportBookActivity + desktop ImportBookScreen;
                    // iOS 端无 ImportBookActivity 路由, 直接选文件导入)
                    onAddLocalBook = {
                        scope.launch {
                            val urls = pickDocuments(
                                contentTypes = listOf("public.epub", "public.text", "public.plain-text"),
                                allowsMultiple = true,
                            ) ?: return@launch
                            var success = 0
                            var fail = 0
                            withContext(Dispatchers.Default) {
                                for (url in urls) {
                                    runCatching {
                                        // NSURL.absoluteString → "file:///..." 形式 URI 字符串
                                        // (对照 desktop item.file.toURI().toString())
                                        val uriStr = url.absoluteString() ?: return@runCatching
                                        FileBook.importLocalFile(uriStr)
                                        success++
                                    }.onFailure { e ->
                                        fail++
                                        AppLog.put(importLocalBookFailedTemplate.formatNative(e.localizedMessage), e)
                                    }
                                }
                            }
                            Toasters.get().toast(importCompleteWithFailTemplate.formatNative(success, fail))
                        }
                    },
                    // KP7: iOS 端无 RemoteBookWebDav, 简化为 URL 输入对话框下载导入
                    // (对照 desktop BookshelfScreen onShowAddBookByUrlAlert; 末尾 AlertDialog 渲染)
                    onAddRemoteBook = { showAddRemoteBookDialog = true },
                    // KP7: 弹 BookshelfManageScreen 全屏 Dialog (对照 app 端 BookshelfManageActivity;
                    // shared/sharedUiMain 已下沉 BookshelfManageScreen, 末尾渲染分支读取 showBookshelfManage)
                    onOpenBookshelfManage = { showBookshelfManage = true },
                    // TODO iOS 端待下沉: BookshelfViewModel 无 refresh() 方法 (flow 已自动订阅 DB),
                    //      后续给 VM 加 refresh 入口或接 FlowBus.UP_BOOKSHELF 事件
                    onRefreshShelf = {},
                    // TODO iOS 端待下沉 Dialog: URL 添加书籍 (依赖 BookshelfViewModel.addBookByUrl 未提供)
                    onShowAddBookByUrlAlert = {},
                    // KP5: 接入下沉的 GroupManageDialog (shared/sharedUiMain), 末尾渲染分支读取 showGroupManage
                    onShowGroupManage = { showGroupManage = true },
                    // KP6: pickDocuments 选 JSON → 读文本 → 解析数组逐条导入书架
                    // (对照 desktop BookshelfScreen onImportBookshelf inline; 待 ImportBookshelfViewModelShared 下沉后改走 VM)
                    onImportBookshelf = {
                        scope.launch {
                            val urls = pickDocuments(
                                contentTypes = listOf("public.json", "public.text"),
                                allowsMultiple = false,
                            ) ?: return@launch
                            val firstUrl = urls.firstOrNull() ?: return@launch
                            val bytes = pickDocumentContent(firstUrl) ?: return@launch
                            val text = bytes.decodeToString().trim()
                            if (!text.startsWith("[")) {
                                Toasters.get().toast(formatInvalidText)
                                return@launch
                            }
                            // 解析 JSON 数组, 遍历 bookInfo 列表 insert (对照 desktop inline)
                            // 简化: 仅处理 origin+bookUrl 都有的情况 (走 WebBook.getBookInfoAwait 刷新)
                            runCatching {
                                val jsonArray = parseJsonElement(text) as? JsonArray
                                    ?: throw NoStackTraceException(formatInvalidText)
                                var successCount = 0
                                for (element in jsonArray) {
                                    val name = element.readString("$.name") ?: continue
                                    val author = element.readString("$.author") ?: continue
                                    if (name.isEmpty() ||
                                        AppDbProviders.get().bookDao.has(name, author)
                                    ) continue
                                    val origin = element.readString("$.origin")
                                    val bookUrl = element.readString("$.bookUrl")
                                    if (origin == null || bookUrl == null) continue
                                    runCatching {
                                        val book = Book(bookUrl).apply {
                                            this.name = name
                                            this.author = author
                                            element.readString("$.kind")?.let { this.kind = it }
                                            element.readString("$.coverUrl")?.let { this.coverUrl = it }
                                            element.readString("$.intro")?.let { this.intro = it }
                                            element.readString("$.tocUrl")?.let { this.tocUrl = it }
                                        }
                                        val source = AppDbProviders.get().bookSourceDao.getBookSource(origin)
                                            ?: return@runCatching
                                        WebBook.getBookInfoAwait(source, book)
                                        book.originName = source.bookSourceName
                                        book.order = AppDbProviders.get().bookDao.minOrder() - 1
                                        AppDbProviders.get().bookDao.insert(book)
                                        successCount++
                                    }.onFailure { e ->
                                        AppLog.put(importBookFailedTemplate.formatNative(name, e.localizedMessage), e)
                                    }
                                }
                                successCount
                            }.onSuccess { count ->
                                Toasters.get().toast(importCompleteTemplate.formatNative(count))
                            }.onFailure { e ->
                                AppLog.put(importBookshelfFailedTemplate.formatNative(e.localizedMessage), e)
                                Toasters.get().toast(e.localizedMessage ?: "ERROR")
                            }
                        }
                    },
                    // KP5: 接入下沉的 AppLogDialog (shared/sharedUiMain), 末尾渲染分支读取 showLogDialog
                    onShowAppLog = { showLogDialog = true },
                    // "我的"入口: 切 MY_CONFIG 路由 (设置/规则/RSS 均由该页进入, 对照 ohos My tab)
                    onOpenMyConfig = onOpenMyConfig,
                    // "发现"入口: 切 EXPLORE 路由 (iOS 无底部 tab, 发现由书架菜单进入)
                    onOpenExplore = onOpenExplore,
                )
            )
        },
    )

    // ---- KP5: 应用日志对话框 (onShowAppLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    // 与 IosBookInfoScreen line 246-248 / desktop BookshelfScreen 一致
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- KP5: 分组管理对话框 (onShowGroupManage 触发, 调用 shared/sharedUiMain 下沉的 GroupManageDialog) ----
    // 接入模式与 IosBookInfoScreen line 220-243 一致: groups 来自 bookGroupDao.flowAll(),
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

    // ---- KP7: 远程书籍 URL 输入对话框 (onAddRemoteBook 触发) ----
    // 对照 desktop BookshelfScreen onShowAddBookByUrlAlert: AlertDialog + OutlinedTextField
    // iOS 端无 RemoteBookWebDav, 简化为 URL 输入下载导入 (语义同 app 端 addBookByUrl)
    if (showAddRemoteBookDialog) {
        val okLabel = rememberString("ok")
        val cancelLabel = rememberString("cancel")
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { if (!importingRemoteBook) showAddRemoteBookDialog = false },
            title = { Text(rememberString("add_book_url")) },
            text = {
                AppTextField(
                    value = remoteBookUrlText,
                    onValueChange = { remoteBookUrlText = it },
                    label = "url",
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !importingRemoteBook && remoteBookUrlText.isNotBlank(),
                    onClick = {
                        // 下载导入: 遍历 url 列表, 调 getBookInfoByUrlAwait + getChapterListAwait + insert
                        // (对照 desktop BookshelfScreen onShowAddBookByUrlAlert confirmButton onClick)
                        importingRemoteBook = true
                        val urls = remoteBookUrlText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        scope.launch {
                            var successCount = 0
                            withContext(Dispatchers.IO) {
                                for (url in urls) {
                                    runCatching {
                                        val book = WebBook.getBookInfoByUrlAwait(url)
                                        val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                                            ?: throw NoStackTraceException(sourceNotExistText)
                                        val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                                        val dbBook = AppDbProviders.get().bookDao.getBook(book.name, book.author)
                                        if (dbBook != null) {
                                            // 已存在同名书: 保留原 order/group/阅读进度 (简化: 不做 migrateBook)
                                            book.order = dbBook.order
                                            book.group = dbBook.group
                                            book.durChapterIndex = dbBook.durChapterIndex
                                            book.durChapterPos = dbBook.durChapterPos
                                            book.durChapterTitle = dbBook.durChapterTitle
                                            book.durChapterTime = dbBook.durChapterTime
                                        } else {
                                            book.order = AppDbProviders.get().bookDao.minOrder() - 1
                                        }
                                        AppDbProviders.get().bookDao.insert(book)
                                        AppDbProviders.get().bookChapterDao.insert(*toc.toTypedArray())
                                        successCount++
                                    }.onFailure { e ->
                                        AppLog.put(addBookUrlFailedTemplate.formatNative(url, e.localizedMessage), e)
                                    }
                                }
                            }
                            importingRemoteBook = false
                            showAddRemoteBookDialog = false
                            remoteBookUrlText = ""
                            Toasters.get().toast(
                                if (successCount > 0) addBookUrlSuccessTemplate.formatNative(successCount, urls.size) else addBookUrlAllFailedText
                            )
                        }
                    },
                ) { Text(if (importingRemoteBook) addingBookText else okLabel) }
            },
            dismissButton = {
                TextButton(
                    enabled = !importingRemoteBook,
                    onClick = { showAddRemoteBookDialog = false },
                ) { Text(cancelLabel) }
            },
        )
    }

    // ---- KP7: 书架管理对话框 (onOpenBookshelfManage 触发, 调用 shared/sharedUiMain BookshelfManageScreen) ----
    // 对照 app 端 BookshelfManageActivity; iOS 端用全屏 Dialog 包装, state/callbacks 简化
    // (BookshelfManagePlatform 未在 iOS 端注册, 故下载/缓存/导出/批量改源等回调 no-op;
    //  仅保留显示书籍列表 + 选中 + 删除 + 改分组等基本功能)
    if (showBookshelfManage) {
        val manageListState = rememberLazyListState()
        // 文案标签 (rememberString 是 @Composable 顶层缓存一次)
        val okLabel = rememberString("ok")
        val cancelLabel = rememberString("cancel")
        val deleteLabel = rememberString("delete")
        val sureDelLabel = rememberString("sure_del")
        val localBookLabel = rememberString("local_book")
        // 删除确认对话框状态 (onDeleteBook 单项 / 批量删除共用)
        var deleteTarget by remember { mutableStateOf<List<Book>?>(null) }
        val callbacks = remember(manageBooks, manageSelected) {
            BookshelfManageCallbacks(
                onBack = { showBookshelfManage = false },
                onQueryChange = { /* 简化: 搜索框不接入筛选, 保留输入态 */ },
                onMove = { _, _ -> /* 拖拽排序: iOS 端简化不实现 */ },
                onSelectAll = { selectAll ->
                    manageSelected = if (selectAll) manageBooks.map { it.bookUrl }.toSet() else emptySet()
                },
                onRevertSelection = {
                    val all = manageBooks.map { it.bookUrl }.toSet()
                    manageSelected = all - manageSelected
                },
                onMainAction = { /* 移动到分组: iOS 端简化不实现 */ },
                onSelectActions = { emptyList() },
                onToggle = { book, checked ->
                    manageSelected = if (checked) manageSelected + book.bookUrl
                    else manageSelected - book.bookUrl
                },
                onOpenBook = { /* iOS 端书架管理页不直接打开书籍 */ },
                onOriginText = { book ->
                    if (book.isLocal) localBookLabel else book.originName
                },
                onGroupName = { gid ->
                    groups.filter { it.groupId > 0 && it.groupId and gid > 0 }
                        .map { it.groupName }
                        .let { if (it.isEmpty()) "" else it.joinToString(",") }
                },
                onDeleteBook = { book -> deleteTarget = listOf(book) },
                onShowGroupManage = { showGroupManage = true },
                onSetBookTypeFilter = { /* 简化: 类型筛选不实现 */ },
            )
        }
        val state = BookshelfManageState(
            books = manageBooks,
            selected = manageSelected,
            groups = groups,
            canDrag = false,
        )
        // 全屏 Dialog 包装 BookshelfManageScreen (对照 IosReadStyleDialog TipConfig 用法:
        // Dialog + DialogProperties(usePlatformDefaultWidth = false) + Surface(fillMaxSize))
        Dialog(
            onDismissRequest = { showBookshelfManage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                SharedBookshelfManageScreen(
                    state = state,
                    callbacks = callbacks,
                    listState = manageListState,
                    listModifier = Modifier,
                    coverSlot = { book ->
                        IosInfoCover(book = book, modifier = Modifier)
                    },
                )
            }
        }
        // 删除确认对话框 (onDeleteBook 单项 / 批量删除共用, 对照 desktop BookshelfManageScreen deleteTarget)
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
                            }
                            // 同步清理选中集
                            val removed = books.map { it.bookUrl }.toSet()
                            manageSelected = manageSelected - removed
                        }
                    }) { Text(okLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text(cancelLabel) }
                },
            )
        }
    }
}

/**
 * iOS 端书架封面 Composable (对应 desktop 的 `DesktopBookCover`)。
 *
 * 复用 [IosInfoCover] (详情页封面) 加载逻辑, 传入书架封面尺寸约束:
 * - `fillMaxWidth()`: 列表项/网格项均期望封面填满宽度;
 * - `height(160.dp)`: 与 desktop `DesktopBookCover` 高度一致 (160dp, 3:4 比例近似)。
 *
 * 加载成功: IosInfoCover 内部用 [androidx.compose.foundation.Image] + Skia ImageBitmap 渲染;
 * 加载失败/进行中: IosInfoCover 内部回退到 Box + 书名首字 + accent 底 (与 desktop 视觉接近)。
 *
 * 注: [IosInfoCover] 的 fallback 用 `Color(0xFF165DFF)` 写死 (与 shared DefaultBookCoverPlaceholder
 * 的 colors.accent 略有差异), 这是 [IosBookCover.kt] 既有实现, 此处不改动。
 *
 * @param book 当前书籍 (传 null 时 IosInfoCover 走 fallback 占位)
 */
@Composable
private fun IosShelfCover(book: Book) {
    IosInfoCover(
        book = book,
        modifier = Modifier.fillMaxWidth().height(160.dp),
    )
}
