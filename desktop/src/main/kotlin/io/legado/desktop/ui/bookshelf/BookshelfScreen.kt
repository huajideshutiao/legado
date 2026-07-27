package io.legado.desktop.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.toast.Toasters
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.bookshelf.BookshelfActions
import io.legado.app.ui.bookshelf.BookshelfActionsCallbacks
import io.legado.app.ui.bookshelf.BookshelfScreen as SharedBookshelfScreen
import io.legado.app.ui.bookshelf.BookshelfTier
import io.legado.app.ui.bookshelf.BookshelfViewModel
import io.legado.app.ui.bookshelf.DefaultBookCoverPlaceholder
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.desktop.help.book.DesktopBookshelfManagePlatform
import io.legado.desktop.ui.main.DesktopMainViewModel
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import io.legado.desktop.ui.component.FileDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端书架 Screen 入口 (包装 shared/commonMain 的 [SharedBookshelfScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `MainActivity` 通过 `BookshelfTab(style)` 装配书架 tab, 桌面端
 * 在 [io.legado.desktop.ui.DesktopApp] 的 `DesktopRoute.BOOKSHELF` 分支调用本入口。
 *
 * 本文件仅做桌面平台适配, 业务逻辑全部下沉到 shared/commonMain:
 * - **VM 生命周期**: 用 `remember { BookshelfViewModel() }` 持有, 窗口退出时
 *   `DisposableEffect.onDispose { viewModel.onCleared() }` 取消内部协程 scope
 *   (桌面端无 lifecycleScope, 对照 app 端 ViewModel.onCleared 语义)
 * - **默认布局**: 强制 [BookshelfTier.GRID] (网格风格, 任务要求"桌面端默认用网格风格")
 * - **封面加载**: 注入 [DesktopBookCover], 本地路径用 `javax.imageio.ImageIO`
 *   加载为 `BufferedImage` → [ImageBitmap] (任务要求"桌面端用 desktop ImageOps
 *   加载本地缓存, 不要用 Glide/Coil"; ImageOps 是 JS image 别名解密 API,
 *   不适合通用封面加载, 故用 JDK 内置 ImageIO 替代, 零外部依赖)
 * - **路由跳转**: 通过 [onBookClick] / [onBookLongClick] / [onSearchClick] 回调注入,
 *   由 [io.legado.desktop.ui.DesktopApp] 顶层实现具体路由切换
 *
 * # 简化项
 *
 * - 注入完整 8 项顶栏溢出菜单 (对照 shared BookshelfActions), 4 项接真实回调,
 *   5 项 no-op 保留入口位置 (避免"入口位置变了", 后续下沉 Dialog 再接真实逻辑)
 * - 不接入下拉刷新 (桌面端无下拉手势)
 * - 网络封面暂走 [DefaultBookCoverPlaceholder] (后续可接 OkHttp 下载 + 本地缓存)
 *
 * @param onBookClick 点击书籍 → 阅读路由 (携带 Book)
 * @param onBookLongClick 长按书籍 → 详情路由 (携带 Book), 默认 no-op 容错
 * @param onSearchClick 顶栏搜索按钮 → 搜索路由, 默认 no-op 容错
 * @param onAddLocalBook 顶栏溢出菜单"添加本地书籍" → 导入本地书籍路由, 默认 no-op 容错
 * @param onAddRemoteBook 顶栏溢出菜单"添加远程书籍" → 远程书籍路由, 默认 no-op 容错
 * @param onOpenBookshelfManage 顶栏溢出菜单"书架管理" → 书架管理路由, 默认 no-op 容错
 */
@Composable
fun BookshelfScreen(
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onAddLocalBook: () -> Unit = {},
    onAddRemoteBook: () -> Unit = {},
    onOpenBookshelfManage: () -> Unit = {},
) {
    val viewModel = remember { BookshelfViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onCleared() }
    }

    // 桌面端 MainViewModel 等价物 (对照 app 端 MainActivity 持有的 MainViewModel):
    // - 目录更新 (upToc) / 强制刷新 (forceRefresh) / 自动更新调度 (scheduleAutoUpdate)
    // - 书源 LRU 缓存 + 线程池限流 + 任务去重 + 阅读进度同步
    // - 内部自管 CoroutineScope, 窗口退出时 onCleared 取消 (无 ViewModelStore)
    // 详见 [DesktopMainViewModel] 类注释。
    val mainViewModel = remember { DesktopMainViewModel() }
    DisposableEffect(mainViewModel) {
        onDispose { mainViewModel.onCleared() }
    }

    // 订阅当前分组书籍列表 (StateFlow → Compose State), 用于:
    // - LaunchedEffect 监听 books 首次非空触发 scheduleAutoUpdate
    // - onRefreshShelf 取当前书籍列表传给 mainViewModel.forceRefresh
    val books by viewModel.books.collectAsState()

    // 首次拿到非空书籍列表后触发一次自动更新 (对照 app 端 observeGroupBooks 内
    // markGroupAutoUpdated(groupId) + scheduleAutoUpdate(list) 语义):
    // - markGroupAutoUpdated 用 VM 内 Set 去重, 同一 groupId 只触发一次 (VM 重建后重置,
    //   与 app 端 fragment 回收重建后再触发语义一致)
    // - scheduleAutoUpdate 内部受 AppConfig.autoRefreshBook 控制 + 10 分钟时间窗,
    //   配置关闭或近期已检查的书会跳过
    LaunchedEffect(books) {
        if (books.isNotEmpty() && mainViewModel.markGroupAutoUpdated(viewModel.currentGroupId.value)) {
            mainViewModel.scheduleAutoUpdate(books)
        }
    }

    // 分组管理对话框: 下沉 GroupManageDialog (shared/sharedUiMain) 接入
    // - showGroupManage: 对话框显隐状态, 由顶栏溢出菜单"分组管理"触发
    // - groupVm: GroupViewModelShared (shared commonMain), 注入 rememberCoroutineScope,
    //   转发 addGroup/upGroup/delGroup 到 bookGroupDao/bookDao
    // - groups: 订阅 bookGroupDao.flowAll() (含 show=false 分组, 管理用全集),
    //   produceState 生命周期绑定 Composable, 退出自动取消订阅
    var showGroupManage by remember { mutableStateOf(false) }
    // 新增分组对话框显隐 (GroupManageDialog.onAddGroup 触发, GroupEditDialog(group=null))
    var showAddGroupDialog by remember { mutableStateOf(false) }
    // 编辑/删除分组对话框目标 (GroupManageDialog.onDeleteGroup 触发, GroupEditDialog(group=editingGroup))
    // null=隐藏, 非 null=显示
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    val groupScope = rememberCoroutineScope()
    val groupVm = remember(groupScope) { GroupViewModelShared(groupScope) }
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }

    // 应用日志对话框状态 (false=隐藏, true=显示; onShowAppLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }

    // 通用协程作用域 (书架刷新/URL添加/导入书架等异步操作用; groupVm 仍用 groupScope)
    val scope = rememberCoroutineScope()

    // ---- URL 添加书籍对话框 (onShowAddBookByUrlAlert 触发, 对照 app 端 showAddBookByUrlAlert) ----
    var showAddBookByUrlDialog by remember { mutableStateOf(false) }
    var addBookUrlText by remember { mutableStateOf("") }
    // 添加中等待状态 (true=禁用对话框关闭; 对照 app 端 waitDialog "添加中...")
    var addingBook by remember { mutableStateOf(false) }

    // ---- 导入书架对话框 (onImportBookshelf 触发, 对照 app 端 importBookshelfAlert) ----
    var showImportBookshelfDialog by remember { mutableStateOf(false) }
    var importBookshelfText by remember { mutableStateOf("") }
    // 导入中等待状态 (true=禁用对话框关闭)
    var importing by remember { mutableStateOf(false) }

    // 文案标签 (AlertDialog 用, rememberString 是 @Composable 顶层缓存一次)
    val addBookUrlLabel = rememberString("add_book_url")
    val importBookshelfLabel = rememberString("import_bookshelf")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val selectFileLabel = rememberString("select_file")

    SharedBookshelfScreen(
        viewModel = viewModel,
        onBookClick = onBookClick,
        onBookLongClick = onBookLongClick,
        onSearchClick = onSearchClick,
        tier = BookshelfTier.GRID,
        coverSlot = { book -> DesktopBookCover(book) },
        actions = {
            // 注入完整 8 项溢出菜单 (对照 shared BookshelfActions), 全部接真实回调
            BookshelfActions(
                BookshelfActionsCallbacks(
                    onOpenSearch = onSearchClick,
                    onAddLocalBook = onAddLocalBook,
                    onAddRemoteBook = onAddRemoteBook,
                    onOpenBookshelfManage = onOpenBookshelfManage,
                    // 刷新书架: 委托给 DesktopMainViewModel.forceRefresh (对照 app 端
                    // mainViewModel.forceRefresh), 内部完成:
                    // - 并发限流 (onEachParallel + threadCount, 对照 app 端 refreshBook)
                    // - 书源 LRU 缓存 (避免每本书走 DB, 对照 app 端 bookSourceCache)
                    // - 任务去重 (refreshJob/upTocJob 活跃时拒绝重复触发, 内部 toast 提示)
                    // - 阅读进度同步 (syncBookProgress 避免冲掉用户并发修改, 对照 Book.sync)
                    // - DB 写回 + postEvent(EventBus.UP_BOOKSHELF) 通知 UI 刷新
                    // 进度通过 mainViewModel.isRefreshing / progressText StateFlow 暴露 (UI 可选订阅)
                    onRefreshShelf = { mainViewModel.forceRefresh(books) },
                    // URL 添加书籍: 弹 AlertDialog 输入 url (末尾渲染)
                    onShowAddBookByUrlAlert = { showAddBookByUrlDialog = true },
                    // 分组管理: 接入下沉的 GroupManageDialog (shared/sharedUiMain)
                    onShowGroupManage = { showGroupManage = true },
                    // 导入书架: 弹 AlertDialog 输入 url/json 或选文件 (末尾渲染)
                    onImportBookshelf = { showImportBookshelfDialog = true },
                    // 应用日志: 接入下沉的 AppLogDialog (shared/sharedUiMain)
                    onShowAppLog = { showLogDialog = true },
                )
            )
        },
    )

    if (showGroupManage) {
        GroupManageDialog(
            groups = groups,
            // 新建分组: 触发 GroupEditDialog(group=null) 让用户编辑分组名/排序/刷新等字段,
            // onConfirm 中调 groupVm.addGroup (用 BookGroup 字段), 替代原直接 addGroup(name)
            onAddGroup = { _ -> showAddGroupDialog = true },
            // 重命名: 从 groups 本地快照查原分组 copy 新名, upGroup 整行写回
            onRenameGroup = { groupId, newName ->
                groups.find { it.groupId == groupId.toLong() }?.let {
                    groupVm.upGroup(it.copy(groupName = newName))
                }
            },
            // 删除: 触发 GroupEditDialog(group=找到的 BookGroup), 由 GroupEditDialog 内删除按钮
            // 二次确认后回调 onDelete → groupVm.delGroup (替代原直接 delGroup)
            onDeleteGroup = { groupId ->
                groups.find { it.groupId == groupId.toLong() }?.let { editingGroup = it }
            },
            onDismiss = { showGroupManage = false },
        )
    }

    // ---- 应用日志对话框 (onShowAppLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- 新增分组对话框 (GroupManageDialog.onAddGroup 触发, GroupEditDialog(group=null)) ----
    // onConfirm: 用 BookGroup 字段调 groupVm.addGroup (groupName/bookSort/enableRefresh/cover),
    //   替代原 onAddGroup 直接 addGroup(name, bookSort=-1, enableRefresh=true, cover=null)
    if (showAddGroupDialog) {
        GroupEditDialog(
            group = null,
            onConfirm = { g ->
                groupVm.addGroup(
                    groupName = g.groupName,
                    bookSort = g.bookSort,
                    enableRefresh = g.enableRefresh,
                    cover = g.cover,
                ) {}
            },
            onDismiss = { showAddGroupDialog = false },
        )
    }

    // ---- 编辑/删除分组对话框 (GroupManageDialog.onDeleteGroup 触发, GroupEditDialog(group=editingGroup)) ----
    // onDelete: GroupEditDialog 内删除按钮二次确认后回调, 调 groupVm.delGroup 连带 bookDao.removeGroup
    editingGroup?.let { g ->
        GroupEditDialog(
            group = g,
            onConfirm = { updated -> groupVm.upGroup(updated) },
            onDismiss = { editingGroup = null },
            onDelete = { del -> groupVm.delGroup(del) {} },
        )
    }

    // ---- URL 添加书籍对话框 (onShowAddBookByUrlAlert 触发) ----
    // 对照 app 端 showAddBookByUrlAlert: alert + editTextView + okButton/cancelButton
    // 桌面端用 AlertDialog + OutlinedTextField 替代 alert DSL
    if (showAddBookByUrlDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { if (!addingBook) showAddBookByUrlDialog = false },
            title = { Text(addBookUrlLabel) },
            text = {
                OutlinedTextField(
                    value = addBookUrlText,
                    onValueChange = { addBookUrlText = it },
                    label = { Text("url") },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !addingBook && addBookUrlText.isNotBlank(),
                    onClick = {
                        // 添加书籍: 遍历 url 列表, 对每个 url 调 getBookInfoByUrlAwait +
                        // getChapterListAwait + migrateBook + insert (对照 app 端 addBookByUrl)
                        addingBook = true
                        val urls = addBookUrlText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        scope.launch {
                            var successCount = 0
                            withContext(Dispatchers.IO) {
                                for (url in urls) {
                                    runCatching {
                                        val book = WebBook.getBookInfoByUrlAwait(url)
                                        val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
                                            ?: throw NoStackTraceException("书源不存在")
                                        val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                                        val dbBook = AppDbProviders.get().bookDao.getBook(book.name, book.author)
                                        if (dbBook != null) {
                                            // 已存在同名书: 迁移阅读进度 (对照 app 端 dbBook.migrateTo(it, toc))
                                            DesktopBookshelfManagePlatform().migrateBook(dbBook, book, toc)
                                        } else {
                                            // 新书: 设置 order 为最小 order - 1 (对照 app 端 it.order = minOrder() - 1)
                                            book.order = AppDbProviders.get().bookDao.minOrder() - 1
                                        }
                                        AppDbProviders.get().bookDao.insert(book)
                                        AppDbProviders.get().bookChapterDao.insert(*toc.toTypedArray())
                                        successCount++
                                    }.onFailure { e ->
                                        AppLog.put("添加 $url 失败\n${e.localizedMessage}", e)
                                    }
                                }
                            }
                            addingBook = false
                            showAddBookByUrlDialog = false
                            addBookUrlText = ""
                            Toasters.get().toast(
                                if (successCount > 0) "$successCount/${urls.size} 成功" else "添加网址失败"
                            )
                        }
                    },
                ) { Text(if (addingBook) "添加中..." else okLabel) }
            },
            dismissButton = {
                TextButton(
                    enabled = !addingBook,
                    onClick = { showAddBookByUrlDialog = false },
                ) { Text(cancelLabel) }
            },
        )
    }

    // ---- 导入书架对话框 (onImportBookshelf 触发) ----
    // 对照 app 端 importBookshelfAlert: alert + editTextView(hint="url/json") + okButton + neutralButton(选文件)
    // 桌面端用 AlertDialog + OutlinedTextField + "选择文件"按钮 替代
    // 简化: 仅支持 JSON 文本/文件, 不支持 URL 下载 (okHttpClient.newCallResponseBody 未在 commonMain 暴露)
    if (showImportBookshelfDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { if (!importing) showImportBookshelfDialog = false },
            title = { Text(importBookshelfLabel) },
            text = {
                Column {
                    OutlinedTextField(
                        value = importBookshelfText,
                        onValueChange = { importBookshelfText = it },
                        label = { Text("url/json") },
                        singleLine = false,
                    )
                    TextButton(onClick = {
                        // 选文件: FileDialog 选 .txt/.json, 读取内容到 importBookshelfText
                        // (对照 app 端 neutralButton + importBookshelfLauncher 选文件)
                        val selected = FileDialogs.pickOpenFile(extensions = listOf("txt", "json"))
                        if (selected != null) {
                            runCatching { selected.readText() }
                                .onSuccess { importBookshelfText = it }
                                .onFailure { Toasters.get().toast(it.localizedMessage ?: "读取文件失败") }
                        }
                    }) { Text(selectFileLabel) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importing && importBookshelfText.isNotBlank(),
                    onClick = {
                        // 导入书架: 解析 JSON 数组, 遍历 bookInfo 列表 insert
                        // (对照 app 端 importBookshelf + importBookshelfByJsonAwait)
                        // 简化: 仅处理 origin+bookUrl 都有的情况 (走 getBookInfoAwait 刷新),
                        // 不处理精确搜索 (preciseSearchAwait 依赖较多, 保留 TODO)
                        importing = true
                        scope.launch {
                            runCatching {
                                val text = importBookshelfText.trim()
                                if (!text.startsWith("[")) {
                                    throw NoStackTraceException("格式不对")
                                }
                                withContext(Dispatchers.IO) {
                                    val bookInfoList = GSON.fromJsonArray<Map<String, Any>>(text).getOrThrow()
                                    var successCount = 0
                                    for (bookInfo in bookInfoList) {
                                        val name = bookInfo["name"] as? String ?: continue
                                        val author = bookInfo["author"] as? String ?: continue
                                        val origin = bookInfo["origin"] as? String
                                        val bookUrl = bookInfo["bookUrl"] as? String
                                        if (name.isEmpty() || AppDbProviders.get().bookDao.has(name, author)) continue
                                        if (origin == null || bookUrl == null) continue
                                        runCatching {
                                            val book = Book(bookUrl).apply {
                                                this.name = name
                                                this.author = author
                                                (bookInfo["kind"] as? String)?.let { this.kind = it }
                                                (bookInfo["coverUrl"] as? String)?.let { this.coverUrl = it }
                                                (bookInfo["intro"] as? String)?.let { this.intro = it }
                                                (bookInfo["tocUrl"] as? String)?.let { this.tocUrl = it }
                                            }
                                            val source = AppDbProviders.get().bookSourceDao.getBookSource(origin)
                                                ?: return@runCatching
                                            WebBook.getBookInfoAwait(source, book)
                                            book.originName = source.bookSourceName
                                            book.order = AppDbProviders.get().bookDao.minOrder() - 1
                                            AppDbProviders.get().bookDao.insert(book)
                                            successCount++
                                        }.onFailure { e ->
                                            AppLog.put("导入<$name>失败\n${e.localizedMessage}", e)
                                        }
                                    }
                                    successCount
                                }
                            }.onSuccess { count ->
                                importing = false
                                showImportBookshelfDialog = false
                                importBookshelfText = ""
                                Toasters.get().toast("导入完成 $count")
                            }.onFailure { e ->
                                importing = false
                                AppLog.put("导入书架失败\n${e.localizedMessage}", e)
                                Toasters.get().toast(e.localizedMessage ?: "ERROR")
                            }
                        }
                    },
                ) { Text(if (importing) "导入中..." else okLabel) }
            },
            dismissButton = {
                TextButton(
                    enabled = !importing,
                    onClick = { showImportBookshelfDialog = false },
                ) { Text(cancelLabel) }
            },
        )
    }
}

