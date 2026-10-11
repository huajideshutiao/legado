package io.legado.desktop.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportBookDirHost
import io.legado.app.ui.book.import.ImportDirRef
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBookShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * 桌面端本地书导入薄注入。
 *
 * 目录状态机 (目录栈/面包屑/过滤/排序/扫描/空态) 在 data/commonMain [ImportBookShared]
 * (四端一份); 本 object 只注入 java.io.File 的列举/条目化能力并保留桌面上架语义
 * (上架只引用原文件路径, 不复制进沙盒)。
 */
object DesktopImportBook {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val items get() = ImportBookShared.items
    val path get() = ImportBookShared.path
    val loading get() = ImportBookShared.loading
    val emptyMsgVisible get() = ImportBookShared.emptyMsgVisible
    val hasParentDir get() = ImportBookShared.hasParentDir

    /** 是否还没选定导入根目录 (页面进入后据此自动拉起选择器)。 */
    val needsRootDir get() = ImportBookShared.needsRootDir

    /** 已上架判定缓存 (一次查询供整页条目判定, key = bookUrl 绝对路径) */
    @Volatile
    private var shelfKeys: Set<String> = emptySet()

    private suspend fun refreshShelfKeys() {
        shelfKeys = runCatching {
            AppDbProviders.get().bookDao.all().mapTo(HashSet()) { it.bookUrl }
        }.getOrDefault(emptySet())
    }

    /** File 的目录引用包装 (状态机经它列举/条目化; 已上架判定读 [shelfKeys] 缓存) */
    private class DesktopImportDirRef(
        val dir: File,
        private val shelfKeysProvider: () -> Set<String>,
    ) : ImportDirRef {
        override val displayName: String get() = dir.name
        override val isDirectory: Boolean get() = dir.isDirectory
        override val pathKey: String get() = dir.absolutePath

        override suspend fun listChildren(): List<ImportDirRef>? =
            dir.listFiles()?.map { DesktopImportDirRef(it, shelfKeysProvider) }

        override suspend fun toItem(isUpDir: Boolean): ImportFileItem =
            DesktopImportFile(
                dir,
                isUpDir = isUpDir,
                isOnBookShelf = !isUpDir && shelfKeysProvider().contains(dir.absolutePath),
            )
    }

    private val importDirHost = object : ImportBookDirHost {
        override fun dirRefOf(item: ImportFileItem): ImportDirRef? =
            (item as? DesktopImportFile)?.takeIf { it.file.isDirectory }
                ?.let { DesktopImportDirRef(it.file) { shelfKeys } }

        override suspend fun resolveRoot(pathKey: String): ImportDirRef? {
            if (pathKey.isBlank()) return null
            val dir = File(pathKey)
            if (!dir.isDirectory) return null
            return DesktopImportDirRef(dir) { shelfKeys }
        }
    }

    /** 对照 ImportBookActivity.initRootDoc: 按偏好恢复上次目录与排序设置。 */
    suspend fun init() {
        ImportBookShared.attach(importDirHost)
        refreshShelfKeys()
        ImportBookShared.initPage()
    }

    fun setRoot(dir: File) {
        ImportBookShared.setRoot(DesktopImportDirRef(dir) { shelfKeys })
    }

    fun enterDir(item: ImportFileItem) {
        ImportBookShared.navigateImportDir(item)
    }

    /** 返回上级; 已在根目录返回 false (对照 goBackDir)。 */
    fun goBack(): Boolean = ImportBookShared.goBackImportDir()

    fun updateFilter(key: String) {
        ImportBookShared.updateImportBookFilter(key)
    }

    fun updateSort(value: Int) {
        ImportBookShared.updateImportBookSort(value)
    }

    /** 平铺扫描当前目录及子目录下的书籍文件 (对照 ImportBookViewModel.scanDoc); 限深 8 层。 */
    fun scan() {
        scope.launch { refreshShelfKeys() }
        ImportBookShared.scanImportFolder(maxDepth = 8)
    }

    /**
     * 上架选中条目 (对照 ImportBookViewModel.addToBookshelf)。
     *
     * [onNeedBooksDir] 供"压缩包导入需要书籍保存位置而尚未设置"的场景接到"书籍保存位置"
     * 出口 (与"其它设置 → 书籍保存位置"同一选择器/同一偏好键), 返回 true 再重试该条导入;
     * 返回 false (用户取消) 或传 null 时按导入出错记日志。普通书文件导入不经它。
     */
    fun addToBookshelf(
        selection: List<ImportFileItem>,
        onComplete: () -> Unit,
        onNeedBooksDir: (suspend () -> Boolean)? = null,
    ) {
        scope.launch {
            selection.filterIsInstance<DesktopImportFile>().forEach { item ->
                runCatching { importOne(item, onNeedBooksDir) }
                    .onSuccess { item.isOnBookShelf = true }
                    .onFailure { AppLog.put("导入 ${item.name} 出错\n${it.message}", it) }
            }
            refreshShelfKeys()
            ImportBookShared.refresh()
            onComplete()
        }
    }

    /** 导入单个条目; 保存位置未设置时先选目录再重试一次 (其余导入异常照旧上抛)。 */
    private suspend fun importOne(
        item: DesktopImportFile,
        onNeedBooksDir: (suspend () -> Boolean)?,
    ) {
        try {
            FileBook.importLocalFile(item.file.absolutePath)
        } catch (e: InvalidBooksDirException) {
            if (onNeedBooksDir?.invoke() != true) throw e
            FileBook.importLocalFile(item.file.absolutePath)
        }
    }
}
