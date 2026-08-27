package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * iOS/鸿蒙本地书条目 (对照 app 端 [io.legado.app.ui.book.import.local.ImportBook] /
 * desktop 端 DesktopImportFile, 用 nativeMain 的 okio [File] 替代 FileDoc/java.io.File)。
 */
class NativeImportFile(
    val file: File,
    override val isUpDir: Boolean = false,
    override var isOnBookShelf: Boolean = false,
) : ImportFileItem {
    override val name: String = if (isUpDir) ".." else file.name
    override val isDir: Boolean = isUpDir || file.isDirectory
    override val size: Long = if (file.isDirectory) 0L else file.length()
    override val lastModified: Long = file.lastModified()
    override val tag: String = file.name.substringAfterLast(".")
    override val itemKey: Any = file.absolutePath
}

/**
 * iOS/鸿蒙本地书导入状态 (对照 app 端 `ImportBookActivity` + `ImportBookViewModel`,
 * 代码结构对齐 desktop 端 DesktopImportBook)。
 *
 * # 与 desktop 的差异
 * - **无 SAF**: 目录遍历直接用 nativeMain okio [File] (listFiles/walkTopDown), 不做
 *   DocumentFile 抽象 —— iOS/鸿蒙选目录入口 (UIDocumentPicker / DocumentViewPicker)
 *   把选中目录归一成 POSIX 路径后调 [setRoot];
 * - **沙盒语义**: desktop 上架只引用原文件路径, 移动端外部目录无持久授权
 *   (iOS security-scoped URL 重启失效 / 鸿蒙 picker URI 跨会话不可靠), 故上架前把
 *   普通书文件**复制**进 `{filesDir}/books`, bookUrl 指向沙盒内副本, 保证重启后仍可读;
 * - **压缩包**: 无压缩包内选章阅读 UI, openReader 走 [NativeFileAssociationDispatch]
 *   的直接导入链 (对照 Android startRead 的 onArchiveFileClick 分支, 平台能力上限)。
 *
 * # 平台限制 (不假装与 Android 等价)
 * - iOS 只能浏览用户经 UIDocumentPicker 显式授权的目录 (Open 模式, security-scoped),
 *   且授权不跨重启 —— 冷启动后 [init] 不恢复上次目录, 需重新选择 (restoreLast=false);
 * - 鸿蒙目录经 picker URI 归一为 POSIX 路径, 能否直接读取决于桥接层对 `file://docs`
 *   前缀的折回 (与备份路径同一约定), 桥接未就绪时降级返回 null。
 */
object NativeImportBook {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val _items = MutableStateFlow<List<ImportFileItem>>(emptyList())
    private val _path = MutableStateFlow<String?>(null)
    private val _loading = MutableStateFlow(false)
    private val _emptyMsgVisible = MutableStateFlow(false)

    val items: StateFlow<List<ImportFileItem>> = _items.asStateFlow()
    val path: StateFlow<String?> = _path.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val emptyMsgVisible: StateFlow<Boolean> = _emptyMsgVisible.asStateFlow()

    private var rootDir: File? = null
    private val subDirs = mutableListOf<File>()
    private var filterKey: String = ""
    private var sort: Int = 0
    private var loadJob: Job? = null

    private val prefs get() = PreferenceProviders.get()

    /** 上次导入目录的 pref key (对照 app 端 AppConfig.importBookPath / desktop 同名 key)。 */
    private const val KEY_IMPORT_BOOK_PATH = "importBookPath"

    /** 当前所在目录 (未选根目录时为 null)。 */
    private fun currentDir(): File? = subDirs.lastOrNull() ?: rootDir

    /**
     * 初始化导入页状态。
     *
     * @param restoreLast 是否恢复上次目录; iOS 传 false —— security-scoped URL 跨重启
     *   不可恢复, 冷启动一律让用户重新选择 (苹果平台能力上限)。
     */
    fun init(restoreLast: Boolean = true) {
        // 排序预存值 (对照 shared 路由读 AppConfig.localBookImportSort 展示勾选项;
        // 桌面参考实现初始为 0, 这里对齐预存值避免"UI 显示时间排序但列表按名称"的不一致)
        sort = prefs.getInt(PreferKey.localBookImportSort, 0)
        if (rootDir != null) {
            // 本次会话已选过根目录 (重进导入页), 直接刷新当前目录
            reload()
            return
        }
        val last = if (restoreLast) prefs.getString(KEY_IMPORT_BOOK_PATH, "") else ""
        if (last.isNotEmpty() && File(last).isDirectory) {
            setRoot(last)
        } else {
            _emptyMsgVisible.value = true
        }
    }