/**
 * 桌面端封面加载 Composable。
 *
 * 策略 (对照 app 端 Glide/Coil 网络封面加载, 桌面端用 OkHttp + ImageIO):
 * - 本地路径 (`file://` 或绝对路径): 用 [ImageIO.read] 加载为 `BufferedImage`
 *   → [toComposeImageBitmap] → [Image] 渲染
 * - 网络路径 (`http://`/`https://`): 用 [OkHttpClientProviders] 下载字节流 →
 *   [ImageIO.read] 解码 → [toComposeImageBitmap] 渲染 (对照 app 端 Glide 网络加载)
 * - 加载失败 (文件不存在/损坏/网络错误): 走 [DefaultBookCoverPlaceholder]
 *
 * 异步加载 (网络下载是 suspend), 用 [produceState] 绑定 Composable 生命周期,
 * 加载完成前显示占位, 避免 UI 阻塞。
 *
 * @see loadCoverBitmap
 */
@Composable
private fun DesktopBookCover(book: Book) {
    val coverPath = remember(book.bookUrl, book.coverUrl, book.customCoverUrl) {
        book.getDisplayCover()
    }
    // 异步加载: 本地用 ImageIO (阻塞 IO 在 IO dispatcher 跑), 网络用 OkHttp suspend 下载
    val bitmap by produceState<ImageBitmap?>(null, coverPath) {
        value = loadCoverBitmap(coverPath)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = book.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    } else {
        // 兜底: 走 shared 的默认占位 (书名首字 + accent 底)
        DefaultBookCoverPlaceholder(book)
    }
}

