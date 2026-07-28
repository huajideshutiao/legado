package io.legado.app.ui.book.import.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.data.AppDbProviders
import io.legado.app.help.file.pickDirectory
import io.legado.app.model.fileBook.FileBook
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.import.ImportFileItem

import io.legado.app.ui.book.import.local.ImportBookUiActions
import io.legado.app.ui.book.import.local.ImportBookUiState
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.utils.formatNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import io.legado.app.utils.File

/**
 * iOS 端导入本地书籍 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.import.local.ImportBookScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `ImportBookActivity` / desktop `ImportBookScreen.kt`, iOS 端仅做平台适配,
 * UI 渲染与交互骨架全部下沉到 shared/sharedUiMain 的 [io.legado.app.ui.book.import.local.ImportBookScreen]:
 *
 * - **数据流**: 持有 [ImportBookUiState] (immutable, copy 更新), actions 用 [remember]
 *   持有稳定实例避免重组 (与 desktop 一致; iOS Provider 已在 MainViewController 顶层注入,
 *   不需要再嵌套 CompositionLocalProvider)
 * - **条目模型**: [IosImportBook] 基于 [kotlin.io.File] 实现 [ImportFileItem]
 *   (与 desktop `DesktopImportBook` 同模式; iOS 端 [File] 在 Kotlin/Native 可用,
 *   文件路径来自 [NSURL.path] 经 [NSURL.startAccessingSecurityScopedResource] 解锁访问)
 *
 * # 平台差异 (iOS 安全作用域 URL)
 *
 * - **文件选择器 (SAF)**: app 端 `registerHandleFile` 走 Storage Access Framework,
 *  desktop 端用 JFileChooser (DIRECTORIES_ONLY), iOS 端用 [pickDirectory] (UIDocumentPickerViewController
 *  Open 模式 + public.folder UTI, 返回 security-scoped NSURL)
 * - **目录枚举**: 拷入沙盒后, iOS 端在 [NSURL.startAccessingSecurityScopedResource] /
 *  [NSURL.stopAccessingSecurityScopedResource] 之间用 [File.listFiles] 列出子项
 *  (与 desktop 直接 File.listFiles 一致, 仅多一层安全作用域 unlock)
 * - **路径持久化**: app 端 `AppConfig.importBookPath` 是 Android 扩展, iOS 端每次启动需重新选择目录
 *  (与 desktop 一致; security-scoped URL 持久化需 bookmarkData, 暂未实现)
 *
 * # 简化项 (依赖未下沉功能, 用 TODO 注释 + no-op)
 *
 * - **加入书架**: 走 `FileBook.importLocalFile` 门面 (FileBookProviders 已注册);
 *  epub 走 nativeMain EpubFile 真实解析, txt/pdf/cbz 未下沉抛明确异常记日志跳过
 * - **打开书籍**: app 端 `startReadBook` / `onArchiveFileClick` 依赖
 *  `startActivityForBook` + `ArchiveUtils`, 未下沉, onItemClick/onItemLongClick no-op
 * - **文件名导入 js**: app 端 `alert` 弹窗, iOS 端用 [AlertDialog] + [AppTextField] 替代
 *  (与 desktop 一致; 暂不持久化到 AppConfig, 因 AppConfig.bookImportFileName 是 Android 扩展)
 *
 * @param onBack 返回回调 (由 IosNavHost 注入, 切回 BOOKSHELF 路由)
 */
@Composable
fun IosImportBookScreen(onBack: () -> Unit) {
    IosImportBookContent(onBack = onBack)
}

/**
 * iOS 端导入本地书籍条目模型 (替代 app 端 ImportBook; 与 desktop `DesktopImportBook` 字段一致)。
 *
 * app 端 [io.legado.app.ui.book.import.local.ImportBook] 基于 `FileDoc` (Android SAF),
 * FileDoc 未下沉到 commonMain, iOS 端用 [kotlin.io.File] 替代:
 * - [tag] 取文件名后缀 (与 app 端 ImportBook.tag 一致)
 * - [itemKey] 取 [file.getAbsolutePath] (与 app 端 ImportBook.itemKey 取 `file.toString()` 一致)
 * - [lastModified] 桥接 [File.lastModified]
 * - [isOnBookShelf] 由扫描时查询 `bookDao.hasFile(name)` 填充, 加入书架后原地变更为 true
 */
