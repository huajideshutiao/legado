package io.legado.app.ui.book.import.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
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
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBookScreen as SharedImportBookScreen
import io.legado.app.ui.book.import.local.ImportBookUiActions
import io.legado.app.ui.book.import.local.ImportBookUiState
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.sharedStringTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.File

/**
 * 鸿蒙端导入本地书籍 Screen 入口 (包装 shared/sharedUiMain 的 [SharedImportBookScreen])。
 *
 * # 职责
 *
 * 对照 app 端 `ImportBookActivity` / desktop `ImportBookScreen.kt` / iOS `IosImportBookScreen.kt`,
 * 鸿蒙端仅做平台适配, UI 渲染与交互骨架全部下沉到 shared/sharedUiMain 的 [SharedImportBookScreen]:
 *
 * - **数据流**: 持有 [ImportBookUiState] (immutable, copy 更新), actions 用 [remember]
 *   持有稳定实例避免重组 (与 desktop/iOS 一致; 鸿蒙 Provider 已在 EntryAbility 顶层注入,
 *   不需要再嵌套 CompositionLocalProvider)
 * - **条目模型**: [OhosImportBook] 基于 [kotlin.io.File] 实现 [ImportFileItem]
 *   (与 desktop `DesktopImportBook` / iOS `IosImportBook` 同模式; Kotlin/Native 直接可用 [File])
 *
 * # 平台差异 (鸿蒙文件系统)
 *
 * - **文件选择器 (SAF)**: app 端 `registerHandleFile` 走 Storage Access Framework,
 *   desktop 端用 JFileChooser, iOS 端用 UIDocumentPickerViewController, 鸿蒙端需用
 *   `picker.Selector` (ohos.file.picker) 桥接; 当前 [pickDirectory] 是 stub, 后续接入
 * - **目录枚举**: 鸿蒙端沙盒内可直用 [File.listFiles]; 沙盒外目录需通过 ohos 安全授权
 *   (ohos.permission.READ_FILESYSTEM) 后访问, 当前实现仅支持沙盒内目录
 * - **路径持久化**: app 端 `AppConfig.importBookPath` 是 Android 扩展, 鸿蒙端每次启动需重新选择目录
 *   (与 desktop/iOS 一致)
 *
 * # 简化项 (依赖未下沉功能, 用 TODO 注释 + no-op)
 *
 * - **加入书架**: 走 `FileBook.importLocalFile` 门面 (FileBookProviders 已注册);
 *   epub 走 nativeMain EpubFile 真实解析, txt/pdf/cbz 未下沉抛明确异常记日志跳过
 * - **打开书籍**: app 端 `startReadBook` / `onArchiveFileClick` 依赖
 *   `startActivityForBook` + `ArchiveUtils`, 未下沉, onItemClick/onItemLongClick no-op
 * - **文件名导入 js**: app 端 `alert` 弹窗, 鸿蒙端用 [AlertDialog] + [OutlinedTextField] 替代
 *   (与 desktop 一致; 暂不持久化到 AppConfig, 因 AppConfig.bookImportFileName 是 Android 扩展)
 *
 * @param onBack 返回回调 (由 OhosNavHost 注入, 切回 BOOKSHELF 路由)
 */
@Composable
fun OhosImportBookScreen(onBack: () -> Unit) {
    OhosImportBookContent(onBack = onBack)
}

/**
 * 鸿蒙端导入本地书籍条目模型 (替代 app 端 ImportBook; 与 iOS `IosImportBook` 字段一致)。
 *
 * app 端 [io.legado.app.ui.book.import.local.ImportBook] 基于 `FileDoc` (Android SAF),
 * FileDoc 未下沉到 commonMain, 鸿蒙端用 [kotlin.io.File] 替代 (与 iOS 端一致):
 * - [tag] 取文件名后缀 (与 app 端 ImportBook.tag 一致)
 * - [itemKey] 取 [file.getAbsolutePath] (与 app 端 ImportBook.itemKey 取 `file.toString()` 一致)
 * - [lastModified] 桥接 [File.lastModified]
 * - [isOnBookShelf] 由扫描时查询 `bookDao.hasFile(name)` 填充, 加入书架后原地变更为 true
 */