/**
 * 加载封面图片为 [ImageBitmap] (suspend, 支持本地与网络路径)。
 *
 * - `file://` URI / 绝对路径: [ImageIO.read] 解码本地文件
 * - `http://`/`https://`: [OkHttpClientProviders] 下载字节流 → [ByteArrayInputStream] →
 *   [ImageIO.read] 解码 (对照 app 端 Glide 网络封面加载)
 * - `content://` / 其他: 返回 null (调用方走占位)
 * - 加载失败 (IO 异常/损坏/网络错误): 返回 null
 *
 * 本地文件读取是阻塞 IO, 用 [withContext] 切到 [Dispatchers.IO]; 网络下载走
 * [newCallResponseBody] (内部已 suspend), 同样在 IO dispatcher 跑。
 */
private suspend fun loadCoverBitmap(path: String?): ImageBitmap? {
    if (path == null) return null
    return runCatching {
        withContext(Dispatchers.IO) {
            when {
                path.startsWith("file://") -> {
                    val file = File(path.removePrefix("file://"))
                    if (!file.exists()) return@withContext null
                    ImageIO.read(file)
                }
                path.startsWith("/") -> {
                    val file = File(path)
                    if (!file.exists()) return@withContext null
                    ImageIO.read(file)
                }
                path.startsWith("http://") || path.startsWith("https://") -> {
                    // 网络封面: OkHttp 下载字节流 → ImageIO 解码 (对照 app 端 Glide 网络加载)
                    val body = OkHttpClientProviders.get().okHttpClient.newCallResponseBody { url(path) }
                    body.use { ImageIO.read(ByteArrayInputStream(it.bytes())) }
                }
                else -> return@withContext null
            }?.toComposeImageBitmap()
        }
    }.getOrNull()
}
