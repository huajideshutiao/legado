package io.legado.desktop.ui.book.import.local

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.fileBook.EpubFile
import io.legado.app.ui.book.import.ImportFileItem

import io.legado.app.ui.book.import.local.ImportBookUiActions
import io.legado.app.ui.book.import.local.ImportBookUiState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.legado.desktop.ui.component.FileDialogs

/**
 * 桌面端导入本地书籍 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.import.local.ImportBookScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `ImportBookActivity`, 桌面端仅做平台适配, UI 渲染与交互骨架全部下沉到
 * shared/sharedUiMain 的 [io.legado.app.ui.book.import.local.ImportBookScreen]:
 *
 * - **平台 Provider 注入**: [DesktopThemeStoreProvider] / [DesktopAppConfigProvider] /
 *   [DesktopEventBusProvider] 经 [CompositionLocalProvider] 注入, 让 commonMain 的
 *   [AppTheme] / [io.legado.app.ui.book.import.local.ImportBookScreen] 跨平台运行
 * - **数据流**: 持有 [ImportBookUiState] (immutable, copy 更新), actions 用 [remember]
 *   持有稳定实例避免重组
 * - **条目模型**: [DesktopImportBook] 基于 [java.io.File] 实现 [ImportFileItem]
 *   (app 端 [io.legado.app.ui.book.import.local.ImportBook] 基于 FileDoc, FileDoc 是
 *   Android 专属未下沉, 桌面端用 [File] 替代)
 *
 * # 简化项 (依赖未下沉功能, 用 TODO 注释 + no-op)
 *
 * - **文件选择器 (SAF)**: app 端 `registerHandleFile` 走 Storage Access Framework,
 *   桌面端用 [JFileChooser] (DIRECTORIES_ONLY) 替代
 * - **文件扫描**: app 端 `FileDoc.list` 走 DocumentFile, 桌面端用 [File.listFiles] 替代
 * - **加入书架**: app 端 `FileBook.importLocalFile` 未下沉, onAddSelectionToBookshelf
 *   仅标记 `isOnBookShelf = true` + refreshTick++, 不写库 (TODO 注释)
 * - **打开书籍/压缩包**: app 端 `startReadBook` / `onArchiveFileClick` 依赖
 *   `startActivityForBook` + `ArchiveUtils`, 未下沉, onItemClick/onItemLongClick no-op
 * - **文件名导入 js**: app 端 `alert` 弹窗, 桌面端用 [AlertDialog] + OutlinedTextField 替代
 *   (输入框文案与 app 端一致, 暂不持久化到 AppConfig, 因 AppConfig.bookImportFileName
 *   是 Android 扩展属性)
 * - **导入路径持久化**: app 端 `AppConfig.importBookPath` 是 Android 扩展, 桌面端
 *   每次启动需重新选择目录 (用 JFileChooser)
 *
 * @param onBack 返回回调 (由 DesktopApp 注入, 切回 BOOKSHELF 路由)
 */
@Composable
fun ImportBookScreen(onBack: () -> Unit) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            ImportBookContent(onBack = onBack)
        }
    }
}

/**
 * 桌面端导入本地书籍条目模型 (替代 app 端 ImportBook)。
 *
 * app 端 [io.legado.app.ui.book.import.local.ImportBook] 基于 `FileDoc` (Android SAF),
 * FileDoc 未下沉到 commonMain, 桌面端用 [java.io.File] 替代:
 * - [tag] 取文件名后缀 (与 app 端 ImportBook.tag 一致)
 * - [itemKey] 取 [file.getAbsolutePath] (与 app 端 ImportBook.itemKey 取 `file.toString()` 一致)
 * - [lastModified] 桥接 [File.lastModified]
 * - [isOnBookShelf] 由扫描时查询 `bookDao.hasFile(name)` 填充, 加入书架后原地变更为 true
 */
private data class DesktopImportBook(
    val file: File,
    override val isUpDir: Boolean = false,
    override var isOnBookShelf: Boolean = false,
) : ImportFileItem {
    override val name: String get() = if (isUpDir) ".." else file.name
    override val isDir: Boolean get() = file.isDirectory
    override val size: Long get() = if (file.isDirectory) 0L else file.length()
    override val lastModified: Long get() = file.lastModified()
    override val tag: String get() =
        if (isUpDir || file.isDirectory) "" else file.name.substringAfterLast(".", "")
    override val itemKey: Any get() = file.absolutePath
}

