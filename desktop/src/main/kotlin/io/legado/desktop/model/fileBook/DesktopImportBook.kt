package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.utils.AlphanumComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 桌面端本地书条目 (对照 app 端 ImportBook, 用 [File] 替代 FileDoc)。 */
class DesktopImportFile(
    val file: File,
    override val isUpDir: Boolean = false,
    override var isOnBookShelf: Boolean = false,
) : ImportFileItem {
    override val name: String = if (isUpDir) ".." else file.name
    override val isDir: Boolean = isUpDir || file.isDirectory
    override val size: Long = if (file.isDirectory) 0L else file.length()
    override val lastModified: Long = file.lastModified()
    override val tag: String = file.absolutePath
    override val itemKey: Any = file.absolutePath
}

/**
 * 桌面端本地书导入状态 (对照 app 端 `ImportBookActivity` + `ImportBookViewModel`)。
 *
 * app 端用 SAF DocumentFile 遍历目录, 桌面端直接用 [File]; 上架走已下沉的
 * [FileBook.importLocalFile] (desktop actual = DesktopFileBookAccessor)。
 */
object DesktopImportBook {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** 上次导入目录的 pref key (对照 app 端 AppConfig.importBookPath, PreferKey 未收录)。 */
    private const val KEY_IMPORT_BOOK_PATH = "importBookPath"

    /** 当前所在目录 (未选根目录时为 null)。 */
    private fun currentDir(): File? = subDirs.lastOrNull() ?: rootDir

    /** 对照 ImportBookActivity.initRootDoc: 按 pref 恢复上次目录。 */
    fun init() {
        if (rootDir != null) {
            reload()
            return
        }
        val last = prefs.getString(KEY_IMPORT_BOOK_PATH, "")
        if (last.isNotEmpty() && File(last).isDirectory) {
            setRoot(File(last))
        } else {
            _emptyMsgVisible.value = true
        }
    }

    fun setRoot(dir: File) {
        rootDir = dir
        subDirs.clear()
        prefs.putString(KEY_IMPORT_BOOK_PATH, dir.absolutePath)
        reload()
    }

    fun enterDir(item: ImportFileItem) {
        val file = (item as? DesktopImportFile)?.file ?: return
        if (!file.isDirectory) return
        subDirs.add(file)
        reload()
    }

    /** 返回上级; 已在根目录返回 false (对照 goBackDir)。 */
    fun goBack(): Boolean {
        if (subDirs.isEmpty()) return false
        subDirs.removeAt(subDirs.lastIndex)
        reload()
        return true
    }

    fun updateFilter(key: String) {
        filterKey = key
        reload()
    }

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
            runCatching { dir.walkTopDown().maxDepth(8).forEach { if (it.isFile && FileBook.isBookFile(it.name)) found += it } }
                .onFailure { AppLog.put("扫描本地书目录失败\n${it.message}", it) }
            publish(found, withUpDir = false)
            _loading.value = false
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
            _path.value = dir.absolutePath
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

    private suspend fun publish(files: List<File>, withUpDir: Boolean) {
        val bookDao = AppDbProviders.get().bookDao
        val onShelf = runCatching { bookDao.all().mapTo(HashSet()) { it.bookUrl } }.getOrDefault(hashSetOf())
        val skipFilter = filterKey.isBlank()
        // 排序规则与 app 端 ImportBookViewModel 一致: 目录优先, 再按 sort, 最后按字母数字混合序
        val comparator = when (sort) {
            2 -> compareBy<ImportFileItem>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        }.then(compareBy(AlphanumComparator) { it.name })
        val list = files.asSequence()
            .filter { skipFilter || it.name.contains(filterKey) }
            .map { DesktopImportFile(it, isOnBookShelf = onShelf.contains(it.absolutePath)) }
            .sortedWith(comparator)
            .toMutableList<ImportFileItem>()
        if (withUpDir) {
            currentDir()?.parentFile?.let { list.add(0, DesktopImportFile(it, isUpDir = true)) }
        }
        _items.value = list
        _emptyMsgVisible.value = list.isEmpty()
    }

    /** 上架选中条目 (对照 ImportBookViewModel.addToBookshelf)。 */
    fun addToBookshelf(selection: List<ImportFileItem>, onComplete: () -> Unit) {
        scope.launch {
            selection.filterIsInstance<DesktopImportFile>().forEach { item ->
                runCatching { FileBook.importLocalFile(item.file.absolutePath) }
                    .onSuccess { item.isOnBookShelf = true }
                    .onFailure { AppLog.put("导入 ${item.name} 出错\n${it.message}", it) }
            }
            reload()
            onComplete()
        }
    }
}