private data class OhosImportBook(
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
private fun OhosImportBookContent(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 持有最新 onBack 引用, 避免 object 内 override fun onBack() 名称遮蔽导致递归调用
    val onBackUpdated = rememberUpdatedState(onBack)

    // js 导入文案 (局部函数非 @Composable, 需预先缓存)
    val filenameImportJsTitleLabel = rememberString("filename_import_js_title")
    val filenameImportJsSummaryLabel = rememberString("filename_import_js_summary")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 文案模板 (initRoot/pickFolder lambda 非 @Composable, 预先 remember)
    val ohosPickDirFailedLog = rememberString("ohos_pick_dir_failed_log")
    val ohosUserCancelSelectImportDirLog = rememberString("ohos_user_cancel_select_import_dir_log")

    // 文件名导入 js 输入对话框状态 (alertImportFileName 触发显示, 末尾 AlertDialog 渲染分支读取,
    // 确认按钮调 AppLog.put 记录输入)
    var showImportFileNameDialog by remember { mutableStateOf(false) }
    var importFileNameJsText by remember { mutableStateOf("") }

    // 列表条目 (含上级目录占位)
    var items by remember { mutableStateOf<List<OhosImportBook>>(emptyList()) }
    // 选中集 (与 app 端一致, 每次切目录时清空)
    var selected by remember { mutableStateOf<Set<OhosImportBook>>(emptySet()) }
    // 上架标记原地变更后 +1 强制列表重组 (对照 ImportBookActivity.refreshTick)
    var refreshTick by remember { mutableIntStateOf(0) }
    // 当前根目录本地路径 (null 时显示空态提示选择目录)
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

    fun isCheckable(item: OhosImportBook): Boolean =
        !item.isUpDir && !item.isDir && !item.isOnBookShelf

    val checkableCount = items.count { isCheckable(it) }

    /** 过滤 + 排序 (对照 ImportBookViewModel.dataFlow 的 map 分支) */
    fun applyFilterAndSort(all: List<OhosImportBook>): List<OhosImportBook> {
        val skipFilter = searchKey.isBlank()
        // 排序: 目录优先 (isDir=false 排前), 然后按 sortState
        val comparator = when (sortState) {
            2 -> compareBy<OhosImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy<OhosImportBook>({ !it.isDir }, { -it.size })
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
     * 鸿蒙端沙盒内目录可直接访问, 沙盒外目录需通过 ohos 安全授权 (暂不支持)
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
                OhosImportBook(f, isOnBookShelf = onShelf)
            }
        }
        // 子目录栈非空时插入上级目录占位
        val withUpDir = if (subDirs.isNotEmpty()) {
            listOf(OhosImportBook(subDirs.last(), isUpDir = true)) + rawItems
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

    /** 切换根目录 (选择目录后调用), 鸿蒙端沙盒内目录直访, 无需安全作用域 unlock */
    fun initRoot(rootFile: File, rootName: String) {
        subDirs.clear()
        rootPath = rootFile.absolutePath
        scope.launch {
            loadDir(rootFile)
            updatePath(rootName)
        }
    }

    /** 进入子目录 (subDirs 加入 + 重新 loadDir) */
    fun nextDir(dir: File) {
        subDirs.add(dir)
        rootPath?.let { updatePath(File(it).name) }
        scope.launch { loadDir(dir) }
    }

    /** 返回上级目录, 返回 false 表示已在根目录 (供 onBack 判断) */
    fun goBackDir(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        val target = subDirs.lastOrNull() ?: rootPath?.let { File(it) } ?: return false
        rootPath?.let { updatePath(File(it).name) }
        scope.launch { loadDir(target) }
        return true
    }

    /** 扫描当前文件夹及所有子文件夹 (对照 ImportBookViewModel.scanDoc) */
    fun scanFolder() {
        val root = rootPath ?: return
        val start = subDirs.lastOrNull()?.absolutePath ?: root
        scope.launch {
            loading = true
            items = emptyList()
            val collected = withContext(Dispatchers.Default) {
                val result = mutableListOf<OhosImportBook>()
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
                                    OhosImportBook(f, isOnBookShelf = dao.hasFile(f.name))
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

    /**
     * 弹 ohos 文件选择器选导入根目录 (替代 app 端 selectFolder.launch)。
     *
     * TODO: 鸿蒙端 ohos.file.picker.Selector 桥接未实现, 当前为 stub 返回 null;
     * 后续接入 EntryAbility 桥接 (tsfn 回调 ArkTS 弹 picker) 后补全
     */
    suspend fun pickDirectory(): File? {
        // TODO: 接入 ohos.file.picker.Selector (FolderSelectMode), 通过 tsfn 桥接 ArkTS 弹选择器
        //  当前 stub, 直接返回 null 走用户取消分支
        AppLog.put(ohosPickDirFailedLog)
        return null
    }

    fun pickFolder() {
        scope.launch {
            val chosen = pickDirectory() ?: run {
                AppLog.put(ohosUserCancelSelectImportDirLog)
                return@launch
            }
            // rootName 取目录最后一段路径
            val rootName = chosen.name.ifEmpty { "root" }
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
        object : ImportBookUiActions<OhosImportBook> {
            override fun onBack() {
                // 优先返回上级目录, 已在根目录时回调外层 onBack
                if (!goBackDir()) onBackUpdated.value.invoke()
            }

            override fun onUpSearchKey(key: String) {
                searchKey = key
                rootPath?.let { rp ->
                    val target = subDirs.lastOrNull() ?: File(rp)
                    scope.launch { loadDir(target) }
                }
            }

            override fun onPickFolder() = pickFolder()

            override fun onUpSort(sort: Int) {
                sortState = sort
                rootPath?.let { rp ->
                    val target = subDirs.lastOrNull() ?: File(rp)
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
                // 导入本地书籍: 走 FileBook.importLocalFile 门面 (FileBookProviders 已注册,
                // epub 走 nativeMain EpubFile 真实解析; txt/pdf/cbz 未下沉, 抛明确异常记日志跳过该项)
                val books = selected.toHashSet()
                scope.launch {
                    withContext(Dispatchers.Default) {
                        books.forEach { item ->
                            if (item.file.length() == 0L) return@forEach
                            // bookUrl 用 file:// URL 形式 (与 iOS 端一致,
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

            override fun onItemClick(item: OhosImportBook) {
                when {
                    item.isUpDir -> goBackDir()
                    item.isDir -> nextDir(item.file)
                    !item.isOnBookShelf -> {
                        // 切换选中 (对照 ImportBookActivity.toggleSelect)
                        selected = if (item in selected) selected - item else selected + item
                    }
                    else -> {
                        // TODO: app 端 startRead(item.file) 依赖 startActivityForBook +
                        //  ArchiveUtils, 未下沉, 鸿蒙端暂不实现打开阅读
                    }
                }
            }

            override fun onItemLongClick(item: OhosImportBook) {
                // TODO: app 端 startRead(fileDoc) 依赖 ArchiveUtils, 未下沉, 鸿蒙端暂 no-op
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
    SharedImportBookScreen(state, actions)

    // ---- AlertDialog 渲染 (与 desktop/iOS 一致; MD2 → MD3 OutlinedTextField, 鸿蒙端无 MD2) ----
    // 文件名导入 js 输入对话框 (alertImportFileName 触发 showImportFileNameDialog=true;
    //   确认按钮调 AppLog.put 记录输入, 复刻原 `js ?: return` + AppLog.put 语义)
    if (showImportFileNameDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showImportFileNameDialog = false },
            title = { Text(filenameImportJsTitleLabel) },
            text = {
                OutlinedTextField(
                    value = importFileNameJsText,
                    onValueChange = { importFileNameJsText = it },
                    label = { Text(filenameImportJsSummaryLabel) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val js = importFileNameJsText
                    showImportFileNameDialog = false
                    // TODO: app 端写 AppConfig.bookImportFileName, 鸿蒙端 AppConfig 是 Android 扩展,
                    //  暂不持久化; 后续接入鸿蒙端 PreferenceStore 后补全
                    AppLog.put(sharedStringTable["ohos_filename_import_js_input_log"]!!.format(js.take(50)))
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