@Composable
private fun ImportBookContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 用 rememberUpdatedState 持有最新 onBack 引用, 避免 object 内 override fun onBack()
    // 名称遮蔽导致递归调用 (override 方法名与外层参数同名时用 .value.invoke() 消歧)
    val onBackUpdated = rememberUpdatedState(onBack)

    // 文件选择/js导入文案 (局部函数非 @Composable, 需预先缓存)
    val selectImportBookDirLabel = rememberString("select_import_book_dir")
    val filenameImportJsTitleLabel = rememberString("filename_import_js_title")
    val filenameImportJsSummaryLabel = rememberString("filename_import_js_summary")
    // AlertDialog 按钮文案 (替换原 JOptionPane, 与 BookSourceEditScreen 模式一致)
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    // 文件名导入 js 输入对话框状态 (替换原 javax.swing.JOptionPane.showInputDialog 同步阻塞,
    // 与 BookSourceEditScreen emptyUrlNameDialog 模式一致; alertImportFileName 触发显示,
    // 末尾 AlertDialog 渲染分支读取, 确认按钮调 AppLog.put 记录输入)
    var showImportFileNameDialog by remember { mutableStateOf(false) }
    var importFileNameJsText by remember { mutableStateOf("") }

    // 列表条目 (含上级目录占位)
    var items by remember { mutableStateOf<List<DesktopImportBook>>(emptyList()) }
    // 选中集 (与 app 端一致, 每次切目录时清空)
    var selected by remember { mutableStateOf<Set<DesktopImportBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 ImportBookActivity.refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 当前根目录 (null 时显示空态提示选择目录)
    var rootFile by remember { mutableStateOf<File?>(null) }
    // 子目录栈 (面包屑路径用, 空表示在根目录)
    val subDirs = remember { mutableListOf<File>() }
    // 面包屑路径 (null 时不显示)
    var path by remember { mutableStateOf<String?>(null) }
    // 加载条
    var loading by remember { mutableStateOf(false) }
    // 空态提示
    var emptyMsgVisible by remember { mutableStateOf(true) }
    // 搜索关键字
    var searchKey by remember { mutableStateOf("") }
    // 排序方式: 0=名称 / 1=大小 / 2=时间
    var sortState by remember { mutableIntStateOf(0) }

    fun isCheckable(item: DesktopImportBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 过滤 + 排序 (对照 ImportBookViewModel.dataFlow 的 map 分支) */
    fun applyFilterAndSort(all: List<DesktopImportBook>): List<DesktopImportBook> {
        val skipFilter = searchKey.isBlank()
        // 排序: 目录优先 (isDir=false 排前), 然后按 sortState
        val comparator = when (sortState) {
            2 -> compareBy<DesktopImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy<DesktopImportBook>({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy { it.name }
        return all.asSequence().filter { skipFilter || it.name.contains(searchKey) }
            .sortedWith(comparator).toList()
    }

    /**
     * 扫描指定目录, 生成 items (含上级目录占位) + 查询是否已在书架。
     *
     * 对照 app 端 ImportBookViewModel.loadDoc + ImportBookActivity.upDocs:
     * - 用 [File.listFiles] 列出子项, 过滤隐藏文件 + 仅保留目录/书籍/压缩包
     * - 子目录栈非空时插入"上级目录"占位 (isUpDir=true)
     * - 查询 `bookDao.hasFile(name)` 标记 isOnBookShelf
     */
    suspend fun loadDir(target: File) {
        loading = true
        emptyMsgVisible = false
        val rawItems = withContext(Dispatchers.IO) {
            val children = target.listFiles() ?: emptyArray()
            // 过滤: 隐藏文件 + 非目录需匹配 bookFileRegex 或 archiveFileRegex
            val filtered = children.filter { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDirectory -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            // 批量查询是否已在书架 (避免每个文件单独 runBlocking)
            val dao = AppDbProviders.get().bookDao
            filtered.map { f ->
                val onShelf = if (!f.isDirectory) dao.hasFile(f.name) else false
                DesktopImportBook(f, isOnBookShelf = onShelf)
            }
        }
        // 子目录栈非空时插入上级目录占位
        val withUpDir = if (subDirs.isNotEmpty()) {
            listOf(DesktopImportBook(subDirs.last(), isUpDir = true)) + rawItems
        } else {
            rawItems
        }
        items = applyFilterAndSort(withUpDir)
        selected = emptySet()
        loading = false
        emptyMsgVisible = items.isEmpty()
    }

    /** 更新面包屑路径 (对照 ImportBookActivity.upDocs 内 showBreadcrumb) */
    fun updatePath(root: File) {
        var p = root.name + File.separator
        subDirs.forEach { p += it.name + File.separator }
        path = p
    }

    /** 切换根目录 (选择目录后调用) */
    fun initRoot(root: File) {
        subDirs.clear()
        rootFile = root
        updatePath(root)
        scope.launch { loadDir(root) }
    }

    /** 进入子目录 */
    fun nextDir(dir: File) {
        subDirs.add(dir)
        rootFile?.let { updatePath(it) }
        scope.launch { loadDir(dir) }
    }

    /** 返回上级目录, 返回 false 表示已在根目录 (供 onBack 判断) */
    fun goBackDir(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        rootFile?.let { root ->
            updatePath(root)
            val target = subDirs.lastOrNull() ?: root
            scope.launch { loadDir(target) }
        }
        return true
    }

    /** 扫描当前文件夹及所有子文件夹 (对照 ImportBookViewModel.scanDoc) */
    fun scanFolder() {
        val root = rootFile ?: return
        val start = subDirs.lastOrNull() ?: root
        scope.launch {
            loading = true
            items = emptyList()
            val collected = withContext(Dispatchers.IO) {
                val result = mutableListOf<DesktopImportBook>()
                val stack = ArrayDeque<File>()
                stack.addLast(start)
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    val children = cur.listFiles() ?: continue
                    val dao = AppDbProviders.get().bookDao
                    children.forEach { f ->
                        when {
                            f.name.startsWith(".") -> Unit
                            f.isDirectory -> stack.addLast(f)
                            f.name.matches(bookFileRegex) || f.name.matches(archiveFileRegex) ->
                                result.add(
                                    DesktopImportBook(f, isOnBookShelf = dao.hasFile(f.name))
                                )
                        }
                    }
                }
                result
            }
            // 扫描结果不含目录与上级目录占位, 直接排序
            items = applyFilterAndSort(collected)
            selected = emptySet()
            loading = false
            emptyMsgVisible = items.isEmpty()
        }
    }

    /** 弹 JFileChooser 选择导入根目录 (替代 app 端 selectFolder.launch) */
    fun pickFolder() {
        scope.launch {
            val chosen = withContext(Dispatchers.IO) {
                FileDialogs.pickDirectory(title = selectImportBookDirLabel)
            } ?: run {
                AppLog.put(jvmGetString("import_local_book_user_cancelled"))
                return@launch
            }
            initRoot(chosen)
        }
    }

    /** 弹 AlertDialog 输入文件名导入 js (替代 app 端 alertImportFileName; 替换原 JOptionPane) */
    fun alertImportFileName() {
        // 触发 AlertDialog 显示 (替换原 JOptionPane.showInputDialog 同步阻塞;
        // 末尾 AlertDialog 渲染分支读取 showImportFileNameDialog,
        // 确认按钮调 AppLog.put 记录输入, 复刻原 `js ?: return` + AppLog.put 语义)
        importFileNameJsText = ""
        showImportFileNameDialog = true
    }

    // ---- UiActions 实现 (remember 持有稳定实例) ----
    val actions = remember {
        object : ImportBookUiActions<DesktopImportBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                // 用 onBackUpdated.value.invoke() 消歧, 避免递归调用 override fun onBack()
                if (!goBackDir()) onBackUpdated.value.invoke()
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                rootFile?.let { root ->
                    val target = subDirs.lastOrNull() ?: root
                    scope.launch { loadDir(target) }
                }
            }

            override fun onPickFolder() = pickFolder()

            override fun onUpSort(sort: Int) {
                sortState = sort
                rootFile?.let { root ->
                    val target = subDirs.lastOrNull() ?: root
                    scope.launch { loadDir(target) }
                }
            }

            override fun onScanFolder() = scanFolder()

            override fun onAlertImportFileName() = alertImportFileName()

            override fun onSelectAll(selectAll: Boolean) {
                selected = if (selectAll) items.filter { isCheckable(it) }.toSet() else emptySet()
            }

            override fun onRevertSelection() {
                selected = items.filter { isCheckable(it) }.toSet() - selected
            }

            override fun onAddSelectionToBookshelf() {
                // 导入本地书籍: epub 走 EpubFile, cbz/zip 走 CbzFile, 其他格式 (txt 等) 仅创建 Book 入库
                val books = selected.toHashSet()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = AppDbProviders.get().bookDao
                        val chapterDao = AppDbProviders.get().bookChapterDao
                        books.forEach { item ->
                            if (item.file.length() == 0L) return@forEach
                            val fileName = item.file.name
                            val name = fileName.substringBeforeLast(".")
                            val bookUrl = item.file.toURI().toString()
                            if (dao.getBook(bookUrl) == null) {
                                // cbz/zip 漫画: BookType.image 标记, 走 CbzFile 解析封面/ComicInfo.xml
                                val isCbz = fileName.endsWith(".cbz", ignoreCase = true) ||
                                    fileName.endsWith(".zip", ignoreCase = true)
                                val book = Book(
                                    bookUrl = bookUrl,
                                    name = name,
                                    author = "",
                                    originName = fileName,
                                    latestChapterTime = item.file.lastModified(),
                                    order = dao.minOrder() - 1,
                                    origin = bookUrl,
                                    type = if (isCbz) BookType.image or BookType.local
                                           else BookType.text or BookType.local
                                )
                                // epub 文件: 调用 EpubFile 解析元数据 (书名/作者/简介/封面) + 章节列表
                                // EpubFile.upBookInfo 会构造 EpubFile(book) 并触发封面加载,
                                // getChapterList 返回章节列表写入 bookChapterDao 供阅读流使用
                                if (fileName.endsWith(".epub", ignoreCase = true)) {
                                    runCatching {
                                        EpubFile.upBookInfo(book)
                                        val chapters = EpubFile.getChapterList(book)
                                        if (chapters.isNotEmpty()) {
                                            chapterDao.insert(*chapters.toTypedArray())
                                            book.totalChapterNum = chapters.size
                                        }
                                    }.onFailure {
                                        AppLog.put(jvmGetString("epub_import_parse_failed_log", fileName, it.localizedMessage), it)
                                    }
                                }
                                // cbz/zip 漫画: 调用 CbzFile 解析封面 + ComicInfo.xml 元数据 + 章节列表
                                // CbzFile.upBookInfo 提取首图压缩为 JPEG 封面 + 解析 ComicInfo.xml
                                // (标题/作者/简介/分类), getChapterList 按目录分组返回章节列表
                                if (isCbz) {
                                    runCatching {
                                        CbzFile.upBookInfo(book)
                                        val chapters = CbzFile.getChapterList(book)
                                        if (chapters.isNotEmpty()) {
                                            chapterDao.insert(*chapters.toTypedArray())
                                            book.totalChapterNum = chapters.size
                                        }
                                    }.onFailure {
                                        AppLog.put(jvmGetString("cbz_import_parse_failed_log", fileName, it.localizedMessage), it)
                                    }
                                }
                                dao.insert(book)
                            }
                            item.isOnBookShelf = true
                        }
                    }
                    selected = emptySet()
                    refreshTick++
                }
            }

            override fun onItemClick(item: DesktopImportBook) {
                when {
                    item.isUpDir -> goBackDir()
                    item.isDir -> nextDir(item.file)
                    !item.isOnBookShelf -> {
                        // 切换选中 (对照 ImportBookActivity.toggleSelect)
                        selected = if (item in selected) selected - item else selected + item
                    }
                    else -> {
                        // TODO: app 端 startRead(item.file) 依赖 startActivityForBook +
                        //  ArchiveUtils, 未下沉, 桌面端暂不实现打开阅读
                    }
                }
            }

            override fun onItemLongClick(item: DesktopImportBook) {
                // TODO: app 端 startRead(fileDoc) 依赖 ArchiveUtils, 未下沉, 桌面端暂 no-op
            }
        }
    }

    // ---- 渲染 shared Screen ----
    val state = ImportBookUiState(
        items = items,
        selected = selected,
        refreshTick = refreshTick,
        path = path,
        loading = loading,
        emptyMsgVisible = emptyMsgVisible,
        searchKey = searchKey,
        checkableCount = checkableCount,
        sortState = sortState,
    )
    io.legado.app.ui.book.import.local.ImportBookScreen(state, actions)

    // ---- 对话框渲染 (替换原 javax.swing.JOptionPane.showInputDialog) ----
    // 文件名导入 js 输入对话框 (alertImportFileName 触发 showImportFileNameDialog=true;
    //   确认按钮调 AppLog.put 记录输入, 复刻原 `js ?: return` + AppLog.put 语义;
    //   filenameImportJsTitleLabel 用作 title, filenameImportJsSummaryLabel 用作输入框 label)
    if (showImportFileNameDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showImportFileNameDialog = false },
            title = filenameImportJsTitleLabel,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                val js = importFileNameJsText
                showImportFileNameDialog = false
                // TODO: app 端写 AppConfig.bookImportFileName, 桌面端 AppConfig 是 Android 扩展,
                //  暂不持久化; 后续接入桌面端 PreferenceStore 后补全
                AppLog.put(jvmGetString("filename_import_js_input_log", js.take(50)))
            },
            cancelButton = AlertButton(cancelLabel),
        ) {
            AppTextField(
                value = importFileNameJsText,
                onValueChange = { importFileNameJsText = it },
                label = filenameImportJsSummaryLabel,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}