private data class IosImportBook(
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
private fun IosImportBookContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 持有最新 onBack 引用, 避免 object 内 override fun onBack() 名称遮蔽导致递归调用
    val onBackUpdated = rememberUpdatedState(onBack)

    // js 导入文案 (局部函数非 @Composable, 需预先缓存)
    val filenameImportJsTitleLabel = rememberString("filename_import_js_title")
    val filenameImportJsSummaryLabel = rememberString("filename_import_js_summary")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 文案模板 (initRoot/nextDir/goBackDir/scanFolder/pickFolder lambda 非 @Composable, 预先 remember)
    val iosUnlockScopeFailedAccessSelectedDirLog = rememberString("ios_unlock_scope_failed_access_selected_dir_log")
    val iosUnlockScopeFailedAccessSubdirLog = rememberString("ios_unlock_scope_failed_access_subdir_log")
    val iosUnlockScopeFailedAccessParentDirLog = rememberString("ios_unlock_scope_failed_access_parent_dir_log")
    val iosUnlockScopeFailedScanDirLog = rememberString("ios_unlock_scope_failed_scan_dir_log")
    val iosUserCancelSelectImportDirLog = rememberString("ios_user_cancel_select_import_dir_log")
    val cannotAccessSelectedDirText = rememberString("cannot_access_selected_dir")

    // 文件名导入 js 输入对话框状态 (alertImportFileName 触发显示, 末尾 AlertDialog 渲染分支读取,
    // 确认按钮调 AppLog.put 记录输入)
    var showImportFileNameDialog by remember { mutableStateOf(false) }
    var importFileNameJsText by remember { mutableStateOf("") }

    // 列表条目 (含上级目录占位)
    var items by remember { mutableStateOf<List<IosImportBook>>(emptyList()) }
    // 选中集 (与 app 端一致, 每次切目录时清空)
    var selected by remember { mutableStateOf<Set<IosImportBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 ImportBookActivity.refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 当前根目录 NSURL (security-scoped, null 时显示空态提示选择目录)
    var rootUrl by remember { mutableStateOf<NSURL?>(null) }
    // 当前根目录对应的本地路径 (rootUrl.path, 供 File 枚举用)
    var rootPath by remember { mutableStateOf<String?>(null) }
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

    fun isCheckable(item: IosImportBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 过滤 + 排序 (对照 ImportBookViewModel.dataFlow 的 map 分支) */
    fun applyFilterAndSort(all: List<IosImportBook>): List<IosImportBook> {
        val skipFilter = searchKey.isBlank()
        // 排序: 目录优先 (isDir=false 排前), 然后按 sortState
        val comparator = when (sortState) {
            2 -> compareBy<IosImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy<IosImportBook>({ !it.isDir }, { -it.size })
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
     *
     * iOS 安全作用域: 调用方需在 [NSURL.startAccessingSecurityScopedResource] /
     * [NSURL.stopAccessingSecurityScopedResource] 之间调本函数 (rootUrl 解锁后才能访问子项)
     */
    suspend fun loadDir(target: File) {
        loading = true
        emptyMsgVisible = false
        val rawItems = withContext(Dispatchers.Default) {
            val children = target.listFiles() ?: emptyArray()
            // 过滤: 隐藏文件 + 非目录需匹配 bookFileRegex 或 archiveFileRegex
            val filtered = children.filter { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDirectory -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            // 批量查询是否已在书架
            val dao = AppDbProviders.get().bookDao
            filtered.map { f ->
                val onShelf = if (!f.isDirectory) dao.hasFile(f.name) else false
                IosImportBook(f, isOnBookShelf = onShelf)
            }
        }
        // 子目录栈非空时插入上级目录占位
        val withUpDir = if (subDirs.isNotEmpty()) {
            listOf(IosImportBook(subDirs.last(), isUpDir = true)) + rawItems
        } else {
            rawItems
        }
        items = applyFilterAndSort(withUpDir)
        selected = emptySet()
        loading = false
        emptyMsgVisible = items.isEmpty()
    }

    /** 更新面包屑路径 (对照 ImportBookActivity.upDocs 内 showBreadcrumb) */
    fun updatePath(rootName: String) {
        var p = rootName + "/"
        subDirs.forEach { p += it.name + "/" }
        path = p
    }

    /** 切换根目录 (选择目录后调用), rootUrl 需要先 startAccessingSecurityScopedResource */
    fun initRoot(url: NSURL, rootName: String) {
        subDirs.clear()
        rootUrl = url
        // 解锁安全作用域后取 path (iOS 沙盒外目录需 unlock)
        val ok = url.startAccessingSecurityScopedResource()
        if (!ok) {
            AppLog.put(iosUnlockScopeFailedAccessSelectedDirLog)
            Toasters.get().toast(cannotAccessSelectedDirText)
            return
        }
        val p = url.path()
        rootPath = p
        // 末尾 stopAccessingSecurityScopedResource 在 loadDir 完成后调用 (见 scope.launch 块)
        scope.launch {
            try {
                if (p != null) loadDir(File(p))
                updatePath(rootName)
            } finally {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    /** 进入子目录 (subDirs 加入 + 重新 loadDir) */
    fun nextDir(dir: File) {
        subDirs.add(dir)
        rootPath?.let { updatePath(File(it).name) }
        // 子目录访问沿用 rootUrl 安全作用域, 需重新 unlock
        val url = rootUrl ?: return
        val ok = url.startAccessingSecurityScopedResource()
        if (!ok) {
            AppLog.put(iosUnlockScopeFailedAccessSubdirLog)
            return
        }
        scope.launch {
            try {
                loadDir(dir)
            } finally {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    /** 返回上级目录, 返回 false 表示已在根目录 (供 onBack 判断) */
    fun goBackDir(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        val target = subDirs.lastOrNull() ?: rootPath?.let { File(it) } ?: return false
        val url = rootUrl ?: return false
        val ok = url.startAccessingSecurityScopedResource()
        if (!ok) {
            AppLog.put(iosUnlockScopeFailedAccessParentDirLog)
            return true
        }
        rootPath?.let { updatePath(File(it).name) }
        scope.launch {
            try {
                loadDir(target)
            } finally {
                url.stopAccessingSecurityScopedResource()
            }
        }
        return true
    }

    /** 扫描当前文件夹及所有子文件夹 (对照 ImportBookViewModel.scanDoc) */
    fun scanFolder() {
        val root = rootPath ?: return
        val start = subDirs.lastOrNull()?.absolutePath ?: root
        val url = rootUrl ?: return
        val ok = url.startAccessingSecurityScopedResource()
        if (!ok) {
            AppLog.put(iosUnlockScopeFailedScanDirLog)
            return
        }
        scope.launch {
            try {
                loading = true
                items = emptyList()
                val collected = withContext(Dispatchers.Default) {
                    val result = mutableListOf<IosImportBook>()
                    val stack = ArrayDeque<File>()
                    stack.addLast(File(start))
                    val dao = AppDbProviders.get().bookDao
                    while (stack.isNotEmpty()) {
                        val cur = stack.removeLast()
                        val children = cur.listFiles() ?: continue
                        children.forEach { f ->
                            when {
                                f.name.startsWith(".") -> Unit
                                f.isDirectory -> stack.addLast(f)
                                f.name.matches(bookFileRegex) || f.name.matches(archiveFileRegex) ->
                                    result.add(
                                        IosImportBook(f, isOnBookShelf = dao.hasFile(f.name))
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
            } finally {
                url.stopAccessingSecurityScopedResource()
            }
        }
    }

    /** 弹 UIDocumentPickerViewController 选导入根目录 (替代 app 端 selectFolder.launch) */
    fun pickFolder() {
        scope.launch {
            val chosen = pickDirectory() ?: run {
                AppLog.put(iosUserCancelSelectImportDirLog)
                return@launch
            }
            // rootName 取 NSURL 最后一段路径 (file:///var/mobile/.../Books → "Books")
            val rootName = chosen.lastPathComponent ?: "root"
            initRoot(chosen, rootName)
        }
    }

    /** 弹 AlertDialog 输入文件名导入 js (替代 app 端 alertImportFileName) */
    fun alertImportFileName() {
        importFileNameJsText = ""
        showImportFileNameDialog = true
    }

    // ---- UiActions 实现 (remember 持有稳定实例) ----
    val actions = remember {
        object : ImportBookUiActions<IosImportBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                if (!goBackDir()) onBackUpdated.value.invoke()
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                rootPath?.let { rp ->
                    val target = subDirs.lastOrNull() ?: File(rp)
                    val url = rootUrl ?: return
                    val ok = url.startAccessingSecurityScopedResource()
                    if (!ok) return
                    scope.launch {
                        try {
                            loadDir(target)
                        } finally {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }
                }
            }

            override fun onPickFolder() = pickFolder()

            override fun onUpSort(sort: Int) {
                sortState = sort
                rootPath?.let { rp ->
                    val target = subDirs.lastOrNull() ?: File(rp)
                    val url = rootUrl ?: return
                    val ok = url.startAccessingSecurityScopedResource()
                    if (!ok) return
                    scope.launch {
                        try {
                            loadDir(target)
                        } finally {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }
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
                // 导入本地书籍: 走 FileBook.importLocalFile 门面 (FileBookProviders 已注册,
                // epub 走 nativeMain EpubFile 真实解析; txt/pdf/cbz 未下沉, 抛明确异常记日志跳过该项)
                val books = selected.toHashSet()
                scope.launch {
                    withContext(Dispatchers.Default) {
                        books.forEach { item ->
                            if (item.file.length() == 0L) return@forEach
                            // bookUrl 用 file:// URL 形式 (与 IosBookshelfScreen.onAddLocalBook 一致,
                            // NativeLocalBookLocator.parseLocalPath 会剥离 file:// 前缀取 POSIX 路径)
                            val bookUrl = "file://" + item.file.absolutePath
                            runCatching {
                                FileBook.importLocalFile(bookUrl)
                            }.onSuccess {
                                item.isOnBookShelf = true
                            }.onFailure { e ->
                                AppLog.put("导入本地书籍失败: ${item.file.name}\n${e.localizedMessage}", e)
                            }
                        }
                    }
                    selected = emptySet()
                    refreshTick++
                }
            }

            override fun onItemClick(item: IosImportBook) {
                when {
                    item.isUpDir -> goBackDir()
                    item.isDir -> nextDir(item.file)
                    !item.isOnBookShelf -> {
                        // 切换选中 (对照 ImportBookActivity.toggleSelect)
                        selected = if (item in selected) selected - item else selected + item
                    }
                    else -> {
                        // TODO: app 端 startRead(item.file) 依赖 startActivityForBook +
                        //  ArchiveUtils, 未下沉, iOS 端暂不实现打开阅读
                    }
                }
            }

            override fun onItemLongClick(item: IosImportBook) {
                // TODO: app 端 startRead(fileDoc) 依赖 ArchiveUtils, 未下沉, iOS 端暂 no-op
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

    // ---- AlertDialog 渲染 (与 desktop 一致) ----
    // 文件名导入 js 输入对话框 (alertImportFileName 触发 showImportFileNameDialog=true;
    //   确认按钮调 AppLog.put 记录输入, 复刻原 `js ?: return` + AppLog.put 语义)
    if (showImportFileNameDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportFileNameDialog = false },
            title = { Text(filenameImportJsTitleLabel) },
            text = {
                AppTextField(
                    value = importFileNameJsText,
                    onValueChange = { importFileNameJsText = it },
                    label = filenameImportJsSummaryLabel,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val js = importFileNameJsText
                    showImportFileNameDialog = false
                    // TODO: app 端写 AppConfig.bookImportFileName, iOS 端 AppConfig 是 Android 扩展,
                    //  暂不持久化; 后续接入 iOS 端 PreferenceStore 后补全
                    AppLog.put(sharedStringTable["ios_filename_import_js_input_log"]!!.formatNative(js.take(50)))
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showImportFileNameDialog = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}