    /** 切换导入根目录 (对照 Android initRootPath / desktop setRoot)。 */
    fun setRoot(dir: String) {
        val file = File(dir)
        rootDir = file
        subDirs.clear()
        prefs.putString(KEY_IMPORT_BOOK_PATH, file.path)
        reload()
    }

    /** 进入子目录 (对照 Android nextDoc)。 */
    fun enterDir(item: ImportFileItem) {
        val file = (item as? NativeImportFile)?.file ?: return
        if (!file.isDirectory) return
        subDirs.add(file)
        reload()
    }

    /** 返回上级; 已在根目录返回 false (对照 Android goBackDir)。 */
    fun goBack(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        reload()
        return true
    }

    /** 更新搜索过滤关键字 (对照 Android updateCallBackFlow)。 */
    fun updateFilter(key: String) {
        filterKey = key
        reload()
    }

    /** 更新排序方式并持久化 (对照 Android upSort)。 */
    fun updateSort(value: Int) {
        sort = value
        prefs.putInt(PreferKey.localBookImportSort, value)
        reload()
    }

    /** 平铺扫描当前目录及子目录下的书籍文件 (对照 ImportBookViewModel.scanDoc)。 */
    fun scan() {
        val dir = currentDir() ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            val found = mutableListOf<File>()
            runCatching {
                // 与 ImportBookViewModel.scanDoc 一致: 书籍文件 + 压缩包都算扫描结果
                // (desktop scan 只认 bookFileRegex 会漏掉 zip/rar, native 补齐)。
                // maxDepth 防误选系统根目录后无限遍历 (不用 Sequence.maxDepth:
                // 该扩展在 K/N 标准库是否可用不明确, 手动层数限制更稳)。
                collectBookFiles(dir, 8, found)
            }.onFailure { AppLog.put("扫描本地书目录失败\n${it.message}", it) }
            publish(found, withUpDir = false)
            _loading.value = false
        }
    }

    /** 深度受限收集书籍/压缩包文件 (目录不含书架文件, 直接递归)。 */
    private fun collectBookFiles(dir: File, depth: Int, out: MutableList<File>) {
        if (depth <= 0) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectBookFiles(child, depth - 1, out)
            } else if (FileBook.isBookFile(child.name)
                || AppPattern.archiveFileRegex.matches(child.name)
            ) {
                out += child
            }
        }
    }

    private fun reload() {
        val dir = currentDir()
        if (dir == null) {
            _items.value = emptyList()
            _path.value = null
            _emptyMsgVisible.value = true
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            // 面包屑路径 (对照 Android upDocs: 根目录名 + 各级子目录名 + 分隔符),
            // 比 desktop 的绝对路径更贴近原版展示, 且不暴露 iOS 冗长的沙盒路径
            _path.value = (listOf(rootDir!!.name) + subDirs.map { it.name })
                .joinToString("/") + "/"
            val children = runCatching {
                dir.listFiles()?.filter {
                    it.isDirectory || FileBook.isBookFile(it.name) ||
                        AppPattern.archiveFileRegex.matches(it.name)
                } ?: emptyList()
            }.getOrElse {
                AppLog.put("读取本地书目录失败\n${it.message}", it)
                emptyList()
            }
            publish(children, withUpDir = subDirs.isNotEmpty())
            _loading.value = false
        }
    }

    /**
     * 发布当前目录列表 (过滤 + 排序 + 已上架标记)。
     *
     * 已上架判定按**文件名** (对照 Android `ImportBook.isOnBookShelf` 的
     * `bookDao.hasFile(file.name)`): native 上架是复制进沙盒, 文件名即副本名,
     * 与 desktop 按 bookUrl 路径判定不同 —— 外部目录里的源文件路径不会等于副本路径。
     */
    private suspend fun publish(files: List<File>, withUpDir: Boolean) {
        val names = runCatching {
            AppDbProviders.get().bookDao.all().mapTo(HashSet()) { it.originName }
        }.getOrDefault(hashSetOf())
        val skipFilter = filterKey.isBlank()
        // 排序规则与 app 端 ImportBookViewModel 一致: 目录优先, 再按 sort, 最后按字母数字混合序
        val comparator = when (sort) {
            2 -> compareBy<ImportFileItem>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        }.then(compareBy(AlphanumComparator) { it.name })
        val list = files.asSequence()
            .filter { skipFilter || it.name.contains(filterKey) }
            .map { NativeImportFile(it, isOnBookShelf = names.contains(it.name)) }
            .sortedWith(comparator)
            .toMutableList<ImportFileItem>()
        if (withUpDir) {
            currentDir()?.parentFile?.let { list.add(0, NativeImportFile(it, isUpDir = true)) }
        }
        _items.value = list
        _emptyMsgVisible.value = list.isEmpty()
    }

    /** 上架选中条目 (对照 ImportBookViewModel.addToBookshelf)。 */
    fun addToBookshelf(selection: List<ImportFileItem>, onComplete: () -> Unit) {
        scope.launch {
            selection.filterIsInstance<NativeImportFile>().forEach { item ->
                runCatching {
                    val file = item.file
                    if (file.name.matches(AppPattern.archiveFileRegex)) {
                        // 压缩包 (zip 内含书籍文件可解压导入; rar/7z native 端无解析器,
                        // importLocalFile 抛明确异常, 不静默跳过) —— 解压产物已落 {filesDir}/books
                        FileBook.importLocalFile(file.path)
                    } else {
                        // 普通书文件: 先复制进沙盒再导入 (见类注释"沙盒语义")
                        FileBook.importLocalFile(sandboxCopy(file).path)
                    }
                    item.isOnBookShelf = true
                }.onFailure { e ->
                    AppLog.put("导入 ${item.name} 出错\n${e.message}", e)
                    Toasters.get().toast("导入 ${item.name} 出错\n${e.message}")
                }
            }
            reload()
            onComplete()
        }
    }

    /**
     * 打开已上架书籍阅读页 (对照 Android startRead)。
     *
     * - 压缩包: native 无压缩包内选章阅读 UI, 走 [NativeFileAssociationDispatch]
     *   (解压 → 导入 → 打开首个书籍文件), 与文件关联导入同链;
     * - 普通书: 按文件名查库, 未入库直接返回 (与 Android `getBookByFileName?.let` 行为一致);
     *   入库后确保书文件在沙盒内 (旧数据引用外部目录时复制修正 bookUrl), 再推阅读路由。
     */
    fun openReader(item: ImportFileItem) {
        val file = (item as? NativeImportFile)?.file ?: return
        val fileName = file.name
        if (fileName.matches(AppPattern.archiveFileRegex)) {
            NativeFileAssociationDispatch.dispatch(file.path)
            return
        }
        scope.launch {
            val bookDao = AppDbProviders.get().bookDao
            val book = bookDao.getBookByFileName(fileName) ?: return@launch
            val saved = runCatching { sandboxCopy(file) }.getOrElse { return@launch }
            val bookUrl = "file://" + saved.absolutePath
            if (book.bookUrl != bookUrl) {
                book.bookUrl = bookUrl
                runCatching { bookDao.update(book) }
            }
            AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
        }
    }

    /**
     * 复制书文件到沙盒 books 目录 (对齐 NativeFileBookAccessor.booksDir)。
     * 同名直接覆盖 (与 FileBook 重复导入的覆写语义一致); 源已在目标目录则跳过。
     */
    private fun sandboxCopy(src: File): File {
        val dir = File(AppFilesDirs.get().filesDir, "books")
        dir.mkdirs()
        val dest = File(dir, src.name)
        if (dest.absolutePath != src.absolutePath) {
            src.copyTo(dest, overwrite = true)
        }
        return dest
    }
}
